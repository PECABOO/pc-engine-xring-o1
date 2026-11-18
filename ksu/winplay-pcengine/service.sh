#!/system/bin/sh
# late_start service：开机后把 root 网桥与 no-Steam 守卫拉起来，并保证它们活着。
MODDIR=${0%/*}
BRIDGE=$MODDIR/common/wp_bridge.sh
NOSTEAM=$MODDIR/common/wp_nosteam.sh
WPZ=/storage/emulated/0/wpz
LOG=$WPZ/service.log
APP=com.dsh.winplaylauncher

log() { echo "[$(date '+%m-%d %H:%M:%S')] $*" >> "$LOG"; }

# 1) 等系统起来（最多 180 s）
i=0
while [ "$(getprop sys.boot_completed)" != "1" ] && [ $i -lt 180 ]; do
    i=$((i + 1)); sleep 1
done
mkdir -p "$WPZ" 2>/dev/null
chmod 0777 "$WPZ" 2>/dev/null
log "service start (boot_completed after ${i}s)"

# 2) 让 uid 7100 能穿越 /data/media（raw 视图；FUSE 视图不受影响）
chmod o+x /data/media 2>/dev/null

# 3) 上次安装时 pm 不可用 → 补装启动器
if [ -f "$MODDIR/.install_app" ]; then
    if pm install -r "$MODDIR/common/WinPlayLauncher.apk" >> "$LOG" 2>&1; then
        rm -f "$MODDIR/.install_app"
        log "launcher installed on boot"
    fi
fi

# 4) 权限（幂等；应用没装就跳过）
if pm path "$APP" >/dev/null 2>&1; then
    appops set "$APP" MANAGE_EXTERNAL_STORAGE allow 2>/dev/null
    appops set "$APP" REQUEST_INSTALL_PACKAGES allow 2>/dev/null
    pm grant "$APP" android.permission.POST_NOTIFICATIONS 2>/dev/null
    pm grant "$APP" android.permission.READ_EXTERNAL_STORAGE 2>/dev/null
    pm grant "$APP" android.permission.WRITE_EXTERNAL_STORAGE 2>/dev/null
    log "permissions ensured for $APP"
fi

# 4.5) 把 Wine 侧小工具与应用清单同步进 prefix（引擎数据目录存在才做）
P=/data/user/0/com.xiaomi.winplay/files/prefix
W=$P/drive_c/windows
if [ -d "$W" ]; then
    cp -f "$MODDIR/common/wintask.exe" "$W/wintask.exe" 2>/dev/null
    cp -f "$MODDIR/common/winfix.exe"  "$W/winfix.exe"  2>/dev/null
    chown 7100:7100 "$W/wintask.exe" "$W/winfix.exe" 2>/dev/null
    chmod 0755 "$W/wintask.exe" "$W/winfix.exe" 2>/dev/null
fi
mkdir -p /storage/emulated/0/WinPlay 2>/dev/null
if [ ! -f /storage/emulated/0/WinPlay/apps.json ]; then
    cp -f "$MODDIR/common/apps.json" /storage/emulated/0/WinPlay/apps.json 2>/dev/null
    chmod 0666 /storage/emulated/0/WinPlay/apps.json 2>/dev/null
    log "apps.json seeded"
fi

# 5) 拉起网桥与 no-Steam 守卫（用 ps 判活：这个 ROM 的 pidof 对脚本不可靠）
start_bridge() {
    nohup setsid sh "$BRIDGE" </dev/null >/dev/null 2>&1 &
    log "bridge started pid=$!"
}
start_nosteam() {
    [ -f "$NOSTEAM" ] || return 0
    nohup setsid sh "$NOSTEAM" </dev/null >/dev/null 2>&1 &
    log "nosteam guard started pid=$!"
}

ps -A -o ARGS 2>/dev/null | grep -q "[w]p_bridge.sh"  || start_bridge
ps -A -o ARGS 2>/dev/null | grep -q "[w]p_nosteam.sh" || start_nosteam

# 6) 看门狗：网桥死了就重拉（每 30 s 一次，永不退出）
while true; do
    sleep 30
    if ! ps -A -o ARGS 2>/dev/null | grep -q "[w]p_bridge.sh"; then
        log "bridge gone, restarting"
        start_bridge
    fi
    if ! ps -A -o ARGS 2>/dev/null | grep -q "[w]p_nosteam.sh"; then
        start_nosteam
    fi
done
