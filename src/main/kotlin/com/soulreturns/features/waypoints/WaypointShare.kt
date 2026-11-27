package com.soulreturns.features.waypoints

import kotlin.math.roundToInt

/**
 * The party chat line a mob waypoint travels in (rule://waypoints): `x: -540, y: 96, z: 235 | Pangolin`.
 * A protocol line, sent raw (no `[Soul]`): the `x: N, y: N, z: N` part is what Skytils / SkyHanni /
 * Patcher turn into a waypoint (the corpse locator's Littlefoot share is the bare form), and
 * ` | <Mob>` is SkyHanni's own mob-share suffix (`| Vanquisher`), so their users see a named waypoint
 * and Soul tells one mob from another (and from a bare Littlefoot or coordinates line).
 */
object WaypointShareLine {
    /** A shared waypoint as read from party chat. */
    data class Shared(val sender: String, val x: Double, val y: Double, val z: Double, val label: String)

    /** `Party > [MVP+] Name: body`, colors stripped; the rank bracket is absent for unranked players. */
    private val PARTY = Regex("""^Party > (?:\[[^\]]{1,12}] )?(\w{1,16}): (.+)$""")

    /** Integers as Soul sends them; decimals and a missing comma as other mods send them. */
    private val BODY = Regex("""^x: (-?\d{1,7}(?:\.\d+)?),? y: (-?\d{1,3}(?:\.\d+)?),? z: (-?\d{1,7}(?:\.\d+)?) \| (.{1,32}?)\s*$""")

    fun format(x: Double, y: Double, z: Double, label: String): String = "x: ${x.roundToInt()}, y: ${y.roundToInt()}, z: ${z.roundToInt()} | $label"

    /** A color-stripped chat line, or null when it is no party line carrying a labeled waypoint. */
    fun parse(stripped: String): Shared? {
        val party = PARTY.matchEntire(stripped.trim()) ?: return null
        val body = BODY.matchEntire(party.groupValues[2]) ?: return null
        val x = body.groupValues[1].toDouble()
        val y = body.groupValues[2].toDouble()
        val z = body.groupValues[3].toDouble()
        if (y < -64 || y > 320) return null
        return Shared(party.groupValues[1], x, y, z, body.groupValues[4])
    }
}

/**
 * Keeps mob shares from flooding party chat (rule://waypoints), one for all mob kinds: at least
 * [minGapMs] between two own lines, at most [perWindow] in any [windowMs], and no line for a spot
 * within [sameSpot] of one sent or heard in the last [spotMemoryMs] (the party has that mob already).
 */
class ShareLimiter(
    private val minGapMs: Long = 5_000L,
    private val perWindow: Int = 3,
    private val windowMs: Long = 60_000L,
    private val sameSpot: Double = 8.0,
    private val spotMemoryMs: Long = 60_000L,
) {
    private val sent = ArrayDeque<Long>()
    private val spots = ArrayList<DoubleArray>()

    /** Own lines sent in the current window. */
    val recent: Int get() = sent.size

    /** Whether an own line may go out now. */
    fun allows(nowMs: Long): Boolean {
        prune(nowMs)
        if (sent.isNotEmpty() && nowMs - sent.last() < minGapMs) return false
        return sent.size < perWindow
    }

    /** Whether a line for this spot was sent or heard recently (no need to send it). */
    fun covered(x: Double, y: Double, z: Double, nowMs: Long): Boolean {
        prune(nowMs)
        return spots.any { (it[0] - x) * (it[0] - x) + (it[1] - y) * (it[1] - y) + (it[2] - z) * (it[2] - z) <= sameSpot * sameSpot }
    }

    fun onSent(x: Double, y: Double, z: Double, nowMs: Long) {
        sent.addLast(nowMs)
        spots += doubleArrayOf(x, y, z, nowMs.toDouble())
    }

    fun onHeard(x: Double, y: Double, z: Double, nowMs: Long) {
        spots += doubleArrayOf(x, y, z, nowMs.toDouble())
    }

    /** Another server or island: its spots mean nothing here. The send budget stays (same party chat). */
    fun forgetSpots() {
        spots.clear()
    }

    private fun prune(nowMs: Long) {
        while (sent.isNotEmpty() && nowMs - sent.first() >= windowMs) sent.removeFirst()
        spots.removeAll { nowMs - it[3].toLong() >= spotMemoryMs }
    }
}
