#!/bin/bash
# 让 Windows 程序直接访问平板内置存储
# 结论：本机 wine 只暴露 C: 盘（D:/E:/Z: 全部不存在），所以只能用 C: 下的目录做挂载点。
#   ./share.sh setup            建 C:\share  <->  /sdcard/WinShare（双向读写）
#   ./share.sh readall          把整个内置存储挂到 C:\sdcard（只读参考）
#   ./share.sh allow <目录名>    给 /sdcard 的某个子目录开"对 Windows 可读"（默认 Download）
#   ./share.sh publish          让 Windows 写出的文件对其他 App 可读
#   ./share.sh status / off
# 引擎每次重启后挂载会丢，重跑 setup + readall 即可。
set -e
P=/data/user/0/com.xiaomi.winplay/files/prefix
SH=/data/media/0/WinShare
EP=$(adb shell 'su -c "pidof com.xiaomi.winplay"' | tr -d '\r')
[ -n "$EP" ] || { echo "引擎没在跑，先启动 PC 引擎"; exit 1; }
b() { adb shell "su -c 'mkdir -p $P/drive_c/$1; chown 7100:7100 $P/drive_c/$1; nsenter -t $EP -m -- umount $P/drive_c/$1 2>/dev/null; nsenter -t $EP -m -- mount --bind $2 $P/drive_c/$1'"; }
case "$1" in
setup)
  adb shell "su -c 'mkdir -p $SH; chown 7100:7100 $SH; chmod 0770 $SH'"
  b share $SH
  echo "✓ /sdcard/WinShare  <->  C:\\share   双向读写"
  ;;
readall)
  adb shell "su -c 'chmod o+x /data/media; chmod o+x /data/media/0'"
  b sdcard /data/media/0
  echo "✓ 内置存储 -> C:\\sdcard（各目录还要 allow 一次才可读）"
  ;;
allow)
  D="${2:-Download}"
  adb shell "su -c 'chmod o+x /data/media; chmod o+x /data/media/0; chmod -R o+rX /data/media/0/$D'"
  echo "✓ /sdcard/$D 已对 Windows 可读"
  ;;
publish)
  adb shell "su -c 'chmod -R o+rX $SH'"
  echo "✓ WinShare 内文件已对其他 App 可读"
  ;;
status)
  adb shell "su -c 'ls -ld $P/drive_c/share $P/drive_c/sdcard 2>/dev/null'"
  adb shell "su -c 'nsenter -t $EP -m -- mount | grep -c \"drive_c/sdcard\"'"
  ;;
off)
  adb shell "su -c 'nsenter -t $EP -m -- umount $P/drive_c/share 2>/dev/null; nsenter -t $EP -m -- umount $P/drive_c/sdcard 2>/dev/null; true'"
  echo "✓ 已卸载"
  ;;
*) sed -n '2,11p' "$0"; exit 1;;
esac
