apk := "gradle/app/build/outputs/apk/debug/app-debug.apk"

_default:
    @just --list

# 编 Rust 动态库 + 打 APK。必须在 `nix-shell Android.nix` 里跑。
build:
    cargo ndk -t arm64-v8a --platform 26 \
        -o gradle/app/src/main/jniLibs build --release
    cd gradle && gradle --no-daemon assembleDebug -PrearmAbis=arm64-v8a

# 装到已连接的设备(USB 或无线 adb 均可)
install:
    adb install -r {{apk}}

# 装完直接拉起来,并跟日志
run: install
    adb shell am start -n io.github.yebei199.btrearm/.MainActivity
    adb logcat -s btrearm

# 宿主机上的静态检查。android target 下的代码在这里是空 crate,
# 所以还要 check 一遍交叉 target 才有意义。
check:
    cargo check --target aarch64-linux-android
    cargo clippy --target aarch64-linux-android -- -D warnings
    cargo fmt --check
