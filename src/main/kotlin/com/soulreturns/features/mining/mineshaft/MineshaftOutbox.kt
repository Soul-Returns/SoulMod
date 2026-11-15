package com.soulreturns.features.mining.mineshaft

import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.SoulLogger
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant

/** One corpse type's counts for a visit. `found` = looted + unlooted, so `looted <= found` always. */
@Serializable
data class CorpseCount(val found: Int, val looted: Int)

/**
 * The body of `POST /mineshaft/visit`, and simultaneously the record the [MineshaftOutbox] stores on
 * disk. Deliberately one type: a queued visit is not a summary of a request to be rebuilt later, it
 * **is** the request, byte for byte, so a replay cannot drift from what the original attempt sent.
 * (The backend keeps the first write on a duplicate `visitId` rather than merging, which only works
 * as a contract because the two bodies are identical.)
 *
 * No property declares a default except [warpedBy], because `kotlinx.serialization` omits a property
 * equal to its declared default and the contract requires every other key to be present. [warpedBy] is
 * the one field the backend accepts only when [warped] is true, so omitting it when absent is exactly
 * right.
 *
 * [source] carries the three-state classification (`"discovered" | "warped" | "unknown"`, see
 * [VisitSource]) and [warped] is derived from it (`source == "warped"`) for back-compat. The backend
 * does not reject a disagreement between the two — it treats `source` as authoritative and stores
 * `warped` verbatim — so keeping them consistent is **our** invariant and nothing will report a
 * violation. It is derived in exactly one place, in [MineshaftVisitTracker], for that reason.
 */
@Serializable
data class MineshaftVisitPayload(
    val visitId: String,
    val mineshaftType: String,
    val serverInstance: String,
    val enteredAt: String,
    val leftAt: String,
    val corpses: Map<String, CorpseCount>,
    val littlefootFound: Boolean,
    val source: String,
    val warped: Boolean,
    val warpedBy: String? = null,
)

@Serializable
private data class OutboxFile(val visits: List<MineshaftVisitPayload> = emptyList())

/**
 * An on-disk queue of mineshaft visits that have not reached the backend yet.
 *
 * The legacy mod's comment was explicit: *"on failure we log a warning and drop the visit — better to
 * lose one data point than to retry / queue locally."* That was the right call **only because there
 * was no dedup key** — a retry could silently double-count a visit, and mineshaft data is odds data,
 * where a duplicate is worse than a gap. Now every visit carries a `visitId` and the backend answers a
 * repeat with `200` and the existing row instead of inserting, so retrying is free and dropping is not:
 * a backend blip losing a week of grind is the kind of thing you notice weeks later when the odds look
 * wrong and cannot reconstruct.
 *
 * ### Rules that keep this from becoming its own problem
 *  - **The queue write is synchronous; only the POST is async.** A visit is recorded before
 *    [submit] returns, so a client that quits straight out of a mineshaft still has it: `SoulScope` is
 *    cancelled at `CLIENT_STOPPING`, and a queue write deferred onto it would simply never run.
 *  - **Cap at [MAX_ENTRIES], dropping oldest.** A client that never reaches the backend must not grow
 *    a file without bound, and the newest visits are the ones worth keeping.
 *  - **Drop what the backend can never accept.** `enteredAt` older than [MAX_AGE] fails the endpoint's
 *    24 h window forever, and a non-429 `4xx` is a permanent verdict on that payload. Either would
 *    otherwise wedge the head of the queue in front of everything behind it.
 *  - **Stop the flush on the first transient failure.** `/authenticate` is capped at **5 successful
 *    auths per uuid per hour**; a queue of 200 visits each triggering the `401` re-auth path would burn
 *    that budget in one go. One flush therefore costs at most one re-auth.
 *
 * Two locks, because they guard different things: [stateLock] is a plain monitor around the queue and
 * its file (short, non-suspending, callable from the client thread), and [drainLock] makes sure only
 * one coroutine is walking the queue over the network at a time.
 */
object MineshaftOutbox {
    private val logger = SoulLogger("Soul/Mineshaft")

    private const val ENDPOINT = "/mineshaft/visit"

    const val INTENT = "mineshaft-visit"

    /** Newest-wins cap. 200 visits is weeks of grinding; past that the oldest are the least useful. */
    private const val MAX_ENTRIES = 200

    /** The endpoint rejects an `enteredAt` older than 24 h, so a queued visit past that is dead. */
    private val MAX_AGE: Duration = Duration.ofHours(24)

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }

    private val stateLock = Any()
    private val drainLock = Mutex()

    @Volatile
    private var queue: List<MineshaftVisitPayload> = emptyList()

    @Volatile
    private var loaded = false

    /** Last flush outcome, for `/soul mineshaft` — a silently stuck queue is otherwise invisible. */
    @Volatile
    var lastOutcome: String = "not flushed yet"
        private set

    /** How many visits are waiting. */
    fun pending(): Int = queue.size

    private fun file(): Path = FabricLoader.getInstance().configDir.resolve("soul").resolve("mineshaft_outbox.json")

    /** Read the queue from disk. Safe to call more than once; only the first call touches the file. */
    fun load() {
        synchronized(stateLock) {
            if (loaded) return
            loaded = true
            val path = file()
            if (!Files.exists(path)) return
            queue = try {
                json.decodeFromString(OutboxFile.serializer(), Files.readString(path)).visits
            } catch (t: Throwable) {
                // A corrupt outbox is not worth failing startup over, and not worth keeping either.
                logger.warn("Could not read the mineshaft outbox (${t.message}); starting empty")
                emptyList()
            }
            if (queue.isNotEmpty()) logger.info("Mineshaft outbox: ${queue.size} visit(s) waiting")
        }
    }

    /**
     * Record [payload] **now**, then try to drain the whole queue in the background. The write happens
     * before the POST, so a crash between the two costs a duplicate attempt (harmless — the `visitId`
     * makes it idempotent) rather than a lost visit.
     */
    fun submit(payload: MineshaftVisitPayload) {
        load()
        synchronized(stateLock) {
            queue = (queue + payload).takeLast(MAX_ENTRIES)
            persist()
        }
        flush()
    }

    /** Try to send everything queued. No-op when the queue is empty. */
    fun flush() {
        if (loaded && queue.isEmpty()) return
        SoulScope.io.launch {
            load()
            drain()
        }
    }

    private suspend fun drain() {
        drainLock.withLock {
            val expired = synchronized(stateLock) {
                val cutoff = Instant.now().minus(MAX_AGE)
                val before = queue.size
                queue = queue.filterNot { isExpired(it, cutoff) }
                val dropped = before - queue.size
                if (dropped > 0) persist()
                dropped
            }
            if (expired > 0) logger.warn("Mineshaft outbox: dropped $expired visit(s) past the backend's 24 h window")

            var sent = 0
            // Oldest first: the queue is a log, and a backend that starts accepting again should get
            // it in the order it happened.
            while (true) {
                val head = synchronized(stateLock) { queue.firstOrNull() } ?: break
                val result = BackendClient.post(
                    endpoint = ENDPOINT,
                    body = head,
                    bodySerializer = MineshaftVisitPayload.serializer(),
                    intent = INTENT,
                )
                if (result is BackendResult.Ok) {
                    dropHead()
                    sent++
                    continue
                }
                val failure = result as BackendResult.Failure
                if (isPermanent(failure.status)) {
                    // The backend has judged this exact payload and will judge it the same way
                    // forever. Keeping it would block every visit behind it.
                    logger.warn("Mineshaft visit ${head.visitId} rejected permanently (${failure.status}): ${failure.message}")
                    dropHead()
                    continue
                }
                // Transient: stop here and keep the rest. See the KDoc — one flush, at most one re-auth.
                lastOutcome = "paused after $sent sent: ${failure.status} ${failure.message} (${queue.size} still queued)"
                logger.warn("Mineshaft outbox paused: ${failure.status} ${failure.message} (${queue.size} queued)")
                return
            }

            lastOutcome = "sent $sent, queue empty" + if (expired > 0) " ($expired expired)" else ""
            if (sent > 0) logger.info("Mineshaft outbox: sent $sent visit(s)")
        }
    }

    private fun dropHead() {
        synchronized(stateLock) {
            queue = queue.drop(1)
            persist()
        }
    }

    /**
     * A verdict the backend will repeat forever. `401` is deliberately **not** in here: it means our
     * bearer is stale, which the next flush may well fix, and treating it as permanent would throw
     * away visits over a transient auth problem.
     */
    private fun isPermanent(status: Int): Boolean = status in 400..499 && status != 401 && status != 429

    private fun isExpired(
        payload: MineshaftVisitPayload,
        cutoff: Instant,
    ): Boolean =
        try {
            Instant.parse(payload.enteredAt).isBefore(cutoff)
        } catch (_: Throwable) {
            true // unparsable timestamp — the backend would reject it anyway
        }

    /** Must be called under [stateLock]. */
    private fun persist() {
        val path = file()
        try {
            Files.createDirectories(path.parent)
            if (queue.isEmpty()) {
                Files.deleteIfExists(path)
                return
            }
            Files.writeString(path, json.encodeToString(OutboxFile.serializer(), OutboxFile(queue)))
        } catch (t: Throwable) {
            logger.warn("Could not write the mineshaft outbox: ${t.message}")
        }
    }
}
