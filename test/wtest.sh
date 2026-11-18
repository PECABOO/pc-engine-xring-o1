#!/usr/bin/env bash
# WinPlay 批量兼容测试：下载 → 解包 → 补丁 → 安装 → 启动 → 判定 → 记录
# 用法:
#   ./wtest.sh batch           跑 targets.tsv 里所有 status=pending 的
#   ./wtest.sh one <name>      只跑一个
#   ./wtest.sh report           打印矩阵
set -uo pipefail
ROOT=$(cd "$(dirname "$0")/.." && pwd)
T=$ROOT/test
STATE=$T/state.tsv
TARGETS=$T/targets.tsv
ADB=${ADB:-adb}
P=/data/user/0/com.xiaomi.winplay/files/prefix
WPZ=/storage/emulated/0/wpz
touch "$STATE"

die() { echo "ERR $*"; exit 1; }

# ---------- 设备侧小工具 ----------
r() { $ADB shell "su -c \"$1\"" 2>/dev/null | tr -d '\r'; }
shot_sig() {
  $ADB exec-out screencap > /tmp/wt_s.raw 2>/dev/null
  dd if=/tmp/wt_s.raw of=/tmp/wt_s.body bs=16 skip=1 2>/dev/null
  md5sum /tmp/wt_s.body 2>/dev/null | cut -c1-8
}
wake() {
  r "input keyevent 224" >/dev/null 2>&1
  local st; st=$(r "dumpsys window | grep -m1 mDreamingLockscreen")
  case "$st" in *mDreamingLockscreen=true*) r "input keyevent 82; input swipe 1600 1900 1600 400 200" >/dev/null 2>&1; sleep 1;; esac
  r "input keyevent 82" >/dev/null 2>&1
  r "am start -n com.xiaomi.winplay/com.xiaomi.winplay.WineActivity" >/dev/null 2>&1
  sleep 3
  r "dumpsys power | grep -m1 mWakefulness=" | grep -q Awake || r "input keyevent 224" >/dev/null 2>&1
}
canary() { # 用 notepad 验证通道活性；失败则重启引擎
  wake
  launch 'C:\windows\system32\notepad.exe' >/dev/null
  sleep 8
  local n; n=$(r "ps -A -o ARGS | grep -ci '[n]otepad.exe'")
  [ "${n:-0}" -ge 1 ] && { r "killall -9 notepad.exe 2>/dev/null; true" >/dev/null 2>&1; return 0; }
  return 1
}
engine_ok() {
  local n; n=$(r "ps -A -o ARGS | grep -c 'explorer.exe /desktop'")
  [ "${n:-0}" -ge 1 ] || return 1
  return 0
}
engine_boot() {
  engine_ok && return 0
  r "am force-stop com.xiaomi.winplay; sleep 2; ps -A -o PID,ARGS|grep -E '[w]ine'|awk '{print \$1}'|while read p; do kill -9 \$p; done" >/dev/null 2>&1
  r "am start -a android.intent.action.VIEW -d 'winplay://opensteam?appid=0&devmode=1'" >/dev/null 2>&1
  for i in $(seq 1 20); do sleep 6; engine_ok && { echo "  引擎就绪(+$((i*6))s)"; return 0; }; done
  return 1
}
launch() { # $1 win path
  local seq; seq=$(date +%s)
  printf '%s|0|%s||\n' "$1" "$seq" > /tmp/wt_c.txt
  $ADB push /tmp/wt_c.txt $WPZ/cmd2.txt >/dev/null 2>&1
  r "chmod 666 $WPZ/cmd2.txt" >/dev/null 2>&1
  echo "$seq"
}

# ---------- 下载 ----------
UA='Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/131.0 Safari/537.36'
fetch() { # $1 url $2 out
  [ -s "$2" ] && [ "$(stat -c%s "$2")" -gt 100000 ] && { echo "  cached $(stat -c%s "$2")"; return 0; }
  rm -f "$2"
  curl -sSL --retry 3 --retry-delay 2 --max-time 3600 -o "$2" "$1" && [ -s "$2" ] && [ "$(stat -c%s "$2")" -gt 100000 ] && { echo "  got $(stat -c%s "$2")"; return 0; }
  curl -sSL -A "$UA" --retry 3 --retry-delay 2 --max-time 3600 -o "$2" "$1" && [ -s "$2" ] && [ "$(stat -c%s "$2")" -gt 100000 ] && { echo "  got $(stat -c%s "$2")"; return 0; }
  echo "  download rejected ($(stat -c%s "$2" 2>/dev/null || echo 0) bytes)"; rm -f "$2"; return 1
}

# ---------- 主流程 ----------
one() {
  local name="$1"
  local line; line=$(awk -F'\t' -v n="$name" '$1==n' "$TARGETS" | head -1)
  [ -z "$line" ] && { echo "no target $name"; return 1; }
  local src rel args
  src=$(echo "$line" | cut -f2); rel=$(echo "$line" | cut -f3); args=$(echo "$line" | cut -f4)
  echo "=== $name ==="
  local ext="${src##*.}"; local dl="/tmp/wt/dl_$name.$ext"
  mkdir -p /tmp/wt
  case "$src" in
    http*) fetch "$src" "$dl" || { record "$name" "download-fail" "" "" ""; return 1; } ;;
    *) dl="$src" ;;
  esac
  local sd="/tmp/wt/src_$name"; rm -rf "$sd"; mkdir -p "$sd"
  case "$dl" in
    *.zip) unzip -oq "$dl" -d "$sd" >/dev/null 2>&1 ;;
    *.7z)  7z x -y -o"$sd" "$dl" >/dev/null 2>&1 ;;
    *.tar.gz|*.tgz) tar xf "$dl" -C "$sd" >/dev/null 2>&1 ;;
    *.exe|*.msi)
      7z x -y -o"$sd" "$dl" >/dev/null 2>&1
      for pl in "$sd"/'$PLUGINSDIR'/app-arm64.7z "$sd"/'$PLUGINSDIR'/app-64.7z "$sd"/'$PLUGINSDIR'/app-32.7z; do
        [ -f "$pl" ] && { rm -rf "$sd.x"; mkdir -p "$sd.x"; 7z x -y -o"$sd.x" "$pl" >/dev/null 2>&1; rm -rf "$sd"; mv "$sd.x" "$sd"; break; }
      done ;;
  esac
  # 找主程序
  local main
  if [ -n "$rel" ] && [ -f "$sd/$rel" ]; then main="$sd/$rel"; else
    main=$(find "$sd" -maxdepth 3 -type f -iname '*.exe' | grep -viE 'unins|uninst|setup|update|install|crash|helper|vc_redist' | xargs -r ls -S 2>/dev/null | head -1 | tr -d '\r')
  fi
  [ -f "$main" ] || { echo "  no exe found"; record "$name" "no-exe" "" "" ""; return 1; }
  local base; base=$(basename "$main")
  echo "  main: ${main#$sd/}"
  python3 "$ROOT/patch_pe.py" "$main" >/dev/null 2>&1
  # 推
  tar cf /tmp/wt/$name.tar -C "$sd" . && $ADB push /tmp/wt/$name.tar /sdcard/wt_$name.tar >/dev/null 2>&1
  r "mkdir -p $P/drive_c/apps/$name && tar xf /sdcard/wt_$name.tar -C $P/drive_c/apps/$name && chown -R 7100:7100 $P/drive_c/apps/$name && rm -f /sdcard/wt_$name.tar" >/dev/null
  local relwin="${main#$sd/}"; relwin="${relwin//\//\\}"
  local win="C:\\apps\\$name\\$relwin"
  local bat_args="$args"
  local target="$win"
  if [ -n "$bat_args" ]; then
    python3 - "$win" "$bat_args" <<'PY'
import sys
exe,args=sys.argv[1],sys.argv[2].replace(';',' ')
bat=f'@echo off\r\nstart "" "{exe}" {args}\r\n'
open('/tmp/wt/run.bat','wb').write(bat.encode())
PY
    $ADB push /tmp/wt/run.bat /sdcard/wt_run.bat >/dev/null 2>&1
    r "cp /sdcard/wt_run.bat $P/drive_c/apps/$name/run.bat && chown 7100:7100 $P/drive_c/apps/$name/run.bat && rm -f /sdcard/wt_run.bat" >/dev/null
    target="C:\\apps\\$name\\run.bat"
  fi
  engine_boot || { echo "  engine not ok"; record "$name" "engine-fail" "" "" ""; return 1; }
  if ! canary; then
    echo "  canary failed -> restart engine"
    engine_boot >/dev/null 2>&1 || true
    if ! canary; then echo "  engine dead"; record "$name" "engine-dead" "" "" ""; return 1; fi
  fi
  wake
  local before; before=$(shot_sig)
  local seq; seq=$(launch "$target")
  local wait=${WT_WAIT:-15}; sleep "$wait"
  local procs fds fast
  procs=$(r "ps -A -o ARGS | grep -ci '[\\\\]${base}'")
  fds=$(r "p=\$(ps -A -o PID,ARGS | grep -i '[\\\\]${base}' | head -1 | awk '{print \$1}'); [ -n \"\$p\" ] && ls /proc/\$p/fd 2>/dev/null | wc -l")
  fast=$(r "p=\$(ps -A -o PID,ARGS | grep -i '[\\\\]${base}' | head -1 | awk '{print \$1}'); [ -n \"\$p\" ] && ls -l /proc/\$p/fd 2>/dev/null | grep -c fastipc")
  local after; after=$(shot_sig)
  local verdict="fail"
  [ "${procs:-0}" -ge 1 ] && verdict="run"
  [ "${procs:-0}" -ge 1 ] && [ "$after" != "$before" ] && verdict="render"
  echo "  procs=$procs fds=${fds:-0} fastipc=${fast:-0} shot ${before}->${after} => $verdict"
  record "$name" "$verdict" "${procs:-0}" "${fast:-0}" "shot=${before}->${after}"
  # 清理该 app 的残留进程
  r "killall -9 ${base} 2>/dev/null; true" >/dev/null 2>&1
  sleep 2
}

record() { # name verdict procs fast extra
  grep -v "^$1	" "$STATE" > "$STATE.tmp" 2>/dev/null || true
  mv "$STATE.tmp" "$STATE" 2>/dev/null || true
  printf '%s\t%s\t%s\t%s\t%s\n' "$1" "$2" "${3:--}" "${4:--}" "${5:--}" >> "$STATE"
}

batch() {
  awk -F'\t' 'NF>=2 && $1 !~ /^#/{print $1}' "$TARGETS" | while read -r n; do
    [ -z "$n" ] && continue
    if grep -q "^$n	" "$STATE" 2>/dev/null; then continue; fi
    one "$n"
  done
}

report() {
  printf '%-22s %-10s %-6s %-8s %s\n' APP VERDICT PROCS FASTIPC EXTRA
  sort "$STATE" | awk -F'\t' '{printf "%-22s %-10s %-6s %-8s %s\n",$1,$2,$3,$4,$5}'
}

case "${1:-}" in
  one) one "${2:?name}" ;;
  batch) batch ;;
  report) report ;;
  *) sed -n '2,8p' "$0" ;;
esac
