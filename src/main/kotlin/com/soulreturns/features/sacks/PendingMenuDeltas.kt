package com.soulreturns.features.sacks

/**
 * Changes a sack menu read already counted, waiting for the `[Sacks]` chat summary that reports the
 * same change a while later (taking items out of a sack by hand shows up in chat after its window).
 * A summary line consumes matching entries (same item, same sign, inside its window) instead of being
 * added again; whatever it reports beyond them (`/gfs`, crafting, pickups) still counts.
 */
class PendingMenuDeltas {
    private class Entry(val at: Long, var amount: Long)

    private val byId = HashMap<String, ArrayList<Entry>>()

    /** A menu read moved [id] by [delta] at [now]. */
    fun record(id: String, delta: Long, now: Long) {
        if (delta == 0L) return
        val list = byId.getOrPut(id) { ArrayList() }
        list.removeAll { now - it.at > RETAIN_MS }
        list += Entry(now, delta)
    }

    /**
     * Take what a summary line of [delta] for [id] with a window of [windowMs] ending at [now] already
     * had counted. Returns the signed amount consumed (same sign as [delta], at most its size).
     */
    fun consume(id: String, delta: Long, now: Long, windowMs: Long): Long {
        val list = byId[id] ?: return 0L
        list.removeAll { now - it.at > RETAIN_MS }
        var left = kotlin.math.abs(delta)
        var taken = 0L
        val from = now - windowMs - SLACK_MS
        val it = list.iterator()
        while (it.hasNext() && left > 0) {
            val entry = it.next()
            if (entry.at < from || (entry.amount > 0) != (delta > 0)) continue
            val part = minOf(left, kotlin.math.abs(entry.amount))
            left -= part
            taken += part
            entry.amount -= if (entry.amount > 0) part else -part
            if (entry.amount == 0L) it.remove()
        }
        if (list.isEmpty()) byId.remove(id)
        return if (delta > 0) taken else -taken
    }

    fun clear() = byId.clear()

    private companion object {
        /** Entries older than this can no longer be inside any summary window. */
        const val RETAIN_MS = 120_000L

        /** Summary windows are counted from the server; allow clock/latency skew. */
        const val SLACK_MS = 5_000L
    }
}
