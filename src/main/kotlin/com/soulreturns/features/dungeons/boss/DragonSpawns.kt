package com.soulreturns.features.dungeons.boss

/** One of M7's five Wither King dragons: where it spawns and its color (docs/research/m7-boss-2026-11-13.md, P5 §3.1). */
class Dragon private constructor(val name: String, val x: Double, val z: Double, val color: Int) {
    companion object {
        val RED = Dragon("Red", 27.0, 59.0, 0xFFFF5555.toInt())
        val ORANGE = Dragon("Orange", 85.0, 56.0, 0xFFFFAA00.toInt())
        val GREEN = Dragon("Green", 27.0, 94.0, 0xFF55FF55.toInt())
        val BLUE = Dragon("Blue", 84.0, 94.0, 0xFF55FFFF.toInt())
        val PURPLE = Dragon("Purple", 56.0, 125.0, 0xFFAA00AA.toInt())
        val ALL = listOf(RED, ORANGE, GREEN, BLUE, PURPLE)

        fun at(x: Double, z: Double, within: Double): Dragon? = ALL.firstOrNull { kotlin.math.abs(it.x - x) <= within && kotlin.math.abs(it.z - z) <= within }
    }
}

/**
 * M7 P5: which dragons are about to spawn, on server ticks. Pure: fed with the spawn particles and the dragon adds.
 *
 * Every spawn sends 10 pulses 10 ticks apart at its exact x/z, y 19.0; one of each pulse is `flame` with count 20
 * (no other packet has these values). The `ender_dragon` is added at the same x/z, y 14, 5 s after the first pulse:
 * 100 ticks by the wall clock, **98.7 `set_time` ticks** (40 valid spawns of the 8 M7 runs; the server runs a little
 * under 20 TPS), so the countdown runs [SPAWN_AFTER] = 99 server ticks. On the lone-ping clock (19 spawns, 2026-11-13)
 * it is 98, 99 or 100 ticks (4, 8, 7 times), never a constant, also across server stalls (wall 8–9 s, still 98–100
 * ticks): 99 puts the 0 within one tick of the add in all 19.
 */
class DragonSpawns {
    class Pending(val dragon: Dragon, var firstPulse: Double) {
        val spawnAt: Double get() = firstPulse + SPAWN_AFTER
    }

    private val pending = LinkedHashMap<Dragon, Pending>()

    /** A `flame` particle packet at [x]/[y]/[z] with [count]: the first pulse of a spawn starts its countdown. */
    fun particle(flame: Boolean, count: Int, x: Double, y: Double, z: Double, tick: Double) {
        if (!flame || count != PULSE_COUNT || kotlin.math.abs(y - PULSE_Y) > 0.01) return
        val d = Dragon.at(x, z, 0.01) ?: return
        val p = pending[d]
        if (p != null && tick <= p.spawnAt + LATE) return
        pending[d] = Pending(d, tick)
    }

    /** [dragon]'s first pulse read again between the `set_time` packets around it (the live clock extrapolates). */
    fun retime(dragon: Dragon, firstPulse: Double, exact: Double) {
        val p = pending[dragon] ?: return
        if (p.firstPulse == firstPulse) p.firstPulse = exact
    }

    /** An `ender_dragon` added at x/z: its countdown is over. */
    fun dragon(x: Double, z: Double) {
        val d = Dragon.at(x, z, ADD_WITHIN) ?: return
        pending.remove(d)
    }

    /** Spawns still to come at [now] (a countdown past its tick by more than [LATE] is dropped), soonest first. */
    fun pending(now: Double): List<Pending> {
        pending.values.removeAll { now > it.spawnAt + LATE }
        return pending.values.sortedBy { it.spawnAt }
    }

    fun clear() = pending.clear()

    companion object {
        const val SPAWN_AFTER = 99.0
        const val PULSE_COUNT = 20
        const val PULSE_Y = 19.0
        /** The add came at the spawn x/z in 40 of 41 (M5 Orange first seen 4 b away, late). */
        const val ADD_WITHIN = 6.0
        /** No add by then (out of tracking range): the countdown goes. */
        const val LATE = 20.0
    }
}
