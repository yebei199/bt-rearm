# 诊断验证与导出

这里验证应用日志的持久化、容量上限和失败隔离，并通过指定的 ADB 设备导出应用私有日志。它不操作蓝牙、WiFi 或游戏。

运行 `bash tools/diagnostics/test.sh` 验证存储行为。`check.sh <设备序列号>` 对比两次落盘心跳，`export.sh <设备序列号> <新输出目录>` 导出应用日志和输入设备快照；两者失败均返回非零退出码。具体方式见仓库首页的当前诊断说明。

导出只读取应用私有日志及 input 快照。Bluetooth dump（包括 bluetooth_manager 和直接服务 dump）与 bugreport 采集已停用，避免进入已知的蓝牙服务崩溃路径。这里不负责连接策略、自然断联根因调查或后台采集的启停。

运行 `bash tools/diagnostics/test-export.sh` 验证导出安全。测试通过 PATH 前置的 mock adb 调用真实导出入口，在每次运行独占的临时目录中检查实际输出和命令记录，全程不连接设备。

issue #1 的 AC-1 导出部分由 `test_exports_logs_and_input` 逐字节比较日志及输入快照，并检查完成标记；`test_avoids_bluetooth_dump_and_bugreport` 验证仅发出应用归档与 input 快照命令。既有行为回归覆盖 ADB 归档失败、ADB 返回无效 tar、输入快照失败、已有目录逐字节保持原样及错误参数拒绝。mock 验证无法证明真机蓝牙服务状态；自然断联调查与最终验收由上游负责。
