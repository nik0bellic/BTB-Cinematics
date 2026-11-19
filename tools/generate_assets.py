#!/usr/bin/env python3
"""Generates the mod's original textures and models (deterministic, no third-party art).

Run from the project root:  python3 tools/generate_assets.py
Writes into src/main/resources/assets/btb_cinematic/.
"""
import json, math, os, random
from PIL import Image

A = "src/main/resources/assets/btb_cinematic"
def path(*p):
    full = os.path.join(A, *p)
    os.makedirs(os.path.dirname(full), exist_ok=True)
    return full
def save_json(obj, *p):
    with open(path(*p), "w") as f:
        json.dump(obj, f, indent=2)
def clamp(v): return max(0, min(255, int(v)))
def mix(c1, c2, t): return tuple(clamp(a + (b - a) * t) for a, b in zip(c1, c2))

# ---------------------------------------------------------------- Heart of the Nether (GeckoLib)
R = random.Random(42)
heart = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
glow = Image.new("RGBA", (64, 64), (0, 0, 0, 0))
hp, gp = heart.load(), glow.load()

def region(x0, y0, w, h, painter):
    for y in range(y0, y0 + h):
        for x in range(x0, x0 + w):
            painter(x, y, x - x0, y - y0)

# outer shell 8x8x8 → box region 32x16 at (0,0): dark crimson stone with molten cracks (holes)
def shell(x, y, lx, ly):
    n = R.random()
    crack = (math.sin(lx * 0.9 + ly * 1.7) + math.sin(lx * 2.3 - ly * 0.6)) > 1.45
    if crack:
        hp[x, y] = (255, 120, 20, 0) if n < 0.55 else (255, 140, 40, 255)
        gp[x, y] = (255, 150, 40, 255) if hp[x, y][3] else (0, 0, 0, 0)
    else:
        base = mix((70, 8, 12), (120, 20, 24), n)
        if n > 0.93:
            base = (230, 90, 30)
            gp[x, y] = (230, 90, 30, 255)  # glowing lava cracks in the heart itself
        hp[x, y] = base + (255,)
region(0, 0, 32, 16, shell)

# inner core 6x6x6 → 24x12 at (32,0): molten, fully emissive
def core(x, y, lx, ly):
    t = 0.5 + 0.5 * math.sin(lx * 0.8) * math.cos(ly * 0.9)
    c = mix((255, 90, 10), (255, 230, 120), t * 0.8 + R.random() * 0.2)
    hp[x, y] = c + (255,)
    gp[x, y] = c + (255,)
region(32, 0, 24, 12, core)

# shards 2x5x2 → 8x7 at (0,16): crimson crystal with bright edges
def shard(x, y, lx, ly):
    edge = lx in (0, 3, 4, 7) or ly == 0
    c = (200, 60, 90) if edge else mix((120, 10, 45), (180, 30, 70), R.random())
    hp[x, y] = c + (255,)
    # Only the tips and a few veins glow (whole glowing faces looked flat and pasted-on in the hand).
    if ly == 0 or R.random() > 0.9:
        gp[x, y] = (190, 40, 80, 255)
region(0, 16, 8, 7, shard)

# spikes 2x2x2 → 8x4 at (32,16): blackstone with gold
def spike(x, y, lx, ly):
    hp[x, y] = ((200, 150, 40) if (lx + ly) % 3 == 0 else (40, 32, 38)) + (255,)
region(32, 16, 8, 4, spike)

# ring 10x1x1 → 22x2 at (0,24) and 1x1x10 → 22x11 at (0,28): gold band
def ring(x, y, lx, ly):
    c = mix((180, 120, 30), (255, 210, 90), (lx % 4) / 3)
    hp[x, y] = c + (255,)
    if lx % 5 == 0:
        gp[x, y] = (255, 200, 80, 255)
region(0, 24, 22, 2, ring)
region(0, 28, 22, 11, ring)
heart.save(path("textures/item/heart_of_the_nether.png"))
glow.save(path("textures/item/heart_of_the_nether_glowmask.png"))

def cube(origin, size, uv, **kw):
    d = {"origin": origin, "size": size, "uv": uv}
    d.update(kw)
    return d

geo = {
    "format_version": "1.12.0",
    "minecraft:geometry": [{
        "description": {"identifier": "geometry.heart_of_the_nether", "texture_width": 64, "texture_height": 64,
                        "visible_bounds_width": 3, "visible_bounds_height": 3, "visible_bounds_offset": [0, 0.5, 0]},
        "bones": [
            {"name": "root", "pivot": [0, 0, 0]},
            {"name": "core", "parent": "root", "pivot": [0, 8, 0], "rotation": [35, 45, 0],
             "cubes": [cube([-4, 4, -4], [8, 8, 8], [0, 0]),
                       cube([-3, 5, -3], [6, 6, 6], [32, 0]),
                       cube([-1, 11, -1], [2, 2, 2], [32, 16]),
                       cube([-1, 3, -1], [2, 2, 2], [32, 16])]},
            {"name": "shards", "parent": "root", "pivot": [0, 8, 0],
             "cubes": [cube([7, 6, -1], [2, 5, 2], [0, 16], pivot=[8, 8, 0], rotation=[0, 0, -15]),
                       cube([-9, 6, -1], [2, 5, 2], [0, 16], pivot=[-8, 8, 0], rotation=[0, 0, 15]),
                       cube([-1, 6, 7], [2, 5, 2], [0, 16], pivot=[0, 8, 8], rotation=[15, 0, 0]),
                       cube([-1, 6, -9], [2, 5, 2], [0, 16], pivot=[0, 8, -8], rotation=[-15, 0, 0])]},
            {"name": "ring", "parent": "root", "pivot": [0, 8, 0], "rotation": [18, 0, 12],
             "cubes": [cube([-5, 7.5, -6], [10, 1, 1], [0, 24]),
                       cube([-5, 7.5, 5], [10, 1, 1], [0, 24]),
                       cube([-6, 7.5, -5], [1, 1, 10], [0, 28]),
                       cube([5, 7.5, -5], [1, 1, 10], [0, 28])]},
        ]}]}
save_json(geo, "geckolib/models/item/heart_of_the_nether.geo.json")

def heartbeat(t0):
    return {f"{t0:.2f}": [1, 1, 1], f"{t0 + 0.12:.2f}": [1.14, 1.14, 1.14], f"{t0 + 0.26:.2f}": [0.98, 0.98, 0.98],
            f"{t0 + 0.38:.2f}": [1.08, 1.08, 1.08], f"{t0 + 0.6:.2f}": [1, 1, 1]}
anim = {"format_version": "1.8.0", "animations": {
    "idle": {"loop": True, "animation_length": 2.0, "bones": {
        "core": {"scale": heartbeat(0.0)},
        "shards": {"rotation": {"0.0": [0, 0, 0], "2.0": [0, 90, 0]},
                   "position": {"0.0": [0, 0, 0], "1.0": [0, 0.6, 0], "2.0": [0, 0, 0]}},
        "ring": {"rotation": {"0.0": [0, 0, 0], "2.0": [0, -90, 0]}},
        "root": {"position": {"0.0": [0, 0, 0], "1.0": [0, 0.4, 0], "2.0": [0, 0, 0]}}}},
    "awaken": {"loop": "hold_on_last_frame", "animation_length": 2.0, "bones": {
        "core": {"scale": {"0.0": [0.2, 0.2, 0.2], "1.2": [1.3, 1.3, 1.3], "1.6": [0.95, 0.95, 0.95], "2.0": [1, 1, 1]}},
        "shards": {"scale": {"0.0": [0, 0, 0], "0.8": [0, 0, 0], "1.6": [1.2, 1.2, 1.2], "2.0": [1, 1, 1]},
                   "rotation": {"0.0": [0, -270, 0], "2.0": [0, 0, 0]}},
        "ring": {"scale": {"0.0": [0, 0, 0], "1.0": [0, 0, 0], "2.0": [1, 1, 1]}}}}}}
save_json(anim, "geckolib/animations/item/heart_of_the_nether.animation.json")

# item model definition → GeckoLib special renderer; base model supplies particle texture
save_json({"model": {"type": "minecraft:special", "base": "btb_cinematic:item/heart_of_the_nether",
                     "model": {"type": "geckolib:geckolib"}}}, "items/heart_of_the_nether.json")
HEART_DISPLAY = {"gui": {"rotation": [25, 45, 0], "translation": [0, -1.5, 0], "scale": [0.75, 0.75, 0.75]}, "ground": {"translation": [0, 2, 0], "scale": [0.4, 0.4, 0.4]}, "fixed": {"rotation": [0, 0, 0], "translation": [0, -4, 0], "scale": [0.75, 0.75, 0.75]}, "head": {"translation": [0, 10, 0], "scale": [0.8, 0.8, 0.8]}, "thirdperson_righthand": {"rotation": [0, 45, 0], "translation": [0, 2, 1], "scale": [0.35, 0.35, 0.35]}, "thirdperson_lefthand": {"rotation": [0, 45, 0], "translation": [0, 2, 1], "scale": [0.35, 0.35, 0.35]}, "firstperson_righthand": {"rotation": [0, 45, 0], "translation": [1, 3, 0], "scale": [0.25, 0.25, 0.25]}, "firstperson_lefthand": {"rotation": [0, 45, 0], "translation": [1, 3, 0], "scale": [0.25, 0.25, 0.25]}}
save_json({"parent": "minecraft:item/generated", "textures": {"layer0": "btb_cinematic:item/heart_of_the_nether_icon",
                                                             "particle": "btb_cinematic:item/heart_of_the_nether_icon"},
           "display": HEART_DISPLAY},
          "models/item/heart_of_the_nether.json")
icon = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); ip = icon.load()
for y in range(16):
    for x in range(16):
        d = abs(x - 7.5) + abs(y - 7.5)
        if d < 6.5:
            ip[x, y] = mix((255, 200, 90), (110, 10, 20), d / 6.5) + (255,)
icon.save(path("textures/item/heart_of_the_nether_icon.png"))

# ---------------------------------------------------------------- Nether Key (3D JSON model)
key = Image.new("RGBA", (16, 16), (0, 0, 0, 0)); kp = key.load()
R2 = random.Random(7)
for y in range(16):
    for x in range(16):
        if y < 8:   # upper half: gold metal
            kp[x, y] = mix((170, 110, 25), (255, 215, 100), (x % 4) / 3 * 0.6 + R2.random() * 0.4) + (255,)
        elif y < 12:  # obsidian
            kp[x, y] = mix((20, 10, 30), (60, 25, 80), R2.random()) + (255,)
        else:       # glowing gem
            kp[x, y] = mix((255, 60, 30), (255, 190, 80), R2.random()) + (255,)
key.save(path("textures/item/nether_key.png"))
gold, obs, gem = [0, 0, 16, 8], [0, 8, 16, 12], [0, 12, 16, 16]
def faces(uv):
    return {f: {"uv": uv, "texture": "#key"} for f in ("north", "south", "east", "west", "up", "down")}
def el(fr, to, uv, light=0, rot=None):
    e = {"from": fr, "to": to, "faces": faces(uv)}
    if light:
        e["light_emission"] = light
    if rot:
        e["rotation"] = rot
    return e
key_model = {
    "textures": {"key": "btb_cinematic:item/nether_key", "particle": "btb_cinematic:item/nether_key"},
    "elements": [
        # bow (ring) of the key
        el([5, 11, 7.5], [11, 12, 8.5], gold), el([5, 15, 7.5], [11, 16, 8.5], gold),
        el([4, 12, 7.5], [5, 15, 8.5], gold), el([11, 12, 7.5], [12, 15, 8.5], gold),
        el([6.5, 12.5, 7.25], [9.5, 14.5, 8.75], gem, 15),
        # shaft
        el([7.25, 2, 7.25], [8.75, 11, 8.75], obs),
        # bit (teeth)
        el([8.75, 2, 7.5], [11, 3.5, 8.5], gold), el([8.75, 4.5, 7.5], [10, 6, 8.5], gold),
        el([6, 9, 7.5], [10, 10, 8.5], gold),
    ],
    "display": {
        "gui": {"rotation": [0, 0, -45], "translation": [0, 0, 0], "scale": [1.1, 1.1, 1.1]},
        "ground": {"rotation": [0, 0, 0], "translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
        "fixed": {"rotation": [0, 0, 0], "scale": [1, 1, 1]},
        "thirdperson_righthand": {"rotation": [0, 90, -35], "translation": [0, 1.25, -2.5], "scale": [0.6, 0.6, 0.6]},
        "firstperson_righthand": {"rotation": [0, -90, 25], "translation": [1.13, 3.2, 1.13], "scale": [0.68, 0.68, 0.68]}}}
save_json(key_model, "models/item/nether_key.json")
save_json({"model": {"type": "minecraft:model", "model": "btb_cinematic:item/nether_key"}}, "items/nether_key.json")

# ---------------------------------------------------------------- Nether Crystal (animated texture, 3D)
frames = 8
cry = Image.new("RGBA", (16, 16 * frames), (0, 0, 0, 0)); cp = cry.load()
for f in range(frames):
    for y in range(16):
        for x in range(16):
            shimmer = 0.5 + 0.5 * math.sin((x + y) * 0.7 - f * 2 * math.pi / frames)
            cp[x, y + 16 * f] = mix((90, 0, 60), (255, 70, 140), shimmer * 0.85) + (255,)
cry.save(path("textures/item/nether_crystal.png"))
with open(path("textures/item/nether_crystal.png.mcmeta"), "w") as fh:
    json.dump({"animation": {"frametime": 2, "interpolate": True}}, fh)
crystal_faces = {f: {"uv": [0, 0, 16, 16], "texture": "#c"} for f in ("north", "south", "east", "west", "up", "down")}
crystal_model = {
    "textures": {"c": "btb_cinematic:item/nether_crystal", "particle": "btb_cinematic:item/nether_crystal"},
    "elements": [
        {"from": [6, 2, 6], "to": [10, 12, 10], "light_emission": 12, "faces": crystal_faces,
         "rotation": {"angle": 22.5, "axis": "y", "origin": [8, 8, 8]}},
        {"from": [3, 1, 7], "to": [6, 7, 10], "light_emission": 10, "faces": crystal_faces,
         "rotation": {"angle": 22.5, "axis": "z", "origin": [5, 4, 8]}},
        {"from": [10, 1, 6], "to": [13, 8, 9], "light_emission": 10, "faces": crystal_faces,
         "rotation": {"angle": -22.5, "axis": "z", "origin": [11, 4, 8]}},
    ],
    "display": {"gui": {"rotation": [25, 45, 0], "scale": [0.9, 0.9, 0.9]},
                "ground": {"translation": [0, 2, 0], "scale": [0.5, 0.5, 0.5]},
                "fixed": {"scale": [1, 1, 1]}}}
save_json(crystal_model, "models/item/nether_crystal.json")
save_json({"model": {"type": "minecraft:model", "model": "btb_cinematic:item/nether_crystal"}}, "items/nether_crystal.json")
print("assets generated")

# ---------------------------------------------------------------- Portal blocks
def block_tex(name, painter, size=16, frames=1):
    img = Image.new("RGBA", (size, size * frames), (0, 0, 0, 0)); px = img.load()
    for f in range(frames):
        for y in range(size):
            for x in range(size):
                px[x, y + size * f] = painter(x, y, f)
    img.save(path(f"textures/block/{name}.png"))
    return img

R3 = random.Random(99)
NOISE = [[R3.random() for _ in range(16)] for _ in range(16)]
def blackstone(x, y):
    n = NOISE[y][x]
    return mix((22, 18, 24), (52, 44, 54), n)
RUNE = {(4, 4), (5, 4), (6, 5), (5, 6), (4, 7), (11, 4), (10, 5), (11, 6), (12, 7), (7, 10), (8, 10), (8, 11), (9, 12), (6, 12)}
def frame(lit):
    def paint(x, y, f):
        border = x in (0, 15) or y in (0, 15)
        if border:
            return mix((150, 100, 20), (230, 180, 70), NOISE[y][x]) + (255,)
        if (x, y) in RUNE:
            return ((255, 150, 40) if lit else (90, 60, 30)) + (255,)
        return blackstone(x, y) + (255,)
    return paint
block_tex("portal_frame", frame(False))
block_tex("portal_frame_lit", frame(True))

def keystone(stage):
    glow = {"idle": (70, 40, 30), "keyed": (255, 200, 90), "charging": (255, 120, 30), "active": (255, 80, 200)}[stage]
    def paint(x, y, f):
        border = x in (0, 15) or y in (0, 15)
        if border:
            return mix((150, 100, 20), (230, 180, 70), NOISE[y][x]) + (255,)
        cx, cy = x - 7.5, y - 6
        keyhole = (cx * cx + cy * cy < 5) or (abs(cx) < 1.1 and 6 <= y <= 11)
        ring = 9 < cx * cx + (y - 7.5) ** 2 < 26
        if keyhole:
            return glow + (255,) if stage != "idle" else (8, 6, 10, 255)
        if ring:
            return mix(glow, (60, 40, 20), 0.35) + (255,)
        return blackstone(x, y) + (255,)
    return paint
for st in ("idle", "keyed", "charging", "active"):
    block_tex(f"portal_keystone_{st}", keystone(st))

FR = 32
def rift(x, y, f):
    cx, cy = x - 7.5, y - 7.5
    ang = math.atan2(cy, cx); rad = math.sqrt(cx * cx + cy * cy)
    swirl = 0.5 + 0.5 * math.sin(ang * 3 + rad * 0.9 - f * 2 * math.pi / FR)
    sparkle = 1.0 if NOISE[(y + f) % 16][(x * 3 + f) % 16] > 0.96 else 0.0
    c = mix((60, 0, 40), (255, 70, 120), swirl * 0.8 + sparkle * 0.4)
    c = mix(c, (255, 200, 240), sparkle * 0.6)
    return c + (clamp(170 + 70 * swirl),)
block_tex("rift_portal", rift, frames=FR)
with open(path("textures/block/rift_portal.png.mcmeta"), "w") as fh:
    json.dump({"animation": {"frametime": 2, "interpolate": True}}, fh)

save_json({"parent": "minecraft:block/cube_all", "textures": {"all": "btb_cinematic:block/portal_frame"}}, "models/block/portal_frame.json")
save_json({"parent": "minecraft:block/cube_all", "textures": {"all": "btb_cinematic:block/portal_frame_lit"}}, "models/block/portal_frame_lit.json")
save_json({"variants": {"lit=false": {"model": "btb_cinematic:block/portal_frame"}, "lit=true": {"model": "btb_cinematic:block/portal_frame_lit"}}},
          "blockstates/portal_frame.json")
for st in ("idle", "keyed", "charging", "active"):
    save_json({"parent": "minecraft:block/cube_column", "textures": {"end": "btb_cinematic:block/portal_frame",
               "side": f"btb_cinematic:block/portal_keystone_{st}"}}, f"models/block/portal_keystone_{st}.json")
save_json({"variants": {f"stage={st}": {"model": f"btb_cinematic:block/portal_keystone_{st}"} for st in ("idle", "keyed", "charging", "active")}},
          "blockstates/portal_keystone.json")
pane = {"parent": "minecraft:block/block", "textures": {"particle": "btb_cinematic:block/rift_portal", "rift": "btb_cinematic:block/rift_portal"},
        "elements": [{"from": [0, 0, 6], "to": [16, 16, 10], "light_emission": 12,
                      "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#rift"}, "south": {"uv": [0, 0, 16, 16], "texture": "#rift"}}}]}
save_json(pane, "models/block/rift_portal_ns.json")
pane_ew = json.loads(json.dumps(pane)); pane_ew["elements"][0] = {"from": [6, 0, 0], "to": [10, 16, 16], "light_emission": 12,
        "faces": {"east": {"uv": [0, 0, 16, 16], "texture": "#rift"}, "west": {"uv": [0, 0, 16, 16], "texture": "#rift"}}}
save_json(pane_ew, "models/block/rift_portal_ew.json")
save_json({"variants": {"axis=x": {"model": "btb_cinematic:block/rift_portal_ns"}, "axis=z": {"model": "btb_cinematic:block/rift_portal_ew"}}},
          "blockstates/rift_portal.json")
for b, m in (("portal_frame", "portal_frame"), ("portal_keystone", "portal_keystone_idle")):
    save_json({"model": {"type": "minecraft:model", "model": f"btb_cinematic:block/{m}"}}, f"items/{b}.json")

# loot tables: frame and keystone drop themselves
for b in ("portal_frame", "portal_keystone"):
    lt = {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"btb_cinematic:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]}
    full = os.path.join("src/main/resources/data/btb_cinematic/loot_table/blocks", b + ".json")
    os.makedirs(os.path.dirname(full), exist_ok=True)
    with open(full, "w") as f:
        json.dump(lt, f, indent=2)
print("portal assets generated")

# ---------------------------------------------------------------- Heart Tower blocks
R4 = random.Random(1337)
N4 = [[R4.random() for _ in range(16)] for _ in range(16)]
def brick_mortar(x, y):
    row = y // 4
    off = 0 if row % 2 == 0 else 4
    return y % 4 == 3 or (x + off) % 8 == 7

FR2 = 16
def heartstone(x, y, f):
    if brick_mortar(x, y):
        return (18, 6, 8, 255)
    base = mix((52, 10, 16), (88, 18, 24), N4[y][x])
    crack = abs(math.sin(x * 1.3 + y * 0.7)) < 0.12 and N4[(y * 3) % 16][x] > 0.4
    if crack:
        pulse = 0.5 + 0.5 * math.sin(f * 2 * math.pi / FR2)
        base = mix((120, 20, 10), (255, 90, 20), pulse)
    return base + (255,)
block_tex("heartstone_bricks", heartstone, frames=FR2)
with open(path("textures/block/heartstone_bricks.png.mcmeta"), "w") as fh:
    json.dump({"animation": {"frametime": 3, "interpolate": True}}, fh)

def vein(x, y, f):
    # dark rock with a thick glowing vein winding vertically; brightness pulses like a heartbeat
    cx = 7.5 + 3 * math.sin(y * 0.45)
    d = abs(x - cx)
    beat = (math.exp(-((f % FR2) / 1.6) ** 2) + 0.6 * math.exp(-(((f % FR2) - 4) / 1.6) ** 2))
    if d < 1.6:
        return mix((200, 30, 20), (255, 200, 120), min(1, 0.35 + beat)) + (255,)
    if d < 2.6:
        return mix((110, 15, 15), (220, 60, 30), min(1, 0.2 + beat * 0.7)) + (255,)
    return mix((24, 10, 14), (46, 18, 22), N4[y][x]) + (255,)
block_tex("nether_vein", vein, frames=FR2)
with open(path("textures/block/nether_vein.png.mcmeta"), "w") as fh:
    json.dump({"animation": {"frametime": 2, "interpolate": True}}, fh)

def altar_side(x, y, f):
    if y < 2 or y > 13:
        return mix((150, 100, 20), (230, 180, 70), N4[y][x]) + (255,)
    if (x, y) in RUNE:
        return (255, 120, 40, 255)
    return blackstone(x, y) + (255,)
def altar_top(x, y, f):
    d = math.sqrt((x - 7.5) ** 2 + (y - 7.5) ** 2)
    if d < 3:
        return mix((255, 220, 140), (255, 90, 20), d / 3) + (255,)
    if 5 < d < 6.5:
        return (240, 170, 60, 255)
    return blackstone(x, y) + (255,)
block_tex("heart_altar_side", altar_side)
block_tex("heart_altar_top", altar_top)

save_json({"parent": "minecraft:block/cube_all", "textures": {"all": "btb_cinematic:block/heartstone_bricks"}}, "models/block/heartstone_bricks.json")
save_json({"variants": {"": {"model": "btb_cinematic:block/heartstone_bricks"}}}, "blockstates/heartstone_bricks.json")
vein_faces = {f: {"uv": [0, 0, 16, 16], "texture": "#v"} for f in ("north", "south", "east", "west", "up", "down")}
save_json({"parent": "minecraft:block/block", "textures": {"v": "btb_cinematic:block/nether_vein", "particle": "btb_cinematic:block/nether_vein"},
           "elements": [{"from": [0, 0, 0], "to": [16, 16, 16], "light_emission": 12, "faces": vein_faces}]}, "models/block/nether_vein.json")
save_json({"variants": {"": {"model": "btb_cinematic:block/nether_vein"}}}, "blockstates/nether_vein.json")
def faces(side, top):
    d = {f: {"uv": [0, 0, 16, 16], "texture": side} for f in ("north", "south", "east", "west")}
    d["up"] = {"uv": [0, 0, 16, 16], "texture": top}
    d["down"] = {"uv": [0, 0, 16, 16], "texture": side}
    return d
altar_model = {"parent": "minecraft:block/block",
    "textures": {"side": "btb_cinematic:block/heart_altar_side", "top": "btb_cinematic:block/heart_altar_top",
                 "particle": "btb_cinematic:block/heart_altar_side"},
    "elements": [
        {"from": [0, 0, 0], "to": [16, 4, 16], "faces": faces("#side", "#side")},
        {"from": [3, 4, 3], "to": [13, 12, 13], "faces": faces("#side", "#side")},
        {"from": [1, 12, 1], "to": [15, 16, 15], "light_emission": 10, "faces": faces("#side", "#top")}]}
save_json(altar_model, "models/block/heart_altar.json")
save_json({"variants": {"": {"model": "btb_cinematic:block/heart_altar"}}}, "blockstates/heart_altar.json")
for b in ("heartstone_bricks", "nether_vein", "heart_altar"):
    save_json({"model": {"type": "minecraft:model", "model": f"btb_cinematic:block/{b}"}}, f"items/{b}.json")
    lt = {"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": f"btb_cinematic:{b}"}],
          "conditions": [{"condition": "minecraft:survives_explosion"}]}]}
    with open(os.path.join("src/main/resources/data/btb_cinematic/loot_table/blocks", b + ".json"), "w") as f:
        json.dump(lt, f, indent=2)
print("tower assets generated")

# ---------------------------------------------------------------- White portal style
def quartz(x, y):
    return mix((226, 222, 214), (250, 248, 244), N4[y][x])
def radiant(lit):
    def paint(x, y, f):
        if x in (0, 15) or y in (0, 15):
            return mix((190, 150, 60), (255, 220, 120), N4[y][x]) + (255,)
        if (x, y) in RUNE:
            return ((150, 230, 255) if lit else (170, 160, 140)) + (255,)
        return quartz(x, y) + (255,)
    return paint
block_tex("radiant_frame", radiant(False))
block_tex("radiant_frame_lit", radiant(True))
save_json({"parent": "minecraft:block/cube_all", "textures": {"all": "btb_cinematic:block/radiant_frame"}}, "models/block/radiant_frame.json")
save_json({"parent": "minecraft:block/cube_all", "textures": {"all": "btb_cinematic:block/radiant_frame_lit"}}, "models/block/radiant_frame_lit.json")
save_json({"variants": {"lit=false": {"model": "btb_cinematic:block/radiant_frame"}, "lit=true": {"model": "btb_cinematic:block/radiant_frame_lit"}}},
          "blockstates/radiant_frame.json")
save_json({"model": {"type": "minecraft:model", "model": "btb_cinematic:block/radiant_frame"}}, "items/radiant_frame.json")
with open(os.path.join("src/main/resources/data/btb_cinematic/loot_table/blocks", "radiant_frame.json"), "w") as fh:
    json.dump({"type": "minecraft:block", "pools": [{"rolls": 1, "entries": [{"type": "minecraft:item", "name": "btb_cinematic:radiant_frame"}]}]}, fh, indent=2)

def rift_white(x, y, f):
    cx, cy = x - 7.5, y - 7.5
    ang = math.atan2(cy, cx); rad = math.sqrt(cx * cx + cy * cy)
    swirl = 0.5 + 0.5 * math.sin(ang * 2 + rad * 0.7 - f * 2 * math.pi / FR)
    streak = 0.5 + 0.5 * math.sin(ang * 5 - rad * 0.4 + f * 4 * math.pi / FR)
    sparkle = 1.0 if NOISE[(y + f) % 16][(x * 5 + f) % 16] > 0.95 else 0.0
    c = mix((210, 225, 240), (255, 255, 255), swirl)
    c = mix(c, (255, 225, 150), streak * 0.35)
    c = mix(c, (170, 235, 255), (1 - swirl) * 0.3)
    c = mix(c, (255, 255, 255), sparkle)
    return c + (clamp(185 + 60 * swirl),)
block_tex("rift_portal_white", rift_white, frames=FR)
with open(path("textures/block/rift_portal_white.png.mcmeta"), "w") as fh:
    json.dump({"animation": {"frametime": 2, "interpolate": True}}, fh)

# rift models for every axis and style
def rift_model(tex, axis):
    if axis == "x":
        el = {"from": [0, 0, 6], "to": [16, 16, 10], "faces": {"north": {"uv": [0, 0, 16, 16], "texture": "#rift"}, "south": {"uv": [0, 0, 16, 16], "texture": "#rift"}}}
    elif axis == "z":
        el = {"from": [6, 0, 0], "to": [10, 16, 16], "faces": {"east": {"uv": [0, 0, 16, 16], "texture": "#rift"}, "west": {"uv": [0, 0, 16, 16], "texture": "#rift"}}}
    else:
        el = {"from": [0, 6, 0], "to": [16, 10, 16], "faces": {"up": {"uv": [0, 0, 16, 16], "texture": "#rift"}, "down": {"uv": [0, 0, 16, 16], "texture": "#rift"}}}
    el["light_emission"] = 15 if "white" in tex else 12
    return {"parent": "minecraft:block/block", "textures": {"particle": tex, "rift": tex}, "elements": [el]}
variants = {}
for style, tex in (("nether", "btb_cinematic:block/rift_portal"), ("white", "btb_cinematic:block/rift_portal_white")):
    for axis in ("x", "y", "z"):
        save_json(rift_model(tex, axis), f"models/block/rift_portal_{style}_{axis}.json")
        variants[f"axis={axis},style={style}"] = {"model": f"btb_cinematic:block/rift_portal_{style}_{axis}"}
save_json({"variants": variants}, "blockstates/rift_portal.json")
for old in ("rift_portal_ns", "rift_portal_ew"):
    try:
        os.remove(path(f"models/block/{old}.json"))
    except FileNotFoundError:
        pass
print("white portal assets generated")

# ---------------------------------------------------------------- Link icons for credits (simplified, original pixel art)
def icon(name, painter):
    img = Image.new("RGBA", (32, 32), (0, 0, 0, 0)); px = img.load()
    for y in range(32):
        for x in range(32):
            c = painter(x, y)
            if c:
                px[x, y] = c
    img.save(path(f"textures/gui/icons/{name}.png"))

def rounded(x, y, r=7, m=1):
    # inside a rounded square [m, 31-m]
    lo, hi = m, 31 - m
    if x < lo or x > hi or y < lo or y > hi:
        return False
    cx = min(max(x, lo + r), hi - r); cy = min(max(y, lo + r), hi - r)
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
def circle(x, y, cx=15.5, cy=15.5, r=15):
    return (x - cx) ** 2 + (y - cy) ** 2 <= r * r
def tri(x, y, ax, ay, bx, by, cx, cy):
    d = lambda x1, y1, x2, y2, x3, y3: (x1 - x3) * (y2 - y3) - (x2 - x3) * (y1 - y3)
    d1, d2, d3 = d(x, y, ax, ay, bx, by), d(x, y, bx, by, cx, cy), d(x, y, cx, cy, ax, ay)
    neg = d1 < 0 or d2 < 0 or d3 < 0; pos = d1 > 0 or d2 > 0 or d3 > 0
    return not (neg and pos)
W = (255, 255, 255, 255)

icon("youtube", lambda x, y: (W if tri(x, y, 12, 9, 12, 22, 22, 15.5) else (230, 33, 23, 255)) if (rounded(x, y, 8, 2) and 5 <= y <= 26) else None)
def spotify(x, y):
    if not circle(x, y):
        return None
    for (yy, w, t) in ((10, 11, 2.2), (16, 9, 1.9), (21.5, 7, 1.6)):
        d = math.hypot(x - 15.5, (y - yy - 9) * 1.0)
        if abs(d - 10 - (w - 7)) < t and y < yy + 3 and 7 <= x <= 25:
            return (25, 20, 20, 255)
    return (30, 215, 96, 255)
icon("spotify", spotify)
icon("twitch", lambda x, y: (W if (11 <= x <= 13 or 18 <= x <= 20) and 8 <= y <= 15 else (145, 70, 255, 255)) if (4 <= x <= 27 and 3 <= y <= 24) or (8 <= x <= 14 and 24 < y <= 28 and x - 8 <= 28 - y) else None)
def insta(x, y):
    if not rounded(x, y, 8, 2):
        return None
    t = (x + (31 - y)) / 62
    base = mix((250, 180, 60), (200, 40, 160), t)
    if 5 <= x <= 26 and 5 <= y <= 26 and not (7 <= x <= 24 and 7 <= y <= 24) or 4.6 <= math.hypot(x - 15.5, y - 15.5) <= 6.6 or math.hypot(x - 21, y - 10) < 1.4:
        return W
    return base + (255,)
icon("instagram", insta)
def tiktok(x, y):
    if not rounded(x, y, 8, 2):
        return None
    note = (16 <= x <= 18 and 7 <= y <= 21) or math.hypot(x - 13, y - 21) < 4 or (18 <= x <= 23 and 7 <= y <= 9 and y >= 7 + (x - 18) * 0.3)
    shadow = (17 <= x <= 19 and 8 <= y <= 22) or math.hypot(x - 14, y - 22) < 4
    if note:
        return W
    if shadow:
        return (240, 40, 90, 255)
    return (16, 16, 20, 255)
icon("tiktok", tiktok)
def discord(x, y):
    if not rounded(x, y, 8, 2):
        return None
    face = 7 <= x <= 24 and 10 <= y <= 22 and math.hypot((x - 15.5) / 9, (y - 16) / 6.5) < 1.05
    eye = math.hypot(x - 12, y - 16) < 2 or math.hypot(x - 19, y - 16) < 2
    if face and not eye:
        return W
    return (88, 101, 242, 255)
icon("discord", discord)
def xlogo(x, y):
    if not rounded(x, y, 8, 2):
        return None
    if abs((x - 8) - (y - 7) * 16 / 18) < 1.6 and 7 <= y <= 25 or abs((24 - x) - (y - 7) * 16 / 18) < 1.1 and 7 <= y <= 25:
        return W
    return (15, 15, 15, 255)
icon("x", xlogo)
def soundcloud(x, y):
    if not rounded(x, y, 8, 2):
        return None
    cloud = (y <= 22 and (math.hypot(x - 19, y - 16) < 6 or math.hypot(x - 24, y - 18) < 4) and y >= 11) or (6 <= x <= 13 and 14 <= y <= 22 and x % 2 == 0)
    return W if cloud and y <= 22 else (255, 85, 0, 255)
icon("soundcloud", soundcloud)
icon("patreon", lambda x, y: ((W if math.hypot(x - 18, y - 13) < 7 else ((20, 20, 20, 255) if 6 <= x <= 9 and 6 <= y <= 25 else (249, 104, 84, 255))) if rounded(x, y, 8, 2) else None))
def github(x, y):
    if not circle(x, y):
        return None
    cat = math.hypot(x - 15.5, y - 15) < 8 or tri(x, y, 9, 10, 10, 4, 14, 8) or tri(x, y, 22, 10, 21, 4, 17, 8)
    return W if cat and y < 24 else (36, 41, 47, 255)
icon("github", github)
def web(x, y):
    if not circle(x, y):
        return None
    line = abs(math.hypot(x - 15.5, y - 15.5) - 12) < 1.2 or abs(x - 15.5) < 0.9 or abs(y - 15.5) < 0.9 or \
           abs(math.hypot((x - 15.5) * 2.2, y - 15.5) - 13) < 1.6 or abs(y - 9) < 0.7 or abs(y - 22) < 0.7
    return W if line else (40, 120, 220, 255)
icon("web", web)
def mail(x, y):
    if not rounded(x, y, 6, 2):
        return None
    env = 6 <= x <= 25 and 9 <= y <= 22
    flap = env and abs((y - 9) - abs(x - 15.5) * 0.0 - (15.5 - abs(x - 15.5)) * 0.62) < 1.0
    if flap or (env and (x in (6, 25) or y in (9, 22))):
        return (60, 60, 70, 255)
    return W if env else (230, 180, 60, 255)
icon("mail", mail)
print("icons generated")
