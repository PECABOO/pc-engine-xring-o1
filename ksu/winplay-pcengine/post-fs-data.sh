#!/system/bin/sh
# 早期启动阶段：这里刻意什么都不做。
# 引擎的 mount namespace 只有 com.xiaomi.winplay 起来后才存在，挂载必须等应用启动后由
# wp_mount.sh 在引擎 namespace 里做（见 common/wp_launch.sh 第 3.6 步）。
MODDIR=${0%/*}
mkdir -p /storage/emulated/0/wpz
chmod 0777 /storage/emulated/0/wpz 2>/dev/null
exit 0
