package com.soulreturns.features.sacks

import java.util.Locale

/**
 * The pure half of the sack reporter ([SackReporter]): which item ids a sack menu lists, whether the
 * player has seen all of a sack's pages, and whether what they saw differs from the backend's catalog.
 *
 * Only ids are ever reported, never counts, so a report carries nothing about the player.
 */
object SackReport {
    /** One container slot as the reporter needs it: the SkyBlock id (null when none), hover name and lore, all plain text. */
    class MenuSlot(val id: String?, val name: String, val lore: List<String>)

    /** Which page arrows a menu shows. A single-page sack shows neither. */
    class PageNav(val hasPrev: Boolean, val hasNext: Boolean)

    private val STORED = Regex("""^Stored: .*""")

    /** A gemstone row's per-tier line, `Rough: 1,234` (the tier word, then a count). */
    private val GEM_TIER = Regex("""^(Rough|Flawed|Fine|Flawless|Perfect)\b[^:]*:\s*[\d,]+""", RegexOption.IGNORE_CASE)

    private val GEM_WORDS = setOf("rough", "flawed", "fine", "flawless", "perfect", "gemstone", "gem", "gemstones")

    /**
     * The item ids [slot] stands for. In the Gemstone Sack ([gemstone]) a row lists per-tier counts and stands for
     * `<TIER>_<TYPE>_GEM` of each tier line, the type being the row's name without tier and "Gemstone" words
     * (`Jade Gemstones` + `Rough: … | Flawed: … | Fine: …` = ROUGH/FLAWED/FINE_JADE_GEM; seen live 2026-11-23: the
     * row's own id is the rough gem and it has a `Stored:` line too). Every other row with a `Stored:` line is its
     * SkyBlock id. Borders, arrows and buttons are no items.
     */
    fun idsOf(slot: MenuSlot, gemstone: Boolean): Set<String> {
        val lines = slot.lore.map { SackParsing.strip(it).trim() }
        if (gemstone) {
            val tiers = lines.mapNotNull { GEM_TIER.find(it)?.groupValues?.get(1)?.uppercase(Locale.ROOT) }
            val type = SackParsing.strip(slot.name).trim().split(Regex("""\s+"""))
                .filter { it.isNotEmpty() && it.lowercase(Locale.ROOT) !in GEM_WORDS }
                .joinToString("_") { it.uppercase(Locale.ROOT) }
            if (tiers.isNotEmpty() && type.isNotEmpty()) return tiers.mapTo(LinkedHashSet()) { "${it}_${type}_GEM" }
        }
        if (slot.id != null && lines.any { STORED.matches(it) }) return setOf(slot.id)
        return emptySet()
    }

    /** The union of [idsOf] over a menu's container slots. */
    fun idsOf(slots: List<MenuSlot>, gemstone: Boolean): Set<String> {
        val out = LinkedHashSet<String>()
        for (slot in slots) out += idsOf(slot, gemstone)
        return out
    }

    /** Whether the menu is the Gemstone Sack (its rows list per-tier counts). */
    fun isGemstone(title: String): Boolean = title.contains("Gemstone", ignoreCase = true)

    /** The page arrows among [slots]: an item named "Next Page" / "Previous Page". */
    fun pageNav(slots: List<MenuSlot>): PageNav {
        var prev = false
        var next = false
        for (slot in slots) {
            val name = SackParsing.strip(slot.name).trim().lowercase(Locale.ROOT)
            if (name == "next page") next = true
            if (name == "previous page" || name == "prev page") prev = true
        }
        return PageNav(prev, next)
    }

    /**
     * Whether the player's [seen] ids differ from the catalog's [known] set for the sack: report when the
     * catalog has no entry for it (null) or has another set. Empty [seen] never differs (nothing to say).
     */
    fun differs(known: Set<String>?, seen: Set<String>): Boolean = seen.isNotEmpty() && known != seen

    /**
     * Collects one sack's pages. A menu with page arrows is only complete once the player has shown its first
     * page (no previous arrow) and its last (no next arrow) in one visit; a visit ends when another sack opens
     * or no sack menu was open for [GAP_MS]. A sack without arrows is complete on its first read.
     */
    class Pages {
        private var title: String? = null
        private val ids = LinkedHashSet<String>()
        private var seenFirst = false
        private var seenLast = false
        private var lastAt = 0L

        /**
         * Adds one read page; returns the complete id set when this read finished the sack, else null.
         * Reading the same page again is harmless.
         */
        fun add(title: String, ids: Set<String>, nav: PageNav, now: Long): Set<String>? {
            if (this.title != title || now - lastAt > GAP_MS) {
                this.title = title
                this.ids.clear()
                seenFirst = false
                seenLast = false
            }
            lastAt = now
            this.ids += ids
            if (!nav.hasPrev) seenFirst = true
            if (!nav.hasNext) seenLast = true
            return if (seenFirst && seenLast && this.ids.isNotEmpty()) LinkedHashSet(this.ids) else null
        }

        /** Keeps a visit alive while the menu is open without a new read (the player is on a page already read). */
        fun touch(now: Long) {
            lastAt = now
        }

        companion object {
            /** Hypixel reopens the window for a page flip within a few ticks; a longer gap is another visit. */
            const val GAP_MS = 5_000L
        }
    }
}
