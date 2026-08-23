//! 安卓这一端:界面 + 与 Java 转发壳之间的 JNI 桥。
//!
//! 这里**不做布防决策**,只做三件事:把 Java 送来的事件喂给 [`crate::engine`],
//! 把引擎的动作发回 Java 执行,以及把引擎的状态画到屏幕上。

use std::sync::{Mutex, OnceLock};

use jni::JavaVM;
use slint::{ModelRc, VecModel};

use crate::engine::{Action, Engine, Scan};

slint::slint! {
    import { ScrollView } from "std-widgets.slint";

    export struct DeviceRow {
        name: string,
        mac: string,
        armed: bool,
        state: string,
    }

    export component App inherits Window {
        background: #101418;
        in property <[DeviceRow]> devices;
        in property <[string]> events;
        in property <string> privileged;
        in property <bool> privileged-ready;
        callback toggle(string);

        VerticalLayout {
            padding: 20px;
            spacing: 10px;
            Text {
                text: "蓝牙布防";
                font-size: 26px;
                color: white;
            }
            Text {
                text: "点一台已配对设备开始布防:它一广播、且系统没连它,就替系统连上。";
                font-size: 13px;
                color: #8899aa;
                wrap: word-wrap;
            }
            // 常驻:让系统接管全靠这条特权通道,它断了应用就只是个摆设。
            // 不摆在明处,平板重启后 Shizuku 没起来,用户只会觉得「又不灵了」。
            Text {
                text: root.privileged;
                font-size: 13px;
                color: root.privileged-ready ? #4ade80 : #f59e0b;
            }
            for d in root.devices: Rectangle {
                height: 70px;
                border-radius: 12px;
                background: d.armed ? #14532d : #1e293b;
                TouchArea {
                    clicked => { root.toggle(d.mac); }
                }
                HorizontalLayout {
                    padding: 14px;
                    spacing: 8px;
                    VerticalLayout {
                        spacing: 3px;
                        Text {
                            text: d.name;
                            color: white;
                            font-size: 17px;
                        }
                        Text {
                            text: d.mac + "   " + d.state;
                            color: #99aabb;
                            font-size: 12px;
                        }
                    }
                    Text {
                        text: d.armed ? "已布防" : "未布防";
                        color: d.armed ? #4ade80 : #778899;
                        font-size: 15px;
                        vertical-alignment: center;
                    }
                }
            }
            // 决策日志。这台平板的 logcat 不可用,不把它画出来就等于瞎飞。
            Text {
                text: "决策日志";
                font-size: 13px;
                color: #8899aa;
            }
            Rectangle {
                background: #0b0f14;
                border-radius: 10px;
                vertical-stretch: 1;
                ScrollView {
                    VerticalLayout {
                        padding: 10px;
                        spacing: 2px;
                        for line in root.events: Text {
                            text: line;
                            color: #7f8ea3;
                            font-size: 11px;
                            wrap: word-wrap;
                        }
                    }
                }
            }
        }
    }
}

/// 与 `Rearm.java` 的包名 + 类名绑死,改一边就要改另一边。
const REARM: &str = "io/github/yebei199/btrearm/Rearm";

/// 进程级单例。JNI 的原生方法是裸符号,调进来时拿不到任何上下文,
/// 所以引擎和 JavaVM 只能是全局的。
static ENGINE: Mutex<Option<Engine>> = Mutex::new(None);
static VM: OnceLock<JavaVM> = OnceLock::new();

/// 无参、无返回值的静态方法。
macro_rules! call_void {
    ($name:literal) => {{
        let done: jni::errors::Result<()> = vm()
            .attach_current_thread(|env| {
                env.call_static_method(
                    jni::jni_str!(
                        "io/github/yebei199/btrearm/Rearm"
                    ),
                    jni::jni_str!($name),
                    jni::jni_sig!("()V"),
                    &[],
                )?;
                Ok(())
            });
        if let Err(err) = done {
            log::warn!("{} 调用失败: {err}", $name);
        }
    }};
}

/// 无参、返回字符串的静态方法。
macro_rules! call_string {
    ($name:literal) => {{
        let got = vm().attach_current_thread(|env| {
            let obj = env
                .call_static_method(
                    jni::jni_str!("io/github/yebei199/btrearm/Rearm"),
                    jni::jni_str!($name),
                    jni::jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            // 返回值静态类型是 JObject,签名保证它其实是 String。
            let s = env.as_cast::<jni::objects::JString>(&obj)?;
            s.try_to_string(env)
        });
        match got {
            Ok(s) => s,
            Err(err) => {
                log::warn!(
                    "{} 调用失败: {err} (类路径应为 {REARM})",
                    $name
                );
                String::new()
            }
        }
    }};
}

/// 无参、返回布尔值的静态方法。
macro_rules! call_bool {
    ($name:literal) => {{
        let got = vm().attach_current_thread(|env| {
            env.call_static_method(
                jni::jni_str!(
                    "io/github/yebei199/btrearm/Rearm"
                ),
                jni::jni_str!($name),
                jni::jni_sig!("()Z"),
                &[],
            )?
            .z()
        });
        got.unwrap_or(false)
    }};
}

/// 收一个字符串参数、返回布尔值的静态方法。
macro_rules! call_str_bool {
    ($name:literal, $arg:expr) => {{
        let got = vm().attach_current_thread(|env| {
            let s = env.new_string($arg)?;
            env.call_static_method(
                jni::jni_str!(
                    "io/github/yebei199/btrearm/Rearm"
                ),
                jni::jni_str!($name),
                jni::jni_sig!("(Ljava/lang/String;)Z"),
                &[(&s).into()],
            )?
            .z()
        });
        got.unwrap_or(false)
    }};
}

/// 收一个字符串参数、无返回值的静态方法。
macro_rules! call_with_str {
    ($name:literal, $arg:expr) => {{
        let done: jni::errors::Result<()> = vm()
            .attach_current_thread(|env| {
                let s = env.new_string($arg)?;
                env.call_static_method(
                    jni::jni_str!(
                        "io/github/yebei199/btrearm/Rearm"
                    ),
                    jni::jni_str!($name),
                    jni::jni_sig!("(Ljava/lang/String;)V"),
                    &[(&s).into()],
                )?;
                Ok(())
            });
        if let Err(err) = done {
            log::warn!("{} 调用失败: {err}", $name);
        }
    }};
}

pub fn run(android_app: slint::android::AndroidApp) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Info)
            .with_tag(env!("CARGO_CRATE_NAME")),
    );

    // SAFETY:`vm_as_ptr` 返回的是 android-activity 在 `android_main` 之前就拿到的
    // 那个 JavaVM 指针,进程存续期间一直有效。
    let vm = unsafe {
        JavaVM::from_raw(android_app.vm_as_ptr().cast())
    };
    let _ = VM.set(vm);
    *ENGINE.lock().unwrap() = Some(Engine::new());

    slint::android::init(android_app)
        .expect("slint android init failed");

    // 恢复上次的布防名单,并按引擎的指示开扫。
    let saved: Vec<String> = call_string!("loadArmed")
        .lines()
        .filter(|l| !l.is_empty())
        .map(str::to_string)
        .collect();
    if !saved.is_empty() {
        let cmd =
            with_engine(|e| e.restore(saved, now_ms()));
        apply(cmd);
    }

    let ui = App::new().expect("create ui");

    let refresh = {
        let ui = ui.as_weak();
        move || {
            let Some(ui) = ui.upgrade() else { return };
            ui.set_devices(ModelRc::new(VecModel::from(
                rows(),
            )));
            ui.set_events(ModelRc::new(VecModel::from(
                events(),
            )));
            ui.set_privileged(
                call_string!("privilegedStatus").into(),
            );
            ui.set_privileged_ready(call_bool!(
                "privilegedReady"
            ));
        }
    };

    ui.on_toggle({
        let refresh = refresh.clone();
        move |mac| {
            let mac = mac.to_string();
            let (cmd, armed) = with_engine(|e| {
                let cmd = e.toggle(&mac, now_ms());
                (cmd, e.armed_macs())
            });
            save_armed(&armed);
            apply(cmd);
            refresh();
        }
    });

    refresh();
    // 连接状态由 Java 回调推进来,但设备列表与"系统已连接"要现问,定期刷一遍最省事。
    // ponytail: 2 秒轮询,够用;要即时再改成回调推送。
    let timer = slint::Timer::default();
    timer.start(
        slint::TimerMode::Repeated,
        std::time::Duration::from_secs(2),
        refresh,
    );

    ui.run().expect("run ui");
}

fn with_engine<T>(f: impl FnOnce(&mut Engine) -> T) -> T {
    let mut guard = ENGINE.lock().unwrap();
    let engine = guard.get_or_insert_with(Engine::new);
    f(engine)
}

/// 领到许可就请求把连接参数压到低延迟档。引擎保证每条链路只发一次。
fn claim_low_latency(mac: &str) {
    if !with_engine(|e| e.take_low_latency_request(mac)) {
        return;
    }
    // 许可是每条链路一次,安卓那侧没发出去就得还回来,否则这条链路再没机会。
    if !call_str_bool!("requestLowLatency", mac) {
        with_engine(|e| e.return_low_latency_permit(mac));
    }
}

/// 目标扫描状态变了才下发。每个事件都重下的话,安卓侧会不停停扫再开扫,
/// 那本身就是一次射频扰动。
fn update_scan() {
    if let Some(cmd) =
        with_engine(|e| e.scan_if_changed(now_ms()))
    {
        apply(cmd);
    }
}

/// 把引擎的扫描指令交给 Java 执行。
fn apply(cmd: Scan) {
    match cmd {
        Scan::Start { macs, fast } => {
            call_scan(&macs.join("\n"), fast)
        }
        Scan::Stop => call_void!("stopScan"),
    }
}

/// 开扫。`fast` 决定占空比:刚掉线要抢时间,久等不回就省电。
fn call_scan(mac_list: &str, fast: bool) {
    let done: jni::errors::Result<()> = vm()
        .attach_current_thread(|env| {
            let s = env.new_string(mac_list)?;
            env.call_static_method(
                jni::jni_str!(
                    "io/github/yebei199/btrearm/Rearm"
                ),
                jni::jni_str!("startScan"),
                jni::jni_sig!("(Ljava/lang/String;Z)V"),
                &[(&s).into(), fast.into()],
            )?;
            Ok(())
        });
    if let Err(err) = done {
        log::warn!("startScan 调用失败: {err}");
    }
}

fn save_armed(macs: &[String]) {
    call_with_str!("saveArmed", macs.join("\n"));
}

/// 界面上的每一行:名字来自 Java(只有那边拿得到),状态来自引擎。
fn rows() -> Vec<DeviceRow> {
    call_string!("bondedDevices")
        .lines()
        .filter_map(|line| {
            let (mac, name) = line.split_once('\t')?;
            let row = with_engine(|e| e.row(mac));
            Some(DeviceRow {
                mac: mac.into(),
                name: name.into(),
                armed: row.armed,
                state: row.state.as_str().into(),
            })
        })
        .collect()
}

fn events() -> Vec<slint::SharedString> {
    let now = now_ms();
    with_engine(|e| {
        // 新的在上面,一眼看到最近发生了什么。
        e.log(now)
            .iter()
            .rev()
            .map(|l| l.as_str().into())
            .collect()
    })
}

// ---- Rust → Java ----

fn vm() -> &'static JavaVM {
    VM.get().expect("JavaVM 尚未初始化")
}

// ---- Java → Rust ----
//
// 符号名与 `Rearm.java` 的包名、类名、方法名绑死 —— 改任何一个都要同时改这里。
// **链接期不会有人提醒**,只会在事件到来那一刻抛 UnsatisfiedLinkError。

/// 权限到手后按当前名单重开扫描 —— 在那之前发起的开扫必然失败。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeResumeScan<
    'c,
>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
) {
    env.with_env(|_env| -> jni::errors::Result<()> {
        let cmd = with_engine(|e| e.scan_command(now_ms()));
        apply(cmd);
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// 定时盲试:对每台布防中的设备碰运气试一次连接。
///
/// 不能只等广播 —— 后台扫描可能被系统压制,那时一条广播都收不到,盲试是唯一
/// 还能动的路径。设备不在时它必然失败,所以引擎会把间隔逐次翻倍,并在收到广播
/// 或连上时清零。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeTick<
    'c,
>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
) {
    env.with_env(|_env| -> jni::errors::Result<()> {
        let macs = with_engine(|e| e.armed_macs());
        let now = now_ms();
        for mac in macs {
            let connected = call_is_connected(&mac);
            let action = with_engine(|e| {
                e.on_tick(&mac, connected, now)
            });
            if action == Action::Connect {
                call_connect(&mac);
            }
            // 应用启动时设备可能已经连着,那一刻不会再有连接广播 —— 巡检补发。
            claim_low_latency(&mac);
            // 保活:试探手柄固件认不认平板发来的数据算「有活动」,从而推迟休眠。
            if with_engine(|e| e.take_keepalive(&mac, now))
            {
                call_with_str!("keepAlive", &mac);
            }
        }
        // 扫描目标只跟整份名单有关,一轮巡检结算一次就够,不必每台设备算一遍。
        update_scan();
        // 自动那条路走不通时喊人。两个事实只有安卓那侧知道,取来交给引擎判断。
        let bt_on = call_bool!("bluetoothOn");
        let ready = call_bool!("privilegedReady");
        let word =
            with_engine(|e| e.attention(bt_on, ready, now))
                .unwrap_or_default();
        call_with_str!("notifyAttention", &word);
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// 扫到一条广播。决策在引擎里,这里只负责把结论执行掉。
///
/// `EnvUnowned` 是 jni 为原生方法准备的 FFI 安全入参,且 `with_env` 自带
/// `catch_unwind` —— panic 穿过 FFI 边界会让整个进程 abort。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnAdvertisement<
    'c,
>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
    mac: jni::objects::JString<'c>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let mac = mac.try_to_string(env)?;
        // 连接状态要现问 Java —— 引擎自己不碰安卓 API。
        let connected = call_is_connected(&mac);
        let now = now_ms();
        let action = with_engine(|e| {
            e.on_advertisement(&mac, connected, now)
        });
        if action == Action::Connect {
            call_connect(&mac);
        }
        update_scan();
        claim_low_latency(&mac);
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// 连接状态变了。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnConnectionChange<
    'c,
>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
    mac: jni::objects::JString<'c>,
    connected: bool,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let mac = mac.try_to_string(env)?;
        with_engine(|e| {
            e.on_connection_change(
                &mac,
                connected,
                now_ms(),
            )
        });
        // 连上就停扫、断开就复扫。扫描与已建立的连接共用射频,连着还扫会挤掉
        // 手柄的输入包 —— 卡手与 0x08 监督超时断线都由此而来。
        update_scan();
        // 刚建的链路用的是保守的连接参数,手感要等几秒才好。主动去压。
        claim_low_latency(&mac);
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Java 侧的异常与失败,原样进日志给用户看。
/// 安卓那侧没能把扫描开起来 —— 蓝牙关着、权限被撤,或者平台回了错误码。
///
/// 必须报回来:引擎记的是「我下发过什么」,不报的话目标状态没变就再也不重下,
/// 扫描永远回不来。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnScanFailed<
    'c,
>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
) {
    env.with_env(|_env| -> jni::errors::Result<()> {
        with_engine(|e| e.on_scan_failed(now_ms()));
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnError<
    'c,
>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
    message: jni::objects::JString<'c>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let msg = message.try_to_string(env)?;
        with_engine(|e| e.note_external(now_ms(), msg));
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn call_is_connected(mac: &str) -> bool {
    let got = vm().attach_current_thread(|env| {
        let mac = env.new_string(mac)?;
        env.call_static_method(
            jni::jni_str!(
                "io/github/yebei199/btrearm/Rearm"
            ),
            jni::jni_str!("isConnected"),
            jni::jni_sig!("(Ljava/lang/String;)Z"),
            &[(&mac).into()],
        )?
        .z()
    });
    got.unwrap_or(false)
}

fn call_connect(mac: &str) {
    call_with_str!("connect", mac);
}

/// 墙上时钟毫秒数,喂给引擎做节流与日志计龄。
/// 进程启动以来的毫秒数。
///
/// 用单调时钟而不是墙钟:引擎里每一处判断都是「距上次过了多久」,而墙钟会跳 ——
/// 开机后对时、用户改时区都能让它一步跨过几小时。往前跳会把所有退避、稳定窗口、
/// 提醒阈值同时引爆,往后跳则让 saturating_sub 一直算出 0,退避与提醒就此冻住。
/// 原点是任意的,引擎只用差值,不在乎。
fn now_ms() -> u64 {
    static START: OnceLock<std::time::Instant> =
        OnceLock::new();
    START
        .get_or_init(std::time::Instant::now)
        .elapsed()
        .as_millis() as u64
}
