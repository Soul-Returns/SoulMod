package com.soulreturns.devbridge

import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.zip.GZIPOutputStream

/**
 * One recording at a time: a [Listener] whose queue a writer thread drains into gzip NDJSON files
 * `<gameDir>/soul-dev/recordings/<time>[_label].rec.ndjson.gz` (`latest.rec.ndjson.gz`).
 * Line 1 is the versioned header; then the backlog, a full snapshot, the live stream ordered by
 * arrival (each line `{t, tick, ping, kind, data}`), a snapshot keyframe every minute, and an `end`
 * line. Flushed every 2 s, so a file is readable while it grows. A finished file is recompressed
 * to zstd off the client thread ([Compress]); the tools read either.
 *
 * Nothing is cut short: a file that reaches [Options.maxMinutes] or [Options.maxMb] (compressed)
 * ends with `end.reason = rollover` and the recording continues in `<name>_partN` (same header
 * format, own snapshot) until [Options.maxTotalMinutes], a stop, or the bridge shutting down.
 * Past [Options.budgetMbPerMin] (uncompressed) within a minute, raw packets are dropped first,
 * then provider frames; chat, menus, titles and marks are always kept. Drops are counted in `end`.
 */
object Recorder {
    const val FORMAT = "soul-devbridge-recording"
    const val VERSION = 2

    private val logger = LoggerFactory.getLogger("Soul/DevBridge")
    private val dir: Path = BridgeInfo.dir.resolve("recordings")
    private val names = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
    private const val SUFFIX = ".rec.ndjson.gz"

    class Options(
        val query: Map<String, String>,
        val label: String?,
        val owner: String,
        val maxMinutes: Double,
        val maxMb: Double,
        val budgetMbPerMin: Double,
        val maxTotalMinutes: Double,
    ) {
        companion object {
            fun parse(query: Map<String, String>, owner: String): Options =
                Options(
                    query,
                    query["label"]?.replace(Regex("[^A-Za-z0-9_.-]+"), "-")?.trim('-')?.ifEmpty { null },
                    owner,
                    query["max_min"]?.toDoubleOrNull()?.coerceIn(0.1, 24 * 60.0) ?: DEFAULT_MAX_MIN,
                    query["max_mb"]?.toDoubleOrNull()?.coerceIn(1.0, 8192.0) ?: DEFAULT_MAX_MB,
                    query["budget_mb_min"]?.toDoubleOrNull()?.coerceIn(1.0, 4096.0) ?: DEFAULT_BUDGET_MB_MIN,
                    query["max_total_min"]?.toDoubleOrNull()?.coerceIn(0.1, 48 * 60.0) ?: DEFAULT_MAX_TOTAL_MIN,
                )
        }
    }

    class Recording(val id: Int, firstFile: Path, val listener: Listener, val options: Options) {
        val started = System.currentTimeMillis()

        /** The file being written now (the first part until a rollover). */
        @Volatile var file: Path = firstFile
        @Volatile var part = 1
        val files = CopyOnWriteArrayList<Path>().apply { add(firstFile) }

        @Volatile var stopReason: String? = null
        @Volatile var rawBytes = 0L
        @Volatile var lines = 0L

        /** Compressed bytes of the current part, and of the parts already closed. */
        @Volatile var partBytes = 0L
        @Volatile var closedBytes = 0L
        val compressedBytes: Long get() = closedBytes + partBytes
        val dropped = HashMap<String, Long>()
        lateinit var thread: Thread

        fun describe(): Map<String, Any?> =
            linkedMapOf(
                "id" to id,
                "file" to file.toString(),
                "part" to part,
                "files" to files.map { it.toString() },
                "owner" to options.owner,
                "started" to started,
                "seconds" to (System.currentTimeMillis() - started) / 1000,
                "lines" to lines,
                "raw_bytes" to rawBytes,
                "compressed_bytes" to compressedBytes,
                "dropped" to synchronized(dropped) { dropped.toMap() },
                "queue_dropped" to listener.droppedTotal.get(),
                "packets" to listener.packetCounts(),
                "caps" to caps(options),
                "filter" to listener.filter.describe(),
                "stopping" to stopReason,
            )
    }

    private fun caps(o: Options) =
        linkedMapOf("max_min" to o.maxMinutes, "max_mb" to o.maxMb, "budget_mb_min" to o.budgetMbPerMin, "max_total_min" to o.maxTotalMinutes)

    @Volatile
    var current: Recording? = null
        private set

    private var ids = 0

    /** Starts a recording, or returns the error text when one already runs. */
    @Synchronized
    fun start(options: Options): Pair<Recording?, String?> {
        current?.let { return null to "already recording #${it.id} to ${it.file} (owner ${it.options.owner}); stop it first" }
        Files.createDirectories(dir)
        val name = LocalDateTime.now().format(names) + (options.label?.let { "_$it" } ?: "") + SUFFIX
        val listener = Listener(++ids, Filter.parse(options.query, recording = true), "recording")
        val rec = Recording(ids, dir.resolve(name), listener, options)
        current = rec
        rec.thread = Thread({ write(rec) }, "Soul devbridge recorder").apply { isDaemon = true }
        rec.thread.start()
        Bridge.emit("record") { json(mapOf("op" to "start", "id" to rec.id, "file" to rec.file.toString(), "owner" to options.owner)) }
        logger.info("[Soul/DevBridge] recording #${rec.id} to ${rec.file}")
        return rec to null
    }

    /** Ends the current recording (waits ≤5 s for the file to close). Returns its final state. */
    fun stop(reason: String): Map<String, Any?>? {
        val rec = current ?: return null
        rec.stopReason = reason
        rec.thread.join(5_000)
        return rec.describe() + ("stopped" to reason)
    }

    fun describe(): Map<String, Any?>? = current?.describe()

    private class Counting(out: OutputStream, val rec: Recording) : FilterOutputStream(out) {
        override fun write(b: Int) {
            out.write(b)
            rec.partBytes++
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
            out.write(b, off, len)
            rec.partBytes += len
        }
    }

    private fun open(rec: Recording): GZIPOutputStream {
        rec.partBytes = 0
        return GZIPOutputStream(Counting(BufferedOutputStream(Files.newOutputStream(rec.file), 1 shl 16), rec), 1 shl 16, true)
    }

    /** `<first>_partN.rec.ndjson.gz` next to the first file. */
    private fun partFile(rec: Recording, n: Int): Path {
        val first = rec.files.first().fileName.toString().removeSuffix(SUFFIX)
        return rec.files.first().resolveSibling("${first}_part$n$SUFFIX")
    }

    private fun write(rec: Recording) {
        val opts = rec.options
        var gz: GZIPOutputStream? = null
        try {
            gz = open(rec)
            fun put(line: String) {
                val bytes = (line + "\n").toByteArray(StandardCharsets.UTF_8)
                gz!!.write(bytes)
                rec.rawBytes += bytes.size
                rec.lines++
            }
            put(header(rec, null))
            val filter = rec.listener.filter
            if (filter.backlog) {
                val backlog = Backlog.lines()
                put(Bridge.line("backlog", json(mapOf("events" to backlog.size, "note" to "events from before the recording started"))))
                backlog.forEach(::put)
            }
            put(snapshotLine(filter))
            Bridge.add(rec.listener)
            var windowStart = System.currentTimeMillis()
            var windowBytes = 0L
            var lastFlush = windowStart
            var lastKeyframe = windowStart
            var partStart = windowStart
            val budget = (opts.budgetMbPerMin * 1024 * 1024).toLong()
            val maxBytes = (opts.maxMb * 1024 * 1024).toLong()
            val maxPartMs = (opts.maxMinutes * 60_000).toLong()
            val maxTotalMs = (opts.maxTotalMinutes * 60_000).toLong()
            while (true) {
                val line = rec.listener.poll(250, TimeUnit.MILLISECONDS)
                val now = System.currentTimeMillis()
                if (line != null) {
                    if (now - windowStart >= 60_000) {
                        windowStart = now
                        windowBytes = 0
                    }
                    val kind = kindOf(line)
                    val over = windowBytes > budget
                    val drop = over && (kind == "packet" || (windowBytes > budget * 3 / 2 && Providers.get(kind) != null))
                    if (drop) {
                        synchronized(rec.dropped) { rec.dropped.merge(kind, 1L, Long::plus) }
                    } else {
                        put(line)
                        windowBytes += line.length + 1
                    }
                }
                if (now - lastKeyframe >= KEYFRAME_MS) {
                    lastKeyframe = now
                    put(snapshotLine(filter))
                }
                if (now - lastFlush >= FLUSH_MS) {
                    lastFlush = now
                    gz!!.flush()
                }
                if (rec.stopReason == null) {
                    if (now - rec.started >= maxTotalMs) rec.stopReason = "max total duration ${opts.maxTotalMinutes} min"
                    else if (!Gate.on) rec.stopReason = "bridge disabled"
                    else if (now - partStart >= maxPartMs || rec.partBytes >= maxBytes) {
                        val closed = rec.file
                        val next = partFile(rec, rec.part + 1)
                        put(Bridge.line("end", json(rec.describe() + ("reason" to "rollover") + ("next" to next.toString()))))
                        gz!!.close()
                        rec.closedBytes += rec.partBytes
                        Compress.submit(closed)
                        rec.part++
                        rec.file = next
                        rec.files.add(next)
                        gz = open(rec)
                        partStart = now
                        lastFlush = now
                        lastKeyframe = now
                        put(header(rec, closed))
                        put(snapshotLine(filter))
                        logger.info("[Soul/DevBridge] recording #${rec.id} rolled over to $next")
                        Bridge.emit("record") { json(mapOf("op" to "rollover", "id" to rec.id, "file" to next.toString(), "previous" to closed.toString())) }
                    }
                }
                if (rec.stopReason != null && (line == null || rec.listener.queue.isEmpty())) break
            }
            Bridge.remove(rec.listener)
            while (true) put(rec.listener.poll() ?: break)
            put(Bridge.line("end", json(rec.describe() + ("reason" to rec.stopReason))))
        } catch (t: Throwable) {
            rec.stopReason = rec.stopReason ?: "writer failed: $t"
            logger.warn("[Soul/DevBridge] recording #${rec.id} failed: $t")
            Bridge.remove(rec.listener)
        } finally {
            runCatching { gz?.close() }
            rec.closedBytes += rec.partBytes
            rec.partBytes = 0
            runCatching {
                Files.deleteIfExists(dir.resolve("latest.rec.ndjson.zst"))
                val latest = dir.resolve("latest.rec.ndjson.gz")
                Files.deleteIfExists(latest)
                Files.createSymbolicLink(latest, rec.file.fileName)
            }
            synchronized(this) { if (current === rec) current = null }
            Compress.submit(rec.file)
            Bridge.emit("record") { json(mapOf("op" to "stop", "id" to rec.id, "file" to rec.file.toString(), "reason" to rec.stopReason, "compressed_bytes" to rec.compressedBytes)) }
            logger.info("[Soul/DevBridge] recording #${rec.id} ended (${rec.stopReason}): ${rec.compressedBytes} bytes in ${rec.files.size} file(s)")
        }
    }

    private fun header(rec: Recording, previous: Path?): String {
        val mc = Minecraft.getInstance()
        return Bridge.line(
            "header",
            json(
                linkedMapOf(
                    "format" to FORMAT,
                    "version" to VERSION,
                    "started" to rec.started,
                    "player" to mc.user.name,
                    "server" to mc.currentServer?.ip,
                    "part" to rec.part,
                    "previous" to previous?.toString(),
                    "client" to BridgeInfo.describe(0, null),
                    "tick_source" to Ticks.describe(),
                    "providers" to Providers.describe(),
                    "filter" to rec.listener.filter.describe(),
                    "caps" to caps(rec.options),
                    "line" to "{t: wall ms, tick: server game time from set_time, interpolated between packets (THE clock), ping: raw ping count (diagnostic; null before the first ping), kind, data}",
                ),
            ),
        )
    }

    private fun snapshotLine(filter: Filter): String =
        try {
            Bridge.line("snapshot", Providers.snapshot(filter))
        } catch (t: Throwable) {
            Bridge.line("snapshot", JsonObject(mapOf("error" to json(t.toString()))))
        }

    /** `{"t":…,"tick":…,"ping":…,"kind":"X",…` → X without parsing the line. */
    private fun kindOf(line: String): String {
        val i = line.indexOf("\"kind\":\"")
        if (i < 0) return ""
        val start = i + 8
        return line.substring(start, line.indexOf('"', start))
    }

    const val DEFAULT_MAX_MIN = 60.0
    const val DEFAULT_MAX_MB = 1024.0
    const val DEFAULT_BUDGET_MB_MIN = 96.0
    const val DEFAULT_MAX_TOTAL_MIN = 480.0
    private const val FLUSH_MS = 2_000L
    private const val KEYFRAME_MS = 60_000L
}
