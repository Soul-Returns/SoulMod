package com.soulreturns.features.profit.dragon

import java.util.ArrayDeque

/**
 * A ring of the last [CAPACITY] loot stands the scanner parsed, for `/soul dev dragons`.
 *
 * ### Why this exists
 * Three independent heuristics feed one number: the `"<Name> xN"` stand-text regex, the
 * display-name → item-id reverse lookup against the drop graph, and the pet rarity code. When any of
 * them misses, the drop is simply **not counted** — there is no error, no gap in the panel, nothing
 * anywhere that says a stand went unread. An unresolved stand is a silent zero, and a silent zero
 * looks exactly like a dragon that dropped less than usual.
 *
 * It is also precisely the signal an admin needs on the other side: an entry reading `unresolved`
 * carries the verbatim text Hypixel wrote on the stand, which is what a `displayNameOverride` has to
 * be set to.
 *
 * Bounded, in memory, and never written anywhere. Twenty entries is two or three kills' worth.
 */
object DragonScanLog {
    private const val CAPACITY = 20

    /**
     * @property raw the stand's `customName` exactly as it arrived, colour codes stripped for
     *   legibility but text otherwise untouched — an override has to match it byte for byte.
     * @property itemId the resolved id, or null when nothing matched.
     * @property granted how much of [count] was newly credited on the scan that parsed it. 0 is
     *   normal and means "already counted on an earlier pass", not a failure.
     */
    data class Entry(
        val atMs: Long,
        val dragon: DragonType,
        val raw: String,
        val itemId: String?,
        val count: Long,
        val granted: Long,
    )

    private val lock = Any()

    private val entries = ArrayDeque<Entry>(CAPACITY)

    fun record(
        dragon: DragonType,
        raw: String,
        itemId: String?,
        count: Long,
        granted: Long,
    ) {
        synchronized(lock) {
            if (entries.size >= CAPACITY) entries.removeFirst()
            entries.addLast(Entry(System.currentTimeMillis(), dragon, raw, itemId, count, granted))
        }
    }

    /** Oldest first. A copy, so a dev dump can format at leisure while a scan keeps writing. */
    fun snapshot(): List<Entry> = synchronized(lock) { entries.toList() }

    fun clear() = synchronized(lock) { entries.clear() }
}
