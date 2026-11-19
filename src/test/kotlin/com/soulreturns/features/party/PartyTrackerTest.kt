package com.soulreturns.features.party

import com.soulreturns.features.party.PartyManager.PartyDisbandReason
import com.soulreturns.features.party.PartyManager.PartyEvent
import com.soulreturns.features.party.PartyManager.PartyRole
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PartyTrackerTest {
    private val events = ArrayList<PartyEvent>()
    private val tracker = PartyTracker { events += it }.apply {
        selfName = "Alice"
        selfUuid = PartyReplay.uuidOf("Alice")
        onHypixel = true
    }
    private var now = 1_000_000L

    private fun chat(vararg lines: String) = lines.forEach { tracker.onChat(it, now) }

    private fun names(): List<String>? = tracker.state?.members?.values?.map { it.name }?.sorted()

    private fun leader(): String? = tracker.state?.leader?.name

    private fun size(): Int = tracker.state?.size ?: 0

    private fun role(name: String): PartyRole? = tracker.state?.members?.get(name.lowercase())?.role

    private fun info(vararg roles: Pair<String, PartyRole>) =
        tracker.onPartyInfo(true, roles.associate { PartyReplay.uuidOf(it.first) to it.second }, mapOf(PartyReplay.uuidOf("Alice") to "Alice"), now)

    private fun pollAt(t: Long): PartyTracker.Request? {
        assertNull(tracker.poll(t - 1))
        return tracker.poll(t)
    }

    private fun joinBobsParty() = chat("You have joined [MVP+] Bob's party!", "You'll be partying with: [VIP] Carol, Dave")

    @Test
    fun colorCodedLinesAsTheGameDeliversThem() {
        chat("§r§eYou have joined §r§b[MVP§r§d+§r§b] Grace' §r§eparty!")
        assertEquals("Grace", leader())
        chat("§r§dParty Finder §r§f> §r§6Bob §r§ejoined the dungeon group! (§r§bMage Level 48§r§e)")
        chat("§r§9Party §8> §6[MVP§3++§6] Carol§f: §rhello")
        assertEquals(listOf("Alice", "Bob", "Carol", "Grace"), names())
        chat(
            "§r§eParty Members (2)",
            "§r§eParty Leader: §r§6[MVP§r§3++§r§6] Alice§r§a ● ",
            "§r§eParty Members: §r§b[MVP§r§d+§r§b] Bob§r§a ● ",
            "§r§9§m-----------------------------------------------------",
        )
        assertEquals("Alice", leader())
        assertEquals(listOf("Alice", "Bob"), names())
    }

    @Test
    fun joinShapes() {
        chat("-----\n[MVP+] Bob has invited you to join their party!\nYou have 60 seconds to accept. Click here to join!\n-----")
        assertEquals(1, tracker.pendingInvites(now).size)
        joinBobsParty()
        assertEquals("Bob", leader())
        assertEquals(listOf("Alice", "Bob", "Carol", "Dave"), names())
        assertTrue(tracker.pendingInvites(now).isEmpty())
        chat("[MVP+] Erin joined the party.", "Party Finder > Frank joined the dungeon group! (Healer Level 44)", "Party Finder > Grace joined the group! (Combat Level 51)")
        assertEquals(7, size())
        assertEquals("Bob", leader())
    }

    @Test
    fun possessiveWithoutS() {
        chat("You have joined [MVP+] Grace' party!")
        assertEquals("Grace", leader())
        assertEquals(PartyRole.MEMBER, role("Alice"))
    }

    @Test
    fun finderSelfJoinStartsAPartyWithUnknownLeader() {
        chat("Attempting to add you to the party...", "Party Finder > Alice joined the dungeon group! (Mage Level 48)")
        assertEquals(listOf("Alice"), names())
        assertNull(leader())
        assertEquals(PartyTracker.Request.PARTY_INFO, pollAt(now + PartyTracker.AMBIGUOUS_DELAY_MS))
    }

    @Test
    fun publicPartyAndOwnInviteOutsideAPartyMakeSelfLeader() {
        chat("Created a public party! Players can join with /party join Alice", "Party is capped at 2 players.")
        assertEquals("Alice", leader())
        chat("You left the party.")
        assertNull(tracker.state)
        chat("[MVP+] Alice invited [VIP] Bob to the party! They have 60 seconds to accept.")
        assertEquals("Alice", leader())
        assertEquals(listOf("Alice"), names())
        assertTrue(tracker.pendingInvites(now).single().outgoing)
    }

    @Test
    fun ownInviteInsideAPartyKeepsTheLeader() {
        joinBobsParty()
        chat("[MVP+] Alice invited [VIP] Erin to the party! They have 60 seconds to accept.", "[VIP] Erin joined the party.")
        assertEquals("Bob", leader())
        assertEquals(5, size())
    }

    @Test
    fun someoneElsesInviteShowsTheInviterIsAMember() {
        chat("You'll be partying with: [MVP+] Bob")
        chat("[MVP+] Carol invited [VIP] Erin to the party! They have 60 seconds to accept.")
        assertEquals(listOf("Alice", "Bob", "Carol"), names())
        chat("The party invite to [VIP] Erin has expired.")
        assertTrue(tracker.pendingInvites(now).isEmpty())
        chat("-----\n[MVP+] Frank has invited you to join [MVP+] Grace's party!\nYou have 60 seconds to accept. Click here to join!\n-----")
        assertEquals("Frank", tracker.pendingInvites(now).single().from)
        chat("The party invite from [MVP+] Frank has expired.")
        assertTrue(tracker.pendingInvites(now).isEmpty())
    }

    @Test
    fun leaveShapes() {
        joinBobsParty()
        chat("[VIP] Carol has left the party.")
        chat("Dave has been removed from the party.")
        assertEquals(listOf("Alice", "Bob"), names())
        chat("[MVP+] Erin joined the party.", "[MVP+] Frank joined the party.", "[MVP+] Grace joined the party.")
        chat("[MVP+] Erin was removed from your party because they disconnected.")
        chat("Kicked [MVP+] Frank because they were offline.")
        assertEquals(listOf("Alice", "Bob", "Grace"), names())
        assertTrue(events.any { it is PartyEvent.MemberKicked && it.memberName == "Frank" })
        chat("Kicked [MVP+] Grace, [VIP] Bob because they were offline.")
        assertEquals(listOf("Alice"), names())
    }

    @Test
    fun selfLeavingOrKicked() {
        joinBobsParty()
        chat("You have been kicked from the party by [MVP+] Bob ")
        assertNull(tracker.state)
        assertTrue(events.any { it is PartyEvent.PartyDisbanded && it.reason == PartyDisbandReason.KICKED })
        joinBobsParty()
        chat("The party was transferred to [MVP+] Bob because [MVP++] Alice left")
        assertNull(tracker.state)
    }

    @Test
    fun disbandShapes() {
        for ((line, reason) in listOf(
            "[MVP+] Bob has disbanded the party!" to PartyDisbandReason.LEADER_DISBANDED,
            "The party was disbanded because all invites expired and the party was empty." to PartyDisbandReason.EMPTY_OR_EXPIRED,
            "The party was disbanded because the party leader disconnected." to PartyDisbandReason.LEADER_DISCONNECTED,
            "The party was disbanded because of something new." to PartyDisbandReason.UNKNOWN,
        )) {
            joinBobsParty()
            events.clear()
            chat(line)
            assertNull(tracker.state, line)
            assertEquals(reason, events.filterIsInstance<PartyEvent.PartyDisbanded>().single().reason, line)
        }
    }

    @Test
    fun offlineMembersStayMembers() {
        joinBobsParty()
        chat(
            "[VIP] Carol has disconnected, they have 5 minutes to rejoin before they are removed from the party.",
            "The party leader, [MVP+] Bob has disconnected, they have 5 minutes to rejoin before the party is disbanded.",
        )
        assertEquals(4, size())
        chat("[VIP] Carol has rejoined.", "The party leader [MVP+] Bob has rejoined.")
        assertEquals(4, size())
        assertEquals("Bob", leader())
    }

    @Test
    fun theseLinesProveAPartyAndItsLeader() {
        chat("[VIP] Carol has rejoined.")
        assertEquals(listOf("Alice", "Carol"), names())
        assertNull(leader())
        chat("Party Leader, [MVP+] Bob, summoned you to their server.")
        assertEquals("Bob", leader())
        assertEquals(3, size())
    }

    @Test
    fun leaderShapes() {
        joinBobsParty()
        chat("The party was transferred to [VIP] Carol by [MVP+] Bob")
        assertEquals("Carol", leader())
        assertEquals(PartyRole.MODERATOR, role("Bob"))
        chat("The party was transferred to Dave because [VIP] Carol left")
        assertEquals("Dave", leader())
        assertEquals(listOf("Alice", "Bob", "Dave"), names())
        chat("[MVP+] Bob has promoted [MVP++] Alice to Party Leader")
        assertEquals("Alice", leader())
        assertEquals(PartyRole.MODERATOR, role("Dave"))
        chat("[MVP++] Alice has demoted Dave to Party Member")
        assertEquals(PartyRole.MEMBER, role("Dave"))
        chat("[MVP++] Alice has promoted Dave to Party Moderator")
        assertEquals(PartyRole.MODERATOR, role("Dave"))
        chat("[MVP++] Alice has demoted Dave to Party Member", "Dave is now a Party Moderator")
        assertEquals(PartyRole.MODERATOR, role("Dave"))
        assertEquals("Alice", leader())
    }

    @Test
    fun notLeaderDropsATrackedSelfLeader() {
        chat("Created a public party! Players can join with /party join Alice")
        chat("You are not this party's leader!")
        assertNull(leader())
        assertEquals(PartyRole.MEMBER, role("Alice"))
        chat("You are not allowed to invite players.")
        assertTrue(tracker.state != null)
    }

    @Test
    fun warpBlockMembers() {
        chat("✔ [MVP+] Bob warped to your server")
        assertNull(tracker.state, "a warp result outside a warp block proves nothing")
        chat(
            "-----------------------------------------------------",
            "SkyBlock Party Warp (6 players)",
            "⚔ [MVP+] Bob warped to your instance",
            "✮ [VIP] Carol is on your server",
            "✖ Dave is offline",
            "✖ [MVP+] Erin is not allowed on your server!",
            "✖ Frank didn't warp correctly!",
            "-----------------------------------------------------",
        )
        assertEquals(listOf("Alice", "Bob", "Carol", "Dave", "Erin", "Frank"), names())
        chat("✔ [MVP+] Grace warped to your server")
        assertEquals(6, size(), "the block ended with its divider")
        chat("You summoned [VIP] Grace to your server.")
        assertEquals(7, size())
    }

    @Test
    fun inPartyEvidence() {
        chat("You are now in the PARTY channel")
        assertTrue(tracker.state != null)
        chat("You are not in a party right now.")
        assertNull(tracker.state)
        chat("Party Finder > You are already in a party!")
        assertTrue(tracker.state != null)
        chat("[MVP+] Bob is already in the party.")
        assertEquals(listOf("Alice", "Bob"), names())
        chat("Party > [MVP+] Carol: hello")
        assertEquals(listOf("Alice", "Bob", "Carol"), names())
    }

    @Test
    fun everyNotInAPartyReplyClears() {
        for (line in listOf(
            "You are not in a party right now.",
            "You are not currently in a party.",
            "You are not currently in a party!",
            "You are not in a party.",
            "You are not in a party and were moved to the ALL channel.",
            "You must be in a party to join the party channel!",
            "-----\nYou are not in a party right now.\n-----",
        )) {
            joinBobsParty()
            chat(line)
            assertNull(tracker.state, line)
        }
    }

    @Test
    fun linesWithoutRosterNewsChangeNothing() {
        joinBobsParty()
        val before = tracker.state
        chat(
            "Party Finder > Your party has been queued in the dungeon finder!",
            "Party Finder > Your dungeon group is full! Click here to warp to the dungeon!",
            "Queueing your party...",
            "[MVP+] Bob queued for Glacite Mineshafts!\nThe party is in position #41 of the queue!",
            "Warping party...",
            "An error occurred while warping the party!",
            "You cannot invite that player since they're not online.",
            "[VIP] Erin has already been invited to the party.",
            "You don't have an invite to that player's party.",
            "That player is not in your party!",
            "You can't transfer the party to yourself!",
            "Only the party leader can summon members!",
            "[MVP+] Bob enabled All Invite",
            "Your party was split for balancing purposes.",
        )
        assertEquals(before?.members, tracker.state?.members)
        assertEquals(0, tracker.unknownLines())
    }

    @Test
    fun unknownPartyLinesAreCountedAndAskForAResync() {
        tracker.onJoin(now)
        assertEquals(PartyTracker.Request.PARTY_INFO, pollAt(now + PartyTracker.JOIN_DELAY_MS))
        tracker.onPartyInfo(false, emptyMap(), emptyMap(), now + PartyTracker.JOIN_DELAY_MS + 100)
        now += 10_000
        chat("The party is now in Private mode.", "[MVP+] Bob: party later?")
        assertEquals(1, tracker.unknownLines())
        assertEquals(PartyTracker.Request.PARTY_INFO, pollAt(now + PartyTracker.AMBIGUOUS_DELAY_MS))
    }

    @Test
    fun partyListWithoutItsClosingDivider() {
        joinBobsParty()
        chat("-----", "Party Members (2)", "Party Leader: [MVP++] Alice ●", "Party Members: [VIP] Erin ●")
        tracker.poll(now + PartyTracker.LIST_IDLE_MS - 1)
        assertEquals("Bob", leader(), "still reading the list")
        tracker.poll(now + PartyTracker.LIST_IDLE_MS)
        assertEquals("Alice", leader())
        assertEquals(listOf("Alice", "Erin"), names())

        chat("Party Members (3)", "Party Leader: [MVP++] Alice ●", "Party Members: [VIP] Erin ● Frank ●", "Autopet equipped your [Lvl 100] Griffin! VIEW RULE")
        assertEquals(listOf("Alice", "Erin", "Frank"), names(), "an unrelated line ends the list at once")
    }

    @Test
    fun partyListReplacesTheRoster() {
        joinBobsParty()
        events.clear()
        chat(
            "-----",
            "Party Members (4)",
            "",
            "Party Leader: [MVP+] Carol ●",
            "Party Moderators: [MVP+] Bob ●",
            "Party Members: [MVP++] Alice ● [VIP] Erin ● ",
            "-----",
        )
        assertEquals("Carol", leader())
        assertEquals(PartyRole.MODERATOR, role("Bob"))
        assertEquals(listOf("Alice", "Bob", "Carol", "Erin"), names())
        assertEquals(PartySource.PARTY_LIST, tracker.source)
        assertTrue(events.any { it is PartyEvent.PartyCorrected })
        assertEquals(1, tracker.corrections()["party_list.leader"])
        // a list without its closing divider ends at the next other line
        chat("Party Members (2)", "Party Leader: [MVP++] Alice ●", "Party Members: [VIP] Erin ●", "[VIP] Erin has left the party.")
        assertEquals(listOf("Alice"), names())
    }

    @Test
    fun modApiPacketNamesWhatItCanAndCountsTheRest() {
        joinBobsParty()
        events.clear()
        info("Alice" to PartyRole.MEMBER, "Bob" to PartyRole.LEADER, "Carol" to PartyRole.MEMBER, "Dave" to PartyRole.MEMBER)
        assertEquals(4, size())
        assertEquals("Bob", leader(), "the only leader uuid pairs with the only chat leader")
        assertTrue(events.isEmpty(), "an agreeing packet changes nothing")
        assertEquals(PartySource.MOD_API, tracker.source)
        info("Alice" to PartyRole.MEMBER, "Bob" to PartyRole.LEADER, "Carol" to PartyRole.MEMBER, "Dave" to PartyRole.MEMBER)
        assertTrue(events.isEmpty())

        info("Alice" to PartyRole.MEMBER, "Bob" to PartyRole.LEADER, "Carol" to PartyRole.MEMBER, "Dave" to PartyRole.MEMBER, "Erin" to PartyRole.MEMBER)
        assertEquals(5, size())
        assertEquals(1, tracker.state!!.unnamedMembers.size)
        assertTrue(events.single() is PartyEvent.PartyCorrected)
        assertEquals(1, tracker.corrections()["mod_api.size"])
        assertEquals(PartyTracker.Request.PARTY_LIST, tracker.poll(now), "an unnamed member asks /party list for names")

        now += 1_000
        chat("[VIP] Erin joined the party.")
        assertEquals(5, size(), "the newcomer is the uuid the packet just counted")
        assertTrue(tracker.state!!.unnamedMembers.isEmpty())

        tracker.onPartyInfo(false, emptyMap(), emptyMap(), now)
        assertNull(tracker.state)
        assertEquals(PartyDisbandReason.UNKNOWN, events.filterIsInstance<PartyEvent.PartyDisbanded>().last().reason)
    }

    @Test
    fun aLeaveLongAfterThePacketDropsAnUnnamedUuid() {
        info("Alice" to PartyRole.LEADER, "Bob" to PartyRole.MEMBER, "Carol" to PartyRole.MEMBER)
        assertEquals(3, size())
        now += 60_000
        chat("[VIP] Carol has left the party.")
        assertEquals(2, size())
        now += 60_000
        chat("[VIP] Carol joined the party.")
        assertEquals(3, size(), "a join long after the packet is a new member")
    }

    @Test
    fun requestFloorsAndFallbacks() {
        tracker.onJoin(now)
        assertNull(tracker.poll(now + PartyTracker.JOIN_DELAY_MS - 1))
        val sentAt = now + PartyTracker.JOIN_DELAY_MS
        assertEquals(PartyTracker.Request.PARTY_INFO, tracker.poll(sentAt))
        now = sentAt + 100
        joinBobsParty()
        assertNull(tracker.poll(sentAt + PartyTracker.INFO_FLOOR_MS - 1), "mod API at most every 5 s")
        assertEquals(PartyTracker.Request.PARTY_INFO, tracker.poll(sentAt + PartyTracker.INFO_FLOOR_MS))
        // still no answer and a member it cannot place: one /party list, then not again within a minute
        now = sentAt + PartyTracker.INFO_FLOOR_MS + 50
        chat("[VIP] Carol has rejoined.", "[MVP+] Erin is already in the party.")
        val from = sentAt + PartyTracker.INFO_FLOOR_MS + PartyTracker.INFO_TIMEOUT_MS
        val requests = (0..10).mapNotNull { tracker.poll(from + it * 1_000L) }
        assertEquals(1, requests.count { it == PartyTracker.Request.PARTY_LIST }, requests.toString())
        assertEquals(1, tracker.sent()["party_list"])
    }

    @Test
    fun aPacketFromAnotherModSatisfiesAPendingRequest() {
        joinBobsParty()
        now += 1_000
        info("Alice" to PartyRole.MEMBER, "Bob" to PartyRole.LEADER, "Carol" to PartyRole.MEMBER, "Dave" to PartyRole.MEMBER)
        assertNull(tracker.poll(now + 60_000))
    }

    @Test
    fun noPartyListOffHypixel() {
        tracker.onHypixel = false
        chat("Party Finder > Alice joined the dungeon group! (Mage Level 48)")
        val requests = (0..120).mapNotNull { tracker.poll(now + it * 1_000L) }
        assertFalse(PartyTracker.Request.PARTY_LIST in requests)
    }

    @Test
    fun echoOfTheTrackersPartyListIsHidden() {
        tracker.onPartyListSent(now)
        assertTrue(tracker.hidesEcho("§9§m-----------------------------------------------------", now))
        assertTrue(tracker.hidesEcho("§eParty Members (2)", now))
        assertTrue(tracker.hidesEcho("", now))
        assertTrue(tracker.hidesEcho("Party Leader: [MVP+] Bob ●", now))
        assertFalse(tracker.hidesEcho("[MVP+] Bob joined the party.", now))
        assertTrue(tracker.hidesEcho("-----------------------------------------------------", now + 10))
        assertFalse(tracker.hidesEcho("Party Members (2)", now + 20), "the closing divider ends the window")

        tracker.onPartyListSent(now)
        assertFalse(tracker.hidesEcho("Party Members (2)", now + PartyTracker.ECHO_WINDOW_MS + 1))
        assertFalse(PartyTracker().hidesEcho("Party Members (2)", now), "a list the player asked for stays visible")
    }

    @Test
    fun disconnectForgetsEverythingSilently() {
        joinBobsParty()
        tracker.onJoin(now)
        tracker.onJoin(now + 10_000)
        assertEquals(4, size(), "a server switch keeps the party")
        events.clear()
        tracker.onDisconnect()
        assertNull(tracker.state)
        assertEquals(PartySource.NONE, tracker.source)
        assertTrue(events.isEmpty())
        assertTrue(tracker.pendingInvites(now).isEmpty())
        assertNull(tracker.poll(now + 60_000))
    }
}
