#!/usr/bin/env bash
# WinPlay 控制脚本（PC 侧，通过 adb 操作平板上的 com.xiaomi.winplay + winplay_run Zygisk 模块）
set -uo pipefail
ADB=${ADB:-adb}
CMD=/storage/emulated/0/wpz/cmd2.txt
RES=/storage/emulated/0/wpz/res2.log
SEQ_FILE=/tmp/wpseq
P=/data/user/0/com.xiaomi.winplay/files/prefix

s() { $ADB shell "$@" 2>/dev/null | tr -d '\r'; }
r() { $ADB shell su -c "$1" 2>/dev/null | tr -d '\r'; }
nextseq() { date +%s; }

case "${1:-}" in
  boot)      # 无 Steam 起引擎
    r "am force-stop com.xiaomi.winplay"
    sleep 2
    r "am start -n com.xiaomi.winplay/com.xiaomi.winplay.WineActivity" >/dev/null
    echo "booting engine (no steam)..."
    for i in $(seq 1 30); do
      sleep 2
      c=$(r "ps -A -o ARGS" | grep -c 'explorer.exe /desktop')
      [ "${c:-0}" -ge 1 ] && { echo "desktop up after $((i*2))s"; exit 0; }
    done
    echo "timeout waiting desktop"; exit 1 ;;
  bootsteam)
    r "am force-stop com.xiaomi.winplay"; sleep 2
    r "am start -a android.intent.action.VIEW -d 'winplay://opensteam?appid=0&devmode=1'" >/dev/null
    echo "booting with steam..."; sleep 60 ;;
  stop)
    r "am force-stop com.xiaomi.winplay"
    r "ps -A -o PID,ARGS | grep -E '[w]ine' | awk '{print \$1}' | while read p; do kill -9 \$p; done"
    echo stopped ;;
  wake)
    r "settings put system screen_brightness 1" >/dev/null
    s "input keyevent 224" >/dev/null; sleep 1
    s "input keyevent 82" >/dev/null; sleep 1
    s "input swipe 1600 1900 1600 400 200" >/dev/null; sleep 2
    r "am start -n com.xiaomi.winplay/com.xiaomi.winplay.WineActivity" >/dev/null 2>&1
    sleep 2
    echo "awake @ brightness 1, engine fronted" ;;
  run)       # run <winpath> [args] [env]  —— 屏幕/Activity 必须 resumed，否则 runNewApp 静默无效
    path="${2:?usage: run <C:\\path\\app.exe> [args] [env]}"; args="${3:-}"; env="${4:-}"
    s "input keyevent 224" >/dev/null 2>&1
    r "am start -n com.xiaomi.winplay/com.xiaomi.winplay.WineActivity" >/dev/null 2>&1
    sleep 2
    seq=$(nextseq)
    tmp=$(mktemp)
    printf '%s|0|%s|%s|%s\n' "$path" "$seq" "$args" "$env" > "$tmp"
    $ADB push "$tmp" $CMD >/dev/null 2>&1; rm -f "$tmp"
    r "chmod 666 $CMD"
    echo "sent seq=$seq path=$path"; sleep "${WAIT:-12}"
    [ -n "${SLEEP_AFTER:-}" ] && s "input keyevent 26" >/dev/null
    r "tail -5 $RES" ;;
  runu)      # unix 路径
    path="${2:?}"; seq=$(nextseq); tmp=$(mktemp)
    printf '%s|1|%s||\n' "$path" "$seq" > "$tmp"
    $ADB push "$tmp" $CMD >/dev/null 2>&1; rm -f "$tmp"; r "chmod 666 $CMD"
    echo "sent seq=$seq unix=$path"; sleep "${WAIT:-12}"; r "tail -4 $RES" ;;
  ps)  r "ps -A -o PID,ARGS | grep -E '[w]ine' | cut -c1-90" ;;
  log) r "ls -t /data/user/0/com.xiaomi.winplay/files/wine_logs/ | head -3" ;;
  tail) r "tail -${2:-40} /data/user/0/com.xiaomi.winplay/files/wine_logs/$(r 'ls -t /data/user/0/com.xiaomi.winplay/files/wine_logs/ | head -1')" ;;
  layers) r "dumpsys SurfaceFlinger --list | grep -i -E 'winplay|WineActivity' | head -20" ;;
  shot)     # 亮屏到最低亮度 → 截图 → 熄屏
    r "settings put system screen_brightness 1" >/dev/null
    s "input keyevent 224" >/dev/null; s "input keyevent 82" >/dev/null; sleep 1
    s "input swipe 1600 1900 1600 400 200" >/dev/null; sleep 1
    out="${2:-/tmp/wp.png}"
    $ADB exec-out screencap > /tmp/_s.raw 2>/dev/null
    dd if=/tmp/_s.raw of=/tmp/_s.body bs=16 skip=1 status=none
    magick -size 3200x2136 -depth 8 rgba:/tmp/_s.body -resize 45% "$out"
    s "input keyevent 26" >/dev/null
    echo "$out $(stat -c%s "$out") bytes" ;;
  off) s "input keyevent 26" >/dev/null; echo "screen off" ;;
  dim) r "settings put system screen_brightness_mode 0; settings put system screen_brightness 1"; echo "dim=1" ;;
  *) sed -n '2,6p' "$0"; echo "cmds: boot bootsteam stop wake run runu ps log tail layers shot off dim" ;;
esac
