# Android 平台桥

这里把 Rust 引擎的扫描、连接和观察请求转交 Android；连接政策归 Rust，特权请求通过 Shizuku 用户服务执行。diagnostics 子目录负责持久采集，这里不改变它的后台生命周期。

链路观察是 opportunistic GATT 注册，只附着已有连接，不主动连接或修改参数。Rearm 的每次既有巡检直接询问 PrivilegedConnect，用户服务在 worker 上验证系统 GATT binder 身份与存活状态，并按实际对象去重；应用不保存第二份观察成功缓存，因此用户服务替换、异步注册失败和在途旧返回都不能阻挡下次巡检。

适配器离开 ON 状态时，Rearm 经 AIDL 让用户服务清除观察器；系统 GATT binder 死亡或身份变化也使旧对象及旧原因失效。回调只有当前 MAC 对应的对象才能生效，失效对象的迟到回调不能删除新对象或发布原因。所有观察状态、回调与原因消费在同一 worker 排序；独立读取原因不关闭健康观察器；ACL入口原子消费原因并退休该连接的观察器，ACL 先到时仍为 unknown，迟到原因会被忽略。ACL 新连接到达及重新附着会丢弃上一连接剩余原因，不修改原有8/19/unknown出口。

离线回归入口与平台替身边界见 tools/observer/README.md。它验证真实Java生命周期与出口，真实Android接口编译另行检查；OEM恢复、设备输入和自然断联原因仍需独立观察。
