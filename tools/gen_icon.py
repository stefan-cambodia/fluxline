#!/usr/bin/env python3
"""
Icône de l'application : un coude et un croisement du jeu, traversés par le
plasma, composés à partir de l'atlas du plateau (mêmes textures que le jeu).

Sortie : android/src/main/res/mipmap-*/ic_launcher_foreground.png,
         ic_launcher_monochrome.png, et docs/icone-512.png (fiche Play Store).
"""
import os
import sys

import numpy as np
from PIL import Image, ImageFilter

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from preview_board import fluid, load_atlas, over, region  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
RES = os.path.join(ROOT, "android", "src", "main", "res")
CELL = 176
MARGIN = 16


def compose():
    img, regions = load_atlas()
    # Deux cases côte à côte sur deux lignes : coude + croix, puis coude.
    size = 2 * CELL + 2 * MARGIN
    canvas = np.zeros((size, size, 4))
    tiles = [
        ((0, 0), "ELBOW_ES", [(0, 1.0, True)]),
        ((1, 0), "ELBOW_SW", [(0, 1.0, False)]),
        ((0, 1), "ELBOW_NE", [(0, 1.0, False)]),
        ((1, 1), "ELBOW_WN", [(0, 0.55, False)]),
    ]
    for (cx, cy), name, fills in tiles:
        px, py = cx * CELL, cy * CELL
        over(canvas, region(img, regions, "body_" + name), px, py)
        for ch, fill, rev in fills:
            over(canvas, fluid(region(img, regions, "flow_" + name), fill, rev, time=0.15), px, py)
        over(canvas, region(img, regions, "glass_" + name), px, py)
    return canvas


def main():
    art = compose()
    rgba = (np.clip(art, 0, 1) * 255).astype(np.uint8)
    pipe = Image.fromarray(rgba, "RGBA")
    # Halo cyan derrière le motif.
    glow_mask = pipe.split()[3].filter(ImageFilter.GaussianBlur(18))
    glow = Image.new("RGBA", pipe.size, (60, 220, 255, 0))
    glow.putalpha(glow_mask.point(lambda v: int(v * 0.45)))
    full = Image.alpha_composite(glow, pipe)

    # Icône adaptative : 108 dp, motif dans la zone sûre de 66 dp.
    for density, px in (("mdpi", 108), ("hdpi", 162), ("xhdpi", 216), ("xxhdpi", 324), ("xxxhdpi", 432)):
        d = os.path.join(RES, f"mipmap-{density}")
        os.makedirs(d, exist_ok=True)
        fg = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        inner = int(px * 0.64)
        fg.alpha_composite(full.resize((inner, inner), Image.LANCZOS), ((px - inner) // 2, (px - inner) // 2))
        fg.save(os.path.join(d, "ic_launcher_foreground.png"), optimize=True)
        mono = Image.new("RGBA", (px, px), (0, 0, 0, 0))
        m = pipe.split()[3].resize((inner, inner), Image.LANCZOS)
        white = Image.new("RGBA", (inner, inner), (255, 255, 255, 255))
        white.putalpha(m)
        mono.alpha_composite(white, ((px - inner) // 2, (px - inner) // 2))
        mono.save(os.path.join(d, "ic_launcher_monochrome.png"), optimize=True)

    # Fiche Play Store : 512 × 512, fond inclus.
    store = Image.new("RGBA", (512, 512), (10, 18, 38, 255))
    yy = np.linspace(0, 1, 512)[:, None]
    bg = np.zeros((512, 512, 4))
    bg[..., 0] = 0.03 + 0.04 * (1 - yy)
    bg[..., 1] = 0.06 + 0.05 * (1 - yy)
    bg[..., 2] = 0.13 + 0.08 * (1 - yy)
    bg[..., 3] = 1
    store = Image.fromarray((bg * 255).astype(np.uint8), "RGBA")
    inner = 400
    store.alpha_composite(full.resize((inner, inner), Image.LANCZOS), ((512 - inner) // 2, (512 - inner) // 2))
    os.makedirs(os.path.join(ROOT, "docs"), exist_ok=True)
    store.save(os.path.join(ROOT, "docs", "icone-512.png"), optimize=True)
    print("icônes générées", file=sys.stderr)


if __name__ == "__main__":
    main()
