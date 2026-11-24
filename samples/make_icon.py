#!/usr/bin/env python3
"""生成一个不依赖第三方库的 PNG 图标（纯 zlib 写 IHDR/IDAT/IEND）。"""
import struct, sys, zlib


def chunk(tag, data):
    return struct.pack(">I", len(data)) + tag + data + struct.pack(">I", zlib.crc32(tag + data) & 0xFFFFFFFF)


def make(path, size=192):
    rows = []
    for y in range(size):
        row = bytearray([0])  # filter type 0
        for x in range(size):
            # 圆角矩形底 + 中心播放三角
            r = size * 0.22
            inside = True
            for cx, cy in ((r, r), (size - r, r), (r, size - r), (size - r, size - r)):
                if (x < r and y < r and (cx, cy) == (r, r)) or \
                   (x > size - r and y < r and (cx, cy) == (size - r, r)) or \
                   (x < r and y > size - r and (cx, cy) == (r, size - r)) or \
                   (x > size - r and y > size - r and (cx, cy) == (size - r, size - r)):
                    if (x - cx) ** 2 + (y - cy) ** 2 > r * r:
                        inside = False
            if not inside:
                row += bytes((0, 0, 0, 0))
                continue
            t = y / size
            bg = (int(20 + 10 * t), int(120 - 40 * t), int(200 - 30 * t), 255)
            # 三角：x 从 0.34 到 0.70，y 线性收敛
            fx = (x / size - 0.34) / 0.36
            if 0 <= fx <= 1:
                half = (1 - fx) * 0.26
                if abs(y / size - 0.5) <= half:
                    bg = (255, 255, 255, 255)
            row += bytes(bg)
        rows.append(bytes(row))
    raw = b"".join(rows)
    png = (b"\x89PNG\r\n\x1a\n"
           + chunk(b"IHDR", struct.pack(">IIBBBBB", size, size, 8, 6, 0, 0, 0))
           + chunk(b"IDAT", zlib.compress(raw, 9))
           + chunk(b"IEND", b""))
    open(path, "wb").write(png)
    print("icon ->", path, len(png), "bytes")


if __name__ == "__main__":
    make(sys.argv[1] if len(sys.argv) > 1 else "ic_launcher.png")
