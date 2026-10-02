#!/usr/bin/env bash
# 比较两次实际落盘的心跳，文件存在或进程存在都不能当作健康证明。
set -euo pipefail
if [ "$#" -ne 1 ]; then
  echo "usage: bash tools/diagnostics/check.sh <adb-serial>" >&2
  exit 2
fi
serial="$1"
heartbeat() {
  adb -s "$serial" exec-out run-as io.github.yebei199.btrearm \
    tail -n 100 files/diagnostics/events.log | grep ' event=heartbeat ' | tail -n 1
}
before="$(heartbeat)"
sleep 8
after="$(heartbeat)"
if [ -z "$after" ] || [ "$before" = "$after" ]; then
  echo "FAIL: no new persisted heartbeat within 8 seconds" >&2
  exit 1
fi
if [[ "$after" != *"observers=ready "* ]] || \
   [[ "$after" != *" dropped=0 write_failures=0 "* ]] || \
   [[ "$after" == *"snapshot_error="* ]]; then
  echo "FAIL: collector is degraded: $after" >&2
  exit 1
fi
echo "PASS: fresh persisted heartbeat, observers ready, no drops or write failures"
echo "$after"
