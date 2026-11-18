#!/system/bin/sh
# WinPlay 无 Steam 守卫：flag 存在时，看到 steam 进程就杀掉
FLAG=/storage/emulated/0/WinPlay/nosteam.flag
LOG=/storage/emulated/0/wpz/nosteam.log
log(){ echo "[$(date '+%H:%M:%S')] $*" >> "$LOG"; }
log "guard up pid=$$"
while true; do
  if [ -f "$FLAG" ]; then
    PIDS=$(ps -A -o PID,ARGS 2>/dev/null | awk '/[s]team\.exe|[s]teamwebhelper|[s]teamerrorreporter/{print $1}')
    if [ -n "$PIDS" ]; then
      for p in $PIDS; do kill -9 "$p" 2>/dev/null; done
      log "killed steam: $(echo $PIDS | tr '\n' ' ')"
      sleep 2
    fi
  fi
  sleep 3
done
