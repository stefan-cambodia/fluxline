#!/usr/bin/env python3
"""
Génère les textures du plateau de FLUXLINE — entièrement procédurales.

Chaque pièce est décrite par sa géométrie (conduits, colliers, réservoirs…) en
« unités de case » (0..1). On construit un champ de hauteur suréchantillonné,
on en déduit les normales, puis on éclaire (diffus + spéculaire, lumière en haut
à gauche). Trois images par pièce :

  body_*   le corps (céramique, colliers en laiton, ombre portée douce) ;
  flow_*   la carte d'écoulement lue par le shader du fluide :
             R = position le long du conduit (0 au port d'entrée a, 1 au port b)
             G = masque de la fenêtre où le fluide est visible
             B = profil transversal (1 au centre, 0 au bord) pour le relief
  glass_*  les reflets du verre, dessinés par-dessus le fluide.

Sortie : assets/gfx/board.png + assets/gfx/board.atlas (format TextureAtlas).
Usage : python3 tools/gen_textures.py [--preview]
"""
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
OUT_DIR = os.path.join(ROOT, "assets", "gfx")

CELL = 176                  # taille d'une case dans la texture (px)
MARGIN = 16                 # marge pour l'ombre et les débordements
CANVAS = CELL + 2 * MARGIN  # 208
SS = 4                      # suréchantillonnage (anticrénelage)
N = CANVAS * SS
PIX = 1.0 / (CELL * SS)     # taille d'un sous-pixel en unités de case

# --- Géométrie des tuyaux (unités de case) ------------------------------------
R_TUBE = 0.205      # rayon extérieur du tube
R_SLOT = 0.118      # demi-largeur de la fenêtre (fluide visible)
R_COLLAR = 0.245    # rayon des colliers en laiton
COLLAR_LEN = 0.085  # longueur des colliers depuis le bord
SLOT_DEPTH = 0.035

LIGHT = np.array([-0.42, -0.62, 0.66])
LIGHT = LIGHT / np.linalg.norm(LIGHT)
HALF = LIGHT + np.array([0.0, 0.0, 1.0])
HALF = HALF / np.linalg.norm(HALF)

# Couleurs (sRGB 0..1)
CERAMIC = np.array([0.905, 0.893, 0.862])
CERAMIC_SHADE = np.array([0.62, 0.66, 0.74])
BRASS = np.array([0.80, 0.58, 0.25])
STEEL = np.array([0.36, 0.40, 0.47])
SLOT_DARK = np.array([0.035, 0.060, 0.105])

u = (np.arange(N) + 0.5) / SS
u = (u - MARGIN) / CELL
X, Y = np.meshgrid(u, u)  # X vers la droite, Y vers le bas

PORT_MID = {"N": (0.5, 0.0), "E": (1.0, 0.5), "S": (0.5, 1.0), "W": (0.0, 0.5)}
DIR_VEC = {"N": (0, -1), "E": (1, 0), "S": (0, 1), "W": (-1, 0)}
OPP = {"N": "S", "S": "N", "E": "W", "W": "E"}


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0.0, 1.0)
    return t * t * (3 - 2 * t)


# --- Conduits ---------------------------------------------------------------------

class Path:
    """Un conduit : distance signée à l'axe (d), abscisse 0..1 (t), validité."""

    def __init__(self, d, t, valid):
        self.d = d
        self.t = t
        self.valid = valid


def straight(a, b):
    """Conduit droit du port a au port b (ports opposés)."""
    ax, ay = PORT_MID[a]
    bx, by = PORT_MID[b]
    if ay == by:  # horizontal
        d = Y - 0.5
        t = (X - ax) / (bx - ax)
    else:
        d = X - 0.5
        t = (Y - ay) / (by - ay)
    valid = (t >= -0.001) & (t <= 1.001)
    return Path(d, t, valid)


def elbow(a, b):
    """Quart de cercle de rayon 0,5 centré sur le coin commun aux deux ports."""
    pa = PORT_MID[a]
    pb = PORT_MID[b]
    cx = pa[0] if pa[0] in (0.0, 1.0) else pb[0]
    cy = pa[1] if pa[1] in (0.0, 1.0) else pb[1]
    vx = X - cx
    vy = Y - cy
    rho = np.sqrt(vx * vx + vy * vy)
    theta = np.arctan2(vy, vx)
    ta = math.atan2(pa[1] - cy, pa[0] - cx)
    tb = math.atan2(pb[1] - cy, pb[0] - cx)
    span = (tb - ta + math.pi) % (2 * math.pi) - math.pi
    delta = (theta - ta + math.pi) % (2 * math.pi) - math.pi
    t = delta / span
    d = rho - 0.5
    valid = (t >= -0.001) & (t <= 1.001) & (vx * (0.5 - cx) >= 0) & (vy * (0.5 - cy) >= 0)
    return Path(d, t, valid)


def radial_from_center(port):
    """Du centre de la case vers un port (source) : t = distance au centre / 0,5."""
    dx, dy = DIR_VEC[port]
    s = (X - 0.5) * dx + (Y - 0.5) * dy        # abscisse le long de l'axe
    d = (X - 0.5) * (-dy) + (Y - 0.5) * dx      # distance signée à l'axe
    t = s / 0.5
    valid = (t >= -0.001) & (t <= 1.001)
    return Path(d, t, valid)


# --- Champ de hauteur et matériaux ----------------------------------------------------

class Canvas:
    def __init__(self):
        self.h = np.zeros((N, N))
        self.mat = np.zeros((N, N), dtype=np.int8)  # 0 vide, 1 céramique, 2 laiton, 3 acier, 4 fenêtre
        self.cover = np.zeros((N, N), dtype=bool)   # zones où l'on ne voit jamais le fluide

    def add(self, height, mask, mat):
        upd = mask & (height > self.h)
        self.h = np.where(upd, height, self.h)
        self.mat = np.where(upd, mat, self.mat)


def tube_height(p, radius):
    inside = p.valid & (np.abs(p.d) < radius)
    h = np.sqrt(np.clip(radius * radius - p.d * p.d, 0, None))
    return h, inside


def add_tube(cv, p, radius=R_TUBE):
    h, inside = tube_height(p, radius)
    cv.add(h, inside, 1)


def add_collar(cv, port):
    """Collier en laiton au port, avec une gorge et deux rivets."""
    dx, dy = DIR_VEC[port]
    mx, my = PORT_MID[port]
    s = -((X - mx) * dx + (Y - my) * dy)       # distance depuis le bord vers l'intérieur
    q = (X - mx) * (-dy) + (Y - my) * dx       # coordonnée transversale
    inside = (s >= -0.001) & (s <= COLLAR_LEN) & (np.abs(q) < R_COLLAR)
    h = np.sqrt(np.clip(R_COLLAR ** 2 - q * q, 0, None))
    # Gorge au milieu du collier, chanfrein côté tube.
    h = h - 0.012 * np.exp(-((s - COLLAR_LEN * 0.55) / 0.008) ** 2)
    h = h * (0.94 + 0.06 * smoothstep(COLLAR_LEN, COLLAR_LEN - 0.02, s))
    cv.add(h, inside, 2)
    cv.cover |= inside
    for side in (-1, 1):
        rx = mx + (-dy) * side * (R_COLLAR - 0.055) + (-dx) * COLLAR_LEN * 0.55
        ry = my + dx * side * (R_COLLAR - 0.055) + (-dy) * COLLAR_LEN * 0.55
        rr = np.sqrt((X - rx) ** 2 + (Y - ry) ** 2)
        rivet = rr < 0.02
        hr = np.sqrt(np.clip(R_COLLAR ** 2 - (R_COLLAR - 0.055) ** 2, 0, None)) + np.sqrt(np.clip(0.02 ** 2 - rr ** 2, 0, None))
        cv.add(hr, rivet, 3)


def carve_slot(cv, p, half_width=R_SLOT, depth=SLOT_DEPTH, exclude=None):
    """Creuse la fenêtre le long d'un conduit ; renvoie son masque."""
    a = np.abs(p.d)
    edge = smoothstep(half_width + 0.004, half_width - 0.010, a)
    m = p.valid & (a < half_width + 0.004) & (cv.mat == 1)
    if exclude is not None:
        m &= ~exclude
    cv.h = np.where(m, cv.h - depth * edge, cv.h)
    win = m & (a < half_width)
    cv.mat = np.where(win, 4, cv.mat)
    return win


# --- Éclairage -------------------------------------------------------------------------

def shade(cv):
    gy, gx = np.gradient(cv.h, PIX)
    nx, ny, nz = -gx, -gy, np.ones_like(gx)
    inv = 1.0 / np.sqrt(nx * nx + ny * ny + nz * nz)
    nx, ny, nz = nx * inv, ny * inv, nz * inv
    diff = np.clip(nx * LIGHT[0] + ny * LIGHT[1] + nz * LIGHT[2], 0, 1)
    spec_dot = np.clip(nx * HALF[0] + ny * HALF[1] + nz * HALF[2], 0, 1)
    rgb = np.zeros((N, N, 3))
    # Occlusion ambiante : les flancs bas du relief sont plus sombres.
    ao = 0.45 + 0.55 * smoothstep(0.0, 0.12, cv.h)
    for mat, alb, amb, kd, ks, power, spec_col in (
        (1, CERAMIC, 0.30, 0.78, 0.55, 38, np.array([1.0, 1.0, 1.0])),
        (2, BRASS, 0.26, 0.80, 1.00, 22, np.array([1.0, 0.86, 0.55])),
        (3, STEEL, 0.30, 0.70, 0.90, 30, np.array([0.9, 0.95, 1.0])),
        (4, SLOT_DARK, 0.80, 0.30, 0.25, 60, np.array([0.5, 0.7, 1.0])),
    ):
        m = cv.mat == mat
        if not m.any():
            continue
        lit = alb[None, None, :] * (amb + kd * diff)[..., None]
        # Ombre bleutée sur la céramique pour un rendu moins plat.
        if mat == 1:
            cool = (1 - diff)[..., None] * 0.25
            lit = lit * (1 - cool) + CERAMIC_SHADE[None, None, :] * cool * (amb + kd * 0.5)
        lit = lit * ao[..., None]
        lit = lit + spec_col[None, None, :] * (ks * spec_dot ** power)[..., None]
        rgb = np.where(m[..., None], lit, rgb)
    alpha = (cv.mat > 0).astype(np.float64)
    return np.clip(rgb, 0, 1), alpha


def downsample(a):
    if a.ndim == 2:
        return a.reshape(CANVAS, SS, CANVAS, SS).mean(axis=(1, 3))
    return a.reshape(CANVAS, SS, CANVAS, SS, a.shape[2]).mean(axis=(1, 3))


def blur(img, sigma):
    """Flou gaussien séparable (sigma en pixels finaux)."""
    radius = int(sigma * 3) + 1
    k = np.exp(-0.5 * (np.arange(-radius, radius + 1) / sigma) ** 2)
    k /= k.sum()
    out = img
    for axis in (0, 1):
        padded = np.pad(out, [(radius, radius) if a == axis else (0, 0) for a in range(out.ndim)], mode="constant")
        acc = np.zeros_like(out)
        for i, w in enumerate(k):
            sl = [slice(None)] * out.ndim
            sl[axis] = slice(i, i + out.shape[axis])
            acc += w * padded[tuple(sl)]
        out = acc
    return out


def shift(img, dx, dy):
    out = np.zeros_like(img)
    h, w = img.shape[:2]
    xs, xd = (slice(0, w - dx), slice(dx, w)) if dx >= 0 else (slice(-dx, w), slice(0, w + dx))
    ys, yd = (slice(0, h - dy), slice(dy, h)) if dy >= 0 else (slice(-dy, h), slice(0, h + dy))
    out[yd, xd] = img[ys, xs]
    return out


def compose_body(rgb, alpha, shadow=True, shadow_strength=0.55):
    """Corps + ombre portée douce, puis débordement des couleurs (anti-franges)."""
    pre = downsample(rgb * alpha[..., None])
    a = downsample(alpha)
    rgb = np.where(a[..., None] > 1e-4, pre / np.maximum(a[..., None], 1e-4), 0)
    if shadow:
        sh = blur(shift(a, 4, 7), 4.5) * shadow_strength
        out_a = a + sh * (1 - a)
        out_rgb = np.where(out_a[..., None] > 1e-5, (rgb * a[..., None]) / np.maximum(out_a[..., None], 1e-5), 0)
        # L'ombre est d'un bleu nuit très sombre plutôt que noire.
        shadow_rgb = np.array([0.01, 0.015, 0.04])
        out_rgb = out_rgb + shadow_rgb * (sh * (1 - a) / np.maximum(out_a, 1e-5))[..., None]
        return out_rgb, out_a
    return rgb, a


def to_rgba8(rgb, a):
    rgb = bleed(rgb, a)
    out = np.dstack([np.clip(rgb, 0, 1), np.clip(a, 0, 1)])
    return (out * 255 + 0.5).astype(np.uint8)


def bleed(rgb, a, passes=6):
    """Étend les couleurs sous les pixels transparents (filtrage sans liseré sombre)."""
    rgb = rgb.copy()
    known = a > 0.004
    for _ in range(passes):
        acc = np.zeros_like(rgb)
        cnt = np.zeros(a.shape)
        for dx, dy in ((1, 0), (-1, 0), (0, 1), (0, -1)):
            k = shift(known.astype(np.float64), dx, dy)
            acc += shift(rgb * known[..., None], dx, dy)
            cnt += k
        grow = (~known) & (cnt > 0)
        rgb = np.where(grow[..., None], acc / np.maximum(cnt, 1)[..., None], rgb)
        known = known | grow
    return rgb


# --- Carte d'écoulement et verre ---------------------------------------------------------

def flow_map(entries):
    """entries : liste de (Path, masque_fenêtre, t_override ou None)."""
    t_acc = np.zeros((N, N))
    m_acc = np.zeros((N, N))
    p_acc = np.zeros((N, N))
    best = np.full((N, N), 1e9)
    for p, win, t_override, half in entries:
        t = p.t if t_override is None else t_override
        prof = np.clip(1 - (p.d / half) ** 2, 0, 1)
        closer = np.abs(p.d) < best
        sel = closer & p.valid
        t_acc = np.where(sel, t, t_acc)
        p_acc = np.where(sel & win, prof, p_acc)
        best = np.where(sel, np.abs(p.d), best)
        m_acc = np.maximum(m_acc, win.astype(np.float64))
    t = np.clip(downsample(t_acc), 0, 1)
    m = downsample(m_acc)
    pr = downsample(p_acc)
    # La position doit rester continue hors fenêtre : on l'étend par débordement.
    out = np.dstack([t, m, pr, np.ones_like(t)])
    return (np.clip(out, 0, 1) * 255 + 0.5).astype(np.uint8)


def glass_overlay(windows, extra=None):
    """Reflets d'une lentille posée sur chaque fenêtre, ombre intérieure aux bords."""
    rgb = np.ones((N, N, 3))
    alpha = np.zeros((N, N))
    for p, win, half in windows:
        a = np.abs(p.d)
        g = np.sqrt(np.clip(half * half - p.d * p.d, 0, None)) * 0.9
        gy, gx = np.gradient(np.where(win, g, 0), PIX)
        nx, ny = -gx, -gy
        inv = 1 / np.sqrt(nx * nx + ny * ny + 1)
        sd = np.clip((nx * HALF[0] + ny * HALF[1] + HALF[2]) * inv, 0, 1)
        streak = sd ** 90 * 0.85 + sd ** 12 * 0.10
        edge_shadow = smoothstep(half * 0.55, half, a) * 0.45
        a_here = np.where(win, np.clip(streak + edge_shadow, 0, 1), 0)
        # Zones claires = reflet blanc ; zones sombres = ombre intérieure.
        col = np.where((streak > edge_shadow)[..., None], np.array([0.92, 0.97, 1.0]), np.array([0.0, 0.02, 0.06]))
        rgb = np.where((a_here > alpha)[..., None], col, rgb)
        alpha = np.maximum(alpha, a_here)
    if extra is not None:
        e_rgb, e_a = extra
        rgb = np.where((e_a > 0)[..., None], e_rgb, rgb)
        alpha = np.maximum(alpha, e_a)
    rgb_s = downsample(rgb * alpha[..., None])
    a_s = downsample(alpha)
    rgb_s = np.where(a_s[..., None] > 1e-4, rgb_s / np.maximum(a_s[..., None], 1e-4), 0)
    return to_rgba8(rgb_s, a_s)


# --- Pièces ---------------------------------------------------------------------------------

STANDARD = {
    "STRAIGHT_H": ("straight", "W", "E"),
    "STRAIGHT_V": ("straight", "N", "S"),
    "ELBOW_NE": ("elbow", "N", "E"),
    "ELBOW_ES": ("elbow", "E", "S"),
    "ELBOW_SW": ("elbow", "S", "W"),
    "ELBOW_WN": ("elbow", "W", "N"),
}


def make_path(kind, a, b):
    return straight(a, b) if kind == "straight" else elbow(a, b)


def gen_standard(name):
    kind, a, b = STANDARD[name]
    p = make_path(kind, a, b)
    cv = Canvas()
    add_tube(cv, p)
    add_collar(cv, a)
    add_collar(cv, b)
    win = carve_slot(cv, p) & ~cv.cover
    rgb, alpha = shade(cv)
    body = to_rgba8(*compose_body(rgb, alpha))
    flow = flow_map([(p, win, None, R_SLOT)])
    glass = glass_overlay([(p, win, R_SLOT)])
    return body, flow, glass, (p, win)


def gen_cross():
    ph = straight("W", "E")
    pv = straight("N", "S")
    cv = Canvas()
    add_tube(cv, pv)
    add_tube(cv, ph)
    # Moyeu central : un dôme qui couvre le croisement, percé d'une fenêtre ronde.
    rr = np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    hub_r = 0.265
    hub = rr < hub_r
    h_hub = np.sqrt(np.clip(hub_r ** 2 - rr ** 2, 0, None)) * 0.75 + 0.06
    cv.add(h_hub, hub, 1)
    for port in "NESW":
        add_collar(cv, port)
    win_h = carve_slot(cv, ph, exclude=hub) & ~cv.cover
    win_v = carve_slot(cv, pv, exclude=hub) & ~cv.cover
    # Fenêtre ronde du moyeu.
    win_r = 0.165
    ring = smoothstep(win_r + 0.004, win_r - 0.012, rr)
    in_hub_win = rr < win_r + 0.004
    cv.h = np.where(in_hub_win, cv.h - 0.05 * ring, cv.h)
    cv.mat = np.where(rr < win_r, 4, cv.mat)
    hub_win = rr < win_r
    # Anneau d'acier autour de la fenêtre.
    steel_ring = (rr >= win_r) & (rr < win_r + 0.03)
    cv.mat = np.where(steel_ring & (cv.mat == 1), 3, cv.mat)
    rgb, alpha = shade(cv)
    body = to_rgba8(*compose_body(rgb, alpha))
    # Dans le moyeu, chaque conduit n'est visible que sur sa bande.
    fh = win_h | (hub_win & (np.abs(ph.d) < R_SLOT))
    fv = win_v | (hub_win & (np.abs(pv.d) < R_SLOT))
    flow_h = flow_map([(ph, fh, None, R_SLOT)])
    flow_v = flow_map([(pv, fv, None, R_SLOT)])
    hub_path = Path(rr, np.zeros_like(rr), np.ones_like(rr, dtype=bool))
    glass = glass_overlay([(ph, win_h, R_SLOT), (pv, win_v, R_SLOT), (hub_path, hub_win, win_r)])
    return body, flow_h, flow_v, glass


def gen_source(port):
    p = radial_from_center(port)
    cv = Canvas()
    rr0 = np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    add_tube(cv, Path(p.d, p.t, p.valid & (rr0 > 0.30)))
    add_collar(cv, port)
    rr = np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    # Boîtier du réacteur : socle d'acier, couronne de céramique, dôme de verre.
    base_r = 0.40
    base = rr < base_r
    h_base = 0.07 + 0.02 * smoothstep(base_r, base_r - 0.03, rr)
    cv.add(h_base * smoothstep(base_r, base_r - 0.02, rr) + 0.001, base, 3)
    crown_r = 0.33
    crown = rr < crown_r
    h_crown = 0.10 + np.sqrt(np.clip(crown_r ** 2 - rr ** 2, 0, None)) * 0.35
    cv.add(h_crown, crown, 1)
    # Gorge des voyants (où l'on dessine le compte à rebours).
    groove = (rr > 0.235) & (rr < 0.30)
    cv.h = np.where(groove, cv.h - 0.03 * np.sin(np.clip((rr - 0.235) / 0.065, 0, 1) * math.pi), cv.h)
    cv.mat = np.where(groove & (cv.mat == 1), 3, cv.mat)
    dome_r = 0.205
    dome = rr < dome_r
    cv.mat = np.where(dome, 4, cv.mat)
    cv.h = np.where(dome, cv.h - 0.03 * smoothstep(dome_r + 0.01, dome_r - 0.02, rr), cv.h)
    # Boulons sur le socle.
    for k in range(6):
        ang = k * math.pi / 3 + math.pi / 6
        bx = 0.5 + math.cos(ang) * 0.365
        by = 0.5 + math.sin(ang) * 0.365
        br = np.sqrt((X - bx) ** 2 + (Y - by) ** 2)
        cv.add(0.09 + np.sqrt(np.clip(0.018 ** 2 - br ** 2, 0, None)), br < 0.018, 3)
    win_out = carve_slot(cv, p, exclude=crown | base & (cv.mat == 3)) & ~cv.cover & (rr > crown_r)
    rgb, alpha = shade(cv)
    body = to_rgba8(*compose_body(rgb, alpha))
    # Le fluide naît au centre du dôme puis s'engage dans la sortie.
    t = rr / 0.5
    win_flow = dome | win_out
    flow = flow_map([(Path(np.where(dome, 0, p.d), t, np.ones_like(rr, dtype=bool)), win_flow, None, R_SLOT)])
    flow = fix_profile(flow, rr, dome, dome_r)
    dome_path = Path(rr, t, np.ones_like(rr, dtype=bool))
    glass = glass_overlay([(p, win_out, R_SLOT), (dome_path, dome, dome_r)])
    return body, flow, glass


def fix_profile(flow, rr, dome, dome_r):
    """Profil sphérique dans les dômes (le relief du fluide y est radial)."""
    prof = np.clip(1 - (rr / dome_r) ** 2, 0, 1)
    pd = downsample(np.where(dome, prof, 0))
    md = downsample(dome.astype(np.float64))
    f = flow.astype(np.float64) / 255
    f[..., 2] = np.where(md > 0.5, pd / np.maximum(md, 1e-3), f[..., 2])
    return (np.clip(f, 0, 1) * 255 + 0.5).astype(np.uint8)


def gen_drain(port):
    p = radial_from_center(port)
    cv = Canvas()
    rr0 = np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    add_tube(cv, Path(p.d, p.t, p.valid & (rr0 > 0.33)))
    add_collar(cv, port)
    rr = np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2)
    # Bassin récepteur : anneaux concentriques en entonnoir.
    bowl_r = 0.37
    bowl = rr < bowl_r
    h_bowl = 0.05 + 0.12 * smoothstep(0.12, bowl_r, rr) - 0.03 * smoothstep(bowl_r - 0.03, bowl_r, rr)
    h_bowl = h_bowl + 0.006 * np.sin(rr * 95)
    cv.add(h_bowl + 0.001, bowl, 1)
    rim = (rr > bowl_r - 0.04) & bowl
    cv.mat = np.where(rim, 2, cv.mat)
    sink_r = 0.17
    sink = rr < sink_r
    cv.mat = np.where(sink, 4, cv.mat)
    win_in = carve_slot(cv, p, exclude=bowl) & ~cv.cover & (rr > bowl_r)
    rgb, alpha = shade(cv)
    body = to_rgba8(*compose_body(rgb, alpha))
    t = 1 - rr / 0.5
    flow = flow_map([(Path(np.where(sink, 0, p.d), t, np.ones_like(rr, dtype=bool)), sink | win_in, None, R_SLOT)])
    flow = fix_profile(flow, rr, sink, sink_r)
    glass = glass_overlay([(p, win_in, R_SLOT), (Path(rr, t, np.ones_like(rr, dtype=bool)), sink, sink_r)])
    return body, flow, glass


def gen_reservoir(horizontal):
    a, b = ("W", "E") if horizontal else ("N", "S")
    p = straight(a, b)
    s = p.t  # 0..1 le long de l'axe
    cv = Canvas()
    add_tube(cv, p)
    add_collar(cv, a)
    add_collar(cv, b)
    # Cuve : capsule large entre 0,16 et 0,84.
    tank_r = 0.34
    z = np.clip((np.abs(s - 0.5) - 0.26) / 0.08, 0, 1)  # 0 au centre, 1 aux extrémités arrondies
    rad = tank_r * np.sqrt(np.clip(1 - z * z, 0, 1))
    in_tank = (np.abs(s - 0.5) < 0.34) & (np.abs(p.d) < rad)
    h_tank = np.sqrt(np.clip(rad ** 2 - p.d ** 2, 0, None))
    cv.add(h_tank, in_tank, 1)
    # Cerclages d'acier.
    for c in (0.30, 0.70):
        band = in_tank & (np.abs(s - c) < 0.018)
        cv.mat = np.where(band, 3, cv.mat)
        cv.h = np.where(band, cv.h + 0.01, cv.h)
    win_r = 0.255 * np.sqrt(np.clip(1 - np.clip((np.abs(s - 0.5) - 0.20) / 0.09, 0, 1) ** 2, 0, 1))
    win_tank = in_tank & (np.abs(p.d) < win_r) & ~((np.abs(s - 0.30) < 0.018) | (np.abs(s - 0.70) < 0.018))
    cv.h = np.where(win_tank, cv.h - 0.03, cv.h)
    cv.mat = np.where(win_tank, 4, cv.mat)
    win_pipe = carve_slot(cv, p, exclude=in_tank) & ~cv.cover
    rgb, alpha = shade(cv)
    body = to_rgba8(*compose_body(rgb, alpha))
    win = win_tank | win_pipe
    half = np.where(in_tank, np.maximum(win_r, 1e-3), R_SLOT)
    flow = flow_map([(p, win, None, 1.0)])
    f = flow.astype(np.float64) / 255
    prof = np.clip(1 - (p.d / half) ** 2, 0, 1)
    f[..., 2] = downsample(np.where(win, prof, 0)) / np.maximum(downsample(win.astype(np.float64)), 1e-3) * (f[..., 1] > 0.01)
    flow = (np.clip(f, 0, 1) * 255 + 0.5).astype(np.uint8)
    glass = glass_overlay([(p, win_pipe, R_SLOT), (Path(p.d, p.t, in_tank), win_tank, 0.255)])
    return body, flow, glass


def gen_oneway_glass(flow_dir):
    """Valve en laiton au centre, gravée d'une flèche : dessinée par-dessus le fluide."""
    horizontal = flow_dir in ("E", "W")
    p = straight("W", "E") if horizontal else straight("N", "S")
    win = (np.abs(p.d) < R_SLOT) & p.valid
    cv = Canvas()
    rr = np.maximum(np.abs(X - 0.5), np.abs(Y - 0.5)) * 0.6 + np.sqrt((X - 0.5) ** 2 + (Y - 0.5) ** 2) * 0.5
    nut = rr < 0.16
    cv.add(0.08 + 0.05 * smoothstep(0.16, 0.12, rr), nut, 2)
    dx, dy = DIR_VEC[flow_dir]
    # Flèche : triangle pointant dans le sens du courant.
    fx = (X - 0.5) * dx + (Y - 0.5) * dy
    fy = (X - 0.5) * (-dy) + (Y - 0.5) * dx
    arrow = (fx < 0.075) & (fx > -0.06) & (np.abs(fy) < (0.075 - fx) * 0.9)
    cv.h = np.where(arrow & nut, cv.h - 0.02, cv.h)
    rgb, alpha = shade(cv)
    ar = arrow & nut
    rgb = np.where(ar[..., None], np.array([0.15, 0.95, 1.0]) * 0.9, rgb)
    e_rgb = rgb
    e_a = alpha
    return glass_overlay([(p, win & ~nut, R_SLOT)], extra=(e_rgb, e_a))


def gen_block():
    cv = Canvas()
    m = 0.07
    q = np.maximum(np.abs(X - 0.5), np.abs(Y - 0.5))
    plate = q < 0.5 - m
    bevel = smoothstep(0.5 - m, 0.5 - m - 0.05, q)
    cv.add(0.05 + 0.06 * bevel, plate, 3)
    rgb, alpha = shade(cv)
    # Bandes de danger ambre/noir sur le pourtour.
    band = plate & (q > 0.5 - m - 0.085)
    stripes = (np.floor((X + Y) * 9) % 2) == 0
    amber = np.array([0.92, 0.62, 0.12])
    dark = np.array([0.08, 0.08, 0.10])
    lum = rgb.mean(axis=2, keepdims=True) / max(STEEL.mean(), 1e-3)
    rgb = np.where((band & stripes)[..., None], amber * np.clip(lum, 0.5, 1.4), rgb)
    rgb = np.where((band & ~stripes)[..., None], dark * np.clip(lum, 0.6, 1.6), rgb)
    # Boulons aux coins et croix gravée au centre.
    for bx in (0.24, 0.76):
        for by in (0.24, 0.76):
            br = np.sqrt((X - bx) ** 2 + (Y - by) ** 2)
            bolt = br < 0.035
            sh = np.clip(1 - br / 0.035, 0, 1)
            rgb = np.where(bolt[..., None], np.array([0.55, 0.6, 0.68]) * (0.6 + 0.6 * sh)[..., None], rgb)
    cx = np.abs(np.abs(X - 0.5) - np.abs(Y - 0.5)) < 0.018
    cx &= q < 0.17
    rgb = np.where(cx[..., None], rgb * 0.45, rgb)
    return to_rgba8(*compose_body(rgb, alpha))


def gen_socket():
    """Case vide : un logement en creux, légèrement biseauté."""
    q = np.maximum(np.abs(X - 0.5), np.abs(Y - 0.5))
    inside = q < 0.5
    recess = smoothstep(0.5, 0.455, q)
    h = -0.03 * recess
    gy, gx = np.gradient(h, PIX)
    nx, ny = -gx, -gy
    inv = 1 / np.sqrt(nx * nx + ny * ny + 1)
    diff = np.clip((nx * LIGHT[0] + ny * LIGHT[1] + LIGHT[2]) * inv, 0, 1)
    base = np.array([0.060, 0.080, 0.135])
    rgb = base[None, None, :] * (0.55 + 0.6 * diff)[..., None]
    # Micro-grille de points et repères d'angle.
    dots = ((np.abs(((X * 8) % 1) - 0.5) < 0.06) & (np.abs(((Y * 8) % 1) - 0.5) < 0.06)) & (q < 0.44)
    rgb = np.where(dots[..., None], rgb + 0.035, rgb)
    corner = (q > 0.425) & (q < 0.44) & ((np.abs(X - 0.5) > 0.36) & (np.abs(Y - 0.5) > 0.36))
    rgb = np.where(corner[..., None], np.array([0.20, 0.55, 0.75]) * 0.7, rgb)
    alpha = inside.astype(np.float64)
    rgb_s = downsample(rgb * alpha[..., None])
    a_s = downsample(alpha)
    rgb_s = np.where(a_s[..., None] > 1e-4, rgb_s / np.maximum(a_s[..., None], 1e-4), 0)
    return to_rgba8(rgb_s, a_s)


def gen_fixed_mark():
    """Quatre boulons hexagonaux aux angles : la pièce appartient au niveau."""
    cv = Canvas()
    for bx in (0.115, 0.885):
        for by in (0.115, 0.885):
            hx = np.abs(X - bx)
            hy = np.abs(Y - by)
            hexd = np.maximum(hx * 0.866 + hy * 0.5, hy)
            bolt = hexd < 0.048
            cv.add(0.05 + 0.03 * smoothstep(0.048, 0.03, hexd), bolt, 3)
            core = np.sqrt((X - bx) ** 2 + (Y - by) ** 2) < 0.016
            cv.h = np.where(core, cv.h - 0.02, cv.h)
    rgb, alpha = shade(cv)
    return to_rgba8(*compose_body(rgb, alpha, shadow_strength=0.4))


def gen_soft(kind, size):
    """Petites images utilitaires : halo, étincelle, cercle, carré blanc."""
    s = size * SS
    uu = (np.arange(s) + 0.5) / s * 2 - 1
    gx, gy = np.meshgrid(uu, uu)
    r = np.sqrt(gx * gx + gy * gy)
    if kind == "glow":
        a = np.exp(-(r / 0.42) ** 2 * 2.2) * smoothstep(1.0, 0.8, r)
    elif kind == "spark":
        a = np.exp(-(gx / 0.9) ** 2 * 3 - (gy / 0.16) ** 2 * 3) * smoothstep(1.0, 0.9, np.abs(gx))
    elif kind == "dot":
        a = smoothstep(0.92, 0.80, r)
    elif kind == "ring":
        a = smoothstep(0.08, 0.0, np.abs(r - 0.86)) * 0.95 + np.exp(-((r - 0.86) / 0.12) ** 2) * 0.35
    elif kind == "droplet":
        a = smoothstep(0.95, 0.7, r) * (0.6 + 0.4 * smoothstep(0.6, -0.4, gx + gy))
    elif kind == "white":
        a = np.ones_like(r)
    else:
        raise ValueError(kind)
    a = np.clip(a, 0, 1)
    a = a.reshape(size, SS, size, SS).mean(axis=(1, 3))
    rgb = np.ones((size, size, 3))
    return (np.dstack([rgb, a]) * 255 + 0.5).astype(np.uint8)


# --- Assemblage de l'atlas -------------------------------------------------------------------

def build():
    os.makedirs(OUT_DIR, exist_ok=True)
    tiles = {}
    previews = {}
    for name in STANDARD:
        body, flow, glass, _ = gen_standard(name)
        tiles["body_" + name] = body
        tiles["flow_" + name] = flow
        tiles["glass_" + name] = glass
        print("  ", name, file=sys.stderr)
    body, fh, fv, glass = gen_cross()
    tiles["body_CROSS"] = body
    tiles["flow_CROSS_0"] = fh
    tiles["flow_CROSS_1"] = fv
    tiles["glass_CROSS"] = glass
    for d in "NESW":
        b, f, g = gen_source(d)
        tiles["body_SOURCE_" + d] = b
        tiles["flow_SOURCE_" + d] = f
        tiles["glass_SOURCE_" + d] = g
        b, f, g = gen_drain(d)
        tiles["body_DRAIN_" + d] = b
        tiles["flow_DRAIN_" + d] = f
        tiles["glass_DRAIN_" + d] = g
        tiles["glass_ONE_WAY_" + d] = gen_oneway_glass(d)
        print("   source/drain/oneway", d, file=sys.stderr)
    for hz, nm in ((True, "H"), (False, "V")):
        b, f, g = gen_reservoir(hz)
        tiles["body_RESERVOIR_" + nm] = b
        tiles["flow_RESERVOIR_" + nm] = f
        tiles["glass_RESERVOIR_" + nm] = g
    tiles["body_BLOCK"] = gen_block()
    tiles["socket"] = gen_socket()
    tiles["fixed_mark"] = gen_fixed_mark()

    small = {
        "glow": gen_soft("glow", 128),
        "spark": gen_soft("spark", 64),
        "dot": gen_soft("dot", 48),
        "ring": gen_soft("ring", 128),
        "droplet": gen_soft("droplet", 32),
        "white": gen_soft("white", 8),
    }

    size = 2048
    atlas = np.zeros((size, size, 4), dtype=np.uint8)
    regions = []
    slot = CANVAS + 8
    per_row = size // slot
    for i, (name, img) in enumerate(tiles.items()):
        x = (i % per_row) * slot + 4
        y = (i // per_row) * slot + 4
        atlas[y:y + CANVAS, x:x + CANVAS] = img
        regions.append((name, x, y, CANVAS, CANVAS))
    # Petites images à la suite, sur une étagère.
    y0 = ((len(tiles) + per_row - 1) // per_row) * slot + 4
    x = 4
    shelf = 0
    for name, img in small.items():
        h, w = img.shape[:2]
        if x + w + 4 > size:
            x = 4
            y0 += shelf + 8
            shelf = 0
        atlas[y0:y0 + h, x:x + w] = img
        regions.append((name, x, y0, w, h))
        x += w + 8
        shelf = max(shelf, h)
    assert y0 + shelf < size, "atlas trop petit"

    Image.fromarray(atlas, "RGBA").save(os.path.join(OUT_DIR, "board.png"), optimize=True)
    write_atlas(os.path.join(OUT_DIR, "board.atlas"), "board.png", size, regions)
    print(f"board.png : {len(regions)} régions", file=sys.stderr)
    return tiles


def write_atlas(path, image, size, regions):
    lines = ["", image, f"size: {size}, {size}", "format: RGBA8888", "filter: MipMapLinearLinear, Linear", "repeat: none"]
    for name, x, y, w, h in regions:
        lines += [
            name,
            "  rotate: false",
            f"  xy: {x}, {y}",
            f"  size: {w}, {h}",
            f"  orig: {w}, {h}",
            "  offset: 0, 0",
            "  index: -1",
        ]
    with open(path, "w") as f:
        f.write("\n".join(lines) + "\n")


if __name__ == "__main__":
    build()
