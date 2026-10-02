#!/usr/bin/env bash
set -euo pipefail
# 小型纯 Java 测试不依赖 Android、Gradle 或设备。
root="$(cd "$(dirname "$0")/../.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
javac -d "$work" \
  "$root/gradle/app/src/main/java/io/github/yebei199/btrearm/diagnostics/PersistentLog.java" \
  "$root/tools/diagnostics/PersistentLogTest.java"
java -cp "$work" io.github.yebei199/btrearm/diagnostics/PersistentLogTest
