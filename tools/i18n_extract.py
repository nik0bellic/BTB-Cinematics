"""Collects every user-facing text of the mod (chat feedback, director screen, editor, export, tooltips)
as templates: literal parts stay, code parts become {0}, {1} … Used to build/verify assets/btb_cinematic/i18n.json."""
import os, re, json, sys

ROOT = os.path.join(os.path.dirname(__file__), '..', 'src')
SINKS = [r'Feedback\.(?:ok|fail|info)\(\s*[^,]+,', r'Component\.literal\(', r'new Action\(', r'new Row\(', r'\bsay\(',
         r'\bnote\(', r'setOverlayMessage\(Component\.literal\(', r'\bstatus\s*=', r'\.label\(', r'\.button\(',
         r'Tooltip\.create\(', r'new JsonParamException\(', r'\bfail\(', r'Outcome\.(?:ok|fail)\(', r'\breturn\s+',
         r'Exception\(', r'\bok\(', r'\binfo\(', r'\bt\(', r'\bc\(']

def literal_value(tok):
    s = tok[1:-1]
    return bytes(s, 'utf-8').decode('unicode_escape').encode('latin-1').decode('utf-8') if '\\' in s else s

def split_top(expr, sep):
    parts, depth, cur, i, q = [], 0, '', 0, None
    while i < len(expr):
        ch = expr[i]
        if q:
            cur += ch
            if ch == '\\': cur += expr[i + 1]; i += 1
            elif ch == q: q = None
        elif ch in '"\'':
            q = ch; cur += ch
        elif ch in '([{': depth += 1; cur += ch
        elif ch in ')]}': depth -= 1; cur += ch
        elif ch == sep and depth == 0:
            parts.append(cur); cur = ''
        else:
            cur += ch
        i += 1
    parts.append(cur)
    return [p.strip() for p in parts]

def arg_after(src, pos):
    """first argument expression starting at pos (until top-level ',' or ')')"""
    depth, i, q = 0, pos, None
    while i < len(src):
        ch = src[i]
        if q:
            if ch == '\\': i += 2; continue
            if ch == q: q = None
        elif ch in '"\'': q = ch
        elif ch in '([{': depth += 1
        elif ch in ')]}':
            if depth == 0: return src[pos:i]
            depth -= 1
        elif ch in ',;' and depth == 0: return src[pos:i]
        i += 1
    return src[pos:]

STR = re.compile(r'"(?:[^"\\]|\\.)*"')
def template(expr):
    parts = split_top(expr, '+')
    out, n, has_text = '', 0, False
    for p in parts:
        if STR.fullmatch(p):
            v = literal_value(p); out += v
            if re.search(r'[A-Za-zÄÖÜäöüß]{2,}', v): has_text = True
        else:
            out += '{%d}' % n; n += 1
    return out if has_text else None

def prose(v):
    if not re.search(r'[A-Za-zÄÖÜäöüß]{2,}', v): return False
    if v.startswith(('btb ', 'local:', 'obs:', 'minecraft:', 'btb_cinematic:', 'key.', 'textures/', 'execute ', 'tp ', 'kill ')): return False
    if re.fullmatch(r'[a-z0-9_.:/\-]+', v): return False          # ids, paths
    if re.fullmatch(r'#?[0-9A-Fa-f]{6,8}', v): return False
    return True

USER_DIRS = ['client/java/de/behindtheblocks/cinematic/client', 'main/java/de/behindtheblocks/cinematic']
SKIP = ('FilmSet.java', 'DemoSet.java', 'CinematicEngine.java', 'DeltaTimerMixin.java', 'CameraMixin.java', 'Easing.java',
        'CameraPose.java', 'LevelLoadingScreenMixin.java', 'BtbCinematic.java', 'BtbEntities.java', 'IrisDepth.java',
        'ClientCueDispatcher.java', 'EventManager.java', 'Feedback.java', 'CinematicHudRenderer.java', 'FilmOverlays.java',
        'I18n.java', 'ClientI18n.java')
def scan():
    templates, literals = {}, {}
    for d in USER_DIRS:
        for dp, _, fs in os.walk(os.path.join(ROOT, d)):
            for f in fs:
                if not f.endswith('.java') or f in SKIP: continue
                p = os.path.join(dp, f); src = open(p, encoding='utf-8').read()
                rel = os.path.relpath(p, ROOT)
                for sink in SINKS:
                    for m in re.finditer(sink, src):
                        expr = arg_after(src, m.end())
                        for alt in split_top(expr, '?') if '?' in expr and ':' in expr else [expr]:
                            for alt2 in split_top(alt, ':') if ':' in alt and '?' in expr else [alt]:
                                t = template(alt2.strip().strip('()'))
                                if t: templates.setdefault(t, rel)
                for m in STR.finditer(src):
                    line = src[src.rfind('\n', 0, m.start()) + 1: src.find('\n', m.end())]
                    if 'LOG.' in line or 'Logger' in line or 'addProperty' in line or 'getAsString' in line: continue
                    v = literal_value(m.group(0))
                    if prose(v) and (' ' in v.strip() or re.search(r'[ÄÖÜäöüß]', v) or v[:1].isupper()):
                        literals.setdefault(v, rel)
    return templates, literals

if __name__ == '__main__':
    t, l = scan()
    json.dump({'templates': t, 'literals': l}, open(sys.argv[1], 'w', encoding='utf-8'), ensure_ascii=False, indent=1)
    print(len(t), 'templates,', len(l), 'literals')
