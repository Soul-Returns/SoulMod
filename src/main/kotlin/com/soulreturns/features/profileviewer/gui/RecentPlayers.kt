package com.soulreturns.features.profileviewer.gui

import com.soulreturns.stats.PersistentStats
import kotlinx.serialization.Serializable

/**
 * The players you looked up last, for the search dropdown and the "not found" chips.
 *
 * Stored in `stats.json` (namespace `spvRecent`, one global bucket per account), because it is the
 * player's own data and must survive updates and restarts; the name and the account uuid (for the
 * face; null in entries saved before it was kept), no profile data, newest first, at most [MAX].
 * Your own name is never recorded (the You row covers it).
 */
object RecentPlayers {
    const val MAX = 8

    @Serializable
    data class Entry(val name: String, val at: Long, val uuid: String? = null)

    @Serializable
    data class Data(val players: List<Entry> = emptyList())

    private val handle by lazy {
        PersistentStats.namespace("spvRecent", Data.serializer(), { Data() }, { "all" })
    }

    fun list(): List<Entry> = try {
        handle.read().players
    } catch (_: Throwable) {
        emptyList()
    }

    fun remember(name: String, uuidUndashed: String? = null, nowMs: Long = System.currentTimeMillis()) {
        try {
            handle.update { copy(players = push(players, name, nowMs, uuidUndashed)) }
        } catch (_: Throwable) {
            // The list is a convenience; a store that is not open yet must not break a lookup.
        }
    }

    /** [name] moved (or added) to the front, a different-case duplicate dropped, capped at [MAX]. */
    fun push(players: List<Entry>, name: String, nowMs: Long, uuidUndashed: String? = null): List<Entry> =
        (listOf(Entry(name, nowMs, uuidUndashed)) + players.filterNot { it.name.equals(name, ignoreCase = true) }).take(MAX)
}
