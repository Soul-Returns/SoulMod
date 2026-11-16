#!/usr/bin/env python3
"""Builds plugin/src/main/resources/forge-data.json (the data of `hx forge`) from our own captures of
Hypixel's Forge menus (docs/research/raw/forge-2026-11-16/, gitignored, local only).

    python3 tools/testserver/gen-forge-data.py [RAW_DIR]

Inputs: forge-recipes.json (the 123 normalised recipes), neu-compare.json (name -> id hints for ingredients
the captures only show by name) and the two dev bridge NDJSON captures (material, name, lore, head texture
of every item, the category icons, the Quick Forge perk). The JSON is committed with the plugin; this script
is only needed when the captures change. Every string keeps the bridge's rendering minus the leading "§r".
"""
import json
import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
RAW = Path(sys.argv[1]) if len(sys.argv) > 1 else REPO / "docs/research/raw/forge-2026-11-16"
OUT = Path(__file__).resolve().parent / "plugin/src/main/resources/forge-data.json"
CAPTURES = ["2026-11-16_17-46-51.ndjson", "2026-11-16_18-18-18.ndjson"]

CATEGORY = re.compile(r"^(?:\((\d+)/(\d+)\) )?(Refining|Forging|Tools|Gear|Reforge Stones|Drill Parts|Perfect Gemstones|Pets|Other)$")
COLOR = re.compile(r"§.")
AMOUNT = re.compile(r"^(?P<name>.*?)(?: §r§8x(?P<n>[\d,]+))?$")
COINS = re.compile(r"^§r§6(?P<n>[\d,]+) Coins$")
PET_TIERS = ["COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC"]
RARITY = {"f": "COMMON", "a": "UNCOMMON", "9": "RARE", "5": "EPIC", "6": "LEGENDARY", "d": "MYTHIC"}
GRID = [r * 9 + c for r in (1, 2, 3, 4) for c in range(1, 8)]
ITEMS_REQUIRED = "§r§eItems Required"


def strip(s):
    return COLOR.sub("", s or "")


def lead(s):
    """The server sends the bridge's rendering without its first §r."""
    return s[2:] if s.startswith("§r") else s


def key_of(it):
    if it.get("sb_id") == "PET":
        info = json.loads(re.search(r"petInfo:'(\{.*?\})'", it["custom_data"])[1])
        return f"{info['type']};{PET_TIERS.index(info['tier'])}", info
    return it.get("sb_id"), None


def base_lore(lore):
    """Lore without the recipe block Hypixel appends on a recipe page."""
    if ITEMS_REQUIRED in lore:
        i = lore.index(ITEMS_REQUIRED)
        return lore[: i - 1] if i and lore[i - 1] == "" else lore[:i]
    return lore


def item_entry(it, lore=None):
    comp = it.get("components") or {}
    key, info = key_of(it)
    e = {
        "sb": "PET" if info else key,
        "material": it["id"].removeprefix("minecraft:"),
        "name": lead(it["name"]),
        "lore": lore if lore is not None else base_lore(it["lore"]),
    }
    if e["sb"] is None:
        del e["sb"]
    if comp.get("texture"):
        e["texture"] = comp["texture"]
    if comp.get("glint_override") or comp.get("glint") is True:
        e["glint"] = True
    if comp.get("tooltip_style"):
        e["tooltip"] = comp["tooltip_style"].split(":")[-1]
    if info:
        e["pet"] = {"type": info["type"], "tier": info["tier"]}
    return e


def fix_lore(entry):
    """Every lore line loses the bridge's leading §r, like the name."""
    entry["lore"] = [lead(l) for l in entry["lore"]]
    return entry


screens = []
for name in CAPTURES:
    for line in (RAW / name).open():
        e = json.loads(line)
        if e["kind"] == "screen_contents":
            screens.append(e["data"])

recipes_json = json.load(open(RAW / "forge-recipes.json"))
hints = json.load(open(RAW / "neu-compare.json"))["name_id_hints"]

# --- items seen anywhere: by key, preferring a Confirm Process / recipe page copy over the rest ---
page_items = {}      # (category, page, slot) -> item with a recipe block
confirm_items = {}   # key -> item as shown in Confirm Process (trimmed lore)
seen_items = {}      # key -> any copy
for d in screens:
    title = strip(d.get("title"))
    m = CATEGORY.match(title)
    for s in d["slots"]:
        it = s["item"]
        if not it.get("sb_id"):
            continue
        key, _ = key_of(it)
        if title == "Confirm Process" and s["slot"] < 54 and s["slot"] not in (13, 16):
            confirm_items.setdefault(key, it)
        if m and 10 <= s["slot"] < 44 and ITEMS_REQUIRED in it["lore"]:
            page_items.setdefault((m[3], int(m[1] or 1), s["slot"]), it)
        seen_items.setdefault(key, it)

items = {}
assumed = []


def rarity_footer(name):
    color = re.match(r"^(?:§r)?§(.)", name)
    return f"§{color[1]}§l{RARITY.get(color[1], 'COMMON')}" if color else "§f§lCOMMON"


def ensure_item(key, name_line):
    """Entry for an ingredient: the real item when the captures show it, else a paper in its rarity color."""
    if key in items:
        return
    src = confirm_items.get(key) or seen_items.get(key)
    if src:
        lore = confirm_items[key]["lore"] if key in confirm_items else base_lore(src["lore"])
        items[key] = fix_lore(item_entry(src, lore))
    else:
        items[key] = {"sb": key, "material": "paper", "name": name_line, "lore": [rarity_footer(name_line)], "assumed": True}
        assumed.append(key)


recipes = []
for r in recipes_json["recipes"]:
    out = r["output"]
    page = int(r["page"])
    src = page_items.get((r["category"], page, r["slot"]))
    if not src:
        raise SystemExit(f"no recipe page item for {out['id']} ({r['category']} {page} slot {r['slot']})")
    key, info = key_of(src)
    assert key == out["id"], (key, out["id"])
    items[key] = fix_lore(item_entry(src))
    lore = src["lore"]
    i = lore.index(ITEMS_REQUIRED)
    j = lore.index("", i)
    ing = []
    for line in lore[i + 1 : j]:
        c = COINS.match(line)
        if c:
            ing.append({"coins": int(c["n"].replace(",", ""))})
            continue
        am = AMOUNT.match(line)
        name_line = lead(am["name"])
        amount = int((am["n"] or "1").replace(",", ""))
        raw = next((x for x in r["ingredients"] if x["raw"] == line), None)
        if raw is None:
            raise SystemExit(f"{out['id']}: ingredient line {line!r} not in forge-recipes.json")
        ing_key = raw["id"]
        if not ing_key:
            h = hints.get(raw["name"])
            ing_key = h[0] if h and len(h) == 1 else re.sub(r"[^A-Z0-9]+", "_", raw["name"].upper()).strip("_")
        ensure_item(ing_key, name_line)
        ing.append({"k": ing_key, "n": name_line, "a": amount})
    base = r.get("duration_base_seconds") or r.get("duration_base_seconds_estimate")
    assert base, out["id"]
    recipes.append({"id": key, "cat": r["category"], "page": page, "slot": r["slot"], "ing": ing, "base": base})

# What Confirm Process shows for an input: description + rarity, shorter than the recipe page's lore.
for key, it in confirm_items.items():
    if key in items:
        trimmed = [lead(l) for l in it["lore"]]
        if trimmed != items[key]["lore"]:
            items[key]["confirm"] = trimmed

# --- category icons, search, coin head ---
categories = []
search = None
forge_ui = {}
for d in screens:
    if strip(d.get("title")) == "Select Process (Slot #1)":
        for s in d["slots"]:
            it = s["item"]
            if s["slot"] >= 54 or not it.get("name"):
                continue
            plain = strip(it["name"])
            if plain == "Search":
                search = fix_lore(item_entry(it, it["lore"]))
            elif plain not in ("Go Back", "Close"):
                e = fix_lore(item_entry(it, it["lore"]))
                e["slot"] = s["slot"]
                categories.append(e)
        break
assert len(categories) == 9 and search, (len(categories), search)

coin = None
for d in screens:
    if strip(d.get("title")) == "Confirm Process":
        for s in d["slots"]:
            if s["slot"] == 19 and re.match(r"^§r§6[\d,]+ Coins$", s["item"].get("name") or ""):
                coin = s["item"]["components"]["texture"]
                break
    if coin:
        break
assert coin

# --- Quick Forge perk shapes: the Mithril Powder cost of each level ---
costs = {}
for d in screens:
    if strip(d.get("title")) != "Heart of the Mountain":
        continue
    for s in d["slots"]:
        it = s["item"]
        if strip(it.get("name")) == "Quick Forge" and it["id"] == "minecraft:emerald" and "§r§a=====[ §r§a§lUPGRADE §r§a]=====" in it["lore"]:
            lore = it["lore"]
            if not lore[0].startswith("§r§7Level "):
                continue
            nxt = lore[lore.index("§r§a=====[ §r§a§lUPGRADE §r§a]=====") + 1]
            lvl = int(re.search(r"Level (\d+)", nxt)[1])
            cost = next(l for l in lore if l.endswith("Mithril Powder"))
            costs[lvl] = strip(cost).removesuffix(" Mithril Powder")
assert sorted(costs) == list(range(2, 21)), sorted(costs)

locked = {x["id"]: x["requires"] for x in recipes_json["locked_by_profile"]["Kiwi"]}
assert all(i in {r["id"] for r in recipes} for i in locked), "locked id not a recipe"

data = {
    "categories": categories,
    "search": search,
    "coinTexture": coin,
    "items": items,
    "recipes": recipes,
    "locked": locked,
    "quickForgeCosts": {str(k): v for k, v in sorted(costs.items())},
}
OUT.write_text(json.dumps(data, separators=(",", ":"), sort_keys=False) + "\n", encoding="ascii")
print(f"{OUT}: {len(recipes)} recipes, {len(items)} items ({len(assumed)} assumed: {', '.join(assumed)}), "
      f"{len(locked)} locked, {OUT.stat().st_size} bytes")
