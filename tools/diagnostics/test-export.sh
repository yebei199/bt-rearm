#!/usr/bin/env bash
# 在隔离临时目录中用 mock adb 驱动真实导出入口，绝不访问设备。
set -euo pipefail
# 脚本位置决定被测入口。
root="$(cd "$(dirname "$0")/../.." && pwd)"
# 每次运行独占测试资源。
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT
mkdir -p "$work/bin" "$work/source/files/diagnostics"
printf 'heartbeat acl disconnected reconnected\n' > "$work/source/files/diagnostics/events.jsonl"
tar -cf "$work/fixture.tar" -C "$work/source" files/diagnostics
cat > "$work/bin/adb" <<'MOCK'
#!/usr/bin/env bash
# 只模拟导出所需边界，未识别的调用直接失败。
set -euo pipefail
printf '%s\n' "$*" >> "$MOCK_CALLS"
if [[ "$*" == '-s mock-serial exec-out run-as io.github.yebei199.btrearm tar -cf - files/diagnostics' ]]; then
  if [[ "${MOCK_FAIL:-}" == archive ]]; then exit 17; fi
  if [[ "${MOCK_FAIL:-}" == invalid-archive ]]; then
    printf 'invalid archive\n'; exit 0
  fi
  cat "$MOCK_ARCHIVE"
elif [[ "$*" == '-s mock-serial shell dumpsys input' ]]; then
  if [[ "${MOCK_FAIL:-}" == input ]]; then exit 18; fi
  printf 'mock input snapshot\n'
elif [[ "$*" == '-s mock-serial shell dumpsys bluetooth_manager' ]]; then
  printf 'unsafe Bluetooth dump invoked\n'
else
  echo "unexpected adb command: $*" >&2
  exit 19
fi
MOCK
chmod +x "$work/bin/adb"
export PATH="$work/bin:$PATH" MOCK_ARCHIVE="$work/fixture.tar"

# 每个案例独占输出和调用记录。
prepare_case() {
  mkdir -p "$work/$1"
  export MOCK_CALLS="$work/$1/calls" MOCK_FAIL=""
  : > "$MOCK_CALLS"
}

# 成功导出后，归档中必须保留日志原文，输入快照及时间可供用户读取。
test_exports_logs_and_input() {
  prepare_case success
  bash "$root/tools/diagnostics/export.sh" mock-serial "$work/success/output"
  tar -xf "$work/success/output/app-logs.tar" -C "$work/success"
  cmp "$work/source/files/diagnostics/events.jsonl" "$work/success/files/diagnostics/events.jsonl"
  printf 'mock input snapshot\n' > "$work/success/expected-input.txt"
  cmp "$work/success/expected-input.txt" "$work/success/output/input.txt"
  [[ -s "$work/success/output/contents.txt" && -s "$work/success/output/exported-at.txt" ]]
  [[ ! -e "$work/success/output/app-logs.tar.partial" ]]
}

# 采集命令只允许应用归档和 input 快照，额外命令会使测试失败。
test_avoids_bluetooth_dump_and_bugreport() {
  prepare_case safe
  bash "$root/tools/diagnostics/export.sh" mock-serial "$work/safe/output"
  printf '%s\n' \
    '-s mock-serial exec-out run-as io.github.yebei199.btrearm tar -cf - files/diagnostics' \
    '-s mock-serial shell dumpsys input' > "$work/safe/expected-calls"
  diff -u "$work/safe/expected-calls" "$MOCK_CALLS"
  [[ ! -e "$work/safe/output/bluetooth_manager.txt" ]]
}

# 应用日志导出失败必须返回非零，并停止后续快照及成功标记。
test_archive_failure_is_nonzero() {
  prepare_case archive-failure
  export MOCK_FAIL=archive
  if bash "$root/tools/diagnostics/export.sh" mock-serial "$work/archive-failure/output"; then
    echo 'archive failure unexpectedly succeeded' >&2; return 1
  fi
  [[ ! -e "$work/archive-failure/output/app-logs.tar" ]]
  [[ ! -e "$work/archive-failure/output/exported-at.txt" ]]
  [[ "$(wc -l < "$MOCK_CALLS")" -eq 1 ]]
}

# ADB 成功返回无效归档时，tar 校验必须阻止完成标记和后续快照。
test_invalid_archive_is_nonzero() {
  prepare_case invalid-archive
  export MOCK_FAIL=invalid-archive
  if bash "$root/tools/diagnostics/export.sh" mock-serial "$work/invalid-archive/output"; then
    echo 'invalid archive unexpectedly accepted' >&2; return 1
  fi
  [[ ! -e "$work/invalid-archive/output/app-logs.tar" ]]
  [[ ! -e "$work/invalid-archive/output/exported-at.txt" ]]
  [[ "$(wc -l < "$MOCK_CALLS")" -eq 1 ]]
}

# 输入快照失败也必须失败，禁止把不完整导出报告为成功。
test_input_failure_is_nonzero() {
  prepare_case input-failure
  export MOCK_FAIL=input
  if bash "$root/tools/diagnostics/export.sh" mock-serial "$work/input-failure/output"; then
    echo 'input failure unexpectedly succeeded' >&2; return 1
  fi
  [[ -f "$work/input-failure/output/app-logs.tar" ]]
  [[ ! -e "$work/input-failure/output/exported-at.txt" ]]
}

# 已有输出目录中的证据必须原样保留，而且不得发出任何 ADB 调用。
test_existing_directory_is_preserved() {
  prepare_case existing
  mkdir "$work/existing/output"
  printf 'previous evidence\n' > "$work/existing/output/app-logs.tar"
  cp "$work/existing/output/app-logs.tar" "$work/existing/expected-archive"
  if bash "$root/tools/diagnostics/export.sh" mock-serial "$work/existing/output"; then
    echo 'existing directory unexpectedly overwritten' >&2; return 1
  fi
  cmp "$work/existing/expected-archive" "$work/existing/output/app-logs.tar"
  [[ ! -s "$MOCK_CALLS" ]]
}

# 参数错误必须早于设备访问和输出创建被拒绝。
test_invalid_arguments_are_rejected() {
  prepare_case arguments
  if bash "$root/tools/diagnostics/export.sh" mock-serial; then
    echo 'missing output argument unexpectedly accepted' >&2; return 1
  fi
  [[ ! -s "$MOCK_CALLS" ]]
}

# 单项选择用于 RED 证明，全套运行用于回归。
if [[ "$#" -gt 0 ]]; then
  "$1"
else
  for test_name in test_exports_logs_and_input test_avoids_bluetooth_dump_and_bugreport \
    test_archive_failure_is_nonzero test_invalid_archive_is_nonzero test_input_failure_is_nonzero \
    test_existing_directory_is_preserved test_invalid_arguments_are_rejected; do
    "$test_name"
    echo "PASS: $test_name"
  done
fi
