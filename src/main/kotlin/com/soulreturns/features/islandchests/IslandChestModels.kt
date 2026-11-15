package com.soulreturns.features.islandchests

import com.soulreturns.features.inventorybuttons.InventoryButtonIcons.ItemLook
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MojangUuid
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.nullable
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.security.MessageDigest
import java.util.Locale

@Serializable
data class ChestPos(val x: Int, val y: Int, val z: Int)

@Serializable
data class StoredChestItem(
    val material: String,
    val skyblockId: String? = null,
    val displayNameRaw: String? = null,
    val loreLines: List<String> = emptyList(),
    val count: Int,
    @Serializable(with = ChestItemLookSerializer::class) val look: ItemLook? = null,
)

private val enchantmentNameLine = Regex("""[\p{L}\p{N}'’ -]+ (?:[IVXLCDM]+|[0-9]+)(?:, [\p{L}\p{N}'’ -]+ (?:[IVXLCDM]+|[0-9]+))*,?""")

internal fun StoredChestItem.searchName(): String {
    if (skyblockId == "ENCHANTED_BOOK" || material == "minecraft:enchanted_book") {
        val enchantments = loreLines.mapNotNull { raw ->
            val line = MessageDetector.stripColorCodes(raw).trim()
            if (enchantmentNameLine.matches(line)) line.trimEnd(',') else null
        }
        if (enchantments.isNotEmpty()) return enchantments.joinToString(", ")
    }
    return MessageDetector.stripColorCodes(displayNameRaw ?: skyblockId?.replace('_', ' ') ?: material.substringAfter(':').replace('_', ' '))
}

@Serializable
data class StoredIslandChest(
    val key: String,
    val blocks: List<ChestPos>,
    val blockId: String,
    val slots: List<StoredChestItem?>,
    val observerUuid: String,
    val observerName: String? = null,
    val observedAt: Long,
    val revision: Long = 0,
    val syncState: String = "local",
)

data class ChestMatch(val chest: StoredIslandChest, val count: Long)
data class ChestSearchResult(val key: String, val item: StoredChestItem, val count: Long, val matches: List<ChestMatch>)
data class IslandChestView(
    val profileId: String? = null,
    val profileName: String? = null,
    val ironman: Boolean = false,
    val ownIsland: Boolean = false,
    val sharingEnabled: Boolean = false,
    val syncState: String = "local",
    val chests: List<StoredIslandChest> = emptyList(),
)

@Serializable
private data class ChestIcon(val item: String, val skin: String = "", val model: String = "", val glint: Boolean = false)

object ChestItemLookSerializer : KSerializer<ItemLook?> {
    private val delegate = ChestIcon.serializer().nullable
    override val descriptor: SerialDescriptor = delegate.descriptor
    override fun serialize(encoder: Encoder, value: ItemLook?) = delegate.serialize(encoder, value?.let { ChestIcon(it.item, it.skin, it.model, it.glint) })
    override fun deserialize(decoder: Decoder): ItemLook? = delegate.deserialize(decoder)?.let { ItemLook(it.item, it.skin, it.model, it.glint) }
}

internal val chestPositionOrder = compareBy<ChestPos>({ it.x }, { it.y }, { it.z })
internal fun chestKey(blocks: List<ChestPos>): String = blocks.minWith(chestPositionOrder).let { "${it.x},${it.y},${it.z}" }
internal fun overlaps(a: StoredIslandChest, b: StoredIslandChest): Boolean = a.blocks.any { it in b.blocks }
private fun samePhysicalObservation(a: StoredIslandChest, b: StoredIslandChest): Boolean {
    if (a.observedAt != b.observedAt || a.blockId != b.blockId || a.blocks != b.blocks || a.slots.size != b.slots.size ||
        MojangUuid.undashedOrNull(a.observerUuid) != MojangUuid.undashedOrNull(b.observerUuid)) return false
    return a.slots.indices.all { index ->
        val first = a.slots[index]
        val second = b.slots[index]
        if (first == null || second == null) first == second else first.material == second.material &&
            first.skyblockId == second.skyblockId && first.displayNameRaw == second.displayNameRaw &&
            first.loreLines == second.loreLines && first.count == second.count
    }
}
internal fun chestBucketKey(account: String?, profile: String?): String? {
    val a = MojangUuid.undashedOrNull(account) ?: return null
    val p = MojangUuid.undashedOrNull(profile) ?: return null
    return "$a/$p"
}

internal fun StoredChestItem.searchKey(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) {
        val bytes = value.toByteArray(Charsets.UTF_8)
        digest.update((bytes.size ushr 24).toByte())
        digest.update((bytes.size ushr 16).toByte())
        digest.update((bytes.size ushr 8).toByte())
        digest.update(bytes.size.toByte())
        digest.update(bytes)
    }
    add(material)
    add(skyblockId.orEmpty())
    add(displayNameRaw.orEmpty())
    loreLines.forEach(::add)
    return java.util.HexFormat.of().formatHex(digest.digest())
}

internal class IslandChestSearchIndex {
    private var source: List<StoredIslandChest>? = null
    private var rows: List<ChestSearchResult> = emptyList()
    private var searchTexts: List<String> = emptyList()
    private var lastQuery: String? = null
    private var filtered: List<ChestSearchResult> = emptyList()
    fun search(chests: List<StoredIslandChest>, query: String): List<ChestSearchResult> {
        if (source !== chests) {
            source = chests
            class CountedChest(val chest: StoredIslandChest, var count: Long = 0)
            val groups = LinkedHashMap<String, Pair<StoredChestItem, LinkedHashMap<String, CountedChest>>>()
            for (chest in chests) for (item in chest.slots) {
                if (item == null || item.count <= 0) continue
                val group = groups.getOrPut(item.searchKey()) { item to LinkedHashMap() }
                group.second.getOrPut(chest.key) { CountedChest(chest) }.count += item.count.toLong()
            }
            rows = groups.map { (key, group) ->
                val matches = group.second.values.map { ChestMatch(it.chest, it.count) }
                ChestSearchResult(key, group.first, matches.sumOf { it.count }, matches)
            }.sortedWith(compareBy({ MessageDetector.stripColorCodes(it.item.displayNameRaw ?: it.item.skyblockId ?: it.item.material).lowercase(Locale.ROOT) }, { it.key }))
            searchTexts = rows.map { row ->
                buildString {
                    append(MessageDetector.stripColorCodes(row.item.displayNameRaw.orEmpty()))
                    append(' ').append(row.item.skyblockId.orEmpty())
                    append(' ').append(row.item.material)
                    if (row.item.skyblockId == "ENCHANTED_BOOK" || row.item.material == "minecraft:enchanted_book") {
                        for (line in row.item.loreLines) append(' ').append(MessageDetector.stripColorCodes(line))
                    }
                }.lowercase(Locale.ROOT)
            }
            lastQuery = null
        }
        val normalized = query.trim().lowercase(Locale.ROOT)
        if (normalized.isEmpty()) return rows
        if (lastQuery != normalized) {
            lastQuery = normalized
            val words = normalized.split(' ').filter(String::isNotEmpty)
            filtered = if (words.isEmpty()) rows else rows.filterIndexed { index, _ ->
                words.all { it in searchTexts[index] }
            }
        }
        return filtered
    }
}

@Serializable
internal data class LocalChestObservation(
    val observationId: String,
    val baseRevision: Long,
    val chest: StoredIslandChest,
    val blocked: Boolean = false,
    val rejection: String? = null,
)

@Serializable
internal data class IslandChestBucket(
    val revision: Long = 0,
    val shared: List<StoredIslandChest> = emptyList(),
    val local: List<LocalChestObservation> = emptyList(),
    val snapshotRevision: Long? = null,
) {
    fun observe(observation: LocalChestObservation): IslandChestBucket = copy(
        local = local.filterNot { overlaps(it.chest, observation.chest) } + observation,
    )

    fun visible(sharing: Boolean): List<StoredIslandChest> {
        val sharedState = if (sharing) "shared" else "cached"
        val result = shared.map { if (it.syncState == sharedState) it else it.copy(syncState = sharedState) }.toMutableList()
        for (observation in local) {
            // Revisions, not clocks, decide whether a queued observation may replace shared evidence.
            if (result.any { overlaps(it, observation.chest) && it.revision > observation.baseRevision }) continue
            result.removeAll { overlaps(it, observation.chest) }
            val state = if (observation.blocked) observation.rejection ?: "conflict" else if (sharing) "pending" else "local"
            result += if (observation.chest.syncState == state) observation.chest else observation.chest.copy(syncState = state)
        }
        return result.sortedBy { it.key }
    }

    fun reconcile(revision: Long, chests: List<StoredIslandChest>): IslandChestBucket {
        if (revision < this.revision) return this
        val looks = (shared + local.map { it.chest }).flatMap { it.slots.filterNotNull() }.associateBy { it.searchKey() }
        val restored = chests.map { chest -> chest.copy(slots = chest.slots.map { item -> item?.copy(look = looks[item.searchKey()]?.look) }) }
        return copy(revision = revision, snapshotRevision = revision, shared = restored, local = local.mapNotNull { observation ->
            val newer = restored.filter { overlaps(it, observation.chest) && it.revision > observation.baseRevision }
            if (newer.any { samePhysicalObservation(it, observation.chest) }) null
            else if (newer.isNotEmpty()) observation.copy(blocked = true) else observation
        })
    }

    fun acknowledge(sent: LocalChestObservation, revision: Long): IslandChestBucket {
        val accepted = sent.chest.copy(revision = revision, syncState = "shared")
        val newer = shared.any { overlaps(it, accepted) && it.revision > revision }
        return copy(
            revision = maxOf(this.revision, revision),
            shared = if (newer) shared else shared.filterNot { overlaps(it, accepted) } + accepted,
            local = local.mapNotNull { observation ->
                if (observation.observationId == sent.observationId) null
                // Our own CAS acknowledgement proves no intervening write to these blocks.
                else if (!newer && !observation.blocked && observation.baseRevision == sent.baseRevision &&
                    observation.chest.blocks == sent.chest.blocks) observation.copy(baseRevision = revision)
                else observation
            },
        )
    }

    fun reject(id: String, reason: String = "conflict"): IslandChestBucket = copy(
        local = local.map { if (it.observationId == id) it.copy(blocked = true, rejection = reason) else it },
    )
}
