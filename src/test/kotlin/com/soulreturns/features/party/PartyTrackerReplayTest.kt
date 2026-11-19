package com.soulreturns.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Every Hypixel session in `party/sessions.txt` against its mod-API packets. The logic before the
 * tracker matched 677 of these 1090 packets from chat (41 drift episodes, 8 "not in a party" replies
 * while it still tracked one).
 */
class PartyTrackerReplayTest {
    private val sessions = PartyReplay.load("/party/sessions.txt")

    @Test
    fun chatAloneFollowsThePackets() {
        val r = PartyReplay.run(sessions, grace = true) { PartyReplay.TrackerSubject(feedInfo = false) }
        println("chat only: $r")
        // What chat cannot know: parties formed before the log starts, a Party Finder group's other members.
        assertTrue(r.ok >= 1034 && r.episodes <= 3, r.details.joinToString("\n", "$r\n"))
        assertEquals(0, r.ghostReplies, r.toString())
    }

    @Test
    fun withThePacketsEveryStateHolds() {
        val subjects = ArrayList<PartyReplay.TrackerSubject>()
        val r = PartyReplay.run(sessions, grace = false) { PartyReplay.TrackerSubject(feedInfo = true).also(subjects::add) }
        println("with packets: $r")
        // The one miss: a packet in the same second as a Party Finder join that only names Alice.
        assertTrue(r.ok >= 1089, r.details.joinToString("\n", "$r\n"))
        assertEquals(0, r.ghostReplies, r.toString())
        assertEquals(0, subjects.sumOf { it.tracker.unknownLines() }, "a party line no shape knows")
    }
}
