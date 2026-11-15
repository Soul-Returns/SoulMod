package com.soulreturns.devbridge.world

import com.soulreturns.devbridge.BridgeInfo
import com.soulreturns.devbridge.Ticks
import com.soulreturns.devbridge.json
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import io.netty.buffer.Unpooled
import net.minecraft.core.BlockPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtIo
import net.minecraft.network.FriendlyByteBuf
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSectionBlocksUpdatePacket
import net.minecraft.util.Mth
import net.minecraft.util.ProblemReporter
import net.minecraft.world.entity.Display
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.decoration.HangingEntity
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.chunk.LevelChunk
import net.minecraft.world.level.chunk.LevelChunkSection
import net.minecraft.world.level.storage.TagValueOutput
import org.slf4j.LoggerFactory
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.IdentityHashMap

/**
 * World capture (read-only, see [WorldFormat]): while a capture runs it copies every chunk section
 * the client has loaded inside the bounds, as first seen, then logs later block changes, block
 * entity data and static entities (armor stands, item frames, displays) as an ordered change log.
 * Reads only: chunk sections, block entities and entities the client already holds; sends nothing.
 *
 * Client thread work is bounded per tick (a time budget of whole sections, default 1 ms); block ids
 * are copied into arrays and handed to [WorldWriter], which encodes and writes on its own thread.
 * Idle: [active] is one volatile read in the tick and the packet hooks. A capture ends on `stop`,
 * leaving the world or a new client level (another server world), or its caps.
 */
object WorldCapture {
    private val logger = LoggerFactory.getLogger("Soul/DevBridge")
    private val names = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private val dir: Path = BridgeInfo.dir.resolve("worlds")

    @JvmField
    @Volatile
    var active = false

    @Volatile
    var current: Session? = null
        private set

    @Volatile
    private var last: Map<String, Any?>? = null

    class Options(
        val label: String?,
        val bounds: IntArray?,
        val around: Int?,
        val maxChunks: Int,
        val budgetMs: Double,
        val maxMinutes: Double,
        val maxMb: Double,
    ) {
        companion object {
            fun parse(q: Map<String, String>): Options {
                val bounds = q["bounds"]?.split(',')?.map { it.trim().toInt() }
                require(bounds == null || bounds.size == 6) { "bounds needs x1,y1,z1,x2,y2,z2" }
                return Options(
                    q["label"]?.replace(Regex("[^A-Za-z0-9_.-]+"), "-")?.trim('-')?.ifEmpty { null },
                    bounds?.toIntArray(),
                    q["around"]?.toInt()?.also { require(it in 1..20_000) { "around 1..20000" } },
                    q["max_chunks"]?.toInt()?.coerceIn(1, 20_000) ?: 4096,
                    q["budget_ms"]?.toDouble()?.coerceIn(0.1, 8.0) ?: 1.0,
                    q["max_min"]?.toDouble()?.coerceIn(0.1, 24 * 60.0) ?: 480.0,
                    q["max_mb"]?.toDouble()?.coerceIn(1.0, 8192.0) ?: 2048.0,
                )
            }
        }
    }

    /** Client thread. Returns the new session or the reason it cannot start. */
    @Synchronized
    fun start(options: Options): Pair<Session?, String?> {
        current?.let { return null to "already capturing to ${it.file} (stop it first)" }
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null to "not in a world"
        val player = mc.player ?: return null to "no player"
        val b = options.bounds
        val minY: Int
        val maxY: Int
        val x1: Int
        val z1: Int
        val x2: Int
        val z2: Int
        if (b != null) {
            x1 = minOf(b[0], b[3]); x2 = maxOf(b[0], b[3])
            minY = minOf(b[1], b[4]); maxY = maxOf(b[1], b[4])
            z1 = minOf(b[2], b[5]); z2 = maxOf(b[2], b[5])
        } else {
            val r = options.around ?: 128
            x1 = Mth.floor(player.x) - r; x2 = Mth.floor(player.x) + r
            z1 = Mth.floor(player.z) - r; z2 = Mth.floor(player.z) + r
            minY = level.minY; maxY = level.maxY
        }
        val colMinX = x1 shr 4
        val colMaxX = x2 shr 4
        val colMinZ = z1 shr 4
        val colMaxZ = z2 shr 4
        val columns = (colMaxX - colMinX + 1).toLong() * (colMaxZ - colMinZ + 1)
        if (columns > options.maxChunks) return null to "bounds cover $columns chunk columns, over --max-chunks ${options.maxChunks}"
        val secMin = maxOf(minY shr 4, level.minSectionY)
        val secMax = minOf(maxY shr 4, level.maxSectionY)
        if (secMin > secMax) return null to "bounds are outside the world's height ${level.minY}..${level.maxY}"
        val started = System.currentTimeMillis()
        val file = dir.resolve(LocalDateTime.now().format(names) + (options.label?.let { "_$it" } ?: "") + WorldFormat.SUFFIX)
        val requested = intArrayOf(x1, minY, z1, x2, maxY, z2)
        val stored = intArrayOf(colMinX * 16, secMin * 16, colMinZ * 16, colMaxX * 16 + 15, secMax * 16 + 15, colMaxZ * 16 + 15)
        val header =
            json(
                linkedMapOf(
                    "format" to WorldFormat.FORMAT,
                    "version" to WorldFormat.VERSION,
                    "label" to options.label,
                    "started" to started,
                    "server" to mc.currentServer?.ip,
                    "player" to mc.user.name,
                    "minecraft" to BridgeInfo.version("minecraft"),
                    "dimension" to level.dimension().identifier().toString(),
                    "min_y" to level.minY,
                    "max_y" to level.maxY,
                    "bounds" to linkedMapOf("requested" to requested.toList(), "stored" to stored.toList()),
                    "columns_total" to columns,
                ),
            ).toString()
        val writer = WorldWriter(file, header, started)
        val session = Session(options, level, writer, started, requested, stored, colMinX, colMaxX, colMinZ, colMaxZ, secMin, secMax)
        current = session
        active = true
        writer.start()
        logger.info("[Soul/DevBridge] world capture to $file, $columns chunk columns, bounds ${stored.toList()}")
        return session to null
    }

    /** Any thread but the client thread (it waits for the writer). */
    fun stop(reason: String): Map<String, Any?>? {
        val s = current ?: return null
        val end = onClientThread { s.beginStop(reason) }
        return s.awaitClosed(end)
    }

    private fun <T> onClientThread(block: () -> T): T {
        val mc = Minecraft.getInstance()
        return if (mc.isSameThread) block() else java.util.concurrent.CompletableFuture.supplyAsync({ block() }, mc).get(5, java.util.concurrent.TimeUnit.SECONDS)
    }

    /** Client thread. */
    fun status(): Map<String, Any?> = current?.describe() ?: last ?: linkedMapOf("active" to false)

    /** END_CLIENT_TICK, only called while [active]. */
    fun tick() {
        current?.tick()
    }

    fun onChunk(cx: Int, cz: Int) {
        current?.onChunk(cx, cz)
    }

    fun onBlockUpdate(packet: ClientboundBlockUpdatePacket) {
        val s = current ?: return
        s.change(packet.pos.x, packet.pos.y, packet.pos.z, packet.blockState)
        s.flushChanges()
    }

    fun onSectionUpdate(packet: ClientboundSectionBlocksUpdatePacket) {
        val s = current ?: return
        packet.runUpdates { pos, state -> s.change(pos.x, pos.y, pos.z, state) }
        s.flushChanges()
    }

    fun onBlockEntityData(packet: ClientboundBlockEntityDataPacket) {
        current?.blockEntityData(packet.pos)
    }

    fun onEntity(id: Int) {
        current?.touchEntity(id)
    }

    /** Last finished capture, for `/world/status` after it ended. */
    fun finished(summary: Map<String, Any?>, session: Session) {
        last = summary
        runCatching {
            val latest = dir.resolve("latest" + WorldFormat.SUFFIX)
            Files.deleteIfExists(latest)
            Files.createSymbolicLink(latest, session.writer.file.fileName)
        }
        // After the link: a `stop` that returns once the session is gone must find latest pointing at it.
        synchronized(this) { if (current === session) current = null }
    }

    fun describeBrief(): Map<String, Any?> {
        val s = current
        return if (s == null) linkedMapOf("active" to false) else linkedMapOf("active" to true, "file" to s.file.toString(), "sections" to s.writer.sections)
    }

    class Session(
        val options: Options,
        val level: ClientLevel,
        val writer: WorldWriter,
        val started: Long,
        val requested: IntArray,
        val stored: IntArray,
        private val colMinX: Int,
        private val colMaxX: Int,
        private val colMinZ: Int,
        private val colMaxZ: Int,
        private val secMin: Int,
        private val secMax: Int,
    ) {
        val file: Path get() = writer.file

        @Volatile private var stopping = false
        private val states = IdentityHashMap<BlockState, Int>()
        private val seen = HashSet<Long>()
        private val capturedSections = HashSet<Long>()
        private val pending = ArrayDeque<Long>()
        private val queued = HashSet<Long>()
        private val progress = HashMap<Long, Int>()
        private val rediff = LinkedHashSet<Long>()
        private var needBlockEntities = false
        private val knownEntities = HashSet<java.util.UUID>()
        private val entityQueue = ArrayDeque<Int>()
        private val dirtyEntities = HashSet<Int>()
        private val scratch = Packed.Scratch()
        private val wire = FriendlyByteBuf(Unpooled.buffer(4096))

        private var cx = Int.MIN_VALUE
        private var cz = Int.MIN_VALUE
        private var cursorNext = 0
        private var cursorRediff = false

        private var cxs = IntArray(256)
        private var cys = IntArray(256)
        private var czs = IntArray(256)
        private var cids = IntArray(256)
        private var cn = 0

        private var ticks = 0L
        private var workTicks = 0L
        private var nanosTotal = 0L
        private var nanosWork = 0L
        private var nanosMax = 0L
        private var over2ms = 0L
        private var entityErrors = 0L
        private var lastEntityError: String? = null
        private var dropped = 0L
        private val columnsTotal = (colMaxX - colMinX + 1).toLong() * (colMaxZ - colMinZ + 1)

        private fun colKey(x: Int, z: Int): Long = (x.toLong() shl 32) or (z.toLong() and 0xFFFFFFFFL)

        private fun secKey(x: Int, z: Int, y: Int): Long = ((x.toLong() and 0x3FFFFF) shl 42) or ((z.toLong() and 0x3FFFFF) shl 20) or (y.toLong() and 0xFFFFF)

        private fun t(): Int = (System.currentTimeMillis() - started).toInt()

        private fun inColumns(x: Int, z: Int) = x in colMinX..colMaxX && z in colMinZ..colMaxZ

        fun tick() {
            if (stopping) return
            val mc = Minecraft.getInstance()
            if (mc.level !== level) {
                beginStop(if (mc.level == null) "left the world" else "the client switched to another world (new level)")
                return
            }
            val t0 = System.nanoTime()
            ticks++
            if (ticks % 10 == 1L) scan()
            if (ticks % 5 == 0L) scanEntities()
            val worked =
                try {
                    work(t0 + (options.budgetMs * 1_000_000).toLong())
                } catch (e: Throwable) {
                    beginStop("capture failed: $e")
                    false
                }
            val cost = System.nanoTime() - t0
            nanosTotal += cost
            if (worked) {
                workTicks++
                nanosWork += cost
            }
            if (cost > nanosMax) nanosMax = cost
            if (cost > 2_000_000) over2ms++
            if (System.currentTimeMillis() - started >= options.maxMinutes * 60_000) beginStop("max duration ${options.maxMinutes} min")
            else if (writer.compressedBytes >= options.maxMb * 1024 * 1024) beginStop("max size ${options.maxMb} MB")
            else if (writer.failure != null) beginStop("writer failed: ${writer.failure}")
        }

        private fun scan() {
            val source = level.chunkSource
            for (x in colMinX..colMaxX) {
                for (z in colMinZ..colMaxZ) {
                    val k = colKey(x, z)
                    if (k in seen || k in queued) continue
                    if (source.getChunkNow(x, z) != null) enqueue(k)
                }
            }
        }

        private fun enqueue(k: Long) {
            queued += k
            pending.addLast(k)
        }

        fun onChunk(x: Int, z: Int) {
            if (stopping || !inColumns(x, z)) return
            val k = colKey(x, z)
            if (k in seen || k in progress) rediff += k
            if (k !in seen && k !in queued) enqueue(k)
        }

        /** Copies sections of waiting columns until [deadline] (always at least one section). */
        private fun work(deadline: Long): Boolean {
            var worked = false
            while (writer.queue.size < MAX_QUEUE) {
                if (cx == Int.MIN_VALUE && !nextColumn()) break
                val chunk = level.chunkSource.getChunkNow(cx, cz)
                val k = colKey(cx, cz)
                if (chunk == null) {
                    if (!cursorRediff) {
                        progress[k] = cursorNext
                        queued -= k
                    }
                    cx = Int.MIN_VALUE
                    continue
                }
                if (needBlockEntities) {
                    needBlockEntities = false
                    blockEntities(chunk)
                }
                captureSection(chunk, cursorNext, cursorRediff)
                worked = true
                cursorNext++
                if (cursorNext > secMax) {
                    if (!cursorRediff) {
                        seen += k
                        queued -= k
                        progress.remove(k)
                    }
                    cx = Int.MIN_VALUE
                }
                if (System.nanoTime() >= deadline) break
            }
            if (writer.queue.size < MAX_QUEUE && System.nanoTime() < deadline) worked = entities(deadline) || worked
            return worked
        }

        private fun nextColumn(): Boolean {
            val first = pending.removeFirstOrNull()
            if (first != null) {
                cx = (first shr 32).toInt()
                cz = first.toInt()
                cursorNext = progress[first] ?: secMin
                cursorRediff = false
                needBlockEntities = true
                return true
            }
            val it = rediff.iterator()
            if (!it.hasNext()) return false
            val k = it.next()
            it.remove()
            cx = (k shr 32).toInt()
            cz = k.toInt()
            cursorNext = secMin
            cursorRediff = true
            needBlockEntities = true
            return true
        }

        private fun captureSection(chunk: LevelChunk, sy: Int, diff: Boolean) {
            val key = secKey(cx, cz, sy)
            if (diff && key !in capturedSections) return
            val section: LevelChunkSection = chunk.getSection(chunk.getSectionIndexFromSectionY(sy))
            val packed = pack(section)
            val tt = t()
            writer.queue.add(if (diff) DiffItem(tt, cx, cz, sy, packed) else SectionItem(tt, cx, cz, sy, packed))
            capturedSections += key
        }

        /**
         * The section as the file stores it, from vanilla's own network encoding of its palette container
         * (bits, palette of block state ids, packed longs in the same layout), so no per-block work: only the
         * palette entries are mapped to the capture's state ids. A container past 8 bits keeps raw global ids
         * and is unpacked once (rare: a section with hundreds of distinct states).
         */
        private fun pack(section: LevelChunkSection): Packed {
            wire.clear()
            section.states.write(wire)
            val bits = wire.readUnsignedByte().toInt()
            if (bits == 0) {
                val only = id(Block.stateById(wire.readVarInt()))
                check(wire.readableBytes() == 0) { "single-valued section left ${wire.readableBytes()} bytes: vanilla's palette container layout changed" }
                return Packed.uniform(only)
            }
            // The long array has no length prefix since 1.21.5: it follows from the bit width.
            val per = 64 / bits
            val count = (WorldFormat.BLOCKS + per - 1) / per
            if (bits <= 8) {
                val palette = IntArray(wire.readVarInt()) { id(Block.stateById(wire.readVarInt())) }
                val longs = LongArray(count) { wire.readLong() }
                check(wire.readableBytes() == 0) { "section encoding left ${wire.readableBytes()} bytes: vanilla's palette container layout changed" }
                return Packed(palette, bits, longs)
            }
            val longs = LongArray(count) { wire.readLong() }
            check(wire.readableBytes() == 0) { "section encoding left ${wire.readableBytes()} bytes: vanilla's palette container layout changed" }
            val mask = (1L shl bits) - 1
            val ids = IntArray(WorldFormat.BLOCKS)
            for (i in 0 until WorldFormat.BLOCKS) ids[i] = id(Block.stateById(((longs[i / per] ushr ((i % per) * bits)) and mask).toInt()))
            return Packed.encode(ids, scratch)
        }

        private fun blockEntities(chunk: LevelChunk) {
            val registries = level.registryAccess()
            for (be in chunk.blockEntities.values.toList()) {
                val p = be.blockPos
                if (p.y < stored[1] || p.y > stored[4]) continue
                val tag = be.saveWithFullMetadata(registries)
                stamp()
                writer.queue.add(BlockEntityItem(t(), p.x, p.y, p.z, nbt(tag)))
            }
        }

        fun blockEntityData(pos: BlockPos) {
            if (stopping || secKey(pos.x shr 4, pos.z shr 4, pos.y shr 4) !in capturedSections) return
            val be = level.getBlockEntity(pos) ?: return
            stamp()
            writer.queue.add(BlockEntityItem(t(), pos.x, pos.y, pos.z, nbt(be.saveWithFullMetadata(level.registryAccess()))))
        }

        fun change(x: Int, y: Int, z: Int, state: BlockState) {
            if (stopping || secKey(x shr 4, z shr 4, y shr 4) !in capturedSections) return
            if (cn == cxs.size) {
                val size = cn * 2
                cxs = cxs.copyOf(size)
                cys = cys.copyOf(size)
                czs = czs.copyOf(size)
                cids = cids.copyOf(size)
            }
            cxs[cn] = x
            cys[cn] = y
            czs[cn] = z
            cids[cn] = id(state)
            cn++
        }

        fun flushChanges() {
            if (cn == 0) return
            stamp()
            writer.queue.add(ChangeItem(t(), cn, cxs.copyOf(cn), cys.copyOf(cn), czs.copyOf(cn), cids.copyOf(cn)))
            cn = 0
        }

        private var lastStampTick = Long.MIN_VALUE

        /** A CLOCK record before the next record whenever the server tick moved (-1 = no clock yet). */
        private fun stamp() {
            val tick = Ticks.tick() ?: -1L
            if (tick == lastStampTick) return
            lastStampTick = tick
            writer.queue.add(ClockItem(t(), tick, Ticks.ping() ?: -1L))
        }

        private fun interesting(e: Entity) = e is ArmorStand || e is HangingEntity || e is Display

        private fun inStored(x: Double, y: Double, z: Double) =
            x >= stored[0] && x < stored[3] + 1 && y >= stored[1] && y < stored[4] + 1 && z >= stored[2] && z < stored[5] + 1

        fun touchEntity(id: Int) {
            if (stopping) return
            val e = level.getEntity(id) ?: return
            if (interesting(e)) dirtyEntities += id
        }

        private fun scanEntities() {
            for (e in level.entitiesForRendering()) {
                if (!interesting(e) || !inStored(e.x, e.y, e.z)) continue
                if (knownEntities.add(e.uuid) || e.id in dirtyEntities) entityQueue.addLast(e.id)
            }
            dirtyEntities.clear()
        }

        private fun entities(deadline: Long): Boolean {
            var worked = false
            while (entityQueue.isNotEmpty() && System.nanoTime() < deadline) {
                val e = level.getEntity(entityQueue.removeFirst()) ?: continue
                worked = true
                try {
                    val out = TagValueOutput.createWithContext(ProblemReporter.DISCARDING, level.registryAccess())
                    e.saveWithoutId(out)
                    val tag = out.buildResult()
                    // Per-tick client bookkeeping that would read as an entity change.
                    for (key in VOLATILE_ENTITY_KEYS) tag.remove(key)
                    val uuid = e.uuid
                    // The shape: the same tag without the label visibility, to tell a label flip from a real change.
                    val shape = tag.copy()
                    shape.remove("CustomNameVisible")
                    stamp()
                    writer.queue.add(
                        EntityItem(
                            t(), e.id, uuid.mostSignificantBits, uuid.leastSignificantBits, BuiltInRegistries.ENTITY_TYPE.getKey(e.type).toString(),
                            e.x, e.y, e.z, e.yRot, e.xRot, nbt(tag), WorldFormat.hash(nbt(shape)),
                        ),
                    )
                } catch (t: Throwable) {
                    entityErrors++
                    lastEntityError = t.toString()
                }
            }
            return worked
        }

        private fun nbt(tag: CompoundTag): ByteArray {
            val bytes = ByteArrayOutputStream(256)
            NbtIo.write(tag, DataOutputStream(bytes))
            return bytes.toByteArray()
        }

        private fun id(state: BlockState): Int {
            states[state]?.let { return it }
            val id = states.size
            states[state] = id
            writer.queue.add(StateItem(id, describe(state)))
            return id
        }

        private fun describe(state: BlockState): String {
            val key = BuiltInRegistries.BLOCK.getKey(state.block).toString()
            val props = state.values.toList().map { it.property().name to it.valueName() }.sortedBy { it.first }
            return if (props.isEmpty()) key else key + props.joinToString(",", "[", "]") { "${it.first}=${it.second}" }
        }

        /** Columns inside the bounds the client never held while capturing, as [cx, cz]. */
        private fun holes(): List<List<Int>> {
            val out = ArrayList<List<Int>>()
            for (z in colMinZ..colMaxZ) for (x in colMinX..colMaxX) if (colKey(x, z) !in seen) out += listOf(x, z)
            return out
        }

        fun describe(): Map<String, Any?> {
            val mc = Minecraft.getInstance()
            val p = mc.player
            val holes = holes()
            var coverage: IntArray? = null
            if (seen.isNotEmpty()) {
                var mnx = Int.MAX_VALUE; var mxx = Int.MIN_VALUE; var mnz = Int.MAX_VALUE; var mxz = Int.MIN_VALUE
                for (k in seen) {
                    val x = (k shr 32).toInt()
                    val z = k.toInt()
                    mnx = minOf(mnx, x); mxx = maxOf(mxx, x); mnz = minOf(mnz, z); mxz = maxOf(mxz, z)
                }
                coverage = intArrayOf(mnx * 16, secMin * 16, mnz * 16, mxx * 16 + 15, secMax * 16 + 15, mxz * 16 + 15)
            }
            val now = System.currentTimeMillis()
            return linkedMapOf(
                "active" to !stopping,
                "stopping" to stopping,
                "label" to options.label,
                "file" to file.toString(),
                "started" to started,
                "seconds" to (now - started) / 1000,
                "dimension" to level.dimension().identifier().toString(),
                "bounds" to linkedMapOf("requested" to requested.toList(), "stored" to stored.toList(), "covered" to coverage?.toList()),
                "columns" to linkedMapOf("total" to columnsTotal, "seen" to seen.size, "queued" to queued.size, "rediff" to rediff.size, "holes" to holes.size),
                "holes" to holes,
                "player" to p?.let { linkedMapOf("x" to it.x, "y" to it.y, "z" to it.z, "cx" to (Mth.floor(it.x) shr 4), "cz" to (Mth.floor(it.z) shr 4)) },
                "sections" to writer.sections,
                "blocks_non_air" to writer.blocksNonAir,
                "states" to writer.states,
                "block_entities" to writer.blockEntities,
                "block_entity_updates" to writer.blockEntityUpdates,
                "entities" to writer.entities,
                "entity_updates" to writer.entityUpdates,
                "entity_updates_capped" to writer.entityUpdatesCapped,
                "entity_visibility_updates" to writer.entityVisibilityUpdates,
                "entity_errors" to entityErrors,
                "last_entity_error" to lastEntityError,
                "changes" to writer.changes,
                "changes_deduped" to writer.changesDeduped,
                "diff_changes" to writer.diffChanges,
                "bytes_raw" to writer.rawBytes,
                "bytes_gz" to writer.compressedBytes,
                "writer_queue" to writer.queue.size,
                "dropped" to dropped,
                "cost" to
                    linkedMapOf(
                        "ticks" to ticks,
                        "work_ticks" to workTicks,
                        "avg_ms" to if (ticks > 0) nanosTotal / 1e6 / ticks else 0.0,
                        "avg_work_tick_ms" to if (workTicks > 0) nanosWork / 1e6 / workTicks else 0.0,
                        "max_ms" to nanosMax / 1e6,
                        "ticks_over_2ms" to over2ms,
                        "budget_ms" to options.budgetMs,
                    ),
                "failure" to writer.failure,
            )
        }

        /** Client thread: freezes the capture and returns the END record's JSON. Idempotent. */
        fun beginStop(reason: String): String {
            endJson?.let { return it }
            stopping = true
            active = false
            flushChanges()
            val data = LinkedHashMap(describe())
            data["reason"] = reason
            data["active"] = false
            data["stopping"] = false
            val text = json(data).toString()
            endJson = text
            endReason = reason
            writer.queue.add(StopItem(text))
            Thread({
                writer.thread.join()
                WorldCapture.finished(data, this)
                logger.info("[Soul/DevBridge] world capture ended ($reason): ${writer.compressedBytes} bytes, ${writer.sections} sections")
            }, "Soul devbridge world close").apply { isDaemon = true }.start()
            return text
        }

        @Volatile private var endJson: String? = null
        @Volatile private var endReason: String? = null

        fun awaitClosed(end: String): Map<String, Any?> {
            writer.thread.join(60_000)
            var waited = 0
            while (WorldCapture.current === this && waited++ < 100) Thread.sleep(50)
            return (kotlinx.serialization.json.Json.parseToJsonElement(end) as kotlinx.serialization.json.JsonObject).toMap()
        }
    }

    private const val MAX_QUEUE = 1500

    private val VOLATILE_ENTITY_KEYS =
        listOf(
            "attributes", "Brain", "Health", "AbsorptionAmount", "DeathTime", "HurtTime", "HurtByTimestamp", "PortalCooldown", "fall_distance", "Air", "OnGround",
            "FallFlying", "current_impulse_context_reset_grace_time", "Fire", "Motion", "active_effects",
        )
}
