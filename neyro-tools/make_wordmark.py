"""Telegram so'z-logotipi vektorlarini (telegram_logo, telegram_logo_2) "Neyrogram" yozuviga almashtiradi.
Yozuv ilovaning o'z Roboto shriftidan konturga aylantiriladi. Balandlik asl fayllar bilan bir xil qoladi.
"""
import sys

from fontTools.pens.svgPathPen import SVGPathPen
from fontTools.pens.transformPen import TransformPen
from fontTools.ttLib import TTFont

FONT, RES = sys.argv[1], sys.argv[2]
TEXT = "Neyrogram"

font = TTFont(FONT)
glyph_set = font.getGlyphSet()
cmap = font.getBestCmap()
hmtx = font['hmtx']
cap_height = font['OS/2'].sCapHeight or 1456

# {fayl: (viewportHeight, dp balandlik, asos chizig'i y, bosh harf balandligi)} — asl logolar bilan mos
TARGETS = {
    'telegram_logo_2.xml': (61.0, 20.408922, 46.0, 45.76),
    'telegram_logo.xml': (57.0, 19.0, 43.0, 42.8),
}


def build(viewport_h, baseline, cap):
    scale = cap / cap_height
    pen = SVGPathPen(glyph_set, ntos=lambda v: ('%.2f' % v).rstrip('0').rstrip('.'))
    x = 0.5
    for ch in TEXT:
        name = cmap[ord(ch)]
        tpen = TransformPen(pen, (scale, 0, 0, -scale, x, baseline))
        glyph_set[name].draw(tpen)
        x += hmtx[name][0] * scale
    return pen.getCommands(), x + 0.5


for filename, (vh, height_dp, baseline, cap) in TARGETS.items():
    path, width = build(vh, baseline, cap)
    width_dp = height_dp * width / vh
    xml = (
        '<vector xmlns:android="http://schemas.android.com/apk/res/android" '
        'android:height="%gdp" android:viewportHeight="%g" android:viewportWidth="%.2f" android:width="%.2fdp">\n'
        '    <!-- Neyrogram so\'z-logotipi -->\n'
        '    <path android:fillColor="#FFFFFFFF" android:fillType="nonZero" android:pathData="%s"/>\n'
        '</vector>\n'
    ) % (height_dp, vh, width, width_dp, path)
    with open(RES + '/drawable/' + filename, 'w', encoding='utf-8', newline='\n') as f:
        f.write(xml)
    print(filename, 'width=%.1f units, %.1fdp' % (width, width_dp))
