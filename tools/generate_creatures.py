"""Small creatures + animated portal focus (GeckoLib): geo models, box-UV textures with glowmasks, animations."""
import json, random, os, sys, math
from PIL import Image
OUT = sys.argv[1] if len(sys.argv) > 1 else "src/main/resources/assets/btb_cinematic"

PALETTES = {
    "stone": lambda R: (30 + R.randint(0, 18), 26 + R.randint(0, 14), 32 + R.randint(0, 16)),
    "ash": lambda R: (70 + R.randint(0, 25), 66 + R.randint(0, 22), 68 + R.randint(0, 22)),
    "ember": lambda R: (255, 120 + R.randint(0, 90), 20 + R.randint(0, 40)),
    "flame": lambda R: (255, 170 + R.randint(0, 70), 60 + R.randint(0, 60)),
    "soul": lambda R: (110 + R.randint(0, 40), 230 + R.randint(0, 25), 255),
    "gold": lambda R: (200 + R.randint(0, 40), 150 + R.randint(0, 40), 40),
    "crystal": lambda R: (200 + R.randint(0, 55), 240 + R.randint(0, 15), 255),
    "eye": lambda R: (255, 60 + R.randint(0, 30), 20),
}
GLOW = {"ember", "flame", "soul", "crystal", "eye"}

class Model:
    def __init__(self, name, tex=64, seed=1):
        self.name, self.T, self.R = name, tex, random.Random(seed)
        self.tex = Image.new("RGBA", (tex, tex), (0, 0, 0, 0)); self.glow = Image.new("RGBA", (tex, tex), (0, 0, 0, 0))
        self.cur = [0, 0, 0]; self.bones = []
    def alloc(self, w, h):
        if self.cur[0] + w > self.T:
            self.cur[0] = 0; self.cur[1] += self.cur[2]; self.cur[2] = 0
        u, v = self.cur[0], self.cur[1]; self.cur[0] += w; self.cur[2] = max(self.cur[2], h)
        assert v + h <= self.T, "texture too small for " + self.name
        return u, v
    def bone(self, name, pivot, parent=None, rotation=None):
        b = {"name": name, "pivot": pivot, "cubes": []}
        if parent: b["parent"] = parent
        if rotation: b["rotation"] = rotation
        self.bones.append(b); return b
    def cube(self, bone, origin, size, kind, rotation=None, pivot=None, inflate=0):
        sx, sy, sz = [max(1, int(math.ceil(s))) for s in size]
        w, h = 2 * (sx + sz), sz + sy
        u, v = self.alloc(w, h)
        tp, gp = self.tex.load(), self.glow.load()
        for x in range(u, u + w):
            for y in range(v, v + h):
                c = PALETTES[kind](self.R)
                tp[x, y] = c + (255,)
                if kind in GLOW:
                    gp[x, y] = c + (255,)
        c = {"origin": origin, "size": size, "uv": [u, v]}
        if inflate: c["inflate"] = inflate
        if rotation:
            c["rotation"] = rotation; c["pivot"] = pivot or [origin[0] + size[0] / 2, origin[1] + size[1] / 2, origin[2] + size[2] / 2]
        bone["cubes"].append(c)
    def save(self, kind, anims, vis=(1, 1.5)):
        geo = {"format_version": "1.12.0", "minecraft:geometry": [{"description": {
            "identifier": "geometry." + self.name, "texture_width": self.T, "texture_height": self.T,
            "visible_bounds_width": vis[0], "visible_bounds_height": vis[1], "visible_bounds_offset": [0, vis[1] / 2, 0]},
            "bones": self.bones}]}
        for d in ("geckolib/models/" + kind, "geckolib/animations/" + kind, "textures/" + kind):
            os.makedirs(OUT + "/" + d, exist_ok=True)
        json.dump(geo, open(f"{OUT}/geckolib/models/{kind}/{self.name}.geo.json", "w"), indent=1)
        json.dump({"format_version": "1.8.0", "animations": anims}, open(f"{OUT}/geckolib/animations/{kind}/{self.name}.animation.json", "w"), indent=1)
        self.tex.save(f"{OUT}/textures/{kind}/{self.name}.png"); self.glow.save(f"{OUT}/textures/{kind}/{self.name}_glowmask.png")

def kf(d): return {f"{k:.2f}": v for k, v in d.items()}

# --- Glutling: a small ember spirit – glowing core in a cage of blackstone shards, flame wings
m = Model("glutling", 64, 3)
root = m.bone("root", [0, 0, 0])
body = m.bone("body", [0, 6, 0], "root")
m.cube(body, [-2, 4, -2], [4, 4, 4], "ember")
shell = m.bone("shell", [0, 6, 0], "body")
for i in range(4):
    a = i * 90
    x, z = math.cos(math.radians(a)) * 3, math.sin(math.radians(a)) * 3
    m.cube(shell, [x - 1, 3 + (i % 2), z - 1], [2, 5, 2], "stone", rotation=[0, 45, 15 if i % 2 else -15])
for side, sx in (("wingL", 1), ("wingR", -1)):
    w = m.bone(side, [sx * 2, 7, 0], "body")
    m.cube(w, [sx * 2 if sx > 0 else sx * 2 - 6, 6, 0], [6, 4, 0.01], "flame")
m.cube(m.bone("tail", [0, 4, 0], "body"), [-0.5, 1, -0.5], [1, 3, 1], "flame")
m.save("entity", {
    "fly": {"loop": True, "animation_length": 2.0, "bones": {
        "shell": {"rotation": kf({0: [0, 0, 0], 2: [0, 360, 0]})},
        "body": {"position": kf({0: [0, 0, 0], 1: [0, 1.2, 0], 2: [0, 0, 0]})},
        "wingL": {"rotation": kf({0: [0, 0, -30], 0.25: [0, 0, 25], 0.5: [0, 0, -30], 0.75: [0, 0, 25], 1: [0, 0, -30], 1.25: [0, 0, 25], 1.5: [0, 0, -30], 1.75: [0, 0, 25], 2: [0, 0, -30]})},
        "wingR": {"rotation": kf({0: [0, 0, 30], 0.25: [0, 0, -25], 0.5: [0, 0, 30], 0.75: [0, 0, -25], 1: [0, 0, 30], 1.25: [0, 0, -25], 1.5: [0, 0, 30], 1.75: [0, 0, -25], 2: [0, 0, 30]})},
        "tail": {"rotation": kf({0: [10, 0, 0], 1: [-10, 0, 0], 2: [10, 0, 0]})}}}}, (1, 1))

# --- Aschekriecher: a low, six-legged crawler of ash and blackstone with glowing eyes
m = Model("aschekriecher", 64, 7)
root = m.bone("root", [0, 0, 0])
body = m.bone("body", [0, 4, 0], "root")
m.cube(body, [-4, 3, -5], [8, 4, 10], "stone")
m.cube(body, [-3, 6.5, -4], [6, 1.5, 8], "ash")
head = m.bone("head", [0, 5, -5], "body")
m.cube(head, [-3, 3, -9], [6, 4, 4], "stone")
m.cube(head, [-2.5, 5, -9.2], [1.5, 1, 0.5], "eye")
m.cube(head, [1, 5, -9.2], [1.5, 1, 0.5], "eye")
m.cube(head, [-1, 3.5, -9.4], [2, 1, 0.5], "ember")
for i, z in enumerate((-3, 0, 3)):
    for side, sx in (("L", 1), ("R", -1)):
        lg = m.bone(f"leg{side}{i}", [sx * 4, 4, z], "body")
        m.cube(lg, [sx * 4 if sx > 0 else sx * 4 - 7, 3.5, z - 0.5], [7, 1, 1], "stone", rotation=[0, 0, -35 * sx], pivot=[sx * 4, 4, z])
walk = {}
for i in range(3):
    for side, sx in (("L", 1), ("R", -1)):
        ph = 0 if (i + (sx > 0)) % 2 else 0.2
        walk[f"leg{side}{i}"] = {"rotation": kf({0 + ph: [0, 25, 0], 0.2 + ph: [0, -25, 0], 0.4: [0, 25, 0]} if ph == 0 else {0: [0, -25, 0], 0.2: [0, 25, 0], 0.4: [0, -25, 0]})}
walk["body"] = {"position": kf({0: [0, 0, 0], 0.1: [0, 0.4, 0], 0.2: [0, 0, 0], 0.3: [0, 0.4, 0], 0.4: [0, 0, 0]})}
m.save("entity", {
    "walk": {"loop": True, "animation_length": 0.4, "bones": walk},
    "idle": {"loop": True, "animation_length": 3.0, "bones": {
        "head": {"rotation": kf({0: [0, 0, 0], 1.5: [5, 12, 0], 3: [0, 0, 0]})},
        "body": {"position": kf({0: [0, 0, 0], 1.5: [0, -0.3, 0], 3: [0, 0, 0]})}}}}, (1.2, 0.8))

# --- Seelenwisp: a drifting soul light with three tiny orbiting sparks
m = Model("seelenwisp", 32, 11)
root = m.bone("root", [0, 0, 0])
core = m.bone("core", [0, 5, 0], "root")
m.cube(core, [-1.5, 3.5, -1.5], [3, 3, 3], "soul")
m.cube(core, [-1, 1, -1], [2, 2.5, 2], "soul", inflate=-0.2)
orbit = m.bone("orbit", [0, 5, 0], "core")
for i in range(3):
    a = math.radians(i * 120)
    m.cube(orbit, [math.cos(a) * 3.5 - 0.5, 4.5, math.sin(a) * 3.5 - 0.5], [1, 1, 1], "crystal")
m.save("entity", {
    "float": {"loop": True, "animation_length": 3.0, "bones": {
        "orbit": {"rotation": kf({0: [0, 0, 0], 3: [0, -360, 0]})},
        "core": {"position": kf({0: [0, 0, 0], 1.5: [0, 1.5, 0], 3: [0, 0, 0]}), "rotation": kf({0: [0, 0, 0], 3: [0, 360, 0]})}}}}, (0.8, 1))

# --- Portalfokus (block): pedestal, floating crystal, two rings spinning on different axes
m = Model("portal_focus", 128, 21)
root = m.bone("root", [0, 0, 0])
m.cube(root, [-6, 0, -6], [12, 3, 12], "stone")
m.cube(root, [-4, 3, -4], [8, 2, 8], "gold")
m.cube(root, [-1.5, 5, -1.5], [3, 3, 3], "stone")
crystal = m.bone("crystal", [0, 16, 0], "root")
m.cube(crystal, [-2, 11, -2], [4, 10, 4], "crystal", rotation=[0, 45, 0])
m.cube(crystal, [-1, 9, -1], [2, 2, 2], "crystal", rotation=[0, 45, 0])
m.cube(crystal, [-1, 21, -1], [2, 2, 2], "crystal", rotation=[0, 45, 0])
for ring, r in (("ring1", 9), ("ring2", 7)):
    rb = m.bone(ring, [0, 16, 0], "root")
    for i in range(8):
        a = math.radians(i * 45)
        x, y = math.cos(a) * r, math.sin(a) * r
        m.cube(rb, [x - 1.5, 16 + y - 0.5, -0.5], [3, 1, 1], "gold" if i % 2 else "ember", rotation=[0, 0, i * 45 + 90], pivot=[x, 16 + y, 0])
m.save("block", {
    "idle": {"loop": True, "animation_length": 8.0, "bones": {
        "crystal": {"rotation": kf({0: [0, 0, 0], 8: [0, 360, 0]}), "position": kf({0: [0, 0, 0], 2: [0, 1, 0], 4: [0, 0, 0], 6: [0, 1, 0], 8: [0, 0, 0]})},
        "ring1": {"rotation": kf({0: [0, 0, 0], 8: [360, 0, 0]})},
        "ring2": {"rotation": kf({0: [0, 0, 0], 8: [0, 360, 360]})}}}}, (2, 2.5))
print("creatures + portal focus written")
