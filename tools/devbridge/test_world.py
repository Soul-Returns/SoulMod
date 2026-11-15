#!/usr/bin/env python3
"""Tests for the world capture tools: python3 tools/devbridge/test_world.py

Builds small synthetic captures (the format of src/devbridge/.../world/WorldFormat.kt) and checks the reader,
`listen world info` (coverage map, block counts, holes, entities, block entities) and `listen world merge`
(first-seen wins, conflicts reported, state ids remapped, truncated files readable).
"""
import gzip
import json
import struct
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import worldfile  # noqa: E402

LISTEN = HERE / "listen"


def nbt_string(s):
    b = s.encode()
    return struct.pack(">H", len(b)) + b


def entries(fields):
    out = b""
    for k, v in fields.items():
        if isinstance(v, str):
            out += b"\x08" + nbt_string(k) + nbt_string(v)
        elif isinstance(v, dict):
            out += b"\x0a" + nbt_string(k) + entries(v) + b"\x00"
        elif isinstance(v, list):  # list of compounds
            out += b"\x09" + nbt_string(k) + b"\x0a" + struct.pack(">i", len(v)) + b"".join(entries(e) + b"\x00" for e in v)
        else:
            out += b"\x03" + nbt_string(k) + struct.pack(">i", v)
    return out


def compound(**fields):
    """NbtIo.write layout: tag 10, empty name, then the entries; strings, ints, nested compounds and lists of compounds."""
    return b"\x0a" + nbt_string("") + entries(fields) + b"\x00"


def make(states, sections, started=1_000_000, label="t", bounds=(0, 0, 0, 31, 15, 31)):
    """states: {id: name}; sections: {(cx, cz, sy): list of 4096 state ids}."""
    cap = worldfile.Capture()
    cap.header = {"format": "soul-world-capture", "version": 1, "label": label, "started": started, "dimension": "minecraft:overworld",
                  "bounds": {"requested": list(bounds), "stored": list(bounds)}}
    cap.states = dict(states)
    for key, ids in sections.items():
        palette, bits, longs = worldfile.pack(ids)
        cap.sections[key] = worldfile.Section(10, palette, bits, longs)
    return cap


class WorldFileTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def listen(self, *args):
        return subprocess.run([sys.executable, str(LISTEN), *args], capture_output=True, text=True, timeout=60)

    def test_pack_unpack(self):
        for n in (1, 2, 3, 5, 17, 300, 4096):
            ids = [(i * 7) % n + 5 for i in range(4096)]
            palette, bits, longs = worldfile.pack(ids)
            self.assertEqual(ids, worldfile.unpack(palette, bits, longs), n)

    def test_round_trip_with_events(self):
        cap = make({0: "minecraft:air", 1: "minecraft:stone"}, {(0, 0, 0): [1] * 4096, (1, 0, 0): [0] * 2048 + [1] * 2048})
        cap.block_entities.append(worldfile.BlockEntity(20, 0, 3, 4, 5, compound(id="minecraft:sign", x=3)))
        cap.entities.append(worldfile.Entity(30, 0, 77, 1, 2, "minecraft:armor_stand", 1.5, 2.0, 3.5, 90.0, 0.0, compound(CustomName="Stand")))
        cap.changes.append((40, 0, [(1, 2, 3, 1), (-5, -60, -9, 0)]))
        cap.end = {"reason": "test"}
        path = self.tmp / "a.soulworld.gz"
        worldfile.write(path, cap)
        back = worldfile.read(path)
        self.assertFalse(back.truncated)
        self.assertEqual(cap.states, back.states)
        self.assertEqual(cap.sections[(1, 0, 0)].blocks(), back.sections[(1, 0, 0)].blocks())
        self.assertEqual(worldfile.parse_nbt(back.block_entities[0].nbt), {"id": "minecraft:sign", "x": 3})
        self.assertEqual(back.entities[0].type, "minecraft:armor_stand")
        self.assertEqual(worldfile.parse_nbt(back.entities[0].nbt)["CustomName"], "Stand")
        self.assertEqual(back.changes[0][2], [(1, 2, 3, 1), (-5, -60, -9, 0)])
        self.assertEqual(back.end, {"reason": "test"})

    def test_clock_records_map_change_times_to_server_ticks(self):
        cap = make({0: "minecraft:air", 1: "minecraft:stone"}, {(0, 0, 0): [0] * 4096})
        cap.changes.append((40, 0, [(1, 2, 3, 1)]))
        cap.changes.append((2040, 0, [(1, 2, 3, 0)]))
        cap.clocks += [(40, 1000, 1200), (2040, 1040, 1290), (9000, -1, -1)]
        path = self.tmp / "c.soulworld.gz"
        worldfile.write(path, cap)
        back = worldfile.read(path)
        self.assertEqual(back.clocks, [(40, 1000, 1200), (2040, 1040, 1290), (9000, -1, -1)])
        self.assertEqual([back.tick_at(t) for t in (10, 40, 1999, 2040, 8999)], [None, 1000, 1000, 1040, 1040])
        self.assertIsNone(back.tick_at(9000))  # -1 = no clock then

    def test_old_captures_without_clock_records_still_read(self):
        cap = make({0: "minecraft:air"}, {(0, 0, 0): [0] * 4096})
        path = self.tmp / "o.soulworld.gz"
        worldfile.write(path, cap)
        back = worldfile.read(path)
        self.assertEqual(back.clocks, [])
        self.assertIsNone(back.tick_at(5))

    def test_truncated_file_reads_what_was_complete(self):
        cap = make({0: "minecraft:air", 1: "minecraft:stone"}, {(0, 0, 0): [1] * 4096})
        path = self.tmp / "a.soulworld.gz"
        worldfile.write(path, cap)
        raw = path.read_bytes()
        cut = self.tmp / "cut.soulworld.gz"
        cut.write_bytes(raw[: len(raw) - 12])
        back = worldfile.read(cut)
        self.assertTrue(back.truncated)
        self.assertEqual(back.header["label"], "t")

    def test_info_shows_holes_and_counts(self):
        cap = make({0: "minecraft:air", 1: "minecraft:coal_block"}, {(0, 0, 0): [1] * 4096, (1, 0, 0): [0] * 4096, (0, 1, 0): [0] * 4096})
        cap.entities.append(worldfile.Entity(30, 0, 77, 1, 2, "minecraft:armor_stand", 1.5, 2.0, 3.5, 90.0, 0.0, compound(CustomName="Crystal")))
        path = self.tmp / "a.soulworld.gz"
        worldfile.write(path, cap)
        res = self.listen("world", "info", str(path))
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("3 sections", res.stdout)
        self.assertRegex(res.stdout, r"4096\s+coal_block")
        self.assertIn("holes: 1 chunk columns never seen", res.stdout)
        self.assertIn("x 16..31, z 16..31", res.stdout)
        self.assertIn("armor_stand 1", res.stdout)
        self.assertIn("Crystal", res.stdout)
        self.assertIn("z    0  ##", res.stdout)
        self.assertIn("z   16  #.", res.stdout)

    def test_merge_first_seen_wins_and_reports_conflicts(self):
        a = make({0: "minecraft:air", 1: "minecraft:stone"}, {(0, 0, 0): [1] * 4096}, started=1_000_000, label="a")
        # B knows the states in another order and sees the same section differently plus one more column
        b = make({0: "minecraft:stone", 1: "minecraft:air", 2: "minecraft:gold_block"}, {(0, 0, 0): [0] * 4000 + [2] * 96, (1, 0, 0): [1] * 4096}, started=1_005_000, label="b")
        b.changes.append((100, 0, [(2, 2, 2, 2)]))
        pa, pb, out = self.tmp / "a.soulworld.gz", self.tmp / "b.soulworld.gz", self.tmp / "m.soulworld.gz"
        worldfile.write(pa, a)
        worldfile.write(pb, b)
        res = self.listen("world", "merge", str(pa), str(pb), "-o", str(out))
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("CONFLICTS: 1 sections", res.stdout)
        self.assertIn("96 blocks differ", res.stdout)
        merged = worldfile.read(out)
        names = merged.states
        # A's section wins: all stone
        self.assertEqual({names[i] for i in merged.sections[(0, 0, 0)].blocks()}, {"minecraft:stone"})
        # B's extra column is kept, its states remapped into the merged table
        self.assertEqual({names[i] for i in merged.sections[(1, 0, 0)].blocks()}, {"minecraft:air"})
        # B's change log survives, shifted to the merged start (B started 5 s after A) and remapped
        t, _, entries = merged.changes[0]
        self.assertEqual(t, 5100)
        self.assertEqual(names[entries[0][3]], "minecraft:gold_block")
        self.assertEqual(merged.header["merged_from"], ["a.soulworld.gz", "b.soulworld.gz"])


def stand(t, kind, uid, x, y, z, **nbt):
    return worldfile.Entity(t, kind, uid, 0, uid, nbt.pop("typ", "minecraft:armor_stand"), x, y, z, 0.0, 0.0, compound(**nbt))


def name(text, color="gray"):
    return {"text": "", "extra": [{"text": text, "color": color}]}


class EntityFilterTest(unittest.TestCase):
    def setUp(self):
        self.dir = tempfile.TemporaryDirectory()
        self.tmp = Path(self.dir.name)

    def tearDown(self):
        self.dir.cleanup()

    def capture(self, started=1_000_000, label="a"):
        cap = make({0: "minecraft:air", 1: "minecraft:stone"}, {(0, 0, 0): [1] * 4096}, started=started, label=label)
        e = cap.entities
        e.append(stand(10, 0, 1, 5.5, 64.0, 5.5, CustomName=name("Inactive Terminal")))
        e.append(stand(20, 1, 1, 5.5, 64.4, 5.5, CustomName=name("Terminal Active")))           # bobs 0.4 and renames: still a terminal
        e.append(stand(11, 0, 2, 9.5, 70.0, 9.5, CustomName=name("18,000")))                    # damage number, never moved
        e.append(stand(12, 0, 3, 1.5, 64.0, 1.5, CustomName=name("[Lv100] Zombie 4.5M❤")))     # mob tag
        e.append(stand(13, 0, 4, 2.5, 64.0, 2.5, CustomName=name("Inactive Device")))
        e.append(stand(23, 1, 4, 9.5, 64.0, 2.5, CustomName=name("Inactive Device")))          # moved 7 blocks
        e.append(stand(14, 0, 5, 3.5, 64.0, 3.5))                                              # unnamed, static: kept
        e.append(stand(15, 0, 6, 4.5, 64.0, 4.5, CustomName=name("Thought I heard something...", "gold")))  # chat-like line
        e.append(stand(16, 0, 7, 6.5, 64.0, 6.5, CustomName=name("Weird Unknown Label")))      # unknown name, static: kept
        e.append(stand(17, 0, 8, -2.0, 64.5, 4.5, typ="minecraft:item_frame", Facing=5))       # item frame
        e.append(stand(18, 0, 9, 7.5, 64.0, 7.5, CustomName=name("\ue085\ue081 Wither Guard 0")))  # glyph prefixed mob tag
        return cap

    def verdicts(self, rules=None):
        infos = worldfile.EntityRules(rules).classify(self.capture())
        return {i.first.id: (i.keep, i.why) for i in infos.values()}

    def test_default_rules(self):
        v = self.verdicts()
        self.assertEqual(v[1][0], True)   # terminal: bobbing and renamed
        self.assertEqual(v[2], (False, "damage number"))
        self.assertEqual(v[3], (False, "mob health tag"))
        self.assertEqual(v[4][0], False)
        self.assertTrue(v[4][1].startswith("moved"))
        self.assertEqual(v[5][0], True)   # unnamed, static
        self.assertEqual(v[6], (False, "chat-like line"))
        self.assertEqual(v[7][0], True)   # unknown names that stay still are kept
        self.assertEqual(v[8][0], True)   # item frame
        self.assertEqual(v[9], (False, "mob name tag glyph"))

    def test_editable_rules(self):
        rules = self.tmp / "rules.json"
        rules.write_text(json.dumps({"max_move": 10, "drop_names": [{"why": "weird", "re": "Unknown|heard"}, {"why": "any", "re": "Terminal"}],
                                     "keep_names": [{"why": "terminals stay", "re": "^Inactive Terminal"}, {"why": "lines stay", "re": "heard"}]}))
        v = self.verdicts(rules)
        self.assertEqual(v[7], (False, "weird"))
        self.assertEqual(v[6][0], True)    # keep_names beats drop_names
        self.assertEqual(v[1][0], True)
        self.assertEqual(v[4][0], True)    # 7 blocks is under max_move 10
        self.assertEqual(v[2][0], True)    # no damage-number rule any more

    def test_merge_static_filters_and_dedupes(self):
        a, b = self.capture(label="a"), self.capture(started=1_010_000, label="b")
        # B sees the terminal again with other ids, 0.3 blocks away (a chunk reload respawns it), and one new label
        b.entities.append(stand(30, 0, 21, 5.8, 64.0, 5.5, CustomName=name("Inactive Terminal")))
        b.entities.append(stand(31, 0, 22, 50.5, 64.0, 5.5, CustomName=name("Inactive Terminal")))
        pa, pb, out = self.tmp / "a.soulworld.gz", self.tmp / "b.soulworld.gz", self.tmp / "m.soulworld.gz"
        worldfile.write(pa, a)
        worldfile.write(pb, b)
        res = subprocess.run([sys.executable, str(LISTEN), "world", "merge", str(pa), str(pb), "-o", str(out), "--entities", "static"], capture_output=True, text=True, timeout=60)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("entity filter", res.stdout)
        self.assertIn("damage number", res.stdout)
        merged = worldfile.read(out)
        kept = {(e.uuid_lo, e.kind) for e in merged.entities}
        # kept from A: terminal (first-seen + its update), unnamed stand, unknown label, item frame; the rest dropped
        self.assertIn((1, 0), kept)
        self.assertIn((1, 1), kept)
        self.assertEqual({u for u, k in kept if k == 0 and u < 20}, {1, 5, 7, 8})
        # B: its copies of A's four are duplicates (same look, same place); only the terminal at x=50 is new
        self.assertEqual({u for u, k in kept if k == 0 and u >= 20}, {22})

    def test_filter_one_file_and_list(self):
        pa, out = self.tmp / "a.soulworld.gz", self.tmp / "f.soulworld.gz"
        worldfile.write(pa, self.capture())
        res = subprocess.run([sys.executable, str(LISTEN), "world", "merge", str(pa), "-o", str(out), "--entities", "static"], capture_output=True, text=True, timeout=60)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertEqual(len({(e.uuid_lo) for e in worldfile.read(out).entities}), 4)
        res = subprocess.run([sys.executable, str(LISTEN), "world", "entities", str(pa)], capture_output=True, text=True, timeout=60)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("4 static and kept", res.stdout)
        self.assertIn("'Inactive Terminal'", res.stdout)
        self.assertIn("5.5 64.0 5.5", res.stdout)
        self.assertIn("later also: 'Terminal Active'", res.stdout)
        self.assertNotIn("18,000", res.stdout)


class FixtureTest(unittest.TestCase):
    """fixtures/selftest.soulworld.gz: a real capture of `hx world selftest build` made by the bridge in the test client."""

    path = HERE / "fixtures" / "selftest.soulworld.gz"

    def test_reads_what_the_bridge_wrote(self):
        cap = worldfile.read(self.path)
        self.assertFalse(cap.truncated)
        self.assertEqual(cap.header["format"], "soul-world-capture")
        self.assertEqual(cap.header["bounds"]["stored"], [192, -64, 192, 399, -33, 239])
        self.assertEqual(len(cap.sections), 78)
        self.assertEqual({(cx, cz) for cx, cz, _ in cap.sections}, {(x, z) for x in range(12, 25) for z in range(12, 15)})
        names = set(cap.states.values())
        self.assertTrue(any(n.startswith("minecraft:oak_stairs[facing=east,half=bottom,shape=inner_left,") for n in names))
        self.assertIn("minecraft:lever[face=wall,facing=west,powered=false]", names)
        first = [b for b in cap.block_entities if b.kind == 0]
        tags = [worldfile.parse_nbt(b.nbt) for b in first]
        self.assertEqual(len(first), 18)
        self.assertEqual(sum(1 for t in tags if t.get("id") == "minecraft:skull"), 4)
        self.assertTrue(any("textures" in str(t.get("profile")) for t in tags))
        self.assertEqual(sorted(e.type for e in cap.entities if e.kind == 0), ["minecraft:armor_stand"] * 2 + ["minecraft:item_frame"] * 2)
        self.assertGreater(len(cap.changes), 0)
        self.assertEqual(cap.end["reason"], "stopped by agent")

    def test_info_on_the_fixture(self):
        res = subprocess.run([sys.executable, str(LISTEN), "world", "info", str(self.path)], capture_output=True, text=True, timeout=60)
        self.assertEqual(res.returncode, 0, res.stderr)
        self.assertIn("78 sections", res.stdout)
        self.assertIn("no holes", res.stdout)
        self.assertIn("player heads", res.stdout)
        self.assertIn("Selftest Stand A", res.stdout)


if __name__ == "__main__":
    unittest.main()
