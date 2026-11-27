package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/**
 * `Drill Fuel: 39,764/100k`: the held drill's fuel from the action bar (`§22,136/3k Drill Fuel`, captured
 * 2026-11 in the Dwarven Mines). The bar is replaced now and then by other messages, so the last reading
 * is kept for [HOLD_MS]; it is gone once the bar has not shown the fuel for that long (drill put away).
 */
object DrillFuelLine : SoulLine(
    "drill", "Drill fuel", "Fuel left in the held drill.",
    listOf("§fDrill Fuel: §239,764§7/§2100k"), "Holding a drill",
) {
    const val HOLD_MS = 10_000L

    override val colors = LineColors.LabelValue(0xFFFFFF, 0x00AA00)

    private val FUEL = Regex("([\\d,.]+[kKmM]?)/([\\d,.]+[kKmM]?) Drill Fuel")

    private var last: Pair<String, String>? = null
    private var lastAt = 0L

    /** Current and capacity as printed, from the action bar's text. */
    fun read(bar: String?): Pair<String, String>? = bar?.let { FUEL.find(it) }?.let { it.groupValues[1] to it.groupValues[2] }

    fun format(fuel: Pair<String, String>): String = "§fDrill Fuel: §2${fuel.first}§7/§2${fuel.second}"

    /** The fuel to show at [now], updating the held reading from [s]. */
    fun current(s: ScoreboardSnapshot, now: Long): Pair<String, String>? {
        read(s.actionBar?.text)?.let {
            last = it
            lastAt = now
        }
        return last?.takeIf { now - lastAt <= HOLD_MS }
    }

    /** `{fuel}`: the fuel left (`§239,764`). */
    fun placeholder(s: ScoreboardSnapshot, now: Long): String? = current(s, now)?.let { "§2${it.first}" }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(current(ctx.sources, ctx.nowMs)?.let(::format))

    internal fun reset() {
        last = null
        lastAt = 0L
    }
}
