"""Kirish animatsiyasidagi samolyot teksturasini (intro_tg_plane) Neyrogram "N" belgisiga almashtiradi.
Kanvas o'lchami asl rasm bilan bir xil qoladi, belgi markazda.
"""
import os
import sys

from PIL import Image, ImageDraw

RES = sys.argv[1]
SS = 4
UNIT_DP = 1.4  # logotip birligi -> dp
SPARK = (0x7C, 0xF3, 0xFF, 255)
WHITE = (255, 255, 255, 255)

DENSITIES = {'mdpi': 1.0, 'hdpi': 1.5, 'xhdpi': 2.0, 'xxhdpi': 3.0}

for name, density in DENSITIES.items():
    path = os.path.join(RES, 'drawable-' + name, 'intro_tg_plane.webp')
    w, h = Image.open(path).size
    W, H = w * SS, h * SS
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    s = UNIT_DP * density * SS

    def p(x, y):
        return (W / 2 + (x - 54) * s, H / 2 + (y - 54) * s)

    def node(x, y, r, color):
        cx, cy = p(x, y)
        d.ellipse([cx - r * s, cy - r * s, cx + r * s, cy + r * s], fill=color)

    d.line([p(67, 40), p(75, 32)], fill=(255, 255, 255, 180), width=max(1, int(2.2 * s)))
    d.line([p(41, 68), p(41, 40), p(67, 68), p(67, 40)], fill=WHITE, width=int(6 * s), joint='curve')
    for x, y in [(41, 68), (41, 40), (67, 68), (67, 40)]:
        node(x, y, 6, WHITE)
    node(75, 32, 3.5, SPARK)

    img.resize((w, h), Image.LANCZOS).save(path, 'WEBP', lossless=True)
    print(name, (w, h))
