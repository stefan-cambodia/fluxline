#!/usr/bin/env python3
"""
Ressources de la fiche Google Play, générées comme le reste du jeu :

  fastlane/metadata/android/<langue>/images/featureGraphic.png  (1024 × 500)
      Un réseau du jeu, plasma en cours d'écoulement, et le logo. Sans texte
      traduit : la même image sert dans toutes les langues.
  fastlane/metadata/android/<langue>/images/icon.png  (512 × 512)
      Copie de docs/icone-512.png (tools/gen_icon.py).

Les textures et le calcul du plasma sont ceux de tools/preview_board.py.
Usage : python3 tools/gen_store.py
"""
import os
import shutil

import numpy as np
from PIL import Image, ImageFilter

from preview_board import CELL, fluid, load_atlas, over, region

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
METADATA = os.path.join(ROOT, "fastlane", "metadata", "android")
LOCALES = ["fr-FR", "en-US", "ru-RU", "zh-CN", "km-KH"]

COLS, ROWS = 9, 6
# Grille vide autour du réseau : elle continue derrière le logo, à gauche.
PAD_LEFT, PAD_TOP, PAD_RIGHT, PAD_BOTTOM = 7, 1, 2, 1
# Directions : (dx, dy), y vers le bas comme sur le plateau.
DIRS = {"N": (0, -1), "E": (1, 0), "S": (0, 1), "W": (-1, 0)}
# Pièce selon les deux côtés reliés (ordre des conduits de Piece.kt : a -> b).
PIECES = {
    frozenset("WE"): ("STRAIGHT_H", "W"), frozenset("NS"): ("STRAIGHT_V", "N"),
    frozenset("NE"): ("ELBOW_NE", "N"), frozenset("ES"): ("ELBOW_ES", "E"),
    frozenset("SW"): ("ELBOW_SW", "S"), frozenset("WN"): ("ELBOW_WN", "W"),
}

# Le réseau : la source en (0, 2) regarde à l'est ; trois croisements ; l'arrivée en (8, 2).
PATH = [
    (0, 2), (1, 2), (2, 2), (3, 2), (4, 2), (4, 1), (4, 0), (5, 0), (6, 0), (6, 1), (6, 2), (6, 3),
    (6, 4), (5, 4), (4, 4), (3, 4), (2, 4), (2, 3), (2, 2), (2, 1), (1, 1), (0, 1), (0, 0), (1, 0),
    (2, 0), (3, 0), (3, 1), (3, 2), (3, 3), (3, 4), (3, 5), (4, 5), (5, 5), (6, 5), (7, 5), (8, 5),
    (8, 4), (8, 3), (8, 2),
]
FILLED_UP_TO = 20      # cases du chemin entièrement remplies (après la source)
FRONT_FILL = 0.6       # remplissage de la case suivante
BLOCKS = [(7, 1), (1, 4)]


def side(a, b):
    """Le côté de la case a par lequel on va vers la case b."""
    d = (b[0] - a[0], b[1] - a[1])
    return next(k for k, v in DIRS.items() if v == d)


def build_board():
    """Pour chaque case : nom de la pièce et conduits [(nom de texture, remplissage, inversé)]."""
    cells = {}
    for i in range(1, len(PATH) - 1):
        c = PATH[i]
        entry = side(c, PATH[i - 1])
        exit_ = side(c, PATH[i + 1])
        name, a = PIECES[frozenset(entry + exit_)]
        fill = 1.0 if i <= FILLED_UP_TO else (FRONT_FILL if i == FILLED_UP_TO + 1 else 0.0)
        cells.setdefault(c, []).append((name, entry != a, fill))
    board = {}
    for c, chans in cells.items():
        if len(chans) == 2:  # deux passages droits : une croix (conduit 0 = ouest-est, 1 = nord-sud)
            flows = []
            for name, rev, fill in chans:
                flows.append(("flow_CROSS_0" if name == "STRAIGHT_H" else "flow_CROSS_1", fill, rev))
            board[c] = ("CROSS", flows)
        else:
            name, rev, fill = chans[0]
            board[c] = (name, [("flow_" + name, fill, rev)])
    board[PATH[0]] = ("SOURCE_E", [("flow_SOURCE_E", 1.0, False)])
    board[PATH[-1]] = ("DRAIN_S", [])
    for b in BLOCKS:
        board[b] = ("BLOCK", [])
    return board


def render_board():
    img, regions = load_atlas()
    m = (208 - CELL) // 2
    cols, rows = PAD_LEFT + COLS + PAD_RIGHT, PAD_TOP + ROWS + PAD_BOTTOM
    W, H = cols * CELL + 2 * m, rows * CELL + 2 * m
    pipes = np.zeros((H, W, 4))
    plasma = np.zeros((H, W, 4))
    for y in range(rows):
        for x in range(cols):
            over(pipes, region(img, regions, "socket"), x * CELL, y * CELL)
    for (x, y), (name, flows) in build_board().items():
        px, py = (x + PAD_LEFT) * CELL, (y + PAD_TOP) * CELL
        over(pipes, region(img, regions, "body_" + name), px, py)
        for fname, fill, rev in flows:
            if fill > 0:
                f = fluid(region(img, regions, fname), fill, rev)
                over(pipes, f, px, py)
                over(plasma, f, px, py)
        if "glass_" + name in regions:
            over(pipes, region(img, regions, "glass_" + name), px, py)
    return pipes, plasma


def ui_logo():
    img = Image.open(os.path.join(ROOT, "assets", "gfx", "ui.png")).convert("RGBA")
    name, box = None, None
    for line in open(os.path.join(ROOT, "assets", "gfx", "ui.atlas")):
        line = line.rstrip("\n")
        if line and not line.startswith(" ") and ":" not in line and not line.endswith(".png"):
            name = line
        elif name == "logo" and line.strip().startswith("xy:"):
            x, y = [int(v) for v in line.split(":")[1].split(",")]
            box = [x, y]
        elif name == "logo" and line.strip().startswith("size:") and box:
            w, h = [int(v) for v in line.split(":")[1].split(",")]
            return img.crop((box[0], box[1], box[0] + w, box[1] + h))
    raise SystemExit("logo absent de ui.atlas")


def feature_graphic():
    W, H = 2048, 1000  # deux fois la taille finale, réduite à la fin
    yy = np.linspace(0, 1, H)[:, None]
    xx = np.linspace(0, 1, W)[None, :]
    bg = np.zeros((H, W, 3))
    bg[..., 0] = 0.020 + 0.020 * yy + 0.01 * xx
    bg[..., 1] = 0.035 + 0.030 * yy + 0.02 * xx
    bg[..., 2] = 0.085 + 0.050 * yy + 0.03 * xx
    canvas = Image.fromarray((bg * 255).astype(np.uint8), "RGB").convert("RGBA")

    pipes, plasma = render_board()
    to_img = lambda a: Image.fromarray((np.clip(a, 0, 1) * 255).astype(np.uint8), "RGBA")
    cell = 140  # pixels par case sur la toile ×2 (70 sur l'image finale)
    scale = cell / CELL
    size = (int(pipes.shape[1] * scale), int(pipes.shape[0] * scale))
    board = to_img(pipes).resize(size, Image.LANCZOS)
    glow = to_img(plasma).resize(size, Image.LANCZOS)
    # La source juste à droite du logo ; le réseau centré en hauteur.
    bx = int(W * 0.445) - PAD_LEFT * cell
    by = (H - ROWS * cell) // 2 - PAD_TOP * cell
    canvas.alpha_composite(board, (bx, by))

    # Halo du plasma : flou large, ajouté.
    halo = Image.new("RGBA", (W, H), (0, 0, 0, 0))
    halo.alpha_composite(glow, (bx, by))
    halo = halo.filter(ImageFilter.GaussianBlur(26))
    arr = np.asarray(canvas).astype(np.float64) / 255
    h = np.asarray(halo).astype(np.float64) / 255
    arr[..., :3] = np.clip(arr[..., :3] + h[..., :3] * h[..., 3:4] * 0.9, 0, 1)

    # Voile sombre à gauche pour le logo, et vignette.
    left = np.clip(1 - xx / 0.50, 0, 1) ** 1.2
    arr[..., :3] *= (1 - 0.70 * left)[..., None]
    r = np.sqrt(((xx - 0.5) / 0.75) ** 2 + ((yy - 0.5) / 0.75) ** 2)
    arr[..., :3] *= np.clip(1.15 - 0.55 * r, 0.55, 1)[..., None]
    canvas = Image.fromarray((arr * 255).astype(np.uint8), "RGBA")

    logo = ui_logo()
    lw = int(W * 0.42)
    lh = int(logo.height * lw / logo.width)
    logo = logo.resize((lw, lh), Image.LANCZOS)
    canvas.alpha_composite(logo, (int(W * 0.02), (H - lh) // 2))
    return canvas.convert("RGB").resize((1024, 500), Image.LANCZOS)


def main():
    fg = feature_graphic()
    icon = os.path.join(ROOT, "docs", "icone-512.png")
    for loc in LOCALES:
        d = os.path.join(METADATA, loc, "images")
        os.makedirs(d, exist_ok=True)
        fg.save(os.path.join(d, "featureGraphic.png"), optimize=True)
        shutil.copyfile(icon, os.path.join(d, "icon.png"))
    print(f"featureGraphic.png (1024×500) et icon.png écrits pour {', '.join(LOCALES)}")


if __name__ == "__main__":
    main()
