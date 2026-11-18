#!/usr/bin/env bash
# 生成 KernelSU 模块 zip。
#   ./make-bundle.sh          轻量包：只含启动器/脚本/Zygisk 桥，引擎 APK 由设备自带（约 150 KB）
#   ./make-bundle.sh --full   完整包：额外从已连接的设备里 pull 出 PC 引擎与游戏中心 APK（约 560 MB）
#
# 为什么轻量包不带 APK：com.xiaomi.winplay 的 base.apk 是 483 MB、游戏中心 56 MB。
# GitHub 单文件上限 100 MB，且这两个是小米的闭源分发包，不适合放进公开仓库。
# 同款设备刷机时它们本来就在；换机/重装场景用 --full。
set -euo pipefail
cd "$(dirname "$0")"
SRC=ksu/winplay-pcengine
VER=$(sed -n 's/^version=//p' "$SRC/module.prop")
OUT=dist/winplay-pcengine-${VER}.zip
mkdir -p dist
rm -f "$OUT"

( cd "$SRC" && zip -qr "../../$OUT" . -x 'apks/*' '.git/*' )

if [ "${1:-}" = "--full" ]; then
    FULL=dist/winplay-pcengine-${VER}-full.zip
    TMP=$(mktemp -d)
    mkdir -p "$TMP/apks"
    echo "== 从设备拉取 APK"
    for pair in "com.xiaomi.winplay:winplay.apk" "com.xiaomi.gamecenter:gamecenter.apk"; do
        pkg=${pair%%:*}; name=${pair##*:}
        path=$(adb shell "pm path $pkg" | tr -d '\r' | sed -n 's/^package://p' | head -1)
        [ -n "$path" ] || { echo "! 设备上没有 $pkg，跳过"; continue; }
        echo "   $pkg ← $path"
        adb exec-out "su -c 'cat $path'" > "$TMP/apks/$name"
        ls -l "$TMP/apks/$name" | awk '{print "   " $5 " B"}'
    done
    cp -a "$SRC/." "$TMP/"
    rm -rf "$TMP/apks/.git"
    ( cd "$TMP" && zip -qr -"$OLDPWD/$FULL" . -x '.git/*' )
    rm -rf "$TMP"
    echo "== 完整包: $FULL ($(du -h "$FULL" | cut -f1))"
fi

echo "== 轻量包: $OUT ($(du -h "$OUT" | cut -f1))"
unzip -l "$OUT" | tail -3
