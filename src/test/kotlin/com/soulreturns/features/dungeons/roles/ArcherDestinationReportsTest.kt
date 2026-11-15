package com.soulreturns.features.dungeons.roles

import com.soulreturns.features.dungeons.boss.BossPhase
import com.soulreturns.features.dungeons.leap.TeammateLocations
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArcherDestinationReportsTest {
    private fun waitingForHealer() = ArcherRoleModel().apply {
        sync(BossPhase.GOLDOR, 2, 2, false, false, false, false)
        complete("s2.t5")
        complete("s2.lever")
    }

    @Test
    fun aFreshOwnEe3CalloutConfirmsTheDestinationButStillNeedsUsableProtection() {
        val route = waitingForHealer()
        val locations = TeammateLocations()
        locations.onPartyLine("Healer", "at ee3!", 1000) { null }
        val known = route.destination(locations.locate("Healer", null, 1500))
        assertTrue(known)
        assertEquals("Preleap → Healer", route.cue(2, "Healer", known, DeathSaveTracker.USABLE)?.text)
        assertTrue(route.cue(2, "Healer", known, DeathSaveTracker.UNKNOWN)!!.waiting)
        assertEquals("Wait · Healer location ?", route.cue(2, "Healer", false, DeathSaveTracker.USABLE)?.text)
    }

    @Test
    fun staleUnknownAndWrongSpotReportsCannotConfirmTheDestination() {
        val route = waitingForHealer()
        val locations = TeammateLocations()
        assertFalse(route.destination(locations.locate("Healer", null, 1000)))
        locations.onPartyLine("Healer", "at ee3!", 1000) { null }
        assertTrue(route.destination(locations.locate("Healer", null, 21_000)))
        assertFalse(route.destination(locations.locate("Healer", null, 21_001)))
        assertFalse(route.destination(locations.locate("Healer", null, 61_001)))
        locations.onPartyLine("Healer", "at lights!", 62_000) { null }
        assertFalse(route.destination(locations.locate("Healer", null, 62_100)))
    }

    @Test
    fun actualSeenPositionWinsOverAFreshReportInTheWrongPlace() {
        val route = waitingForHealer()
        val locations = TeammateLocations()
        locations.onPartyLine("Healer", "at ee3!", 1000) { null }
        assertTrue(route.destination(locations.locate("Healer", null, 1500)))
        assertFalse(route.destination(60.0, 132.0, 139.0))
        val seen = locations.locate("Healer", "Lights", 1600)
        assertEquals(TeammateLocations.Source.SEEN, seen.source)
        assertFalse(route.destination(seen))
        assertFalse(route.destination(locations.locate("Healer", null, 1700)))
    }

    @Test
    fun movementIntentionsAndInferredLeapReportsAreNotActualArrivalEvidence() {
        val route = waitingForHealer()
        val locations = TeammateLocations()
        for (body in listOf("going ee3!", "omw to ee3!", "I'm omw ee3!", "[Soul] omw ee3!")) {
            locations.onPartyLine("Healer", body, 1000) { null }
            val location = locations.locate("Healer", null, 1500)
            assertEquals("ee3", location.label, "the leap menu can still show a destination intention")
            assertFalse(location.atSpot)
            assertFalse(route.destination(location), body)
        }
        locations.onPartyLine("Healer", "Boo! Leaped to Friend!", 2000) { "ee3" }
        assertFalse(route.destination(locations.locate("Healer", null, 2500)))
        locations.onPartyLine("Healer", "at ee3!", 3000) { null }
        assertTrue(route.destination(locations.locate("Healer", null, 3500)))
    }

    @Test
    fun coordinatesAndExistingPhaseDeathInvalidationKeepTheirSemantics() {
        val route = waitingForHealer()
        val locations = TeammateLocations()
        locations.onPartyLine("Healer", "x: 2, y: 109, z: 107", 1000) { null }
        assertTrue(route.destination(locations.locate("Healer", null, 1500)))
        locations.forget("Healer")
        assertFalse(route.destination(locations.locate("Healer", null, 1600)))
        locations.onPartyLine("Healer", "at ee3!", 2000) { null }
        locations.onPhaseLine()
        assertFalse(route.destination(locations.locate("Healer", null, 2100)))
    }

    @Test
    fun labelsQualifyOnlyForTheirOwnRouteDestination() {
        val route = ArcherRoleModel()
        route.sync(BossPhase.GOLDOR, 1, 1, false, false, false, false)
        route.complete("s1.t4")
        route.complete("s1.t3")
        val locations = TeammateLocations()
        locations.onPartyLine("Mage", "at ee2!", 1000) { null }
        assertTrue(route.destination(locations.locate("Mage", null, 1500)))
        locations.onPartyLine("Mage", "at lights!", 2000) { null }
        assertTrue(route.destination(locations.locate("Mage", null, 2500)))
        locations.onPartyLine("Mage", "at ee3!", 3000) { null }
        assertFalse(route.destination(locations.locate("Mage", null, 3500)))
    }
}
