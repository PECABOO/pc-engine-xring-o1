#!/usr/bin/env python3
"""WinPlay 兼容补丁：抬高 PE 栈保留 + 把 EL0 非法的 pmccntr_el0 读改为 cntvct_el0。
只在可执行段内匹配指令，避免误改数据。用法: patch_pe.py <file>；有改动返回 0。"""
import sys, struct

def sections(d):
    e = struct.unpack_from('<I', d, 0x3c)[0]
    n = struct.unpack_from('<H', d, e + 6)[0]
    optsz = struct.unpack_from('<H', d, e + 20)[0]
    base = e + 24 + optsz
    out = []
    for i in range(n):
        o = base + i * 40
        if o + 40 > len(d):
            break
        cs = struct.unpack_from('<I', d, o + 36)[0]
        va = struct.unpack_from('<I', d, o + 12)[0]
        rs = struct.unpack_from('<I', d, o + 16)[0]
        ro = struct.unpack_from('<I', d, o + 20)[0]
        out.append((bool(cs & 0x20000000), ro, rs))
    return out

def main(path):
    try:
        d = bytearray(open(path, 'rb').read())
    except Exception:
        return 1
    if len(d) < 0x400 or d[:2] != b'MZ':
        return 1
    e = struct.unpack_from('<I', d, 0x3c)[0]
    if e + 0x60 > len(d):
        return 1
    magic = struct.unpack_from('<H', d, e + 24)[0]
    if magic not in (0x10b, 0x20b):
        return 1
    secs = sections(d)
    def in_exec(off):
        return any(x and ro <= off < ro + rs for x, ro, rs in secs)
    opt = e + 24
    changed = 0
    is_dll = path.lower().endswith('.dll')
    if is_dll:
        pass
    elif magic == 0x20b:
        cur = struct.unpack_from('<Q', d, opt + 0x48)[0]
        if 0 < cur < 0x10000000:
            struct.pack_into('<Q', d, opt + 0x48, 0x10000000); changed += 1
    else:
        cur = struct.unpack_from('<I', d, opt + 0x48)[0]
        if 0 < cur < 0x08000000:
            struct.pack_into('<I', d, opt + 0x48, 0x01000000); changed += 1
    for rt in range(32):
        if is_dll:
            break
        old = (0xd53b9d00 | rt).to_bytes(4, 'little')
        new = (0xd53be000 | rt).to_bytes(4, 'little')
        start = 0
        while True:
            i = d.find(old, start)
            if i < 0:
                break
            if in_exec(i):
                d[i:i + 4] = new
                changed += 1
            start = i + 1
    if changed:
        open(path, 'wb').write(d)
        print(f'  patched {path} ({changed} changes)')
        return 0
    return 1

if __name__ == '__main__':
    sys.exit(main(sys.argv[1]))
