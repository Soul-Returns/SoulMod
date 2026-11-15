package com.soulreturns.devbridge.world

/**
 * The world capture file `<gameDir>/soul-dev/worlds/<time>[_label].soulworld.gz` (format version 1).
 * Read by `tools/devbridge/listen world …` and the SoulTestKit plugin (`/hx world load`); change
 * both readers with this file and bump [VERSION] for anything incompatible.
 *
 * ```
 * file   = gzip( "SOULWRLD" u16:version record* )          big endian, sync-flushed every 2 s
 * record = u8:type u32:length payload[length]
 * HEADER       1  utf8 JSON: format, version, label, started (wall ms), server, player, minecraft,
 *                 dimension, min_y, max_y, bounds{requested, stored}, columns_total
 * STATE        2  u32:id u16:n utf8[n]   "minecraft:oak_stairs[facing=east,half=bottom]" (props sorted)
 * SECTION      3  u32:t i32:cx i32:cz i16:sy u16:n u32[n]:palette (state ids) u8:bits
 *                 [bits>0: u16:longs i64[longs]]   first-seen 16^3 blocks; index i = (y*16+z)*16+x,
 *                 values per long = 64/bits, LSB first, entries never span two longs
 * BLOCK_ENTITY 4  u32:t u8:kind(0 first, 1 update) i32:x i32:y i32:z nbt   (NbtIo.write of saveWithFullMetadata)
 * ENTITY       5  u32:t u8:kind(0 first, 1 update) i32:id i64:uuidHi i64:uuidLo u16:n utf8[n]:type
 *                 f64:x f64:y f64:z f32:yaw f32:pitch nbt   (Entity.saveWithoutId)
 * CHANGES      6  u32:t u8:source(0 packet, 1 diff of a re-sent chunk) u32:n n*(i32:x i16:y i32:z u32:state)
 * NOTE         7  utf8 JSON {t, kind, ...}
 * END          8  utf8 JSON: reason, stats, holes (absent when the process died: a truncated file is valid)
 * CLOCK        9  u32:t i64:tick i64:ping   the server tick ([com.soulreturns.devbridge.Ticks.tick], -1 unknown) and the raw ping
 *                 count (-1 unknown) when the records after it were queued; written before a BLOCK_ENTITY, ENTITY or
 *                 CHANGES record whenever the tick differs from the last CLOCK. A record's server tick is the last CLOCK
 *                 before it. Added 2026-11-12 without a version bump: readers skip record types they do not know.
 * `t` is milliseconds since HEADER.started. A STATE record always precedes its first use. "As on
 * entry" = SECTION + first BLOCK_ENTITY/ENTITY; "latest" = those plus every later record in order.
 */
object WorldFormat {
    const val FORMAT = "soul-world-capture"
    const val MAGIC = "SOULWRLD"
    const val VERSION = 1
    const val SUFFIX = ".soulworld.gz"

    const val HEADER = 1
    const val STATE = 2
    const val SECTION = 3
    const val BLOCK_ENTITY = 4
    const val ENTITY = 5
    const val CHANGES = 6
    const val NOTE = 7
    const val END = 8
    const val CLOCK = 9

    const val BLOCKS = 4096

    /** 64-bit content hash of a serialized tag (change detection, not security). */
    fun hash(bytes: ByteArray): Long {
        var h = 1125899906842597L
        for (b in bytes) h = 31 * h + b
        return h xor bytes.size.toLong()
    }
}

/** One section's blocks: a palette of state ids and bit-packed palette indices (no data when [bits] is 0). */
class Packed(val palette: IntArray, val bits: Int, val longs: LongArray) {
    fun decode(): IntArray {
        val out = IntArray(WorldFormat.BLOCKS)
        if (bits == 0) {
            java.util.Arrays.fill(out, palette[0])
            return out
        }
        val per = 64 / bits
        val mask = (1L shl bits) - 1
        for (i in 0 until WorldFormat.BLOCKS) {
            out[i] = palette[((longs[i / per] ushr ((i % per) * bits)) and mask).toInt()]
        }
        return out
    }

    fun at(i: Int): Int {
        if (bits == 0) return palette[0]
        val per = 64 / bits
        return palette[((longs[i / per] ushr ((i % per) * bits)) and ((1L shl bits) - 1)).toInt()]
    }

    companion object {
        fun uniform(id: Int) = Packed(intArrayOf(id), 0, LongArray(0))

        /** [scratch] maps state ids to palette slots without hashing; one per thread. */
        fun encode(ids: IntArray, scratch: Scratch): Packed {
            scratch.next()
            val pal = IntArray(WorldFormat.BLOCKS)
            val idx = IntArray(WorldFormat.BLOCKS)
            var n = 0
            for (i in 0 until WorldFormat.BLOCKS) {
                val id = ids[i]
                scratch.ensure(id)
                if (scratch.stamp[id] != scratch.gen) {
                    scratch.stamp[id] = scratch.gen
                    scratch.slot[id] = n
                    pal[n++] = id
                }
                idx[i] = scratch.slot[id]
            }
            if (n == 1) return uniform(pal[0])
            val bits = maxOf(1, 32 - Integer.numberOfLeadingZeros(n - 1))
            val per = 64 / bits
            val longs = LongArray((WorldFormat.BLOCKS + per - 1) / per)
            for (i in 0 until WorldFormat.BLOCKS) longs[i / per] = longs[i / per] or (idx[i].toLong() shl ((i % per) * bits))
            return Packed(pal.copyOf(n), bits, longs)
        }
    }

    class Scratch {
        var stamp = IntArray(1024)
        var slot = IntArray(1024)
        var gen = 0

        fun next() {
            gen++
        }

        fun ensure(id: Int) {
            if (id < stamp.size) return
            val size = maxOf(id + 1, stamp.size * 2)
            stamp = stamp.copyOf(size)
            slot = slot.copyOf(size)
        }
    }
}
