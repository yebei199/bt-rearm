package io.github.yebei199.btrearm;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import rikka.shizuku.Shizuku;

/**
 * 应用这一侧的 Shizuku 客户端:把连接请求送到 shell 身份的进程里去执行。
 *
 * <p>为什么非借 shell 不可:让系统接管手柄的 {@code BluetoothDevice.connect()}
 * 要 BLUETOOTH_PRIVILEGED 与 MODIFY_PHONE_STATE,两者都是签名/特权级权限,普通
 * 应用申请不到也授不了;shell 恰好两个都有。真机实测过 shell 身份能调通,
 * 且能把断开的手柄重新连成可用的系统输入设备。
 *
 * <p>Shizuku 没运行或没授权时,这里一律安静地返回不可用,由调用方退回原来的
 * 自建链路 —— 那条链路虽然换不来系统接管,至少不会让应用变砖。
 */
final class Privileged {

    /** 用户服务版本号,改了服务实现要加一,否则 Shizuku 会复用旧进程。 */
    private static final int SERVICE_VERSION = 1;

    private static final int PERMISSION_REQUEST_CODE = 2;

    private static volatile IPrivilegedConnect service;
    /** 权限回调只注册一次:绑定器可能重连,重连时再注册会积累重复回调。 */
    private static boolean permissionListenerAdded;
    private static Context ctx;
    private static boolean binding;

    private Privileged() {}

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = IPrivilegedConnect.Stub.asInterface(binder);
            Rearm.note("特权连接服务已就绪");
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            binding = false;
        }
    };

    /** Shizuku 的绑定器可能晚于应用启动才到位,到位后再绑服务。 */
    static void init(Context context) {
        ctx = context.getApplicationContext();
        Shizuku.addBinderReceivedListenerSticky(Privileged::onBinderReady);
        Shizuku.addBinderDeadListener(() -> {
            service = null;
            binding = false;
        });
    }

    private static void onBinderReady() {
        if (Shizuku.isPreV11()) {
            Rearm.note("Shizuku 版本过旧,不支持用户服务");
            return;
        }
        if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            bind();
            return;
        }
        if (!permissionListenerAdded) {
            permissionListenerAdded = true;
            Shizuku.addRequestPermissionResultListener((code, result) -> {
                if (code == PERMISSION_REQUEST_CODE
                        && result == PackageManager.PERMISSION_GRANTED) {
                    bind();
                } else {
                    Rearm.note("Shizuku 授权被拒,退回自建链路");
                }
            });
        }
        Shizuku.requestPermission(PERMISSION_REQUEST_CODE);
    }

    private static synchronized void bind() {
        if (binding || service != null) return;
        binding = true;
        try {
            Shizuku.bindUserService(
                    new Shizuku.UserServiceArgs(
                                    new ComponentName(ctx, PrivilegedConnect.class.getName()))
                            .processNameSuffix("privileged")
                            .version(SERVICE_VERSION)
                            .daemon(false),
                    CONNECTION);
        } catch (Throwable t) {
            // 不复位的话这个标志会一直挡着,后面再也不会重试,而界面只会显示
            // 「正在绑定服务」—— 看上去像卡住,实际是永远不会再动了。
            binding = false;
            Rearm.note("绑定特权服务失败: " + t);
        }
    }

    /**
     * 特权连接当前的可用状态,一句话,常驻显示在界面上。
     *
     * <p>不常驻的话:平板重启后 Shizuku 不会自启,应用会安静地退回自建链路 ——
     * 那条链路换不来系统接管,等于没用,而用户只会觉得「又不灵了」。
     */
    static String status() {
        if (!Shizuku.pingBinder()) return "特权连接:Shizuku 未运行";
        if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            return "特权连接:未授权";
        }
        return service != null ? "特权连接:已就绪" : "特权连接:正在绑定服务";
    }

    /** 状态是否为可用,界面据此上色。 */
    static boolean ready() {
        return service != null;
    }

    /**
     * 请系统连接这台设备。
     *
     * @return 一行结果进日志;服务尚未就绪时返回 null,由调用方决定怎么退让
     */
    static String connect(String mac) {
        IPrivilegedConnect s = service;
        if (s == null) return null;
        try {
            return s.connect(mac);
        } catch (Exception e) {
            service = null;
            binding = false;
            return "特权连接调用失败: " + e;
        }
    }
}
