"""Generate Orbit's UI sound packs. Pure stdlib; writes 16-bit mono WAV files
into app/app/src/main/res/raw. Run from the repo root:

    python tools/make_sfx.py
"""

import math
import os
import wave

SR = 44100
ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "app", "app", "src", "main", "res", "raw")

HARMONICS = ((1, 1.0), (2, 0.18), (3, 0.05))


def envelope(t, total, attack=0.004, decay=0.05):
    if decay <= 0:
        return min(1.0, t / attack) if attack else 1.0
    return min(1.0, t / attack) * math.exp(-t / decay)


def tone(freq, dur, amp=0.3, decay=0.05, attack=0.004, harmonics=HARMONICS, slide_to=None):
    n = int(SR * dur)
    phase = 0.0
    out = []
    for i in range(n):
        f = freq if slide_to is None else freq + (slide_to - freq) * (i / max(1, n - 1))
        phase += 2 * math.pi * f / SR
        value = sum(weight * math.sin(phase * mult) for mult, weight in harmonics)
        out.append(value * amp * envelope(i / SR, dur, attack, decay))
    return out


def mix(*layers):
    size = max(len(layer[0]) + layer[1] for layer in layers)
    buf = [0.0] * size
    for samples, offset in layers:
        for i, value in enumerate(samples):
            buf[i + offset] += value
    return buf


def write(name, samples):
    peak = max(1e-9, max(abs(v) for v in samples))
    scale = 0.85 / peak
    fade = min(len(samples), int(SR * 0.004))
    path = os.path.join(OUT, name + ".wav")
    os.makedirs(OUT, exist_ok=True)
    with wave.open(path, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(SR)
        frames = bytearray()
        for i, value in enumerate(samples):
            gain = scale
            if i < fade:
                gain *= i / fade
            if i >= len(samples) - fade:
                gain *= (len(samples) - 1 - i) / fade
            pcm = int(max(-1.0, min(1.0, value * gain)) * 32767)
            frames += int(pcm).to_bytes(2, "little", signed=True)
        wav.writeframes(bytes(frames))
    print(f"{name}.wav  {len(samples) / SR * 1000:.0f} ms  {os.path.getsize(path) // 1024} KB")


# Soft: warm, quiet ticks and a two-tone confirmation.
write("sfx_soft_h", tone(1900, 0.045, amp=0.28, decay=0.018, harmonics=((1, 1), (2, 0.15))))
write("sfx_soft_v", tone(1250, 0.055, amp=0.26, decay=0.022, harmonics=((1, 1), (2, 0.10))))
write("sfx_soft_select", mix(
    (tone(660, 0.11, amp=0.26, decay=0.060), 0),
    (tone(990, 0.17, amp=0.24, decay=0.070), int(SR * 0.09)),
))
write("sfx_soft_back", tone(620, 0.16, amp=0.22, decay=0.060, slide_to=420))

# Crisp: short, bright clicks for faster navigation.
write("sfx_crisp_h", tone(3100, 0.025, amp=0.32, decay=0.008, harmonics=((1, 1), (3, 0.2))))
write("sfx_crisp_v", tone(2300, 0.030, amp=0.30, decay=0.010, harmonics=((1, 1), (2, 0.2))))
write("sfx_crisp_select", mix(
    (tone(1500, 0.050, amp=0.30, decay=0.015), 0),
    (tone(2200, 0.070, amp=0.26, decay=0.020), int(SR * 0.03)),
))
write("sfx_crisp_back", tone(1100, 0.090, amp=0.26, decay=0.030, slide_to=700))

# Chime: musical cues, a C-E-G arpeggio for selection.
write("sfx_chime_h", tone(1046.5, 0.090, amp=0.24, decay=0.050, harmonics=((1, 1), (2, 0.28), (3, 0.08))))
write("sfx_chime_v", tone(783.99, 0.100, amp=0.24, decay=0.055, harmonics=((1, 1), (2, 0.22))))
write("sfx_chime_select", mix(
    (tone(523.25, 0.240, amp=0.22, decay=0.090, harmonics=((1, 1), (2, 0.20))), 0),
    (tone(659.25, 0.240, amp=0.21, decay=0.090, harmonics=((1, 1), (2, 0.20))), int(SR * 0.085)),
    (tone(783.99, 0.260, amp=0.22, decay=0.100, harmonics=((1, 1), (2, 0.20))), int(SR * 0.170)),
))
write("sfx_chime_back", tone(659.25, 0.200, amp=0.22, decay=0.080, slide_to=440))
