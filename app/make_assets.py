#!/usr/bin/env python3
"""生成 TV 应用图标（各密度）与 Leanback banner（320x180）。"""
import os
from PIL import Image, ImageDraw, ImageFont

OUT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "res")
BOLD = "/root/.fonts/NotoSansCJKsc-Bold.otf"
REG = "/root/.fonts/NotoSansCJKsc-Regular.otf"

ACCENT = (76, 154, 255)
DEEP = (43, 98, 196)
INK = (11, 15, 20)
TEXT = (232, 238, 246)
SUB = (143, 163, 184)


def font(path, size):
    try:
        return ImageFont.truetype(path, size)
    except Exception:
        return ImageFont.load_default()


def gradient(size, top, bottom):
    img = Image.new("RGB", (1, size), top)
    d = ImageDraw.Draw(img)
    for y in range(size):
        t = y / max(1, size - 1)
        d.point((0, y), tuple(int(top[i] + (bottom[i] - top[i]) * t) for i in range(3)))
    return img.resize((size, size), Image.BILINEAR)


def icon_master(size=768):
    """两个错位的白色圆角方块 = 同一应用的两份实例。"""
    s = size
    base = gradient(s, ACCENT, DEEP).convert("RGBA")

    # 柔光
    glow = Image.new("L", (s, s), 0)
    ImageDraw.Draw(glow).ellipse((-s * 0.25, -s * 0.45, s * 0.85, s * 0.55), fill=70)
    base = Image.composite(Image.new("RGBA", (s, s), (255, 255, 255, 60)), base, glow)

    layer = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    d = ImageDraw.Draw(layer)
    r = s * 0.115
    w = s * 0.40
    # 后面那张（半透明）
    x0, y0 = s * 0.235, s * 0.255
    d.rounded_rectangle([x0, y0, x0 + w, y0 + w], radius=r, fill=(255, 255, 255, 130))
    # 前面那张
    x1, y1 = s * 0.355, s * 0.375
    d.rounded_rectangle([x1, y1, x1 + w, y1 + w], radius=r, fill=(255, 255, 255, 242))

    base = Image.alpha_composite(base, layer)

    mask = Image.new("L", (s, s), 0)
    ImageDraw.Draw(mask).rounded_rectangle([0, 0, s - 1, s - 1], radius=int(s * 0.235), fill=255)
    out = Image.new("RGBA", (s, s), (0, 0, 0, 0))
    out.paste(base, (0, 0), mask)
    return out


def write_icons():
    master = icon_master(768)
    for folder, px in (("mipmap-mdpi", 48), ("mipmap-hdpi", 72), ("mipmap-xhdpi", 96),
                       ("mipmap-xxhdpi", 144), ("mipmap-xxxhdpi", 192)):
        d = os.path.join(OUT, folder)
        os.makedirs(d, exist_ok=True)
        im = master.resize((px, px), Image.LANCZOS)
        if im.getpixel((1, 1))[3] != 0:
            raise SystemExit(f"{folder} 圆角外不是透明：{im.getpixel((1, 1))}")
        im.save(os.path.join(d, "ic_launcher.png"))
        print(f"icon {folder}/ic_launcher.png {px}x{px}")


def write_banner():
    s = 2  # 超采样
    W, H = 320 * s, 180 * s
    MARGIN = 22 * s
    img = Image.new("RGB", (W, H), (14, 20, 28))

    # 左侧蓝色光斑
    for i in range(60, 0, -1):
        a = int(46 * (i / 60) ** 1.7)
        layer = Image.new("RGBA", (W, H), (0, 0, 0, 0))
        ImageDraw.Draw(layer).ellipse(
            [-W * 0.18 + i, -H * 0.5 + i, W * 0.52 - i, H * 1.35 - i],
            fill=(76, 154, 255, a))
        img = Image.alpha_composite(img.convert("RGBA"), layer).convert("RGB")

    # 图标：垂直居中
    icon_px = 84 * s
    ic = icon_master(384).resize((icon_px, icon_px), Image.LANCZOS)
    img = img.convert("RGBA")
    img.alpha_composite(ic, (26 * s, (H - icon_px) // 2))

    d = ImageDraw.Draw(img)
    left = 26 * s + icon_px + 16 * s
    max_w = W - left - MARGIN

    title = "双开管家"
    f_title = font(BOLD, 38 * s)
    while d.textlength(title, font=f_title) > max_w and f_title.size > 12:
        f_title = font(BOLD, f_title.size - 2 * s)

    sub = "原版 / 克隆 并存"
    f_sub = font(REG, 17 * s)
    while d.textlength(sub, font=f_sub) > max_w and f_sub.size > 8:
        f_sub = font(REG, f_sub.size - s)

    total = f_title.size + 6 * s + f_sub.size
    top = (H - total) // 2 - 2 * s
    d.text((left, top), title, font=f_title, fill=TEXT)
    d.text((left, top + f_title.size + 6 * s), sub, font=f_sub, fill=(178, 198, 218))

    # 断言不越界（含 2px 容差）
    for text, f in ((title, f_title), (sub, f_sub)):
        w = d.textlength(text, font=f)
        assert left + w <= W - MARGIN + 2, f"banner 文本越界: {text} w={w} limit={W - MARGIN - left}"

    out = img.convert("RGB").resize((320, 180), Image.LANCZOS)
    path = os.path.join(OUT, "drawable-xhdpi", "banner.png")
    os.makedirs(os.path.dirname(path), exist_ok=True)
    out.save(path)
    print("banner drawable-xhdpi/banner.png 320x180")


if __name__ == "__main__":
    write_icons()
    write_banner()
