"""Reader/writer for the dev bridge's world capture files (`*.soulworld.gz`, format version 1).

The layout is documented in `src/devbridge/kotlin/com/soulreturns/devbridge/world/WorldFormat.kt`;
the SoulTestKit plugin reads the same format (`/hx world load`). Pure standard library.
"""
import bisect
import gzip
import json
import struct
import zlib

MAGIC = b"SOULWRLD"
VERSION = 1
HEADER, STATE, SECTION, BLOCK_ENTITY, ENTITY, CHANGES, NOTE, END, CLOCK = 1, 2, 3, 4, 5, 6, 7, 8, 9
BLOCKS = 4096


class Capture:
    def __init__(self):
        self.header = {}
        self.states = {}          # id -> "minecraft:oak_stairs[facing=east]"
        self.sections = {}        # (cx, cz, sy) -> Section
        self.block_entities = []  # BlockEntity records in file order
        self.entities = []        # Entity records in file order
        self.changes = []         # (t, source, [(x, y, z, state_id)])
        self.notes = []
        self.clocks = []          # (t ms since capture start, server tick, raw ping count; -1 = unknown) in file order
        self.end = None
        self.truncated = False
        self.size = 0

    @property
    def started(self):
        return self.header.get("started", 0)

    def tick_at(self, t):
        """The server tick of the records queued at [t] ms: the last CLOCK at or before it (None before the first)."""
        i = bisect.bisect_right(self.clocks, (t, float("inf"), float("inf"))) - 1
        return self.clocks[i][1] if i >= 0 and self.clocks[i][1] >= 0 else None


class Section:
    __slots__ = ("t", "palette", "bits", "longs")

    def __init__(self, t, palette, bits, longs):
        self.t, self.palette, self.bits, self.longs = t, palette, bits, longs

    def blocks(self):
        return unpack(self.palette, self.bits, self.longs)


class BlockEntity:
    __slots__ = ("t", "kind", "x", "y", "z", "nbt")

    def __init__(self, t, kind, x, y, z, nbt):
        self.t, self.kind, self.x, self.y, self.z, self.nbt = t, kind, x, y, z, nbt


class Entity:
    __slots__ = ("t", "kind", "id", "uuid_hi", "uuid_lo", "type", "x", "y", "z", "yaw", "pitch", "nbt")

    def __init__(self, t, kind, eid, hi, lo, typ, x, y, z, yaw, pitch, nbt):
        self.t, self.kind, self.id, self.uuid_hi, self.uuid_lo, self.type = t, kind, eid, hi, lo, typ
        self.x, self.y, self.z, self.yaw, self.pitch, self.nbt = x, y, z, yaw, pitch, nbt


def unpack(palette, bits, longs):
    if bits == 0:
        return [palette[0]] * BLOCKS
    per = 64 // bits
    mask = (1 << bits) - 1
    out = []
    for value in longs:
        for j in range(per):
            out.append(palette[(value >> (j * bits)) & mask])
    return out[:BLOCKS]


def pack(ids):
    """ids (4096 state ids) -> (palette, bits, longs), the layout the bridge writes."""
    palette, slot, idx = [], {}, []
    for i in ids:
        s = slot.get(i)
        if s is None:
            s = slot[i] = len(palette)
            palette.append(i)
        idx.append(s)
    if len(palette) == 1:
        return palette, 0, []
    bits = max(1, (len(palette) - 1).bit_length())
    per = 64 // bits
    longs = [0] * ((BLOCKS + per - 1) // per)
    for n, s in enumerate(idx):
        longs[n // per] |= s << ((n % per) * bits)
    return palette, bits, longs


def read(path):
    """Reads a whole capture. A file whose writer died (truncated gzip) yields what was complete."""
    cap = Capture()
    data = bytearray()
    with open(path, "rb") as raw:
        d = zlib.decompressobj(31)
        try:
            while True:
                chunk = raw.read(1 << 20)
                if not chunk:
                    break
                data += d.decompress(chunk)
            data += d.flush()
        except zlib.error:
            cap.truncated = True
        cap.truncated = cap.truncated or not d.eof
        cap.size = raw.tell()
    if bytes(data[:8]) != MAGIC:
        raise ValueError("%s is not a soul world capture" % path)
    version = struct.unpack_from(">H", data, 8)[0]
    if version != VERSION:
        raise ValueError("%s is format version %d, this tool reads %d" % (path, version, VERSION))
    pos, n = 10, len(data)
    while pos + 5 <= n:
        kind, length = struct.unpack_from(">BI", data, pos)
        pos += 5
        if pos + length > n:
            cap.truncated = True
            break
        _record(cap, kind, memoryview(data)[pos:pos + length])
        pos += length
    return cap


def _record(cap, kind, p):
    if kind == HEADER:
        cap.header = json.loads(bytes(p))
    elif kind == STATE:
        sid, size = struct.unpack_from(">IH", p, 0)
        cap.states[sid] = bytes(p[6:6 + size]).decode()
    elif kind == SECTION:
        t, cx, cz, sy, n = struct.unpack_from(">IiihH", p, 0)
        off = 16
        palette = list(struct.unpack_from(">%dI" % n, p, off))
        off += 4 * n
        bits = p[off]
        off += 1
        longs = []
        if bits:
            count = struct.unpack_from(">H", p, off)[0]
            longs = list(struct.unpack_from(">%dQ" % count, p, off + 2))
        cap.sections[(cx, cz, sy)] = Section(t, palette, bits, longs)
    elif kind == BLOCK_ENTITY:
        t, k, x, y, z = struct.unpack_from(">IBiii", p, 0)
        cap.block_entities.append(BlockEntity(t, k, x, y, z, bytes(p[17:])))
    elif kind == ENTITY:
        t, k, eid, hi, lo, size = struct.unpack_from(">IBiqqH", p, 0)
        off = 4 + 1 + 4 + 8 + 8 + 2
        typ = bytes(p[off:off + size]).decode()
        off += size
        x, y, z, yaw, pitch = struct.unpack_from(">dddff", p, off)
        cap.entities.append(Entity(t, k, eid, hi, lo, typ, x, y, z, yaw, pitch, bytes(p[off + 32:])))
    elif kind == CHANGES:
        t, source, count = struct.unpack_from(">IBI", p, 0)
        entries = [struct.unpack_from(">ihiI", p, 9 + 14 * i) for i in range(count)]
        cap.changes.append((t, source, entries))
    elif kind == NOTE:
        cap.notes.append(json.loads(bytes(p)))
    elif kind == CLOCK:
        cap.clocks.append(struct.unpack_from(">Iqq", p, 0))
    elif kind == END:
        cap.end = json.loads(bytes(p))


def _rec(kind, payload):
    return struct.pack(">BI", kind, len(payload)) + payload


def write(path, cap, order=None):
    """Writes [cap] as a capture file: header, states, sections, then block entities, entities and
    changes ordered by time (an ordered change log keeps its order)."""
    out = bytearray(MAGIC + struct.pack(">H", VERSION))
    out += _rec(HEADER, json.dumps(cap.header, separators=(",", ":")).encode())
    for sid in sorted(cap.states):
        name = cap.states[sid].encode()
        out += _rec(STATE, struct.pack(">IH", sid, len(name)) + name)
    for (cx, cz, sy), s in cap.sections.items():
        body = struct.pack(">IiihH", s.t, cx, cz, sy, len(s.palette)) + struct.pack(">%dI" % len(s.palette), *s.palette) + bytes([s.bits])
        if s.bits:
            body += struct.pack(">H", len(s.longs)) + struct.pack(">%dQ" % len(s.longs), *s.longs)
        out += _rec(SECTION, body)
    events = [(b.t, 0, i, b) for i, b in enumerate(cap.block_entities)]
    events += [(e.t, 1, i, e) for i, e in enumerate(cap.entities)]
    events += [(c[0], 2, i, c) for i, c in enumerate(cap.changes)]
    for _, which, _, ev in sorted(events, key=lambda e: e[:3]):
        if which == 0:
            out += _rec(BLOCK_ENTITY, struct.pack(">IBiii", ev.t, ev.kind, ev.x, ev.y, ev.z) + ev.nbt)
        elif which == 1:
            typ = ev.type.encode()
            out += _rec(ENTITY, struct.pack(">IBiqqH", ev.t, ev.kind, ev.id, ev.uuid_hi, ev.uuid_lo, len(typ)) + typ + struct.pack(">dddff", ev.x, ev.y, ev.z, ev.yaw, ev.pitch) + ev.nbt)
        else:
            t, source, entries = ev
            out += _rec(CHANGES, struct.pack(">IBI", t, source, len(entries)) + b"".join(struct.pack(">ihiI", *e) for e in entries))
    for note in cap.notes:
        out += _rec(NOTE, json.dumps(note, separators=(",", ":")).encode())
    for t, tick, ping in cap.clocks:
        out += _rec(CLOCK, struct.pack(">Iqq", t, tick, ping))
    if cap.end is not None:
        out += _rec(END, json.dumps(cap.end, separators=(",", ":")).encode())
    with gzip.open(path, "wb", compresslevel=6) as f:
        f.write(bytes(out))


# ---------- NBT (NbtIo.write: type byte, empty name, payload) ----------

def parse_nbt(data):
    """Big-endian Java NBT -> python (compounds are dicts, lists lists, arrays lists of ints)."""
    pos = [0]

    def take(fmt):
        size = struct.calcsize(fmt)
        v = struct.unpack_from(fmt, data, pos[0])
        pos[0] += size
        return v[0]

    def string():
        n = take(">H")
        s = bytes(data[pos[0]:pos[0] + n]).decode("utf-8", "replace")
        pos[0] += n
        return s

    def payload(t):
        if t == 1:
            return take(">b")
        if t == 2:
            return take(">h")
        if t == 3:
            return take(">i")
        if t == 4:
            return take(">q")
        if t == 5:
            return take(">f")
        if t == 6:
            return take(">d")
        if t == 7:
            n = take(">i")
            v = list(struct.unpack_from(">%db" % n, data, pos[0]))
            pos[0] += n
            return v
        if t == 8:
            return string()
        if t == 9:
            et = take(">b")
            return [payload(et) for _ in range(take(">i"))]
        if t == 10:
            d = {}
            while True:
                tt = take(">b")
                if tt == 0:
                    return d
                key = string()
                d[key] = payload(tt)
        if t == 11:
            n = take(">i")
            v = list(struct.unpack_from(">%di" % n, data, pos[0]))
            pos[0] += 4 * n
            return v
        if t == 12:
            n = take(">i")
            v = list(struct.unpack_from(">%dq" % n, data, pos[0]))
            pos[0] += 8 * n
            return v
        raise ValueError("bad nbt tag %d" % t)

    t = take(">b")
    if t == 0:
        return {}
    string()
    return payload(t)


# ---------- entity noise filter ----------

RULES_PATH = __import__("pathlib").Path(__file__).with_name("entity-filter.json")


def plain_name(name):
    """A custom name (parsed NBT text component) as plain text, or None."""
    if name is None:
        return None
    if isinstance(name, str):
        return name
    if isinstance(name, list):
        return "".join(plain_name(e) or "" for e in name)
    if isinstance(name, dict):
        text = plain_name(name.get("text")) or ""
        return text + "".join(plain_name(e) or "" for e in (name.get("extra") or []))
    return str(name)


class EntityInfo:
    __slots__ = ("uid", "type", "names", "first", "move", "keep", "why", "records")

    def __init__(self, uid, typ, first):
        self.uid, self.type, self.first, self.names, self.move, self.keep, self.why, self.records = uid, typ, first, [], 0.0, True, "static", 0

    @property
    def name(self):
        return self.names[0] if self.names else None


class EntityRules:
    def __init__(self, path=None):
        import re
        with open(path or RULES_PATH, encoding="utf-8") as f:
            data = json.load(f)
        self.max_move = float(data.get("max_move", 1.0))
        self.drop = [(r["why"], re.compile(r["re"])) for r in data.get("drop_names", [])]
        self.keep = [re.compile(r["re"]) for r in data.get("keep_names", [])]

    def classify(self, cap):
        """uid -> EntityInfo for every entity of [cap]: movement over all its records, every distinct name, and the verdict."""
        infos, lo, hi = {}, {}, {}
        for e in cap.entities:
            uid = (e.uuid_hi, e.uuid_lo)
            info = infos.get(uid)
            if info is None:
                info = infos[uid] = EntityInfo(uid, e.type, e)
                lo[uid], hi[uid] = [e.x, e.y, e.z], [e.x, e.y, e.z]
            info.records += 1
            for i, v in enumerate((e.x, e.y, e.z)):
                lo[uid][i] = min(lo[uid][i], v)
                hi[uid][i] = max(hi[uid][i], v)
            try:
                name = plain_name(parse_nbt(e.nbt).get("CustomName"))
            except (ValueError, struct.error):
                name = None
            if name is not None and name not in info.names:
                info.names.append(name)
        for uid, info in infos.items():
            info.move = max(h - l for l, h in zip(lo[uid], hi[uid]))
            if not any(k.search(name) for k in self.keep for name in info.names):
                for name in info.names:
                    why = next((w for w, r in self.drop if r.search(name)), None)
                    if why:
                        info.keep, info.why = False, why
                        break
            if info.keep and info.move > self.max_move:
                info.keep, info.why = False, "moved %.1f blocks" % info.move
        return infos


SIGNATURE_KEYS = ("equipment", "Item", "ItemRotation", "Facing", "Pose", "Small", "ShowArms", "NoBasePlate", "Invisible", "Marker", "Glowing")


def entity_signature(e):
    """Type, custom name and look of an entity, without identity (UUID, ids) or position: two records with the same
    signature within a fraction of a block are the same thing seen twice (an entity respawns each time its chunk reloads)."""
    nbt = parse_nbt(e.nbt)
    return (e.type, plain_name(nbt.get("CustomName")), json.dumps({k: nbt[k] for k in SIGNATURE_KEYS if k in nbt}, sort_keys=True))
