"""Ilova ikonkasining Neyrogram rang variantlarini yaratadi (Sozlamalar -> Ilova ikonkasi).

Har bir variant uchun:
  drawable/neyro_icon_bg_<name>.xml       — gradient fon
  drawable/neyro_icon_fg_<name>.xml       — "N" belgisi (rangli)
  mipmap-anydpi-v26/<alias>.xml (+_round) — adaptive icon
  mipmap-*dpi/<alias>.png (+_round)       — eski Android uchun PNG
Ishlatish: python make_icon_variants.py <res papka>
"""
import os
import sys

from PIL import Image, ImageDraw

RES = sys.argv[1]

# nom: (mipmap alias, fon boshi, fon oxiri, belgi rangi, uchqun rangi)
VARIANTS = {
    'default': ('ic_launcher', '8E5CFF', '3F6BFF', 'FFFFFF', '7CF3FF'),
    'sunset': ('icon_6_launcher', 'FF8A3D', 'FF3D7F', 'FFFFFF', 'FFF3B0'),
    'ocean': ('icon_4_launcher', '22D3EE', '0E7490', 'FFFFFF', 'B9FBC0'),
    'gold': ('icon_3_launcher', 'FBBF24', 'D97706', 'FFFFFF', 'FFF7D6'),
    'neon': ('icon_5_launcher', '1E1B4B', '0B1020', '7CF3FF', 'FF4FD8'),
    'midnight': ('icon_2_launcher', '111827', '000000', 'A78BFA', '7CF3FF'),
}

NODES = [(41, 68), (41, 40), (67, 68), (67, 40)]


def circle(x, y, r):
    return 'M%g,%ga%g,%g 0,1 0,%g 0a%g,%g 0,1 0,%g 0' % (x - r, y, r, r, 2 * r, r, r, -2 * r)


def fg_xml(mark, spark):
    nodes = ''.join(circle(x, y, 6) for x, y in NODES)
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- make_icon_variants.py tomonidan yaratilgan -->
<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp"
    android:height="108dp"
    android:viewportWidth="108"
    android:viewportHeight="108">
    <path
        android:pathData="M67,40 L75,32"
        android:strokeWidth="2.2"
        android:strokeColor="#B3%(mark)s"
        android:strokeLineCap="round" />
    <path
        android:pathData="M41,68 L41,40 L67,68 L67,40"
        android:strokeWidth="6"
        android:strokeColor="#FF%(mark)s"
        android:strokeLineCap="round"
        android:strokeLineJoin="round" />
    <path
        android:fillColor="#FF%(mark)s"
        android:pathData="%(nodes)s" />
    <path
        android:fillColor="#FF%(spark)s"
        android:pathData="%(spark_path)s" />
</vector>
''' % {'mark': mark, 'spark': spark, 'nodes': nodes, 'spark_path': circle(75, 32, 3.5)}


def bg_xml(c1, c2):
    return '''<?xml version="1.0" encoding="utf-8"?>
<!-- make_icon_variants.py tomonidan yaratilgan -->
<shape xmlns:android="http://schemas.android.com/apk/res/android"
    android:shape="rectangle">
    <gradient
        android:angle="315"
        android:startColor="#%s"
        android:endColor="#%s"
        android:type="linear" />
</shape>
''' % (c1, c2)


def adaptive_xml(name):
    return '''<?xml version="1.0" encoding="utf-8"?>
<adaptive-icon xmlns:android="http://schemas.android.com/apk/res/android">
    <background android:drawable="@drawable/neyro_icon_bg_%s" />
    <foreground android:drawable="@drawable/neyro_icon_fg_%s" />
    <monochrome android:drawable="@drawable/neyro_icon_monochrome" />
</adaptive-icon>
''' % (name, name)


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (0, 2, 4))


def png(size, shape, c1, c2, mark, spark):
    ss = 4
    big = size * ss
    a, b = rgb(c1), rgb(c2)
    bg = Image.new('RGBA', (big, big))
    px = bg.load()
    for y in range(big):
        for x in range(big):
            t = (x + y) / (2 * (big - 1))
            px[x, y] = tuple(int(a[i] + (b[i] - a[i]) * t) for i in range(3)) + (255,)
    d = ImageDraw.Draw(bg)
    s = big / 72.0

    def p(x, y):
        return ((x - 18) * s, (y - 18) * s)

    m = rgb(mark) + (255,)
    d.line([p(67, 40), p(75, 32)], fill=rgb(mark) + (180,), width=int(2.2 * s))
    d.line([p(*xy) for xy in [(41, 68), (41, 40), (67, 68), (67, 40)]], fill=m, width=int(6 * s), joint='curve')
    for x, y in NODES:
        cx, cy = p(x, y)
        d.ellipse([cx - 6 * s, cy - 6 * s, cx + 6 * s, cy + 6 * s], fill=m)
    cx, cy = p(75, 32)
    d.ellipse([cx - 3.5 * s, cy - 3.5 * s, cx + 3.5 * s, cy + 3.5 * s], fill=rgb(spark) + (255,))

    mask = Image.new('L', (big, big), 0)
    md = ImageDraw.Draw(mask)
    margin = big * 0.02
    if shape == 'round':
        md.ellipse([margin, margin, big - margin, big - margin], fill=255)
    else:
        md.rounded_rectangle([margin, margin, big - margin, big - margin], radius=big * 0.22, fill=255)
    out = Image.new('RGBA', (big, big), (0, 0, 0, 0))
    out.paste(bg, (0, 0), mask)
    return out.resize((size, size), Image.LANCZOS)


SIZES = {'mdpi': 48, 'hdpi': 72, 'xhdpi': 96, 'xxhdpi': 144, 'xxxhdpi': 192}


def write(path, text):
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)


for name, (alias, c1, c2, mark, spark) in VARIANTS.items():
    write(os.path.join(RES, 'drawable', 'neyro_icon_bg_%s.xml' % name), bg_xml(c1, c2))
    write(os.path.join(RES, 'drawable', 'neyro_icon_fg_%s.xml' % name), fg_xml(mark, spark))
    write(os.path.join(RES, 'mipmap-anydpi-v26', alias + '.xml'), adaptive_xml(name))
    write(os.path.join(RES, 'mipmap-anydpi-v26', alias + '_round.xml'), adaptive_xml(name))
    for dpi, size in SIZES.items():
        folder = os.path.join(RES, 'mipmap-' + dpi)
        if os.path.isdir(folder):
            png(size, 'square', c1, c2, mark, spark).save(os.path.join(folder, alias + '.png'))
            png(size, 'round', c1, c2, mark, spark).save(os.path.join(folder, alias + '_round.png'))
    print(name, '->', alias)
