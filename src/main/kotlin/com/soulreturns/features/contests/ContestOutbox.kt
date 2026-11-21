package com.soulreturns.features.contests

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.config.DataBackups
import com.soulreturns.features.contests.model.RunUpload
import com.soulreturns.features.contests.model.RunUploadResponse
import com.soulreturns.features.contests.model.UploadItem
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/** One of your runs on disk: not sent yet, or rejected by the server ([rejected], with the reason). */
data class QueuedRun(
    val contestId: Long,
    val runId: String,
    val seq: Int?,
    val finishedAt: Long,
    val startedAt: Long?,
    val durationMs: Long?,
    val partial: Boolean,
    val items: List<UploadItem>,
    val powder: Map<String, Long>,
    val queuedAt: Long,
    val attempts: Int,
    val rejected: Boolean,
    /** Contract error code of the rejection (`outside_window`, `profile_mismatch`, …). */
    val rejectCode: String?,
    /** A sentence for the UI. */
    val rejectMessage: String?,
)

/**
 * A queued upload. [run] is the request body byte for byte, so a replay cannot drift from the first
 * attempt (the server keeps the first write of a `runId`).
 */
@Serializable
internal data class OutboxEntry(
    val contestId: Long,
    val queuedAt: Long,
    val run: RunUpload,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0L,
    val rejectedCode: String? = null,
    val rejectedAt: Long? = null,
)

/** `config/soul/contest_outbox.json`. [seqs] = the next client `seq` per contest. */
@Serializable
internal data class ContestOutboxFile(
    val schemaVersion: Int = ContestOutbox.SCHEMA_VERSION,
    val entries: List<OutboxEntry> = emptyList(),
    val seqs: Map<String, Int> = emptyMap(),
    /** Contests you play in (lobby/running), so capture works before the first snapshot of a session. */
    val targets: List<CaptureTarget> = emptyList(),
    /** Contests whose end was already announced on this computer (newest last), so a restart does not replay it. */
    val endsShown: List<Long> = emptyList(),
)

/**
 * The disk queue of contest runs, modeled on [com.soulreturns.features.mining.mineshaft.MineshaftOutbox]:
 * - **Synchronous write, async send.** [submit] persists before it returns (`SoulScope` is cancelled at
 *   `CLIENT_STOPPING`), so a run survives a quit or crash right after the bundle.
 * - **Idempotent.** Each run carries a client `runId`; the server answers a replay with 200 and the stored
 *   run, so retrying is free.
 * - **Retries with backoff** (1 min doubling to 30 min) on transport errors, 5xx and 401; a 429 waits for
 *   `Retry-After`. One transient failure ends the flush (at most one re-auth per flush).
 * - **Only a definitive rejection drops a run from the queue** (every 4xx except 401/429, contract 5):
 *   it stays in the file as rejected, with the code, so the UI can show it; rejected entries are pruned
 *   after [REJECTED_KEEP_MS].
 * - Production only: capture never queues on Alpha, and nothing is sent while on Alpha.
 */
object ContestOutbox {
    private val logger = SoulLogger("Soul/Contests")
    const val SCHEMA_VERSION = 1

    private const val MAX_ENTRIES = 500
    private const val FIRST_BACKOFF_MS = 60_000L
    private const val MAX_BACKOFF_MS = 30 * 60_000L

    /** Contests last at most 7 days plus 24 h of late uploads: anything older can never be accepted. */
    private const val MAX_PENDING_AGE_MS = 9L * 24 * 3_600_000L
    private const val REJECTED_KEEP_MS = 7L * 24 * 3_600_000L
    private const val MAX_ENDS_SHOWN = 100

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; coerceInputValues = true }

    private val stateLock = Any()
    private val drainLock = Mutex()

    @Volatile private var file = ContestOutboxFile()
    @Volatile private var loaded = false

    @Volatile var lastOutcome: String = "not flushed yet"
        private set

    // ------------------------------------------------------------------ seams for tests

    internal var path: () -> Path = { FabricLoader.getInstance().configDir.resolve("soul").resolve("contest_outbox.json") }
    internal var now: () -> Long = { System.currentTimeMillis() }
    internal var send: suspend (Long, RunUpload) -> ContestsApi.Reply<RunUploadResponse> = { id, body -> ContestsApi.uploadRun(id, body) }
    internal var sendingAllowed: () -> Boolean = { !HypixelNetwork.isAlpha }
    internal var onChange: () -> Unit = { ContestsStore.post { ContestsStore.changed() } }
    internal var onUploaded: (Long, RunUploadResponse) -> Unit = { id, resp -> ContestsStore.post { ContestsStore.onRunUploaded(id, resp) } }
    internal var launcher: (suspend () -> Unit) -> Unit = { block -> SoulScope.io.launch { block() } }

    // ------------------------------------------------------------------ reads

    fun pending(): Int = file.entries.count { it.rejectedCode == null }

    fun entries(contestId: Long): List<QueuedRun> = file.entries.filter { it.contestId == contestId }.map(::view)

    fun all(): List<QueuedRun> = file.entries.map(::view)

    private fun view(e: OutboxEntry) = QueuedRun(
        contestId = e.contestId,
        runId = e.run.runId,
        seq = e.run.seq,
        finishedAt = e.run.finishedAt,
        startedAt = e.run.startedAt,
        durationMs = e.run.durationMs,
        partial = e.run.partial,
        items = e.run.items,
        powder = e.run.powder,
        queuedAt = e.queuedAt,
        attempts = e.attempts,
        rejected = e.rejectedCode != null,
        rejectCode = e.rejectedCode,
        rejectMessage = e.rejectedCode?.let(ContestErrors::describe),
    )

    // ------------------------------------------------------------------ load / persist

    fun load() {
        synchronized(stateLock) {
            if (loaded) return
            loaded = true
            val p = path()
            if (!Files.exists(p)) return
            file = try {
                json.decodeFromString(ContestOutboxFile.serializer(), Files.readString(p))
            } catch (t: Throwable) {
                DataBackups.backup(p, "unreadable")
                logger.warn("Could not read the contest outbox (${t.message}); starting empty")
                ContestOutboxFile()
            }
            if (file.schemaVersion > SCHEMA_VERSION) {
                // A newer Soul wrote it: keep its file and its runs, send what we can read.
                DataBackups.backup(p, "newer-v${file.schemaVersion}")
            }
            if (pending() > 0) logger.info("Contest outbox: ${pending()} run(s) waiting")
        }
    }

    /** Must be called under [stateLock]. */
    private fun persist() {
        val p = path()
        try {
            Files.createDirectories(p.parent)
            if (file.entries.isEmpty() && file.seqs.isEmpty() && file.targets.isEmpty() && file.endsShown.isEmpty()) {
                Files.deleteIfExists(p)
                return
            }
            val tmp = p.resolveSibling(p.fileName.toString() + ".tmp")
            Files.writeString(tmp, json.encodeToString(ContestOutboxFile.serializer(), file.copy(schemaVersion = maxOf(file.schemaVersion, SCHEMA_VERSION))))
            Files.move(tmp, p, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
        } catch (t: Throwable) {
            logger.warn("Could not write the contest outbox: ${t.message}")
        }
    }

    // ------------------------------------------------------------------ queue

    /** Capture targets kept on disk (see [CaptureTarget]). */
    internal fun targets(): List<CaptureTarget> {
        load()
        return file.targets
    }

    /** Upsert (or remove, with null) the capture target of [contestId]. */
    internal fun setTarget(contestId: Long, target: CaptureTarget?) {
        load()
        synchronized(stateLock) {
            val rest = file.targets.filter { it.contestId != contestId }
            file = file.copy(targets = if (target == null) rest else rest + target)
            persist()
        }
    }

    /** True once the end of [contestId] was announced here (chat line, toast, reveal). */
    internal fun endShown(contestId: Long): Boolean {
        load()
        return contestId in file.endsShown
    }

    internal fun markEndShown(contestId: Long) {
        load()
        synchronized(stateLock) {
            if (contestId in file.endsShown) return
            file = file.copy(endsShown = (file.endsShown + contestId).takeLast(MAX_ENDS_SHOWN))
            persist()
        }
    }

    /** The next client `seq` for [contestId] (1-based), reserved and persisted with the run in [submit]. */
    internal fun nextSeq(contestId: Long): Int {
        load()
        return synchronized(stateLock) { (file.seqs[contestId.toString()] ?: 0) + 1 }
    }

    /** Record [run] for [contestId] now (synchronously), then try to send in the background. */
    fun submit(contestId: Long, run: RunUpload) {
        load()
        synchronized(stateLock) {
            if (file.entries.any { it.contestId == contestId && it.run.runId == run.runId }) return
            val seqs = file.seqs + (contestId.toString() to maxOf(run.seq ?: 0, file.seqs[contestId.toString()] ?: 0))
            file = file.copy(entries = (file.entries + OutboxEntry(contestId, now(), run)).takeLast(MAX_ENTRIES), seqs = seqs)
            persist()
        }
        onChange()
        flush()
    }

    /** Send every due run in the background. */
    fun flush(force: Boolean = false) {
        load()
        if (!force && file.entries.none { due(it) }) return
        launcher { drain(force) }
    }

    /** Due now (pending and past its backoff). */
    private fun due(e: OutboxEntry): Boolean = e.rejectedCode == null && e.nextAttemptAt <= now()

    internal suspend fun drain(force: Boolean = false) {
        if (!sendingAllowed()) return
        drainLock.withLock {
            prune()
            var sent = 0
            while (true) {
                val head = synchronized(stateLock) {
                    file.entries.firstOrNull { it.rejectedCode == null && (force || it.nextAttemptAt <= now()) }
                } ?: break
                val reply = send(head.contestId, head.run)
                val value = reply.value
                if (value != null) {
                    update(head) { null }
                    sent++
                    onUploaded(head.contestId, value)
                    continue
                }
                val status = reply.status
                if (isPermanent(status)) {
                    val code = reply.error?.error ?: "http_$status"
                    logger.warn("Contest run ${head.run.runId} rejected ($status $code)")
                    update(head) { it.copy(rejectedCode = code, rejectedAt = now()) }
                    continue
                }
                val wait = if (status == 429) {
                    reply.retryAfterMs ?: FIRST_BACKOFF_MS
                } else {
                    (FIRST_BACKOFF_MS shl head.attempts.coerceAtMost(5)).coerceAtMost(MAX_BACKOFF_MS)
                }
                update(head) { it.copy(attempts = it.attempts + 1, nextAttemptAt = now() + wait) }
                lastOutcome = "paused after $sent sent: $status ${reply.error?.error} (${pending()} waiting)"
                return
            }
            lastOutcome = "sent $sent, ${pending()} waiting"
        }
    }

    /** Contract 5: every 4xx except 401/429 is permanent for that run. */
    internal fun isPermanent(status: Int): Boolean = status in 400..499 && status != 401 && status != 429

    private fun update(entry: OutboxEntry, change: (OutboxEntry) -> OutboxEntry?) {
        synchronized(stateLock) {
            file = file.copy(entries = file.entries.mapNotNull { if (it.contestId == entry.contestId && it.run.runId == entry.run.runId) change(it) else it })
            persist()
        }
        onChange()
    }

    private fun prune() {
        val t = now()
        synchronized(stateLock) {
            val keep = file.entries.filter { e ->
                if (e.rejectedCode != null) t - (e.rejectedAt ?: e.queuedAt) < REJECTED_KEEP_MS
                else t - e.run.finishedAt < MAX_PENDING_AGE_MS
            }
            if (keep.size == file.entries.size) return
            logger.warn("Contest outbox: pruned ${file.entries.size - keep.size} old run(s)")
            file = file.copy(entries = keep)
            persist()
        }
        onChange()
    }

    /** Tests: forget the in-memory state so the next call reads [path] again. */
    internal fun resetForTest() {
        synchronized(stateLock) {
            file = ContestOutboxFile()
            loaded = false
        }
    }
}
