package io.github.yebei199.btrearm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.AttributionSource;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Process;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
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

    private final Handler worker;
    private BluetoothAdapter adapter;

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

    /** 绕开框架的服务注册表,自行组装适配器,理由见类注释。 */
    private static BluetoothAdapter buildAdapter() throws Exception {
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

        AttributionSource source = new AttributionSource.Builder(Process.myUid())
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
