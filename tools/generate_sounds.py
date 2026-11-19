#!/usr/bin/env python3
"""Procedurally synthesises the mod's original sound effects (no third-party samples).

Usage: python3 tools/generate_sounds.py <out_dir_for_wav>   then encode with oggenc.
All effects are mono (required for positional attenuation in Minecraft); the music bed is stereo.
Deterministic: fixed random seeds, so rebuilding yields identical files.
"""
import sys, wave, os
import numpy as np

SR = 44100
OUT = sys.argv[1]
os.makedirs(OUT, exist_ok=True)


def t(sec):
    return np.arange(int(SR * sec)) / SR


def env(n, a, r, curve=2.0):
    """attack/release envelope in seconds over n samples"""
    e = np.ones(n)
    ai, ri = int(a * SR), int(r * SR)
    if ai > 0:
        e[:ai] = np.linspace(0, 1, ai) ** curve
    if ri > 0:
        e[-ri:] *= np.linspace(1, 0, ri) ** curve
    return e


def lowpass(x, cutoff):
    # one-pole low pass, cutoff may be array
    a = np.exp(-2 * np.pi * np.asarray(cutoff) / SR) * np.ones_like(x)
    y = np.zeros_like(x)
    acc = 0.0
    for i in range(len(x)):
        acc = (1 - a[i]) * x[i] + a[i] * acc
        y[i] = acc
    return y


def noise(n, seed):
    return np.random.default_rng(seed).standard_normal(n)


def reverb(x, decay=1.6, mix=0.3, seed=1):
    """cheap convolution reverb with an exponentially decaying noise impulse"""
    n = int(SR * decay)
    ir = noise(n, seed) * np.exp(-np.linspace(0, 6, n))
    ir = lowpass(ir, 4000)
    wet = np.convolve(x, ir)[: len(x) + n] / np.sqrt(n) * 4
    wet = np.pad(wet, (0, len(x) + n - len(wet)))
    out = np.concatenate([x, np.zeros(n)]) * (1 - mix) + wet * mix
    return out


def norm(x, peak=0.89):
    m = np.max(np.abs(x))
    return x / m * peak if m > 0 else x


def write(name, x, stereo=None):
    path = os.path.join(OUT, name + ".wav")
    if stereo is not None:
        data = np.stack([x, stereo], axis=1)
        ch = 2
    else:
        data = x
        ch = 1
    pcm = (np.clip(data, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(ch)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())
    print("wrote", path)


def boom(sec=2.5, f0=55, seed=3):
    tt = t(sec)
    f = f0 * (1 + 1.5 * np.exp(-tt * 18))
    ph = 2 * np.pi * np.cumsum(f) / SR
    body = np.sin(ph) * np.exp(-tt * 2.2)
    click = lowpass(noise(len(tt), seed), 2500) * np.exp(-tt * 40) * 0.6
    tail = lowpass(noise(len(tt), seed + 1), 300) * np.exp(-tt * 1.6) * 0.5
    return body + click + tail


# 1. portal_charge: rising energy hum with crackle, 6 s
tt = t(6.0)
f = 60 + 220 * (tt / 6) ** 1.8
ph = 2 * np.pi * np.cumsum(f) / SR
hum = np.sin(ph) + 0.5 * np.sin(2 * ph + 0.3) + 0.25 * np.sin(3.01 * ph)
crackle = (np.random.default_rng(5).random(len(tt)) > 0.9985 - 0.002 * tt / 6) * noise(len(tt), 6)
crackle = lowpass(crackle, 5000) * 3
shimmer = np.sin(2 * np.pi * (880 + 300 * tt / 6) * tt) * 0.08 * (tt / 6)
x = (hum * 0.6 + crackle + shimmer) * env(len(tt), 1.5, 0.15) * (0.4 + 0.6 * tt / 6)
write("portal_charge", norm(reverb(x, 1.2, 0.25)))

# 2. portal_open: whoosh + boom + sparkle, 3 s
tt = t(3.0)
wh = lowpass(noise(len(tt), 7), 200 + 3000 * np.exp(-tt * 3)) * np.exp(-tt * 2) * 1.5
x = wh + boom(3.0, 48, 8) * 0.9 + np.sin(2 * np.pi * 1320 * tt) * np.exp(-tt * 4) * 0.12
write("portal_open", norm(reverb(x, 2.0, 0.35)))

# 3. portal_enter: swelling whoosh into high shimmer, 2.5 s
tt = t(2.5)
cut = 150 + 6000 * (tt / 2.5) ** 2
x = lowpass(noise(len(tt), 9), cut) * env(len(tt), 2.0, 0.3, 1.5) * 2
x += np.sin(2 * np.pi * (440 + 880 * tt / 2.5) * tt) * 0.15 * (tt / 2.5) ** 2
write("portal_enter", norm(reverb(x, 1.5, 0.3)))

# 4. heart_pulse: deep double heartbeat, 1.2 s (loopable rhythm when repeated)
tt = t(1.2)
def thump(at, amp):
    s = np.clip(tt - at, 0, None)
    on = tt >= at
    return on * amp * np.sin(2 * np.pi * (42 + 30 * np.exp(-s * 25)) * s) * np.exp(-s * 14)
x = thump(0.0, 1.0) + thump(0.22, 0.7)
x += lowpass(noise(len(tt), 10), 120) * (np.exp(-tt * 10)) * 0.2
write("heart_pulse", norm(x))

# 5. heart_reveal: choir-like pad swell with low boom, 6 s
tt = t(6.0)
pad = np.zeros_like(tt)
for i, fr in enumerate([110, 165, 220, 277.2, 330, 440]):
    vib = 1 + 0.004 * np.sin(2 * np.pi * (4.5 + i * 0.3) * tt)
    pad += np.sin(2 * np.pi * fr * vib * tt) * (1 / (1 + i * 0.4))
pad *= env(len(tt), 2.5, 2.0, 1.6)
x = pad * 0.5 + np.concatenate([np.zeros(int(SR * 2.4)), boom(3.6, 40, 11)])[: len(tt)] * 0.9
write("heart_reveal", norm(reverb(x, 2.5, 0.4)))

# 6. impact: big cinematic hit, 3 s
write("impact", norm(reverb(boom(3.0, 45, 12) + boom(3.0, 90, 13) * 0.3, 2.2, 0.3)))

# 7. rumble: 5 s low earth rumble (loopable-ish)
tt = t(5.0)
x = lowpass(noise(len(tt), 14), 80) * 3 + lowpass(noise(len(tt), 15), 400) * 0.4 * (1 + np.sin(2 * np.pi * 0.7 * tt))
x *= env(len(tt), 0.6, 0.6, 1.0)
write("rumble", norm(x))

# 8. whoosh: 1.4 s air pass-by
tt = t(1.4)
c = 1 - np.abs(tt / 1.4 - 0.5) * 2
x = lowpass(noise(len(tt), 16), 200 + 4000 * c ** 2) * c ** 2 * 2
write("whoosh", norm(x))

# 9. key_insert: metallic click and resonant ring, 2 s
tt = t(2.0)
click = lowpass(noise(len(tt), 17), 8000) * np.exp(-tt * 120)
ring = sum(np.sin(2 * np.pi * fr * tt) * np.exp(-tt * d) * a for fr, d, a in
           [(1046, 2.5, 0.5), (1568, 3.5, 0.3), (2637, 5, 0.2), (523, 2, 0.2)])
write("key_insert", norm(reverb(click * 1.5 + ring, 1.2, 0.25)))

# 10. crystal_shimmer: sparkling chimes, 3 s
tt = t(3.0)
rng = np.random.default_rng(18)
x = np.zeros_like(tt)
for k in range(28):
    at = rng.random() * 2.2
    fr = rng.choice([1760, 2093, 2349, 2637, 3136, 3520])
    s = np.clip(tt - at, 0, None)
    x += (tt >= at) * np.sin(2 * np.pi * fr * s) * np.exp(-s * 6) * (0.3 + 0.7 * rng.random())
write("crystal_shimmer", norm(reverb(x, 1.8, 0.4)))

# 11. collapse: crumbling debris + low hits, 4 s
tt = t(4.0)
rng = np.random.default_rng(19)
x = lowpass(noise(len(tt), 20), 900) * env(len(tt), 0.05, 2.5) * 0.5
for k in range(14):
    at = rng.random() * 3
    seg = boom(1.0, 60 + rng.random() * 60, 30 + k) * (0.3 + 0.5 * rng.random())
    i = int(at * SR)
    x[i:i + len(seg)] += seg[: len(x) - i]
write("collapse", norm(reverb(x, 1.6, 0.3)))

# 12. title_hit: trailer "braam" (detuned brass-like saws), 4 s
tt = t(4.0)
x = np.zeros_like(tt)
for det in (-0.6, 0, 0.5, 1.1):
    for fr in (55, 110):
        ph = (fr + det) * tt
        x += 2 * (ph - np.floor(ph + 0.5))
x = lowpass(x, 300 + 1500 * np.exp(-tt * 1.5)) * env(len(tt), 0.02, 2.2, 1.2)
x += boom(4.0, 38, 21) * 0.8
write("title_hit", norm(reverb(x, 2.5, 0.35)))

# 13. nether_drone (music): 32 s stereo ambient bed, seamless loop
dur = 32.0
tt = t(dur)
def bed(seed, detune):
    y = np.zeros_like(tt)
    for i, fr in enumerate([55, 82.4, 110, 164.8]):
        lfo = 0.5 + 0.5 * np.sin(2 * np.pi * (1 / dur) * (i + 1) * tt + seed)
        y += np.sin(2 * np.pi * (fr + detune) * tt) * lfo / (1 + i * 0.5)
    wind = lowpass(noise(len(tt), int(seed * 10) + 40), 350) * 0.25
    return y + wind
L = bed(1.0, 0.0)
R = bed(2.3, 0.15)
fade = int(SR * 1.0)
for ch in (L, R):  # crossfade end into start for a click-free loop
    ch[:fade] = ch[:fade] * np.linspace(0, 1, fade) + ch[-fade:] * np.linspace(1, 0, fade)
L, R = L[:-fade], R[:-fade]
m = max(np.max(np.abs(L)), np.max(np.abs(R)))
write("nether_drone", L / m * 0.7, stereo=R / m * 0.7)
