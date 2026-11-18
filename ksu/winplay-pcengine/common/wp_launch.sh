#!/system/bin/sh
# ---------------------------------------------------------------------------
# WinPlay Launcher 启动桥
#   用法: sh wp_launch.sh <b64_exe> <b64_args> <screenoff 0|1> <steam 0|1>
#   参数用 base64 传入，彻底避开空格/引号/反斜杠/中文的转义问题。
#
# 协议（Zygisk 模块 winplay_run 每 500 ms 轮询）：
#   /storage/emulated/0/wpz/cmd2.txt
#   exePath|isUnixPath|seq|args|envs
# ---------------------------------------------------------------------------

WPZ=/storage/emulated/0/wpz
CMD=$WPZ/cmd2.txt
PKG=com.xiaomi.winplay
ACT=com.xiaomi.winplay/com.xiaomi.winplay.WineActivity

b64d() { echo "$1" | base64 -d 2>/dev/null; }
# 这个 ROM 的 toybox pidof 对 PE 进程名匹配不可靠，统一用 ps 判活
wt_running() { /system/bin/ps -A -o ARGS 2>/dev/null | /system/bin/grep -q "wintask[.]exe"; }
EXE=$(b64d "$1")
# ---- 参数位次防左移 ----
# 空字符串参数会被 shell/命令拼接吃掉，导致后面所有位置整体左移：
#   wp_launch.sh <exe> "" 0 0 1  ->  实际变成 <exe> 0 0 1
# 结果 steam 吃掉 screenoff 的 0，panel 吃掉 steam 的 0，$4(=1) 被当成 steam=1
# → 启动器明明没勾 Steam，引擎却走了 winplay://opensteam。
# 约定：空 ARGS 用 "-" 占位；同时兜住"已经被吃掉"的历史调用。
_RAW2="${2-}"
case "$_RAW2" in
  -)            ARGS="" ;;
  0|1|2|"")     ARGS="" ;;      # 占位丢失（旧调用）
  *)            ARGS=$(b64d "$_RAW2") ;;
esac
if [ "$_RAW2" = "0" ] || [ "$_RAW2" = "1" ]; then
    # 左移形态：$2=screenoff $3=steam $4=panel
    SCREENOFF="$_RAW2"
    STEAM=${3:-0}
    PANEL=${4:-1}
    echo "[$(date +%H:%M:%S)] WARN 参数左移已兜住: screenoff=$SCREENOFF steam=$STEAM panel=$PANEL" >&2
else
    # 第 3 参 = 启动后是否熄屏。默认 0（不动屏幕）；只在我自己调试时才显式传 1。
    SCREENOFF=${3:-0}
    STEAM=${4:-0}
    # 第 5 参 = 是否启用 Wine 内的任务面板（wintask）。默认 1=启用。
    PANEL=${5:-1}
fi
[ -z "$SCREENOFF" ] && SCREENOFF=0
[ -z "$STEAM" ] && STEAM=0
case "$STEAM" in 0|1) ;; *) STEAM=0 ;; esac
[ -z "$PANEL" ] && PANEL=1
case "$PANEL" in 0|1) ;; *) PANEL=1 ;; esac

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

# input 注入在本 ROM 上偶发 "Failure calling service input: Failed transaction"，
# 用有限重试 + 状态回读兜住（不改变语义，只是更稳）。
try_input() {
    _n=0
    while [ $_n -lt 3 ]; do
        _out=$(input "$@" 2>&1); _rc=$?
        case "$_out" in
            "*Failure*"|"*Exception*"|"*denied*"|"*not found*") ;;
            *) [ $_rc -eq 0 ] && return 0 ;;
        esac
        _n=$((_n + 1)); sleep 1
    done
    log "WARN input $* -> rc=$_rc out=$_out"
    return 1
}


next_seq() {
    _o=$(cut -d'|' -f3 "$CMD" 2>/dev/null | head -n1 | tr -dc '0-9')
    _n=$(date +%s)
    [ -n "$_o" ] && [ "$_n" -le "$_o" ] && _n=$((_o + 1))
    echo "$_n"
}
wr_cmd() {   # $1=exe  $2=args  $3=seq
    _t=$WPZ/.cmd2.$$
    printf '%s|0|%s|%s|\n' "$1" "$3" "$2" > "$_t" || return 1
    chmod 666 "$_t" 2>/dev/null
    mv -f "$_t" "$CMD" || return 1
    chmod 666 "$CMD" 2>/dev/null
}

wakefulness() { dumpsys power 2>/dev/null | /system/bin/grep -m1 -o 'mWakefulness=[A-Za-z]*' | cut -d= -f2; }

echo "----- winplay-launcher ($(id -u)) -----"
log "exe=[$EXE] args=[$ARGS] screenoff=$SCREENOFF steam=$STEAM"
if [ -z "$EXE" ]; then
    log "FATAL: exe path is empty"
    exit 10
fi

# 自检钩子：exe 传 "--noop" 时只空转，用来验证桥链路（不碰 cmd2.txt）
if [ "$EXE" = "--noop" ]; then
    log "noop: 心跳链路自检 12s"
    sleep 12
    log "noop done, wakefulness=$(wakefulness)"
    exit 0
fi

mkdir -p "$WPZ" 2>/dev/null
chmod 777 "$WPZ" 2>/dev/null

# 1. 亮屏 + 解锁（只有本来就是灭屏/锁屏时才滑动，避免在桌面误拉通知栏）
WAKE=$(wakefulness)
LOCK=$(dumpsys window 2>/dev/null | /system/bin/grep -m1 -o 'mDreamingLockscreen=[a-z]*' | cut -d= -f2)
log "wakefulness=${WAKE:-?} dreamingLockscreen=${LOCK:-?}"

NEED_UNLOCK=0
[ "$WAKE" != "Awake" ] && NEED_UNLOCK=1
[ "$LOCK" = "true" ] && NEED_UNLOCK=1

if [ "$NEED_UNLOCK" = "1" ]; then
    log "wake + unlock (menu / dismiss-keyguard / swipe)"
    try_input keyevent 224        # KEYCODE_WAKEUP
    sleep 1
    try_input keyevent 82         # KEYCODE_MENU：唤醒后先"点亮"锁屏
    wm dismiss-keyguard 2>/dev/null
    sleep 1
    try_input swipe 1600 1900 1600 400 200
    sleep 1
    log "after wake: wakefulness=$(wakefulness)"
else
    log "already awake & unlocked (skip wake/swipe)"
fi

# 1.5 Program Files 下的程序需要自己的工作目录（WinRAR 这类会秒退）
#     统一包一层 bat：cd /d + start，交给 cmd.exe 起。
PREFIX=/data/user/0/com.xiaomi.winplay/files/prefix
case "$EXE" in
    "C:\\Program Files\\"*|"C:\\Program Files (x86)\\"*)
        DIR=${EXE%\\*}
        BASE=${EXE##*\\}
        W=$PREFIX/drive_c/windows/_wpwrap.bat
        printf '@echo off\r\ncd /d "%s"\r\nstart "" "%s" %s\r\n' "$DIR" "$BASE" "$ARGS" > "$W" 2>/dev/null
        if [ -s "$W" ]; then
            chown 7100:7100 "$W" 2>/dev/null
            chmod 644 "$W" 2>/dev/null
            log "wrap -> C:\\windows\\_wpwrap.bat (cd /d \"$DIR\")"
            EXE='C:\windows\_wpwrap.bat'
            ARGS=''
        else
            log "WARN: cannot create wrap bat, launching directly"
        fi
        ;;
esac

# 1.6 带参数的程序：模块的 wine_run_app 通道会抛异常（实测 ERR wine_run_app threw），
#     统一改成 bat 包装，让 runNewApp 走 bat，参数写在 bat 里。
if [ -n "$ARGS" ]; then
    A=$PREFIX/drive_c/windows/_wpargs.bat
    # args 用分号分隔（模块原来的约定），转成空格
    SP=$(echo "$ARGS" | tr ';' ' ')
    printf '@echo off\r\nstart "" "%s" %s\r\n' "$EXE" "$SP" > "$A" 2>/dev/null
    if [ -s "$A" ]; then
        chown 7100:7100 "$A" 2>/dev/null
        chmod 644 "$A" 2>/dev/null
        log "wrap args -> C:\\windows\\_wpargs.bat (args: $SP)"
        EXE='C:\windows\_wpargs.bat'
        ARGS=''
    else
        log "WARN: cannot create args bat"
    fi
fi

# 2. 引擎进程（没起来就拉起来，冷启动要等 40~60s）
PID=$(pidof $PKG)
if [ -z "$PID" ]; then
    if [ "$STEAM" = "1" ]; then
        log "engine not running -> am start (带 Steam)"
        am start -a android.intent.action.VIEW -d "winplay://opensteam?appid=0&devmode=1" >/dev/null 2>&1
    else
        log "engine not running -> am start (不带 Steam，只起 Windows 桌面)"
        am start -n $ACT >/dev/null 2>&1
    fi
    i=0
    while [ -z "$(pidof $PKG)" ] && [ $i -lt 60 ]; do i=$((i + 1)); sleep 1; done
    PID=$(pidof $PKG)
    log "engine pid after ${i}s: [${PID}]"
    if [ -z "$PID" ]; then
        log "FATAL: engine did not come up within 60s"
        exit 11
    fi
    # 冷启动不再固定 sleep：轮询等 wine 桌面（explorer.exe /desktop）出现即可。
    # 早期 prefix 未预热 + WINEDEBUG 全量日志时确实要 40~70s；现在实测 3~6s。
    j=0
    while [ $j -lt 90 ]; do
        j=$((j + 1)); sleep 1
        [ "$(/system/bin/ps -A -o ARGS 2>/dev/null | /system/bin/grep -c '[e]xplorer.exe /desktop')" -ge 1 ] && break
    done
    log "cold start: desktop ready after ${j}s"
    # 模块的 WindowChannel 在进程启动时就已加载；清一次 res2.log 便于后续按 seq 判读
    : > /storage/emulated/0/wpz/res2.log 2>/dev/null
else
    log "engine running pid=$PID"
fi
log "wineserver=[$(pidof wineserver)]"

# 3. 确保 WineActivity 在前台（后台/灭屏时 runNewApp 静默无效）
case "$(dumpsys activity activities 2>/dev/null | /system/bin/grep -m1 -E 'topResumedActivity|mResumedActivity')" in
    *$PKG*) log "engine already foreground" ;;
    *)
        log "engine not foreground -> am start WineActivity"
        am start -n $ACT >/dev/null 2>&1
        i=0
        while [ $i -lt 15 ]; do
            sleep 1
            dumpsys activity activities 2>/dev/null | /system/bin/grep -qE "(topResumedActivity|mResumedActivity).*$PKG" && { log "foreground after $((i + 1))s"; break; }
            i=$((i + 1))
        done
        ;;
esac

# 3.6 把内置存储挂进 C 盘（C:\sdcard 整盘可读、C:\share 双向）
#     本机 wine 只暴露 C: 盘，所以只能用 C 下的目录；幂等，引擎重启后自动重挂。
MS=$(dirname "$0")/wp_mount.sh
[ -f "$MS" ] || MS=/data/local/tmp/wp/wp_mount.sh
if [ -f "$MS" ]; then
    _m=$(sh "$MS" 2>&1 | tail -n 1)
    log "mount: $_m"
else
    log "WARN: wp_mount.sh not found, skip storage mount"
fi

# 3.7 应用清单同步 + 常驻「任务面板」wintask（wine 桌面没有图标/任务栏）
if [ "$PANEL" != "1" ]; then
    # 用户在启动器里关掉了任务面板：不拉起，并把已经在跑的收掉
    if wt_running; then
        # wintask 属 uid 7100；脚本以 App 身份跑时 killall 会 EPERM，必须借 root
        if [ "$(id -u)" = "0" ]; then killall -9 wintask.exe 2>/dev/null
        else su -M -c 'killall -9 wintask.exe' 2>/dev/null; fi
        
# 3.8) 窗口几何修正守护：把跑出屏幕的窗口拉回来（SumatraPDF 这类会记住过大尺寸）
fx_running() { /system/bin/ps -A -o ARGS 2>/dev/null | /system/bin/grep -q "winfix[.]exe"; }
if [ -f "$PREFIX/drive_c/windows/winfix.exe" ] && ! fx_running; then
    printf '@echo off\r\nset MV_WATCH=1\r\nstart "" "C:\\windows\\winfix.exe"\r\nexit\r\n' \
        > "$PREFIX/drive_c/windows/_wpfix.bat" 2>/dev/null
    chown 7100:7100 "$PREFIX/drive_c/windows/_wpfix.bat" 2>/dev/null
    wr_cmd "C:\\windows\\_wpfix.bat" 0 0 || true
    log "winfix: 启动窗口几何守护"
    sleep 2
fi
log "panel: 已按设置关闭 wintask"
    else
        log "panel: 设置里关闭，跳过"
    fi
    PANEL_OFF=1
fi
if [ "${PANEL_OFF:-0}" = "1" ]; then :; else
cp -f /storage/emulated/0/WinPlay/wintask_apps.txt "$PREFIX/drive_c/windows/wintask_apps.txt" 2>/dev/null \
  && { chown 7100:7100 "$PREFIX/drive_c/windows/wintask_apps.txt" 2>/dev/null; chmod 644 "$PREFIX/drive_c/windows/wintask_apps.txt" 2>/dev/null; log "panel: apps list synced"; }
printf '@echo off\r\nstart "" "C:\\windows\\wintask.exe"\r\nexit\r\n' > "$PREFIX/drive_c/windows/wt.bat" 2>/dev/null
chown 7100:7100 "$PREFIX/drive_c/windows/wt.bat" 2>/dev/null
chmod 644 "$PREFIX/drive_c/windows/wt.bat" 2>/dev/null
if [ -x "$PREFIX/drive_c/windows/wintask.exe" ] && ! wt_running; then
    _ps=$(next_seq)
    wr_cmd 'C:\windows\wt.bat' '' "$_ps" && { log "panel: 拉起任务面板 seq=$_ps"; sleep 3; }
else
    log "panel: wintask 已在运行或未部署"
fi
fi

# 4. 写命令文件（seq 必须严格递增；tmp + rename 防止模块读到半个文件）
NOW=$(next_seq)
wr_cmd "$EXE" "$ARGS" "$NOW" || { log "FATAL: cannot write $CMD"; exit 12; }

log "cmd seq=$NOW written, content:"
cat "$CMD"
echo "----- waiting for module -----"

# 5. 回读模块日志
sleep 4
log "res2.log tail:"
tail -n 14 "$WPZ/res2.log" 2>/dev/null || log "(no res2.log)"
if grep -q "cmd seq=$NOW" "$WPZ/res2.log" 2>/dev/null; then
    log "OK: module picked up seq=$NOW"
else
    log "WARN: module 还没记录 seq=$NOW（可能仍在轮询或引擎未就绪）"
fi

# 6. 熄屏（OLED）：注入偶发失败，重试并用 mWakefulness 校验
if [ "$SCREENOFF" = "1" ]; then
    sleep 4
    _i=0
    while [ $_i -lt 4 ]; do
        try_input keyevent 26      # KEYCODE_POWER
        sleep 2
        _w=$(wakefulness)
        case "$_w" in
            Asleep|Dozing) log "screen off ok ($_w)"; break ;;
        esac
        _i=$((_i + 1))
    done
    [ $_i -ge 4 ] && log "WARN: screen still $_w after 4 tries"
fi

log "=== done"
exit 0
