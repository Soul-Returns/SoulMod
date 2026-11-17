package com.soulreturns.features.experiments

import kotlinx.serialization.Serializable

/**
 * Which Superpairs reward cards deserve a warning (research §7). Nothing is hard-coded: the set comes
 * from the player's own `(n/N) Experimentation Table RNG` pages. Each entry's base odds (the first
 * percentage of its `Odds:` line, the struck-through one on the selected drop) are read; the cut is the
 * largest ratio gap between consecutive distinct odds (today 1.0638 % → 0.0263 %, ×40), and every entry
 * at or below it is rare. A card matches an entry by the §7.2 key, never by display name alone.
 *
 * Second signal, independent of the meter: the card itself says `ULTRA-RARE` in its name or lore.
 */
object RareRewards {
    /** [selected]: the player's chosen RNG drop on that page (per profile; stored apart from the per-account odds). */
    @Serializable
    data class Entry(val name: String, val odds: Double, val keys: List<String>, val selected: Boolean = false)

    /** The selected drop of one profile: the page it was read on and its keys. */
    @Serializable
    data class Selection(val page: Int = 0, val keys: List<String> = emptyList(), val readAt: Long = 0)

    /** Entries per page (`"1"`, `"2"` …) and the page count, as cached per account. */
    @Serializable
    data class Table(val version: Int = 1, val readAt: Long = 0, val pages: Int = 0, val entries: Map<String, List<Entry>> = emptyMap())

    private val ODDS = Regex("([\\d.]+)%")

    /** The page's entries: every slot whose lore has an `Odds:` line. */
    fun readPage(items: List<ExpItem?>): List<Entry> {
        val out = ArrayList<Entry>()
        for (it in items) {
            if (it == null) continue
            val line = it.plainLore.firstOrNull { l -> l.startsWith("Odds:") } ?: continue
            val odds = ODDS.find(line)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            out += Entry(it.plain, odds, entryKeys(it), it.plainLore.any { l -> l.trim() == "SELECTED" })
        }
        return out
    }

    /** Merge one page into [t] (the page count from the title). */
    fun withPage(t: Table, page: Int, pages: Int, entries: List<Entry>, now: Long): Table {
        val keep = if (t.pages == pages) t.entries else emptyMap()
        return Table(1, now, pages, keep + (page.toString() to entries.map { it.copy(selected = false) }))
    }

    /**
     * The profile's selection after reading [page]: a page showing a selected entry sets it; the page that
     * held the old selection showing none clears it (the player picked another drop or reset it).
     */
    fun withSelection(old: Selection?, page: Int, entries: List<Entry>, now: Long): Selection? {
        entries.firstOrNull { it.selected }?.let { return Selection(page, it.keys, now) }
        return if (old != null && old.page == page) null else old
    }

    fun complete(t: Table): Boolean = t.pages > 0 && (1..t.pages).all { t.entries[it.toString()]?.isNotEmpty() == true }

    /** The odds at the far side of the largest ratio gap: entries at or below it are rare. Null without two distinct odds. */
    fun cut(odds: Collection<Double>): Double? {
        val d = odds.filter { it > 0 }.distinct().sortedDescending()
        if (d.size < 2) return null
        var best = 0
        for (i in 0 until d.size - 1) if (d[i] / d[i + 1] > d[best] / d[best + 1]) best = i
        return d[best + 1]
    }

    /** Keys of every rare entry, or null while the table is incomplete (then only the card's own label warns). */
    fun rareKeys(t: Table): Set<String>? {
        if (!complete(t)) return null
        val all = t.entries.values.flatten()
        val c = cut(all.map { it.odds }) ?: return emptySet()
        return all.filter { it.odds <= c }.flatMap { it.keys }.toSet()
    }

    private fun idKey(it: ExpItem): String? {
        val id = it.sbId ?: return null
        return if (id == "PET") "id:PET;${it.petType};${it.petTier}" else "id:$id"
    }

    private fun dyeOf(it: ExpItem) = it.dye?.let { d -> String.format(java.util.Locale.ROOT, "%06x", d and 0xFFFFFF) } ?: ""

    /** A meter entry may be a book (its display name is the enchant) or an item; it gets a key for each form. */
    fun entryKeys(it: ExpItem): List<String> {
        idKey(it)?.let { return listOf(it) }
        return listOf(
            "book:${it.id}|${it.count}|${dyeOf(it)}|${it.plain}",
            "item:${it.id}|${it.count}|${it.texture ?: ""}|${it.plain}",
        )
    }

    /** A revealed card's key: SkyBlock id (+ pet type/tier); a book by item + count + dye + its enchant line; else item + count + texture + name. */
    fun cardKey(it: ExpItem): String {
        idKey(it)?.let { k -> return k }
        if (it.plain == "Enchanted Book") {
            val enchant = it.plainLore.drop(1).firstOrNull { l -> l.isNotBlank() }?.trim() ?: ""
            return "book:${it.id}|${it.count}|${dyeOf(it)}|$enchant"
        }
        return "item:${it.id}|${it.count}|${it.texture ?: ""}|${it.plain}"
    }

    /** Hypixel's own label on the card (SkyHanni expects `ULTRA-RARE BOOK!`; never captured, so matched loosely). */
    fun labelledUltraRare(it: ExpItem): Boolean = it.plain.contains("ULTRA-RARE", ignoreCase = true) || it.plainLore.any { l -> l.contains("ULTRA-RARE", ignoreCase = true) }

    /** Rare: Hypixel's label, a rare meter entry, or the profile's selected RNG drop (whatever its odds). */
    fun isRare(it: ExpItem, keys: Set<String>?, selected: Collection<String>? = null): Boolean {
        if (labelledUltraRare(it)) return true
        val k = cardKey(it)
        return (keys != null && k in keys) || (selected != null && k in selected)
    }
}
