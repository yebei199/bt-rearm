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
        let done: jni::errors::Result<()> =
            vm().attach_current_thread(|env| {
                env.call_static_method(
                    jni::jni_str!("io/github/yebei199/btrearm/Rearm"),
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

/// 收一个字符串参数、无返回值的静态方法。
macro_rules! call_with_str {
    ($name:literal, $arg:expr) => {{
        let done: jni::errors::Result<()> =
            vm().attach_current_thread(|env| {
                let s = env.new_string($arg)?;
                env.call_static_method(
                    jni::jni_str!("io/github/yebei199/btrearm/Rearm"),
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
    let vm = unsafe { JavaVM::from_raw(android_app.vm_as_ptr().cast()) };
    let _ = VM.set(vm);
    *ENGINE.lock().unwrap() = Some(Engine::new());

    slint::android::init(android_app).expect("slint android init failed");

    // 恢复上次的布防名单,并按引擎的指示开扫。
    let saved: Vec<String> = call_string!("loadArmed")
        .lines()
        .filter(|l| !l.is_empty())
        .map(str::to_string)
        .collect();
    if !saved.is_empty() {
        let cmd = with_engine(|e| e.restore(saved));
        apply(cmd);
    }

    let ui = App::new().expect("create ui");

    let refresh = {
        let ui = ui.as_weak();
        move || {
            let Some(ui) = ui.upgrade() else { return };
            ui.set_devices(ModelRc::new(VecModel::from(rows())));
            ui.set_events(ModelRc::new(VecModel::from(events())));
        }
    };

    ui.on_toggle({
        let refresh = refresh.clone();
        move |mac| {
            let mac = mac.to_string();
            let (cmd, armed) = with_engine(|e| {
                let cmd = e.toggle(&mac);
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

/// 把引擎的扫描指令交给 Java 执行。
fn apply(cmd: Scan) {
    match cmd {
        Scan::Start(macs) => call_with_str!("startScan", macs.join("\n")),
        Scan::Stop => call_void!("stopScan"),
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
    with_engine(|e| {
        // 新的在上面,一眼看到最近发生了什么。
        e.log().iter().rev().map(|l| l.as_str().into()).collect()
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
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeResumeScan<'c>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
) {
    env.with_env(|_env| -> jni::errors::Result<()> {
        let cmd = with_engine(|e| e.scan_command());
        apply(cmd);
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// 扫到一条广播。决策在引擎里,这里只负责把结论执行掉。
///
/// `EnvUnowned` 是 jni 为原生方法准备的 FFI 安全入参,且 `with_env` 自带
/// `catch_unwind` —— panic 穿过 FFI 边界会让整个进程 abort。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnAdvertisement<'c>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
    mac: jni::objects::JString<'c>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let mac = mac.try_to_string(env)?;
        // 连接状态要现问 Java —— 引擎自己不碰安卓 API。
        let connected = call_is_connected(&mac);
        let now = uptime_ms();
        let action =
            with_engine(|e| e.on_advertisement(&mac, connected, now));
        if action == Action::Connect {
            call_connect(&mac);
        }
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// 连接状态变了。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnConnectionChange<'c>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
    mac: jni::objects::JString<'c>,
    connected: bool,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let mac = mac.try_to_string(env)?;
        with_engine(|e| e.on_connection_change(&mac, connected));
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

/// Java 侧的异常与失败,原样进日志给用户看。
#[unsafe(no_mangle)]
pub extern "system" fn Java_io_github_yebei199_btrearm_Rearm_nativeOnError<'c>(
    mut env: jni::EnvUnowned<'c>,
    _class: jni::objects::JClass<'c>,
    message: jni::objects::JString<'c>,
) {
    env.with_env(|env| -> jni::errors::Result<()> {
        let msg = message.try_to_string(env)?;
        with_engine(|e| e.note_external(msg));
        Ok(())
    })
    .resolve::<jni::errors::LogErrorAndDefault>()
}

fn call_is_connected(mac: &str) -> bool {
    let got = vm().attach_current_thread(|env| {
        let mac = env.new_string(mac)?;
        env.call_static_method(
            jni::jni_str!("io/github/yebei199/btrearm/Rearm"),
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

/// 单调时钟毫秒数,喂给引擎做节流。
fn uptime_ms() -> u64 {
    std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_millis() as u64)
        .unwrap_or(0)
}
