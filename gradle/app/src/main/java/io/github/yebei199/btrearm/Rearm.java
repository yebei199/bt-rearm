package io.github.yebei199.btrearm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
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
import android.os.Looper;
import android.os.ParcelUuid;
import android.view.InputDevice;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
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
    /** 当前挂着的 GATT 客户端,按 MAC 存,断开时释放。 */
    private static final Map<String, BluetoothGatt> gatts = new HashMap<>();
    private static boolean scanning;
    private static boolean ticking;
    private static final Handler TICKER = new Handler(Looper.getMainLooper());
    private static final long TICK_MS = 10_000L;
    private static final UUID HID_SERVICE = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb");
    private static final UUID BATTERY_SERVICE = UUID.fromString("0000180f-0000-1000-8000-00805f9b34fb");
    /** {@code BluetoothProfile.HID_HOST}:常量本身是隐藏的,值写死。 */
    private static final int PROFILE_HID_HOST = 4;
    /** HID 主机 profile 代理,异步拿到,见 {@link #systemConnect}。 */
    private static volatile BluetoothProfile hidHost;

    private Rearm() {}

    public static synchronized void attach(Context c) {
        ctx = c.getApplicationContext();
        // 连接状态改由系统的 ACL 广播提供 —— connect() 把连接交给系统栈之后,
        // 我们不再持有 GATT 客户端,也就没有自己的回调可听。
        IntentFilter f = new IntentFilter();
        f.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        f.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        ctx.registerReceiver(ACL, f);
        BluetoothAdapter a = adapter();
        if (a != null) {
            a.getProfileProxy(ctx, new BluetoothProfile.ServiceListener() {
                @Override
                public void onServiceConnected(int profile, BluetoothProfile proxy) {
                    hidHost = proxy;
                }

                @Override
                public void onServiceDisconnected(int profile) {
                    hidHost = null;
                }
            }, PROFILE_HID_HOST);
        }
    }

    private static final BluetoothGattCallback CALLBACK = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            String mac = g.getDevice().getAddress();
            if (newState != BluetoothProfile.STATE_CONNECTED) {
                synchronized (Rearm.class) {
                    closeQuietly(gatts.remove(mac));
                }
                return;
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
    public static synchronized void startScan(String macList) {
        String[] macs = macList.isEmpty() ? new String[0] : macList.split("\n");
        BluetoothLeScanner scanner = scanner();
        if (scanner == null) {
            nativeOnError("拿不到扫描器,蓝牙可能已关闭");
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
            // LOW_POWER 已足够:手柄开机后会持续广播好几分钟。
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            scanner.startScan(filters, settings, SCAN);
            scanning = true;
        } catch (SecurityException e) {
            nativeOnError("开扫失败: " + e);
        }
    }

    public static synchronized void stopScan() {
        BluetoothLeScanner scanner = scanner();
        if (scanner == null || !scanning) return;
        try {
            scanner.stopScan(SCAN);
            scanning = false;
        } catch (SecurityException e) {
            nativeOnError("停扫失败: " + e);
        }
    }

    /**
     * 主动把 ACL 链路拉起来。
     *
     * <p>设备对象从已配对列表取,不用 {@code getRemoteDevice(MAC)}:那样拿到的对象
     * 地址类型是 public,而手柄用的是随机静态地址(抓包实测),类型不符时连接请求
     * 发给的是一个永不出现的设备。已配对列表里的对象带着配对记录里的正确类型。
     */
    public static synchronized void connect(String mac) {
        BluetoothDevice d = bonded(mac);
        if (d == null) {
            nativeOnError(mac + " 不在已配对列表里");
            return;
        }
        nativeOnError(systemConnect(d));
        // autoConnect=true:BLE 里连接只能由广播触发 —— 外设睡着时 autoConnect=false
        // 的即时连接必然失败(实测每 10 秒试一次,一次都连不上)。true 是把设备挂进
        // 系统的后台等待名单,它一广播就自动接上,这正是"布防"要的语义。
        // 已经挂着的不再重挂:重建客户端会把等待名单清掉。
        // 已经挂在等待名单里的不再重挂 —— 重建客户端会把名单清掉。
        if (gatts.containsKey(mac)) return;
        try {
            gatts.put(mac, d.connectGatt(ctx, true, CALLBACK, BluetoothDevice.TRANSPORT_LE));
        } catch (SecurityException e) {
            nativeOnError("连接 " + mac + " 失败: " + e);
        }
    }

    /**
     * 复刻设置里那个「连接」按钮。
     *
     * <p>设置点下去走的是 {@code BluetoothDevice.connect()} —— 让系统栈把这台设备
     * 启用的所有 profile(手柄就是 HOGP)都接上,这才是我们真正要的那个动作。它标了
     * {@code @SystemApi},公开 SDK 编译不到,只能反射;能不能调通取决于两道门:
     * 隐藏 API 名单(调不到会抛 NoSuchMethodException)和 BLUETOOTH_PRIVILEGED
     * 权限检查(调得到但没权限会抛 SecurityException)。两道门的失败信息不一样,
     * 原样写进界面日志,由它告诉我们卡在哪一道。
     *
     * <p>退一步还有 HID_HOST profile 代理的 {@code connect(device)},那是同一件事的
     * 另一个入口,顺带一起试,免得多跑一轮真机。
     *
     * @return 一行结果,直接进界面日志
     */
    private static String systemConnect(BluetoothDevice d) {
        StringBuilder sb = new StringBuilder("系统连接: ");
        sb.append("device.connect()=").append(reflectConnect(BluetoothDevice.class, d, d));
        if (hidHost != null) {
            sb.append(" hid.connect()=")
                    .append(reflectConnect(hidHost.getClass(), hidHost, d));
        } else {
            sb.append(" hid=代理未就绪");
        }
        return sb.toString();
    }

    /**
     * 在 {@code owner} 上反射调用 {@code connect} —— 参数要么没有(设备自身的
     * connect),要么是一台设备(profile 代理的 connect)。
     *
     * @return 返回值,或失败原因;两者都短到能塞进一行日志
     */
    private static String reflectConnect(Class<?> cls, Object target, BluetoothDevice arg) {
        boolean onDevice = target == arg;
        try {
            Method m = onDevice
                    ? cls.getMethod("connect")
                    : cls.getMethod("connect", BluetoothDevice.class);
            Object r = onDevice ? m.invoke(target) : m.invoke(target, arg);
            return String.valueOf(r);
        } catch (InvocationTargetException e) {
            // 方法调到了但内部抛了 —— 真正的原因在 cause 里,多半是权限。
            return String.valueOf(e.getCause());
        } catch (Throwable t) {
            return String.valueOf(t);
        }
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

    // ---- Java 转给 Rust 的事件 ----

    private static native void nativeResumeScan();

    private static native void nativeTick();

    private static native void nativeOnAdvertisement(String mac);

    private static native void nativeOnConnectionChange(String mac, boolean connected);

    private static native void nativeOnError(String message);

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
            nativeOnError("扫描失败,错误码 " + errorCode);
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
