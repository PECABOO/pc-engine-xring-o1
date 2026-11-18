#!/system/bin/sh
# ---------------------------------------------------------------------------
# winplay 命令桥守护进程（shell / root 身份运行，不依赖 su 挂载）
#   请求: /storage/emulated/0/wpz/launch_req.txt   seq|screenoff|b64exe|b64args
#   结果: /storage/emulated/0/wpz/launch_res.txt   seq=<seq> ... 脚本输出
#   安装: /storage/emulated/0/wpz/install_req.txt seq|dirName|stagingPath
#   结果: /storage/emulated/0/wpz/install_res.txt seq=<seq> ... 脚本输出
#   心跳: /storage/emulated/0/wpz/.bridge_alive    （epoch 秒）
# ---------------------------------------------------------------------------
WPZ=/storage/emulated/0/wpz
REQ=$WPZ/launch_req.txt
RES=$WPZ/launch_res.txt
ALIVE=$WPZ/.bridge_alive
SELF=$(dirname "$0")/wp_launch.sh
[ -f "$SELF" ] || SELF=/data/local/tmp/wp/wp_launch.sh
INST=$(dirname "$0")/install.sh
[ -f "$INST" ] || INST=/data/local/tmp/wp/install.sh
IREQ=$WPZ/install_req.txt
IRES=$WPZ/install_res.txt

trap '' HUP INT TERM          # 不随 adb shell 退出/挂断而死

# ---- 单实例锁：service.sh、启动器、手动启动都可能拉起它，只允许一份 ----
# mkdir 是原子的：并发时只有一个能成功，其余立刻退出（不能用 mkdir -p，它对已存在目录不报错）
LOCK=/data/local/tmp/.wp_bridge.lock
if mkdir "$LOCK" 2>/dev/null; then
    echo $$ > "$LOCK/pid"
elif [ -n "$(cat "$LOCK/pid" 2>/dev/null)" ] && kill -0 "$(cat "$LOCK/pid" 2>/dev/null)" 2>/dev/null; then
    echo "[$(date)] bridge already running pid=$(cat "$LOCK/pid" 2>/dev/null), this one exits" >> /storage/emulated/0/wpz/bridge.log
    exit 0
else
    rm -rf "$LOCK"                       # 上次异常退出留下的死锁
    mkdir "$LOCK" 2>/dev/null || exit 0
    echo $$ > "$LOCK/pid"
fi
trap 'rm -rf "$LOCK"' EXIT INT TERM HUP

# 锁文件可能因强杀而残留：再做一次「最小 PID 胜出」的兜底判定
# （toybox ps 的 ARGS 列可能截断，所以只按 PID 比较，不看命令行尾部）
OLDER=$(ps -A -o PID,ARGS 2>/dev/null | awk -v me=$$ '/wp_bridge\.sh/ && $1+0 < me+0 {print $1; exit}')
if [ -n "$OLDER" ]; then
    echo "[$(date)] bridge older instance pid=$OLDER exists, this one ($$) exits" >> /storage/emulated/0/wpz/bridge.log
    exit 0
fi


# ---- no-Steam 守卫（flag 存在时把引擎自己拉起的 steam 杀掉）----
NS=$(dirname "$0")/wp_nosteam.sh
[ -f "$NS" ] || NS=/data/local/tmp/wp/wp_nosteam.sh
if [ -f "$NS" ] && ! ps -A -o ARGS 2>/dev/null | grep -q "[w]p_nosteam.sh"; then
    nohup setsid sh "$NS" </dev/null >/dev/null 2>&1 &
    echo "[$(date)] nosteam guard started" >> /storage/emulated/0/wpz/bridge.log
fi

mkdir -p "$WPZ" 2>/dev/null
echo "[$(date)] bridge up pid=$$ uid=$(id -u) script=$SELF" >> "$WPZ/bridge.log"

# 重启后不要把上一次的请求重跑一遍：启动时把现存 req 的 seq 当作"已处理"
LAST=""
if [ -f "$REQ" ]; then
    LAST=$(cut -d'|' -f1 "$REQ" 2>/dev/null | tr -dc '0-9')
    echo "[$(date +%H:%M:%S)] resume: last req seq=$LAST skipped" >> "$WPZ/bridge.log"
fi
ILAST=""
if [ -f "$IREQ" ]; then
    ILAST=$(cut -d'|' -f1 "$IREQ" 2>/dev/null | tr -dc '0-9')
fi
TICK=0
while true; do
    date +%s > "$ALIVE" 2>/dev/null
    # 每 ~2s 写一次引擎状态，供 App 显示（App 自己看不到别人的进程）
    TICK=$((TICK + 1))
    if [ $((TICK % 4)) -eq 0 ]; then
        printf 'engine=%s|wineserver=%s|ts=%s\n' \
            "$(pidof com.xiaomi.winplay)" "$(pidof wineserver)" "$(date +%s)" > "$WPZ/status.txt" 2>/dev/null
        chmod 666 "$WPZ/status.txt" 2>/dev/null
    fi
    # ---- 安装请求（把 staging 目录搬进 Wine prefix，需要 root 或可写 prefix）
    if [ -f "$IREQ" ]; then
        ISEQ=$(cut -d'|' -f1 "$IREQ" 2>/dev/null | tr -dc '0-9')
        if [ -n "$ISEQ" ] && [ "$ISEQ" != "$ILAST" ]; then
            ILAST=$ISEQ
            IDIR=$(cut -d'|' -f2 "$IREQ")
            ISRC=$(cut -d'|' -f3 "$IREQ")
            echo "[$(date +%H:%M:%S)] install seq=$ISEQ dir=$IDIR src=$ISRC" >> "$WPZ/bridge.log"
            ( while true; do date +%s > "$ALIVE" 2>/dev/null; sleep 1; done ) &
            _HB2=$!
            { echo "seq=$ISEQ"; sh "$INST" "$ISRC" "$IDIR" 2>&1; echo "rc=$?"; } > "$IRES.tmp" 2>&1
            kill $_HB2 2>/dev/null
            date +%s > "$ALIVE" 2>/dev/null
            mv -f "$IRES.tmp" "$IRES" 2>/dev/null
            chmod 666 "$IRES" 2>/dev/null
        fi
    fi
    if [ -f "$REQ" ]; then
        SEQ=$(cut -d'|' -f1 "$REQ" 2>/dev/null | tr -dc '0-9')
        if [ -n "$SEQ" ] && [ "$SEQ" != "$LAST" ]; then
            LAST=$SEQ
            SO=$(cut -d'|' -f2 "$REQ")
            EXE=$(cut -d'|' -f3 "$REQ")
            ARG=$(cut -d'|' -f4 "$REQ")
            echo "[$(date +%H:%M:%S)] req seq=$SEQ" >> "$WPZ/bridge.log"
            # 跑脚本期间单线程循环会阻塞，起一个心跳子进程，避免 App 误判"网桥已死"
            ( while true; do date +%s > "$ALIVE" 2>/dev/null; sleep 1; done ) &
            _HB=$!
            { echo "seq=$SEQ"; sh "$SELF" "$EXE" "$ARG" "$SO" 2>&1; echo "rc=$?"; } > "$RES.tmp" 2>&1
            kill $_HB 2>/dev/null
            date +%s > "$ALIVE" 2>/dev/null
            mv -f "$RES.tmp" "$RES" 2>/dev/null
        fi
    fi
    sleep 0.5 2>/dev/null || sleep 1
done
