package com.soulreturns.features.islandchests

import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

@Serializable
internal data class ChestWireItem(
    val material: String,
    val skyblockId: String? = null,
    val displayNameRaw: String? = null,
    val loreLines: List<String> = emptyList(),
    val count: Int,
) {
    fun stored() = StoredChestItem(material, skyblockId, displayNameRaw, loreLines, count)
    fun valid(): Boolean = material.utf8AtMost(128) && material.isNotBlank() &&
        (skyblockId == null || skyblockId.utf8AtMost(256)) && (displayNameRaw == null || displayNameRaw.utf8AtMost(1024)) &&
        loreLines.size <= 64 && loreLines.all { it.utf8AtMost(2048) } && count > 0
}

@Serializable
internal data class ChestWireChest(
    val key: String,
    val blocks: List<ChestPos>,
    val blockId: String,
    val slots: List<ChestWireItem?>,
    val observerUuid: String,
    val observerName: String? = null,
    val observedAt: Long,
    val receivedAt: Long,
    val revision: Long,
) {
    fun stored() = StoredIslandChest(key, blocks, blockId, slots.map { it?.stored() }, observerUuid, observerName, observedAt, revision, "shared")
    fun valid(globalRevision: Long): Boolean = validChestShape(blockId, blocks, slots.size) && key == chestKey(blocks) &&
        revision in 1..globalRevision && observedAt in 1..253402300799999L && MojangUuid.undashedOrNull(observerUuid) != null &&
        slots.all { it == null || it.valid() }
}

@Serializable
internal data class ChestWireSnapshot(
    val schemaVersion: Int,
    val profileUuid: String,
    val revision: Long,
    val unchanged: Boolean,
    val chests: List<ChestWireChest>? = null,
    val nextCursor: String? = null,
) {
    fun valid(profile: String): Boolean {
        if (schemaVersion != 2 || MojangUuid.undashedOrNull(profileUuid) != profile || revision < 0) return false
        if (unchanged) return chests == null && nextCursor == null
        val rows = chests ?: return false
        if (rows.size > 32 || (nextCursor != null && (rows.isEmpty() || nextCursor != "$revision:${rows.last().key}"))) return false
        val blocks = HashSet<ChestPos>()
        return rows.all { it.valid(revision) && it.blocks.all(blocks::add) }
    }
}

internal class ChestSnapshotDownload(private val profile: String, private val knownRevision: Long?) {
    private var revision: Long? = null
    private var unchanged = false
    private var failed = false
    private val blocks = HashSet<ChestPos>()
    private val chests = ArrayList<StoredIslandChest>()
    private val cursors = HashSet<String>()
    var nextCursor: String? = null
        private set
    var complete = false
        private set

    fun accept(page: ChestWireSnapshot): Boolean {
        if (failed || complete || !page.valid(profile) || (revision != null && page.revision != revision) ||
            (page.unchanged && (revision != null || page.revision != knownRevision)) ||
            (page.nextCursor != null && !cursors.add(page.nextCursor)) ||
            (!page.unchanged && page.chests!!.any { chest -> chest.blocks.any { !blocks.add(it) } })) {
            failed = true
            complete = false
            return false
        }
        revision = page.revision
        unchanged = page.unchanged
        if (!unchanged) page.chests!!.forEach { chests.add(it.stored()) }
        nextCursor = page.nextCursor
        complete = nextCursor == null
        return true
    }

    fun reconcile(bucket: IslandChestBucket): IslandChestBucket =
        if (!complete || failed || unchanged) bucket else bucket.reconcile(revision!!, chests)

    /** A partly staged snapshot for the same baseline may continue at its cursor after an interruption. */
    fun resumable(known: Long?): Boolean = !failed && !complete && nextCursor != null && knownRevision == known
}

@Serializable
internal data class ChestWireObservation(
    val schemaVersion: Int,
    val baseRevision: Long,
    val observationId: String,
    val observedAt: Long,
    val blockId: String,
    val blocks: List<ChestPos>,
    val slots: List<ChestWireItem?>,
)

@Serializable
internal data class ChestWireAck(
    val schemaVersion: Int,
    val profileUuid: String,
    val revision: Long,
    val observationId: String,
    val key: String,
    val receivedAt: Long,
)

private fun String.utf8AtMost(limit: Int): Boolean = length <= limit && toByteArray(Charsets.UTF_8).size <= limit
internal fun validChestShape(blockId: String, blocks: List<ChestPos>, slots: Int): Boolean {
    if (blockId != "minecraft:chest" && blockId != "minecraft:trapped_chest") return false
    if (blocks.size !in 1..2 || blocks != blocks.sortedWith(chestPositionOrder) || blocks.distinct().size != blocks.size || slots != blocks.size * 27) return false
    return blocks.size == 1 || (blocks[0].y == blocks[1].y &&
        kotlin.math.abs(blocks[0].x.toLong() - blocks[1].x) + kotlin.math.abs(blocks[0].z.toLong() - blocks[1].z) == 1L)
}

internal fun LocalChestObservation.wire(): ChestWireObservation? {
    val c = chest
    if (!validChestShape(c.blockId, c.blocks, c.slots.size) || c.observedAt !in 1..253402300799999L) return null
    val items = c.slots.map { it?.let { item -> ChestWireItem(item.material, item.skyblockId, item.displayNameRaw, item.loreLines, item.count) } }
    if (items.any { it != null && !it.valid() }) return null
    val body = ChestWireObservation(1, baseRevision, observationId, c.observedAt, c.blockId, c.blocks, items)
    return body.takeIf { chestWireJson.encodeToString(ChestWireObservation.serializer(), it).toByteArray(Charsets.UTF_8).size <= 256 * 1024 }
}

internal val chestWireJson = Json { ignoreUnknownKeys = true }
/** Sync identity: account plus exact profile UUID. No generation, so server hops with the same identity keep sync state. */
internal data class IslandChestSyncContext(val account: String, val profile: String)

/** The cache is readable and synced anywhere on SkyBlock with a fresh identity; only observing needs the own island. */
internal object IslandChestGates {
    fun readable(identity: ChestObservationContext?, fresh: Boolean, enabled: Boolean, onSkyblock: Boolean, stopping: Boolean): ChestObservationContext? =
        identity?.takeIf { fresh && enabled && onSkyblock && !stopping }

    fun observable(readable: ChestObservationContext?, ownIsland: Boolean): ChestObservationContext? = readable?.takeIf { ownIsland }

    fun sync(readable: ChestObservationContext?, sharing: Boolean, previous: IslandChestSyncContext?): IslandChestSyncContext? =
        if (readable == null || !sharing) null
        else if (previous != null && previous.account == readable.account && previous.profile == readable.profile) previous
        else IslandChestSyncContext(readable.account, readable.profile)
}

/** When to run; survives unresolved gaps (server hops) and resets only for another identity. */
internal class IslandChestSyncSchedule {
    private var identity: IslandChestSyncContext? = null
    private var requested = true
    private var nextAttempt = 0L
    private var rateLimitedUntil = 0L

    /** True if [current] replaces another identity, whose scheduling and staged pages no longer apply. */
    fun resolve(current: IslandChestSyncContext): Boolean {
        if (identity == current) return false
        identity = current
        requested = true
        nextAttempt = 0
        rateLimitedUntil = 0
        return true
    }

    fun request() { requested = true }
    fun due(now: Long): Boolean = now >= rateLimitedUntil && (requested || now >= nextAttempt)
    fun started() { requested = false }

    fun finished(at: Long, delay: Long, rateLimited: Boolean) {
        nextAttempt = at + delay
        if (rateLimited) rateLimitedUntil = nextAttempt
    }
}

internal class IslandChestSync(
    /** The open dataset's store; each run keeps the one it started with, so it never reaches another dataset's. */
    private val currentStore: () -> IslandChestStore,
    private val context: () -> IslandChestSyncContext?,
    private val changed: () -> Unit,
    private val status: (String) -> Unit,
) {
    private val schedule = IslandChestSyncSchedule()
    private var job: Job? = null
    private var stopped: Job? = null
    private var runId = 0L
    // Written by the running job only; a new run joins the stopped one before reading them.
    @Volatile private var staged: ChestSnapshotDownload? = null
    @Volatile private var stagedFor: IslandChestSyncContext? = null
    @Volatile private var pageRetryAt = 0L

    fun refresh() = schedule.request()

    fun tick(now: Long, activeBlocks: List<ChestPos>) {
        val current = context()
        if (current == null) {
            stop()
            return
        }
        if (schedule.resolve(current)) {
            stop()
            stagedFor = null
            staged = null
            pageRetryAt = 0
            status("local")
        }
        if (job?.isActive == true || !schedule.due(now)) return
        schedule.started()
        val serial = ++runId
        val store = currentStore()
        val previous = stopped
        stopped = null
        status("syncing")
        job = SoulScope.io.launch {
            previous?.join()
            val outcome = try {
                reconcile(current, activeBlocks, store)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome("offline")
            }
            Minecraft.getInstance().execute {
                if (serial != runId) return@execute
                job = null
                val state = outcome.state
                if (state == null || context() != current) {
                    // Interrupted by an unresolved gap: run again once this identity is back.
                    schedule.request()
                    return@execute
                }
                schedule.finished(System.currentTimeMillis(), outcome.delay, outcome.rateLimited)
                status(state)
                changed()
            }
        }
    }

    /** Stops the running job; the next run for the same identity resumes its staged pages. */
    private fun stop() {
        val running = job ?: return
        runId++
        running.cancel()
        job = null
        stopped = running
        schedule.request()
    }

    /** [state] null: interrupted, nothing concluded. */
    private data class Outcome(val state: String?, val delay: Long = 30_000, val rateLimited: Boolean = false)
    private val interrupted = Outcome(null)

    private fun stillCurrent(c: IslandChestSyncContext): Boolean = context() == c

    private fun apply(store: IslandChestStore, c: IslandChestSyncContext, change: (IslandChestBucket) -> IslandChestBucket) {
        if (!stillCurrent(c)) return
        store.update(c.account, c.profile, change)
        Minecraft.getInstance().execute { if (stillCurrent(c)) changed() }
    }

    private suspend fun reconcile(c: IslandChestSyncContext, activeBlocks: List<ChestPos>, store: IslandChestStore): Outcome {
        if (!stillCurrent(c)) return interrupted
        val endpoint = "/skyblock/profiles/${c.profile}/island-chests"
        val known = store.read(c.account, c.profile).snapshotRevision
        val resumed = staged?.takeIf { stagedFor == c && it.resumable(known) }
        val download = resumed ?: ChestSnapshotDownload(c.profile, known)
        staged = download
        stagedFor = c
        var query = resumed?.nextCursor?.let(::cursorQuery) ?: ("?format=2" + (known?.let { "&knownRevision=$it" } ?: ""))
        while (true) {
            val wait = pageRetryAt - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            val fetched = BackendClient.get("$endpoint$query", ChestWireSnapshot.serializer(), "island-chests-refresh", bypassCache = true)
            if (!stillCurrent(c)) return interrupted
            if (fetched !is BackendResult.Ok) {
                val error = fetched as BackendResult.Failure
                if (error.status != 429) {
                    staged = null
                    return failure(error)
                }
                // Keep staged pages across the read limiter (and across interruptions); large legacy profiles may need more than one window.
                pageRetryAt = System.currentTimeMillis() + maxOf(1_000, error.retryAfter?.toMillis() ?: 60_000)
                continue
            }
            if (!download.accept(fetched.value)) {
                staged = null
                return Outcome("error")
            }
            if (download.complete) break
            query = cursorQuery(download.nextCursor!!)
        }
        staged = null
        apply(store, c, download::reconcile)
        val observations = store.read(c.account, c.profile).local
        var state = "shared"
        for (observation in observations) {
            if (!stillCurrent(c)) return interrupted
            if (observation.blocked) { state = observation.rejection ?: "conflict"; continue }
            if (observation.chest.blocks.any { it in activeBlocks }) { state = "pending"; continue }
            // Never repair a conflict by attaching a newer GET revision to old physical contents.
            val latest = store.read(c.account, c.profile)
            if (latest.local.none { it.observationId == observation.observationId && !it.blocked }) continue
            val payload = observation.wire()
            if (payload == null) {
                apply(store, c) { it.reject(observation.observationId) }
                state = "too-large"
                continue
            }
            val posted = BackendClient.post(endpoint, payload, ChestWireObservation.serializer(), "island-chests-observe")
            if (!stillCurrent(c)) return interrupted
            if (posted is BackendResult.Ok) {
                val ack = try { chestWireJson.decodeFromJsonElement(ChestWireAck.serializer(), posted.value) } catch (_: Exception) { return Outcome("error") }
                if (ack.schemaVersion != 1 || MojangUuid.undashedOrNull(ack.profileUuid) != c.profile || ack.observationId != observation.observationId || ack.key != observation.chest.key || ack.revision <= observation.baseRevision) return Outcome("error")
                apply(store, c) { it.acknowledge(observation, ack.revision) }
            } else {
                val error = posted as BackendResult.Failure
                if (error.status == 422) {
                    apply(store, c) { it.reject(observation.observationId, "quota") }
                    return Outcome("quota", 60_000)
                }
                if (error.status == 409) {
                    apply(store, c) { it.reject(observation.observationId) }
                    refreshOnClient()
                    return Outcome("conflict", 1_000)
                }
                if (error.status == 400 || error.status == 413) apply(store, c) { it.reject(observation.observationId) }
                return failure(error)
            }
        }
        return Outcome(state)
    }

    private fun cursorQuery(cursor: String) = "?format=2&cursor=" + URLEncoder.encode(cursor, StandardCharsets.UTF_8)
    private fun refreshOnClient() = Minecraft.getInstance().execute { refresh() }
    private fun failure(error: BackendResult.Failure): Outcome {
        if (error.status == 429) return Outcome("rate-limited", maxOf(1_000, error.retryAfter?.toMillis() ?: 60_000), true)
        if (error.status == 401 || error.status == 403) return Outcome("forbidden", 60_000)
        if (error.status == 409) return Outcome("conflict")
        if (error.status == 426) return Outcome("update-required", 60_000)
        if (error.status == 422) return Outcome("quota", 60_000)
        if (error.status == 400 || error.status == 413) return Outcome("error", 60_000)
        return Outcome("offline", 30_000)
    }
}
