package com.soulreturns.devbridge.world

import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

sealed interface WorldItem

class StateItem(val id: Int, val name: String) : WorldItem

class SectionItem(val t: Int, val cx: Int, val cz: Int, val sy: Int, val packed: Packed) : WorldItem

/** A re-sent chunk's section, compared with what was captured; only the differences are logged. */
class DiffItem(val t: Int, val cx: Int, val cz: Int, val sy: Int, val packed: Packed) : WorldItem

class BlockEntityItem(val t: Int, val x: Int, val y: Int, val z: Int, val nbt: ByteArray) : WorldItem

class EntityItem(
    val t: Int,
    val id: Int,
    val uuidHi: Long,
    val uuidLo: Long,
    val type: String,
    val x: Double,
    val y: Double,
    val z: Double,
    val yaw: Float,
    val pitch: Float,
    val nbt: ByteArray,
    /** [WorldFormat.hash] of the tag without `CustomNameVisible`: equal to the previous one means the update only flipped the label's visibility. */
    val shape: Long,
) : WorldItem

/** Block changes read off packets: parallel arrays of [n] entries. */
class ChangeItem(val t: Int, val n: Int, val xs: IntArray, val ys: IntArray, val zs: IntArray, val ids: IntArray) : WorldItem

/** The server tick and raw ping count at [t] (-1 = unknown), queued before the records that follow it. */
class ClockItem(val t: Int, val tick: Long, val ping: Long) : WorldItem

class NoteItem(val json: String) : WorldItem

class StopItem(val endJson: String) : WorldItem

/**
 * The capture's single writer thread: encodes sections, keeps a compact copy of every captured
 * section to drop no-op changes and to diff re-sent chunks, and streams the gzip file. The client
 * thread only copies block ids and hands items over, so the file work never costs a frame.
 */
class WorldWriter(val file: Path, private val header: String, val started: Long) {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")
    val queue = LinkedBlockingQueue<WorldItem>()

    @Volatile var compressedBytes = 0L
    @Volatile var rawBytes = 0L
    @Volatile var sections = 0L
    @Volatile var blocksNonAir = 0L
    @Volatile var blockEntities = 0L
    @Volatile var blockEntityUpdates = 0L
    @Volatile var entities = 0L
    @Volatile var entityUpdates = 0L
    @Volatile var entityUpdatesCapped = 0L
    @Volatile var entityVisibilityUpdates = 0L
    @Volatile var changes = 0L
    @Volatile var changesDeduped = 0L
    @Volatile var diffChanges = 0L
    @Volatile var states = 0L
    @Volatile var failure: String? = null
    @Volatile var finished = false

    lateinit var thread: Thread
    private val scratch = Packed.Scratch()
    private val shadow = HashMap<Long, Packed>()
    private var air = BooleanArray(256)
    private val beHash = HashMap<Long, Long>()
    private val entityState = HashMap<Long, LongArray>()
    private val body = ByteArrayOutputStream(1 shl 16)
    private val data = DataOutputStream(body)

    private class Counting(out: OutputStream, val w: WorldWriter) : FilterOutputStream(out) {
        override fun write(b: Int) {
            out.write(b)
            w.compressedBytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            w.compressedBytes += len
        }
    }

    fun start() {
        thread = Thread({ run() }, "Soul devbridge world writer").apply { isDaemon = true }
        thread.start()
    }

    private fun run() {
        var gz: GZIPOutputStream? = null
        try {
            Files.createDirectories(file.parent)
            gz = GZIPOutputStream(Counting(BufferedOutputStream(Files.newOutputStream(file), 1 shl 16), this), 1 shl 16, true)
            val out = DataOutputStream(gz)
            out.write(WorldFormat.MAGIC.toByteArray(StandardCharsets.US_ASCII))
            out.writeShort(WorldFormat.VERSION)
            record(out, WorldFormat.HEADER) { it.write(header.toByteArray(StandardCharsets.UTF_8)) }
            var lastFlush = System.currentTimeMillis()
            while (true) {
                val item = queue.poll(250, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (item is StopItem) {
                    record(out, WorldFormat.END) { it.write(item.endJson.toByteArray(StandardCharsets.UTF_8)) }
                    break
                }
                if (item != null) handle(out, item)
                if (now - lastFlush >= 2_000) {
                    lastFlush = now
                    gz.flush()
                }
            }
        } catch (t: Throwable) {
            failure = t.toString()
            logger.warn("[Soul/DevBridge] world capture writer failed: $t")
        } finally {
            runCatching { gz?.close() }
            finished = true
        }
    }

    private fun record(out: DataOutputStream, type: Int, fill: (DataOutputStream) -> Unit) {
        body.reset()
        fill(data)
        data.flush()
        out.writeByte(type)
        out.writeInt(body.size())
        body.writeTo(out)
        rawBytes += 5 + body.size()
    }

    private fun handle(out: DataOutputStream, item: WorldItem) {
        if (item is StateItem) {
            if (item.id >= air.size) air = air.copyOf(maxOf(item.id + 1, air.size * 2))
            val name = item.name.substringBefore('[')
            air[item.id] = name == "minecraft:air" || name == "minecraft:cave_air" || name == "minecraft:void_air"
            states++
            record(out, WorldFormat.STATE) {
                val bytes = item.name.toByteArray(StandardCharsets.UTF_8)
                it.writeInt(item.id)
                it.writeShort(bytes.size)
                it.write(bytes)
            }
        } else if (item is SectionItem) {
            val packed = item.packed
            shadow[key(item.cx, item.cz, item.sy)] = packed
            sections++
            blocksNonAir += nonAir(packed)
            record(out, WorldFormat.SECTION) {
                it.writeInt(item.t)
                it.writeInt(item.cx)
                it.writeInt(item.cz)
                it.writeShort(item.sy)
                it.writeShort(packed.palette.size)
                for (id in packed.palette) it.writeInt(id)
                it.writeByte(packed.bits)
                if (packed.bits > 0) {
                    it.writeShort(packed.longs.size)
                    for (l in packed.longs) it.writeLong(l)
                }
            }
        } else if (item is DiffItem) {
            diff(out, item)
        } else if (item is ChangeItem) {
            changed(out, item)
        } else if (item is BlockEntityItem) {
            val k = ((item.x.toLong() and 0x3FFFFFF) shl 38) or ((item.z.toLong() and 0x3FFFFFF) shl 12) or (item.y.toLong() and 0xFFF)
            val h = hash(item.nbt)
            val old = beHash.put(k, h)
            if (old != null && old == h) return
            if (old == null) blockEntities++ else blockEntityUpdates++
            record(out, WorldFormat.BLOCK_ENTITY) {
                it.writeInt(item.t)
                it.writeByte(if (old == null) 0 else 1)
                it.writeInt(item.x)
                it.writeInt(item.y)
                it.writeInt(item.z)
                it.write(item.nbt)
            }
        } else if (item is EntityItem) {
            val k = item.uuidHi xor (item.uuidLo * 31)
            val h = hash(item.nbt)
            val st = entityState[k]
            if (st != null && st[0] == h) return
            // [hash, shape, counted updates, visibility-only updates]; the shape is the tag without CustomNameVisible.
            val visibilityOnly = st != null && st[1] == item.shape
            if (st != null && !visibilityOnly && st[2] >= MAX_ENTITY_UPDATES) {
                entityUpdatesCapped++
                return
            }
            if (st != null && visibilityOnly && st[3] >= MAX_VISIBILITY_UPDATES) {
                entityUpdatesCapped++
                return
            }
            if (st == null) {
                entityState[k] = longArrayOf(h, item.shape, 0, 0)
                entities++
            } else {
                st[0] = h
                st[1] = item.shape
                if (visibilityOnly) {
                    st[3]++
                    entityVisibilityUpdates++
                } else {
                    st[2]++
                    entityUpdates++
                }
            }
            record(out, WorldFormat.ENTITY) {
                val type = item.type.toByteArray(StandardCharsets.UTF_8)
                it.writeInt(item.t)
                it.writeByte(if (st == null) 0 else 1)
                it.writeInt(item.id)
                it.writeLong(item.uuidHi)
                it.writeLong(item.uuidLo)
                it.writeShort(type.size)
                it.write(type)
                it.writeDouble(item.x)
                it.writeDouble(item.y)
                it.writeDouble(item.z)
                it.writeFloat(item.yaw)
                it.writeFloat(item.pitch)
                it.write(item.nbt)
            }
        } else if (item is ClockItem) {
            record(out, WorldFormat.CLOCK) {
                it.writeInt(item.t)
                it.writeLong(item.tick)
                it.writeLong(item.ping)
            }
        } else if (item is NoteItem) {
            record(out, WorldFormat.NOTE) { it.write(item.json.toByteArray(StandardCharsets.UTF_8)) }
        }
    }

    private fun nonAir(p: Packed): Int {
        if (p.bits == 0) return if (air[p.palette[0]]) 0 else WorldFormat.BLOCKS
        var n = 0
        val ids = p.decode()
        for (id in ids) if (!air[id]) n++
        return n
    }

    private fun changed(out: DataOutputStream, item: ChangeItem) {
        val keepX = IntArray(item.n)
        val keepY = IntArray(item.n)
        val keepZ = IntArray(item.n)
        val keepId = IntArray(item.n)
        var kept = 0
        var i = 0
        var cachedKey = Long.MIN_VALUE
        var cached: IntArray? = null
        fun flushCache() {
            val c = cached ?: return
            shadow[cachedKey] = Packed.encode(c, scratch)
        }
        while (i < item.n) {
            val x = item.xs[i]
            val y = item.ys[i]
            val z = item.zs[i]
            val k = key(x shr 4, z shr 4, y shr 4)
            if (k != cachedKey) {
                flushCache()
                cachedKey = k
                cached = shadow[k]?.decode()
            }
            val section = cached
            if (section != null) {
                val idx = (((y and 15) shl 4) or (z and 15)) shl 4 or (x and 15)
                if (section[idx] == item.ids[i]) {
                    changesDeduped++
                } else {
                    section[idx] = item.ids[i]
                    keepX[kept] = x
                    keepY[kept] = y
                    keepZ[kept] = z
                    keepId[kept] = item.ids[i]
                    kept++
                }
            }
            i++
        }
        flushCache()
        if (kept == 0) return
        changes += kept
        writeChanges(out, item.t, 0, kept, keepX, keepY, keepZ, keepId)
    }

    private fun diff(out: DataOutputStream, item: DiffItem) {
        val k = key(item.cx, item.cz, item.sy)
        val old = shadow[k] ?: return
        val now = item.packed.decode()
        val before = old.decode()
        var n = 0
        for (i in 0 until WorldFormat.BLOCKS) if (before[i] != now[i]) n++
        if (n == 0) return
        val xs = IntArray(n)
        val ys = IntArray(n)
        val zs = IntArray(n)
        val ids = IntArray(n)
        var j = 0
        for (i in 0 until WorldFormat.BLOCKS) {
            if (before[i] == now[i]) continue
            xs[j] = (item.cx shl 4) + (i and 15)
            zs[j] = (item.cz shl 4) + ((i shr 4) and 15)
            ys[j] = (item.sy shl 4) + (i shr 8)
            ids[j] = now[i]
            j++
        }
        shadow[k] = item.packed
        diffChanges += n
        writeChanges(out, item.t, 1, n, xs, ys, zs, ids)
    }

    private fun writeChanges(out: DataOutputStream, t: Int, source: Int, n: Int, xs: IntArray, ys: IntArray, zs: IntArray, ids: IntArray) {
        var from = 0
        while (from < n) {
            val count = minOf(n - from, 60_000)
            record(out, WorldFormat.CHANGES) {
                it.writeInt(t)
                it.writeByte(source)
                it.writeInt(count)
                for (i in from until from + count) {
                    it.writeInt(xs[i])
                    it.writeShort(ys[i])
                    it.writeInt(zs[i])
                    it.writeInt(ids[i])
                }
            }
            from += count
        }
    }

    private fun hash(bytes: ByteArray): Long = WorldFormat.hash(bytes)

    private fun key(cx: Int, cz: Int, sy: Int): Long =
        ((cx.toLong() and 0x3FFFFF) shl 42) or ((cz.toLong() and 0x3FFFFF) shl 20) or (sy.toLong() and 0xFFFFF)

    companion object {
        /** Updates per entity that change anything but its name visibility. */
        const val MAX_ENTITY_UPDATES = 40

        /**
         * Name-visibility flips per entity, counted apart: Hypixel's label stands flip between shown and hidden
         * every few ticks and used the whole 40 up long before the fight did anything else.
         */
        const val MAX_VISIBILITY_UPDATES = 2000
    }
}
