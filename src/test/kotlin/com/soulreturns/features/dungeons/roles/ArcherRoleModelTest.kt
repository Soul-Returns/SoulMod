package com.soulreturns.features.dungeons.roles

import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.boss.BossPhase
import com.soulreturns.features.dungeons.boss.BossTimings
import com.soulreturns.features.dungeons.boss.GoldorSections
import com.soulreturns.features.dungeons.boss.StormGeometry
import com.soulreturns.features.dungeons.boss.StormModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArcherRoleModelTest {
    private fun sync(m: ArcherRoleModel, phase: Int, section: Int = 0, empty: Boolean = false, left: Boolean = false, five: Boolean = false, gate: Boolean = false) =
        m.sync(phase, section, section, empty, left, five, gate)

    private fun cue(m: ArcherRoleModel, section: Int = 0, known: Boolean = true, protection: Int = DeathSaveTracker.UNKNOWN) =
        m.cue(section, "Mate", known, protection)!!

    private fun leap(m: ArcherRoleModel, cls: DungeonClass) = m.chat("You have teleported to Mate!", "Self", cls, null, null, null)

    private fun complete(m: ArcherRoleModel, actor: String, kind: String, x: Double?, y: Double?, z: Double?, count: String = "1/7") =
        m.chat("$actor activated a $kind! ($count)", "Self", null, x, y, z)

    @Test
    fun theFullApprovedRouteAdvancesOnlyOnItsActualSignals() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.MAXOR)
        m.crystalSlot(false, false, false)
        assertEquals("LEFT crystal", cue(m).text)
        m.chat("Other picked up an Energy Crystal!", "Self", null, null, null, null)
        assertEquals("LEFT crystal", cue(m).text)
        m.chat("Self picked up an Energy Crystal!", "Self", null, null, null, null)
        assertEquals(DungeonClass.TANK, cue(m).target)
        leap(m, DungeonClass.MAGE)
        assertEquals(DungeonClass.TANK, cue(m).target)
        leap(m, DungeonClass.TANK)
        assertEquals("Place crystal", cue(m).text)
        m.crystalSlot(true, true, false)
        assertEquals("Wait for next crystal cycle", cue(m).text)
        m.crystalSlot(false, false, true)
        assertEquals("p1.left2", cue(m).objective?.id)
        m.chat("Self picked up an Energy Crystal!", "Self", null, null, null, null)
        leap(m, DungeonClass.TANK)
        m.crystalSlot(true, true, false)
        assertEquals("Wait for Maxor", cue(m).text)
        sync(m, BossPhase.MAXOR, empty = true)
        assertEquals(DungeonClass.BERSERK, cue(m).target)
        leap(m, DungeonClass.BERSERK)
        sync(m, BossPhase.STORM)
        assertEquals("Wait for Storm to leave purple", cue(m).text)
        sync(m, BossPhase.STORM, left = true)
        assertEquals(DungeonClass.MAGE, cue(m).target)
        leap(m, DungeonClass.MAGE)
        sync(m, BossPhase.STORM_DONE)
        assertEquals(DungeonClass.HEALER, cue(m).target)
        leap(m, DungeonClass.HEALER)
        assertTrue(cue(m).waiting)
        sync(m, BossPhase.GOLDOR, 1)
        assertEquals("s1.t4", cue(m, 1).objective?.id)
        complete(m, "Self", "terminal", 91.6, 122.0, 101.3)
        assertEquals("s1.t3", cue(m, 1).objective?.id)
        complete(m, "Self", "terminal", 92.0, 112.0, 93.0)
        assertEquals(DungeonClass.MAGE, cue(m, 1).target)
        assertTrue(cue(m, 1).waiting)
        leap(m, DungeonClass.MAGE)
        assertTrue(cue(m, 1).waiting)
        sync(m, BossPhase.GOLDOR, 2)
        assertEquals("s2.t5", cue(m, 2).objective?.id)
        complete(m, "Healer", "lever", 29.3, 123.8, 129.2, "4/8")
        assertEquals("s2.t5", cue(m, 2).objective?.id, "coverage does not also complete the terminal")
        complete(m, "Self", "terminal", 39.7, 109.0, 140.6, "5/8")
        assertEquals(DungeonClass.HEALER, cue(m, 2).target)
        assertTrue(cue(m, 2).waiting)
        leap(m, DungeonClass.HEALER)
        sync(m, BossPhase.GOLDOR, 3)
        complete(m, "Self", "terminal", 0.7, 109.0, 78.1)
        assertEquals("s3.left", cue(m, 3).objective?.id)
        m.complete("s3.gate")
        complete(m, "Self", "lever", 4.4, 123.4, 56.4)
        assertEquals("s3.right", cue(m, 3).objective?.id, "an already destroyed gate is not suggested again")
        complete(m, "Self", "lever", 13.1, 123.6, 55.9)
        assertEquals(DungeonClass.MAGE, cue(m, 3).target)
        leap(m, DungeonClass.MAGE)
        sync(m, BossPhase.GOLDOR, 4)
        assertEquals("s4.t2", cue(m, 4).objective?.id)
        complete(m, "Self", "terminal", 43.7, 121.0, 31.6)
        leap(m, DungeonClass.MAGE)
        sync(m, BossPhase.NECRON)
        assertEquals("Wait for Necron 5%", cue(m, 4).text)
        sync(m, BossPhase.NECRON, five = true)
        assertEquals(DungeonClass.HEALER, cue(m, 4).target)
        leap(m, DungeonClass.HEALER)
        assertNull(m.cue(0, null, false, DeathSaveTracker.UNKNOWN))
    }

    @Test
    fun noGenericOrQuotedCompletionCanSkipTheBottomLever() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.GOLDOR, 2)
        complete(m, "Healer", "lever", null, null, null, "3/8")
        complete(m, "Healer", "lever", 23.0, 132.0, 138.0, "4/8")
        m.chat("Party > Healer: Self activated a lever! (5/8)", "Self", null, 27.0, 124.0, 127.0)
        complete(m, "Self", "terminal", 39.7, 109.0, 140.6, "5/8")
        assertEquals("s2.lever", cue(m, 2).objective?.id)
        complete(m, "Healer", "lever", 29.3, 123.8, 129.2, "6/8")
        assertEquals(DungeonClass.HEALER, cue(m, 2).target)
    }

    @Test
    fun outOfOrderAssignedTerminalsAndUnrelatedNearbyTerminalsRemainDistinct() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.GOLDOR, 1)
        complete(m, "Self", "terminal", 92.0, 112.0, 93.0)
        assertEquals("s1.t4", cue(m, 1).objective?.id)
        complete(m, "Self", "terminal", 91.6, 122.0, 101.3)
        assertEquals(DungeonClass.MAGE, cue(m, 1).target)
        sync(m, BossPhase.GOLDOR, 2)
        complete(m, "Self", "terminal", 40.0, 124.0, 122.0, "3/8")
        assertEquals("s2.t5", cue(m, 2).objective?.id)
    }

    @Test
    fun preleapNeedsBothAnActualDestinationAndCurrentlyUsableProtection() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.GOLDOR, 2)
        m.complete("s2.t5")
        m.complete("s2.lever")
        assertEquals("Wait · Healer location ?", cue(m, 2, false, DeathSaveTracker.USABLE).text)
        assertTrue(cue(m, 2, true, DeathSaveTracker.UNKNOWN).waiting)
        assertTrue(cue(m, 2, true, DeathSaveTracker.SPENT).waiting)
        assertTrue(cue(m, 2, true, DeathSaveTracker.SWAP).waiting)
        assertEquals("Preleap → Healer", cue(m, 2, true, DeathSaveTracker.USABLE).text)
        assertEquals("Leap → Healer", cue(m, 3, false, DeathSaveTracker.UNKNOWN).text, "an open section needs no death-save assumption")
    }

    @Test
    fun lateSectionAnchorsAndLifecycleResetDoNotLeakAnOldCue() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.MAXOR)
        sync(m, BossPhase.GOLDOR, 3, gate = true)
        assertEquals("s3.t4", cue(m, 3).objective?.id)
        m.complete("s3.t4")
        m.complete("s3.left")
        assertEquals("s3.right", cue(m, 3).objective?.id)
        sync(m, BossPhase.CORE)
        assertEquals("Wait for Necron 5%", cue(m, 5).text)
        m.reset()
        assertNull(m.cue(0, null, false, DeathSaveTracker.UNKNOWN))
        sync(m, BossPhase.GOLDOR, 1)
        assertEquals("s1.t4", cue(m, 1).objective?.id)
    }

    @Test
    fun aRealArrivalResyncsAMissedLeapButNeverCompletesAnUnfinishedObjective() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.GOLDOR, 2)
        m.arrived(2.0, 109.0, 107.0)
        assertEquals("s2.t5", cue(m, 2).objective?.id)
        m.complete("s2.t5")
        m.complete("s2.lever")
        m.arrived(2.0, 109.0, 107.0)
        assertEquals("Wait · S3 not open", cue(m, 2).text)
        sync(m, BossPhase.GOLDOR, 3)
        assertEquals("s3.t4", cue(m, 3).objective?.id)
        sync(m, BossPhase.NECRON, five = true)
        m.arrived(55.0, 5.0, 76.0)
        assertNull(m.cue(0, null, false, DeathSaveTracker.UNKNOWN))
    }

    @Test
    fun purpleDepartureNeverMeansTheInitialParkDepartureAndAlsoWorksAfterAFailedCrush() {
        val m = StormModel(BossTimings.M7)
        m.chat(StormModel.PATHETIC, 1000)
        m.wither(7, 103.0, 188.0, 53.0, 1002)
        m.wither(7, 102.4, 183.0, 52.4, 1400)
        m.wither(7, 98.5, 179.0, 63.5, 1648)
        m.wither(7, 90.0, 169.0, 65.0, 1650)
        assertNull(m.leftPurpleAt, "being under Purple without an observed crush attempt is not enough")
        val p = StormGeometry.E
        m.sound(StormModel.PISTON_DOWN, p.cornerX + 0.5, 175.5, p.cornerZ + 0.5, 10f, 1661)
        m.wither(7, 98.5, 170.0, 64.5, 1680)
        m.wither(7, 90.0, 170.0, 64.5, 1700)
        assertEquals(1700L, m.leftPurpleAt, "departure after the actual attempt, even without a successful crush line")
    }

    @Test
    fun aDestroyedGateDoesNotOpenTheNextSectionUntilTheFullCounter() {
        val s = GoldorSections()
        s.begin()
        s.chat(GoldorSections.GATE_DOWN)
        assertTrue(s.gateDestroyed(1))
        assertEquals(1, s.open)
        s.chat("Party > Healer: Self activated a terminal! (7/7)")
        assertEquals(1, s.open)
        s.chat("Self activated a terminal! (7/7)")
        assertEquals(2, s.open)
        assertTrue(s.gateDestroyed(1), "gate evidence survives advancing to the next section")
        s.reset()
        assertFalse(s.gateDestroyed(1))
    }

    @Test
    fun anUnknownLateGoldorSectionDoesNotInventTheStormTransitionAndCoreIsAnIndependentAnchor() {
        val m = ArcherRoleModel()
        sync(m, BossPhase.GOLDOR)
        assertEquals("Wait · section unknown", cue(m).text)
        assertNull(cue(m).target)
        val sections = GoldorSections()
        sections.chat(StormModel.CORE_OPEN)
        assertEquals(GoldorSections.CORE, sections.open)
        sections.begin()
        assertEquals(GoldorSections.CORE, sections.open, "a late T cannot overwrite the confirmed core")
        sync(m, BossPhase.CORE, sections.open)
        assertEquals("Wait for Necron 5%", cue(m, 5).text)
    }
}
