#!/system/bin/sh
# ---------------------------------------------------------------------------
# WinPlay Launcher —— 把 staging 目录装进 Wine prefix
#   用法: sh install.sh <src_dir(staging)> <dirName>
#   root 执行：复制到 C:\apps\<dirName>，并把属主改成引擎 uid 7100
# ---------------------------------------------------------------------------
SRC="$1"
DIR="$2"
P=/data/user/0/com.xiaomi.winplay/files/prefix
DST="$P/drive_c/apps/$DIR"

log() { printf '[%s] %s\n' "$(date +%H:%M:%S)" "$*"; }

log "src=$SRC"
log "dst=$DST"

[ -n "$SRC" ] || { log "FATAL: src 为空"; exit 20; }
[ -n "$DIR" ] || { log "FATAL: dir 为空"; exit 21; }
[ -d "$SRC" ] || { log "FATAL: src 不存在"; exit 22; }
[ -d "$P/drive_c" ] || { log "FATAL: prefix drive_c 不存在"; exit 23; }

log "清理旧目录"
rm -rf "$DST" 2>/dev/null
mkdir -p "$DST" || { log "FATAL: mkdir 失败"; exit 24; }

log "复制中…"
cp -a "$SRC/." "$DST/" || { log "FATAL: cp 失败"; exit 25; }

log "修正属主与权限"
chown -R 7100:7100 "$DST" 2>/dev/null
chmod -R u+rwX "$DST" 2>/dev/null

N=$(find "$DST" -type f 2>/dev/null | wc -l)
SZ=$(du -sh "$DST" 2>/dev/null | awk '{print $1}')
E=$(find "$DST" -maxdepth 3 -iname '*.exe' 2>/dev/null | head -n 5)
log "完成：$N 个文件 / ${SZ:-?}"
log "exe 候选:"
echo "$E" | sed 's/^/   /'
exit 0
