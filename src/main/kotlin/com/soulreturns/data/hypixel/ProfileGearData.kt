package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonObject

/*
 * Reads for the Profile Viewer's Gear section: the item blobs under `inventory` and the text rules that
 * sort their contents. The blobs are base64 gzipped NBT, decoded by `LegacyItemDecoder`.
 *
 * Verified against a real dump: `inv_armor` lists boots first, `wardrobe_contents` holds 36 slots per page
 * (row = piece, column = set; a dump holds two or three pages, the count is the data's) and
 * `wardrobe_equipped_slot` counts from 1 (-1 = none; the worn set's own wardrobe column is empty). The
 * type-line ordering stays as a guard.
 */

/**
 * `inventory.wardrobe_equipped_slot` as a 0-based set index, null when no set is worn (absent or
 * the API's -1). The API counts from 1.
 */
val SkyblockProfileMember.wardrobeEquippedSet: Int?
    get() {
        val slot = raw.path("inventory")?.long("wardrobe_equipped_slot", -1L) ?: -1L
        return if (slot >= 1) (slot - 1).toInt() else null
    }

/** Pure text rules of the Gear section (type lines, armor order, set names, weapon test). */
object GearText {
    val ARMOR_PIECES = listOf("Helmet", "Chestplate", "Leggings", "Boots")
    val EQUIPMENT_PIECES = listOf("Necklace", "Cloak", "Belt", "Gloves")

    /** Ender Chest pages hold five rows of nine. */
    const val ENDER_PAGE = 45

    /** A wardrobe page: four piece rows of nine set columns. */
    const val WARDROBE_PAGE = 36
    const val WARDROBE_COLUMNS = 9

    /** Every wardrobe has at least two pages; the cap only guards a nonsense blob or worn index. */
    private const val WARDROBE_MIN_PAGES = 2
    private const val WARDROBE_MAX_PAGES = 50

    /**
     * How many sets to show: the blob's pages (36 slots each, at least two), extended so the [worn] set
     * (0-based) is always inside, times nine.
     */
    fun wardrobeSetCount(slotCount: Int, worn: Int?): Int {
        val ofData = (slotCount + WARDROBE_PAGE - 1) / WARDROBE_PAGE
        val ofWorn = if (worn == null) 0 else worn / WARDROBE_COLUMNS + 1
        return maxOf(WARDROBE_MIN_PAGES, ofData, ofWorn).coerceAtMost(WARDROBE_MAX_PAGES) * WARDROBE_COLUMNS
    }

    private val OBFUSCATED = Regex("§k.")
    private val CODES = Regex("§.")
    private val SPACES = Regex("\\s+")
    private val STARS = Regex("[✪➊➋➌➍➎]")

    private val WEAPON_LAST = setOf("SWORD", "LONGSWORD", "BOW", "SHORTBOW", "DRILL", "PICKAXE", "AXE", "WAND", "GAUNTLET", "SHOVEL", "HOE")
    private val WEAPON_PAIRS = setOf("FISHING ROD", "FISHING WEAPON")
    private val PIECE_WORDS = ARMOR_PIECES.map { it.uppercase() }

    /**
     * The rarity line of an item (its last lore line, `§` string) without the Recombobulator's
     * obfuscated markers: `"MYTHIC DUNGEON SWORD"`.
     */
    fun typeLine(legacyLore: String): String =
        SPACES.replace(CODES.replace(OBFUSCATED.replace(legacyLore, ""), ""), " ").trim()

    /** An item name without the star glyphs the viewer's font lacks (the tooltip keeps them). */
    fun cleanName(legacy: String): String = SPACES.replace(STARS.replace(legacy, ""), " ").trim()

    /** 0 helmet .. 3 boots from a type line's last word, null for anything else. */
    fun armorPiece(typeLine: String): Int? {
        val last = typeLine.trim().uppercase().substringAfterLast(' ')
        return PIECE_WORDS.indexOf(last).takeIf { it >= 0 }
    }

    /** Weapons and tools: swords, bows, drills, pickaxes, axes, fishing rods and the like. */
    fun isWeapon(typeLine: String): Boolean {
        val words = typeLine.trim().uppercase().split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return false
        if (words.last() in WEAPON_LAST) return true
        return words.size >= 2 && "${words[words.size - 2]} ${words.last()}" in WEAPON_PAIRS
    }

    /**
     * [apiOrder] (four slots as the API lists them) as helmet, chestplate, leggings, boots. Each item goes
     * where its type line says; if any item is unrecognized or two claim a slot, [apiOrder] is kept, or
     * reversed with [reverseFallback] (`inv_armor` lists boots first, wardrobe columns list the helmet first).
     */
    fun <T : Any> orderArmor(apiOrder: List<T?>, reverseFallback: Boolean, typeOf: (T) -> String): List<T?> {
        val padded: List<T?> = List(4) { apiOrder.getOrNull(it) }
        val typed = MutableList<T?>(4) { null }
        for (item in padded) {
            if (item == null) continue
            val slot = armorPiece(typeOf(item))
            if (slot == null || typed[slot] != null) return if (reverseFallback) padded.reversed() else padded
            typed[slot] = item
        }
        return typed
    }

    /** [name] without its reforge prefix, when it starts with [modifier] (`ancient` → `Ancient `). */
    fun withoutReforge(name: String, modifier: String?): String {
        if (modifier.isNullOrEmpty()) return name
        val prefix = modifier.split('_').joinToString(" ") { w -> w.replaceFirstChar { it.uppercaseChar() } } + " "
        return if (name.startsWith(prefix, ignoreCase = true) && name.length > prefix.length) name.substring(prefix.length) else name
    }

    /**
     * `"Necron's set"` when all four pieces share a name once the piece word is dropped (the longest shared
     * word suffix), else `"mixed armor"`. [names] are plain, reforge-free names in any order; null = empty slot.
     */
    fun setName(names: List<String?>): String {
        if (names.size != 4 || names.any { it == null }) return "mixed armor"
        val words = names.map { name ->
            var parts = name!!.trim().split(' ').filter { it.isNotEmpty() }
            if (parts.size > 1 && parts.last().uppercase() in PIECE_WORDS) parts = parts.dropLast(1)
            parts
        }
        var shared = 0
        val shortest = words.minOf { it.size }
        while (shared < shortest && words.all { it[it.size - 1 - shared] == words[0][words[0].size - 1 - shared] }) shared++
        if (shared == 0) return "mixed armor"
        return words[0].takeLast(shared).joinToString(" ") + " set"
    }

    /** Where hotbar-first inventory slot [index] is: `Hotbar 3` or `Inventory`. */
    fun inventoryPlace(index: Int): String = if (index < 9) "Hotbar ${index + 1}" else "Inventory"

    /** `Ender Chest 3` for the 0-based blob slot [index]. */
    fun enderPlace(index: Int): String = "Ender Chest ${index / ENDER_PAGE + 1}"
}
