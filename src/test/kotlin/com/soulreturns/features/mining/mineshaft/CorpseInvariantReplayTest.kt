package com.soulreturns.features.mining.mineshaft

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Every recorded Glacite Mineshaft stay (`mineshaft/corpse-stays.txt`, generated from the
 * `mineshaft-*` recordings in `soul-dev/recordings`) through [CorpseStay] as [CorpseLocator] drives it:
 * own dings (within [CorpseLocatorSolver.OWN_RADIUS] of the last 1.5 s of positions), loot lines at
 * the player, the tab's LOOTED backup, seen stands. After every event: never more corpses drawn than
 * the tab lists unlooted, per type and in total.
 */
class CorpseInvariantReplayTest {
    private sealed interface Event { val tick: Int }
    private class Pos(override val tick: Int, val p: DoubleArray) : Event
    private class Ding(override val tick: Int, val ix: Int, val iy: Int, val iz: Int, val pitch: Float) : Event
    private class Loot(override val tick: Int, val type: String) : Event
    private class Tab(override val tick: Int, val rows: List<Pair<String, Boolean>>) : Event
    private class See(override val tick: Int, val type: String, val p: DoubleArray) : Event

    private class Stay(val layout: String, val source: String) {
        val events = ArrayList<Event>()
    }

    private val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())

    private fun load(resource: String): List<Stay> =
        ArrayList<Stay>().also { out ->
            for (line in javaClass.getResource(resource)!!.readText().lines()) {
                if (line.isBlank() || line.startsWith("#")) continue
                val f = line.split(' ')
                val stay = out.lastOrNull()
                fun xyz(i: Int) = doubleArrayOf(f[i].toDouble(), f[i + 1].toDouble(), f[i + 2].toDouble())
                when (f[0]) {
                    "stay" -> out += Stay(f[1], "${f[2]} ${f[3]}")
                    "pos" -> stay!!.events += Pos(f[1].toInt(), xyz(2))
                    "ding" -> stay!!.events += Ding(f[1].toInt(), f[2].toInt(), f[3].toInt(), f[4].toInt(), f[5].toFloat())
                    "loot" -> stay!!.events += Loot(f[1].toInt(), f[2])
                    "tab" -> stay!!.events += Tab(f[1].toInt(), f[2].split(',').map { it.substringBefore(':') to it.endsWith(":NOT_LOOTED") })
                    "see" -> stay!!.events += See(f[1].toInt(), f[2], xyz(3))
                }
            }
        }

    /** One violation: drawn corpses beyond the tab's unlooted rows. */
    private data class Violation(val stay: String, val tick: Int, val drawn: List<CorpseSpot>, val unlooted: Map<String, Int>, val why: String)

    private fun replay(stay: Stay, guarded: Boolean): List<Violation> {
        val solver = CorpseLocatorSolver(data.spots(stay.layout).orEmpty(), fixed = stay.layout in data.fixed)
        val st = CorpseStay(solver)
        data.fixed[stay.layout]?.let { type -> for (s in solver.spots) st.book.seenTypes[s] = type }
        val history = ArrayDeque<Pair<Int, DoubleArray>>()
        var unlooted: Map<String, Int> = emptyMap()
        val out = ArrayList<Violation>()
        for (e in stay.events) {
            when (e) {
                is Pos -> history.addLast(e.tick to e.p)
                is Ding -> {
                    while (history.size > 1 && history.first().first < e.tick - 30) history.removeFirst()
                    val n = CorpseLocatorSolver.pitchByte(e.pitch) ?: continue
                    if (!CorpseLocatorSolver.isOwnDing(e.ix / 8.0, e.iy / 8.0, e.iz / 8.0, history.map { it.second })) continue
                    st.ding(e.ix, e.iy, e.iz, n)
                }
                is Loot -> history.lastOrNull()?.second?.let { st.loot(it[0], it[1], it[2], e.type) }
                is Tab -> {
                    // Per type, NOT LOOTED rows; a type whose rows are all looted counts as 0.
                    unlooted = e.rows.map { it.first }.distinct().associateWith { t -> e.rows.count { it.first == t && it.second } }
                    history.lastOrNull()?.second?.let { st.tab(e.rows.count { !it.second }, it[0], it[1], it[2]) }
                }
                is See -> st.see(e.p[0], e.p[1], e.p[2], e.type)
            }
            if (unlooted.isEmpty()) continue
            val drawn = if (guarded) st.shown(unlooted) else solver.shown()
            val total = unlooted.values.sum()
            val overType = drawn.groupBy { st.typeOf(it) }.entries.firstOrNull { (t, s) -> t != null && t != CorpseLook.OTHER && s.size > (unlooted[t] ?: 0) }
            if (drawn.size > total || overType != null) {
                val why = drawn.joinToString { s -> "$s ${st.typeOf(s)} seen=${s in st.seen} support=${solver.support(s)} pinned=${solver.isPinned(s)}" }
                out += Violation("${stay.layout} ${stay.source}", e.tick, drawn, unlooted, why)
            }
        }
        return out
    }

    private val stays = load("/mineshaft/corpse-stays.txt")

    @Test
    fun neverMoreWaypointsThanUnlootedCorpses() {
        var events = 0
        val raw = ArrayList<Violation>()
        val guarded = ArrayList<Violation>()
        for (stay in stays) {
            events += stay.events.size
            raw += replay(stay, guarded = false)
            guarded += replay(stay, guarded = true)
        }
        println("invariant replay: ${stays.size} stays, $events events; unguarded ${raw.size} violating events, guarded ${guarded.size}")
        for (v in raw.distinctBy { it.stay to it.drawn }) println("  unguarded: $v")
        assertEquals(emptyList(), guarded.distinctBy { it.stay to it.drawn })
        assertEquals(emptyList(), raw.distinctBy { it.stay to it.drawn })
    }
}
