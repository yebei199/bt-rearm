//! Android 平台入口:被 `NativeActivity` 加载的 cdylib。
//!
//! 蓝牙布防的核心全在 Java 侧(`Rearm.java`):`connectGatt(autoConnect=true)`
//! 的回调必须是 Java 子类,布防状态也由它持有。这里只负责画界面,并通过 JNI
//! 拉取设备列表、转发点击。

#[cfg(target_os = "android")]
mod app {
    use jni::JavaVM;
    use slint::{ModelRc, VecModel};

    slint::slint! {
        export struct DeviceRow {
            name: string,
            mac: string,
            armed: bool,
            state: string,
        }

        export component App inherits Window {
            background: #101418;
            in property <[DeviceRow]> devices;
            callback toggle(string);

            VerticalLayout {
                padding: 24px;
                spacing: 12px;
                Text {
                    text: "蓝牙布防";
                    font-size: 28px;
                    color: white;
                }
                Text {
                    text: "点一台已配对的设备开始替系统布防:它一开机广播就会被自动连回。";
                    font-size: 14px;
                    color: #8899aa;
                    wrap: word-wrap;
                }
                for d in root.devices: Rectangle {
                    height: 76px;
                    border-radius: 12px;
                    background: d.armed ? #14532d : #1e293b;
                    TouchArea {
                        clicked => { root.toggle(d.mac); }
                    }
                    HorizontalLayout {
                        padding: 16px;
                        spacing: 8px;
                        VerticalLayout {
                            spacing: 4px;
                            Text {
                                text: d.name;
                                color: white;
                                font-size: 18px;
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
                            font-size: 16px;
                            vertical-alignment: center;
                        }
                    }
                }
                // 撑满剩余高度,让列表贴顶。
                Rectangle {}
            }
        }
    }

    /// 与 `Rearm.java` 的包名 + 类名绑死,改一边就要改另一边。
    const REARM: &str = "io/github/yebei199/btrearm/Rearm";

    pub fn run(android_app: slint::android::AndroidApp) {
        android_logger::init_once(
            android_logger::Config::default()
                .with_max_level(log::LevelFilter::Info)
                .with_tag(env!("CARGO_CRATE_NAME")),
        );
        log::info!("{} starting", env!("CARGO_CRATE_NAME"));

        // SAFETY:`vm_as_ptr` 返回的是 android-activity 在 `android_main` 之前
        // 就拿到的那个 JavaVM 指针,进程存续期间一直有效。
        let vm = unsafe {
            JavaVM::from_raw(android_app.vm_as_ptr().cast())
        };

        slint::android::init(android_app)
            .expect("slint android init failed");

        let ui = App::new().expect("create ui");

        let refresh = {
            let vm = vm.clone();
            let ui = ui.as_weak();
            move || {
                let Some(ui) = ui.upgrade() else { return };
                let rows = parse_rows(&list(&vm));
                ui.set_devices(ModelRc::new(
                    VecModel::from(rows),
                ));
            }
        };

        ui.on_toggle({
            let vm = vm.clone();
            let refresh = refresh.clone();
            move |mac| {
                toggle(&vm, &mac);
                refresh();
            }
        });

        refresh();
        // 设备列表和连接状态都在 Java 侧变化,定期拉一遍最省事。
        // ponytail: 2 秒轮询,想要即时就改成 Java 回调推送。
        let timer = slint::Timer::default();
        timer.start(
            slint::TimerMode::Repeated,
            std::time::Duration::from_secs(2),
            refresh,
        );

        ui.run().expect("run ui");
    }

    /// `Rearm.list()` 的行协议:`MAC\t名字\t是否布防\t状态`,一行一台。
    fn parse_rows(raw: &str) -> Vec<DeviceRow> {
        raw.lines()
            .filter_map(|line| {
                let mut f = line.split('\t');
                Some(DeviceRow {
                    mac: f.next()?.into(),
                    name: f.next()?.into(),
                    armed: f.next()? == "1",
                    state: f.next().unwrap_or("-").into(),
                })
            })
            .collect()
    }

    fn list(vm: &JavaVM) -> String {
        let got = vm.attach_current_thread(|env| {
            let s = env
                .call_static_method(
                    jni::jni_str!(
                        "io/github/yebei199/btrearm/Rearm"
                    ),
                    jni::jni_str!("list"),
                    jni::jni_sig!("()Ljava/lang/String;"),
                    &[],
                )?
                .l()?;
            let s =
                jni::objects::JString::from(s);
            Ok(env.get_string(&s)?.into())
        });
        match got {
            Ok(s) => s,
            Err(err) => {
                log::warn!("list 调用失败: {err} (类路径应为 {REARM})");
                String::new()
            }
        }
    }

    fn toggle(vm: &JavaVM, mac: &str) {
        let done: jni::errors::Result<()> = vm
            .attach_current_thread(|env| {
                let mac = env.new_string(mac)?;
                env.call_static_method(
                    jni::jni_str!(
                        "io/github/yebei199/btrearm/Rearm"
                    ),
                    jni::jni_str!("toggle"),
                    jni::jni_sig!(
                        "(Ljava/lang/String;)V"
                    ),
                    &[(&mac).into()],
                )?;
                Ok(())
            });
        if let Err(err) = done {
            log::warn!("toggle 调用失败: {err}");
        }
    }
}

/// Android 入口点,由 android-activity 胶水按裸符号名调用。
/// `unsafe(no_mangle)`:符号可能撞名、签名无法被编译器校验,契约由本函数保证。
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(app: slint::android::AndroidApp) {
    app::run(app);
}
