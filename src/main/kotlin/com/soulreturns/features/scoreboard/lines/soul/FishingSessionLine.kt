package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.fishing.FishingTimer
import com.soulreturns.features.fishing.FishingTracker
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext

/**
 * `Fishing: 12:41 · 38 creatures`: the stopwatch of active fishing ([FishingTimer]) and this session's
 * catches ([FishingTracker.session]; rule://fishing). The tracker counts whether or not its panel is on,
 * so the line shows as soon as the session has a catch or time; nothing before.
 */
object FishingSessionLine : SoulLine(
    "fishing", "Fishing session", "Session time and sea creatures caught.",
    listOf("§fFishing: §f12:41 §8· §b38 §7creatures"),
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFFFFFF)

    fun format(ms: Long, catches: Long): String? {
        if (ms <= 0 && catches <= 0) return null
        return "§fFishing: §f${SoulText.clock(ms)}${SoulText.SEP}§b$catches §7creature${if (catches == 1L) "" else "s"}"
    }

    override fun lines(ctx: LineContext): List<String> = listOfNotNull(format(FishingTimer.totalMs, FishingTracker.session.catches))
}
