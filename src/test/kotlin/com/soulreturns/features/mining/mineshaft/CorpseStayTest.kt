package com.soulreturns.features.mining.mineshaft

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorpseStayTest {
    private val data = CorpseSpotData.parse(javaClass.getResource("/assets/soul/mineshaft/corpse_spots.json")!!.readText())

    private fun dist(a: DoubleArray, b: DoubleArray): Double = sqrt((a[0] - b[0]) * (a[0] - b[0]) + (a[1] - b[1]) * (a[1] - b[1]) + (a[2] - b[2]) * (a[2] - b[2]))

    private fun ding(st: CorpseStay, player: DoubleArray, stand: DoubleArray): DingFit? =
        st.ding((player[0] * 8).toInt(), (player[1] * 8).toInt(), (player[2] * 8).toInt(), CorpseLocatorSolver.byteFor(dist(player, stand))!!)

    // CITR_2's two spots 6.16 apart, the closest pair in the data.
    private val citrA = CorpseSpot(-142, 10, -187)
    private val citrB = CorpseSpot(-143, 11, -181)
    private val standA = doubleArrayOf(-141.5, 10.73, -186.5)
    private val standB = doubleArrayOf(-142.5, 11.73, -180.5)

    /** A located by its dings (not seen), B walked up to and opened before the sight scan saw it. */
    private fun citrStay(): CorpseStay {
        val st = CorpseStay(CorpseLocatorSolver(data.spots("CITR_2")!!))
        ding(st, doubleArrayOf(-150.0, 10.0, -195.0), standA)
        assertEquals(listOf(citrA), st.solver.shown())
        return st
    }

    @Test
    fun aLootGuessedFromThePositionCanTakeTheNeighbor() {
        // Between the two, B in reach: the guess prefers the located A, whose box is 3.5 away.
        val st = citrStay()
        assertEquals(citrA, st.loot(-142.5, 11.0, -182.5, "LAPIS"))
        // B's stand, seen afterwards, is shown although it was the one looted; A is hidden although unlooted.
        st.see(standB[0], standB[1], standB[2], "Lapis")
        assertEquals(listOf(citrB), st.solver.shown())
    }

    @Test
    fun theStandUnderTheCrosshairIsTheOneLooted() {
        val st = citrStay()
        assertEquals(citrB, st.loot(-142.5, 11.0, -182.5, "LAPIS", aimed = standB))
        assertEquals(listOf(citrA), st.solver.shown())
        // Its stand seen again later stays hidden.
        st.see(standB[0], standB[1], standB[2], "Lapis")
        assertEquals(listOf(citrA), st.solver.shown())
    }

    @Test
    fun neverMoreDrawnThanTheTabListsPerType() {
        // AMBE_1, the recording's stands: two Lapis seen, the tab says one Lapis left (one was looted unseen).
        val st = CorpseStay(CorpseLocatorSolver(data.spots("AMBE_1")!!))
        val looted = doubleArrayOf(-171.7869, 16.4411, -185.3481)
        val live = doubleArrayOf(-122.3381, 23.4838, -159.2151)
        st.see(looted[0], looted[1], looted[2], "Lapis")
        st.see(live[0], live[1], live[2], "Lapis")
        // The talisman measures only unlooted corpses: its dings point at the live one.
        ding(st, doubleArrayOf(-130.0, 23.0, -160.0), live)
        ding(st, doubleArrayOf(-122.0, 23.0, -170.0), live)
        val drawn = st.shown(mapOf("Lapis" to 1, "Umber" to 1))
        assertEquals(listOf(CorpseSpot(-123, 23, -160)), drawn)
        assertEquals(1, st.capEvents)
        assertTrue(st.capped.getValue(CorpseSpot(-172, 16, -186)).startsWith("seen, 0 ding(s)"), st.capped.toString())
        // Same answer next frame: one event, not one per frame.
        st.shown(mapOf("Lapis" to 1, "Umber" to 1))
        assertEquals(1, st.capEvents)
    }

    @Test
    fun aSpotOnlyTheDingsPointAtGoesBeforeASeenOne() {
        val st = CorpseStay(CorpseLocatorSolver(data.spots("AMBE_1")!!))
        val umber = doubleArrayOf(-191.5, 25.9083, -160.5)
        ding(st, doubleArrayOf(-185.0, 25.0, -150.0), umber)
        val lapis = doubleArrayOf(-171.7869, 16.4411, -185.3481)
        st.see(lapis[0], lapis[1], lapis[2], "Lapis")
        assertEquals(2, st.solver.shown().size)
        assertEquals(listOf(CorpseSpot(-172, 16, -186)), st.shown(mapOf("Lapis" to 1)))
        assertTrue(st.capped.getValue(CorpseSpot(-192, 25, -161)).startsWith("dings only, 1 ding(s)"), st.capped.toString())
        // The tab catching up (an Umber row) draws both again.
        assertEquals(2, st.shown(mapOf("Lapis" to 1, "Umber" to 1)).size)
        assertTrue(st.capped.isEmpty())
    }

    @Test
    fun aDingSpotContradictedByASeenStandGoesFirst() {
        // LITT_L-like: no listed spot near; a gap corpse's ding confirmed listed AMBE_1 spot X, then the gap stand was seen.
        val spots = data.spots("AMBE_1")!!
        val listed = CorpseSpot(-172, 16, -186)
        val player = doubleArrayOf(-160.0, 16.5, -186.0)
        val gap = doubleArrayOf(-160.5, 16.9, -197.5)
        val st = CorpseStay(CorpseLocatorSolver(spots))
        ding(st, player, gap)
        assertEquals(listOf(listed), st.solver.shown())
        val other = doubleArrayOf(-122.3381, 23.4838, -159.2151)
        ding(st, doubleArrayOf(-130.0, 23.0, -160.0), other)
        st.see(gap[0], gap[1], gap[2], "Umber")
        assertEquals(0, st.solver.support(listed), "its only ding is the seen gap stand's")
        assertEquals(3, st.solver.shown().size)
        val drawn = st.shown(mapOf("Umber" to 1, "Lapis" to 1))
        assertEquals(setOf(CorpseSpot(-161, 16, -198), CorpseSpot(-123, 23, -160)), drawn.toSet())
        assertNotNull(st.capped[listed])
    }

    @Test
    fun theVanguardSpotIsNeverHeldBack() {
        val st = CorpseStay(CorpseLocatorSolver(data.spots("FAIR_1")!!, fixed = true))
        st.book.seenTypes[CorpseSpot(-139, 3, -171)] = "Vanguard"
        st.see(-150.0, 3.5, -160.0, "Lapis")
        assertEquals(listOf(CorpseSpot(-139, 3, -171)), st.shown(mapOf("Vanguard" to 1)))
    }
}
