#!/usr/bin/env bash
set -euo pipefail
# 只在独占临时目录编译平台替身和真实业务类，每条行为用独立 JVM 隔离静态状态。
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mode="${1:-run}"
case "$mode" in run|--compile-only|--list) ;; *) echo 'usage: test.sh [--compile-only|--list]' >&2; exit 2 ;; esac
mkdir -p "$work/sources" "$work/classes"
awk -v out="$work/sources" '
  /^@@ / { file = out "/" $2; next }
  file { print > file }
' "$root/tools/observer/platform.sources"
# 平台源码使用扁平文件名，package 由 javac 放到正确目录。
fixtures=("$work"/sources/*.java)
business="$root/gradle/app/src/main/java/io/github/yebei199/btrearm"
javac -encoding UTF-8 -d "$work/classes" "${fixtures[@]}" \
  "$business/Rearm.java" "$business/Privileged.java" "$business/PrivilegedConnect.java" \
  "$root/tools/observer/ObserverLifecycleTest.java"
jdk="$(dirname "$(dirname "$(readlink -f "$(command -v javac)")")")"
cc -shared -fPIC -Wall -Wextra -Werror -I "$jdk/include" -I "$jdk/include/linux" \
  "$root/tools/observer/native-events.c" -o "$work/libbtrearm.so"
if [[ "$mode" == --compile-only ]]; then
  echo 'COMPILED: real Java entry points, platform doubles and JNI event sink; no behavior executed'
  exit 0
fi
runner=(java "-Djava.library.path=$work" -cp "$work/classes" io.github.yebei199.btrearm.ObserverLifecycleTest)
if [[ "$mode" == --list ]]; then
  "${runner[@]}" --list
  exit 0
fi
cd "$root"
mkdir -p .dispatch
"${runner[@]}" --suite .dispatch/1-observer-junit.xml
