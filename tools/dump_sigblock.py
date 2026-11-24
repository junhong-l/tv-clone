#!/usr/bin/env python3
"""解析 APK Signing Block，打印 v2 分块结构，用于对比 apksigner 的输出。"""
import struct, sys

def u32(b, o): return struct.unpack_from('<I', b, o)[0]
def u64(b, o): return struct.unpack_from('<Q', b, o)[0]

def find_eocd(b):
    for i in range(len(b) - 22, max(0, len(b) - 65557), -1):
        if u32(b, i) == 0x06054b50:
            return i
    return -1

def walk(buf, depth=0, maxdepth=3):
    """把一块按 length-prefixed 序列递归展开。"""
    off = 0
    idx = 0
    while off + 4 <= len(buf) and idx < 8:
        ln = u32(buf, off)
        if ln > len(buf) - off - 4:
            print('  ' * depth + f'[{idx}] len={ln} 越界(remain={len(buf)-off-4})')
            return
        body = buf[off + 4: off + 4 + ln]
        print('  ' * depth + f'[{idx}] len={ln}')
        if depth < maxdepth and ln >= 4:
            walk(body, depth + 1, maxdepth)
        off += 4 + ln
        idx += 1
    if off != len(buf):
        print('  ' * depth + f'!! 剩余 {len(buf) - off} 字节未解析')

def main(path):
    b = open(path, 'rb').read()
    eocd = find_eocd(b)
    cd_off = u32(b, eocd + 16)
    magic = b[cd_off - 16: cd_off]
    print(f'{path}\n  size={len(b)} cd_off={cd_off} magic={magic!r}')
    if magic != b'APK Sig Block 42':
        print('  没有签名块'); return
    size2 = u64(b, cd_off - 24)
    start = cd_off - size2 - 8
    print(f'  block start={start} size={size2}')
    p = start + 8
    end = cd_off - 24
    while p < end:
        ln = u64(b, p); pid = u32(b, p + 8)
        val = b[p + 12: p + 8 + ln]
        print(f'  pair id=0x{pid:08x} len={ln} valueLen={len(val)}')
        if pid == 0x7109871a:
            walk(val, 1, 2)
        p += 8 + ln

if __name__ == '__main__':
    for f in sys.argv[1:]:
        main(f); print()
