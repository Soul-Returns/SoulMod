package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.gui.Theme
import com.soulreturns.features.profileviewer.gui.ProfileCanvas
import kotlinx.serialization.json.JsonObject
import java.util.Locale

/**
 * The Profile Viewer's rendering of the `mineshaft` soul-stats namespace.
 *
 * It lives here, next to the feature that produces the data, rather than inside `SoulStatsTab`: the
 * tab's contract is that it renders *any* namespace generically and only knows a title for the ones
 * it has heard of. A feature that wants better than generic brings its own drawing, and the tab
 * dispatches to it. So a future tracker adds a file here, not a branch there.
 *
 * Returning false from [draw] means "I could not make sense of this payload" and the tab falls back to
 * the generic scalar renderer — which is the right answer if the backend ever restructures the
 * namespace ahead of a mod release. The contract is additive-only, so this should not happen; the
 * fallback exists because "should not happen" is not a rendering strategy.
 *
 * All rows are pure drawing: [ProfileCanvas.section] lays its body out twice (once to measure), so
 * anything with a side effect here would happen twice per frame.
 */
object MineshaftStatsSection {
    /** @return false if [payload] is not a mineshaft stats body, so the caller can render it generically. */
    fun draw(
        c: ProfileCanvas,
        payload: JsonObject,
    ): Boolean {
        val stats = MineshaftStats.from(payload) ?: return false
        if (stats.totalVisits == 0) return false

        overview(c, stats)
        sources(c, stats)
        corpses(c, stats)
        byType(c, stats)
        return true
    }

    private fun overview(
        c: ProfileCanvas,
        stats: MineshaftStats,
    ) {
        c.section("Mineshafts", "Glacite Mineshaft visits Soul recorded") {
            statRow("Visits", groupThousands(stats.totalVisits.toLong()))
            statRow("Time inside", formatDuration(stats.totalDurationSec))
            statRow("Average visit", formatDuration(stats.averageDurationSec.toLong()))
            divider()
            statRow(
                "Littlefoot found",
                "${groupThousands(stats.littlefootFound.toLong())}  (${percent(stats.littlefootFindRate)})",
                valueColor = if (stats.littlefootFound > 0) Theme.ACCENT else Theme.TEXT,
            )
        }
    }

    private fun sources(
        c: ProfileCanvas,
        stats: MineshaftStats,
    ) {
        if (stats.bySource.isEmpty()) return
        c.section(
            "How they were found",
            "Only self-discovered visits describe your own odds — a mineshaft you were warped into was " +
                "someone else's roll, and one Soul could not classify might have been either",
        ) {
            row(this, "Self-discovered", stats.bySource[VisitSource.DISCOVERED], stats.totalVisits, Theme.ACCENT)
            row(this, "Warped in", stats.bySource[VisitSource.WARPED], stats.totalVisits, Theme.TEXT)
            row(this, "Unclassified", stats.bySource[VisitSource.UNKNOWN], stats.totalVisits, Theme.TEXT_FAINT)
        }
    }

    private fun row(
        c: ProfileCanvas,
        label: String,
        source: SourceStats?,
        totalVisits: Int,
        color: Int,
    ) {
        val visits = source?.visits ?: 0
        val share = if (totalVisits > 0) visits.toDouble() / totalVisits else 0.0
        c.statRow(label, "${groupThousands(visits.toLong())}  (${percent(share)})", valueColor = color)
    }

    private fun corpses(
        c: ProfileCanvas,
        stats: MineshaftStats,
    ) {
        if (stats.byCorpse.isEmpty()) return
        c.section(
            "Frozen Corpses",
            "\"In\" is how many mineshafts had at least one; \"Avg\" is how many there were when there " +
                "were any",
        ) {
            val widths = columns(width)
            tableRow(listOf("Corpse", "Found", "Looted", "Loot %", "In", "Avg"), widths, header = true)
            for ((type, corpse) in stats.byCorpse.entries.sortedByDescending { it.value.found }) {
                tableRow(
                    listOf(
                        type,
                        groupThousands(corpse.found.toLong()),
                        groupThousands(corpse.looted.toLong()),
                        percent(corpse.lootRate),
                        percent(corpse.spawnRate),
                        decimal(corpse.avgFoundWhenSpawned),
                    ),
                    widths,
                )
            }
        }
    }

    private fun byType(
        c: ProfileCanvas,
        stats: MineshaftStats,
    ) {
        if (stats.byType.isEmpty()) return
        c.section("By mineshaft type") {
            val widths = columns(width)
            tableRow(listOf("Type", "Visits", "Avg time", "Littlefoot", "Rate", ""), widths, header = true)
            for ((type, bucket) in stats.byType.entries.sortedByDescending { it.value.visits }) {
                tableRow(
                    listOf(
                        // Raw identifiers on purpose: "Onyx" and "Littlefoot's Den" are display names
                        // the backend only ever applies in its own admin templates, never in JSON.
                        type,
                        groupThousands(bucket.visits.toLong()),
                        formatDuration(bucket.averageDurationSec.toLong()),
                        groupThousands(bucket.littlefootFound.toLong()),
                        percent(bucket.littlefootRate),
                        "",
                    ),
                    widths,
                )
            }
        }
    }

    /** Six columns sized off the available width, so a narrow card still fits its numbers. */
    private fun columns(width: Int): IntArray {
        val first = (width * 0.26f).toInt().coerceIn(60, 130)
        val rest = ((width - first) / 5).coerceAtLeast(34)
        return intArrayOf(first, rest, rest, rest, rest, rest)
    }

    private fun formatDuration(seconds: Long): String {
        if (seconds <= 0) return "0s"
        val h = seconds / 3600
        val m = (seconds % 3600) / 60
        val s = seconds % 60
        return if (h > 0) {
            String.format(Locale.ROOT, "%dh %02dm", h, m)
        } else if (m > 0) {
            String.format(Locale.ROOT, "%dm %02ds", m, s)
        } else {
            "${s}s"
        }
    }

    private fun percent(rate: Double): String = String.format(Locale.ROOT, "%.1f%%", rate * 100)

    private fun decimal(value: Double): String = String.format(Locale.ROOT, "%.2f", value)

    /** `1 234 567` — a thin space, because a comma or a dot means different things per locale. */
    private fun groupThousands(value: Long): String =
        String.format(Locale.ROOT, "%,d", value).replace(',', ' ')
}
