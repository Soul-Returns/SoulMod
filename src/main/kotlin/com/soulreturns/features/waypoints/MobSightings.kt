package com.soulreturns.features.waypoints

/**
 * One waypoint of a [MobWaypointDef]: where its mob was last seen, when, in which state, and by whom.
 * [sharedBy] null = the player's own sighting; else the party member whose line placed it (until the
 * player sees the mob there, which makes it their own).
 */
class Sighting internal constructor(
    var x: Double,
    var y: Double,
    var z: Double,
    /** Own: the last scan that saw the mob. Shared: when the line arrived. */
    var seenAtMs: Long,
    /** The definition's state word (Pangolin: "rolled in"), null = none; always null while shared. */
    var state: String?,
    /** The entity last seen as this mob; null for a shared sighting not seen yet. */
    var entityId: Int?,
    var sharedBy: String?,
) {
    /** Seen by the latest scan (drawn as live, its state is current). */
    var inSight = false
        internal set

    /** Scans in a row that saw this spot clearly and no such mob in it. */
    var emptyScans = 0
        internal set

    /** The party has it: we sent it, someone sent it, or a share near it came in. Never sent (again). */
    var announced = false
        internal set
}

/** A mob the player sees in this scan, read only after the sight test passed. */
data class SeenMob(val entityId: Int, val x: Double, val y: Double, val z: Double, val state: String?)

/**
 * Sightings of one mob kind (rule://waypoints). Pure: the caller feeds what a line-of-sight scan saw
 * and what a sight test of each remembered spot found; nothing here reads the world, so the ToS rule
 * lives with the caller ([MobWaypoints]): a sighting is only created, moved or removed from a moment
 * the player sees the mob or its spot.
 *
 * Per scan: [see] with every visible mob, then [endScan] with which unseen spots were in clear view
 * and empty. [expire] drops what nobody saw for [EXPIRE_MS].
 */
class MobSightings {
    companion object {
        /** A waypoint whose mob was not seen (or shared) for this long goes: the corpse rule's 60 s for reusing a sighting. */
        const val EXPIRE_MS = 60_000L

        /** Scans in a row with the spot in clear view and no mob in it before the waypoint goes. */
        const val EMPTY_SCANS = 2

        /** A seen mob this close to a sighting not seen this scan is that one (a re-sent entity, a shared spot). */
        const val SAME_MOB = 3.0

        /** A shared line this close to a known sighting is about that one. */
        const val SAME_SHARE = 6.0
    }

    private val list = ArrayList<Sighting>()

    val all: List<Sighting> get() = list

    fun clear() = list.clear()

    /**
     * The mobs one scan saw. Each sighting follows its entity; a mob without one takes the nearest
     * sighting not seen this scan within [SAME_MOB] (a re-sent entity, a shared spot), else starts a new
     * one. A shared sighting it lands on becomes the player's own and stays [Sighting.announced].
     */
    fun see(mobs: List<SeenMob>, nowMs: Long) {
        for (s in list) s.inSight = false
        val rest = ArrayList<SeenMob>()
        for (m in mobs) {
            val s = list.firstOrNull { !it.inSight && it.entityId == m.entityId }
            if (s != null) update(s, m, nowMs) else rest += m
        }
        for (m in rest) {
            val s =
                list.filter { !it.inSight }.minByOrNull { dist2(it, m.x, m.y, m.z) }?.takeIf { dist2(it, m.x, m.y, m.z) <= SAME_MOB * SAME_MOB }
                    ?: Sighting(m.x, m.y, m.z, nowMs, m.state, m.entityId, null).also { list += it }
            update(s, m, nowMs)
        }
    }

    private fun update(s: Sighting, m: SeenMob, nowMs: Long) {
        s.x = m.x
        s.y = m.y
        s.z = m.z
        s.seenAtMs = nowMs
        s.state = m.state
        s.entityId = m.entityId
        s.sharedBy = null
        s.inSight = true
        s.emptyScans = 0
    }

    /**
     * After [see]: [emptyInView] says, for each sighting not seen this scan, whether the player now sees
     * its spot clearly with no such mob in it. [EMPTY_SCANS] such scans in a row remove it (caught in
     * front of the player, walked off, or a shared one that is not there); any other scan resets the count.
     */
    fun endScan(emptyInView: (Sighting) -> Boolean): List<Sighting> {
        val gone = ArrayList<Sighting>()
        for (s in list) {
            if (s.inSight) continue
            if (emptyInView(s)) s.emptyScans++ else s.emptyScans = 0
            if (s.emptyScans >= EMPTY_SCANS) gone += s
        }
        list.removeAll(gone.toSet())
        return gone
    }

    /** Drops the sightings nobody saw (or shared) for [EXPIRE_MS]; returns them. */
    fun expire(nowMs: Long): List<Sighting> {
        val gone = list.filter { nowMs - it.seenAtMs > EXPIRE_MS }
        list.removeAll(gone.toSet())
        return gone
    }

    /**
     * A party member's line: a sighting within [SAME_SHARE] is that mob (an own one only counts as
     * announced; a shared one moves to the new spot and takes the new sharer), else a new shared sighting.
     */
    fun received(from: String, x: Double, y: Double, z: Double, nowMs: Long): Sighting {
        val near = list.minByOrNull { dist2(it, x, y, z) }?.takeIf { dist2(it, x, y, z) <= SAME_SHARE * SAME_SHARE }
        if (near != null) {
            near.announced = true
            if (near.sharedBy != null) {
                near.x = x
                near.y = y
                near.z = z
                near.seenAtMs = nowMs
                near.sharedBy = from
                near.emptyScans = 0
            }
            return near
        }
        return Sighting(x, y, z, nowMs, null, null, from).also {
            it.announced = true
            list += it
        }
    }

    /** The newest own sighting the party does not have yet and that is at most [maxAgeMs] old, or null. */
    fun nextToShare(nowMs: Long, maxAgeMs: Long): Sighting? =
        list.filter { it.sharedBy == null && !it.announced && nowMs - it.seenAtMs <= maxAgeMs }.maxByOrNull { it.seenAtMs }

    fun markAnnounced(s: Sighting) {
        s.announced = true
    }

    private fun dist2(s: Sighting, x: Double, y: Double, z: Double): Double {
        val dx = s.x - x
        val dy = s.y - y
        val dz = s.z - z
        return dx * dx + dy * dy + dz * dz
    }
}
