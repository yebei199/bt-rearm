# bt-rearm 蓝牙布防

替不布防的安卓蓝牙栈,给已配对的 BLE 设备挂上后台自动回连。

## 它解决的问题

红魔平板 5 Pro(RedMagicOS 11.5 / Android 16)上,已配对的蓝牙低功耗手柄
关机再开机后**不会自动连回**:手柄持续广播可连接报文,系统一次连接尝试都不
发起,只能手动去设置里点一下那台设备。同一台平板上的罗技鼠标却一切正常,
所以机制是有的,只是不给这台手柄用。

抓包证据(btsnoop,2026-08-22):

- 手柄以配对记录中相同的地址连续广播 272 次,类型为 `CONNECTABLE+scannable`;
- 同一时段主机零次 `LE Create Connection`;
- 已排除:配对由谁创建、第三方 App 是否在场、蓝牙权限、"输入设备"策略开关。

## 原理

一开始走的是 `connectGatt(autoConnect = true)`:给目标设备挂一个后台连接客户端,
设备一广播链路就被拉起。链路确实能拉起来,但**系统不会因此接管**它 —— 设置里
照旧显示未连接,手柄照旧不能用。应用建的是自己的 GATT 链路,和系统的 HID 输入
服务是两回事。

让系统真正接管的动作是 `BluetoothDevice.connect()`,设置里点「连接」走的就是它。
它要 `BLUETOOTH_PRIVILEGED` 与 `MODIFY_PHONE_STATE`,两者都是签名/特权级权限,
普通应用申请不到也授不了。以下路径均已真机证伪:

| 尝试 | 结果 |
|---|---|
| 反射调 `BluetoothDevice.connect()` | 方法拿得到,系统服务端拒绝:缺 `MODIFY_PHONE_STATE` |
| HID_HOST profile 代理的 `connect(device)` | 被隐藏 API 名单拦下 |
| 配对记录残缺(缺 HID 服务 UUID / 类型不对) | 手柄记录正常,与能回连的鼠标一致 |
| 手柄广播地址与配对记录不符 | 地址分毫不差 |
| HID 连接策略被设成「禁止」 | 策略就是「允许」 |
| `fetchUuidsWithSdp()` 借系统 PhonePolicy 自行发起 | 触发送达,但那条分支只在策略「未知」时执行 |

shell 用户恰好握有这两个权限。Shizuku 能把一个进程拉起在 shell 身份下,应用把
连接请求送过去执行,系统便如常接管。所以应用做的是:

1. 列出已配对设备,点一台即开始布防;
2. 扫到它的广播、且系统没连它时,请 shell 身份的服务调用系统的连接接口;
3. 布防名单存在 SharedPreferences 里,下次启动自动恢复;
4. 前台服务把进程钉住,免得布防随进程被冻结而失效。

Shizuku 未运行或未授权时退回自建链路,应用仍可用,只是换不来系统接管。

不需要 root,不修改系统,不碰配对记录。

### 前置条件

装 [Shizuku](https://shizuku.rikka.app/) 并启动它(无线调试或连电脑 adb),
首次运行时在弹窗里允许本应用使用 Shizuku。平板重启后 Shizuku 需要重新启动。

## 构建

工具链走 Nix(Android SDK 34 / NDK r27 / JDK 17 / cargo-ndk):

```sh
nix-shell Android.nix
just build      # cargo-ndk 编 .so + gradle 打 APK
just run        # 装到设备并跟日志
```

界面是 Slint,入口是 `NativeActivity` 加载的 cdylib;布防决策在 Rust 的 `engine`
模块里(有单元测试,不碰安卓 API),蓝牙那半边必须是 Java(BLE 的回调类是抽象类,
JNI 无法从 Rust 实现),两侧靠 JNI 相连。

## 状态

在一台红魔平板 5 Pro(Android 16)+ 飞智八爪鱼 2 手柄上开发,只在这一台上
验证过。特权连接那条路对任何"系统不给 BLE 设备回连"的机型都通用,前提是
装得上 Shizuku。

已验证:

- 手柄关机再开机后自动接回,设置里显示已连接、手柄可用,约两秒;
- 屏幕熄灭、应用在后台时同样成立。

尚未验证,见 `docs/TODO.md`:游戏中途掉线、平板重启后的完整流程、深度休眠
(adb 连着时平板不进入最深休眠,已做的验证都不覆盖这个条件)。

## 许可

MIT
