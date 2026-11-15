package com.soulreturns.features.dungeons.boss

import com.soulreturns.features.dungeons.devices.GoldorPhaseModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Self-calibrating parts of [StormModel] on synthetic events (floors whose constants are unconfirmed). */
class StormModelTest {
    private fun started(timings: BossTimings = BossTimings.M7, p: Long = 1000): StormModel =
        StormModel(timings).also { it.chat(StormModel.PATHETIC, p) }

    private fun step(m: StormModel, pillar: Pillar, start: Long, layers: Int, fromY: Int = 175) {
        for (i in 0 until layers) {
            m.sound(StormModel.PISTON_DOWN, pillar.cornerX + 0.5, (fromY - i) + 0.5, pillar.cornerZ + 0.5, 10f, start + 4L * i)
        }
    }

    @Test
    fun gigaCountdownFollowsTheShownNumberAndSpacing() {
        val m = started()
        // A countdown that starts at 5 (as the user remembers M7), titles 24 ticks apart.
        m.title("5", 2000)
        assertEquals(2000L + 4 * 25 + 20, m.gigaStrike, "first title: the floor's default spacing")
        m.title("4", 2024)
        assertEquals(2024L + 3 * 24 + 20, m.gigaStrike, "from the second title on: the spacing shown")
        m.title("1", 2096)
        assertEquals(2116L, m.gigaStrike)
        assertTrue(m.gigaFromTitles)
        // A new countdown starting at 6 restarts the spacing.
        m.title("6", 3000)
        assertEquals(3000L + 5 * 25 + 20, m.gigaStrike)
    }

    @Test
    fun giga1BeforeAnyTitleComesFromTheFloorTable() {
        val m = started(BossTimings.F7, p = 500)
        assertEquals(500L + 520, m.gigaStrike)
        assertFalse(m.gigaFromTitles)
    }

    @Test
    fun padGridMovesOnlyAfterTwoAgreeingOffGridSteps() {
        val m = started(p = 1000)
        step(m, StormGeometry.WS, 1209, 5)
        assertEquals(1000L, m.padAnchor, "one step 9 off the grid is kept")
        step(m, StormGeometry.WN, 1309, 5)
        assertEquals(1308L, m.padAnchor, "a second step agreeing with it moves the grid (to one before the step)")
        assertTrue(m.padCalibrated)
    }

    @Test
    fun padStepOnTheGridConfirmsIt() {
        val m = started(p = 1000)
        assertFalse(m.padCalibrated)
        step(m, StormGeometry.WS, 1201, 5)
        assertTrue(m.padCalibrated)
        assertEquals(1000L, m.padAnchor)
        val ws = m.stateAt("WS")!!
        assertEquals(1200L, ws.lastDownPoll)
        assertEquals(1260L, ws.windowEnd)
        assertEquals(listOf(1220L, 1240L, 1260L), ws.checksAhead(1201, 20))
        assertTrue(m.loaded(ws))
    }

    @Test
    fun goldorGridFromTheFloorCollapseThenAnOwnSave() {
        val m = StormModel(BossTimings.M7)
        m.chat(StormModel.PATHETIC, 1000)
        m.chat(StormModel.STORM_END, 1900)
        m.falling(98.5, 221.5, 42.5, 2000)
        m.chat(StormModel.WHO_DARES, 2006)
        assertEquals(2001L, m.goldorAnchor, "collapse + 61.4 + 60k (M7)")
        m.chat("Your Phoenix Pet saved you from certain death!", 2242)
        assertEquals(2001L, m.goldorAnchor, "a check 1 off the collapse grid keeps it")
        m.chat(StormModel.DEATH_ZONE, 2242)
        assertEquals(1, m.goldorChecks.size, "the Goldor line of the same check is not a second one")
        m.chat("☠ PuppyGirlSunny died and became a ghost.", 2312)
        assertEquals(2312L, m.goldorAnchor, "11 off: the grid was wrong and moves")
        assertEquals(GoldorSource.DIRECT, m.goldorSource)
        m.chat("☠ Maaxnn died to a mob and became a ghost.", 2340)
        assertEquals(2, m.goldorChecks.size, "other deaths are not death-zone checks")
    }

    @Test
    fun goldorStartsAtWhoDaresWhenTheCollapseWasLongBefore() {
        // F7 solo: `Who dares` 27.6–59 after the collapse; R5's check sat on its grid.
        val m = StormModel(BossTimings.F7)
        m.chat(StormModel.PATHETIC, 1000)
        m.chat(StormModel.STORM_END, 1900)
        m.falling(98.5, 221.5, 42.5, 1960)
        m.chat(StormModel.WHO_DARES, 2006)
        assertEquals(2006L + GoldorPhaseModel.WD_TO_START, m.goldorAnchor)
        assertEquals(GoldorSource.WHO_DARES, m.goldorSource)
    }

    @Test
    fun goldorsGridStartsAtStormsDeathPlus61WhenYouStandInHisArea() {
        // M7: the user in section 1 at the collapse; T (and the grid) = Storm's death + 61, before `Who dares` arrives.
        val m = StormModel(BossTimings.M7)
        m.chat(StormModel.PATHETIC, 1000)
        m.sound(GoldorPhaseModel.THUNDER, 45.0, 170.0, 42.0, 10000f, 1890)
        assertEquals(1951L, m.phase3.expected())
        m.chat(StormModel.STORM_END, 1905)
        m.player(107.0, 120.0, 98.0, 1940)
        m.falling(98.5, 221.5, 42.5, 1949)
        assertEquals(1951L, m.goldorAnchor)
        assertEquals(GoldorSource.COLLAPSE, m.goldorSource)
        assertNull(m.whoDares)
        m.chat(StormModel.WHO_DARES, 1952)
        assertEquals(1951L, m.goldorAnchor, "`Who dares` − 1 is the same T")
    }

    @Test
    fun goldorGridResyncsFromAnOffGridDeathLine() {
        val m = StormModel(BossTimings.M7)
        m.chat(StormModel.WHO_DARES, 5000)
        assertEquals(59.0, m.toNextGoldor(5000.0))
        m.chat(StormModel.DEATH_ZONE, 5149)
        assertEquals(5149L, m.goldorAnchor, "29 off the grid: a direct check sits on its own tick")
        m.chat(StormModel.CORE_OPEN, 5300)
        assertNull(m.toNextGoldor(5300.0))
    }

    @Test
    fun stormStatesFromPosition() {
        val m = started(p = 0)
        m.wither(7, 103.0, 188.0, 53.0, 2)
        assertEquals(7, m.stormId)
        m.wither(7, 102.4, 183.0, 52.4, 400)
        assertEquals(StormState.PARKED, m.state)
        m.wither(7, 72.3, 179.9, 53.3, 1500)
        assertEquals(StormState.GIGA, m.state)
        m.wither(7, 66.0, 179.9, 53.0, 1598)
        assertNull(m.highA, "sideways at the Giga height is no ascent")
        m.wither(7, 66.0, 181.5, 53.0, 1600)
        assertNull(m.highA, "one rising step could be a climbing chase")
        m.wither(7, 66.0, 183.1, 53.0, 1602)
        assertEquals(1598L, m.highA, "A = the last track point before the rise")
        assertEquals(StormState.HIGH, m.state)
        m.witherGone(7, 1700)
        assertEquals(StormState.HIGH, m.state, "the phase runs on the clock, tracked or not")
        m.advance(1760)
        assertEquals(StormState.RETURNING, m.state)
        m.wither(7, 60.0, 174.0, 50.0, 1800)
        assertEquals(StormState.CHASE, m.state, "re-added with the same id")
        assertNull(m.highPhase(1800))
    }

    @Test
    fun ascentNeedsTheLowHoldAndStillSides() {
        val m = started(p = 0)
        m.wither(7, 103.0, 188.0, 53.0, 2)
        m.wither(7, 60.0, 182.0, 50.0, 1000)
        m.wither(7, 60.0, 183.6, 50.0, 1002)
        m.wither(7, 60.0, 185.2, 50.0, 1004)
        assertNull(m.highA, "a rise from above y181 is not the ascent")
        m.wither(7, 60.0, 180.0, 50.0, 1010)
        m.wither(7, 60.5, 181.0, 50.0, 1012)
        assertNull(m.highA, "a rise while moving sideways is not the ascent")
        m.wither(7, 60.5, 181.0, 50.0, 1013)
        m.wither(7, 60.5, 182.6, 50.0, 1014)
        assertNull(m.highA, "one straight rising step is not enough")
        m.wither(7, 60.5, 184.2, 50.0, 1016)
        assertEquals(1010L, m.highA, "backdated to the point before the rise, the sideways slow-down included")
        // Leaving the top and the pause after it never start a second phase.
        m.wither(7, 60.5, 196.0, 50.0, 1100)
        m.wither(7, 57.0, 192.0, 47.0, 1185)
        m.wither(7, 57.0, 192.6, 47.0, 1187)
        assertEquals(1010L, m.highA)
    }

    @Test
    fun fireballsOnTheRunWideGridUntilTheTopIsLeft() {
        val m = started(p = 0)
        m.wither(7, 103.0, 188.0, 53.0, 2)
        m.fireball(103.0, 188.0, 53.0, 100)
        assertNull(m.lastFireball, "the opening flight's fireballs sit on another grid")
        m.wither(7, 66.0, 179.9, 53.0, 1598)
        m.wither(7, 66.0, 181.5, 53.0, 1600)
        m.wither(7, 66.0, 183.1, 53.0, 1602)
        assertNull(m.nextFireball(1620), "nothing before the run's first high-phase fireball")
        m.fireball(66.0, 194.0, 53.0, 1640)
        assertEquals(1680L, m.nextFireball(1641))
        assertEquals(1720L, m.nextFireball(1719))
        assertNull(m.nextFireball(1720), "1760 would fall on A+162, after he left the top")
    }

    @Test
    fun onlyTheLoudDoorSoundInsideAFootprintRetiresAPillar() {
        val m = started(p = 0)
        m.sound(StormModel.BREAK_DOOR, 46.5, 169.5, 41.5, 0.25f, 1354)
        m.sound(StormModel.BREAK_DOOR, 42.5, 169.5, 75.5, 0.25f, 1701)
        assertTrue(m.pillars.none { it.retired }, "quiet door sounds near WN and WS (R6)")
        m.sound(StormModel.BREAK_DOOR, 46.875, 176.875, 63.875, 3f, 6040)
        assertEquals(listOf("WS"), m.pillars.filter { it.retired }.map { it.pillar.id })
    }

    @Test
    fun baitArmsThePillarNearestTheHoverPointThenMarksTheSpot() {
        val m = started(BossTimings.F7, p = 0)
        m.wither(7, 103.0, 188.0, 53.0, 2)
        m.wither(7, 66.0, 179.9, 57.0, 1598)
        m.wither(7, 66.0, 181.5, 57.0, 1600)
        m.wither(7, 66.0, 183.1, 57.0, 1602)
        m.wither(7, 66.0, 193.0, 57.0, 1620)
        val arm = m.bait(1700)!!
        assertEquals(StormGeometry.WS, arm.pillar)
        assertEquals(BaitView.ARM, arm.stage)
        assertEquals(1760L, arm.armPoll, "first poll in A+160..A+190")
        assertTrue(arm.spot[0] in 39.0..44.0 && arm.spot[2] in 64.0..70.0, arm.spot.joinToString())
        assertTrue(StormGeometry.WS.depth(arm.spot[0], arm.spot[2]) in -3.0..-1.0, "1-3 b outside the footprint")
        step(m, StormGeometry.WS, 1761, 5)
        val bait = m.bait(1790)!!
        assertEquals(BaitView.BAIT, bait.stage)
        assertNull(m.bait(1900), "over once the window has ended")
    }
}
