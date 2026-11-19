#!/usr/bin/env python3
"""Extra procedural sounds (round 5): ear ring, white tone, clapperboard, flare. Usage: generate_sounds_extra.py <wav_dir>"""
import sys, os, wave
import numpy as np
SR = 44100
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)

def t(sec): return np.arange(int(SR * sec)) / SR

def write(name, x):
    x = x / max(1e-9, np.max(np.abs(x))) * 0.85
    with wave.open(os.path.join(OUT, name + ".wav"), "wb") as w:
        w.setnchannels(1); w.setsampwidth(2); w.setframerate(SR)
        w.writeframes((np.clip(x, -1, 1) * 32767).astype("<i2").tobytes())

def fade(x, a, r):
    n = len(x); e = np.ones(n)
    ai, ri = int(a * SR), int(r * SR)
    if ai: e[:ai] = np.linspace(0, 1, ai) ** 2
    if ri: e[-ri:] *= np.linspace(1, 0, ri) ** 2
    return x * e

# tinnitus after a blast: high sine with slow beating, fading out
tt = t(4.5)
ring = np.sin(2 * np.pi * 4180 * tt) + 0.35 * np.sin(2 * np.pi * 4193 * tt)
ring *= np.exp(-tt * 0.55)
write("ear_ring", fade(ring, 0.05, 1.5) * 0.6)

# white tone: soft, pure, slowly blooming chord (portal / white fade)
tt = t(6.0)
x = sum(a * np.sin(2 * np.pi * f * tt + p) for f, a, p in [(523.25, 1, 0), (783.99, 0.6, 1), (1046.5, 0.45, 2), (1567.98, 0.2, 3)])
x *= 1 + 0.15 * np.sin(2 * np.pi * 0.4 * tt)
write("white_tone", fade(x, 2.2, 2.5))

# clapperboard: sharp wooden clack (two boards), short body resonance
tt = t(0.35)
rng = np.random.default_rng(5)
burst = rng.standard_normal(len(tt)) * np.exp(-tt * 180)
body = np.sin(2 * np.pi * 1150 * tt) * np.exp(-tt * 55) * 0.6 + np.sin(2 * np.pi * 2300 * tt) * np.exp(-tt * 90) * 0.3
write("clap", burst + body)

# flare ignition: strike + steady hiss with crackles
tt = t(2.2)
rng = np.random.default_rng(9)
hiss = rng.standard_normal(len(tt))
hiss = np.diff(hiss, prepend=0) * 0.6  # brighter
crack = (rng.random(len(tt)) > 0.9993) * rng.standard_normal(len(tt)) * 6
strike = np.exp(-tt * 25) * rng.standard_normal(len(tt)) * 1.5
write("flare_ignite", fade(hiss * (0.4 + 0.6 * np.minimum(1, tt * 3)) + crack + strike, 0.005, 0.6))
print("ok")
