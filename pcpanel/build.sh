#!/bin/bash
# 交叉编译 Wine 内的「任务面板」wintask.exe（ARM64，无 CRT）
set -e
cd "$(dirname "$0")"
LLVM=/usr/lib/llvm-21/bin/llvm-dlltool
$LLVM -m arm64 -d u32b.def -l u32b.lib
$LLVM -m arm64 -d k32c.def -l k32c.lib
$LLVM -m arm64 -d g32.def -l g32.lib
clang --target=aarch64-pc-windows-msvc -ffreestanding -O2 -c wintask.c -o wintask.o
clang --target=aarch64-pc-windows-msvc -ffreestanding -O2 -c winfix.c -o winfix.o
lld-link /subsystem:windows /entry:main /machine:arm64 /nodefaultlib wintask.o u32b.lib k32c.lib g32.lib /out:wintask.exe
lld-link /subsystem:windows /entry:main /machine:arm64 /nodefaultlib winfix.o u32b.lib k32c.lib g32.lib /out:winfix.exe
echo "== 产物: $(pwd)/wintask.exe ($(stat -c%s wintask.exe) B)"
