package com.soulreturns.features.search.forge

import kotlin.math.roundToInt

/**
 * Pure readers of the forge menus (`docs/research/forge-2026-11-16.md` §1, §4a, §4b). Inputs are names and
 * lore lines with colors already stripped (`MessageDetector.stripColorCodes`). Names are never checked
 * against a list (categories, requirements and items change with Hypixel updates).
 */
object ForgeParsing {
    private val DURATION_PART = Regex("""(\d+)([dhms])""")
    private val DURATION_LINE = Regex("""^Duration: ((?:\d+[dhms] ?)+)$""")
    private val REMAINING = Regex("""^Time Remaining: ((?:\d+[dhms] ?)+)$""")
    private val MAKING = Regex("""^Currently making: (.+)$""")
    private val IDLE_NAME = Regex("""^Slot #(\d+)$""")
    private val PICKER_TITLE = Regex("""^Select Process \(Slot #(\d+)\)$""")
    private val PAGE_TITLE = Regex("""^(?:\((\d+)/(\d+)\) )?(.+)$""")
    private val REQUIRES = Regex("""^Requires (.+)$""")
    private val PERCENT = Regex("""^by ([\d.]+)%\.$""")
    private val LEVEL = Regex("""^Level (\d+)""")
    private val COINS = Regex("""^([\d,]+) Coins$""")
    private val INGREDIENT = Regex("""^(?:[\uE000-\uF8FF] )?(.+?)(?: x([\d,]+))?$""")

    const val FORGE_TITLE = "The Forge"
    const val CONFIRM_TITLE = "Confirm Process"
    const val HOTM_TITLE = "Heart of the Mountain"
    const val RESULTS_TITLE = "Results"
    /** Forge main menu slots 10–16 = forge slots #1–#7. */
    val FORGE_SLOTS = 10..16
    /** The 7×4 recipe grid of a category page. */
    val GRID = listOf(10..16, 19..25, 28..34, 37..43).flatten()

    /** `21s`, `5h 36m`, `4d 21h 36m` → seconds; null when nothing matches. */
    fun seconds(text: String): Long? {
        var sum = 0L
        var any = false
        for (m in DURATION_PART.findAll(text)) {
            any = true
            val v = m.groupValues[1].toLong()
            val unit = m.groupValues[2]
            sum += v * (if (unit == "d") 86400 else if (unit == "h") 3600 else if (unit == "m") 60 else 1)
        }
        return if (any) sum else null
    }

    // ---------------------------------------------------------------- The Forge (main)

    sealed interface Slot
    data object Idle : Slot
    data class Busy(val outputId: String, val name: String, val remainingSeconds: Long) : Slot
    /** Anything else (finished/claimable and locked slots were never captured): never guessed. */
    data object Unknown : Slot

    fun forgeSlot(skyblockId: String?, name: String, lore: List<String>): Slot {
        if (skyblockId == null && IDLE_NAME.matches(name) && lore.firstOrNull() == "View and start forge processes") return Idle
        if (skyblockId != null) {
            val making = lore.firstNotNullOfOrNull { MAKING.matchEntire(it)?.groupValues?.get(1) }
            val left = lore.firstNotNullOfOrNull { REMAINING.matchEntire(it)?.groupValues?.get(1) }?.let(::seconds)
            if (making != null && left != null) return Busy(skyblockId, making, left)
        }
        return Unknown
    }

    // ---------------------------------------------------------------- Select Process / category pages

    fun isPicker(title: String) = PICKER_TITLE.matches(title)

    class PageTitle(val category: String, val page: Int, val pages: Int)

    /** `(1/2) Gear` → Gear, 1, 2; `Refining` → Refining, 1, 1. [RESULTS_TITLE] is the search, not a category. */
    fun pageTitle(title: String): PageTitle {
        val m = PAGE_TITLE.matchEntire(title)!!
        val page = m.groupValues[1].toIntOrNull() ?: 1
        return PageTitle(m.groupValues[3], page, m.groupValues[2].toIntOrNull() ?: 1)
    }

    sealed interface Entry
    /** A forgeable recipe: its output, ingredient names in menu order, coins and the shown duration. */
    data class Recipe(val outputId: String, val name: String, val ingredients: List<String>, val coins: Long, val shownSeconds: Long?) : Entry
    /** A locked placeholder (`gray_dye`, red name, no id): its name and `Requires …` text. */
    data class Locked(val name: String, val requirement: String) : Entry

    /** A grid item of a recipe page, or null when it is neither shape. */
    fun entry(skyblockId: String?, name: String, lore: List<String>): Entry? {
        if (skyblockId == null) {
            if (lore.size != 1) return null
            REQUIRES.matchEntire(lore[0]) ?: return null
            return Locked(name, lore[0])
        }
        val start = lore.indexOf("Items Required")
        if (start < 0) return null
        val ingredients = ArrayList<String>()
        var coins = 0L
        var i = start + 1
        while (i < lore.size && lore[i].isNotBlank()) {
            val line = lore[i].trim()
            val c = COINS.matchEntire(line)
            if (c != null) coins += c.groupValues[1].replace(",", "").toLong()
            else INGREDIENT.matchEntire(line)?.let { ingredients += it.groupValues[1] }
            i++
        }
        val duration = lore.drop(i).firstNotNullOfOrNull { DURATION_LINE.matchEntire(it.trim())?.groupValues?.get(1) }?.let(::seconds)
        return Recipe(skyblockId, name, ingredients, coins, duration)
    }

    // ---------------------------------------------------------------- Quick Forge

    class QuickForge(val level: Int, val percent: Double)

    /**
     * The `Quick Forge` perk in Heart of the Mountain: the percent of the FIRST `by X%.` line (the current
     * level; a second one after `=====[ UPGRADE ]=====` is the next level's). Not unlocked (coal, `Click to
     * unlock!`) or switched off (`DISABLED`) = 0 %.
     */
    fun quickForge(material: String, lore: List<String>): QuickForge? {
        val level = lore.firstNotNullOfOrNull { LEVEL.find(it.trim())?.groupValues?.get(1)?.toIntOrNull() } ?: 0
        if (material == "minecraft:coal" || lore.any { it.trim() == "Click to unlock!" || it.trim() == "DISABLED" }) return QuickForge(0, 0.0)
        val percent = lore.firstNotNullOfOrNull { PERCENT.matchEntire(it.trim())?.groupValues?.get(1)?.toDoubleOrNull() } ?: return null
        return QuickForge(level, percent)
    }

    /**
     * Quick Forge from a recipe page: shown ÷ base duration of its longest recipe (research §4a: a recipe page
     * reflects loadout, toggle and boosts). Only from recipes of at least [MIN_BASE] so whole-minute rounding
     * cannot move the result past the nearest half percent; null otherwise.
     */
    fun quickForgeFromPage(pairs: List<Pair<Long, Long>>): Double? {
        val (shown, base) = pairs.filter { it.second >= MIN_BASE }.maxByOrNull { it.second } ?: return null
        val percent = (1 - shown.toDouble() / base) * 100
        return ((percent * 2).roundToInt() / 2.0).coerceIn(0.0, 100.0)
    }

    const val MIN_BASE = 6 * 3600L
}
