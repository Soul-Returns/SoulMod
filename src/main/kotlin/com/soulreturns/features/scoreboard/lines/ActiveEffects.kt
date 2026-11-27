package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * What the tab list footer says about your Booster Cookie and God Potion. Real Hypixel footer
 * (recorded 2026-11-07/08):
 * ```
 * Active Effects
 * You have a God Potion active! 39 hours
 * You have 12 non-god effects.
 * Cookie Buff
 * 2 days, 21 hours
 * ```
 * Without a cookie the line under `Cookie Buff` starts with `Not active`; without a potion the God
 * Potion line is missing. Times are shortened to Hypixel's scoreboard style (`2d 21h`).
 */
internal object ActiveEffects {
    private val GOD_POTION = Regex("You have an? God Potion active! (.+)")
    private val PART = Regex("(\\d+)\\s*(day|hour|minute|second)s?", RegexOption.IGNORE_CASE)

    /** Time left on the Booster Cookie as `2d 21h`, or null (none active, or the footer has no cookie section). */
    fun cookie(s: ScoreboardSnapshot): String? {
        val lines = s.tabFooter
        val at = lines.indexOfFirst { it.text.trim() == "Cookie Buff" }
        if (at < 0) return null
        val value = lines.getOrNull(at + 1)?.text?.trim() ?: return null
        if (value.isEmpty() || value.startsWith("Not active", ignoreCase = true)) return null
        return compact(value)
    }

    /** Time left on the God Potion as `39h`, or null. */
    fun godPotion(s: ScoreboardSnapshot): String? {
        for (l in s.tabFooter) GOD_POTION.matchEntire(l.text.trim())?.let { return compact(it.groupValues[1]) }
        return null
    }

    /** `2 days, 21 hours` -> `2d 21h`; text that is not a list of amounts is returned as it is. */
    fun compact(text: String): String {
        val parts = PART.findAll(text).toList()
        if (parts.isEmpty()) return text
        return parts.joinToString(" ") { it.groupValues[1] + it.groupValues[2].first().lowercaseChar() }
    }
}
