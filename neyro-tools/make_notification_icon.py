"""Status bar bildirishnoma ikonkasini (notification.webp) oq "N" belgisiga almashtiradi."""
import os
import sys

from PIL import Image, ImageDraw

RES = sys.argv[1]
SS = 8
WHITE = (255, 255, 255, 255)

for name in ('mdpi', 'hdpi', 'xhdpi', 'xxhdpi'):
    path = os.path.join(RES, 'drawable-' + name, 'notification.webp')
    w, h = Image.open(path).size
    W, H = w * SS, h * SS
    img = Image.new('RGBA', (W, H), (0, 0, 0, 0))
    d = ImageDraw.Draw(img)
    # belgi chegarasi: x 35..78.5, y 28.5..74 (logotip birliklarida)
    s = min(W, H) * 0.9 / 45.5
    cx, cy = (35 + 78.5) / 2, (28.5 + 74) / 2

    def p(x, y):
        return (W / 2 + (x - cx) * s, H / 2 + (y - cy) * s)

    def node(x, y, r):
        px, py = p(x, y)
        d.ellipse([px - r * s, py - r * s, px + r * s, py + r * s], fill=WHITE)

    d.line([p(67, 40), p(75, 32)], fill=WHITE, width=max(1, int(2.6 * s)))
    d.line([p(41, 68), p(41, 40), p(67, 68), p(67, 40)], fill=WHITE, width=int(6.5 * s), joint='curve')
    for x, y in [(41, 68), (41, 40), (67, 68), (67, 40)]:
        node(x, y, 6.5)
    node(75, 32, 4)
    img.resize((w, h), Image.LANCZOS).save(path, 'WEBP', lossless=True)
    print(name, (w, h))
