#!/usr/bin/env bash
# 输出目录必须是新目录，避免覆盖上一场游戏的证据。
set -euo pipefail
if [ "$#" -ne 2 ]; then
  echo "usage: bash tools/diagnostics/export.sh <adb-serial> <new-output-directory>" >&2
  exit 2
fi
serial="$1"
destination="$2"
mkdir -- "$destination"
adb -s "$serial" exec-out run-as io.github.yebei199.btrearm \
  tar -cf - files/diagnostics > "$destination/app-logs.tar.partial"
tar -tf "$destination/app-logs.tar.partial" > "$destination/contents.txt"
mv -- "$destination/app-logs.tar.partial" "$destination/app-logs.tar"
# 系统快照是导出时刻的状态，不能替代断联发生时的 HCI 记录。
for service in bluetooth_manager input; do
  adb -s "$serial" shell dumpsys "$service" > "$destination/$service.txt"
done
date -Iseconds > "$destination/exported-at.txt"
echo "Exported: $destination/app-logs.tar"
