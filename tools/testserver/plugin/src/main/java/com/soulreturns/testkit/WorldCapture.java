package com.soulreturns.testkit;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.zip.GZIPInputStream;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;

/**
 * A world capture file written by the dev bridge (format version 1, documented in
 * src/devbridge/.../world/WorldFormat.kt; the Python twin is tools/devbridge/worldfile.py):
 * sections as first seen plus the ordered change log. {@link #read} tolerates a truncated file
 * (the client died mid-capture) and returns what was complete; {@link #resolve} turns it into
 * what to build for "entry" (as first seen) or "latest" (all later changes applied).
 */
final class WorldCapture {
    static final String MAGIC = "SOULWRLD";
    static final int VERSION = 1;

    JsonObject header = new JsonObject();
    final Map<Integer, String> states = new HashMap<>();
    final Map<Long, Section> sections = new HashMap<>();
    final List<BlockEntityRec> blockEntities = new ArrayList<>();
    final List<EntityRec> entities = new ArrayList<>();
    final List<Changes> changes = new ArrayList<>();
    JsonObject end;
    boolean truncated;
    long fileSize;

    static final class Section {
        int cx, cz, sy;
        int[] palette;
        int bits;
        long[] longs;
        /** Decoded blocks once changes were applied (or on first use); null = still packed. */
        int[] ids;

        int[] blocks() {
            if (ids != null) return ids;
            int[] out = new int[4096];
            if (bits == 0) {
                java.util.Arrays.fill(out, palette[0]);
            } else {
                int per = 64 / bits;
                long mask = (1L << bits) - 1;
                for (int i = 0; i < 4096; i++) out[i] = palette[(int) ((longs[i / per] >>> ((i % per) * bits)) & mask)];
            }
            return ids = out;
        }

        boolean uniform() {
            return ids == null && bits == 0;
        }
    }

    record BlockEntityRec(int t, int kind, int x, int y, int z, byte[] nbt) {}

    record EntityRec(int t, int kind, int id, long uuidHi, long uuidLo, String type, double x, double y, double z, float yaw, float pitch, byte[] nbt) {}

    record Changes(int t, int source, int[] xs, int[] ys, int[] zs, int[] ids) {}

    static long key(int cx, int cz, int sy) {
        return ((long) (cx & 0x3FFFFF) << 42) | ((long) (cz & 0x3FFFFF) << 20) | (sy & 0xFFFFF);
    }

    static WorldCapture read(Path file) throws IOException {
        WorldCapture cap = new WorldCapture();
        cap.fileSize = Files.size(file);
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new GZIPInputStream(Files.newInputStream(file), 1 << 16), 1 << 16))) {
            byte[] magic = in.readNBytes(MAGIC.length());
            if (!new String(magic, StandardCharsets.US_ASCII).equals(MAGIC)) throw new IOException(file.getFileName() + " is not a soul world capture");
            int version = in.readUnsignedShort();
            if (version != VERSION) throw new IOException(file.getFileName() + " is format version " + version + ", this plugin reads " + VERSION);
            while (true) {
                int type;
                try {
                    type = in.readUnsignedByte();
                } catch (EOFException e) {
                    break;
                }
                byte[] payload = new byte[in.readInt()];
                in.readFully(payload);
                cap.record(type, ByteBuffer.wrap(payload));
            }
        } catch (EOFException | java.util.zip.ZipException e) {
            cap.truncated = true;
        }
        return cap;
    }

    private void record(int type, ByteBuffer p) {
        switch (type) {
            case 1 -> header = JsonParser.parseString(new String(p.array(), StandardCharsets.UTF_8)).getAsJsonObject();
            case 2 -> {
                int id = p.getInt();
                byte[] name = new byte[p.getShort() & 0xFFFF];
                p.get(name);
                states.put(id, new String(name, StandardCharsets.UTF_8));
            }
            case 3 -> {
                Section s = new Section();
                p.getInt();
                s.cx = p.getInt();
                s.cz = p.getInt();
                s.sy = p.getShort();
                s.palette = new int[p.getShort() & 0xFFFF];
                for (int i = 0; i < s.palette.length; i++) s.palette[i] = p.getInt();
                s.bits = p.get() & 0xFF;
                s.longs = new long[s.bits > 0 ? p.getShort() & 0xFFFF : 0];
                for (int i = 0; i < s.longs.length; i++) s.longs[i] = p.getLong();
                sections.put(key(s.cx, s.cz, s.sy), s);
            }
            case 4 -> {
                int t = p.getInt();
                int kind = p.get();
                int x = p.getInt(), y = p.getInt(), z = p.getInt();
                blockEntities.add(new BlockEntityRec(t, kind, x, y, z, rest(p)));
            }
            case 5 -> {
                int t = p.getInt();
                int kind = p.get();
                int id = p.getInt();
                long hi = p.getLong(), lo = p.getLong();
                byte[] name = new byte[p.getShort() & 0xFFFF];
                p.get(name);
                double x = p.getDouble(), y = p.getDouble(), z = p.getDouble();
                float yaw = p.getFloat(), pitch = p.getFloat();
                entities.add(new EntityRec(t, kind, id, hi, lo, new String(name, StandardCharsets.UTF_8), x, y, z, yaw, pitch, rest(p)));
            }
            case 6 -> {
                int t = p.getInt();
                int source = p.get();
                int n = p.getInt();
                int[] xs = new int[n], ys = new int[n], zs = new int[n], ids = new int[n];
                for (int i = 0; i < n; i++) {
                    xs[i] = p.getInt();
                    ys[i] = p.getShort();
                    zs[i] = p.getInt();
                    ids[i] = p.getInt();
                }
                changes.add(new Changes(t, source, xs, ys, zs, ids));
            }
            case 8 -> end = JsonParser.parseReader(new InputStreamReader(new ByteArrayInputStream(p.array()), StandardCharsets.UTF_8)).getAsJsonObject();
            default -> {
                // NOTE and unknown records are skipped
            }
        }
    }

    private static byte[] rest(ByteBuffer p) {
        byte[] out = new byte[p.remaining()];
        p.get(out);
        return out;
    }

    static CompoundTag nbt(byte[] bytes) {
        try {
            return NbtIo.read(new DataInputStream(new ByteArrayInputStream(bytes)), NbtAccounter.unlimitedHeap());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What to build: final blocks per section, final block entities and entities. */
    record Resolved(Map<Long, Section> sections, List<BlockEntityRec> blockEntities, List<EntityRec> entities, int changesApplied) {}

    Resolved resolve(boolean latest) {
        int applied = 0;
        if (latest) {
            for (Changes c : changes) {
                for (int i = 0; i < c.xs.length; i++) {
                    Section s = sections.get(key(c.xs[i] >> 4, c.zs[i] >> 4, c.ys[i] >> 4));
                    if (s == null) continue;
                    s.blocks()[(((c.ys[i] & 15) << 4 | (c.zs[i] & 15)) << 4) | (c.xs[i] & 15)] = c.ids[i];
                    applied++;
                }
            }
        }
        Map<Long, BlockEntityRec> be = new LinkedHashMap<>();
        for (BlockEntityRec r : blockEntities) {
            if (!latest && r.kind != 0) continue;
            be.put(BlockPos.asLong(r.x, r.y, r.z), r);
        }
        Map<UUID, EntityRec> ents = new LinkedHashMap<>();
        for (EntityRec r : entities) {
            if (!latest && r.kind != 0) continue;
            ents.put(new UUID(r.uuidHi, r.uuidLo), r);
        }
        return new Resolved(sections, new ArrayList<>(be.values()), new ArrayList<>(ents.values()), applied);
    }

    /** Chunk columns in the file, sorted by (cx, cz). */
    TreeMap<Long, List<Section>> columns(Map<Long, Section> from) {
        TreeMap<Long, List<Section>> out = new TreeMap<>();
        for (Section s : from.values()) out.computeIfAbsent(((long) s.cx << 32) | (s.cz & 0xFFFFFFFFL), k -> new ArrayList<>()).add(s);
        return out;
    }

    /** One line for `/hx world list`. */
    String summary() {
        JsonObject bounds = header.has("bounds") ? header.getAsJsonObject("bounds") : null;
        String stored = bounds != null && bounds.has("stored") ? bounds.get("stored").toString() : "?";
        return sections.size() + " sections, " + blockEntities.size() + " block entities, " + entities.size() + " entities, "
            + changes.size() + " change batches, stored bounds " + stored + (truncated ? ", TRUNCATED" : "") + ", " + (fileSize >> 10) + " KiB";
    }
}
