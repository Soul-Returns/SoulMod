package com.soulreturns.features.dungeons.leap

/**
 * Where each teammate is, as the leap menu shows it (spec `mocks/leap-menu/SPEC.md` §6). Best first: seen now
 * (the caller passes the label of a tracked player) > where they were last seen or their own party report,
 * whichever is newer, with its age > unknown. Pure: times are passed in, so tests can replay real chat.
 *
 * A later backend source (Soul users sharing their position) is one more record kind next to [lastSeen] and
 * [reports]: the newest valid one wins.
 */
class TeammateLocations {
    enum class Source { SEEN, LAST_SEEN, CHAT, NONE }

    /** [label] is `?` for NONE. [ageMs] for LAST_SEEN/CHAT; [atSpot] excludes intentions and inferred leap destinations. */
    data class Location(val source: Source, val label: String, val ageMs: Long = 0, val stale: Boolean = false, val atSpot: Boolean = true)

    private class Record(val label: String, val at: Long, val atSpot: Boolean = true)

    private val lastSeen = HashMap<String, Record>()
    private val reports = HashMap<String, Record>()

    /**
     * A party line from [name]. [locate] answers where a leap target is right now (its seen, last-seen or reported
     * label), so `Leaped to go9o!` puts the sender where go9o is, else `with go9o`.
     */
    fun onPartyLine(name: String, body: String, now: Long, locate: (String) -> String?) {
        val c = PartyCallouts.parse(body) ?: return
        val label = when (c) {
            is PartyCallouts.Callout.Spot -> c.label
            is PartyCallouts.Callout.Coords -> PartyCallouts.spotAt(c.x, c.y, c.z) ?: "${c.x.toInt()} ${c.y.toInt()} ${c.z.toInt()}"
            is PartyCallouts.Callout.Leap -> locate(c.target) ?: "with ${c.target}"
        }
        val atSpot = c is PartyCallouts.Callout.Coords || (c is PartyCallouts.Callout.Spot && c.arrived)
        reports[key(name)] = Record(label, now, atSpot)
    }

    /** Everyone moves after a phase line: every record so far is dropped. */
    fun onPhaseLine() = clear()

    /** Their death or revive: where they were is no longer where they are. */
    fun forget(name: String) {
        lastSeen.remove(key(name))
        reports.remove(key(name))
    }

    fun clear() {
        lastSeen.clear()
        reports.clear()
    }

    /**
     * [seen] = the spot / room label while the player is tracked now, else null. [remember] = whether that label is
     * worth keeping as the last-seen place (a bare distance is not).
     */
    fun locate(name: String, seen: String?, now: Long, remember: Boolean = true): Location {
        val k = key(name)
        if (seen != null) {
            if (remember) lastSeen[k] = Record(seen, now) else lastSeen.remove(k)
            return Location(Source.SEEN, seen)
        }
        val last = valid(lastSeen, k, now)
        val chat = valid(reports, k, now)
        val pick = if (last != null && (chat == null || last.at >= chat.at)) last else chat ?: return UNKNOWN
        val age = now - pick.at
        return Location(if (pick === last) Source.LAST_SEEN else Source.CHAT, pick.label, age, age > FRESH_MS, pick.atSpot)
    }

    private fun valid(map: HashMap<String, Record>, k: String, now: Long): Record? {
        val r = map[k] ?: return null
        val age = now - r.at
        if (age > MAX_MS || age < 0) {
            map.remove(k)
            return null
        }
        return r
    }

    private fun key(name: String) = name.lowercase()

    companion object {
        /** A last-seen place or report is drawn dimmer from this age on. */
        const val FRESH_MS = 20_000L

        /** And dropped after this. */
        const val MAX_MS = 60_000L

        val UNKNOWN = Location(Source.NONE, "?")
    }
}
