package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** Small readers shared by the Money and Progress lines. */
internal object Stat {
    private val COLOR = Regex("^(?:§#[0-9a-fA-F]{6}|§[0-9a-fA-FzZ])")
    private val PLAIN_NUMBER = Regex("^(-?[\\d,]+(?:\\.\\d+)?)(\\D*)$")

    /** Hypixel's own `(+912)` after a counter. */
    val GAIN = Regex("\\s*\\((\\+[^)]*)\\)$")

    /** The first color code of [legacy] after its leading spaces/resets, or null. */
    fun leadingColor(legacy: String): String? {
        var i = 0
        while (i < legacy.length) {
            when {
                legacy[i] == ' ' -> i++
                legacy.startsWith("§r", i) || legacy.startsWith("§R", i) -> i += 2
                else -> return COLOR.find(legacy.substring(i))?.value
            }
        }
        return null
    }

    /** `2,963,902` in the Look's number format (`1.19B`); `0♨`, `6.5k` and other shapes stay as Hypixel wrote them. */
    fun number(ctx: LineContext, text: String): String {
        val m = PLAIN_NUMBER.matchEntire(text) ?: return text
        val v = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return text
        return ctx.number(v) + m.groupValues[2]
    }

    fun parse(text: String): Double? = text.replace(",", "").toDoubleOrNull()

    /** Every line of every tab widget, color-stripped, in display order. */
    fun tabTexts(s: ScoreboardSnapshot): List<String> = s.tabWidgets.flatMap { w -> w.lines.map { it.text.trim() } }

    /** The first tab widget line `Label: value` (value after the colon, stripped), or null. */
    fun tabValue(s: ScoreboardSnapshot, label: String): String? {
        val re = Regex("${Regex.escape(label)}:\\s*(.+)")
        for (t in tabTexts(s)) re.matchEntire(t)?.let { return it.groupValues[1].trim() }
        return null
    }

    fun strip(s: String) = LegacyText.strip(s)

    /** The signed-in player's profile as Hypixel's API last reported it (null until loaded, always on Alpha). */
    var member: () -> SkyblockProfileMember? = { SkyblockSelfProfile.snapshot?.member }
}
