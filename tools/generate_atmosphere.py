"""Models + textures for the atmosphere blocks (brazier, chandelier, light shaft, ember crack, fog vent, soul crystal lamp)."""
import json, random, os, sys
from PIL import Image
OUT = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/btb_cinematic"
R = random.Random(21)
T = OUT + "/textures/block/"; M = OUT + "/models/block/"; B = OUT + "/blockstates/"; I = OUT + "/items/"
for d in (T, M, B, I): os.makedirs(d, exist_ok=True)

def tex(name, fn, size=16):
    im = Image.new("RGBA", (size, size), (0, 0, 0, 0)); px = im.load()
    for y in range(size):
        for x in range(size):
            px[x, y] = fn(x, y)
    im.save(T + name + ".png")

def n(a=10): return R.randint(-a, a)
tex("atmo_iron", lambda x, y: (44 + n(), 40 + n(), 46 + n(), 255) if not (x in (2, 13) and y in (2, 13)) else (90, 80, 70, 255))
tex("atmo_coals", lambda x, y: ((255, 170 + n(40), 40, 255) if R.random() < 0.35 else (200 + n(30), 50 + n(20), 10, 255)) if R.random() < 0.8 else (40, 20, 18, 255))
tex("atmo_wax", lambda x, y: (232 + n(8), 222 + n(8), 196 + n(8), 255))
tex("atmo_wick", lambda x, y: (255, 200, 90, 255) if y < 8 else (40, 30, 20, 255))
def shaft(x, y):
    streak = 1.0 if x in (3, 7, 8, 12) else 0.6
    a = int((60 + 30 * ((x * 7 + 3) % 5) / 4) * streak)
    return (255, 236, 196, a)
tex("light_shaft", shaft)
def base_bs(x, y): return (34 + n(8), 30 + n(8), 36 + n(8), 255)
tex("ember_crack_base", base_bs)
crack = set()
cx, cy = 2, 3
for _ in range(40):
    crack.add((cx, cy)); cx = max(0, min(15, cx + R.choice((0, 1, 1)))); cy = max(0, min(15, cy + R.choice((-1, 0, 1))))
cx, cy = 14, 1
for _ in range(30):
    crack.add((cx, cy)); cx = max(0, min(15, cx + R.choice((-1, 0)))); cy = max(0, min(15, cy + R.choice((0, 1, 1))))
tex("ember_crack_glow", lambda x, y: ((255, 150 + n(40), 30, 255) if (x, y) in crack else (0, 0, 0, 0)))
tex("fog_vent", lambda x, y: (22, 22, 26, 255) if (y % 4 in (1, 2) and 2 <= x <= 13) else (60 + n(8), 60 + n(8), 66 + n(8), 255))
tex("soul_crystal", lambda x, y: (120 + n(20), 225 + n(20), 255, 255) if (x + y) % 7 else (230, 255, 255, 255))

def face(t, uv=(0, 0, 16, 16)): return {"uv": list(uv), "texture": t}
def cube(f, t, tex_all, emis=0, faces="nsewud"):
    names = {"n": "north", "s": "south", "e": "east", "w": "west", "u": "up", "d": "down"}
    e = {"from": f, "to": t, "faces": {names[k]: face(tex_all) for k in faces}}
    if emis: e["light_emission"] = emis
    return e
def plane_x(f, t, tx, emis=0):
    e = {"from": f, "to": t, "shade": False, "faces": {"north": face(tx), "south": face(tx)}}
    if emis: e["light_emission"] = emis
    return e
def model(name, textures, elements, parent="minecraft:block/block", ao=False):
    j = {"parent": parent, "textures": textures, "elements": elements}
    if not ao: j["ambientocclusion"] = False
    json.dump(j, open(M + name + ".json", "w"), indent=1)
    json.dump({"variants": {"": {"model": "btb_cinematic:block/" + name}}}, open(B + name + ".json", "w"), indent=1)
    json.dump({"model": {"type": "minecraft:model", "model": "btb_cinematic:block/" + name}}, open(I + name + ".json", "w"), indent=1)

# brazier: three legs, bowl, glowing coals, crossed fire planes
el = []
for lx, lz in ((3, 3), (12, 3), (7.5, 12.5)):
    el.append(cube([lx, 0, lz], [lx + 1, 7, lz + 1], "#iron"))
el.append(cube([2, 7, 2], [14, 8, 14], "#iron"))
for a, b in (([2, 8, 2], [14, 12, 3]), ([2, 8, 13], [14, 12, 14]), ([2, 8, 3], [3, 12, 13]), ([13, 8, 3], [14, 12, 13])):
    el.append(cube(a, b, "#iron"))
el.append(cube([3, 8, 3], [13, 10.5, 13], "#coals", 15, "u"))
el.append({"from": [3, 9, 8], "to": [13, 21, 8], "shade": False, "light_emission": 15, "rotation": {"angle": 45, "axis": "y", "origin": [8, 8, 8]},
           "faces": {"north": face("#fire"), "south": face("#fire")}})
el.append({"from": [8, 9, 3], "to": [8, 21, 13], "shade": False, "light_emission": 15, "rotation": {"angle": 45, "axis": "y", "origin": [8, 8, 8]},
           "faces": {"east": face("#fire"), "west": face("#fire")}})
model("brazier", {"iron": "btb_cinematic:block/atmo_iron", "coals": "btb_cinematic:block/atmo_coals", "fire": "minecraft:block/fire_0",
                  "particle": "btb_cinematic:block/atmo_iron"}, el)

# chandelier: chain up to the ceiling, cross bars, ring of six candles
el = [cube([7.5, 10, 7.5], [8.5, 16, 8.5], "#iron"), cube([2, 8, 7.5], [14, 9, 8.5], "#iron"), cube([7.5, 8, 2], [8.5, 9, 14], "#iron")]
import math
for i in range(6):
    a = i * math.pi / 3; x = 8 + math.cos(a) * 6; z = 8 + math.sin(a) * 6
    el.append(cube([x - 1, 6, z - 1], [x + 1, 7, z + 1], "#iron"))
    el.append(cube([x - 0.5, 7, z - 0.5], [x + 0.5, 9.5, z + 0.5], "#wax"))
    el.append(cube([x - 0.15, 9.5, z - 0.15], [x + 0.15, 10.2, z + 0.15], "#wick", 15))
    # ring segment to the next candle
    a2 = (i + 0.5) * math.pi / 3; mx = 8 + math.cos(a2) * 5.6; mz = 8 + math.sin(a2) * 5.6
    el.append(cube([mx - 0.6, 6, mz - 0.6], [mx + 0.6, 6.8, mz + 0.6], "#iron"))
model("chandelier", {"iron": "btb_cinematic:block/atmo_iron", "wax": "btb_cinematic:block/atmo_wax", "wick": "btb_cinematic:block/atmo_wick",
                     "particle": "btb_cinematic:block/atmo_iron"}, el)

# light shaft: four soft planes crossing, full bright
el = []
for ang in (0, 45, -45):
    el.append({"from": [2, 0, 8], "to": [14, 16, 8], "shade": False, "light_emission": 15,
               "rotation": {"angle": ang if ang else 0, "axis": "y", "origin": [8, 8, 8]},
               "faces": {"north": face("#shaft"), "south": face("#shaft")}})
el.append({"from": [8, 0, 2], "to": [8, 16, 14], "shade": False, "light_emission": 15,
           "faces": {"east": face("#shaft"), "west": face("#shaft")}})
model("light_shaft", {"shaft": "btb_cinematic:block/light_shaft", "particle": "btb_cinematic:block/light_shaft"}, el)

# ember crack: base cube + glowing crack overlay (cutout)
model("ember_crack", {"base": "btb_cinematic:block/ember_crack_base", "glow": "btb_cinematic:block/ember_crack_glow",
                      "particle": "btb_cinematic:block/ember_crack_base"},
      [cube([0, 0, 0], [16, 16, 16], "#base"), cube([-0.01, -0.01, -0.01], [16.01, 16.01, 16.01], "#glow", 15)], ao=True)

# fog vent: flat grate
model("fog_vent", {"vent": "btb_cinematic:block/fog_vent", "particle": "btb_cinematic:block/fog_vent"}, [cube([0, 0, 0], [16, 2, 16], "#vent")], ao=True)

# soul crystal lamp: blackstone base + crystal spikes
el = [cube([3, 0, 3], [13, 3, 13], "#base")]
for (x, z, w, h, tilt) in ((7, 7, 2.4, 11, 0), (4.5, 6, 1.6, 7, -22.5), (10, 9, 1.8, 8, 22.5), (6.5, 10.5, 1.4, 6, -22.5), (9.5, 4.5, 1.4, 6, 22.5)):
    e = cube([x, 3, z], [x + w, 3 + h, z + w], "#crystal", 15)
    if tilt: e["rotation"] = {"angle": tilt, "axis": "x" if x < 8 else "z", "origin": [x + w / 2, 3, z + w / 2]}
    el.append(e)
model("soul_crystal_lamp", {"base": "minecraft:block/polished_blackstone", "crystal": "btb_cinematic:block/soul_crystal",
                            "particle": "btb_cinematic:block/soul_crystal"}, el)
print("atmosphere assets written")
