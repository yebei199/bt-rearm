package io.github.yebei199.btrearm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.input.InputManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.ParcelUuid;
import android.view.InputDevice;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * 安卓侧的转发壳。**这里不做任何判断** —— 谁该连、什么时候连、要不要让路,
 * 全部由 Rust 的 engine 模块决定(那边有单元测试,这边没有)。
 *
 * <p>之所以还需要 Java:安卓的 {@link ScanCallback} 与 {@link BluetoothGattCallback}
 * 是抽象类,JNI 无法从 Rust 实现(动态代理只支持接口),必须有个 Java 子类把事件
 * 转进来。除此之外这个类只提供三种能力:开关扫描、发起连接、查已配对设备。
 *
 * <p>调用方向:
 * <ul>
 *   <li>Rust → Java:{@link #startScan}、{@link #stopScan}、{@link #connect}、
 *       {@link #bondedDevices}、{@link #isConnected}
 *   <li>Java → Rust:{@link #nativeOnAdvertisement}、{@link #nativeOnConnectionChange}
 * </ul>
 */
public final class Rearm {

    static {
        // NativeActivity 加载 libbtrearm 走的是原生侧 dlopen,不会把库登记进
        // 应用的 ClassLoader —— ART 解析下面的 native 方法时按 ClassLoader 找
        // 符号,找不到就抛 UnsatisfiedLinkError。这里从 Java 侧 load 一次完成
        // 登记;NativeActivity 随后的 dlopen 只是同一库的引用计数 +1。
        System.loadLibrary("btrearm");
    }

    private static Context ctx;

    /** 前台保活通知占了 1,这条是提醒用户动手的那条。 */
    private static final int ATTENTION_ID = 2;

    private static final String ATTENTION_CHANNEL = "rearm-attention";

    /** 上一次弹出的正文,用来避免同一句话反复打扰。 */
    private static String lastAttention = "";

    /** HCI 0x13:远端用户主动终止连接。手柄关机或闲置休眠走的就是这个。 */
    private static final int REMOTE_TERMINATED = 0x13;
    /** 当前挂着的 GATT 客户端,按 MAC 存,断开时释放。 */
    private static final Map<String, BluetoothGatt> gatts = new HashMap<>();
    /** 每台设备用于保活的可读特征,服务发现完成后确定。 */
    private static final Map<String, BluetoothGattCharacteristic> keepaliveTarget =
            new HashMap<>();
    private static boolean scanning;
    private static boolean ticking;
    /**
     * 定时重试所在的线程。
     *
     * <p>不能用主线程:重试要走特权连接,那是一次阻塞的跨进程调用(超时 10 秒),
     * 压在主线程上一旦卡住就是界面冻结加 ANR。
     */
    private static final Handler TICKER = newWorkerHandler();

    private static Handler newWorkerHandler() {
        HandlerThread thread = new HandlerThread("rearm-tick");
        thread.start();
        return new Handler(thread.getLooper());
    }
    private static final long TICK_MS = 10_000L;
    private static final UUID HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");

    private Rearm() {}

    public static synchronized void attach(Context c) {
        ctx = c.getApplicationContext();
        // 连接状态改由系统的 ACL 广播提供 —— connect() 把连接交给系统栈之后,
        // 我们不再持有 GATT 客户端,也就没有自己的回调可听。
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        // 服务 UUID 发现完成的广播 —— 系统的 PhonePolicy 也在听它,收到即说明
        // 触发已送达,连接该由系统自己发起了。
        f.addAction(BluetoothDevice.ACTION_UUID);
        ctx.registerReceiver(ACL, f);
    }

    private static final BluetoothGattCallback CALLBACK = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            String mac = g.getDevice().getAddress();
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                synchronized (Rearm.class) {
                    closeQuietly(gatts.remove(mac));
                    keepaliveTarget.remove(mac);
                }
                // 链路真断了的话,这条回调比 ACL 广播早半秒到 —— 实测断开时刻
                // 27.383,回调 27.385,而广播驱动的开扫要等到 27.900。那半秒
                // 占整个回连过程的六分之一,白等太亏。
                //
                // 只认非零状态:0 是我们自己关的客户端,链路未必有事;8(监督
                // 超时)这类才是链路真的没了。报错了也不怕,巡检每十秒拿平台的
                // 权威状态兜一次底。
                //
                // 0x13 要单独拎出来:那是对方主动终止(关机或闲置休眠),不是
                // 链路失效。对它硬发连接只会换来一串连不上的尝试。
                if (status == REMOTE_TERMINATED) {
                    log("对方主动断开 " + mac);
                    nativeOnPeerLeft(mac);
                } else if (status != BluetoothGatt.GATT_SUCCESS) {
                    log("链路断开 " + mac + " 状态 " + status);
                    nativeOnConnectionChange(mac, false);
                }
                return;
            }
            applyHighPriority(mac, g);
            // 保活要往手柄发真实的空中数据,得先知道有哪些特征可读。
            try {
                g.discoverServices();
            } catch (SecurityException e) {
                log("服务发现失败: " + e);
            }
        }

        @Override
        public void onServicesDiscovered(BluetoothGatt g, int status) {
            if (status != BluetoothGatt.GATT_SUCCESS) return;
            String mac = g.getDevice().getAddress();
            BluetoothGattCharacteristic pick = pickReadable(g);
            if (pick == null) {
                log("没有可用于保活的特征 " + mac);
                return;
            }
            synchronized (Rearm.class) {
                keepaliveTarget.put(mac, pick);
            }
        }

        @Override
        public void onCharacteristicRead(
                BluetoothGatt g,
                BluetoothGattCharacteristic c,
                byte[] value,
                int status) {
            // 读成功即说明数据确实到手柄走了一圈,这正是保活想要的效果。
            if (status != BluetoothGatt.GATT_SUCCESS) {
                log("保活读取失败 " + g.getDevice().getAddress()
                        + " 状态 " + status);
            }
        }

    };

    private static void closeQuietly(BluetoothGatt g) {
        if (g == null) return;
        try {
            g.close();
        } catch (SecurityException e) {
            // 关闭失败不影响后续,忽略。
        }
    }

    /** 只取 128 位 UUID 里那 4 位短码,日志一行放得下。 */
    private static String shortUuid(UUID u) {
        return u.toString().substring(4, 8);
    }

    private static final BroadcastReceiver ACL = new BroadcastReceiver() {
        @Override
        public void onReceive(Context c, Intent intent) {
            BluetoothDevice d = intent.getParcelableExtra(
                    BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
            if (d == null) return;
            if (BluetoothDevice.ACTION_UUID.equals(intent.getAction())) {
                log("服务发现完成 " + d.getAddress());
                return;
            }
            nativeOnConnectionChange(
                    d.getAddress(),
                    BluetoothDevice.ACTION_ACL_CONNECTED.equals(intent.getAction()));
        }
    };

    // ---- Rust 调过来的:平台能力,不含判断 ----

    /**
     * 按地址过滤开扫。地址过滤的扫描在后台是允许的(未过滤的会被系统掐掉)。
     *
     * @param macList 换行分隔的 MAC 列表。用字符串而不是 String[]:JNI 造数组要
     *     多一圈 API,而这里的量小到不值得。
     */
    public static synchronized void startScan(String macList, boolean fast) {
        String[] macs = macList.isEmpty() ? new String[0] : macList.split("\n");
        BluetoothLeScanner scanner = scanner();
        if (scanner == null) {
            log("拿不到扫描器,蓝牙可能已关闭");
            nativeOnScanFailed();
            return;
        }
        try {
            if (scanning) {
                scanner.stopScan(SCAN);
                scanning = false;
            }
            List<ScanFilter> filters = new ArrayList<>();
            for (String mac : macs) {
                filters.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
            }
            // 兜底:地址过滤偶尔漏(地址类型、机型差异都可能),HOGP 规范要求 HID 外设
            // 的可连接广播携带 0x1812,按它再收一路。过滤器之间是"或";收进来的广播
            // 是不是布防目标,由 Rust 引擎按名单判断。
            filters.add(new ScanFilter.Builder()
                    .setServiceUuid(new ParcelUuid(HID_SERVICE)).build());
            // 省电模式每 5120 毫秒只听 512 毫秒,九成时间耳朵是闭着的 —— 手柄
            // 广播得再勤,平均也要两秒多才被撞上。刚掉线那阵子改用满占空比把
            // 这段等待压掉;久等不回(手柄多半已关机)再退回省电,免得白烧电。
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(fast
                            ? ScanSettings.SCAN_MODE_LOW_LATENCY
                            : ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            scanner.startScan(filters, settings, SCAN);
            scanning = true;
            // 开停扫的时刻是重连耗时分段的锚点,掐表全靠它。
            log("开扫 " + (fast ? "满占空比" : "省电") + " 目标=" + macs.length);
        } catch (SecurityException e) {
            log("开扫失败: " + e);
            nativeOnScanFailed();
        }
    }

    public static synchronized void stopScan() {
        BluetoothLeScanner scanner = scanner();
        if (scanner == null || !scanning) return;
        try {
            scanner.stopScan(SCAN);
            scanning = false;
            log("停扫");
        } catch (SecurityException e) {
            log("停扫失败: " + e);
        }
    }

    /**
     * 把设备连起来。
     *
     * <p>**这个方法不能带类锁**:它里面的特权连接是一次阻塞的跨进程调用,而界面
     * 每两秒要调 {@link #bondedDevices} 与 {@link #isConnected},那两个都带同一把
     * 锁 —— 特权调用一卡住,界面线程就跟着卡在锁上。需要互斥的只有 GATT 客户端
     * 那张表,单独锁它即可。
     *
     * <p>退回自建链路时,设备对象从已配对列表取,不用 {@code getRemoteDevice(MAC)}:
     * 那样拿到的对象地址类型是 public,而手柄用的是随机静态地址(抓包实测),
     * 类型不符时连接请求发给的是一个永不出现的设备。已配对列表里的对象带着
     * 配对记录里的正确类型。
     */
    public static void connect(String mac) {
        BluetoothDevice d = bonded(mac);
        if (d == null) {
            log(mac + " 不在已配对列表里");
            // 配对记录是这一切的地基。告诉引擎,它会停扫并弹通知让用户去重新
            // 配对 —— 否则应用会为一台不存在的设备满占空比空转下去。
            nativeOnUnpaired(mac);
            return;
        }
        // 首选:借 Shizuku 的 shell 身份让系统自己接管 —— 这是唯一能真正换来
        // 系统「已连接」、手柄可用的路径。不可用时才退回下面两条自救手段。
        String privileged = Privileged.connect(mac);
        if (privileged != null) {
            log(privileged);
            // 系统接管了链路,但保活和读电量用的是我们自己的 GATT 客户端 ——
            // 以前这里直接返回,那两件事便一直在空转。
            ensureGatt(d);
            return;
        }
        // 借系统自己的策略逻辑来发起连接:PhonePolicy 监听 ACTION_UUID,一收到就
        // 检查该设备的 HID 连接策略,若为「未知」便设成「允许」—— 而 HidHostService
        // 把策略设成「允许」这个动作本身会直接调用 connect(device)。也就是说,只要
        // 让系统重新发现一次服务 UUID,连接就由系统自己发起,不需要任何特权。
        // fetchUuidsWithSdp 只要 BLUETOOTH_CONNECT,这个权限我们有。
        try {
            log("请求重新发现服务=" + d.fetchUuidsWithSdp());
        } catch (SecurityException e) {
            log("请求重新发现服务失败: " + e);
        }
        // autoConnect=true:BLE 里连接只能由广播触发 —— 外设睡着时 autoConnect=false
        // 的即时连接必然失败(实测每 10 秒试一次,一次都连不上)。true 是把设备挂进
        // 系统的后台等待名单,它一广播就自动接上,这正是"布防"要的语义。
        // 已经挂在等待名单里的不再重挂 —— 重建客户端会把名单清掉。
        ensureGatt(d);
    }

    /**
     * 确保这台设备有一个我们自己的 GATT 客户端。保活与读电量都要用它,而系统
     * 接管的那条 HID 链路是另一回事,借不到。
     *
     * <p>已经挂着的不重挂 —— 重建客户端会把系统的后台等待名单清掉。
     * connectGatt 返回 null 时不记账,否则那个空位会永远挡住重试。
     */
    private static void ensureGatt(BluetoothDevice d) {
        String mac = d.getAddress();
        synchronized (Rearm.class) {
            if (gatts.containsKey(mac)) return;
            try {
                BluetoothGatt g = d.connectGatt(ctx, true, CALLBACK, BluetoothDevice.TRANSPORT_LE);
                if (g != null) gatts.put(mac, g);
            } catch (SecurityException e) {
                log("连接 " + mac + " 失败: " + e);
            }
        }
    }

    /**
     * 请求把链路的连接参数压到低延迟档。
     *
     * <p>连接间隔决定输入延迟。系统建链时用的是保守的默认参数,实测手柄要等
     * 几秒才变跟手 —— 那几秒里是游戏或系统随后去请求了高优先级。我们主动请求,
     * 把这段等待去掉。
     *
     * <p>连接参数是**整条链路**的属性,不是某个客户端私有的,所以我们这个 GATT
     * 客户端提的请求同样作用于系统的 HID 链路。客户端要一直挂着:关掉之后栈可能
     * 把参数恢复成默认档。注意它与扫描是两回事 —— 附在已有链路上的 GATT 客户端
     * 不增加额外的射频占用,而扫描会。
     *
     * <p>要不要对某台设备做,由 Rust 引擎判断(只对布防中且已连上的设备做)。
     */
    /**
     * 把这条链路的连接参数压到低延迟档。
     *
     * @return 请求是否真的发出去了。没发出去要让引擎把「每条链路一次」的许可
     *     还回来,否则这条链路再没有第二次机会。
     */
    public static boolean requestLowLatency(String mac) {
        BluetoothGatt existing;
        synchronized (Rearm.class) {
            existing = gatts.get(mac);
        }
        if (existing != null) return applyHighPriority(mac, existing);
        BluetoothDevice d = bonded(mac);
        if (d == null) {
            log(mac + " 不在已配对列表里,没法压低延迟");
            return false;
        }
        // 客户端还没建好,这一轮先把它拉起来;连上后回调里会自己提参数请求。
        ensureGatt(d);
        return false;
    }

    /**
     * 挑一个可读、且不属于 HID 服务的特征作为保活目标。
     *
     * <p>避开 HID 服务(0x1812):安卓禁止普通应用访问它,读会抛 SecurityException。
     * 设备信息服务里的型号、固件版本之类是只读常量,读它对手柄没有副作用。
     */
    private static BluetoothGattCharacteristic pickReadable(BluetoothGatt g) {
        for (BluetoothGattService svc : g.getServices()) {
            if (HID_SERVICE.equals(svc.getUuid())) continue;
            for (BluetoothGattCharacteristic c : svc.getCharacteristics()) {
                if ((c.getProperties() & BluetoothGattCharacteristic.PROPERTY_READ) != 0) {
                    return c;
                }
            }
        }
        return null;
    }

    /**
     * 往手柄发一次保活:读一个无副作用的特征。
     *
     * <p>手柄闲置会自行休眠,那是它固件里的计时器,平板改不了。唯一能试的是往它
     * 发点数据,看固件认不认这算「有活动」—— 认不认查不到资料,只能实测,所以这
     * 是实验性的。要不要发、多久发一次,由 Rust 引擎决定。
     */
    public static void keepAlive(String mac) {
        BluetoothGatt g;
        BluetoothGattCharacteristic target;
        synchronized (Rearm.class) {
            g = gatts.get(mac);
            target = keepaliveTarget.get(mac);
        }
        if (g == null) {
            BluetoothDevice d = bonded(mac);
            if (d != null) ensureGatt(d);
            return;
        }
        if (target == null) return;
        try {
            g.readCharacteristic(target);
        } catch (SecurityException e) {
            log("保活失败: " + e);
        }
    }

    private static boolean applyHighPriority(String mac, BluetoothGatt g) {
        try {
            boolean accepted =
                    g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH);
            log((accepted ? "已请求低延迟 " : "低延迟请求被拒 ") + mac);
            return accepted;
        } catch (SecurityException e) {
            log("请求低延迟失败: " + e);
            return false;
        }
    }

    /**
     * 把每台已配对设备的记录打进日志。
     *
     * <p>同一台平板上鼠标能自动回连、手柄不能,两者都是 BLE HID —— 通用缺陷解释不了
     * 这种不对称,差异只可能在系统给各自存的那份记录里。系统要判断一台已配对设备
     * 值不值得放进后台回连名单,看的就是这份记录里的传输类型和服务 UUID 列表:
     * 记录里没有 HID 服务(0x1812),系统就不知道它是输入设备,自然不会替它守着。
     *
     * <p>这两样都是公开 API 读得到的,并排打出来即可对照。
     */
    public static synchronized String describeBonded() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return "拿不到蓝牙适配器";
        StringBuilder sb = new StringBuilder();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                sb.append(d.getName()).append(" 类型=").append(d.getType()).append(" UUID=");
                ParcelUuid[] uuids = d.getUuids();
                if (uuids == null) {
                    sb.append("null");
                } else {
                    for (ParcelUuid u : uuids) {
                        sb.append(shortUuid(u.getUuid())).append(' ');
                    }
                }
                sb.append('\n');
            }
        } catch (SecurityException e) {
            return "读配对记录失败: " + e;
        }
        return sb.toString();
    }

    /** 行协议:{@code MAC\t名字},一行一台。设备名只有 Java 侧拿得到。 */
    public static synchronized String bondedDevices() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName();
                sb.append(d.getAddress()).append('\t')
                        .append(name == null ? "?" : name).append('\n');
            }
        } catch (SecurityException e) {
            return "";
        }
        return sb.toString();
    }

    /**
     * 系统是否已经把这台设备当输入设备用上了。
     *
     * <p>不能查 GATT 连接状态:我们自己开的 GATT 链路也会让它变 true,而那条链路
     * 挂着的时候系统里照样显示未连接、手柄照样不能用 —— 拿它当"让路"依据会把主动
     * 连接全挡掉。{@code BluetoothProfile.HID_DEVICE} 更是方向相反的角色(本机当
     * 外设)。真正等价于设置里那个"已连接"的公开信号,是它有没有出现在输入设备列表里。
     */
    public static synchronized boolean isConnected(String mac) {
        BluetoothDevice d = bonded(mac);
        if (d == null || ctx == null) return false;
        InputManager im = (InputManager) ctx.getSystemService(Context.INPUT_SERVICE);
        if (im == null) return false;
        String name;
        try {
            name = d.getName();
        } catch (SecurityException e) {
            return false;
        }
        if (name == null) return false;
        for (int id : im.getInputDeviceIds()) {
            InputDevice dev = im.getInputDevice(id);
            if (dev != null && name.equals(dev.getName())) return true;
        }
        return false;
    }

    // ---- 存盘。名单内容由 Rust 决定,这里只负责读写 ----

    public static synchronized String loadArmed() {
        if (ctx == null) return "";
        return String.join("\n", prefs().getStringSet("macs", Collections.emptySet()));
    }

    /** @param macList 换行分隔的 MAC 列表,理由同 startScan。 */
    public static synchronized void saveArmed(String macList) {
        if (ctx == null) return;
        Set<String> set = new HashSet<>();
        if (!macList.isEmpty()) {
            Collections.addAll(set, macList.split("\n"));
        }
        prefs().edit().putStringSet("macs", set).apply();
    }

    /** 权限到手后让 Rust 按当前布防名单重开扫描,并起定时轮询。 */
    public static void resumeScan() {
        log(describeBonded());
        nativeResumeScan();
        startTicking();
    }

    /**
     * 定时主动重试连接。
     *
     * <p>光等广播不够:手柄一旦被断开就不再广播,而它开机期间始终接受连接请求。
     * 间隔取 10 秒,略大于引擎的重试窗口,免得每次 tick 都撞在节流上。
     */
    private static synchronized void startTicking() {
        if (ticking) return;
        ticking = true;
        TICKER.post(new Runnable() {
            @Override
            public void run() {
                nativeTick();
                TICKER.postDelayed(this, TICK_MS);
            }
        });
    }

    /** 特权连接状态,给界面常驻显示。Rust 只认这个类,故在此转一道。 */
    public static String privilegedStatus() {
        return Privileged.status();
    }

    /** 特权连接是否可用,界面据此上色。 */
    public static boolean privilegedReady() {
        return Privileged.ready();
    }

    /** 这台设备还在已配对列表里吗。配对记录没了之后,引擎靠它判断何时恢复。 */
    public static boolean isBonded(String mac) {
        return bonded(mac) != null;
    }

    /** 蓝牙开着没有。引擎拿它判断该不该喊人来开。 */
    public static boolean bluetoothOn() {
        BluetoothAdapter a = adapter();
        return a != null && a.isEnabled();
    }

    /**
     * 弹一条「这事只有你能做」的通知;传空串表示事情已经解决,撤掉通知。
     *
     * <p>同一句话不重复弹:用户划掉之后,只要状态没变就别再打扰,状态一变
     * 立刻重新弹出来。判断什么算「需要人管」在 Rust 引擎里,这里只管显示。
     */
    public static void notifyAttention(String message) {
        if (ctx == null || message.equals(lastAttention)) return;
        lastAttention = message;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        if (message.isEmpty()) {
            nm.cancel(ATTENTION_ID);
            return;
        }
        nm.createNotificationChannel(
                new NotificationChannel(
                        ATTENTION_CHANNEL, "需要人工处理", NotificationManager.IMPORTANCE_HIGH));
        PendingIntent tap =
                PendingIntent.getActivity(
                        ctx,
                        0,
                        new Intent(ctx, MainActivity.class)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                        PendingIntent.FLAG_IMMUTABLE);
        nm.notify(
                ATTENTION_ID,
                new Notification.Builder(ctx, ATTENTION_CHANNEL)
                        .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                        .setContentTitle("蓝牙布防需要你")
                        .setContentText(message)
                        .setStyle(new Notification.BigTextStyle().bigText(message))
                        .setContentIntent(tap)
                        .setAutoCancel(true)
                        .build());
    }

    /** 供同包内其它类往界面日志写一行。 */
    static void note(String line) {
        log(line);
    }

    // ---- Java 转给 Rust 的事件 ----

    private static native void nativeResumeScan();

    private static native void nativeTick();

    private static native void nativeOnAdvertisement(String mac);

    private static native void nativeOnConnectionChange(String mac, boolean connected);

    private static native void nativeOnError(String message);

    private static native void nativeOnScanFailed();

    private static native void nativeOnUnpaired(String mac);

    private static native void nativeOnPeerLeft(String mac);

    /**
     * 往应用内日志写一行,同时抄一份进 logcat。
     *
     * <p>抄这一份是因为这台红魔把 log.tag 设成了 SILENT,平时 logcat 什么都看不到;
     * 而想看应用内那份日志就得把界面切到前台 —— 用户正在打游戏时这么做是不礼貌的。
     */
    private static void log(String line) {
        android.util.Log.i("btrearm", line);
        nativeOnError(line);
    }

    private static final ScanCallback SCAN = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            // 判断一概不做,原样转给 Rust。
            nativeOnAdvertisement(result.getDevice().getAddress());
        }

        @Override
        public void onScanFailed(int errorCode) {
            synchronized (Rearm.class) {
                scanning = false;
            }
            log("扫描失败,错误码 " + errorCode);
            nativeOnScanFailed();
        }
    };

    // ---- 内部工具 ----

    private static BluetoothDevice bonded(String mac) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return null;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equals(mac)) return d;
            }
        } catch (SecurityException e) {
            // 权限没批,调用方会看到"不在已配对列表里"。
        }
        return null;
    }


    private static BluetoothAdapter adapter() {
        if (ctx == null) return null;
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private static BluetoothLeScanner scanner() {
        BluetoothAdapter a = adapter();
        return a == null ? null : a.getBluetoothLeScanner();
    }

    private static SharedPreferences prefs() {
        return ctx.getSharedPreferences("rearm", Context.MODE_PRIVATE);
    }
}
