#!/usr/bin/env python3
"""Regression test for the `listen` replay queries and events.json: python3 tools/devbridge/test_listen.py

Builds a small recording from real lines of the 2026-11-11 M4 run (docs/research/dev-bridge-first-real-run-2026-11-11.md),
as gzip and as zstd, and checks streaming, ranges, limits, named events and the `at`/`track`/`ticks` views.
"""
import gzip
import json
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path

LISTEN = Path(__file__).resolve().parent / "listen"
T0 = 1_794_397_859_573


def line(dt, kind, data, tick=None, gt=None):
    return json.dumps({"t": T0 + dt, "tick": tick, "gt": gt, "kind": kind, "data": data}, ensure_ascii=False, separators=(",", ":"))


CHAT = [
    (1000, "§a§l Starting in 4 seconds."),
    (1500, "§r§6[MVP++] SoulReturns has obtained Blood Key!"),
    (2000, "§r[NPC] Mort: Here, I found this map when I first entered the dungeon."),
    (3000, "§r The BLOOD DOOR has been opened!"),
    (4000, "§r[BOSS] Thorn: Welcome Adventurers! I am Thorn, the Spirit! And host of the Vegan Trials!"),
    (5000, "§r ☠ ArkCeleste was killed by Zombie Soldier and became a ghost."),
    (6000, "§r\n  OBSIDIAN CHEST REWARDS\n    Enchanted Book (Bank II)\n    RARE REWARD! Spirit Bone\n"),
    (7000, "§r            Master Mode The Catacombs - Floor IV"),
    (7100, "§r                       Team Score: 301 (S+)"),
    (7200, "§r                       ☠ Defeated Thorn in 02m 59s"),
    (7300, "§r                              > EXTRA STATS <"),
    (7400, "§r                               Secrets Found: 4"),
]


def build(path_gz):
    rows = [line(0, "header", {"format": "soul-devbridge-recording", "version": 1, "player": "SoulReturns", "server": "hypixel.net", "client": {}})]
    rows.append(line(1, "connection", {"op": "join", "server": "hypixel.net"}))
    rows.append(line(2, "location", {"area": "Dungeon", "sub_location": "The Catacombs", "server_id": "mini192CJ"}))
    for i in range(80):  # 8 s of 10 Hz self frames, ping 22/s against game time 20/s, stalling at 5.0-6.0 s
        t = i * 100
        tick = 100 + (t * 22 // 1000 if t < 5000 else 110 if t < 6000 else (t - 1000) * 22 // 1000)
        rows.append(line(t + 10, "self", {"name": "SoulReturns", "pos": [t / 100, 69.0, 3.0], "yaw": 0.0}, tick, 1000 + t // 50))
        if i % 2 == 0:
            rows.append(line(t + 20, "players", {"count": 2, "players": [
                {"name": "Pawding", "real": True, "pos": [t / 100 + 5, 69.0, 3.0], "yaw": 90.0, "distance": 5.0},
                {"name": "Crypt Dreadlord", "real": False, "pos": [0.0, 69.0, 9.0], "yaw": 0.0, "distance": 9.0}], "untracked_recent": []}))
        rows.append(line(t + 30, "packet", {"t": T0 + t, "dir": "in", "type": "minecraft:sound", "data": {"@": "x"}}))
    for dt, text in CHAT:
        rows.append(line(dt, "chat", {"text": text}))
    rows.append(line(2500, "tracking", {"event": "tracked", "name": "Crypt Dreadlord", "uuid": "u1", "real": False, "pos": [0, 0, 0]}))
    rows.append(line(8000, "end", {"reason": "stopped by agent", "dropped": {}, "queue_dropped": 0, "caps": {}}))
    data = ("\n".join(rows) + "\n").encode()
    with gzip.open(path_gz, "wb") as f:
        f.write(data)
    return data


def listen(*args):
    p = subprocess.run([sys.executable, str(LISTEN), *map(str, args)], capture_output=True, text=True, timeout=120)
    if p.returncode:
        raise AssertionError(p.stderr)
    return p.stdout


class ReplayTest(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.gz = Path(cls.dir.name) / "t.rec.ndjson.gz"
        data = build(cls.gz)
        cls.files = [cls.gz]
        try:
            from compression import zstd
            cls.zst = Path(cls.dir.name) / "t.rec.ndjson.zst"
            cls.zst.write_bytes(zstd.compress(data, level=3))
            cls.files.append(cls.zst)
        except ImportError:
            pass

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def test_summary_runs_and_events(self):
        for f in self.files:
            out = listen("replay", f, "summary")
            self.assertIn("run start ×1", out)
            self.assertIn("boss entry F4 Thorn ×1", out)
            self.assertIn("key obtained ×1", out)
            self.assertIn("chest rewards ×1", out)
            self.assertIn("MM Floor IV", out)
            self.assertIn("score 301 (S+) Thorn in 02m 59s", out)
            self.assertIn("deaths 1 own secrets 4", out)
            self.assertIn("npc entities tracked: 1", out)

    def test_timeline_range_grep_limit_and_flat_multiline(self):
        out = listen("replay", self.gz, "timeline", "--from", "+00:05", "--to", "+00:06.5", "--kinds", "chat")
        self.assertIn("CHEST REWARDS", out)
        self.assertNotIn("Starting in", out)
        self.assertEqual(len([l for l in out.splitlines() if l.startswith("+")]), 2, out)
        self.assertIn("|", [l for l in out.splitlines() if "CHEST" in l][0])  # newlines flattened
        out = listen("replay", self.gz, "timeline", "--grep", "blood", "--limit", "1")
        self.assertIn("more", out)
        self.assertEqual(len(listen("replay", self.gz, "timeline", "--events", "--player", "ArkCeleste").splitlines()), 1)

    def test_at_track_ticks(self):
        out = listen("replay", self.gz, "at", "+00:03")
        self.assertIn("self: SoulReturns", out)
        self.assertIn("Pawding", out)
        self.assertIn("Dreadlord", out)  # npcs are listed after the real players
        out = listen("replay", self.gz, "track", "Pawding", "--every", "1")
        self.assertEqual(len(out.strip().splitlines()), 1 + 8, out)
        out = listen("replay", self.gz, "ticks")
        self.assertIn("mini192CJ", out)
        self.assertIn("1 (", out)  # the stall
        self.assertRegex(out, r"mini192CJ\s+8\s+20\.00\s+\d+\.\d\d\s+[+-]\d+\.\d%\s+0\s+1 \(1\.")  # v1 file: its `gt` is the clock

    def test_sizes_and_markers(self):
        out = listen("replay", self.gz, "sizes")
        self.assertIn("sound", out)
        self.assertIn("busiest", out)

    def test_v1_recordings_read_as_clock_and_ping(self):
        out = listen("replay", self.gz, "at", "tick=1100")  # v1 `gt` 1100 = 5 s in
        self.assertIn("server tick 11", out)
        out = listen("replay", self.gz, "at", "ping=110")  # the stalled ping count
        self.assertIn("at +00:0", out)
        out = listen("replay", self.gz, "summary")
        self.assertIn("format v1", out)


def line2(dt, kind, data, tick=None, ping=None):
    return json.dumps({"t": T0 + dt, "tick": tick, "ping": ping, "kind": kind, "data": data}, ensure_ascii=False, separators=(",", ":"))


def build_v2(path, surplus_per_s=2.4):
    """A format-v2 fight: the server tick runs at 19.6/s (set_time), the ping count at 19.6 + surplus/s, with a 2.0 s freeze at 5.0 s."""
    rows = [line2(0, "header", {"format": "soul-devbridge-recording", "version": 2, "player": "SoulReturns", "server": "hypixel.net", "client": {}})]
    rows.append(line2(1, "connection", {"op": "join", "server": "hypixel.net"}))
    rows.append(line2(2, "location", {"area": "Dungeon", "sub_location": "The Catacombs", "server_id": "mini192CJ"}))
    for i in range(400):  # 40 s of 10 Hz self frames
        t = i * 100
        server_s = t / 1000 if t < 5000 else (t / 1000 - 2.0 if t >= 7000 else 5.0)   # frozen from 5.0 s to 7.0 s
        tick = 2000 + int(server_s * 19.6)
        ping = 5000 + int(server_s * (19.6 + surplus_per_s))
        rows.append(line2(t + 10, "self", {"name": "SoulReturns", "pos": [t / 100, 69.0, 3.0], "yaw": 0.0}, tick, ping))
    rows.append(line2(500, "title", {"type": "times", "fade_in": 0, "stay": 25, "fade_out": 5}, 2009, 5011))
    rows.append(line2(1000, "bossbar", {"op": "progress", "id": "x", "name": "§cMaxor", "progress": 0.5}, 2019, 5030))
    rows.append(line2(30_000, "chat", {"text": "§r[BOSS] Maxor: Hello"}, 2000 + int(28 * 19.6), 5000 + int(28 * 22)))
    rows.append(line2(40_000, "end", {"reason": "stopped by agent", "dropped": {}, "queue_dropped": 0, "caps": {}}))
    data = ("\n".join(rows) + "\n").encode()
    with gzip.open(path, "wb") as f:
        f.write(data)
    return data


class V2Test(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.dir = tempfile.TemporaryDirectory()
        cls.gz = Path(cls.dir.name) / "v2.rec.ndjson.gz"
        build_v2(cls.gz)

    @classmethod
    def tearDownClass(cls):
        cls.dir.cleanup()

    def test_ticks_shows_the_surplus_of_the_ping_count(self):
        out = listen("replay", self.gz, "ticks")
        row = [l for l in out.splitlines() if "mini192CJ" in l][0].split()
        tick_s, ping_s = float(row[3]), float(row[4])
        self.assertAlmostEqual(tick_s, 19.6 * 38 / 40, delta=0.4)  # 2 s of the 40 are a freeze
        self.assertGreater(ping_s, tick_s * 1.08)
        self.assertRegex(row[5], r"^\+(1[0-4]|[89])\.\d%$")
        self.assertRegex(out, r"1 \(2\.[01]s at \+00:05\.0\)")

    def test_tick_prefix_is_the_clock_and_at_tick_finds_it(self):
        out = listen("replay", self.gz, "timeline", "--kinds", "chat")
        self.assertRegex(out, r"\+00:30\.0 2548\s+CHAT")
        out = listen("replay", self.gz, "at", "tick=2548")
        self.assertIn("at +00:3", out.splitlines()[0])
        self.assertIn("server tick 25", out.splitlines()[0])

    def test_title_times_and_boss_progress_events(self):
        out = listen("replay", self.gz, "timeline", "--kinds", "title,bossbar", "--limit", "0")
        self.assertIn("times: fade in 0, stay 25, fade out 5 ticks", out)
        self.assertIn("BOSSBAR progress", out)  # asked for by name
        self.assertNotIn("BOSSBAR progress", listen("replay", self.gz, "timeline"))

    def test_summary_names_both_clocks(self):
        out = listen("replay", self.gz, "summary")
        self.assertIn("server tick 2000..", out)
        self.assertIn("game time from set_time", out)
        self.assertIn("ping count 5000..", out)

    def test_track_csv_has_tick_and_ping_columns(self):
        out = listen("replay", self.gz, "track", "self", "--csv", "--limit", "3")
        self.assertEqual(out.splitlines()[0], "t_ms,rel_s,tick,ping,x,y,z,yaw,source")


def load_listen():
    from importlib.machinery import SourceFileLoader
    from importlib.util import module_from_spec, spec_from_loader
    loader = SourceFileLoader("listen_under_test", str(LISTEN))
    mod = module_from_spec(spec_from_loader("listen_under_test", loader))
    loader.exec_module(mod)
    return mod


class MidCompressionTest(unittest.TestCase):
    """The client swaps `x.rec.ndjson.gz` for `.zst` a moment after `record stop`; replay raced that and crashed in `path.stat()`."""

    def test_replay_follows_the_recording_to_its_new_name(self):
        try:
            from compression import zstd
        except ImportError:
            self.skipTest("no zstd")
        with tempfile.TemporaryDirectory() as d:
            gz = Path(d) / "r.rec.ndjson.gz"
            data = build_v2(gz)
            gz.unlink()
            (Path(d) / "r.rec.ndjson.zst").write_bytes(zstd.compress(data, level=3))
            out = listen("replay", gz, "summary")  # the name the caller resolved a moment ago
            self.assertIn("server tick 2000..", out)

    def test_size_of_a_file_that_vanished_is_zero_not_a_crash(self):
        mod = load_listen()
        with tempfile.TemporaryDirectory() as d:
            gz = Path(d) / "gone.rec.ndjson.gz"
            self.assertEqual(mod.disk_size(gz), 0)
            self.assertEqual(mod.disk_mtime(gz), 0)
            gz.write_bytes(b"x")
            self.assertEqual(mod.disk_size(gz), 1)
            twin = Path(d) / "gone.rec.ndjson.zst"
            twin.write_bytes(b"xyz")
            gz.unlink()
            self.assertEqual(mod.disk_size(gz), 3)  # the .zst that replaced it

    def test_file_deleted_while_streaming(self):
        mod = load_listen()
        with tempfile.TemporaryDirectory() as d:
            gz = Path(d) / "s.rec.ndjson.gz"
            build_v2(gz)
            real_lines = mod.lines

            def lines_then_vanish(path):
                yield from real_lines(path)
                Path(path).unlink()  # the compressor deleted the .gz after the reader had it open

            mod.lines = lines_then_vanish
            args = type("A", (), {"from_": None, "to": None, "limit": None, "tail": False, "json": False})()
            rng = mod.Range(args, gz)
            import contextlib
            import io
            buf = io.StringIO()
            with contextlib.redirect_stdout(buf):
                mod.replay_summary(args, gz, rng)
            self.assertIn("server tick 2000..", buf.getvalue())


class UnexpectedDataTest(unittest.TestCase):
    """Servers and builds send things the rules never saw: strings where objects are, nulls, a line cut off mid-write."""

    def test_every_replay_view_survives_odd_lines(self):
        with tempfile.TemporaryDirectory() as d:
            gz = Path(d) / "odd.rec.ndjson.gz"
            rows = [line2(0, "header", {"format": "soul-devbridge-recording", "version": 2, "player": "P", "server": "mc.example.net", "client": {}})]
            rows += [
                line2(10, "connection", "joined"),
                line2(20, "location", None),
                line2(30, "location", ["not", "a", "dict"]),
                line2(40, "chat", "plain string"),
                line2(50, "chat", {"text": None}),
                line2(60, "title", {"type": "title", "text": 5}),
                line2(70, "bossbar", "gone"),
                line2(80, "tracking", {"event": "tracked", "name": None, "real": True}),
                line2(90, "mark", {}),
                line2(100, "screen_open", {"title": None}),
                line2(110, "self", "nothing here", 100, 200),
                line2(120, "self", {"name": "P", "pos": [1, 2, 3]}, 101, 201),
                line2(130, "players", {"players": "many"}, 101, 201),
                '{"t":%d,"tick":102,"ping":202,"kind":"chat","data":{"text":"cut off mid-wri' % (T0 + 140),
            ]
            with gzip.open(gz, "wb") as f:
                f.write(("\n".join(rows) + "\n").encode())
            for what in (["summary"], ["timeline", "--all"], ["at", "+00:00.12"], ["ticks"], ["track", "P"], ["sizes"]):
                p = subprocess.run([sys.executable, str(LISTEN), "replay", str(gz), *what], capture_output=True, text=True, timeout=60)
                self.assertNotIn("Traceback", p.stderr, "replay %s: %s" % (what, p.stderr))


class PacketDirectionTest(unittest.TestCase):
    """`record --preset full --dir both` once produced in-only files without saying so."""

    def summary(self, outbound):
        with tempfile.TemporaryDirectory() as d:
            gz = Path(d) / "dirs.rec.ndjson.gz"
            rows = [line2(0, "header", {"format": "soul-devbridge-recording", "version": 2, "player": "P", "server": "x", "client": {}, "filter": {"dir": "both"}})]
            for i in range(5):
                rows.append(line2(10 + i, "packet", {"t": T0 + i, "dir": "in", "type": "minecraft:sound", "seq": i + 1, "data": {}}, 100 + i, 200 + i))
            if outbound:
                rows.append(line2(30, "packet", {"t": T0 + 30, "dir": "out", "type": "minecraft:use_item_on", "seq": 9, "data": {}}, 110, 210))
            rows.append(line2(40, "end", {"reason": "stopped", "dropped": {}, "queue_dropped": 0, "caps": {}}))
            with gzip.open(gz, "wb") as f:
                f.write(("\n".join(rows) + "\n").encode())
            return subprocess.run([sys.executable, str(LISTEN), "replay", str(gz), "summary"], capture_output=True, text=True, timeout=60).stdout

    def test_a_missing_outbound_direction_is_named(self):
        self.assertIn("packets in 5, out 0", self.summary(False))
        self.assertIn("has no outbound packets", self.summary(False))

    def test_outbound_rows_are_counted(self):
        out = self.summary(True)
        self.assertIn("packets in 5, out 1", out)
        self.assertNotIn("has no outbound packets", out)



if __name__ == "__main__":
    unittest.main()
