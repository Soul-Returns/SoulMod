package com.soulreturns.features.party

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** One test per failure sequence the session replay turned up (see `party/sequences.txt`). */
class PartyTrackerSequenceTest {
    private val sequences = PartyReplay.load("/party/sequences.txt").associateBy { it.id }

    private fun check(name: String) {
        val failures = PartyReplay.expectFailures(sequences.getValue(name), PartyReplay.TrackerSubject(feedInfo = true))
        assertTrue(failures.isEmpty(), failures.joinToString("\n"))
    }

    @Test
    fun everySequenceHasATest() {
        assertEquals(TESTED.sorted(), sequences.keys.sorted())
    }

    @Test
    fun possessiveWithoutS() = check("possessive_without_s")

    @Test
    fun finderDungeonJoin() = check("finder_dungeon_join")

    @Test
    fun finderKuudraJoin() = check("finder_kuudra_join")

    @Test
    fun finderSelfJoin() = check("finder_self_join")

    @Test
    fun leaderDisconnectDisband() = check("leader_disconnect_disband")

    @Test
    fun transferBecauseLeft() = check("transfer_because_left")

    @Test
    fun kickedOffline() = check("kicked_offline")

    @Test
    fun joinNeverNamesLeader() = check("join_never_names_leader")

    @Test
    fun ownInviteInsideParty() = check("own_invite_inside_party")

    @Test
    fun reconnectResetsState() = check("reconnect_resets_state")

    @Test
    fun wrappedNotInParty() = check("wrapped_not_in_party")

    @Test
    fun rareShapes() = check("rare_shapes")

    @Test
    fun listWithoutClosingDivider() = check("list_without_closing_divider")

    private companion object {
        val TESTED = listOf(
            "possessive_without_s", "finder_dungeon_join", "finder_kuudra_join", "finder_self_join",
            "leader_disconnect_disband", "transfer_because_left", "kicked_offline", "join_never_names_leader",
            "own_invite_inside_party", "reconnect_resets_state", "wrapped_not_in_party", "rare_shapes",
            "list_without_closing_divider",
        )
    }
}
