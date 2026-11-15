package com.soulreturns.features.islandchests

import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import net.minecraft.client.Minecraft

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
) {
    fun valid(profile: String): Boolean {
        if (schemaVersion != 1 || MojangUuid.undashedOrNull(profileUuid) != profile || revision < 0) return false
        if (unchanged) return true
        val rows = chests ?: return false
        val blocks = HashSet<ChestPos>()
        return rows.all { it.valid(revision) && it.blocks.all(blocks::add) }
    }
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
internal data class IslandChestSyncContext(val account: String, val profile: String, val generation: Long)

internal class IslandChestSync(
    private val store: IslandChestStore,
    private val context: () -> IslandChestSyncContext?,
    private val changed: () -> Unit,
    private val status: (String) -> Unit,
) {
    private var active: IslandChestSyncContext? = null
    private var job: Job? = null
    private var runId = 0L
    private var requested = true
    private var nextAttempt = 0L
    private var rateLimitedUntil = 0L

    fun refresh() { requested = true }

    fun tick(now: Long, activeBlocks: List<ChestPos>) {
        val current = context()
        if (active != current) {
            active = current
            runId++
            job?.cancel()
            job = null
            requested = true
            nextAttempt = 0
            rateLimitedUntil = 0
        }
        if (current == null || job?.isActive == true || now < rateLimitedUntil || (!requested && now < nextAttempt)) return
        requested = false
        val serial = ++runId
        status("syncing")
        job = SoulScope.io.launch {
            val outcome = try {
                reconcile(current, activeBlocks)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                Outcome("offline")
            }
            Minecraft.getInstance().execute {
                if (serial != runId || context() != current) return@execute
                job = null
                val at = System.currentTimeMillis()
                nextAttempt = at + outcome.delay
                if (outcome.rateLimited) rateLimitedUntil = nextAttempt
                status(outcome.state)
                changed()
            }
        }
    }

    private data class Outcome(val state: String, val delay: Long = 30_000, val rateLimited: Boolean = false)
    private fun stillCurrent(c: IslandChestSyncContext): Boolean = context() == c

    private fun apply(c: IslandChestSyncContext, change: (IslandChestBucket) -> IslandChestBucket) {
        if (!stillCurrent(c)) return
        store.update(c.account, c.profile, change)
        Minecraft.getInstance().execute { if (stillCurrent(c)) changed() }
    }

    private suspend fun reconcile(c: IslandChestSyncContext, activeBlocks: List<ChestPos>): Outcome {
        if (!stillCurrent(c)) return Outcome("local")
        val endpoint = "/skyblock/profiles/${c.profile}/island-chests"
        val known = store.read(c.account, c.profile).revision
        val fetched = BackendClient.get("$endpoint?knownRevision=$known", ChestWireSnapshot.serializer(), "island-chests-refresh", bypassCache = true)
        if (!stillCurrent(c)) return Outcome("local")
        if (fetched !is BackendResult.Ok) return failure(fetched as BackendResult.Failure)
        val snapshot = fetched.value
        if (!snapshot.valid(c.profile) || (snapshot.unchanged && snapshot.revision != known)) return Outcome("error")
        if (!snapshot.unchanged) apply(c) { it.reconcile(snapshot.revision, snapshot.chests!!.map(ChestWireChest::stored)) }
        val observations = store.read(c.account, c.profile).local
        var state = "shared"
        for (observation in observations) {
            if (!stillCurrent(c)) return Outcome("local")
            if (observation.blocked) { state = "conflict"; continue }
            if (observation.chest.blocks.any { it in activeBlocks }) { state = "pending"; continue }
            // Never repair a conflict by attaching a newer GET revision to old physical contents.
            val latest = store.read(c.account, c.profile)
            if (latest.local.none { it.observationId == observation.observationId && !it.blocked }) continue
            val payload = observation.wire()
            if (payload == null) {
                apply(c) { it.reject(observation.observationId) }
                state = "too-large"
                continue
            }
            val posted = BackendClient.post(endpoint, payload, ChestWireObservation.serializer(), "island-chests-observe")
            if (!stillCurrent(c)) return Outcome("local")
            if (posted is BackendResult.Ok) {
                val ack = try { chestWireJson.decodeFromJsonElement(ChestWireAck.serializer(), posted.value) } catch (_: Exception) { return Outcome("error") }
                if (ack.schemaVersion != 1 || MojangUuid.undashedOrNull(ack.profileUuid) != c.profile || ack.observationId != observation.observationId || ack.key != observation.chest.key || ack.revision <= observation.baseRevision) return Outcome("error")
                apply(c) { it.acknowledge(observation, ack.revision) }
            } else {
                val error = posted as BackendResult.Failure
                if (error.status == 409) {
                    apply(c) { it.reject(observation.observationId) }
                    refreshOnClient()
                    return Outcome("conflict", 1_000)
                }
                if (error.status == 400 || error.status == 413) apply(c) { it.reject(observation.observationId) }
                return failure(error)
            }
        }
        return Outcome(state)
    }

    private fun refreshOnClient() = Minecraft.getInstance().execute { refresh() }
    private fun failure(error: BackendResult.Failure): Outcome {
        if (error.status == 429) return Outcome("rate-limited", maxOf(1_000, error.retryAfter?.toMillis() ?: 60_000), true)
        if (error.status == 401 || error.status == 403) return Outcome("forbidden", 60_000)
        if (error.status == 409) return Outcome("conflict")
        if (error.status == 400 || error.status == 413) return Outcome("error", 60_000)
        return Outcome("offline", 30_000)
    }
}
