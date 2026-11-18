#!/usr/bin/env bash
# WinPlay 应用安装器：把 Windows 程序放进引擎 prefix，patch Electron 兼容位，并生成启动 bat
# 用法: ./wapp.sh install <src(zip|exe|dir)> <name> [args...]
#       ./wapp.sh patch <name>          只重新 patch 已安装目录
#       ./wapp.sh list
set -uo pipefail
ADB=${ADB:-adb}
P=/data/user/0/com.xiaomi.winplay/files/prefix
APPS_JSON=/storage/emulated/0/WinPlay/apps.json
WORK=/tmp/wapp; mkdir -p $WORK

patch_pe() { python3 "$(dirname "$0")/patch_pe.py" "$1"; }

install_dir() {  # $1 本地目录, $2 name, $3 args
  local dir="$1" name="$2" args="${3:-}"
  echo "== patch PE =="
  find "$dir" -maxdepth 2 -type f \( -iname '*.exe' -o -iname '*.dll' \) -size -300M | while read -r f; do patch_pe "$f" || true; done
  local main=""
  # 优先最大的、名字不像卸载/安装体的 exe
  # 先排掉「其实是安装器」的 exe：NSIS / Inno Setup 会在体内留签名串。
  # 这类文件改名叫本体起不来（SumatraPDF 就吃过这个亏，会报 corrupted installation）。
  local cand=""
  while read -r f; do
    [ -z "$f" ] && continue
    if head -c 4000000 "$f" | grep -aq -m1 -e Nullsoft -e 'Inno Setup'; then
      echo "  ! 跳过我安装器载荷外的主程序: $(basename "$f")" >&2
      continue
    fi
    cand="$cand$f\n"
  done < <(find "$dir" -maxdepth 2 -type f -iname '*.exe')
  main=$(printf '%b' "$cand" \
      | grep -viE 'unins|uninst|setup|update|install|crash|helper' \
      | xargs -r ls -S 2>/dev/null | head -1)
  if [ -z "$main" ]; then
    # 退路同样要排掉安装器；全都排掉说明这个包只能用便携版/zip
    main=$(printf '%b' "$cand" | xargs -r ls -S 2>/dev/null | head -1)
  fi
  if [ -z "$main" ]; then
    echo "  错误：这个包里没有可用的主程序（只有安装器）。请换便携版 zip，或先用 7z 解开安装器取载荷。" >&2
    return 11
  fi
  echo "== push =="
  tar cf $WORK/$name.tar -C "$dir" .
  $ADB push $WORK/$name.tar /sdcard/$name.tar >/dev/null
  $ADB shell "su -c 'mkdir -p $P/drive_c/apps/$name && tar xf /sdcard/$name.tar -C $P/drive_c/apps/$name && chown -R 7100:7100 $P/drive_c/apps/$name && rm -f /sdcard/$name.tar'"
  local rel="${main#$dir}"; rel="${rel#/}"
  local winpath="C:\\apps\\$name\\$(echo "$rel" | tr '/' '\\')"
  echo "== register =="
  register "$name" "$winpath" "$args"
  echo "installed: $winpath"
}

register() {  # $1 name $2 winpath $3 args
python3 - "$1" "$2" "$3" <<'PY'
import json,sys,os,subprocess
name,exe,args=sys.argv[1],sys.argv[2],sys.argv[3]
path='/tmp/wapp/apps.json'
data=[]
if os.path.exists(path):
    try: data=json.load(open(path))
    except Exception: data=[]
data=[d for d in data if d.get('name')!=name]
data.append({'name':name,'exe':exe,'args':args})
json.dump(data,open(path,'w'),ensure_ascii=False,indent=1)
PY
  $ADB shell 'mkdir -p /storage/emulated/0/WinPlay' >/dev/null 2>&1
  $ADB push /tmp/wapp/apps.json $APPS_JSON >/dev/null
  $ADB shell "su -c 'chmod 666 $APPS_JSON'" >/dev/null 2>&1
}

case "${1:-}" in
  install)
    src="${2:?src}"; name="${3:?name}"; shift 3; args="${*:-}"
    d="$WORK/$name.src"; rm -rf "$d"; mkdir -p "$d"
    case "$src" in
      *.zip) unzip -oq "$src" -d "$d" ;;
      *.tar) tar xf "$src" -C "$d" ;;
      *.exe|*.msi)
        # 安装器在 wow64+FEX 下会卡死：优先在 PC 上用 7z 解开
        if 7z x -y -o"$d" "$src" >/dev/null 2>&1; then
          echo "  7z 解包完成"
          # electron-builder 常见结构：$PLUGINSDIR/app-64.7z 或 app-arm64.7z
          for pl in "$d"/'$PLUGINSDIR'/app-arm64.7z "$d"/'$PLUGINSDIR'/app-64.7z "$d"/'$PLUGINSDIR'/app-32.7z; do
            [ -f "$pl" ] && { echo "  展开 $(basename "$pl")"; rm -rf "$d.app"; mkdir -p "$d.app"; 7z x -y -o"$d.app" "$pl" >/dev/null; rm -rf "$d"; mv "$d.app" "$d"; break; }
          done
        else
          cp -a "$src" "$d/"
        fi ;;
      *) if [ -d "$src" ]; then cp -a "$src/." "$d/"; else cp -a "$src" "$d/"; fi ;;
    esac
    # 若只有一层同名目录，压平
    inner=$(ls "$d" | head -2 | tr '\n' ' ')
    install_dir "$d" "$name" "$args" ;;
  patch)
    name="${2:?name}"
    $ADB shell "su -c 'cd $P/drive_c/apps/$name && find . -maxdepth 2 -type f -iname \"*.exe\" -o -maxdepth 2 -type f -iname \"*.dll\"'" | tr -d '\r' > $WORK/list.txt
    echo "device-side patch 需要把文件拉回本地，改用 patchlocal"
    ;;
  list) $ADB shell "su -c 'cat $APPS_JSON'" | tr -d '\r' ;;
  *) sed -n '2,7p' "$0" ;;
esac
