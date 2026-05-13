package com.soulreturns.util

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.BufferedWriter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Date
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Dedicated Soul-only log file at `config/soul/logs/soul-latest.log`.
 *
 * Every [SoulLogger] call is teed here in addition to its normal SLF4J output. This gives
 * us a clean Soul-only view that doesn't have to be grep'd out of Minecraft's `latest.log`.
 *
 * **Lifecycle:**
 *   - [init] runs first thing in [com.soulreturns.Soul.onInitializeClient] (before config
 *     loads), so startup logs are captured. The previous session's `soul-latest.log` is
 *     rotated to a dated file; the oldest historical files past [MAX_HISTORICAL] are deleted.
 *   - A single daemon thread ([WRITER_THREAD_NAME]) drains a [LinkedBlockingQueue] and writes
 *     line-by-line. Enqueue is non-blocking, so a slow disk never stalls game threads.
 *   - On `CLIENT_STOPPING`, the queue is drained synchronously (best-effort, 1 s budget) so
 *     last-second log lines actually make it to disk.
 *
 * **Format** (one line per entry, plus inline stack trace if a [Throwable] is supplied):
 * ```
 * [2026-05-13 13:34:00.123] [soul-sync/INFO] [Soul/Sync] config: pushed (1234 bytes)
 * ```
 *
 * The bracketed style mirrors Minecraft's `latest.log` so the file feels familiar. Column
 * padding doesn't work here because some thread names contain spaces (`Render thread`),
 * which would visually merge with the next column.
 *
 * Toggle: `cfg.dev.debug.logToFile()` (default on). When off we still drain the queue (so
 * it can't grow unbounded) but skip the write — keeps the path consistent.
 */
internal object SoulFileLog {
    private const val MAX_HISTORICAL = 10
    private const val WRITER_THREAD_NAME = "soul-log-writer"
    private const val LATEST_NAME = "soul-latest.log"
    private val HISTORICAL_NAME = SimpleDateFormat("'soul-'yyyy-MM-dd-HHmmss'.log'")
    private val LINE_TS_FORMATTER: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    private data class Entry(
        val timestamp: Long,
        val level: String,
        val thread: String,
        val tag: String,
        val message: String,
        val throwable: Throwable?,
    )

    /** Tombstone — when the writer thread dequeues this, it flushes and exits. */
    private val POISON =
        Entry(
            timestamp = 0L,
            level = "",
            thread = "",
            tag = "",
            message = "",
            throwable = null,
        )

    private val queue = LinkedBlockingQueue<Entry>()
    private val started = AtomicBoolean(false)

    @Volatile private var writer: BufferedWriter? = null

    @Volatile private var writerThread: Thread? = null

    /**
     * Open the log file, rotate the previous session, and start the writer thread. Safe to
     * call exactly once at mod init; subsequent calls are no-ops. Never throws — file-log
     * failure must not break mod startup.
     */
    fun init() {
        if (!started.compareAndSet(false, true)) return
        try {
            val logDir = File(FabricLoader.getInstance().configDir.toFile(), "soul/logs")
            if (!logDir.exists() && !logDir.mkdirs()) return
            val latest = File(logDir, LATEST_NAME)
            rotatePreviousSession(latest, logDir)
            trimHistorical(logDir)
            writer = latest.bufferedWriter(Charsets.UTF_8)
            val t =
                Thread({ drainLoop() }, WRITER_THREAD_NAME).apply {
                    isDaemon = true
                }
            writerThread = t
            t.start()

            ClientLifecycleEvents.CLIENT_STOPPING.register(
                ClientLifecycleEvents.ClientStopping { _ -> shutdown() }
            )
        } catch (_: Throwable) {
            // Disable the queue path so SoulLogger doesn't pile entries forever.
            started.set(false)
            writer = null
        }
    }

    /** Called by every [SoulLogger] method. Non-blocking; queues for the writer thread. */
    fun offer(
        level: String,
        tag: String,
        message: String,
        throwable: Throwable?,
    ) {
        if (!started.get()) return
        val entry =
            Entry(
                timestamp = System.currentTimeMillis(),
                level = level,
                thread = Thread.currentThread().name,
                tag = tag,
                message = message,
                throwable = throwable,
            )
        queue.offer(entry)
    }

    private fun rotatePreviousSession(
        latest: File,
        logDir: File,
    ) {
        if (!latest.exists() || latest.length() == 0L) return
        // Stamp the rotation by the previous session's last-modified time.
        val rotatedName = HISTORICAL_NAME.format(Date(latest.lastModified()))
        var target = File(logDir, rotatedName)
        var suffix = 1
        // Collision-resistant: if a same-named file exists (rare; same-second restart), bump.
        while (target.exists()) {
            target = File(logDir, rotatedName.removeSuffix(".log") + "-$suffix.log")
            suffix++
        }
        latest.renameTo(target)
    }

    private fun trimHistorical(logDir: File) {
        val historical =
            logDir.listFiles { f ->
                f.isFile &&
                    f.name.startsWith("soul-") &&
                    f.name.endsWith(".log") &&
                    f.name != LATEST_NAME
            } ?: return
        if (historical.size <= MAX_HISTORICAL) return
        historical
            .sortedByDescending { it.lastModified() }
            .drop(MAX_HISTORICAL)
            .forEach { runCatching { it.delete() } }
    }

    private fun drainLoop() {
        while (true) {
            val entry =
                try {
                    queue.take()
                } catch (_: InterruptedException) {
                    break
                }
            if (entry === POISON) break
            writeEntry(entry)
        }
        runCatching { writer?.flush() }
        runCatching { writer?.close() }
    }

    private fun writeEntry(entry: Entry) {
        val w = writer ?: return
        // Re-check the toggle inside the writer so a runtime flip takes effect immediately.
        val toFile =
            try {
                com.soulreturns.config.SoulConfigHolder.isConfigReady() &&
                    com.soulreturns.config.cfg.dev.debug.logToFile()
            } catch (_: Throwable) {
                // Early-startup logs (before config is ready) always land in the file.
                true
            }
        if (!toFile) return
        try {
            val ts =
                LINE_TS_FORMATTER.format(
                    LocalDateTime.ofInstant(
                        Instant.ofEpochMilli(entry.timestamp),
                        ZoneId.systemDefault(),
                    )
                )
            w.write("[$ts] [${entry.thread}/${entry.level}] [${entry.tag}] ${entry.message}")
            w.newLine()
            entry.throwable?.let { t ->
                val sw = StringWriter()
                t.printStackTrace(PrintWriter(sw))
                w.write(sw.toString())
            }
            w.flush()
        } catch (_: Throwable) {
            // I/O failure on a single line shouldn't kill the writer thread.
        }
    }

    private fun shutdown() {
        if (!started.compareAndSet(true, false)) return
        queue.offer(POISON)
        try {
            writerThread?.join(TimeUnit.SECONDS.toMillis(1))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }
}
