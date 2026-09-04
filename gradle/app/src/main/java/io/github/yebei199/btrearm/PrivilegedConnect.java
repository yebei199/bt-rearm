package io.github.yebei199.btrearm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothProfile;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;

/**
 * 跑在 shell 身份下的连接服务,由 Shizuku 拉起。
 *
 * <p>让系统接管手柄的那个动作是 {@code BluetoothDevice.connect()} —— 设置里点
 * 「连接」走的就是它。它要 BLUETOOTH_PRIVILEGED 与 MODIFY_PHONE_STATE,普通应用
 * 永远拿不到(实测抛 SecurityException),而 shell 两个都有。
 *
 * <p>这个进程由 Shizuku 用 app_process 拉起,没走过应用初始化,常规写法在这里
 * 全都不成立,三处非常规做法都是实测逼出来的:
 * <ul>
 *   <li>框架内的蓝牙服务注册表是空的,常规入口只会返回 null,得先自己填一次,
 *       再从服务管理器取绑定器、反射构造适配器;
 *   <li>归属信息要显式带上 shell 的包身份,否则系统按空归属鉴权;
 *   <li>适配器建好后会往消息循环投递回调,所有蓝牙调用必须在一条**正在运行**的
 *       循环上执行 —— 在没有活循环的线程上调用,进程会被直接杀掉。
 * </ul>
 */
public final class PrivilegedConnect extends IPrivilegedConnect.Stub {

    /** 单次连接调用的等待上限,超时即认为系统没响应。 */
    private static final long CALL_TIMEOUT_SECONDS = 10;

    /**
     * 拉长监督超时时请求的链路参数。间隔与从属延迟照抄手柄自己要的
     * (11.25 ms、1),只把超时从它要的 3 秒改成 20 秒(单位 10 ms)。改得越少,
     * 手柄越没理由再把参数抢回去;抢不抢回去,由 btsnoop 说了算。
     */
    private static final int CONN_INTERVAL_UNITS = 9;
    private static final int PERIPHERAL_LATENCY = 1;
    private static final int SUPERVISION_TIMEOUT_UNITS = 2000;
    private static final int TRANSPORT_LE = BluetoothDevice.TRANSPORT_LE;
    private static final int PHY_1M_MASK = BluetoothDevice.PHY_LE_1M_MASK;

    private final Handler worker;
    private BluetoothAdapter adapter;
    private AttributionSource source;
    /** 挂在各设备链路上的观察客户端,断开即关掉释放。 */
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();
    /** 各设备上一次断开的 HCI 原因码,取走即清。 */
    private final Map<String, Integer> lastReason = new HashMap<>();

    public PrivilegedConnect() {
        HandlerThread thread = new HandlerThread("rearm-privileged");
        thread.start();
        worker = new Handler(thread.getLooper());
    }

    @Override
    public String connect(String mac) {
        // 绑定器调用落在没有消息循环的线程上,转给带活循环的工作线程执行,
        // 再把结果同步取回来 —— 调用方要的是一行可以直接进日志的结果。
        BlockingQueue<String> result = new ArrayBlockingQueue<>(1);
        worker.post(() -> result.offer(connectOnWorker(mac)));
        try {
            String line = result.poll(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return line == null ? "系统连接超时 " + mac : line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "系统连接被打断 " + mac;
        }
    }

    private String connectOnWorker(String mac) {
        try {
            if (adapter == null) {
                adapter = buildAdapter();
            }
            BluetoothDevice device = adapter.getRemoteDevice(mac);
            Method connect = BluetoothDevice.class.getMethod("connect");
            Object code = connect.invoke(device);
            // 返回 0 只表示请求被受理:手柄不在时它照样返回 0。真正的成功信号是
            // 随后系统报上来的连接状态,措辞上不能让这一行读起来像连上了。
            return Integer.valueOf(0).equals(code)
                    ? "已请求系统连接 " + mac
                    : "系统连接被拒 " + mac + " 码=" + code;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return "系统连接失败 " + mac + ": " + cause;
        }
    }

    @Override
    public String tuneLink(String mac) {
        BlockingQueue<String> result = new ArrayBlockingQueue<>(1);
        worker.post(() -> result.offer(tuneOnWorker(mac)));
        try {
            String line = result.poll(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return line == null ? "挂链路参数客户端超时 " + mac : line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "挂链路参数客户端被打断 " + mac;
        }
    }

    @Override
    public int lastDisconnectReason(String mac) {
        synchronized (lastReason) {
            Integer reason = lastReason.remove(mac);
            return reason == null ? -1 : reason;
        }
    }

    /**
     * 往已建好的链路上挂一个 GATT 客户端。
     *
     * <p>公开的 {@code connectGatt} 在这个进程里走不通:它内部取的是框架注册表里的
     * 默认适配器,而这里的注册表是空的。于是反射构造 {@link BluetoothGatt},用我们
     * 自己组装的适配器交出的 GATT 接口。opportunistic 为真:只附着在已有链路上,
     * 永远不自己发起连接,也不把设备挂进后台等待名单,链路断了它就跟着断。
     *
     * <p>设备对象从已配对列表取,不用 {@code getRemoteDevice(mac)}:后者的地址类型
     * 是 public,而手柄用随机静态地址,类型不符的连接请求发给的是一个不存在的设备。
     */
    private String tuneOnWorker(String mac) {
        try {
            if (adapter == null) {
                adapter = buildAdapter();
            }
            if (gatts.containsKey(mac)) return "链路参数客户端已挂着 " + mac;
            BluetoothDevice device = null;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equals(mac)) device = d;
            }
            if (device == null) return mac + " 不在已配对列表里,挂不上客户端";
            Object iGatt = BluetoothAdapter.class.getMethod("getBluetoothGatt").invoke(adapter);
            Constructor<BluetoothGatt> ctor = BluetoothGatt.class.getDeclaredConstructor(
                    Class.forName("android.bluetooth.IBluetoothGatt"),
                    BluetoothDevice.class, int.class, boolean.class, int.class,
                    AttributionSource.class);
            ctor.setAccessible(true);
            BluetoothGatt gatt = ctor.newInstance(
                    iGatt, device, TRANSPORT_LE, true, PHY_1M_MASK, source);
            Method connect = BluetoothGatt.class.getDeclaredMethod(
                    "connect", Boolean.class, BluetoothGattCallback.class, Handler.class);
            connect.setAccessible(true);
            Object ok = connect.invoke(gatt, Boolean.FALSE, new LinkWatcher(mac), worker);
            if (!Boolean.TRUE.equals(ok)) return "挂链路参数客户端被拒 " + mac;
            gatts.put(mac, gatt);
            return "已挂链路参数客户端 " + mac;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return "挂链路参数客户端失败 " + mac + ": " + cause;
        }
    }

    /** 附着在链路上:连上就改参数,断开就记原因、关客户端。 */
    private final class LinkWatcher extends BluetoothGattCallback {
        private final String mac;

        LinkWatcher(String mac) {
            this.mac = mac;
        }

        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                android.util.Log.i("btrearm", "链路参数客户端已附着 " + mac + " 状态 " + status);
                requestLongTimeout(g);
                return;
            }
            android.util.Log.i("btrearm", "链路参数客户端断开 " + mac + " 状态 " + status);
            synchronized (lastReason) {
                lastReason.put(mac, status);
            }
            if (gatts.remove(mac) != null) {
                try {
                    g.close();
                } catch (SecurityException e) {
                    // 关不掉也不影响下一次重挂。
                }
            }
        }

        private void requestLongTimeout(BluetoothGatt g) {
            try {
                Method update = BluetoothGatt.class.getMethod("requestLeConnectionUpdate",
                        int.class, int.class, int.class, int.class, int.class, int.class);
                Object accepted = update.invoke(g, CONN_INTERVAL_UNITS, CONN_INTERVAL_UNITS,
                        PERIPHERAL_LATENCY, SUPERVISION_TIMEOUT_UNITS, 0, 0);
                android.util.Log.i("btrearm", "已请求 20 秒监督超时 " + mac + " 受理=" + accepted);
            } catch (Throwable t) {
                Throwable cause = t.getCause() == null ? t : t.getCause();
                android.util.Log.i("btrearm", "请求监督超时失败 " + mac + ": " + cause);
            }
        }
    }

    /** 绕开框架的服务注册表,自行组装适配器,理由见类注释。 */
    private BluetoothAdapter buildAdapter() throws Exception {
        Class<?> serviceManagerClass = Class.forName("android.os.BluetoothServiceManager");
        Constructor<?> serviceManagerCtor = serviceManagerClass.getDeclaredConstructor();
        serviceManagerCtor.setAccessible(true);
        Method setter = Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                .getMethod("setBluetoothServiceManager", serviceManagerClass);
        try {
            setter.invoke(null, serviceManagerCtor.newInstance());
        } catch (Exception alreadySet) {
            // 只能设一次,重复设会抛;进程内第二次连接走到这里是正常的。
        }

        IBinder binder = (IBinder) Class.forName("android.os.ServiceManager")
                .getMethod("getService", String.class).invoke(null, "bluetooth_manager");
        Class<?> managerItf = Class.forName("android.bluetooth.IBluetoothManager");
        Object manager = Class.forName("android.bluetooth.IBluetoothManager$Stub")
                .getMethod("asInterface", IBinder.class).invoke(null, binder);

        source = new AttributionSource.Builder(Process.myUid())
                .setPackageName("com.android.shell").build();

        for (Constructor<?> c : BluetoothAdapter.class.getDeclaredConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length == 2 && params[0] == managerItf) {
                c.setAccessible(true);
                return (BluetoothAdapter) c.newInstance(manager, source);
            }
        }
        throw new IllegalStateException("没有匹配的适配器构造函数");
    }

    @Override
    public void destroy() {
        System.exit(0);
    }
}
