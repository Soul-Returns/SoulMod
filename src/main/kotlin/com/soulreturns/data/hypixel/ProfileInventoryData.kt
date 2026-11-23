package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonObject
import net.minecraft.nbt.CompoundTag
import net.minecraft.nbt.NbtAccounter
import net.minecraft.nbt.NbtIo
import java.io.ByteArrayInputStream
import java.util.Base64
import java.util.Locale

/*
 * The Inventories section's reads: the item blobs under `inventory.*` and the sack counts. Field names
 * follow the Hypixel API shape (`inventory.inv_contents.data`, `ender_chest_contents`,
 * `backpack_contents.<n>`, `backpack_icons.<n>`, `personal_vault_contents`, `bag_contents.<bag>`,
 * `sacks_counts`), confirmed against a real 13-profile dump: backpack keys are zero-based (0..17), the Ender Chest blob is
 * 405 slots (nine pages of 45), the vault 27, the bags 9..45, and every blob is gzipped 1.8 NBT with `tag.display` and `tag.ExtraAttributes`.
 * Absence is normal (an API toggle is off) and means null, never an empty container.
 */

// ------------------------------------------------------------------ the item text of a blob

/**
 * What a section needs from one stack without building an `ItemStack`: SkyBlock id, display name and
 * lore (still with `§` codes), count and the recombobulator flag. Read straight from the 1.8 NBT of the
 * blob, so search, values and Magical Power are plain data and work in a headless test.
 */
class BlobItem(
    val id: String?,
    val name: String,
    val lore: List<String>,
    val count: Int,
    val recombobulated: Boolean,
    /** `ExtraAttributes.sack_pss`: Pocket Sack-in-a-Sack stitches on a sack item, 0 elsewhere. */
    val sackStitches: Int = 0,
) {
    val plainName: String by lazy(LazyThreadSafetyMode.NONE) { strip(name) }

    /** Lower-cased name and lore, one line each, for [matches]. */
    private val searchText: String by lazy(LazyThreadSafetyMode.NONE) {
        buildString {
            append(plainName.lowercase(Locale.ROOT))
            for (line in lore) append('\n').append(strip(line).lowercase(Locale.ROOT))
        }
    }

    /** Rarity from the last lore line ("LEGENDARY ACCESSORY"), upper case; null when the item has none. */
    val rarity: String? by lazy(LazyThreadSafetyMode.NONE) {
        val line = lore.lastOrNull { it.isNotBlank() } ?: return@lazy null
        RARITY.find(strip(line))?.value
    }

    /** The mock's search: [query] (lower-cased, trimmed) in the name or any lore line. */
    fun matches(query: String): Boolean = query.isEmpty() || searchText.contains(query)

    companion object {
        private val RARITY = Regex("VERY SPECIAL|SPECIAL|UNCOMMON|COMMON|RARE|EPIC|LEGENDARY|MYTHIC|DIVINE|ULTIMATE|SUPREME")
        private val CODES = Regex("§.")

        fun strip(text: String): String = CODES.replace(text, "")
    }
}

/** Decodes blobs to [BlobItem]s, once per blob (small LRU: the section asks every frame). */
object ItemBlobs {
    private const val MAX_BLOBS = 96

    private class Parsed(val items: List<BlobItem?>?)

    private val cache = object : LinkedHashMap<String, Parsed>(32, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Parsed>?): Boolean = size > MAX_BLOBS
    }

    /** One entry per slot, null for an empty one; null overall when the blob cannot be read. */
    fun parse(base64: String): List<BlobItem?>? {
        synchronized(cache) { cache[base64]?.let { return it.items } }
        val items = read(base64)
        synchronized(cache) { cache[base64] = Parsed(items) }
        return items
    }

    private fun read(base64: String): List<BlobItem?>? {
        val list = try {
            NbtIo.readCompressed(ByteArrayInputStream(Base64.getDecoder().decode(base64)), NbtAccounter.unlimitedHeap()).getListOrEmpty("i")
        } catch (_: Exception) {
            return null
        }
        return List(list.size) { index -> (list[index] as? CompoundTag)?.let(::item) }
    }

    private fun item(tag: CompoundTag): BlobItem? {
        if (tag.isEmpty || tag.getShortOr("id", -1) == 0.toShort()) return null
        val inner = tag.getCompoundOrEmpty("tag")
        val extra = inner.getCompoundOrEmpty("ExtraAttributes")
        val display = inner.getCompoundOrEmpty("display")
        val loreTag = display.getListOrEmpty("Lore")
        val lore = List(loreTag.size) { loreTag.getStringOr(it, "") }
        val id = extra.getStringOr("id", "").takeIf { it.isNotEmpty() }
        return BlobItem(
            id = id,
            name = display.getStringOr("Name", id ?: "Unknown item"),
            lore = lore,
            count = tag.getByteOr("Count", 1).toInt().coerceAtLeast(1),
            recombobulated = extra.getIntOr("rarity_upgrades", 0) > 0 || lore.lastOrNull()?.contains("§k") == true,
            sackStitches = extra.getIntOr("sack_pss", 0).coerceAtLeast(0),
        )
    }

    /** Test seam. */
    fun clear() {
        synchronized(cache) { cache.clear() }
    }
}

// ------------------------------------------------------------------ container shapes

/** The inventory as the game draws it: the three inventory rows, then the hotbar (the API lists the hotbar first). */
fun <T> inventoryDisplayOrder(slots: List<T>): List<T> =
    if (slots.size == 36) slots.subList(9, 36) + slots.subList(0, 9) else slots

/** The Ender Chest blob is one list; each page is 45 slots (9 columns, 5 rows). */
fun <T> enderChestPages(slots: List<T>): List<List<T>> = slots.chunked(45)

// ------------------------------------------------------------------ sacks

/** One sack row: [fromSoul] means the count is from the player's own sack menus, newer than Hypixel's. */
class SackLine(val id: String, val count: Long, val fromSoul: Boolean)

/** The merged sack list (largest count first, then id) and when the newest Soul count was read, null when none replaced one. */
class SackSummary(val lines: List<SackLine>, val newestSoulAt: Long?)

/**
 * `inventory.sacks_counts` with the owner's own newer counts laid over it.
 *
 * @param soul id to (count, readAt epoch ms) of exact reads from the player's sack menus or `[Sacks]` chat;
 *   null for any profile but the player's own. A Soul count replaces Hypixel's for the same id and adds ids
 *   Hypixel does not list yet.
 */
fun mergeSacks(api: Map<String, Long>, soul: Map<String, Pair<Long, Long>>? = null): SackSummary {
    val merged = LinkedHashMap<String, SackLine>(api.size)
    for ((id, count) in api) merged[id] = SackLine(id, count, false)
    var newest: Long? = null
    if (soul != null) {
        for ((id, read) in soul) {
            if (read.first == api[id] && api.containsKey(id)) continue
            merged[id] = SackLine(id, read.first, true)
            if (newest == null || read.second > newest) newest = read.second
        }
    }
    return SackSummary(merged.values.sortedWith(compareByDescending<SackLine> { it.count }.thenBy { it.id }), newest)
}

/** A readable name for a SkyBlock id the item catalog does not know: `ENCHANTED_RAW_FISH` becomes `Enchanted Raw Fish`. */
fun humanizeItemId(id: String): String =
    id.lowercase(Locale.ROOT).split('_', ':').filter { it.isNotEmpty() }.joinToString(" ") { part ->
        part.replaceFirstChar { it.uppercase(Locale.ROOT) }
    }

// ------------------------------------------------------------------ owned sacks (`sacksBagBlob`, the Sack of Sacks, is in ProfileOverviewData)

/**
 * One owned sack type, all copies folded together (rule://sacks "Owned sacks").
 *
 * @property key the Sack of Sacks item id without its `SMALL_`/`MEDIUM_`/`LARGE_` prefix (`MINING_SACK`), the catalog key.
 * @property name the display name without a leading `Small `/`Medium `/`Large ` ("Mining Sack"): the menu title.
 * @property sizes distinct sizes owned, small to large ("Medium", "Large"); empty for sacks that have none (`RUNE_SACK`).
 * @property copies how many sack items of this key the bag holds.
 * @property capacity the lore capacities of every copy added up ("Multiple sacks sum their capacity"); 0 when no lore said one.
 * @property stitches Pocket Sack-in-a-Sack stitches over all copies.
 * @property rarity upper case rarity of the sack item ("EPIC"), the highest of its copies; null when unreadable.
 * @property slot index of the first copy in the blob, to find its decoded stack (the icon).
 */
class OwnedSack(
    val key: String,
    val name: String,
    val sizes: List<String>,
    val copies: Int,
    val capacity: Long,
    val stitches: Int,
    val rarity: String?,
    val slot: Int,
)

private val SACK_SIZES = listOf("SMALL" to "Small", "MEDIUM" to "Medium", "LARGE" to "Large")
private val CAPACITY_LINE = Regex("""^Capacity: ([\d,]+) of each""")
private val RARITY_ORDER = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC", "DIVINE", "SPECIAL", "VERY SPECIAL")

/** The sack key of an item id (`LARGE_MINING_SACK` becomes `MINING_SACK`), null when it is no sack. */
fun sackKeyOf(id: String): String? {
    if (!id.endsWith("_SACK")) return null
    for ((prefix, _) in SACK_SIZES) if (id.startsWith("${prefix}_")) return id.substring(prefix.length + 1).takeIf { it.isNotEmpty() }
    return id
}

/** The size word of an item id (`LARGE_MINING_SACK` -> "Large"), null for a sack without one. */
fun sackSizeOf(id: String): String? = SACK_SIZES.firstOrNull { id.startsWith("${it.first}_") }?.second

/** The display name without a leading size word (`Large Mining Sack` -> `Mining Sack`). */
fun sackNameOf(displayName: String): String {
    for ((_, word) in SACK_SIZES) if (displayName.startsWith("$word ")) return displayName.substring(word.length + 1).trim()
    return displayName.trim()
}

/**
 * The owned sacks in a Sack of Sacks blob (slots as [ItemBlobs.parse] reads them), first appearance first.
 * Tolerant: a slot that is not a sack item is skipped, a missing capacity line counts 0.
 */
fun ownedSacks(slots: List<BlobItem?>): List<OwnedSack> {
    class Acc(val key: String, val name: String, val slot: Int) {
        val sizes = LinkedHashSet<String>()
        var copies = 0
        var capacity = 0L
        var stitches = 0
        var rarity: String? = null
    }
    val byKey = LinkedHashMap<String, Acc>()
    for ((slot, item) in slots.withIndex()) {
        item ?: continue
        val id = item.id ?: continue
        val key = sackKeyOf(id) ?: continue
        val name = BlobItem.strip(item.name).takeIf { it.isNotBlank() && it != id }?.let(::sackNameOf) ?: humanizeItemId(key)
        val acc = byKey.getOrPut(key) { Acc(key, name, slot) }
        sackSizeOf(id)?.let { acc.sizes += it }
        acc.copies++
        acc.stitches += item.sackStitches
        acc.capacity += item.lore.firstNotNullOfOrNull { CAPACITY_LINE.find(BlobItem.strip(it).trim()) }?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L
        val rarity = item.rarity
        if (rarity != null && (acc.rarity == null || RARITY_ORDER.indexOf(rarity) > RARITY_ORDER.indexOf(acc.rarity))) acc.rarity = rarity
    }
    val sizeOrder = SACK_SIZES.map { it.second }
    return byKey.values.map { a ->
        OwnedSack(a.key, a.name, a.sizes.sortedBy { sizeOrder.indexOf(it) }, a.copies, a.capacity, a.stitches, a.rarity, a.slot)
    }
}

/** The member's owned sacks, null when the Sack of Sacks blob is absent or unreadable (API off, never opened). */
val SkyblockProfileMember.ownedSacks: List<OwnedSack>?
    get() {
        val slots = ItemBlobs.parse(sacksBagBlob ?: return null) ?: return null
        // The section asks every frame; ItemBlobs hands out the same list instance until it evicts it.
        synchronized(OwnedSacksMemo) {
            if (OwnedSacksMemo.slots !== slots) {
                OwnedSacksMemo.owned = ownedSacks(slots)
                OwnedSacksMemo.slots = slots
            }
            return OwnedSacksMemo.owned
        }
    }

private object OwnedSacksMemo {
    var slots: List<BlobItem?>? = null
    var owned: List<OwnedSack> = emptyList()
}
