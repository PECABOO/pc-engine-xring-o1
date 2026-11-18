#!/usr/bin/env bash
cd "$(dirname "$0")/.."
for n in everything cpuz python-embed nodejs gitportable scummvm ffmpeg; do
  echo "### $(date +%H:%M:%S) $n" >> test/rest.log
  timeout 1200 ./test/wtest.sh one "$n" >> test/rest.log 2>&1
  echo "### rc=$? done $n" >> test/rest.log
done
echo "ALL DONE $(date +%H:%M:%S)" >> test/rest.log
