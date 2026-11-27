package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import java.util.Locale

/**
 * A line drawn from Soul's own feature state (tag "Soul"). Each reads what its feature already tracks;
 * a line whose feature is switched off, or that has no data, draws nothing. The text itself comes from a
 * pure `format` function next to the reader so it can be tested without Minecraft.
 */
abstract class SoulLine(id: String, name: String, description: String, example: List<String>, override val where: String? = null) :
    LineType(id, ScoreboardCatalog.SOUL, name, description, example) {
    override val tag = "Soul"
}

/** Text helpers of the Soul lines. */
internal object SoulText {
    const val SEP = " §8· "

    /** `12:41`, or `1:02:03` from an hour on. */
    fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) String.format(Locale.ROOT, "%d:%02d:%02d", s / 3600, s / 60 % 60, s % 60) else String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    /** `4`, `4.5` (a whole `.0` dropped). */
    fun plain(v: Double): String = if (v == Math.floor(v) && Math.abs(v) < 1e15) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v).trimEnd('0').trimEnd('.')
}
