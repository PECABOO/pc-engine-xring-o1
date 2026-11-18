#!/system/bin/sh
# 卸载模块：停掉网桥/守卫；默认保留启动器与 Wine prefix（prefix 是你的数据，绝不删）。
MODDIR=${0%/*}
LOG=/storage/emulated/0/wpz/service.log

for pat in wp_bridge.sh wp_nosteam.sh; do
    ps -A -o PID,ARGS 2>/dev/null | grep "[${pat:0:1}]${pat:1}" | while read -r pid rest; do
        kill -9 "$pid" 2>/dev/null
    done
done
echo "[$(date '+%m-%d %H:%M:%S')] module removed" >> "$LOG" 2>/dev/null

# 想连应用一起删：刷入前在模块目录里放一个 remove_app 文件
if [ -f "$MODDIR/remove_app" ]; then
    pm uninstall com.dsh.winplaylauncher 2>/dev/null
fi
exit 0
