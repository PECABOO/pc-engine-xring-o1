#!/bin/bash
# 把本机文件放进 Wine 的 C: 盘（= /data/user/0/com.xiaomi.winplay/files/prefix/drive_c）
# 用法: ./put.sh <本地文件> [C盘里的子目录，默认 apps/_inbox]
set -e
SRC="$1"; SUB="${2:-apps/_inbox}"
[ -f "$SRC" ] || { echo "用法: $0 <本地文件> [子目录]"; exit 1; }
[ -n "$(adb devices | sed -n 2p)" ] || { echo "没有设备"; exit 1; }
P=/data/user/0/com.xiaomi.winplay/files/prefix
BASE=$(basename "$SRC")
echo "→ push 到 /data/local/tmp/$BASE"
adb push "$SRC" "/data/local/tmp/$BASE" >/dev/null
adb shell "su -c 'mkdir -p \"$P/drive_c/$SUB\" && cp -f \"/data/local/tmp/$BASE\" \"$P/drive_c/$SUB/\" && chown -R 7100:7100 \"$P/drive_c/$SUB\" && rm -f \"/data/local/tmp/$BASE\" && ls -l \"$P/drive_c/$SUB/$BASE\"'"
echo "✓ Wine 里路径: C:\\${SUB//\//\\}\\$BASE"
