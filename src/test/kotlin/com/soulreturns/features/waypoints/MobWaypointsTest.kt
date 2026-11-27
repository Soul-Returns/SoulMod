package com.soulreturns.features.waypoints

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MobWaypointsTest {
    @Test
    fun rolledInIsRollingAndScared() {
        assertTrue(Pangolin.rolledIn("ROLLING"))
        assertTrue(Pangolin.rolledIn("SCARED"))
        assertFalse(Pangolin.rolledIn("IDLE"))
        assertFalse(Pangolin.rolledIn("UNROLLING"))
        assertFalse(Pangolin.rolledIn("SOMETHING_NEW"))
    }

    @Test
    fun aSightingFollowsItsEntity() {
        val s = MobSightings()
        s.see(listOf(SeenMob(7, 1.0, 96.0, 1.0, null)), 0)
        s.see(listOf(SeenMob(7, 9.0, 96.0, 1.0, Pangolin.ROLLED_IN)), 250)
        val only = s.all.single()
        assertEquals(9.0, only.x)
        assertEquals(Pangolin.ROLLED_IN, only.state)
        assertTrue(only.inSight)
        assertEquals(250, only.seenAtMs)
    }

    @Test
    fun aResentEntityTakesTheNearbySightingButTwoMobsStayTwo() {
        val s = MobSightings()
        s.see(listOf(SeenMob(1, 0.0, 96.0, 0.0, null), SeenMob(2, 2.0, 96.0, 0.0, null)), 0)
        assertEquals(2, s.all.size)
        // Entity 1 comes back as 3 a block away; entity 2 is still seen: 3 must not steal 2's sighting.
        s.see(listOf(SeenMob(3, 1.0, 96.0, 0.0, null), SeenMob(2, 2.0, 96.0, 0.0, null)), 250)
        assertEquals(2, s.all.size)
        assertEquals(setOf(2, 3), s.all.map { it.entityId }.toSet())
    }

    @Test
    fun outOfSightItStaysThenExpires() {
        val s = MobSightings()
        s.see(listOf(SeenMob(7, 1.0, 96.0, 1.0, Pangolin.ROLLED_IN)), 0)
        s.see(emptyList(), 250)
        s.endScan { false }
        val left = s.all.single()
        assertFalse(left.inSight)
        assertEquals(1.0, left.x)
        assertTrue(s.expire(MobSightings.EXPIRE_MS).isEmpty())
        assertEquals(1, s.expire(MobSightings.EXPIRE_MS + 1).size)
        assertTrue(s.all.isEmpty())
    }

    @Test
    fun aSpotSeenEmptyTwiceInARowGoes() {
        val s = MobSightings()
        s.see(listOf(SeenMob(7, 1.0, 96.0, 1.0, null)), 0)
        s.see(emptyList(), 250)
        assertTrue(s.endScan { true }.isEmpty())
        s.see(emptyList(), 500)
        assertTrue(s.endScan { false }.isEmpty(), "a scan without a clear view resets the count")
        s.see(emptyList(), 750)
        s.endScan { true }
        s.see(emptyList(), 1000)
        assertEquals(1, s.endScan { true }.size)
        assertTrue(s.all.isEmpty())
    }

    @Test
    fun aSeenMobIsNeverJudgedEmpty() {
        val s = MobSightings()
        repeat(5) {
            s.see(listOf(SeenMob(7, 1.0, 96.0, 1.0, null)), it * 250L)
            s.endScan { true }
        }
        assertEquals(1, s.all.size)
    }

    @Test
    fun aSharedOneIsAnnouncedAndBecomesOwnWhenSeen() {
        val s = MobSightings()
        val shared = s.received("Bob", 10.0, 96.0, 10.0, 0)
        assertEquals("Bob", shared.sharedBy)
        assertTrue(shared.announced)
        assertNull(s.nextToShare(0, 60_000))
        s.see(listOf(SeenMob(9, 11.0, 96.0, 10.5, null)), 100)
        val own = s.all.single()
        assertSame(shared, own)
        assertNull(own.sharedBy)
        assertTrue(own.announced, "the party has it already")
        assertNull(s.nextToShare(100, 60_000))
    }

    @Test
    fun aShareNearAnOwnSightingOnlyAnnouncesIt() {
        val s = MobSightings()
        s.see(listOf(SeenMob(9, 11.0, 96.0, 10.0, null)), 0)
        assertNotNull(s.nextToShare(0, 60_000))
        s.received("Bob", 14.0, 96.0, 10.0, 10)
        val own = s.all.single()
        assertNull(own.sharedBy)
        assertEquals(11.0, own.x)
        assertNull(s.nextToShare(10, 60_000))
    }

    @Test
    fun onlyFreshOwnSightingsAreShared() {
        val s = MobSightings()
        s.see(listOf(SeenMob(1, 0.0, 96.0, 0.0, null)), 0)
        s.see(listOf(SeenMob(2, 50.0, 96.0, 0.0, null)), 1_000)
        assertEquals(2, s.nextToShare(1_000, 60_000)?.entityId, "newest first")
        s.markAnnounced(s.nextToShare(1_000, 60_000)!!)
        assertEquals(1, s.nextToShare(1_000, 60_000)?.entityId)
        assertNull(s.nextToShare(60_001, 60_000), "older than the LOS rule allows")
    }

    @Test
    fun limiterKeepsAGapAndAPerMinuteCap() {
        val l = ShareLimiter()
        assertTrue(l.allows(0))
        l.onSent(0.0, 0.0, 0.0, 0)
        assertFalse(l.allows(4_999))
        assertTrue(l.allows(5_000))
        l.onSent(100.0, 0.0, 0.0, 5_000)
        l.onSent(200.0, 0.0, 0.0, 10_000)
        assertFalse(l.allows(20_000), "three a minute")
        assertFalse(l.allows(59_999))
        assertTrue(l.allows(60_000), "the first one left the window")
    }

    @Test
    fun limiterCoversSpotsSentOrHeard() {
        val l = ShareLimiter()
        l.onHeard(0.0, 96.0, 0.0, 0)
        assertTrue(l.covered(5.0, 96.0, 5.0, 1_000))
        assertFalse(l.covered(10.0, 96.0, 0.0, 1_000))
        assertFalse(l.covered(0.0, 96.0, 0.0, 60_000), "forgotten after a minute")
        l.onSent(30.0, 96.0, 0.0, 61_000)
        assertTrue(l.covered(31.0, 96.0, 0.0, 61_000))
        l.forgetSpots()
        assertFalse(l.covered(31.0, 96.0, 0.0, 61_000))
        assertFalse(l.allows(61_000), "a server change keeps the send budget")
    }

    @Test
    fun theShareLineRoundTrips() {
        val line = WaypointShareLine.format(-539.53, 97.52, 234.9, "Pangolin")
        assertEquals("x: -540, y: 98, z: 235 | Pangolin", line)
        val parsed = WaypointShareLine.parse("Party > [MVP+] Bob_2: $line")
        assertEquals(WaypointShareLine.Shared("Bob_2", -540.0, 98.0, 235.0, "Pangolin"), parsed)
    }

    @Test
    fun theParserTakesOtherModsShapesAndRejectsTheRest() {
        assertEquals("Vanquisher", WaypointShareLine.parse("Party > [VIP] Al: x: -1.5, y: 64.0, z: 2 | Vanquisher")?.label)
        assertEquals("Al", WaypointShareLine.parse("Party > Al: x: 1 y: 64 z: 2 | Pangolin")?.sender)
        assertNull(WaypointShareLine.parse("Party > Al: x: 1, y: 64, z: 2"), "no label: a bare coordinates line")
        assertNull(WaypointShareLine.parse("Guild > Al: x: 1, y: 64, z: 2 | Pangolin"))
        assertNull(WaypointShareLine.parse("Al: x: 1, y: 64, z: 2 | Pangolin"), "public chat")
        assertNull(WaypointShareLine.parse("Party > Al: x: 1, y: 900, z: 2 | Pangolin"), "out of the world")
        assertNull(WaypointShareLine.parse("Party > Al: look x: 1, y: 64, z: 2 | Pangolin"))
    }
}
