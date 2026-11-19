"""Builds src/main/resources/assets/btb_cinematic/i18n.json from the work list + translation files and checks placeholders."""
import json, re, sys, glob
work, trdir, out = sys.argv[1], sys.argv[2], sys.argv[3]
w = json.load(open(work, encoding='utf-8'))
tr = {}
for f in sorted(glob.glob(trdir + '/tr_*.txt')):
    for line in open(f, encoding='utf-8'):
        line = line.rstrip('\n')
        if not line.strip():
            continue
        parts = line.split('\t')
        tr[int(parts[0])] = (parts[1], parts[2].replace('\\n', '\n') if len(parts) > 2 else None)
PH = re.compile(r'\{\d+\}|%[-#+ 0,(]*\d*(?:\.\d+)?[a-zA-Z]')
rows, bad = [], []
for o in w:
    src = o['src']; mode, t = tr[o['i']]
    if mode == '=':
        de = en = src
    elif mode == 'D':
        de, en = t, src
    else:
        de, en = src, t
    for other in (de, en):
        a = sorted(x for x in PH.findall(src) if x.startswith('{')); b = sorted(x for x in PH.findall(other) if x.startswith('{'))
        pa = [x for x in PH.findall(src) if x.startswith('%')]; pb = [x for x in PH.findall(other) if x.startswith('%')]
        if a != b or pa != pb:
            bad.append((o['i'], src, other))
    rows.append([src, de, en])
import os
man = os.path.join(os.path.dirname(os.path.abspath(__file__)), 'i18n', 'manual.tsv')
if os.path.exists(man):
    known = {r[0]: r for r in rows}
    for line in open(man, encoding='utf-8'):
        if not line.strip() or line.startswith('#'):
            continue
        src, de, en = [x.replace('\\n', '\n') for x in line.rstrip('\n').split('\t')]
        if src in known:
            known[src][1], known[src][2] = de, en
        else:
            rows.append([src, de, en])
json.dump(rows, open(out, 'w', encoding='utf-8'), ensure_ascii=False, indent=0)
print(len(rows), 'texts;', len(bad), 'placeholder problems')
for b in bad[:20]:
    print(b)
