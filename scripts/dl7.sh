#!/bin/bash
URL="https://vscode.download.prss.microsoft.com/dbazure/download/stable/2ccd690cbff1569e4a83d7c43d45101f817401dc/VSCode-win32-arm64-1.80.2.zip"
OUT=/tmp/vsc180b.zip
TOTAL=125723259
CH=$((1*1024*1024))
PROXY=""
: > $OUT
pos=0; fails=0
while [ "$pos" -lt "$TOTAL" ]; do
  end=$((pos+CH-1)); [ "$end" -ge "$TOTAL" ] && end=$((TOTAL-1))
  use=""
  [ $fails -ge 2 ] && use=""
  curl -s $use -D /tmp/h7.txt -r "$pos-$end" --connect-timeout 10 --max-time 60 -o /tmp/c7.part "$URL"
  got=$(stat -c %s /tmp/c7.part 2>/dev/null || echo 0)
  cr=$(grep -i '^content-range:' /tmp/h7.txt | tr -d '\r' | awk "{print \$3}")
  st=$(echo "$cr" | cut -d- -f1)
  if [ "$got" -gt 0 ] && [ "$st" = "$pos" ]; then
    cat /tmp/c7.part >> $OUT; pos=$((pos+got)); fails=0
    [ $((pos % (10*CH))) -lt $CH ] && echo "pos=$pos"
  else
    fails=$((fails+1)); sleep 1
    [ $fails -ge 10 ] && { echo "HARD_FAIL pos=$pos st=$st got=$got"; exit 1; }
  fi
done
echo "DONE $pos $(stat -c %s $OUT)"
