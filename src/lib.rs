//! Android 平台入口:被 `NativeActivity` 加载的 cdylib。
//!
//! 布防的**决策**全在 [`engine`] 里,与安卓无关,可在电脑上跑测试。
//! Java 那一侧只剩转发壳:安卓的 BLE 回调类是抽象类,JNI 无法从 Rust 实现
//! (动态代理只支持接口),所以必须有个 Java 子类把事件转进来。

/// 布防决策核心。不碰安卓 API,可在电脑上跑测试。
pub mod engine;

#[cfg(target_os = "android")]
mod app;

/// Android 入口点,由 android-activity 胶水按裸符号名调用。
/// `unsafe(no_mangle)`:符号可能撞名、签名无法被编译器校验,契约由本函数保证。
#[cfg(target_os = "android")]
#[unsafe(no_mangle)]
fn android_main(app: slint::android::AndroidApp) {
    app::run(app);
}
