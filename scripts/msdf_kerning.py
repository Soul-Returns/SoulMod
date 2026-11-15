#!/usr/bin/env python3
"""Add pair kerning to msdf-atlas-gen JSON files.

msdf-atlas-gen reads kerning through FreeType's legacy `kern` table, and the bundled fonts keep
theirs in OpenType GPOS, so their atlases ship with no kerning at all. This shapes every pair of
the atlas's own codepoints with HarfBuzz (kern feature only, no ligatures) and writes the
non-zero adjustments into the JSON's `kerning` array, in msdf-atlas-gen's own format and em units.

Needs uharfbuzz (`python3 -m venv /tmp/hb && /tmp/hb/bin/pip install uharfbuzz`). Re-run after
regenerating an atlas:

    /tmp/hb/bin/python scripts/msdf_kerning.py <atlas.json> <font.ttf> [<atlas.json> <font.ttf> ...]
"""

import json
import sys

import uharfbuzz as hb

FEATURES = {"kern": True, "liga": False, "clig": False, "calt": False, "dlig": False, "rlig": False}


def kerning_for(json_path: str, font_path: str) -> None:
    with open(json_path, encoding="utf-8") as f:
        data = json.load(f)
    blob = hb.Blob.from_file_path(font_path)
    face = hb.Face(blob)
    font = hb.Font(face)
    upm = face.upem

    codepoints = []
    mismatched = 0
    for glyph in data["glyphs"]:
        cp = glyph["unicode"]
        gid = font.get_nominal_glyph(cp)
        if gid is None:
            continue
        codepoints.append((cp, gid))
        if abs(font.get_glyph_h_advance(gid) / upm - glyph["advance"]) > 1e-3:
            mismatched += 1
    if mismatched:
        sys.exit(f"{font_path} does not match {json_path}: {mismatched} advances differ")

    pairs = []
    for cp1, gid1 in codepoints:
        nominal = font.get_glyph_h_advance(gid1)
        for cp2, _ in codepoints:
            buf = hb.Buffer()
            buf.add_str(chr(cp1) + chr(cp2))
            buf.guess_segment_properties()
            hb.shape(font, buf, FEATURES)
            if len(buf.glyph_positions) != 2 or buf.glyph_infos[1].cluster != 1:
                continue
            first, second = buf.glyph_positions
            delta = first.x_advance - nominal + second.x_offset
            if delta:
                pairs.append({"unicode1": cp1, "unicode2": cp2, "advance": round(delta / upm, 6)})

    data["kerning"] = pairs
    with open(json_path, "w", encoding="utf-8") as f:
        json.dump(data, f, separators=(",", ":"))
    print(f"{json_path}: {len(pairs)} kerning pairs over {len(codepoints)} glyphs")


def main() -> None:
    args = sys.argv[1:]
    if not args or len(args) % 2:
        sys.exit(__doc__)
    for i in range(0, len(args), 2):
        kerning_for(args[i], args[i + 1])


if __name__ == "__main__":
    main()
