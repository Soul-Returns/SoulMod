package com.soulreturns.features.mining.nucleus

/**
 * The tab list's `Crystals:` widget (capture `2026-11-08_04-25-39`): a `Crystals:` header, then one
 * `<Name>: <glyph> <status>` line per crystal, status `✖ Not Found` / `✖ Not Placed` (found) /
 * `✔ Placed`. The whole status after the glyph is matched (`Not Placed` contains `Placed`); unknown
 * statuses are skipped, crystal names are taken as printed (no closed list).
 */
object NucleusTabCrystals {
    private val HEADER = Regex("""^Crystals:?$""", RegexOption.IGNORE_CASE)
    private val ENTRY = Regex("""^([A-Za-z][A-Za-z' ]*?):\s*(.+)$""")

    private val STATUSES: Map<String, CrystalState> =
        mapOf(
            "not found" to CrystalState.MISSING,
            "not placed" to CrystalState.FOUND,
            "placed" to CrystalState.PLACED,
        )

    /** Pure: crystal -> state from the tab lines (legacy text), or null without the widget. */
    fun parse(lines: List<String>): Map<String, CrystalState>? {
        var inWidget = false
        var result: LinkedHashMap<String, CrystalState>? = null
        for (raw in lines) {
            val line = NucleusLines.clean(raw)
            if (HEADER.matches(line)) {
                inWidget = true
                result = result ?: LinkedHashMap()
                continue
            }
            if (!inWidget) continue
            val m = ENTRY.matchEntire(line)
            if (m == null) {
                inWidget = false
                continue
            }
            val status = m.groupValues[2].trimStart { !it.isLetter() }.lowercase()
            STATUSES[status]?.let { result?.put(m.groupValues[1].trim(), it) }
        }
        return result
    }
}

/**
 * Which `Crystals:` tab states override the run state. Pure (clock passed in); [NucleusRuns] owns one
 * and calls [changes] once a second.
 *
 * The widget lags chat by 1-3 s (capture `2026-11-08_04-25-39`), so a state equal to the previous read
 * is stale right after a chat event and only wins once no chat event came for [GRACE_MS]; a state that
 * changed since the previous read wins at once. After the loot bundle the widget keeps the finished run
 * (every crystal `✔ Placed`) for another 4-10 s: reads with a `Placed` are ignored until it resets.
 */
class NucleusTabRestore {
    private var last: Map<String, CrystalState>? = null
    private var bundleHoldUntil = 0L
    private var missingHoldUntil = 0L

    /** A new server: the widget may still show the old lobby or be empty, so it must not clear states. */
    fun onServerChange(now: Long) {
        missingHoldUntil = now + SERVER_HOLD_MS
    }

    fun onBundle(now: Long) {
        bundleHoldUntil = now + BUNDLE_HOLD_MS
    }

    /**
     * @param states the widget now ([NucleusTabCrystals.parse]), null when not shown
     * @param current the run state per crystal (absent = missing)
     * @param lastEventMs when the last nucleus chat event arrived
     * @return the crystals whose tab state wins
     */
    fun changes(
        states: Map<String, CrystalState>?,
        current: Map<String, CrystalState>,
        now: Long,
        lastEventMs: Long,
    ): Map<String, CrystalState> {
        val previous = last
        last = states
        if (states == null) return emptyMap()
        if (now < bundleHoldUntil) {
            if (states.values.any { it == CrystalState.PLACED }) return emptyMap()
            bundleHoldUntil = 0L
        }
        val settled = now - lastEventMs >= GRACE_MS
        val holdMissing = now < missingHoldUntil
        return states.filter { (name, state) ->
            !(holdMissing && state == CrystalState.MISSING) &&
                (settled || previous?.get(name) != state) && (current[name] ?: CrystalState.MISSING) != state
        }
    }

    companion object {
        /** Tab states equal to the previous read are ignored this long after a chat event. */
        const val GRACE_MS = 5_000L

        /** Longest wait for the widget to reset after a bundle (seen: 4 s and 9.6 s). */
        const val BUNDLE_HOLD_MS = 30_000L

        /** After a server change the tab never resets a crystal to missing for this long. */
        const val SERVER_HOLD_MS = 30_000L
    }
}
