#!/usr/bin/env python3
"""
Aperçu hors moteur : compose un morceau de plateau à partir de l'atlas et
simule le shader du fluide sur le CPU. Sert à juger les textures sans lancer
le jeu. Usage : python3 tools/preview_board.py sortie.png
"""
import math
import os
import sys

import numpy as np
from PIL import Image

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
GFX = os.path.join(ROOT, "assets", "gfx")
CANVAS = 208
MARGIN = 16
CELL = 176


def load_atlas():
    img = np.asarray(Image.open(os.path.join(GFX, "board.png")).convert("RGBA")).astype(np.float64) / 255
    regions = {}
    name = None
    for line in open(os.path.join(GFX, "board.atlas")):
        line = line.rstrip("\n")
        if line and not line.startswith(" ") and ":" not in line and not line.endswith(".png"):
            name = line
        elif line.strip().startswith("xy:") and name:
            x, y = [int(v) for v in line.split(":")[1].split(",")]
            regions[name] = [x, y]
        elif line.strip().startswith("size:") and name in regions and len(regions[name]) == 2:
            w, h = [int(v) for v in line.split(":")[1].split(",")]
            regions[name] += [w, h]
    return img, regions


def region(img, regions, name):
    x, y, w, h = regions[name]
    return img[y:y + h, x:x + w]


def over(dst, src, x, y):
    h, w = src.shape[:2]
    d = dst[y:y + h, x:x + w]
    a = src[..., 3:4]
    d[..., :3] = src[..., :3] * a + d[..., :3] * (1 - a)
    d[..., 3:4] = a + d[..., 3:4] * (1 - a)


def smoothstep(e0, e1, x):
    t = np.clip((x - e0) / (e1 - e0), 0, 1)
    return t * t * (3 - 2 * t)


def fluid(flow, fill, reverse, time=0.3):
    """Le même calcul que fluid.frag (à garder en phase)."""
    t = flow[..., 0]
    if reverse:
        t = 1 - t
    mask = flow[..., 1]
    prof = flow[..., 2]
    filled = smoothstep(fill + 0.012, fill - 0.012, t)
    deep = np.array([0.02, 0.30, 0.85])
    bright = np.array([0.30, 0.96, 1.00])
    core = deep + (bright - deep) * (np.clip(prof, 0, 1) ** 0.6)[..., None]
    wave = 0.5 + 0.5 * np.sin((t * 5.0 - time * 1.6) * 2 * math.pi)
    core = core * (0.88 + 0.22 * wave[..., None])
    front = np.exp(-((t - fill) / 0.05) ** 2) * (fill < 0.999)
    core = core + np.array([0.6, 0.9, 1.0]) * (front * 0.9)[..., None]
    a = mask * filled
    return np.dstack([np.clip(core, 0, 1), a])


def main(out):
    img, regions = load_atlas()
    cols, rows = 6, 4
    W = cols * CELL + 2 * MARGIN + 60
    H = rows * CELL + 2 * MARGIN + 60
    canvas = np.zeros((H, W, 4))
    # Fond bleu nuit dégradé.
    yy = np.linspace(0, 1, H)[:, None]
    canvas[..., 0] = 0.03 + 0.02 * yy
    canvas[..., 1] = 0.05 + 0.03 * yy
    canvas[..., 2] = 0.11 + 0.05 * yy
    canvas[..., 3] = 1
    ox, oy = 30, 30
    layout = [
        ["SOURCE_E", "STRAIGHT_H", "ELBOW_SW", None, "BLOCK", "DRAIN_W"],
        ["STRAIGHT_V", None, "CROSS", "STRAIGHT_H", "ELBOW_SW", "STRAIGHT_H"],
        ["ELBOW_NE", "ELBOW_SW", "ELBOW_NE", "RESERVOIR_H", "ELBOW_WN", "ONE_WAY_E"],
        [None, "ELBOW_NE", "STRAIGHT_H", "ELBOW_WN", None, "STRAIGHT_H"],
    ]
    # (pièce, conduit) -> (remplissage, sens inverse)
    fills = {
        (0, 0, 0): (1.0, False), (1, 0, 0): (1.0, False), (2, 0, 0): (1.0, False),
        (2, 1, 1): (1.0, False), (2, 2, 0): (0.55, True),
        (3, 2, 0): (0.35, False), (5, 2, 0): (1.0, False),
    }
    for y in range(rows):
        for x in range(cols):
            over(canvas, region(img, regions, "socket"), ox + x * CELL, oy + y * CELL)
    for y in range(rows):
        for x in range(cols):
            name = layout[y][x]
            if not name:
                continue
            px, py = ox + x * CELL, oy + y * CELL
            body = "body_STRAIGHT_H" if name == "ONE_WAY_E" else "body_" + name
            over(canvas, region(img, regions, body), px, py)
            channels = 2 if name == "CROSS" else 1
            for ch in range(channels):
                key = (x, y, ch)
                if key in fills:
                    fill, rev = fills[key]
                    if name == "CROSS":
                        fname = f"flow_CROSS_{ch}"
                    elif name == "ONE_WAY_E":
                        fname = "flow_STRAIGHT_H"
                    else:
                        fname = "flow_" + name
                    over(canvas, fluid(region(img, regions, fname), fill, rev), px, py)
            glass = "glass_" + name
            if glass in regions:
                over(canvas, region(img, regions, glass), px, py)
            if name == "CROSS":
                over(canvas, region(img, regions, "fixed_mark"), px, py)
    Image.fromarray((np.clip(canvas, 0, 1) * 255).astype(np.uint8), "RGBA").save(out)
    print(out)


if __name__ == "__main__":
    main(sys.argv[1] if len(sys.argv) > 1 else "preview.png")
