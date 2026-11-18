#!/bin/bash
# ---------------------------------------------------------------------------
# 安装 / 启动命令网桥（不需要 root，用 adb shell 身份即可）
#   每次重启平板后需要重新跑一次；App 端会显示网桥是否在线。
# 用法: ./install_bridge.sh
# ---------------------------------------------------------------------------
set -e
cd "$(dirname "$0")"

echo "== 推脚本到 /data/local/tmp/wp/ =="
adb shell 'mkdir -p /data/local/tmp/wp'
adb push scripts/wp_launch.sh /data/local/tmp/wp/wp_launch.sh
adb push scripts/wp_bridge.sh /data/local/tmp/wp/wp_bridge.sh
adb push scripts/install.sh  /data/local/tmp/wp/install.sh
adb push scripts/wp_mount.sh /data/local/tmp/wp/wp_mount.sh
adb push scripts/wp_nosteam.sh /data/local/tmp/wp/wp_nosteam.sh
adb shell 'su -c "chmod 755 /data/local/tmp/wp/*.sh" 2>/dev/null; true'

echo "== 启动守护进程 =="
# 注意：Android 上没有 pgrep（toybox 不带），必须用 ps 解析；$pid 要转义，让它在设备端展开
adb shell 'su -c "ps -A -o PID,ARGS | grep wp_bridge.sh | grep -v grep | while read pid rest; do kill -9 \$pid; done" 2>/dev/null; rm -f /storage/emulated/0/wpz/.bridge_alive; true' || true
# 关键：stdin/stdout 全部重定向到 /dev/null 并用 setsid 脱离会话，
# 否则 adb shell 会一直等在这个 pty 上不返回；再配合脚本里的 trap '' HUP 才不会被带走。
start_once() {
    # 用 su 启动：网桥是 root 才能在 init mount namespace 里写 Wine prefix
    timeout 8 adb shell 'su -c "nohup setsid sh /data/local/tmp/wp/wp_bridge.sh </dev/null >/dev/null 2>&1 &" >/dev/null 2>&1; sleep 1; echo started' >/dev/null 2>&1
    sleep 2
    timeout 10 adb shell 'now=$(date +%s); al=$(cat /storage/emulated/0/wpz/.bridge_alive 2>/dev/null); echo "$now $al"' 2>/dev/null \
      | awk '{ if ($2 != "" && $1 - $2 <= 6) { print "OK 心跳新鲜: " $2 " (now " $1 ")"; exit 0 } else { print ".. 心跳不新鲜: " $2 " (now " $1 ")"; exit 1 } }'
}

echo "== 启动守护进程 =="
ok=0
for i in 1 2 3; do
    if start_once; then ok=1; break; fi
    echo "   第 $i 次启动没成，重试"
done
[ "$ok" = "1" ] || { echo "!! 守护进程起不来，检查 /storage/emulated/0/wpz/bridge.log"; exit 1; }

echo "== 最近日志 =="
timeout 10 adb shell 'tail -n 4 /storage/emulated/0/wpz/bridge.log'
