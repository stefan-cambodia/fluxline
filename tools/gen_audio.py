#!/usr/bin/env python3
"""
Génère tous les sons de FLUXLINE : bruitages et deux musiques en boucle.
Synthèse additive en numpy (aucun échantillon importé), encodage OGG Vorbis
par ffmpeg.

Sortie : assets/audio/sfx/*.ogg, assets/audio/music/{menu,game}.ogg
Usage  : python3 tools/gen_audio.py [--wav]   (--wav garde aussi les .wav)
"""
import math
import os
import subprocess
import sys
import tempfile
import wave

import numpy as np

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT = os.path.join(ROOT, "assets", "audio")
SR = 44100
RNG = np.random.default_rng(1989)


# --- Briques de synthèse ---------------------------------------------------------------

def t_axis(dur):
    return np.arange(int(dur * SR)) / SR


def midi(n):
    return 440.0 * 2 ** ((n - 69) / 12)


def env_adsr(n, a, d, s, r, sustain_time=None):
    """Enveloppe ADSR (secondes) sur n échantillons."""
    out = np.zeros(n)
    a_n, d_n, r_n = int(a * SR), int(d * SR), int(r * SR)
    s_n = max(0, n - a_n - d_n - r_n) if sustain_time is None else int(sustain_time * SR)
    i = 0
    seg = min(a_n, n - i)
    out[i:i + seg] = np.linspace(0, 1, a_n, endpoint=False)[:seg] if a_n else 1
    i += seg
    seg = min(d_n, n - i)
    if seg > 0:
        out[i:i + seg] = np.linspace(1, s, d_n, endpoint=False)[:seg]
    i += seg
    seg = min(s_n, n - i)
    if seg > 0:
        out[i:i + seg] = s
    i += seg
    seg = min(r_n, n - i)
    if seg > 0:
        out[i:i + seg] = np.linspace(s, 0, r_n)[:seg]
    return out


def exp_env(n, tau):
    return np.exp(-np.arange(n) / SR / tau)


def additive(freq, dur, harmonics, amp_fn, decay_fn=None, detune=0.0, phase_rand=True):
    """Somme de partiels k·f, amplitude amp_fn(k), décroissance propre decay_fn(k)."""
    t = t_axis(dur)
    out = np.zeros_like(t)
    nyq = SR / 2 * 0.92
    for k in range(1, harmonics + 1):
        f = freq * k * (1 + detune)
        if f >= nyq:
            break
        ph = RNG.uniform(0, 2 * math.pi) if phase_rand else 0
        a = amp_fn(k)
        partial = a * np.sin(2 * math.pi * f * t + ph)
        if decay_fn is not None:
            partial *= np.exp(-t / decay_fn(k))
        out += partial
    return out


def saw(freq, dur, bright=12.0, decay=None, detune=0.0):
    return additive(freq, dur, 60, lambda k: (1 / k) * math.exp(-k / bright), decay, detune)


def square(freq, dur, bright=10.0, decay=None):
    return additive(freq, dur, 60, lambda k: (1 / k if k % 2 else 0) * math.exp(-k / bright), decay)


def sine(freq, dur, phase=0.0):
    return np.sin(2 * math.pi * freq * t_axis(dur) + phase)


def sweep(f0, f1, dur, curve=1.0):
    t = t_axis(dur)
    x = (t / dur) ** curve
    f = f0 + (f1 - f0) * x
    return np.sin(2 * math.pi * np.cumsum(f) / SR)


def noise(dur):
    return RNG.uniform(-1, 1, int(dur * SR))


def fft_filter(x, lo=None, hi=None, slope=1.5):
    """Passe-bande doux appliqué dans le domaine fréquentiel."""
    n = len(x)
    if n == 0:
        return x
    spec = np.fft.rfft(x)
    f = np.fft.rfftfreq(n, 1 / SR)
    gain = np.ones_like(f)
    if lo:
        gain *= 1 / (1 + (lo / np.maximum(f, 1e-3)) ** (2 * slope))
    if hi:
        gain *= 1 / (1 + (f / hi) ** (2 * slope))
    return np.fft.irfft(spec * gain, n)


def bell(freq, dur, bright=1.0):
    t = t_axis(dur)
    out = np.zeros_like(t)
    for ratio, amp, tau in ((1.0, 1.0, 0.9), (2.01, 0.55, 0.55), (2.76, 0.35 * bright, 0.35), (4.07, 0.22 * bright, 0.22), (5.43, 0.12 * bright, 0.14)):
        out += amp * np.sin(2 * math.pi * freq * ratio * t) * np.exp(-t / (tau * dur))
    return out * env_adsr(len(t), 0.002, 0.0, 1.0, 0.02)


def place(buf, sig, at, gain=1.0):
    i = int(at * SR)
    if i >= len(buf):
        return
    j = min(len(buf), i + len(sig))
    buf[i:j] += sig[: j - i] * gain


def reverb_ir(dur=1.6, tau=0.45, damp=6000, stereo=False, seed=7):
    r = np.random.default_rng(seed)
    n = int(dur * SR)
    t = np.arange(n) / SR
    chans = []
    for _ in range(2 if stereo else 1):
        ir = r.uniform(-1, 1, n) * np.exp(-t / tau)
        ir = fft_filter(ir, lo=120, hi=damp)
        ir[: int(0.012 * SR)] *= np.linspace(0, 1, int(0.012 * SR))
        chans.append(ir / np.sqrt(np.sum(ir ** 2)))
    return chans


def convolve(x, ir):
    n = len(x) + len(ir) - 1
    size = 1 << (n - 1).bit_length()
    return np.fft.irfft(np.fft.rfft(x, size) * np.fft.rfft(ir, size), size)[:n]


def finish(x, peak=0.89, fade_in=0.002, fade_out=0.01):
    x = np.array(x, dtype=np.float64)
    if x.ndim == 1:
        a = int(fade_in * SR)
        b = int(fade_out * SR)
        if a:
            x[:a] *= np.linspace(0, 1, a)
        if b:
            x[-b:] *= np.linspace(1, 0, b)
    m = np.max(np.abs(x)) or 1
    x = x / m * peak
    return np.tanh(x * 1.1) / np.tanh(1.1) * peak / max(peak, 1e-9) * peak


# --- Bruitages ------------------------------------------------------------------------------

def sfx_place():
    d = 0.16
    out = np.zeros(int(d * SR))
    click = fft_filter(noise(0.012), lo=2500) * exp_env(int(0.012 * SR), 0.003)
    place(out, click, 0, 0.6)
    thump = sweep(190, 80, 0.06) * exp_env(int(0.06 * SR), 0.018)
    place(out, thump, 0.0, 0.9)
    ping = bell(1480, 0.14, 0.6) * 0.35
    place(out, ping, 0.004)
    return finish(out, 0.8)


def sfx_replace():
    d = 0.42
    out = np.zeros(int(d * SR))
    crunch = fft_filter(noise(0.22), lo=300, hi=4000) * exp_env(int(0.22 * SR), 0.06)
    place(out, crunch, 0, 0.8)
    zap = square(0, 0.01) if False else sweep(900, 130, 0.18, 0.6) * exp_env(int(0.18 * SR), 0.07)
    place(out, zap, 0, 0.5)
    for k in range(6):
        f = RNG.uniform(2500, 5200)
        place(out, bell(f, 0.12, 0.3) * 0.12, 0.02 + k * 0.025)
    place(out, sweep(120, 50, 0.12) * exp_env(int(0.12 * SR), 0.04), 0, 0.8)
    return finish(out, 0.85)


def sfx_rotate():
    d = 0.12
    out = np.zeros(int(d * SR))
    whoosh = fft_filter(noise(0.1), lo=1500, hi=7000) * env_adsr(int(0.1 * SR), 0.03, 0.07, 0, 0)
    place(out, whoosh, 0, 0.5)
    place(out, sweep(700, 1100, 0.05) * exp_env(int(0.05 * SR), 0.015), 0.0, 0.5)
    return finish(out, 0.7)


def sfx_refuse():
    d = 0.22
    out = np.zeros(int(d * SR))
    for k in range(2):
        b = square(165, 0.07, bright=6) * env_adsr(int(0.07 * SR), 0.004, 0.02, 0.6, 0.02)
        place(out, b, k * 0.1, 0.6)
    return finish(out, 0.6)


def sfx_tick():
    s = sine(1046.5, 0.07) * env_adsr(int(0.07 * SR), 0.003, 0.05, 0.0, 0.01)
    s += 0.3 * sine(2093, 0.07) * exp_env(int(0.07 * SR), 0.012)
    return finish(s, 0.7)


def sfx_go():
    d = 0.9
    out = np.zeros(int(d * SR))
    rise = sweep(110, 420, 0.5, 0.7) * env_adsr(int(0.5 * SR), 0.05, 0.1, 0.8, 0.35)
    place(out, rise, 0, 0.6)
    air = fft_filter(noise(0.6), lo=800, hi=5000) * env_adsr(int(0.6 * SR), 0.2, 0.1, 0.5, 0.3)
    place(out, air, 0, 0.25)
    for n in (57, 64, 69):
        place(out, saw(midi(n), 0.5, bright=6, decay=lambda k: 0.5 / k) * env_adsr(int(0.5 * SR), 0.005, 0.2, 0.3, 0.2), 0.32, 0.22)
    return finish(out, 0.85)


def sfx_fill():
    d = 0.11
    s = sweep(620, 380, d, 0.5) * env_adsr(int(d * SR), 0.004, 0.04, 0.3, 0.05)
    s += 0.25 * sweep(1240, 760, d, 0.5) * exp_env(int(d * SR), 0.02)
    return finish(fft_filter(s, hi=3000), 0.6)


def sfx_cross():
    d = 0.75
    out = np.zeros(int(d * SR))
    for i, n in enumerate((84, 88, 91, 96)):
        place(out, bell(midi(n), 0.5), i * 0.06, 0.5)
    shimmer = fft_filter(noise(0.4), lo=6000) * exp_env(int(0.4 * SR), 0.12)
    place(out, shimmer, 0.05, 0.15)
    return finish(out, 0.85)


def sfx_target():
    d = 1.1
    out = np.zeros(int(d * SR))
    place(out, bell(midi(76), 1.0), 0, 0.6)
    place(out, bell(midi(80), 1.0), 0.09, 0.5)
    place(out, bell(midi(83), 0.9), 0.18, 0.45)
    return finish(out, 0.85)


def sfx_leak():
    d = 1.2
    out = np.zeros(int(d * SR))
    hiss = fft_filter(noise(1.1), lo=1200, hi=9000)
    wob = 0.6 + 0.4 * np.sin(2 * math.pi * 7 * t_axis(1.1))
    hiss *= wob * env_adsr(len(hiss), 0.02, 0.3, 0.5, 0.6)
    place(out, hiss, 0, 0.45)
    place(out, sweep(160, 45, 0.3) * exp_env(int(0.3 * SR), 0.09), 0, 0.9)
    gurgle = np.zeros(int(0.8 * SR))
    for k in range(9):
        f = RNG.uniform(250, 700)
        b = sweep(f, f * 0.6, 0.06) * exp_env(int(0.06 * SR), 0.015)
        place(gurgle, b, RNG.uniform(0, 0.7), 0.4)
    place(out, gurgle, 0.05)
    place(out, saw(midi(52), 0.6, bright=5, decay=lambda k: 0.4) * env_adsr(int(0.6 * SR), 0.01, 0.2, 0.4, 0.3), 0.08, 0.25)
    place(out, saw(midi(51), 0.6, bright=5, decay=lambda k: 0.4) * env_adsr(int(0.6 * SR), 0.01, 0.2, 0.4, 0.3), 0.32, 0.25)
    return finish(out, 0.85)


def sfx_drain():
    d = 1.3
    out = np.zeros(int(d * SR))
    swirl = fft_filter(noise(0.8), lo=400, hi=3000) * np.sin(2 * math.pi * np.cumsum(np.linspace(3, 14, int(0.8 * SR))) / SR) ** 2
    place(out, swirl * env_adsr(len(swirl), 0.1, 0.3, 0.6, 0.3), 0, 0.4)
    for i, n in enumerate((72, 76, 79, 84)):
        place(out, bell(midi(n), 0.9), 0.25 + i * 0.07, 0.4)
    return finish(out, 0.85)


def brass(freq, dur):
    s = saw(freq, dur, bright=9, detune=0.0) + saw(freq, dur, bright=9, detune=0.004)
    return s * env_adsr(len(s), 0.02, 0.15, 0.7, 0.12)


def sfx_win():
    d = 2.0
    out = np.zeros(int(d * SR))
    seq = [(72, 0.0), (76, 0.09), (79, 0.18), (84, 0.27)]
    for n, at in seq:
        place(out, brass(midi(n), 0.16), at, 0.35)
        place(out, bell(midi(n + 12), 0.3), at, 0.15)
    for n in (72, 76, 79, 84, 60):
        place(out, brass(midi(n), 1.1) * exp_env(int(1.1 * SR), 0.6), 0.38, 0.22)
    sparkle = fft_filter(noise(1.0), lo=7000) * exp_env(int(1.0 * SR), 0.3)
    place(out, sparkle, 0.38, 0.1)
    ir = reverb_ir(1.2, 0.35)[0]
    out = out + 0.25 * convolve(out, ir)[: len(out)]
    return finish(out, 0.88)


def sfx_fail():
    d = 1.6
    out = np.zeros(int(d * SR))
    for i, n in enumerate((67, 63, 60)):
        place(out, brass(midi(n), 0.45) * exp_env(int(0.45 * SR), 0.3), i * 0.22, 0.3)
    place(out, brass(midi(48), 0.9) * exp_env(int(0.9 * SR), 0.5), 0.66, 0.3)
    ir = reverb_ir(1.2, 0.4)[0]
    out = out + 0.25 * convolve(out, ir)[: len(out)]
    return finish(fft_filter(out, hi=4000), 0.8)


def sfx_fast():
    d = 0.8
    out = np.zeros(int(d * SR))
    sw = fft_filter(noise(0.7), lo=600, hi=8000)
    sw *= np.linspace(0.2, 1, len(sw)) ** 2 * env_adsr(len(sw), 0.05, 0.1, 1, 0.15)
    place(out, sw, 0, 0.35)
    place(out, sweep(200, 900, 0.6, 1.8) * env_adsr(int(0.6 * SR), 0.05, 0.1, 0.8, 0.2), 0, 0.4)
    place(out, bell(midi(81), 0.4), 0.45, 0.3)
    return finish(out, 0.8)


def sfx_click():
    s = np.zeros(int(0.05 * SR))
    place(s, fft_filter(noise(0.006), lo=3000) * exp_env(int(0.006 * SR), 0.0015), 0, 0.5)
    place(s, sine(2350, 0.04) * exp_env(int(0.04 * SR), 0.008), 0.001, 0.5)
    return finish(s, 0.6)


def sfx_back():
    s = np.zeros(int(0.06 * SR))
    place(s, fft_filter(noise(0.006), lo=2000) * exp_env(int(0.006 * SR), 0.0015), 0, 0.5)
    place(s, sine(1500, 0.05) * exp_env(int(0.05 * SR), 0.01), 0.001, 0.5)
    return finish(s, 0.55)


def sfx_star():
    out = np.zeros(int(0.8 * SR))
    place(out, bell(midi(88), 0.7), 0, 0.6)
    place(out, fft_filter(noise(0.4), lo=6000) * exp_env(int(0.4 * SR), 0.1), 0.0, 0.12)
    return finish(out, 0.8)


def sfx_count():
    s = sine(1800, 0.025) * exp_env(int(0.025 * SR), 0.005)
    return finish(s, 0.4)


def sfx_hum():
    """Bourdonnement du fluide, bouclable sans couture (fréquences multiples de 1/durée)."""
    d = 2.0
    t = t_axis(d)
    out = np.zeros_like(t)
    for f, a in ((55, 0.5), (110, 0.35), (165, 0.12), (220, 0.08)):
        out += a * np.sin(2 * math.pi * f * t)
    out *= 0.75 + 0.25 * np.sin(2 * math.pi * 1.5 * t)
    bubbles = np.zeros_like(t)
    r = np.random.default_rng(5)
    for k in range(14):
        at = r.uniform(0, d - 0.08)
        f = r.uniform(300, 650)
        b = sweep(f, f * 1.25, 0.05) * exp_env(int(0.05 * SR), 0.012)
        place(bubbles, b, at, 0.12)
    out += bubbles
    out = fft_filter(out, hi=1800)
    return out / np.max(np.abs(out)) * 0.8


# --- Musiques -------------------------------------------------------------------------------

def render_loop(length_s, voices_fn, reverb_mix=0.28, stereo_width=0.35):
    """Rend une boucle stéréo ; la queue (réverbération) est repliée sur le début."""
    tail = 3.0
    n = int((length_s + tail) * SR)
    left = np.zeros(n)
    right = np.zeros(n)
    voices_fn(left, right)
    irs = reverb_ir(2.4, 0.7, 5000, stereo=True)
    wet_l = convolve(left, irs[0])[:n]
    wet_r = convolve(right, irs[1])[:n]
    left = left + reverb_mix * wet_l
    right = right + reverb_mix * wet_r
    loop = int(length_s * SR)
    for ch in (left, right):
        ch[: n - loop] += ch[loop:n]
    left, right = left[:loop], right[:loop]
    mid = (left + right) / 2
    side = (left - right) / 2 * (1 + stereo_width)
    left, right = mid + side, mid - side
    peak = max(np.max(np.abs(left)), np.max(np.abs(right)))
    left = np.tanh(left / peak * 1.05) * 0.82
    right = np.tanh(right / peak * 1.05) * 0.82
    return np.stack([left, right], axis=1)


def pan_add(left, right, sig, at, gain, pan):
    lg = gain * math.cos((pan + 1) * math.pi / 4)
    rg = gain * math.sin((pan + 1) * math.pi / 4)
    place(left, sig, at, lg)
    place(right, sig, at, rg)


def pad_voice(freq, dur):
    s = saw(freq, dur, bright=6, detune=-0.003) + saw(freq, dur, bright=6, detune=0.003) + 0.6 * saw(freq / 2, dur, bright=4)
    return fft_filter(s, hi=2200) * env_adsr(len(s), 0.9, 0.5, 0.75, 1.2)


def pluck_voice(freq, dur, bright=14.0):
    s = saw(freq, dur, bright=bright, decay=lambda k: 0.45 / (1 + k * 0.35))
    return s * env_adsr(len(s), 0.003, dur * 0.5, 0.0, dur * 0.4)


def bass_voice(freq, dur):
    s = saw(freq, dur, bright=7, decay=lambda k: 0.25 / (1 + k * 0.2)) + 0.8 * sine(freq, dur)
    return s * env_adsr(len(s), 0.004, 0.08, 0.7, 0.05)


def kick():
    s = sweep(160, 42, 0.32, 0.4) * exp_env(int(0.32 * SR), 0.11)
    s[: int(0.004 * SR)] += fft_filter(noise(0.004), lo=1500) * 0.4
    return s


def snare():
    n_ = fft_filter(noise(0.22), lo=1200, hi=9000) * exp_env(int(0.22 * SR), 0.06)
    tone = sine(190, 0.12) * exp_env(int(0.12 * SR), 0.035)
    tone = np.pad(tone, (0, len(n_) - len(tone)))
    return n_ * 0.8 + tone * 0.5


def hat(open_=False):
    d = 0.18 if open_ else 0.05
    return fft_filter(noise(d), lo=7000) * exp_env(int(d * SR), 0.05 if open_ else 0.012)


CHORDS_MENU = [(57, 60, 64), (53, 57, 60), (48, 52, 55), (55, 59, 62)]  # Am F C G


def music_menu():
    bpm = 84
    beat = 60 / bpm
    bars = 16
    length = bars * 4 * beat

    def voices(L, R):
        for bar in range(bars):
            chord = CHORDS_MENU[bar % 4]
            t0 = bar * 4 * beat
            for i, n in enumerate(chord):
                pan_add(L, R, pad_voice(midi(n), 4 * beat + 0.4), t0, 0.16, (-0.5, 0.0, 0.5)[i])
            # Basse lente.
            pan_add(L, R, bass_voice(midi(chord[0] - 24), 2 * beat), t0, 0.32, 0)
            pan_add(L, R, bass_voice(midi(chord[0] - 24), 1.5 * beat), t0 + 2.5 * beat, 0.24, 0)
            # Arpège cristallin, à partir de la mesure 5.
            if bar >= 4:
                pattern = [0, 1, 2, 1, 2, 3, 2, 1]
                notes = list(chord) + [chord[0] + 12]
                for k, p in enumerate(pattern):
                    at = t0 + k * beat / 2
                    pan_add(L, R, pluck_voice(midi(notes[p] + 12), beat * 0.9, bright=9), at, 0.075, 0.6 if k % 2 else -0.6)
            # Cloche en contre-chant toutes les deux mesures.
            if bar % 2 == 1 and bar >= 8:
                pan_add(L, R, bell(midi(chord[2] + 24), 1.8, 0.5), t0 + 3 * beat, 0.05, 0.3)
    return render_loop(length, voices, reverb_mix=0.38, stereo_width=0.5)


CHORDS_GAME = [(52, 55, 59), (48, 52, 55), (55, 59, 62), (50, 54, 57)]  # Em C G D


def music_game():
    bpm = 112
    beat = 60 / bpm
    bars = 32
    length = bars * 4 * beat
    k_ = kick()
    s_ = snare()
    h_ = hat()
    ho_ = hat(True)

    def voices(L, R):
        for bar in range(bars):
            chord = CHORDS_GAME[bar % 4]
            t0 = bar * 4 * beat
            section = bar // 8  # 0 intro, 1 groove, 2 lead, 3 breakdown-ish
            # Batterie.
            for b in range(4):
                if section != 3 or b % 2 == 0:
                    pan_add(L, R, k_, t0 + b * beat, 0.42 if section else 0.3, 0)
                if b in (1, 3) and section >= 1:
                    pan_add(L, R, s_, t0 + b * beat, 0.22, 0.05)
                for e in range(2):
                    hh = ho_ if (e == 1 and b == 3) else h_
                    pan_add(L, R, hh, t0 + b * beat + e * beat / 2, 0.07 if e else 0.05, 0.35)
            # Basse en croches, octave sur le temps faible.
            for e in range(8):
                n = chord[0] - 24 + (12 if e % 2 else 0)
                pan_add(L, R, bass_voice(midi(n), beat / 2 * 0.9), t0 + e * beat / 2, 0.22, 0)
            # Nappe discrète.
            for i, n in enumerate(chord):
                pan_add(L, R, pad_voice(midi(n + 12), 4 * beat + 0.3), t0, 0.07, (-0.6, 0, 0.6)[i])
            # Arpège en doubles croches dans les sections 1 et 2.
            if section in (1, 2):
                notes = [chord[0], chord[1], chord[2], chord[0] + 12]
                for e in range(16):
                    n = notes[(e * 3) % 4] + 12
                    pan_add(L, R, pluck_voice(midi(n), beat / 4 * 1.6, bright=10), t0 + e * beat / 4, 0.05, 0.5 if e % 2 else -0.5)
            # Mélodie en section 2.
            if section == 2:
                motif = [(0, 2, 0), (2, 1, 1), (3, 1, 2), (4, 2, 3), (6, 2, 1)]
                scale = [chord[0] + 12, chord[1] + 12, chord[2] + 12, chord[0] + 24]
                for start, length_b, idx in motif:
                    s = brass(midi(scale[idx]), length_b * beat / 2 * 0.95)
                    pan_add(L, R, fft_filter(s, hi=3500), t0 + start * beat / 2, 0.07, 0.1)
    return render_loop(length, voices, reverb_mix=0.22, stereo_width=0.35)


# --- Écriture ---------------------------------------------------------------------------------

def write_wav(path, data):
    data = np.asarray(data)
    channels = 1 if data.ndim == 1 else data.shape[1]
    pcm = (np.clip(data, -1, 1) * 32767).astype("<i2")
    with wave.open(path, "wb") as w:
        w.setnchannels(channels)
        w.setsampwidth(2)
        w.setframerate(SR)
        w.writeframes(pcm.tobytes())


def to_ogg(data, path, quality):
    with tempfile.TemporaryDirectory() as tmp:
        wav = os.path.join(tmp, "x.wav")
        write_wav(wav, data)
        subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", wav, "-c:a", "libvorbis", "-q:a", str(quality), path],
            check=True,
        )
        if "--wav" in sys.argv:
            write_wav(path.replace(".ogg", ".wav"), data)


SFX = {
    "place": sfx_place, "replace": sfx_replace, "rotate": sfx_rotate, "refuse": sfx_refuse,
    "tick": sfx_tick, "go": sfx_go, "fill": sfx_fill, "cross": sfx_cross, "target": sfx_target,
    "leak": sfx_leak, "drain": sfx_drain, "win": sfx_win, "fail": sfx_fail, "fast": sfx_fast,
    "click": sfx_click, "back": sfx_back, "star": sfx_star, "count": sfx_count, "hum": sfx_hum,
}


def main():
    os.makedirs(os.path.join(OUT, "sfx"), exist_ok=True)
    os.makedirs(os.path.join(OUT, "music"), exist_ok=True)
    for name, fn in SFX.items():
        to_ogg(fn(), os.path.join(OUT, "sfx", name + ".ogg"), 5)
        print("  sfx", name, file=sys.stderr)
    to_ogg(music_menu(), os.path.join(OUT, "music", "menu.ogg"), 3)
    print("  musique menu", file=sys.stderr)
    to_ogg(music_game(), os.path.join(OUT, "music", "game.ogg"), 3)
    print("  musique jeu", file=sys.stderr)


if __name__ == "__main__":
    main()
