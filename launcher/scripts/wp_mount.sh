#!/system/bin/sh
# ---------------------------------------------------------------------------
# 把平板内置存储挂进 Wine 的 C 盘（本机 wine 只认 C:，没有 D:/E:/Z:）
#   挂载点：  C:\sdcard  <-  /data/media/0        （整盘，读；写会被 MediaProvider 拦住）
#             C:\share   <-  /data/media/0/WinShare（双向读写，和 /sdcard/WinShare 同一个目录）
#   权限：    只做"加法"—— o+x 目录 + o+rX 文件，从不减少原有 owner/group 权限，
#             所以其他 App 对自己文件的读写完全不受影响（FUSE 侧权限由 MediaProvider 决定，
#             这里改的是 raw f2fs inode，只对直接读 /data/media 的进程生效）。
#   幂等：    每次启动都能重复跑；引擎重启后挂载会丢，重跑即恢复。
#   用法：    sh wp_mount.sh            安装/刷新挂载
#             sh wp_mount.sh status     查看状态
#             sh wp_mount.sh off        卸载
# ---------------------------------------------------------------------------
P=/data/user/0/com.xiaomi.winplay/files/prefix
SH=/data/media/0/WinShare
STAMP=/data/local/tmp/wp_perm.stamp
LOG=/storage/emulated/0/wpz/mount.log
WPZ=/storage/emulated/0/wpz

mkdir -p "$WPZ" 2>/dev/null
log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*" >> "$LOG" 2>/dev/null; }

EP=$(pidof com.xiaomi.winplay)
[ -n "$EP" ] || { log "engine not running"; echo "engine not running"; exit 10; }

bind_one() {   # $1=drive_c 下的目录名  $2=源目录
    mkdir -p "$P/drive_c/$1" 2>/dev/null
    chown 7100:7100 "$P/drive_c/$1" 2>/dev/null
    nsenter -t "$EP" -m -- umount "$P/drive_c/$1" 2>/dev/null
    if nsenter -t "$EP" -m -- mount --bind "$2" "$P/drive_c/$1" 2>/dev/null; then
        log "bind OK: C:\\$1 <- $2"
        return 0
    fi
    log "bind FAIL: C:\\$1 <- $2"
    return 1
}

case "$1" in
off)
    nsenter -t "$EP" -m -- umount "$P/drive_c/sdcard" 2>/dev/null
    nsenter -t "$EP" -m -- umount "$P/drive_c/share"  2>/dev/null
    log "unmounted"
    echo "unmounted"
    exit 0
    ;;
status)
    echo "engine=$EP"
    nsenter -t "$EP" -m -- mount 2>/dev/null | grep -E "drive_c/(sdcard|share)" || echo "(no bind)"
    ls -ld /data/media /data/media/0 2>/dev/null
    [ -f "$STAMP" ] && echo "perm stamp: $(ls -l "$STAMP" | awk '{print $6,$7,$8}')" || echo "perm stamp: (未做过全量)"
    exit 0
    ;;
esac

# 1) /data/media 必须 others 可进入，否则 uid 7100 连穿越都不行
chmod o+x /data/media /data/media/0 2>/dev/null

# 2) 放开 Windows 侧读权限：首次全量，之后只处理"新增/改动"的文件（后台跑，不拖慢启动）
#    整棵 Android/ 目录跳过（其他 App 的私有数据，绝不碰）
if [ ! -f "$STAMP" ]; then
    (
      find /data/media/0 -xdev \
        -path /data/media/0/Android -prune -o \
        -exec chmod o+rX {} + 2>/dev/null
      touch "$STAMP"
      log "perm: 首次全量 o+rX 完成"
    ) &
    log "perm: 首次全量放开中（后台）"
else
    (
      find /data/media/0 -xdev -newer "$STAMP" \
        -path /data/media/0/Android -prune -o \
        -exec chmod o+rX {} + 2>/dev/null
      touch "$STAMP"
      log "perm: 增量 o+rX 完成"
    ) &
    log "perm: 增量放开中（后台）"
fi

# 3) 挂载
mkdir -p "$SH" 2>/dev/null
chown 7100:7100 "$SH" 2>/dev/null
chmod 0770 "$SH" 2>/dev/null
RC=0
bind_one sdcard /data/media/0 || RC=1
bind_one share  "$SH"          || RC=1
log "mount done ep=$EP rc=$RC"
echo "mount done ep=$EP rc=$RC"
exit $RC
