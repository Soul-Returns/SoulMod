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
