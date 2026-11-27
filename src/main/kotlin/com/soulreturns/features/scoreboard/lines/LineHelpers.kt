package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions

/** Hypixel's indented lines (` Late Summer 22nd`) without their leading space when "Indent like Hypixel" is off. */
internal fun indented(ctx: LineContext, legacy: String): String {
    if (ctx.options.bool("indent")) return legacy
    var i = 0
    while (i + 1 < legacy.length && legacy[i] == '§') i += if (legacy[i + 1] == '#') 8 else 2
    var j = i
    while (j < legacy.length && legacy[j] == ' ') j++
    return legacy.substring(0, i) + legacy.substring(j)
}

/** Option key of [LineOptions.indent]. */
internal const val INDENT = "indent"

/**
 * Soul's own "(+N)" for a counter Hypixel shows without one: an increase stays visible for [showMs].
 * The first reading (and one after a gap with no value) is never a gain.
 */
class GainTracker(private val showMs: Long = 5_000) {
    private var last: Double? = null
    private var gain = 0.0
    private var until = 0L

    fun update(value: Double?, nowMs: Long): Double? {
        if (frozen) return null
        if (value == null) {
            last = null
            return null
        }
        val prev = last
        last = value
        if (prev != null && value > prev) {
            gain = if (nowMs < until) gain + (value - prev) else value - prev
            until = nowMs + showMs
        }
        return if (nowMs < until) gain else null
    }

    companion object {
        /** Set while the setup screen's preview builds: its sample values are never readings, so they never make a gain. */
        @Volatile
        var frozen = false
    }
}
