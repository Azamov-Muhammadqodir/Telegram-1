"""Neyrogram logotipini PNG ko'rinishida chizadi (eski Android launcherlari va preview uchun).
Vektor (drawable/neyro_icon_foreground.xml) bilan bir xil geometriya, 108x108 viewport.
"""
import os
import sys

from PIL import Image, ImageDraw

RES = sys.argv[1]
PREVIEW = sys.argv[2]
SS = 4  # supersampling

C1 = (0x8E, 0x5C, 0xFF)
C2 = (0x3F, 0x6B, 0xFF)
SPARK = (0x7C, 0xF3, 0xFF)


def gradient(size):
    img = Image.new('RGB', (size, size))
    px = img.load()
    for y in range(size):
        for x in range(size):
            t = (x + y) / (2 * (size - 1))
            px[x, y] = tuple(int(C1[i] + (C2[i] - C1[i]) * t) for i in range(3))
    return img


def draw_logo(size, shape):
    big = size * SS
    bg = gradient(big).convert('RGBA')
    mask = Image.new('L', (big, big), 0)
    md = ImageDraw.Draw(mask)
    margin = big * 0.02
    if shape == 'round':
        md.ellipse([margin, margin, big - margin, big - margin], fill=255)
    else:
        md.rounded_rectangle([margin, margin, big - margin, big - margin], radius=big * 0.22, fill=255)

    fg = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    d = ImageDraw.Draw(fg)
    # adaptive icon'ning ko'rinadigan qismi: viewport [18..90]
    s = big / 72.0

    def p(x, y):
        return ((x - 18) * s, (y - 18) * s)

    def node(x, y, r, color):
        cx, cy = p(x, y)
        d.ellipse([cx - r * s, cy - r * s, cx + r * s, cy + r * s], fill=color)

    d.line([p(67, 40), p(75, 32)], fill=(255, 255, 255, 180), width=int(2.2 * s))
    pts = [p(41, 68), p(41, 40), p(67, 68), p(67, 40)]
    d.line(pts, fill=(255, 255, 255, 255), width=int(6 * s), joint='curve')
    for x, y in [(41, 68), (41, 40), (67, 68), (67, 40)]:
        node(x, y, 6, (255, 255, 255, 255))
    node(75, 32, 3.5, SPARK + (255,))

    bg.alpha_composite(fg)
    out = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}
for dpi, size in SIZES.items():
    folder = os.path.join(RES, 'mipmap-' + dpi)
    draw_logo(size, 'square').save(os.path.join(folder, 'ic_launcher.png'))
    draw_logo(size, 'round').save(os.path.join(folder, 'ic_launcher_round.png'))

draw_logo(512, 'round').save(PREVIEW)
print('ok')
