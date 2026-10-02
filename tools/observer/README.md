# 观察客户端离线回归

这里通过真实 `Rearm.attach/watchLink`、`Privileged.init`、`PrivilegedConnect.watchLink/lastDisconnectReason` 和注册的回调验证观察客户端生命周期。测试只替换 Android、Shizuku、AIDL 传输和 JNI 边界，不复制业务逻辑，不操作设备。验收映射在 `acceptance/1.toml`。

运行入口是 `bash tools/observer/test.sh`，需要 JDK 17、C 编译器及 Linux JNI headers。`--compile-only` 只编译测试和真实 Java 类，不执行行为；`--list` 列出测试名；默认按环境预算有界并行，每个用例在独立 JVM 中执行，汇总 JUnit 到 `.dispatch/1-observer-junit.xml`。临时源码、类和 JNI 库都在本次运行独占的目录，退出后回收。这个小型编译不构建 APK 或 Rust 库。行为执行前，运行环境须给出 `OBSERVER_TEST_WORKERS` 正整数预算（或已有的 `NIX_BUILD_CORES`）；缺失预算会明确失败，无固定全机并行数，也不隐式退回串行。

当前提交是待主路由审查的测试骨架。未运行行为 RED，未写生产修复。主路由 proceed 后才能执行默认入口，确认失败来自行为断言，再实施修复。

`platform.sources` 按文件标记展开平台替身。Handler 的即时任务运行在单独的串行 daemon worker，延时任务只记录、不自动触发；GATT 回调投递到生产代码传入的 Handler，测试使用队列屏障控制先后，不使用 sleep。Context 依据生产注册的 filter 分发广播。GATT 替身记录真实构造、注册和关闭对象，主动连接及参数修改会立即失败。Binder 替身保留当前接口身份与死亡回调，Shizuku 替身驱动真实 ServiceConnection。JNI 库记录真实 Java 发出的连接和 peer-left 事件；不执行 Rust 决策。

测试覆盖适配器广播序列、服务失效及恢复、重复巡检、注册拒绝/异常、Shizuku 死亡/替换、在途旧成功返回、旧 GATT 的迟到回调、原因一次消费及 ACL 两种顺序。巡检从 Rust 既有调用终点 `Rearm.watchLink` 进入；不证明 Rust 定时器、系统 Binder 分发、OEM GATT 注册时序、真机恢复、APK 构建或自然断联根治。平台替身接受系统信号后不会替生产代码清理任何缓存或原因。
