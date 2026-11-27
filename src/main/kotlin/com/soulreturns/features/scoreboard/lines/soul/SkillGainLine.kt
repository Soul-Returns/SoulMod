package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `+42.7 Combat (39.52%)`: the last skill XP you got, from the action bar (`§3+200.5 Combat (659,020,723/0)`,
 * captured 2026-11; the bracket is Hypixel's progress, a percent or `have/need`). The bar shows it for a
 * few seconds only, so the line keeps the last gain for [HOLD_MS] after it left the bar.
 */
object SkillGainLine : SoulLine(
    "skillgain", "Last skill gain", "The last skill XP you got and the progress.",
    listOf("§3+42.7 Combat §7(39.52%)"),
) {
    const val HOLD_MS = 20_000L

    override val colors = LineColors.Text(0x00AAAA)

    class Gain(val amount: String, val skill: String, val progress: String)

    private val GAIN = Regex("\\+([\\d,.]+) ([A-Za-z]+(?: [A-Za-z]+)?) \\(([^)]+)\\)")

    private var last: Gain? = null
    private var lastAt = 0L

    fun read(bar: String?): Gain? = bar?.let { GAIN.find(it) }?.let { Gain(it.groupValues[1], it.groupValues[2], it.groupValues[3]) }

    fun format(g: Gain): String = "§3+${g.amount} ${g.skill} §7(${g.progress})"

    fun current(s: ScoreboardSnapshot, now: Long): Gain? {
        read(s.actionBar?.text)?.let {
            last = it
            lastAt = now
        }
        return last?.takeIf { now - lastAt <= HOLD_MS }
    }

    /** `{skill}`: `§3+42.7 Combat`. */
    fun placeholder(s: ScoreboardSnapshot, now: Long): String? = current(s, now)?.let { "§3+${it.amount} ${it.skill}" }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(current(ctx.sources, ctx.nowMs)?.let(::format))

    internal fun reset() {
        last = null
        lastAt = 0L
    }
}
