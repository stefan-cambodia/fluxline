#!/usr/bin/env python3
"""Polices et textes pré-mis en forme des langues non latines.

Le jeu rastérise ses polices avec FreeType (libGDX), qui dessine un glyphe par
caractère, sans moteur de mise en forme. Cela suffit pour le français,
l'anglais, le russe et le chinois, mais pas pour le khmer : ses consonnes
souscrites, ses voyelles réordonnées et ses diacritiques empilés exigent
HarfBuzz. Ce script prépare donc, une fois pour toutes :

  assets/fonts/Fluxline-<langue>-<graisse>.ttf
      Chakra Petch (latin, chiffres, ponctuation : identiques dans toutes les
      langues) + l'écriture de la langue :
        ru : cyrillique d'Exo 2 (le « д » de Tektur a la forme d'un « g ») ;
        zh : les idéogrammes réellement utilisés, tirés de Noto Sans SC ;
        km : un glyphe par syllabe khmère, déjà mise en forme par HarfBuzz,
             rangé dans la zone d'usage privé (U+E000…).
  assets/i18n/km.shaped.properties
      Les textes khmers où chaque syllabe est remplacée par son code privé.

À relancer après toute modification de assets/i18n/{ru,zh,km}.properties :
    python3 tools/gen_fonts.py
Dépendances : fontTools, uharfbuzz (pip install fonttools uharfbuzz).
Les polices sources sont téléchargées une fois (versions figées, empreintes
vérifiées) dans tools/fonts/cache/.
"""
from __future__ import annotations

import hashlib
import io
import os
import re
import sys
import urllib.request

import uharfbuzz as hb
from fontTools.pens.recordingPen import DecomposingRecordingPen
from fontTools.pens.transformPen import TransformPen
from fontTools.pens.ttGlyphPen import TTGlyphPen
from fontTools.subset import Options, Subsetter
from fontTools.ttLib import TTFont
from fontTools.varLib import instancer

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
TOOLS_FONTS = os.path.join(ROOT, "tools", "fonts")
CACHE = os.path.join(TOOLS_FONTS, "cache")
ASSET_FONTS = os.path.join(ROOT, "assets", "fonts")
I18N = os.path.join(ROOT, "assets", "i18n")

# Dépôt google/fonts figé à une révision : les mêmes octets à chaque génération.
GOOGLE_FONTS = "https://raw.githubusercontent.com/google/fonts/24ddc1f324cac5d9f5aedca126d298f56e3f1640/ofl/"
SOURCES = {
    "exo2": ("exo2/Exo2%5Bwght%5D.ttf", "205a448676a2586f9c57c25f3d5c58ca8db7e6cf5edf7506783a010c6fe2bfb5"),
    "khmer": ("notosanskhmer/NotoSansKhmer%5Bwdth,wght%5D.ttf", "f37a8431a0c5d5ed2f81a767417546aca576a81fb7eff9c924d46aecf828f2ca"),
    "sc": ("notosanssc/NotoSansSC%5Bwght%5D.ttf", "a3041811a78c361b1de50f953c805e0244951c21c5bd412f7232ef0d899af0da"),
}

WEIGHTS = {"Bold": 700, "SemiBold": 600}

# Ce que Chakra Petch fournit à toutes les langues.
LATIN = (
    list(range(0x20, 0x7F)) + list(range(0xA0, 0x180)) + list(range(0x2000, 0x2070))
    + [0x20AC, 0x2122, 0x2212]
)

# Taille relative de chaque écriture par rapport au latin (capitales de 700/1000).
SCALE = {"ru": 700 / 690, "zh": 0.92, "km": 1.08}
ZWSP = 0x200B
PUA_START = 0xE000


# --- Fichiers de textes ------------------------------------------------------------

def parse_properties(path: str) -> list[tuple[str, str]]:
    """Même format que le jeu (Strings.parse) : « clé = valeur », \\n et \\uXXXX."""
    out = []
    with open(path, encoding="utf-8") as f:
        for raw in f:
            line = raw.strip()
            if not line or line.startswith("#"):
                continue
            eq = line.find("=")
            if eq <= 0:
                raise SystemExit(f"{path} : ligne invalide : {line}")
            out.append((line[:eq].strip(), unescape(line[eq + 1:].strip())))
    return out


def unescape(s: str) -> str:
    return re.sub(r"\\(u[0-9a-fA-F]{4}|n|\\)", lambda m: {"n": "\n", "\\": "\\"}.get(m.group(1)) or chr(int(m.group(1)[1:], 16)), s)


def escape(s: str) -> str:
    """Inverse de unescape ; les blancs de début et de fin sont protégés (le lecteur les retire)."""
    s = s.replace("\\", "\\\\").replace("\n", "\\n")
    i, j = 0, len(s)
    while i < j and s[i] in " \u00a0":
        i += 1
    while j > i and s[j - 1] in " \u00a0":
        j -= 1
    enc = lambda part: "".join(f"\\u{ord(c):04X}" for c in part)
    return enc(s[:i]) + s[i:j] + enc(s[j:])


# --- Polices sources ---------------------------------------------------------------

def source(name: str) -> str:
    rel, sha = SOURCES[name]
    path = os.path.join(CACHE, os.path.basename(rel).replace("%5B", "[").replace("%5D", "]"))
    if not os.path.exists(path):
        os.makedirs(CACHE, exist_ok=True)
        print(f"téléchargement de {rel}…")
        with urllib.request.urlopen(GOOGLE_FONTS + rel, timeout=300) as r:
            data = r.read()
        with open(path + ".part", "wb") as f:
            f.write(data)
        os.replace(path + ".part", path)
    digest = hashlib.sha256(open(path, "rb").read()).hexdigest()
    if digest != sha:
        raise SystemExit(f"{path} : empreinte inattendue ({digest}), fichier corrompu ou modifié")
    return path


def instance(path: str, weight: int, unicodes: set[int] | None = None) -> TTFont:
    """Instance statique d'une police variable, éventuellement réduite à quelques caractères."""
    font = TTFont(path)
    if unicodes is not None:
        opts = Options()
        opts.layout_features = ["*"]
        opts.notdef_outline = True
        opts.hinting = False
        sub = Subsetter(opts)
        sub.populate(unicodes=unicodes)
        sub.subset(font)
    axes = {"wght": weight}
    if "fvar" in font and any(a.axisTag == "wdth" for a in font["fvar"].axes):
        axes["wdth"] = 100
    return instancer.instantiateVariableFont(font, axes)


def to_bytes(font: TTFont) -> bytes:
    buf = io.BytesIO()
    font.save(buf)
    return buf.getvalue()


# --- Construction ------------------------------------------------------------------

class Merged:
    """Chakra Petch (latin) auquel on ajoute des glyphes dessinés depuis d'autres polices."""

    def __init__(self, weight: str):
        self.font = TTFont(os.path.join(TOOLS_FONTS, f"ChakraPetch-{weight}.ttf"))
        opts = Options()
        opts.layout_features = []
        opts.drop_tables += ["GSUB", "GPOS", "GDEF", "kern"]
        opts.hinting = False
        opts.notdef_outline = True
        opts.name_IDs = ["*"]
        sub = Subsetter(opts)
        have = set(self.font.getBestCmap())
        sub.populate(unicodes=[c for c in LATIN if c in have])
        sub.subset(self.font)
        self.order = list(self.font.getGlyphOrder())
        self.cmap: dict[int, str] = {}
        self.y_max = 0
        self.y_min = 0

    def add(self, codepoint: int, name: str, draw, advance: float):
        pen = TTGlyphPen(None)
        draw(pen)
        glyph = pen.glyph()
        glyf = self.font["glyf"]
        glyf.glyphs[name] = glyph
        if glyph.numberOfContours:
            glyph.recalcBounds(glyf)
            lsb = glyph.xMin
            self.y_max = max(self.y_max, glyph.yMax)
            self.y_min = min(self.y_min, glyph.yMin)
        else:
            lsb = 0
        self.font["hmtx"].metrics[name] = (max(0, round(advance)), lsb)
        self.order.append(name)
        self.cmap[codepoint] = name

    def save(self, path: str, family: str, copyright_extra: str):
        f = self.font
        f.setGlyphOrder(self.order)
        for table in f["cmap"].tables:
            if table.isUnicode() and table.format in (4, 12):
                table.cmap.update(self.cmap)
        # Hauteurs : de quoi loger les diacritiques empilés (khmer) sans rognage.
        hhea, os2 = f["hhea"], f["OS/2"]
        asc = max(hhea.ascent, self.y_max + 30)
        desc = min(hhea.descent, self.y_min - 30)
        hhea.ascent, hhea.descent = asc, desc
        os2.sTypoAscender, os2.sTypoDescender = asc, desc
        os2.usWinAscent, os2.usWinDescent = asc, -desc
        f["post"].formatType = 3.0
        f["post"].extraNames = []
        f["post"].mapping = {}
        name = f["name"]
        copyright_base = name.getDebugName(0) or ""
        name.names = []
        style = "Regular"
        ps = family.replace(" ", "") + "-Regular"
        for nid, text in {
            0: f"{copyright_base} {copyright_extra}".strip(),
            1: family, 2: style, 3: f"{ps};fluxline", 4: f"{family}", 5: "Version 1.000",
            6: ps, 13: "This Font Software is licensed under the SIL Open Font License, Version 1.1.",
            14: "https://openfontlicense.org",
        }.items():
            name.setName(text, nid, 3, 1, 0x409)
        f.save(path)


def draw_from(glyph_set, gname: str, scale: float, dx: float = 0, dy: float = 0):
    rec = DecomposingRecordingPen(glyph_set)
    glyph_set[gname].draw(rec)

    def draw(pen):
        rec.replay(TransformPen(pen, (scale, 0, 0, scale, dx, dy)))
    return draw


def build_simple(lang: str, src: str, chars: set[int], copyright_extra: str):
    """Cyrillique ou chinois : un caractère = un glyphe, simplement recopié."""
    for weight, wght in WEIGHTS.items():
        merged = Merged(weight)
        latin = set(merged.font.getBestCmap())
        need = sorted(c for c in chars if c not in latin)
        fallback = instance(source(src), wght, set(need))
        cmap = fallback.getBestCmap()
        gs = fallback.getGlyphSet()
        missing = [c for c in need if c not in cmap]
        if missing:
            raise SystemExit(f"{lang} : caractères absents de la police : {''.join(map(chr, missing))}")
        s = SCALE[lang]
        upm = fallback["head"].unitsPerEm
        k = s * 1000 / upm
        for c in need:
            g = cmap[c]
            merged.add(c, f"u{c:04X}", draw_from(gs, g, k), fallback["hmtx"][g][0] * k)
        merged.add(ZWSP, "zwsp", lambda pen: None, 0)
        out = os.path.join(ASSET_FONTS, f"Fluxline-{lang}-{weight}.ttf")
        merged.save(out, f"Fluxline Sans {lang.upper()} {weight}", copyright_extra)
        print(f"{os.path.relpath(out, ROOT)} : {len(need)} glyphes ajoutés, {os.path.getsize(out) // 1024} Ko")


KHMER = re.compile(r"[ក-៿᧠-᧿]+")


def shape(hb_font, text: str):
    buf = hb.Buffer()
    buf.add_codepoints([ord(c) for c in text])
    buf.direction = "ltr"
    buf.script = "Khmr"
    buf.language = "km"
    hb.shape(hb_font, buf, {})
    return buf.glyph_infos, buf.glyph_positions


def build_khmer(entries: list[tuple[str, str]], src_sha: str):
    # 1. Découpe en syllabes (grappes HarfBuzz) avec la graisse grasse.
    bold = instance(source("khmer"), WEIGHTS["Bold"])
    hb_bold = hb.Font(hb.Face(hb.Blob(to_bytes(bold))))
    dotted = bold.getBestCmap().get(0x25CC)
    codes: dict[str, int] = {}

    def clusters(run: str) -> list[str]:
        infos, _ = shape(hb_bold, run)
        if dotted is not None and any(bold.getGlyphName(i.codepoint) == dotted for i in infos):
            raise SystemExit(f"khmer : suite de caractères invalide (cercle pointé) dans « {run} »")
        starts = sorted({i.cluster for i in infos})
        return [run[a:b] for a, b in zip(starts, starts[1:] + [len(run)])]

    shaped = []
    for key, value in entries:
        out = []
        pos = 0
        text = value.replace("|", "\u200b")
        for m in KHMER.finditer(text):
            out.append(text[pos:m.start()])
            for cl in clusters(m.group()):
                if cl not in codes:
                    codes[cl] = PUA_START + len(codes)
                out.append(chr(codes[cl]))
            pos = m.end()
        out.append(text[pos:])
        shaped.append((key, "".join(out)))

    if PUA_START + len(codes) > 0xF8FF:
        raise SystemExit("khmer : zone d'usage privé saturée")

    # 2. Un glyphe par syllabe, pour chaque graisse.
    for weight, wght in WEIGHTS.items():
        src = bold if weight == "Bold" else instance(source("khmer"), wght)
        hb_font = hb_bold if weight == "Bold" else hb.Font(hb.Face(hb.Blob(to_bytes(src))))
        gs = src.getGlyphSet()
        k = SCALE["km"] * 1000 / src["head"].unitsPerEm
        merged = Merged(weight)
        for cl, code in codes.items():
            infos, positions = shape(hb_font, cl)
            parts = []
            x = 0
            for info, p in zip(infos, positions):
                parts.append(draw_from(gs, src.getGlyphName(info.codepoint), k, (x + p.x_offset) * k, p.y_offset * k))
                x += p.x_advance

            def draw(pen, parts=parts):
                for d in parts:
                    d(pen)
            merged.add(code, f"km{code - PUA_START:04d}", draw, x * k)
        merged.add(ZWSP, "zwsp", lambda pen: None, 0)
        out = os.path.join(ASSET_FONTS, f"Fluxline-km-{weight}.ttf")
        merged.save(out, f"Fluxline Sans KM {weight}", "Khmer: Copyright 2022 The Noto Project Authors (https://github.com/notofonts/khmer).")
        print(f"{os.path.relpath(out, ROOT)} : {len(codes)} syllabes, {os.path.getsize(out) // 1024} Ko")

    path = os.path.join(I18N, "km.shaped.properties")
    with open(path, "w", encoding="utf-8") as f:
        f.write("# FICHIER GÉNÉRÉ par tools/gen_fonts.py depuis km.properties : ne pas modifier.\n")
        f.write("# Chaque syllabe khmère y est remplacée par un code privé de Fluxline-km-*.ttf.\n")
        f.write(f"# source-sha256 = {src_sha}\n")
        for key, value in shaped:
            f.write(f"{key} = {escape(value)}\n")
    print(f"{os.path.relpath(path, ROOT)} : {len(shaped)} textes")


def chars_of(entries) -> set[int]:
    return {ord(c) for _, v in entries for c in v}


def main():
    os.makedirs(ASSET_FONTS, exist_ok=True)
    ru = parse_properties(os.path.join(I18N, "ru.properties"))
    zh = parse_properties(os.path.join(I18N, "zh.properties"))
    km_path = os.path.join(I18N, "km.properties")
    km = parse_properties(km_path)

    cyrillic = set(range(0x400, 0x460)) | chars_of(ru)
    build_simple("ru", "exo2", cyrillic, "Cyrillic: Copyright 2013 The Exo 2 Project Authors (https://github.com/googlefonts/Exo-2.0).")
    build_simple("zh", "sc", chars_of(zh) | {ord(c) for c in "，。：；！？（）、—…·"},
                 "CJK: Copyright 2014-2021 Adobe (http://www.adobe.com/), with Reserved Font Name 'Source'.")
    build_khmer(km, hashlib.sha256(open(km_path, "rb").read()).hexdigest())


if __name__ == "__main__":
    sys.exit(main())
