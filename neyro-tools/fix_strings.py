import re
import sys

BS = chr(92)
path = sys.argv[1]
s = open(path, encoding='utf-8', newline='').read()
line_re = re.compile(r'(    <string name="Neyro[^"]*">)(.*?)(</string>)')


def fix(m):
    body = m.group(2)
    out = []
    for i, ch in enumerate(body):
        if ch in ("'", '"') and (i == 0 or body[i - 1] != BS):
            out.append(BS + ch)
        else:
            out.append(ch)
    return m.group(1) + ''.join(out) + m.group(3)


s = line_re.sub(fix, s)
open(path, 'w', encoding='utf-8', newline='').write(s)
