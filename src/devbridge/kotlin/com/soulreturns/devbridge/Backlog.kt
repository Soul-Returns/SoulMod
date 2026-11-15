package com.soulreturns.devbridge

import java.util.ArrayDeque

/**
 * Always-on ring of the last minutes of low-volume events (chat, titles, boss bar names, place
 * changes, joins, menu opens, marks) so an agent attaching mid-activity gets context. Never
 * packets or provider frames. Bounded by age, count and bytes; [costNanos] is what it took.
 */
object Backlog {
    val KINDS = setOf("chat", "title", "bossbar", "location", "connection", "mark", "screen_open", "record")

    private val lines = ArrayDeque<Pair<Long, String>>()
    private var bytes = 0L

    @Volatile
    var added = 0L
        private set

    /** Total time spent building and storing backlog lines (includes the JSON of the event). */
    @Volatile
    var costNanos = 0L

    @Synchronized
    fun add(t: Long, line: String) {
        lines.addLast(t to line)
        bytes += line.length
        added++
        val cutoff = t - MAX_AGE_MS
        while (lines.isNotEmpty() && (lines.size > MAX_LINES || bytes > MAX_BYTES || lines.first.first < cutoff)) {
            bytes -= lines.removeFirst().second.length
        }
    }

    @Synchronized
    fun lines(): List<String> {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        return lines.filter { it.first >= cutoff }.map { it.second }
    }

    @Synchronized
    fun describe(): Map<String, Any?> =
        linkedMapOf(
            "events" to lines.size,
            "bytes" to bytes,
            "oldest_s_ago" to lines.firstOrNull()?.let { (System.currentTimeMillis() - it.first) / 1000 },
            "added_total" to added,
            "cost_ms_total" to costNanos / 1_000_000.0,
            "cost_us_per_event" to if (added == 0L) null else costNanos / 1000.0 / added,
            "keeps" to "${MAX_AGE_MS / 60_000} min, ≤$MAX_LINES events, ≤${MAX_BYTES / 1024} KiB",
        )

    private const val MAX_AGE_MS = 5 * 60_000L
    private const val MAX_LINES = 2000
    private const val MAX_BYTES = 1L shl 20
}
