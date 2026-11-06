#!/usr/bin/env python3
"""
Génère l'atlas de l'interface de FLUXLINE : logo, panneaux et boutons
(neuf-tranches), icônes. Tout est dessiné ici, rien n'est importé.

Les icônes sont blanches (teintées à l'affichage). Les neuf-tranches sont
déclarées avec « split » dans l'atlas, que libGDX lit comme des NinePatch.

Sortie : assets/gfx/ui.png + assets/gfx/ui.atlas
"""
import math
import os
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFilter, ImageFont

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "assets", "gfx")
FONTS = os.path.join(ROOT, "tools", "fonts")
SS = 4


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def rounded_rect_sdf(w, h, r, ss=SS):
    """Distance signée (en px finaux) à un rectangle arrondi de w×h."""
    ys = (np.arange(h * ss) + 0.5) / ss
    xs = (np.arange(w * ss) + 0.5) / ss
    X, Y = np.meshgrid(xs, ys)
    qx = np.abs(X - w / 2) - (w / 2 - r)
    qy = np.abs(Y - h / 2) - (h / 2 - r)
    outside = np.sqrt(np.maximum(qx, 0) ** 2 + np.maximum(qy, 0) ** 2)
    inside = np.minimum(np.maximum(qx, qy), 0)
    return outside + inside - r, X, Y


def down(a, ss=SS):
    h, w = a.shape[0] // ss, a.shape[1] // ss
    if a.ndim == 2:
        return a.reshape(h, ss, w, ss).mean(axis=(1, 3))
    return a.reshape(h, ss, w, ss, a.shape[2]).mean(axis=(1, 3))


def finish(rgb, a):
    """Prémultiplication pour le sous-échantillonnage, puis débordement des couleurs."""
    pre = down(rgb * a[..., None])
    al = down(a)
    col = np.where(al[..., None] > 1e-4, pre / np.maximum(al[..., None], 1e-4), 0)
    col = bleed(col, al)
    return (np.dstack([np.clip(col, 0, 1), np.clip(al, 0, 1)]) * 255 + 0.5).astype(np.uint8)


def bleed(rgb, a, passes=4):
    rgb = rgb.copy()
    known = a > 0.004
    for _ in range(passes):
        acc = np.zeros_like(rgb)
        cnt = np.zeros(a.shape)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            k = np.roll(known, (dy, dx), axis=(0, 1)).astype(np.float64)
            acc += np.roll(rgb * known[..., None], (dy, dx), axis=(0, 1))
            cnt += k
        grow = (~known) & (cnt > 0)
        rgb = np.where(grow[..., None], acc / np.maximum(cnt, 1)[..., None], rgb)
        known |= grow
    return rgb


# --- Panneaux et boutons -----------------------------------------------------------------

AMBER = np.array([1.00, 0.70, 0.22])
CYAN = np.array([0.30, 0.92, 1.00])


def panel(size=128, radius=30):
    """Panneau de verre fumé, liseré ambre et filet intérieur clair."""
    d, X, Y = rounded_rect_sdf(size, size, radius)
    a = smoothstep(0.6, -0.6, d)
    ny = Y / size
    base = np.array([0.050, 0.075, 0.130])
    rgb = base[None, None, :] * (1.15 - 0.35 * ny)[..., None]
    rim = smoothstep(-4.5, -2.5, d) * smoothstep(0.5, -0.8, d)
    rgb = rgb * (1 - rim[..., None]) + (AMBER * 0.85)[None, None, :] * rim[..., None]
    inner = np.exp(-((d + 7.5) / 1.0) ** 2) * 0.35 * (1 - ny)
    rgb = rgb + np.array([0.6, 0.8, 1.0])[None, None, :] * inner[..., None] * 0.4
    alpha = a * np.where(d < -4.5, 0.93, 1.0)
    return finish(rgb, alpha), radius + 6


def button(size=128, radius=34, pressed=False, accent=AMBER):
    """Bouton bombé en acier sombre, liseré coloré, reflet supérieur."""
    d, X, Y = rounded_rect_sdf(size, size, radius)
    a = smoothstep(0.6, -0.6, d)
    ny = Y / size
    top = np.array([0.20, 0.25, 0.34]) if not pressed else np.array([0.08, 0.10, 0.15])
    bot = np.array([0.08, 0.10, 0.16]) if not pressed else np.array([0.12, 0.15, 0.22])
    rgb = top[None, None, :] * (1 - ny)[..., None] + bot[None, None, :] * ny[..., None]
    rim = smoothstep(-5.0, -3.0, d) * smoothstep(0.5, -0.8, d)
    rgb = rgb * (1 - rim[..., None]) + (accent * (0.75 if pressed else 1.0))[None, None, :] * rim[..., None]
    if not pressed:
        gloss = smoothstep(-6, -9, d) * smoothstep(0.55, 0.25, ny) * 0.10
        rgb = rgb + gloss[..., None]
    return finish(rgb, a), radius + 6


def pill(w=128, h=64):
    d, X, Y = rounded_rect_sdf(w, h, h / 2 - 0.5)
    a = smoothstep(0.6, -0.6, d)
    ny = Y / h
    rgb = np.array([0.04, 0.06, 0.11])[None, None, :] * (1.2 - 0.4 * ny)[..., None]
    rim = smoothstep(-3.0, -1.6, d) * smoothstep(0.5, -0.8, d)
    rgb = rgb * (1 - rim[..., None]) + np.array([0.25, 0.45, 0.60])[None, None, :] * rim[..., None]
    return finish(rgb, a * 0.92 + rim * 0.08), int(h / 2)


def bar(w=64, h=24, fill=False):
    d, X, Y = rounded_rect_sdf(w, h, h / 2 - 0.5)
    a = smoothstep(0.6, -0.6, d)
    if fill:
        ny = Y / h
        rgb = np.ones((h * SS, w * SS, 3)) * (0.85 + 0.15 * (1 - ny))[..., None]
    else:
        rgb = np.ones((h * SS, w * SS, 3)) * np.array([0.03, 0.05, 0.09])
        rim = smoothstep(-2.0, -1.0, d) * smoothstep(0.5, -0.8, d)
        rgb = rgb * (1 - rim[..., None]) + np.array([0.22, 0.32, 0.45]) * rim[..., None]
    return finish(rgb, a), int(h / 2)


def knob(size=72):
    ys = (np.arange(size * SS) + 0.5) / SS
    X, Y = np.meshgrid(ys, ys)
    r = np.sqrt((X - size / 2) ** 2 + (Y - size / 2) ** 2) / (size / 2)
    a = smoothstep(0.97, 0.90, r)
    h = np.sqrt(np.clip(1 - r * r, 0, 1))
    shade = 0.45 + 0.55 * np.clip(((X - size / 2) * -0.4 + (Y - size / 2) * -0.6) / (size / 2) + h * 0.7, 0, 1)
    rgb = AMBER[None, None, :] * shade[..., None]
    rgb += np.clip(1 - np.abs(r - 0.55) / 0.06, 0, 1)[..., None] * 0.15
    return finish(rgb, a)


# --- Icônes ---------------------------------------------------------------------------------

def icon(draw_fn, size=96):
    big = size * SS
    img = Image.new("L", (big, big), 0)
    d = ImageDraw.Draw(img)
    s = big / 96.0
    draw_fn(d, s, big)
    a = np.asarray(img).astype(np.float64) / 255
    rgb = np.ones((big, big, 3))
    return finish(rgb, a)


def P(s, *pts):
    return [(x * s, y * s) for x, y in pts]


def ic_pause(d, s, big):
    d.rounded_rectangle(P(s, (26, 20), (40, 76)), radius=4 * s, fill=255)
    d.rounded_rectangle(P(s, (56, 20), (70, 76)), radius=4 * s, fill=255)


def ic_play(d, s, big):
    d.polygon(P(s, (30, 18), (78, 48), (30, 78)), fill=255)


def ic_fast(d, s, big):
    d.polygon(P(s, (14, 22), (48, 48), (14, 74)), fill=255)
    d.polygon(P(s, (46, 22), (80, 48), (46, 74)), fill=255)
    d.rectangle(P(s, (80, 22), (88, 74)), fill=255)


def arc_arrow(d, s, start, end, clockwise=True):
    c = 48 * s
    r = 28 * s
    w = int(10 * s)
    d.arc([c - r, c - r, c + r, c + r], start=start, end=end, fill=255, width=w)
    ang = math.radians(end if clockwise else start)
    tip_x = c + r * math.cos(ang)
    tip_y = c + r * math.sin(ang)
    # Pointe de flèche tangente.
    tdir = ang + (math.pi / 2 if clockwise else -math.pi / 2)
    nx, ny = math.cos(ang), math.sin(ang)
    tx, ty = math.cos(tdir), math.sin(tdir)
    size = 15 * s
    pts = [
        (tip_x + tx * size, tip_y + ty * size),
        (tip_x + nx * size * 0.95, tip_y + ny * size * 0.95),
        (tip_x - nx * size * 0.95, tip_y - ny * size * 0.95),
    ]
    d.polygon(pts, fill=255)


def ic_rotate(d, s, big):
    arc_arrow(d, s, 200, 120 + 360 - 30, clockwise=True)


def ic_restart(d, s, big):
    arc_arrow(d, s, -60, 230, clockwise=False)


def ic_back(d, s, big):
    d.line(P(s, (62, 16), (30, 48), (62, 80)), fill=255, width=int(12 * s), joint="curve")
    for x, y in ((62, 16), (30, 48), (62, 80)):
        d.ellipse([x * s - 6 * s, y * s - 6 * s, x * s + 6 * s, y * s + 6 * s], fill=255)


def ic_home(d, s, big):
    d.polygon(P(s, (48, 12), (86, 46), (76, 46), (76, 82), (20, 82), (20, 46), (10, 46)), fill=255)
    d.rectangle(P(s, (40, 56), (56, 82)), fill=0)


def ic_gear(d, s, big):
    c = 48 * s
    for k in range(8):
        ang = k * math.pi / 4
        pts = []
        for da, rr in ((-0.18, 30), (-0.13, 42), (0.13, 42), (0.18, 30)):
            pts.append((c + math.cos(ang + da) * rr * s, c + math.sin(ang + da) * rr * s))
        d.polygon(pts, fill=255)
    d.ellipse([c - 32 * s, c - 32 * s, c + 32 * s, c + 32 * s], fill=255)
    d.ellipse([c - 13 * s, c - 13 * s, c + 13 * s, c + 13 * s], fill=0)


def ic_music(d, s, big):
    d.polygon(P(s, (36, 18), (78, 10), (78, 22), (36, 30)), fill=255)
    d.rectangle(P(s, (36, 18), (43, 70)), fill=255)
    d.rectangle(P(s, (71, 10), (78, 62)), fill=255)
    d.ellipse(P(s, (18, 60), (44, 82)), fill=255)
    d.ellipse(P(s, (53, 52), (79, 74)), fill=255)


def ic_sound(d, s, big):
    d.polygon(P(s, (12, 36), (30, 36), (52, 16), (52, 80), (30, 60), (12, 60)), fill=255)
    for r in (16, 30):
        d.arc([58 * s - r * s, 48 * s - r * s, 58 * s + r * s, 48 * s + r * s], start=-50, end=50, fill=255, width=int(8 * s))


def ic_vibrate(d, s, big):
    d.rounded_rectangle(P(s, (32, 12), (64, 84)), radius=7 * s, fill=255)
    d.rounded_rectangle(P(s, (38, 20), (58, 72)), radius=2 * s, fill=0)
    for x in (16, 80):
        d.line(P(s, (x, 30), (x - 6 if x > 48 else x + 6, 40), (x, 50), (x - 6 if x > 48 else x + 6, 60), (x, 70)), fill=255, width=int(6 * s))


def ic_lock(d, s, big):
    d.rounded_rectangle(P(s, (18, 42), (78, 86)), radius=8 * s, fill=255)
    d.arc(P(s, (28, 10), (68, 58)), start=180, end=360, fill=255, width=int(10 * s))
    d.rectangle(P(s, (28, 32), (36, 44)), fill=255)
    d.rectangle(P(s, (60, 32), (68, 44)), fill=255)
    d.ellipse(P(s, (42, 54), (54, 66)), fill=0)
    d.rectangle(P(s, (45, 60), (51, 76)), fill=0)


def ic_check(d, s, big):
    d.line(P(s, (16, 50), (38, 72), (82, 24)), fill=255, width=int(13 * s), joint="curve")


def ic_close(d, s, big):
    d.line(P(s, (22, 22), (74, 74)), fill=255, width=int(12 * s))
    d.line(P(s, (74, 22), (22, 74)), fill=255, width=int(12 * s))


def ic_trophy(d, s, big):
    d.polygon(P(s, (24, 12), (72, 12), (68, 44), (56, 56), (40, 56), (28, 44)), fill=255)
    d.arc(P(s, (6, 16), (34, 44)), start=90, end=270, fill=255, width=int(7 * s))
    d.arc(P(s, (62, 16), (90, 44)), start=-90, end=90, fill=255, width=int(7 * s))
    d.rectangle(P(s, (42, 54), (54, 70)), fill=255)
    d.rounded_rectangle(P(s, (26, 70), (70, 84)), radius=3 * s, fill=255)


def ic_chart(d, s, big):
    for i, h in enumerate((30, 50, 40, 66)):
        x = 14 + i * 19
        d.rounded_rectangle(P(s, (x, 84 - h), (x + 13, 84)), radius=3 * s, fill=255)


def star_points(cx, cy, r_out, r_in, n=5):
    pts = []
    for k in range(n * 2):
        ang = -math.pi / 2 + k * math.pi / n
        r = r_out if k % 2 == 0 else r_in
        pts.append((cx + math.cos(ang) * r, cy + math.sin(ang) * r))
    return pts


def ic_star(d, s, big):
    d.polygon(star_points(48 * s, 52 * s, 44 * s, 19 * s), fill=255)


def ic_star_outline(d, s, big):
    d.polygon(star_points(48 * s, 52 * s, 44 * s, 19 * s), fill=255)
    d.polygon(star_points(48 * s, 52 * s, 31 * s, 11 * s), fill=0)


def ic_info(d, s, big):
    d.ellipse(P(s, (40, 12), (56, 28)), fill=255)
    d.rounded_rectangle(P(s, (40, 36), (56, 84)), radius=4 * s, fill=255)


def ic_levels(d, s, big):
    for gx in range(3):
        for gy in range(3):
            x = 12 + gx * 26
            y = 12 + gy * 26
            d.rounded_rectangle(P(s, (x, y), (x + 20, y + 20)), radius=4 * s, fill=255)


def ic_heart(d, s, big):
    d.ellipse(P(s, (12, 16), (50, 54)), fill=255)
    d.ellipse(P(s, (46, 16), (84, 54)), fill=255)
    d.polygon(P(s, (14, 42), (48, 84), (82, 42), (48, 30)), fill=255)


def ic_drop(d, s, big):
    d.polygon(P(s, (48, 8), (24, 50), (72, 50)), fill=255)
    d.ellipse(P(s, (22, 32), (74, 86)), fill=255)


def ic_calendar(d, s, big):
    d.rounded_rectangle(P(s, (12, 18), (84, 84)), radius=8 * s, fill=255)
    d.rectangle(P(s, (20, 36), (76, 76)), fill=0)
    for x in (28, 60):
        d.rounded_rectangle(P(s, (x, 8), (x + 8, 26)), radius=3 * s, fill=255)
    for gx in range(3):
        for gy in range(2):
            d.rectangle(P(s, (26 + gx * 16, 44 + gy * 16), (34 + gx * 16, 52 + gy * 16)), fill=255)


def ic_globe(d, s, big):
    w = int(7 * s)
    d.ellipse(P(s, (12, 12), (84, 84)), outline=255, width=w)
    d.ellipse(P(s, (33, 12), (63, 84)), outline=255, width=int(6 * s))
    d.line(P(s, (12, 48), (84, 48)), fill=255, width=int(6 * s))
    d.line(P(s, (19, 30), (77, 30)), fill=255, width=int(5 * s))
    d.line(P(s, (19, 66), (77, 66)), fill=255, width=int(5 * s))


def ic_power(d, s, big):
    """Marche/arrêt : cercle ouvert en haut, barre verticale dans l'ouverture."""
    w = int(10 * s)
    cx, cy, r = 48, 52, 31
    d.arc(P(s, (cx - r, cy - r), (cx + r, cy + r)), start=-50, end=230, fill=255, width=w)
    for ang in (-50, 230):
        ex = cx + (r - 5) * math.cos(math.radians(ang))
        ey = cy + (r - 5) * math.sin(math.radians(ang))
        d.ellipse([(ex - 5) * s, (ey - 5) * s, (ex + 5) * s, (ey + 5) * s], fill=255)
    d.rounded_rectangle(P(s, (43, 9), (53, 50)), radius=5 * s, fill=255)


def ic_hand(d, s, big):
    """Main qui pointe vers le haut : le bout de l'index (44, 6) est le point touché."""
    d.rounded_rectangle(P(s, (36, 6), (52, 58)), radius=8 * s, fill=255)        # index
    d.rounded_rectangle(P(s, (52, 34), (64, 60)), radius=6 * s, fill=255)       # majeur
    d.rounded_rectangle(P(s, (64, 38), (76, 62)), radius=6 * s, fill=255)       # annulaire
    d.rounded_rectangle(P(s, (30, 46), (78, 90)), radius=16 * s, fill=255)      # paume
    d.line(P(s, (38, 74), (19, 50)), fill=255, width=int(15 * s))               # pouce
    for x, y in ((38, 74), (19, 50)):
        d.ellipse([(x - 7.5) * s, (y - 7.5) * s, (x + 7.5) * s, (y + 7.5) * s], fill=255)
    # Fines séparations entre les doigts, pour la lisibilité.
    for x, y0, y1 in ((52, 36, 54), (64, 40, 56)):
        d.line(P(s, (x, y0), (x, y1)), fill=0, width=max(1, int(2.5 * s)))
    d.line(P(s, (31, 62), (25, 55)), fill=0, width=max(1, int(2.5 * s)))


ICONS = {
    "pause": ic_pause, "play": ic_play, "fast": ic_fast, "rotate": ic_rotate,
    "restart": ic_restart, "back": ic_back, "home": ic_home, "gear": ic_gear,
    "music": ic_music, "sound": ic_sound, "vibrate": ic_vibrate, "lock": ic_lock,
    "check": ic_check, "close": ic_close, "trophy": ic_trophy, "chart": ic_chart,
    "star": ic_star, "star_outline": ic_star_outline, "info": ic_info,
    "levels": ic_levels, "heart": ic_heart, "drop": ic_drop, "calendar": ic_calendar,
    "globe": ic_globe, "power": ic_power, "hand": ic_hand,
}


# --- Logo -----------------------------------------------------------------------------------

def logo():
    """« FLUXLINE » en Orbitron Black : chrome dégradé, contour sombre, halo cyan."""
    font = ImageFont.truetype(os.path.join(FONTS, "Orbitron-Variable.ttf"), 150)
    font.set_variation_by_axes([900])
    text = "FLUXLINE"
    bbox = font.getbbox(text)
    tw, th = bbox[2] - bbox[0], bbox[3] - bbox[1]
    pad = 46
    W, H = tw + pad * 2, th + pad * 2
    mask = Image.new("L", (W, H), 0)
    ImageDraw.Draw(mask).text((pad - bbox[0], pad - bbox[1]), text, font=font, fill=255)
    m = np.asarray(mask).astype(np.float64) / 255
    ys = np.linspace(0, 1, H)[:, None] * np.ones((1, W))
    # Chrome : ciel clair, horizon net, sol acier.
    rel = (ys * H - pad) / th
    sky = np.array([0.98, 0.99, 1.0])[None, None, :] * (1 - rel[..., None] * 0.35) + np.array([0.55, 0.80, 1.0]) * (rel[..., None] * 0.35)
    ground = np.array([0.20, 0.30, 0.48])[None, None, :] * (1 - (rel[..., None] - 0.52) * 0.6) + np.array([0.75, 0.88, 1.0]) * np.clip(rel[..., None] - 0.52, 0, 1) * 0.9
    chrome = np.where((rel < 0.52)[..., None], sky, ground)
    horizon = np.exp(-((rel - 0.52) / 0.015) ** 2)
    chrome = chrome + horizon[..., None] * 0.5
    # Contour : dilatation du masque.
    outline = np.asarray(mask.filter(ImageFilter.MaxFilter(9))).astype(np.float64) / 255
    glow = np.asarray(mask.filter(ImageFilter.MaxFilter(5)).filter(ImageFilter.GaussianBlur(16))).astype(np.float64) / 255
    rgb = np.zeros((H, W, 3))
    a = np.zeros((H, W))
    # Halo cyan.
    rgb += CYAN[None, None, :] * 1.0
    a = np.maximum(a, glow * 0.85)
    # Contour bleu nuit.
    rgb = np.where((outline > a)[..., None], np.array([0.03, 0.06, 0.14]), rgb)
    a = np.maximum(a, outline)
    rgb = rgb * (1 - m[..., None]) + np.clip(chrome, 0, 1) * m[..., None]
    a = np.maximum(a, m)
    rgb = bleed(rgb, a)
    return (np.dstack([np.clip(rgb, 0, 1), np.clip(a, 0, 1)]) * 255 + 0.5).astype(np.uint8)


def vignette(size=256):
    ys = (np.arange(size) + 0.5) / size * 2 - 1
    X, Y = np.meshgrid(ys, ys)
    r = np.sqrt(X * X + Y * Y)
    a = smoothstep(0.55, 1.35, r) * 0.85
    rgb = np.zeros((size, size, 3))
    return (np.dstack([rgb, a]) * 255 + 0.5).astype(np.uint8)


# --- Atlas --------------------------------------------------------------------------------------

def build():
    os.makedirs(OUT_DIR, exist_ok=True)
    items = []  # (nom, image, split ou None)
    img, sp = panel()
    items.append(("panel", img, (sp, sp, sp, sp)))
    img, sp = button()
    items.append(("button", img, (sp, sp, sp, sp)))
    img, sp = button(pressed=True)
    items.append(("button_down", img, (sp, sp, sp, sp)))
    img, sp = button(accent=CYAN * 0.9)
    items.append(("button_cyan", img, (sp, sp, sp, sp)))
    img, sp = pill()
    items.append(("pill", img, (sp, sp, 8, 8)))
    img, sp = bar()
    items.append(("bar_track", img, (sp, sp, 4, 4)))
    img, sp = bar(fill=True)
    items.append(("bar_fill", img, (sp, sp, 4, 4)))
    items.append(("knob", knob(), None))
    for name, fn in ICONS.items():
        items.append(("icon_" + name, icon(fn), None))
    items.append(("vignette", vignette(), None))
    items.append(("logo", logo(), None))

    W, H = 2048, 1024
    atlas = np.zeros((H, W, 4), dtype=np.uint8)
    regions = []
    # Étagères, les plus hautes d'abord.
    order = sorted(items, key=lambda it: -it[1].shape[0])
    x, y, shelf = 4, 4, 0
    for name, img, split in order:
        h, w = img.shape[:2]
        if x + w + 4 > W:
            x = 4
            y += shelf + 6
            shelf = 0
        assert y + h < H, "atlas d'interface trop petit"
        atlas[y:y + h, x:x + w] = img
        regions.append((name, x, y, w, h, split))
        x += w + 6
        shelf = max(shelf, h)
    Image.fromarray(atlas, "RGBA").save(os.path.join(OUT_DIR, "ui.png"), optimize=True)
    lines = ["", "ui.png", f"size: {W}, {H}", "format: RGBA8888", "filter: Linear, Linear", "repeat: none"]
    for name, x, y, w, h, split in regions:
        lines += [name, "  rotate: false", f"  xy: {x}, {y}", f"  size: {w}, {h}"]
        if split:
            lines.append(f"  split: {split[0]}, {split[1]}, {split[2]}, {split[3]}")
        lines += [f"  orig: {w}, {h}", "  offset: 0, 0", "  index: -1"]
    with open(os.path.join(OUT_DIR, "ui.atlas"), "w") as f:
        f.write("\n".join(lines) + "\n")
    print(f"ui.png : {len(regions)} régions", file=sys.stderr)


if __name__ == "__main__":
    build()
