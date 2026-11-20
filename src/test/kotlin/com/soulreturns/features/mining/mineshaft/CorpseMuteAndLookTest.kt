package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.MineshaftConfig
import com.soulreturns.util.MobSpotter
import kotlin.math.floor
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CorpseMuteAndLookTest {
    private fun mutes(mode: String, queued: Boolean = false, fitsOnlySeen: Boolean = false, locatorOn: Boolean = true, muteOn: Boolean = true) =
        CorpseLocatorSolver.mutes(mode, queued, fitsOnlySeen, locatorOn, muteOn)

    @Test
    fun everyDingIsMutedWhereTheSpotsAreKnown() {
        assertTrue(mutes("listed"))
        assertTrue(mutes("crystal"))
        assertTrue(mutes("listed", fitsOnlySeen = true))
    }

    @Test
    fun dingsWaitingForTheLayoutAreMutedUntilTheQueueIsFull() {
        assertTrue(mutes("waiting", queued = true))
        assertFalse(mutes("waiting", queued = false), "the layout is still unknown after the queue filled up")
    }

    @Test
    fun whereDingsPlaceNothingTheDingPlaysUntilItsCorpseWasSeen() {
        for (mode in listOf("sight-only", "learned")) {
            assertFalse(mutes(mode), "$mode: the ding may point at an unseen corpse")
            assertTrue(mutes(mode, fitsOnlySeen = true), "$mode: it fits only corpses the player saw")
        }
        assertFalse(mutes("waiting", queued = false, fitsOnlySeen = true), "a full queue plays")
    }

    @Test
    fun aDingFitsOnlySeenCorpsesOnceEveryCorpseItFitsWasSeen() {
        val stand = doubleArrayOf(-139.5, 12.2, -169.5)
        val st = CorpseStay(CorpseLocatorSolver(emptyList(), learned = listOf(stand)))
        val key = CorpseSpot(-140, 12, -170)
        val player = doubleArrayOf(-139.5, 12.0, -160.0)
        fun dingFrom(corpse: DoubleArray): DingFit? {
            val d = sqrt((0..2).sumOf { (player[it] - corpse[it]) * (player[it] - corpse[it]) })
            return st.ding(floor(player[0] * 8).toInt(), floor(player[1] * 8).toInt(), floor(player[2] * 8).toInt(), CorpseLocatorSolver.byteFor(d)!!, 0)
        }
        val unseen = dingFrom(stand)
        assertEquals(listOf(key), unseen?.fitting)
        assertFalse(st.fitsOnlySeen(unseen), "the learned spot is not seen yet")
        st.see(stand[0], stand[1], stand[2], "Lapis")
        assertTrue(st.fitsOnlySeen(dingFrom(stand)), "now it is")
        assertFalse(st.fitsOnlySeen(dingFrom(doubleArrayOf(-139.5, 12.2, -178.0))), "a ding fitting nothing may point at an unseen corpse")
    }

    @Test
    fun locatorOrMuteOffIsAlwaysAudible() {
        for (mode in listOf("listed", "crystal", "waiting", "sight-only", "learned")) {
            assertFalse(mutes(mode, queued = true, fitsOnlySeen = true, locatorOn = false), mode)
            assertFalse(mutes(mode, queued = true, fitsOnlySeen = true, muteOn = false), mode)
        }
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

    @Test
    fun aSeenStandBelongsToItsSpotWithinTheBoxPlusMargin() {
        val spot = CorpseSpot(-154, 12, -174)
        assertEquals(0.0, CorpseLocatorSolver.standOffset(spot, -154.0, 12.3, -174.0), "the owner's JADE_1 stand")
        assertEquals(0.0, CorpseLocatorSolver.standOffset(CorpseSpot(-117, 4, -168), -117.5, 3.7, -168.5), "the owner's CITR_2 stand (rounded convention)")
        assertEquals(0.0, CorpseLocatorSolver.standOffset(spot, -154.99, 11.01, -173.01), "anywhere in the box")
        assertEquals(0.4, CorpseLocatorSolver.standOffset(spot, -152.6, 12.5, -173.5)!!, 1e-9, "a hair past the box")
        assertNull(CorpseLocatorSolver.standOffset(spot, -152.4, 12.5, -173.5), "0.6 past the box")
        assertNull(CorpseLocatorSolver.standOffset(spot, -155.4, 12.5, -175.4), "0.57 past diagonally")
    }

    @Test
    fun bodyPointsCoverHeadChestFeetAndSidesInsideTheBox() {
        // An armor stand at (-154.0, 12.3, -174.0): 0.5 wide, 1.975 tall.
        val pts = MobSpotter.bodyPoints(-154.25, 12.3, -174.25, -153.75, 14.275, -173.75)
        assertEquals(7, pts.size)
        for (p in pts) {
            assertTrue(p[0] > -154.25 && p[0] < -153.75 && p[2] > -174.25 && p[2] < -173.75 && p[1] > 12.3 && p[1] < 14.275, p.toList().toString())
        }
        val ys = pts.map { it[1] }.distinct().sorted()
        assertEquals(3, ys.size, "feet, chest, head")
        assertEquals(12.3 + 1.975 * 0.9, ys.last(), 1e-9)
        assertEquals(12.3 + 1.975 * 0.1, ys.first(), 1e-9)
        val chest = pts.filter { it[1] == ys[1] }
        assertEquals(5, chest.size, "center and four sides")
        assertEquals(-154.2, chest.minOf { it[0] }, 1e-9)
        assertEquals(-153.8, chest.maxOf { it[0] }, 1e-9)
        assertEquals(-174.2, chest.minOf { it[2] }, 1e-9)
        assertEquals(-173.8, chest.maxOf { it[2] }, 1e-9)
    }
}
