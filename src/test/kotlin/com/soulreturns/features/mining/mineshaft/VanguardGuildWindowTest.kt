package com.soulreturns.features.mining.mineshaft

import com.soulreturns.util.MessageDetector
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Lines are the recorded shapes (rule://mineshaft) with invented names. */
class VanguardGuildWindowTest {
    private fun strip(raw: String) = MessageDetector.stripColorCodes(raw)

    private fun guild(name: String, text: String, rank: String = "[MVP+] ", guildRank: String = " [Member]") = "Guild > $rank$name$guildRank: $text"

    private fun openWindow(paceMs: Long = 1500L, max: Int = 10) = VanguardGuildWindow(paceMs, max).also { it.begin() }

    // ------------------------------------------------------------------ guild line shapes

    @Test
    fun parsesRankAndGuildRankWithColorCodes() {
        val raw = "§r§2Guild > §b[MVP§4+§b] Alpha_1 §3[Shrimp]§f: §rme please"
        assertEquals(VanguardGuildChat.GuildLine("Alpha_1", "me please"), VanguardGuildChat.parseGuild(strip(raw)))
    }

    @Test
    fun parsesWithoutGuildRank() {
        assertEquals(VanguardGuildChat.GuildLine("Beta", "me"), VanguardGuildChat.parseGuild("Guild > [MVP+] Beta: me"))
    }

    @Test
    fun parsesUnrankedPlayerWithGuildRank() {
        assertEquals(VanguardGuildChat.GuildLine("Gamma", "me!"), VanguardGuildChat.parseGuild("Guild > Gamma [Krill]: me!"))
    }

    @Test
    fun parsesUnrankedPlayerWithoutGuildRank() {
        assertEquals(VanguardGuildChat.GuildLine("Delta", "me"), VanguardGuildChat.parseGuild("Guild > Delta: me"))
    }

    @Test
    fun parsesGuildRankWithSpace() {
        assertEquals("Epsilon", VanguardGuildChat.parseGuild("Guild > [VIP] Epsilon [Guild Master]: me")?.name)
    }

    @Test
    fun otherChannelsAndNonMessagesAreNotGuildLines() {
        assertNull(VanguardGuildChat.parseGuild("Party > [MVP+] Alpha: me"))
        assertNull(VanguardGuildChat.parseGuild("Officer > [MVP+] Alpha [Officer]: me"))
        assertNull(VanguardGuildChat.parseGuild("From [MVP+] Alpha: me"))
        assertNull(VanguardGuildChat.parseGuild("[MVP+] Alpha: me"))
        assertNull(VanguardGuildChat.parseGuild("[276] [MVP+] Alpha: me"))
        assertNull(VanguardGuildChat.parseGuild("Guild > Alpha left."))
        assertNull(VanguardGuildChat.parseGuild("Guild > Alpha joined."))
        assertNull(VanguardGuildChat.parseGuild("Guild > They have gifted 27 ranks so far!"))
    }

    @Test
    fun quotedGuildLineInsideOtherChatIsNotAGuildLine() {
        assertNull(VanguardGuildChat.parseGuild("[MVP+] Alpha: Guild > Beta [Member]: me"))
        assertEquals("Alpha", VanguardGuildChat.parseGuild("Guild > [MVP+] Alpha [Member]: Guild > Beta [Member]: me")?.name)
    }

    // ------------------------------------------------------------------ the "me" rule

    @Test
    fun meMatchesAsAWholeWord() {
        for (text in listOf("me", "Me", "ME", "me!", "Me!", "\"me\"", "me pls", "yes me", "me, me", "(me)", "me.", "can I come, me?", "me\u2026")) {
            assertTrue(VanguardGuildChat.wantsIn(text), text)
        }
    }

    @Test
    fun meDoesNotMatchInsideOtherWords() {
        for (text in listOf("home", "some", "meme", "meet me_", "welcome", "mean", "amen", "gamer", "message", "me2", "x_me", "mé", "")) {
            assertFalse(VanguardGuildChat.wantsIn(text), text)
        }
    }

    // ------------------------------------------------------------------ collapse

    @Test
    fun recognizesTheRecordedCollapseLine() {
        val raw = "§r§7§oThe mineshaft entrance has caved in... it doesn't look like anyone else will be able to get in here."
        assertTrue(VanguardGuildChat.isCollapse(strip(raw)))
        assertFalse(VanguardGuildChat.isCollapse(strip("§r§aSending to Mineshaft...")))
        assertFalse(VanguardGuildChat.isCollapse(strip("§r§b§lBYE! §r§aYou got a §r§b+1,560 §r§bGlacite Powder §r§abonus for leaving a Mineshaft before you died!")))
    }

    // ------------------------------------------------------------------ the window

    @Test
    fun twoPlayersAreInvitedInOrderOnePerPace() {
        val w = openWindow()
        assertTrue(w.onGuildLine(guild("Alpha", "me"), "Self"))
        assertTrue(w.onGuildLine(guild("Beta", "Me!", rank = ""), "Self"))
        assertEquals("Alpha", w.nextInvite(10_000))
        assertNull(w.nextInvite(10_000), "same instant")
        assertNull(w.nextInvite(11_499), "pace not up")
        assertEquals("Beta", w.nextInvite(11_500))
        assertNull(w.nextInvite(20_000))
        assertEquals(listOf("Alpha", "Beta"), w.invitesSent)
    }

    @Test
    fun aNewVisitStartsWithNothingOfTheLastOne() {
        val w = openWindow()
        w.onGuildLine(guild("Alpha", "me"), "Self")
        w.nextInvite(0)
        w.close("the shaft collapsed")
        w.reset()
        assertFalse(w.open)
        assertNull(w.closeReason)
        assertNull(w.lastIgnored)
        assertTrue(w.invited.isEmpty())
        assertTrue(w.invitesSent.isEmpty())
    }

    @Test
    fun eachPlayerOnlyOncePerShaftWhateverTheCase() {
        val w = openWindow()
        assertTrue(w.onGuildLine(guild("Alpha", "me"), "Self"))
        assertFalse(w.onGuildLine(guild("Alpha", "me again"), "Self"))
        assertFalse(w.onGuildLine(guild("ALPHA", "me"), "Self"))
        assertEquals(1, w.invited.size)
        w.nextInvite(0)
        assertFalse(w.onGuildLine(guild("Alpha", "me"), "Self"), "also after the command went out")
        assertNull(w.nextInvite(100_000))
    }

    @Test
    fun aNewShaftForgetsThePreviousPlayers() {
        val w = openWindow()
        w.onGuildLine(guild("Alpha", "me"), "Self")
        w.close("left the shaft")
        w.begin()
        assertTrue(w.onGuildLine(guild("Alpha", "me"), "Self"))
    }

    @Test
    fun selfIsNeverInvited() {
        val w = openWindow()
        assertFalse(w.onGuildLine(guild("Self", "me"), "self"))
        assertEquals("own message", w.lastIgnored)
        assertNull(w.nextInvite(0))
    }

    @Test
    fun ownAnnouncementAndAnotherCallAreNotAnswers() {
        val w = openWindow()
        assertFalse(w.onGuildLine(guild("Self", VanguardGuildChat.ANNOUNCE), "Self"))
        assertFalse(w.onGuildLine(guild("Zeta", "[Soul] " + VanguardGuildChat.ANNOUNCE), "Self"))
        assertTrue(w.lastIgnored!!.startsWith("another call"))
        assertNull(w.nextInvite(0))
    }

    @Test
    fun wrongChannelOrNoMeIsIgnored() {
        val w = openWindow()
        assertFalse(w.onGuildLine("Party > [MVP+] Alpha: me", "Self"))
        assertFalse(w.onGuildLine("From [MVP+] Alpha: me", "Self"))
        assertFalse(w.onGuildLine(guild("Alpha", "home soon"), "Self"))
        assertTrue(w.lastIgnored!!.startsWith("no \"me\""))
        assertTrue(w.invited.isEmpty())
    }

    @Test
    fun nothingIsInvitedBeforeTheCallOrAfterTheWindowCloses() {
        val w = VanguardGuildWindow()
        assertFalse(w.onGuildLine(guild("Alpha", "me"), "Self"), "no announcement yet")
        w.begin()
        assertTrue(w.onGuildLine(guild("Alpha", "me"), "Self"))
        assertTrue(w.onGuildLine(guild("Beta", "me"), "Self"))
        assertEquals("Alpha", w.nextInvite(0))
        w.close("the shaft collapsed")
        assertFalse(w.open)
        assertNull(w.nextInvite(10_000), "the queued Beta is dropped")
        assertFalse(w.onGuildLine(guild("Gamma", "me"), "Self"), "a third after the collapse")
        assertEquals("window closed (the shaft collapsed)", w.lastIgnored)
        assertEquals(listOf("Alpha"), w.invitesSent)
        assertTrue(w.queued.isEmpty())
    }

    @Test
    fun capBoundsAFloodOfMe() {
        val w = openWindow(max = 3)
        for (i in 1..5) w.onGuildLine(guild("P$i", "me"), "Self")
        assertEquals(listOf("P1", "P2", "P3"), w.invited)
        assertTrue(w.lastIgnored!!.startsWith("invite cap 3"))
    }

    // ------------------------------------------------------------------ announcing

    @Test
    fun announcesInAShaftYouFoundWithAVanguard() {
        val v = VanguardGuildChat.verdict(VisitSource.DISCOVERED, collapsed = false, skipInParty = true, partyAtEntry = false, vanguardCorpses = 1)
        assertTrue(v.announce)
    }

    @Test
    fun waitsForTheVanguardRowWithoutDeciding() {
        val v = VanguardGuildChat.verdict(VisitSource.DISCOVERED, collapsed = false, skipInParty = true, partyAtEntry = false, vanguardCorpses = 0)
        assertFalse(v.announce)
        assertFalse(v.final)
    }

    @Test
    fun aWarpedInOrUnclassifiedShaftIsNeverAnnounced() {
        for (source in listOf(VisitSource.WARPED, VisitSource.UNKNOWN)) {
            val v = VanguardGuildChat.verdict(source, collapsed = false, skipInParty = false, partyAtEntry = false, vanguardCorpses = 1)
            assertFalse(v.announce, source)
            assertTrue(v.final, source)
        }
    }

    @Test
    fun aCollapsedShaftIsNeverAnnounced() {
        val v = VanguardGuildChat.verdict(VisitSource.DISCOVERED, collapsed = true, skipInParty = false, partyAtEntry = false, vanguardCorpses = 1)
        assertFalse(v.announce)
        assertTrue(v.final)
    }

    @Test
    fun inAPartyAtEntryDependsOnTheSkipSetting() {
        val skip = VanguardGuildChat.verdict(VisitSource.DISCOVERED, collapsed = false, skipInParty = true, partyAtEntry = true, vanguardCorpses = 1)
        assertFalse(skip.announce)
        assertTrue(skip.final)
        assertNotNull(skip.why)
        val allowed = VanguardGuildChat.verdict(VisitSource.DISCOVERED, collapsed = false, skipInParty = false, partyAtEntry = true, vanguardCorpses = 1)
        assertTrue(allowed.announce)
        val alone = VanguardGuildChat.verdict(VisitSource.DISCOVERED, collapsed = false, skipInParty = true, partyAtEntry = false, vanguardCorpses = 1)
        assertTrue(alone.announce)
    }

    @Test
    fun theCallTextIsTheOwnersWording() {
        assertEquals("Vanguard found! If you want to gamble say \"me\" for auto party and then use \"!warp\" when ready", VanguardGuildChat.ANNOUNCE)
    }
}
