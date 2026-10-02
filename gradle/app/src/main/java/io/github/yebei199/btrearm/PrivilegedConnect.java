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
import android.os.IInterface;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

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

    private static final int TRANSPORT_LE = BluetoothDevice.TRANSPORT_LE;
    private static final int PHY_1M_MASK = BluetoothDevice.PHY_LE_1M_MASK;

    private final Handler worker;
    private BluetoothAdapter adapter;
    private AttributionSource source;
    /** 只保护观察身份与原因，不在此锁内调用平台或等待worker。 */
    private final Object observerLock = new Object();
    /** 在途注册捕获的连接身份，ACL退休后不得重新提交。 */
    private final Map<String, Object> connections = new HashMap<>();
    /** 系统服务失效的世代，防止锁外查询跨过失效后重新提交。 */
    private long serviceGeneration;
    /** 挂在各设备链路上的观察客户端,断开即关掉释放。 */
    private final Map<String, BluetoothGatt> gatts = new HashMap<>();
    /** 当前服务的断开状态，与连接退休在短状态临界区内原子消费。 */
    private final Map<String, Integer> lastReason = new HashMap<>();

    /** 当前系统GATT服务的身份，失效后不能再信任任何旧观察器。 */
    private IBinder gattBinder;
    /** 与当前服务绑定的死亡监听，换代时解除。 */
    private IBinder.DeathRecipient gattDeath;

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
    public String watchLink(String mac) {
        BlockingQueue<String> result = new ArrayBlockingQueue<>(1);
        worker.post(() -> result.offer(watchOnWorker(mac)));
        try {
            String line = result.poll(CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            return line == null ? "挂链路观察客户端超时 " + mac : line;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "挂链路观察客户端被打断 " + mac;
        }
    }

    /** 读取并消费原因不会改变仍健康的观察器。 */
    @Override
    public int lastDisconnectReason(String mac) {
        return consumeReason(mac, false);
    }

    /** ACL消费与身份退休立即完成，不等待平台注册或关闭。 */
    @Override
    public int retireConnection(String mac) {
        return consumeReason(mac, true);
    }

    /** 只访问短状态临界区，资源回收在返回路径之外。 */
    private int consumeReason(String mac, boolean retire) {
        IBinder binder;
        synchronized (observerLock) { binder = gattBinder; }
        if (binder != null && !binder.isBinderAlive()) invalidateService(binder);
        BluetoothGatt old = null;
        Integer reason;
        synchronized (observerLock) {
            reason = lastReason.remove(mac);
            if (retire) {
                connections.remove(mac);
                old = gatts.remove(mac);
            }
        }
        BluetoothGatt retired = old;
        if (retired != null) worker.post(() -> closeObserver(retired));
        return reason == null ? -1 : reason;
    }

    /** 适配器失效先清观察身份，平台回收不阻挡广播。 */
    @Override
    public void invalidateObservers() {
        invalidateService(null);
    }

    /** 已失效状态的资源快照，只在锁外回收。 */
    private record RetiredObservers(BluetoothGatt[] gatts, IBinder binder, IBinder.DeathRecipient death) {}

    /** 调用者持锁：清账先于close，阻塞中的旧注册也失去身份。 */
    private RetiredObservers retireAllLocked() {
        RetiredObservers old = new RetiredObservers(gatts.values().toArray(new BluetoothGatt[0]),
                gattBinder, gattDeath);
        gatts.clear();
        lastReason.clear();
        connections.clear();
        serviceGeneration++;
        gattBinder = null;
        gattDeath = null;
        return old;
    }

    /** 旧死亡通知只能失效仍匹配的服务，清理交给worker。 */
    private void invalidateService(IBinder expected) {
        RetiredObservers old;
        synchronized (observerLock) {
            if (expected != null && expected != gattBinder) return;
            old = retireAllLocked();
        }
        worker.post(() -> releaseRetired(old));
    }

    /** 平台操作始终在短状态临界区之外，允许消费已记录原因。 */
    private static void releaseRetired(RetiredObservers old) {
        unlinkDeath(old.binder(), old.death());
        for (BluetoothGatt gatt : old.gatts()) closeObserver(gatt);
    }

    /** 已死或已解除的监听不影响观察身份失效。 */
    private static void unlinkDeath(IBinder binder, IBinder.DeathRecipient death) {
        if (binder == null || death == null) return;
        try {
            binder.unlinkToDeath(death, 0);
        } catch (RuntimeException ignored) {
            // 资源已失效，不重新取得任何状态所有权。
        }
    }

    /** 回收只触碰传入的对象，异常不阻止后续巡检。 */
    private static void closeObserver(BluetoothGatt gatt) {
        if (gatt == null) return;
        try {
            gatt.close();
        } catch (RuntimeException ignored) {
            // 系统服务可能已死，缓存已清所以仍可恢复。
        }
    }

    /** 锁外查询和登记平台身份，提交时核对查询期间没有发生失效。 */
    private Object currentGattService() throws Exception {
        long generation;
        synchronized (observerLock) { generation = serviceGeneration; }
        if (!adapter.isEnabled()) {
            invalidateObservers();
            throw new IllegalStateException("蓝牙已关闭");
        }
        Object iGatt = BluetoothAdapter.class.getMethod("getBluetoothGatt").invoke(adapter);
        IBinder binder = iGatt == null ? null : ((IInterface) iGatt).asBinder();
        if (binder == null || !binder.isBinderAlive()) {
            invalidateObservers();
            throw new IllegalStateException("GATT服务不可用");
        }
        RetiredObservers old;
        synchronized (observerLock) {
            if (generation != serviceGeneration) throw new IllegalStateException("观察服务已失效");
            if (binder == gattBinder) return iGatt;
            old = retireAllLocked();
            generation = serviceGeneration;
        }
        releaseRetired(old);
        AtomicBoolean died = new AtomicBoolean();
        IBinder.DeathRecipient death = () -> {
            died.set(true);
            invalidateService(binder);
        };
        binder.linkToDeath(death, 0);
        boolean alive = binder.isBinderAlive();
        boolean accepted;
        synchronized (observerLock) {
            accepted = generation == serviceGeneration && alive && !died.get();
            if (accepted) {
                gattBinder = binder;
                gattDeath = death;
            }
        }
        if (!accepted) {
            unlinkDeath(binder, death);
            throw new IllegalStateException("观察服务已失效");
        }
        return iGatt;
    }

    /**
     * 往已建好的链路上挂一个只读的观察客户端,唯一的用途是拿到断开原因码。
     *
     * <p>公开的 {@code connectGatt} 在这个进程里走不通:它内部取的是框架注册表里的
     * 默认适配器,而这里的注册表是空的。于是反射构造 {@link BluetoothGatt},用我们
     * 自己组装的适配器交出的 GATT 接口。opportunistic 为真:只附着在已有链路上,
     * 永远不自己发起连接,也不把设备挂进后台等待名单,链路断了它就跟着断。
     *
     * <p>设备对象从已配对列表取,不用 {@code getRemoteDevice(mac)}:后者的地址类型
     * 是 public,而手柄用随机静态地址,类型不符的连接请求发给的是一个不存在的设备。
     */
    private String watchOnWorker(String mac) {
        BluetoothGatt pending = null;
        Object connection = null;
        boolean registered = false;
        try {
            if (adapter == null) adapter = buildAdapter();
            Object iGatt = currentGattService();
            IBinder binder = ((IInterface) iGatt).asBinder();
            synchronized (observerLock) {
                if (gattBinder != binder) return "观察服务已失效 " + mac;
                if (gatts.containsKey(mac)) return "链路观察客户端已挂着 " + mac;
            }
            BluetoothDevice device = null;
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equals(mac)) device = d;
            }
            if (device == null) return mac + " 不在已配对列表里,挂不上客户端";
            synchronized (observerLock) {
                if (gattBinder != binder) return "观察服务已失效 " + mac;
                connection = new Object();
                connections.put(mac, connection);
                lastReason.remove(mac);
            }
            Constructor<BluetoothGatt> ctor = BluetoothGatt.class.getDeclaredConstructor(
                    Class.forName("android.bluetooth.IBluetoothGatt"),
                    BluetoothDevice.class, int.class, boolean.class, int.class,
                    AttributionSource.class);
            ctor.setAccessible(true);
            pending = ctor.newInstance(iGatt, device, TRANSPORT_LE, true, PHY_1M_MASK, source);
            Method connect = BluetoothGatt.class.getDeclaredMethod(
                    "connect", Boolean.class, BluetoothGattCallback.class, Handler.class);
            connect.setAccessible(true);
            Object ok = connect.invoke(pending, Boolean.FALSE, new LinkWatcher(mac), worker);
            if (!Boolean.TRUE.equals(ok)) return "挂链路观察客户端被拒 " + mac;
            synchronized (observerLock) {
                if (connections.get(mac) != connection || gattBinder != binder) {
                    return "链路观察注册已退休 " + mac;
                }
                gatts.put(mac, pending);
                registered = true;
                pending = null;
            }
            return "已挂链路观察客户端 " + mac;
        } catch (Throwable t) {
            Throwable cause = t.getCause() == null ? t : t.getCause();
            return "挂链路观察客户端失败 " + mac + ": " + cause;
        } finally {
            if (!registered && connection != null) {
                synchronized (observerLock) {
                    if (connections.get(mac) == connection) connections.remove(mac);
                }
            }
            closeObserver(pending);
        }
    }

    /**
     * 附着在链路上,只为记下断开原因码。
     *
     * <p>不碰任何链路参数。曾经在这里把监督超时顶到 20 秒,想让链路扛过静默;
     * 09-05 的抓包证明静默超过 21.8 秒,判死线放到 20 秒照样死,而线越长每次掉线
     * 反而等得越久 —— 手柄要等主机判死之后再过一个超时才重新广播。已撤。
     */
    private final class LinkWatcher extends BluetoothGattCallback {
        private final String mac;

        LinkWatcher(String mac) {
            this.mac = mac;
        }

        /** 回调先在短状态区确认所有权并记原因，再到锁外关闭对象。 */
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            IBinder binder;
            synchronized (observerLock) {
                if (gatts.get(mac) != g) return;
                binder = gattBinder;
            }
            if (binder == null || !binder.isBinderAlive()) {
                invalidateService(binder);
                return;
            }
            synchronized (observerLock) {
                if (gatts.get(mac) != g || gattBinder != binder) return;
                if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    lastReason.put(mac, status);
                    gatts.remove(mac);
                }
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                android.util.Log.i("btrearm", "链路观察客户端已附着 " + mac + " 状态 " + status);
                return;
            }
            if (newState != BluetoothProfile.STATE_DISCONNECTED) return;
            android.util.Log.i("btrearm", "链路观察客户端断开 " + mac + " 状态 " + status);
            closeObserver(g);
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
