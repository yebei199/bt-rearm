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

一个 `connectGatt(autoConnect = true)` 的 GATT 客户端会把目标设备放进控制器的
后台连接名单。设备一广播,ACL 链路即被拉起,已配对 HID 设备的输入服务随即
附着 —— 也就是系统本该自己做的那件事。本应用只做这一件事:

1. 列出已配对设备,你点哪台就给哪台挂一个 autoConnect 客户端("布防");
2. 布防名单存在 SharedPreferences 里,下次启动自动恢复;
3. 一个前台服务把进程钉住,免得布防随进程被冻结而失效。

不需要 root,不修改系统,不碰配对记录。

## 构建

工具链走 Nix(Android SDK 34 / NDK r27 / JDK 17 / cargo-ndk):

```sh
nix-shell Android.nix
just build      # cargo-ndk 编 .so + gradle 打 APK
just run        # 装到设备并跟日志
```

界面是 Slint,入口是 `NativeActivity` 加载的 cdylib;蓝牙那半边必须是 Java
(`connectGatt` 的回调只能是 Java 子类),两侧靠 JNI 相连。

## 状态

在一台红魔平板 5 Pro(Android 16)+ 飞智八爪鱼 2 手柄上开发。原理对任何
"系统不给 BLE 设备回连"的机型都通用,但只在这一台上验证过。

## 许可

MIT
