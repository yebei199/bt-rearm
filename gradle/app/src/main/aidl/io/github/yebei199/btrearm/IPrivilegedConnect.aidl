package io.github.yebei199.btrearm;

/**
 * 跑在 shell 身份进程里的连接服务。
 *
 * <p>系统的连接接口要 BLUETOOTH_PRIVILEGED 与 MODIFY_PHONE_STATE,普通应用
 * 永远拿不到这两个权限,而 shell 两个都有。Shizuku 负责把这个服务拉起在
 * shell 身份下,应用通过这个接口把连接请求送过去。
 */
interface IPrivilegedConnect {

    /**
     * 让系统连接指定设备,等价于设置里点那个「连接」按钮。
     *
     * @param mac 设备地址
     * @return 一行结果,原样进界面日志
     */
    String connect(String mac) = 1;

    /**
     * 往这台设备已经建好的链路上挂一个只读的观察客户端,断开时记下原因码。
     *
     * @return 一行结果,原样进界面日志
     */
    String watchLink(String mac) = 2;

    /**
     * 上一次断开的 HCI 原因码(取走即清),没记录时返回 -1。
     * 系统接管的链路在应用这边没有回调,原因码只有挂在链路上的那个客户端看得到。
     */
    int lastDisconnectReason(String mac) = 3;

    /** 适配器失效时退休观察句柄和旧原因，不发起连接。 */
    void invalidateObservers() = 4;

    /** ACL退休当前连接并消费原因，晚到回调不再持有原因槽。 */
    int retireConnection(String mac) = 5;

    /** 结束服务进程。Shizuku 解绑时调用。 */
    void destroy() = 16777114;
}
