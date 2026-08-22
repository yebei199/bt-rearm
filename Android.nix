# Android APK 工具链的 nix-shell 形式。容器里该有的都在这儿:Android SDK
# (platform 34, build-tools 34.0.0)、NDK r27、cargo-ndk、基于 JDK 17 的
# gradle 8,以及配好 Android target 的 rustup。
#
#   nix-shell Android.nix
#   just build
#
# 体积很重(SDK+NDK 有几个 GB)且是 unfree 包,所以要手动 `nix-shell`。
# 这里导入 nixpkgs 时接受了许可协议,androidenv 要求如此。
let
  pkgs = import <nixpkgs> {
    config = {
      allowUnfree = true;
      android_sdk.accept_license = true;
    };
  };

  ndkVersion = "27.2.12479018";
  buildToolsVersion = "34.0.0";
  platformVersion = "34";

  android = pkgs.androidenv.composeAndroidPackages {
    cmdLineToolsVersion = "11.0";
    platformVersions = [ platformVersion ];
    buildToolsVersions = [ buildToolsVersion ];
    includeNDK = true;
    ndkVersions = [ ndkVersion ];
  };

  sdk = "${android.androidsdk}/libexec/android-sdk";
  ndkBin = "${sdk}/ndk/${ndkVersion}/toolchains/llvm/prebuilt/linux-x86_64/bin";
  # 与 gradle 的 minSdk 是同一个数。
  minSdk = "26";
in
pkgs.mkShell {
  buildInputs = [
    android.androidsdk
    (pkgs.gradle_8.override { java = pkgs.jdk17; })
    pkgs.jdk17
    pkgs.cargo-ndk
    pkgs.rustup
    pkgs.pkg-config
    pkgs.just
  ];

  ANDROID_HOME = sdk;
  ANDROID_SDK_ROOT = sdk;
  ANDROID_NDK_HOME = "${sdk}/ndk/${ndkVersion}";
  ANDROID_NDK_ROOT = "${sdk}/ndk/${ndkVersion}";
  JAVA_HOME = "${pkgs.jdk17}";

  # slint 的 android-activity 后端要编一个 Java 胶水类,靠 android-build crate
  # 去 SDK 里找 android.jar 与 d8.jar。它那套自动发现在 nix 的布局下找不到
  # (platforms/android-34 是指向另一个 store path 的符号链接),报的是
  # "No Android platforms found"。这两个变量是该 crate 的最高优先级来源,
  # 直接给出路径就跳过整个发现流程。
  ANDROID_JAR = "${sdk}/platforms/android-${platformVersion}/android.jar";
  ANDROID_D8_JAR = "${sdk}/build-tools/${buildToolsVersion}/lib/d8.jar";
  # 同一个 crate 还会为「用哪个 API level」打印警告,顺手定死。
  ANDROID_PLATFORM = platformVersion;

  # 依赖树里带 C 代码的 crate 由 cc-rs 驱动编译,而 cc-rs 找交叉编译器的顺序是
  # CC_<target> → CC → 按三元组猜 `<triple>-clang`。前两条都不设就落到第三条,
  # 可 NDK r23 起不带 API level 的 `aarch64-linux-android-clang` 已经不存在。
  # 目标专用变量优先级最高,设了两头都治。
  CC_aarch64_linux_android = "${ndkBin}/aarch64-linux-android${minSdk}-clang";
  AR_aarch64_linux_android = "${ndkBin}/llvm-ar";
  CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER =
    "${ndkBin}/aarch64-linux-android${minSdk}-clang";

  shellHook = ''
    # 交叉编译时宿主机的库路径永远是错的。外层 devshell 留下的 PKG_CONFIG_PATH
    # 指着宿主机的 .so,链接器会报 incompatible。
    unset PKG_CONFIG_PATH

    # AGP 会从 Maven 拉预编译的 aapt2,那个二进制在 NixOS 上跑不了;
    # 指向 androidenv build-tools 里那份(已 patchelf 过)。
    export GRADLE_OPTS="-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2"

    rustup target add aarch64-linux-android 2>/dev/null || \
      echo "note: run 'rustup default stable' first, then re-enter the shell"

    echo "Android toolchain ready: SDK $ANDROID_HOME, NDK ${ndkVersion}"
  '';
}
