package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.MineshaftConfig
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CorpseMuteAndLookTest {
    private val a = CorpseSpot(0, 0, 0)
    private val c = CorpseSpot(0, 0, 10)

    private fun stand(s: CorpseSpot) = doubleArrayOf(s.x + 0.5, s.y + 0.9, s.z + 0.5)

    /** The server's ding for a player at [player] whose nearest unlooted stand is [nearest]. */
    private fun ding(s: CorpseLocatorSolver, player: DoubleArray, nearest: CorpseSpot): DingFit {
        val st = stand(nearest)
        val d = sqrt((0..2).sumOf { (player[it] - st[it]) * (player[it] - st[it]) })
        val n = assertNotNull(CorpseLocatorSolver.byteFor(d))
        return assertNotNull(s.ding((player[0] * 8).toInt(), (player[1] * 8).toInt(), (player[2] * 8).toInt(), n))
    }

    private fun mutes(fit: DingFit, locatorOn: Boolean = true, muteOn: Boolean = true) = CorpseLocatorSolver.mutes(fit, locatorOn, muteOn)

    private val southOfA = doubleArrayOf(0.5, 0.9, -4.5)
    private val between = doubleArrayOf(0.5, 0.9, 5.5)

    @Test
    fun theConfirmingDingIsAudibleAndLaterOnesExplainedByItAreMuted() {
        val s = CorpseLocatorSolver(listOf(a, c))
        val first = ding(s, southOfA, a)
        assertEquals(listOf(a), first.fitting)
        assertFalse(mutes(first), "the ding that confirms plays")
        assertTrue(a in s.confirmed)
        val second = ding(s, southOfA, a)
        assertTrue(mutes(second), "fits only the confirmed spot")
    }

    @Test
    fun anUnconfirmedOrAmbiguousDingStaysAudible() {
        val s = CorpseLocatorSolver(listOf(a, c))
        val ambiguous = ding(s, between, a)
        assertEquals(setOf(a, c), ambiguous.fitting.toSet())
        assertFalse(mutes(ambiguous), "nothing confirmed yet")
        ding(s, southOfA, a)
        val again = ding(s, between, a)
        assertEquals(setOf(a, c), again.fitting.toSet())
        assertFalse(mutes(again), "fits a confirmed spot but also the unconfirmed one")
    }

    @Test
    fun aLootedSpotNeverMutes() {
        val s = CorpseLocatorSolver(listOf(a))
        ding(s, southOfA, a)
        assertTrue(mutes(ding(s, southOfA, a)))
        assertEquals(a, s.loot(0.5, 0.9, 0.5))
        // A ding at the looted spot's distance (a stand the data lacks): it fits nothing now.
        val after = ding(s, southOfA, a)
        assertTrue(after.fitting.isEmpty())
        assertFalse(mutes(after))
    }

    @Test
    fun locatorOrMuteOffIsAlwaysAudible() {
        val s = CorpseLocatorSolver(listOf(a, c))
        ding(s, southOfA, a)
        val explained = ding(s, southOfA, a)
        assertTrue(mutes(explained))
        assertFalse(mutes(explained, locatorOn = false))
        assertFalse(mutes(explained, muteOn = false))
        assertFalse(CorpseLocatorSolver.mutes(null, true, true), "no fit (unknown layout, out of range)")
    }

    @Test
    fun theFixedVanguardSpotMutesFromItsFirstDing() {
        val s = CorpseLocatorSolver(listOf(a), fixed = true)
        assertTrue(mutes(ding(s, southOfA, a)))
    }

    @Test
    fun helmetIdsMapToTypesAndAnythingElseIsOther() {
        assertEquals("Lapis", CorpseLook.typeOf("LAPIS_ARMOR_HELMET"))
        assertEquals("Umber", CorpseLook.typeOf("ARMOR_OF_YOG_HELMET"))
        assertEquals("Tungsten", CorpseLook.typeOf("MINERAL_HELMET"))
        assertEquals("Vanguard", CorpseLook.typeOf("VANGUARD_HELMET"))
        assertEquals(CorpseLook.OTHER, CorpseLook.typeOf("DIAMOND_HELMET"))
        assertEquals(CorpseLook.OTHER, CorpseLook.typeOf(null))
    }

    private val m = MineshaftConfig()
    private val colors = m.corpsesHudColors

    @Test
    fun beforeLineOfSightItIsAPlainCorpseInTheLocatorColor() {
        val d = CorpseLook.decide(null, emptySet(), m)
        assertEquals(CorpseDraw("Corpse", m.corpseLocatorColor, line = true, dimmed = false), d)
        assertFalse(CorpseLook.decide(null, emptySet(), m.copy(corpseLineUnknown = false)).line)
    }

    @Test
    fun anUnknownHelmetIsCorpseInOtherAndNeverDimmed() {
        assertEquals(CorpseDraw("Corpse", colors.other, line = true, dimmed = false), CorpseLook.decide(CorpseLook.OTHER, emptySet(), m))
        assertFalse(CorpseLook.decide(CorpseLook.OTHER, emptySet(), m.copy(corpseLineUnknown = false)).line)
    }

    @Test
    fun lapisNeedsNoKey() {
        assertEquals(CorpseDraw("Lapis Corpse", colors.lapis, line = true, dimmed = false), CorpseLook.decide("Lapis", emptySet(), m))
        assertFalse(CorpseLook.decide("Lapis", emptySet(), m.copy(corpseLineLapis = false)).line)
    }

    @Test
    fun keyedTypesDimWithoutTheirKeyAndFollowTheirLineToggle() {
        val cases =
            listOf(
                Triple("Umber", "UMBER_KEY", colors.umber),
                Triple("Tungsten", "TUNGSTEN_KEY", colors.tungsten),
                Triple("Vanguard", "SKELETON_KEY", colors.vanguard),
            )
        for ((type, key, color) in cases) {
            val lineOn =
                when (type) {
                    "Umber" -> m.copy(corpseLineUmber = true)
                    "Tungsten" -> m.copy(corpseLineTungsten = true)
                    else -> m.copy(corpseLineVanguard = true)
                }
            // Defaults: line off for keyed types.
            assertEquals(CorpseDraw("$type Corpse", color, line = false, dimmed = false), CorpseLook.decide(type, setOf(key), m), type)
            assertEquals(CorpseDraw("$type Corpse", color, line = true, dimmed = false), CorpseLook.decide(type, setOf(key), lineOn), type)
            // No key: dimmed and never a line, whatever the line toggle says.
            assertEquals(CorpseDraw("$type Corpse", color, line = false, dimmed = true), CorpseLook.decide(type, setOf("UMBER_KEY_X"), lineOn), type)
            assertEquals(CorpseDraw("$type Corpse", color, line = false, dimmed = true), CorpseLook.decide(type, emptySet(), m), type)
            // Dimming off: no key changes nothing.
            assertEquals(CorpseDraw("$type Corpse", color, line = true, dimmed = false), CorpseLook.decide(type, emptySet(), lineOn.copy(corpseLocatorDimKeyless = false)), type)
        }
        assertTrue(CorpseLook.decide("Umber", setOf("TUNGSTEN_KEY"), m).dimmed, "another type's key does not count")
    }
}
