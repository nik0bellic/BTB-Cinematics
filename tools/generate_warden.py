"""Generates the Heart Warden (Herzwächter): GeckoLib model, box-UV texture + glowmask, animations."""
import json, random, os, sys
from PIL import Image
OUT = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/btb_cinematic"
R = random.Random(13)
TW = TH = 256
tex = Image.new('RGBA', (TW, TH), (0, 0, 0, 0)); glow = Image.new('RGBA', (TW, TH), (0, 0, 0, 0))
tp = tex.load(); gp = glow.load()
cur = [0, 0, 0]
def alloc(w, h):
    if cur[0] + w > TW:
        cur[0] = 0; cur[1] += cur[2]; cur[2] = 0
    u, v = cur[0], cur[1]; cur[0] += w; cur[2] = max(cur[2], h); return u, v
def stone(x, y, kind):
    n = R.random()
    if kind == 'armor': c = (38 + int(18 * n), 34 + int(14 * n), 40 + int(16 * n))
    elif kind == 'gold': c = (170 + int(50 * n), 120 + int(30 * n), 30)
    else: c = (28 + int(20 * n), 24 + int(16 * n), 30 + int(18 * n))
    tp[x, y] = c + (255,)
cubes = []
def cube(bone, origin, size, kind='stone', glowfaces=None):
    sx, sy, sz = [int(round(s)) for s in size]
    w = 2 * (sx + sz); h = sz + sy
    u, v = alloc(w, h)
    for x in range(u, u + w):
        for y in range(v, v + h): stone(x, y, kind)
    if kind != 'gold':
        for i in range(max(2, (w * h) // 60)):
            x = u + R.randrange(w); y = v + R.randrange(h)
            for k in range(R.randint(2, 6)):
                if u <= x < u + w and v <= y < v + h:
                    tp[x, y] = (230, 90, 20, 255); gp[x, y] = (230, 90, 20, 255)
                x += R.choice([-1, 0, 1]); y += 1
    if glowfaces: glowfaces(u, v, sx, sy, sz)
    cubes.append((bone, {"origin": origin, "size": size, "uv": [u, v]}))
def heart(u, v, sx, sy, sz):
    fx, fy = u + sz, v + sz
    for x in range(fx, fx + sx):
        for y in range(fy, fy + sy):
            d = ((x - fx - sx / 2 + 0.5) ** 2 + (y - fy - sy / 2 + 0.5) ** 2) ** 0.5
            c = (255, 230, 120) if d < 1.3 else (255, 120, 20) if d < 2.6 else (180, 30, 10)
            tp[x, y] = c + (255,); gp[x, y] = c + (255,)
def eyes(u, v, sx, sy, sz):
    fx, fy = u + sz, v + sz
    for ex in (fx + 2, fx + sx - 4):
        for x in range(ex, ex + 2):
            for y in range(fy + 4, fy + 6):
                tp[x, y] = (255, 140, 30, 255); gp[x, y] = (255, 170, 60, 255)
bones = [
    {"name": "root", "pivot": [0, 0, 0]},
    {"name": "leftLeg", "parent": "root", "pivot": [5, 26, 0]},
    {"name": "rightLeg", "parent": "root", "pivot": [-5, 26, 0]},
    {"name": "body", "parent": "root", "pivot": [0, 26, 0]},
    {"name": "head", "parent": "body", "pivot": [0, 46, 0]},
    {"name": "leftArm", "parent": "body", "pivot": [13, 44, 0]},
    {"name": "rightArm", "parent": "body", "pivot": [-13, 44, 0]},
]
cube("leftLeg", [2, 0, -3], [7, 26, 7])
cube("rightLeg", [-9, 0, -3], [7, 26, 7])
cube("leftLeg", [2, 0, -4], [8, 4, 9], 'armor')
cube("rightLeg", [-10, 0, -4], [8, 4, 9], 'armor')
cube("body", [-10, 26, -6], [20, 20, 12])
cube("body", [-3, 33, -7], [6, 6, 1], 'stone', heart)
cube("body", [-11, 40, -7], [22, 6, 14], 'armor')
cube("head", [-5, 46, -5], [10, 10, 10], 'stone', eyes)
cube("head", [-8, 53, -1], [3, 7, 3], 'gold')
cube("head", [5, 53, -1], [3, 7, 3], 'gold')
cube("leftArm", [10, 16, -4], [8, 30, 8])
cube("rightArm", [-18, 16, -4], [8, 30, 8])
cube("leftArm", [9, 40, -5], [10, 8, 10], 'armor')
cube("rightArm", [-19, 40, -5], [10, 8, 10], 'armor')
cube("leftArm", [9, 12, -5], [10, 6, 10], 'armor')
cube("rightArm", [-19, 12, -5], [10, 6, 10], 'armor')
cube("body", [-2, 46, 4], [4, 6, 3], 'gold')
for b in bones:
    cs = [c for (bn, c) in cubes if bn == b["name"]]
    if cs: b["cubes"] = cs
geo = {"format_version": "1.12.0", "minecraft:geometry": [{"description": {"identifier": "geometry.herzwaechter",
        "texture_width": TW, "texture_height": TH, "visible_bounds_width": 4, "visible_bounds_height": 5,
        "visible_bounds_offset": [0, 2, 0]}, "bones": bones}]}
os.makedirs(OUT + "/geckolib/models/entity", exist_ok=True); os.makedirs(OUT + "/geckolib/animations/entity", exist_ok=True)
os.makedirs(OUT + "/textures/entity", exist_ok=True)
json.dump(geo, open(OUT + '/geckolib/models/entity/herzwaechter.geo.json', 'w'), indent=1)
tex.save(OUT + '/textures/entity/herzwaechter.png'); glow.save(OUT + '/textures/entity/herzwaechter_glowmask.png')
def kf(d): return {f"{k:.2f}": v for k, v in d.items()}
anims = {"format_version": "1.8.0", "animations": {
    "idle": {"loop": True, "animation_length": 2.0, "bones": {
        "body": {"position": kf({0: [0, 0, 0], 1: [0, -0.6, 0], 2: [0, 0, 0]}), "rotation": kf({0: [2, 0, 0], 1: [4, 0, 0], 2: [2, 0, 0]})},
        "head": {"rotation": kf({0: [0, 0, 0], 1: [3, 6, 0], 2: [0, 0, 0]})},
        "leftArm": {"rotation": kf({0: [0, 0, -4], 1: [4, 0, -7], 2: [0, 0, -4]})},
        "rightArm": {"rotation": kf({0: [0, 0, 4], 1: [4, 0, 7], 2: [0, 0, 4]})}}},
    "walk": {"loop": True, "animation_length": 1.2, "bones": {
        "leftLeg": {"rotation": kf({0: [25, 0, 0], 0.6: [-25, 0, 0], 1.2: [25, 0, 0]})},
        "rightLeg": {"rotation": kf({0: [-25, 0, 0], 0.6: [25, 0, 0], 1.2: [-25, 0, 0]})},
        "leftArm": {"rotation": kf({0: [-20, 0, -5], 0.6: [20, 0, -5], 1.2: [-20, 0, -5]})},
        "rightArm": {"rotation": kf({0: [20, 0, 5], 0.6: [-20, 0, 5], 1.2: [20, 0, 5]})},
        "body": {"position": kf({0: [0, 0, 0], 0.3: [0, -1, 0], 0.6: [0, 0, 0], 0.9: [0, -1, 0], 1.2: [0, 0, 0]}),
                 "rotation": kf({0: [6, -4, 0], 0.6: [6, 4, 0], 1.2: [6, -4, 0]})}}},
    "attack": {"loop": False, "animation_length": 0.8, "bones": {
        "rightArm": {"rotation": kf({0: [0, 0, 0], 0.3: [-150, 0, 10], 0.5: [20, 0, 0], 0.8: [0, 0, 0]})},
        "leftArm": {"rotation": kf({0: [0, 0, 0], 0.3: [-150, 0, -10], 0.5: [20, 0, 0], 0.8: [0, 0, 0]})},
        "body": {"rotation": kf({0: [0, 0, 0], 0.3: [-12, 0, 0], 0.5: [18, 0, 0], 0.8: [0, 0, 0]})}}},
    # ground slam: both fists up (telegraph), then down hard
    "slam": {"loop": False, "animation_length": 1.6, "bones": {
        "leftArm": {"rotation": kf({0: [0, 0, -4], 0.8: [-175, 0, -15], 1.05: [-175, 0, -15], 1.2: [10, 0, -5], 1.6: [0, 0, -4]})},
        "rightArm": {"rotation": kf({0: [0, 0, 4], 0.8: [-175, 0, 15], 1.05: [-175, 0, 15], 1.2: [10, 0, 5], 1.6: [0, 0, 4]})},
        "body": {"rotation": kf({0: [0, 0, 0], 0.8: [-18, 0, 0], 1.05: [-20, 0, 0], 1.2: [30, 0, 0], 1.6: [0, 0, 0]}),
                 "position": kf({0: [0, 0, 0], 1.2: [0, -4, 0], 1.6: [0, 0, 0]})},
        "head": {"rotation": kf({0: [0, 0, 0], 0.8: [-20, 0, 0], 1.2: [20, 0, 0], 1.6: [0, 0, 0]})}}},
    # rips a block out and throws it
    "throw": {"loop": False, "animation_length": 1.8, "bones": {
        "rightArm": {"rotation": kf({0: [0, 0, 4], 0.5: [40, 0, 10], 1.0: [-160, 0, 25], 1.35: [-170, 0, 25], 1.5: [-30, 0, 5], 1.8: [0, 0, 4]})},
        "leftArm": {"rotation": kf({0: [0, 0, -4], 0.5: [40, 0, -10], 1.0: [-150, 0, -25], 1.35: [-160, 0, -25], 1.5: [-20, 0, -5], 1.8: [0, 0, -4]})},
        "body": {"rotation": kf({0: [0, 0, 0], 0.5: [25, 0, 0], 1.0: [-15, 15, 0], 1.35: [-18, 20, 0], 1.5: [20, -10, 0], 1.8: [0, 0, 0]})}}},
    # the heart breaks open: rears up and roars
    "roar": {"loop": False, "animation_length": 2.2, "bones": {
        "body": {"rotation": kf({0: [0, 0, 0], 0.4: [15, 0, 0], 0.9: [-25, 0, 0], 1.8: [-22, 0, 0], 2.2: [0, 0, 0]})},
        "head": {"rotation": kf({0: [0, 0, 0], 0.4: [20, 0, 0], 0.9: [-40, 0, 0], 1.8: [-35, 0, 0], 2.2: [0, 0, 0]})},
        "leftArm": {"rotation": kf({0: [0, 0, -4], 0.9: [-40, 0, -70], 1.8: [-45, 0, -75], 2.2: [0, 0, -4]})},
        "rightArm": {"rotation": kf({0: [0, 0, 4], 0.9: [-40, 0, 70], 1.8: [-45, 0, 75], 2.2: [0, 0, 4]})}}},
    # rises out of the ground: pushes up hunched with the arms first, straightens, then a roar with raised arms
    "emerge": {"loop": "hold_on_last_frame", "animation_length": 3.0, "bones": {
        "root": {"position": kf({0: [0, -60, 0], 0.4: [0, -46, 0], 1.2: [0, -24, 0], 1.9: [0, -4, 0], 2.2: [0, 0, 0], 3.0: [0, 0, 0]})},
        "body": {"rotation": kf({0: [45, 0, 0], 1.2: [35, 0, 0], 2.0: [10, 0, 0], 2.4: [-14, 0, 0], 2.8: [-8, 0, 0], 3.0: [0, 0, 0]})},
        "head": {"rotation": kf({0: [25, 0, 0], 1.9: [10, 0, 0], 2.4: [-30, 0, 0], 2.8: [-25, 0, 0], 3.0: [0, 0, 0]})},
        "leftArm": {"rotation": kf({0: [-170, 0, -10], 0.8: [-150, 0, -15], 1.6: [-60, 0, -20], 2.3: [-120, 0, -40], 2.8: [-110, 0, -35], 3.0: [0, 0, -4]})},
        "rightArm": {"rotation": kf({0: [-170, 0, 10], 0.8: [-150, 0, 15], 1.6: [-60, 0, 20], 2.3: [-120, 0, 40], 2.8: [-110, 0, 35], 3.0: [0, 0, 4]})}}}}}
json.dump(anims, open(OUT + '/geckolib/animations/entity/herzwaechter.animation.json', 'w'), indent=1)
print("warden generated, texture rows used up to", cur[1] + cur[2])
