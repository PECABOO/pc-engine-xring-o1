#!/bin/bash
# parallel range downloader: pdl.sh URL OUTFILE TOTAL WORKERS CHUNK
URL="$1"; OUT="$2"; TOTAL="$3"; W="${4:-8}"; CH="${5:-4194304}"
truncate -s "$TOTAL" "$OUT" 2>/dev/null
span=$(( (TOTAL + W - 1) / W ))
worker() {
  local id=$1 start=$(( $1 * span )) end=$(( ($1 + 1) * span - 1 ))
  [ $end -ge $TOTAL ] && end=$((TOTAL - 1))
  local pos=$start fails=0
  while [ $pos -le $end ]; do
    local e=$((pos + CH - 1)); [ $e -gt $end ] && e=$end
    curl -s -r "$pos-$e" --connect-timeout 10 --max-time 90 -o "/tmp/w$id.part" "$URL" 2>/dev/null
    local got=$(stat -c %s "/tmp/w$id.part" 2>/dev/null || echo 0)
    if [ "$got" -gt 0 ]; then
      dd if="/tmp/w$id.part" of="$OUT" bs=1M seek=$pos oflag=seek_bytes conv=notrunc status=none 2>/dev/null
      pos=$((pos + got)); fails=0
    else
      fails=$((fails+1)); [ $fails -gt 400 ] && break; sleep 1
    fi
  done
  echo "worker$id done at $pos (end $end)"
}
for i in $(seq 0 $((W-1))); do worker $i & done
wait
echo "FINAL size=$(stat -c %s $OUT) expect=$TOTAL"
