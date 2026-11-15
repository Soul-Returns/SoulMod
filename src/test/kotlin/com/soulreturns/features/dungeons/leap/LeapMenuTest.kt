package com.soulreturns.features.dungeons.leap

import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.DungeonClass.ARCHER
import com.soulreturns.features.dungeons.DungeonClass.BERSERK
import com.soulreturns.features.dungeons.DungeonClass.HEALER
import com.soulreturns.features.dungeons.DungeonClass.MAGE
import com.soulreturns.features.dungeons.DungeonClass.TANK
import com.soulreturns.features.dungeons.DungeonClass.UNKNOWN
import com.soulreturns.features.dungeons.leap.PartyCallouts.Callout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Leap menu (spec `mocks/leap-menu/SPEC.md`, research `docs/research/spirit-leap-2026-11-13.md`). Names, classes,
 * slots and chat lines are from the user's 8 M7 party runs (recording `2026-11-12_23-48-02_m7-runs`).
 */
class LeapMenuTest {
    private data class Mate(val name: String, val cls: DungeonClass, val slot: Int)

    private fun sort(mates: List<Mate>, mode: String = LeapSort.ODIN) = LeapSort.sort(mates, mode, { it.cls }, { it.name }, { it.slot }).map { it?.name }

    // Tab order of run 1 without the user (SoulReturns, Archer); slots = where Hypixel put each head in run 1.
    private val run1 = listOf(Mate("go9o", HEALER, 14), Mate("Maaxnn", TANK, 15), Mate("PuppyGirlSunny", BERSERK, 12), Mate("Waccoons", MAGE, 11))

    // ---------------------------------------------------------------- sorting (SPEC §2)

    @Test
    fun odinSortingPutsEachClassInItsCorner() {
        assertEquals(listOf("Waccoons", "PuppyGirlSunny", "go9o", "Maaxnn"), sort(run1))
    }

    @Test
    fun odinSortingAsTank() {
        // The user plays Tank, Maaxnn Archer: Mage gets the bottom right.
        val asTank = listOf(Mate("go9o", HEALER, 14), Mate("Maaxnn", ARCHER, 15), Mate("PuppyGirlSunny", BERSERK, 12), Mate("Waccoons", MAGE, 11))
        assertEquals(listOf("Maaxnn", "PuppyGirlSunny", "go9o", "Waccoons"), sort(asTank))
    }

    @Test
    fun fewerTeammatesLeaveCornersEmpty() {
        assertEquals(listOf(null, "PuppyGirlSunny", "go9o", "Waccoons"), sort(run1.filter { it.name != "Maaxnn" }))
        assertEquals(listOf(null, null, null, "Waccoons"), sort(listOf(Mate("Waccoons", MAGE, 13))))
    }

    @Test
    fun duplicateAndUnknownClassesFillFreeCornersInOrder() {
        val mates = listOf(Mate("a", MAGE, 11), Mate("b", MAGE, 12), Mate("c", UNKNOWN, 14), Mate("d", BERSERK, 15))
        // d takes TR, a takes BR, b and c (no home) fill TL then BL.
        assertEquals(listOf("b", "d", "c", "a"), sort(mates))
    }

    @Test
    fun listModes() {
        assertEquals(listOf("go9o", "Maaxnn", "PuppyGirlSunny", "Waccoons"), sort(run1, LeapSort.NAME))
        assertEquals(listOf("PuppyGirlSunny", "go9o", "Waccoons", "Maaxnn"), sort(run1, LeapSort.CLASS))
        assertEquals(listOf("Waccoons", "PuppyGirlSunny", "go9o", "Maaxnn"), sort(run1, LeapSort.MENU))
    }

    // ---------------------------------------------------------------- click mapping (SPEC §5)

    @Test
    fun aPressPicksTheQuadrantsHeadSlot() {
        val w = 1280.0
        val h = 720.0
        fun slotAt(x: Double, y: Double, mates: List<Mate>) = LeapSort.sort(mates, LeapSort.ODIN, { it.cls }, { it.name }, { it.slot })[LeapSort.quadrant(x, y, w, h)]?.slot
        // Run 1: heads 11 Waccoons, 12 PuppyGirlSunny, 14 go9o, 15 Maaxnn.
        assertEquals(11, slotAt(10.0, 10.0, run1))
        assertEquals(12, slotAt(1270.0, 10.0, run1))
        assertEquals(14, slotAt(10.0, 710.0, run1))
        assertEquals(15, slotAt(640.0, 360.0, run1))
        // Run 4 put the heads elsewhere (11 Maaxnn, 12 Waccoons, 14 go9o, 15 PuppyGirlSunny): same corners, other slots.
        val run4 = listOf(Mate("go9o", HEALER, 14), Mate("Maaxnn", TANK, 11), Mate("PuppyGirlSunny", BERSERK, 15), Mate("Waccoons", MAGE, 12))
        assertEquals(listOf(12, 15, 14, 11), listOf(slotAt(1.0, 1.0, run4), slotAt(700.0, 1.0, run4), slotAt(1.0, 400.0, run4), slotAt(700.0, 400.0, run4)))
        // An empty corner picks nothing.
        assertNull(slotAt(1.0, 1.0, run1.filter { it.name != "Waccoons" && it.name != "Maaxnn" }.map { it }))
    }

    // ---------------------------------------------------------------- menu recognition (research §1)

    private val pane = LeapMenuReader.Item("black_stained_glass_pane", "", null)
    private fun head(name: String, dead: Boolean = false) = LeapMenuReader.Item("player_head", name, if (dead) LeapMenuReader.DEAD else LeapMenuReader.ALIVE)

    private fun menu(vararg heads: Pair<Int, LeapMenuReader.Item?>): List<LeapMenuReader.Item?> {
        val slots = MutableList<LeapMenuReader.Item?>(36) { pane }
        for ((i, it) in heads) slots[i] = it
        return slots
    }

    @Test
    fun readsTheRecordedMenu() {
        val r = LeapMenuReader.read(menu(11 to head("Waccoons"), 12 to head("PuppyGirlSunny"), 14 to head("go9o"), 15 to head("Maaxnn")))
        assertIs<LeapMenuReader.Result.Ok>(r)
        assertEquals(listOf(11 to "Waccoons", 12 to "PuppyGirlSunny", 14 to "go9o", 15 to "Maaxnn"), r.heads.map { it.slot to it.name })
        assertTrue(r.heads.none { it.dead })
        assertTrue(LeapMenuReader.isLeapMenu("Spirit Leap", 36))
    }

    @Test
    fun deadLoreMarksTheHead() {
        // Screen #19 at 00:31:44 (run 5): Waccoons and Maaxnn dead.
        val r = LeapMenuReader.read(menu(11 to head("Waccoons", true), 12 to head("go9o"), 14 to head("Maaxnn", true), 15 to head("PuppyGirlSunny")))
        assertIs<LeapMenuReader.Result.Ok>(r)
        assertEquals(listOf("Waccoons", "Maaxnn"), r.heads.filter { it.dead }.map { it.name })
    }

    @Test
    fun anOfflineTeammateIsSkipped() {
        // M7 2026-11-13 +32:24 and +32:31 (run 6, P1, the Tank disconnected): slot 15 `§7Unknown Player`, lore
        // `This player is offline!`, no profile. Both menus fell back to vanilla and were closed without a leap.
        val r = LeapMenuReader.read(
            menu(11 to head("go9o"), 12 to head("PuppyGirlSunny"), 14 to head("Waccoons"), 15 to LeapMenuReader.Item("player_head", "Unknown Player", LeapMenuReader.OFFLINE)),
        )
        assertIs<LeapMenuReader.Result.Ok>(r)
        assertEquals(listOf(11 to "go9o", 12 to "PuppyGirlSunny", 14 to "Waccoons"), r.heads.map { it.slot to it.name })
    }

    @Test
    fun unknownMenusFallBackToVanilla() {
        assertTrue(!LeapMenuReader.isLeapMenu("Spirit Leap", 54))
        assertTrue(!LeapMenuReader.isLeapMenu("Ender Chest", 36))
        assertIs<LeapMenuReader.Result.Pending>(LeapMenuReader.read(menu(11 to head("go9o"), 20 to null)))
        assertIs<LeapMenuReader.Result.Fail>(LeapMenuReader.read(menu(11 to head("go9o"), 31 to LeapMenuReader.Item("barrier", "Close", null))))
        assertIs<LeapMenuReader.Result.Fail>(LeapMenuReader.read(menu(11 to LeapMenuReader.Item("player_head", "go9o", "Offline!"))))
        assertIs<LeapMenuReader.Result.Fail>(LeapMenuReader.read(menu(15 to LeapMenuReader.Item("player_head", "Unknown Player", LeapMenuReader.OFFLINE))), "nobody to leap to")
        assertIs<LeapMenuReader.Result.Fail>(LeapMenuReader.read(menu()))
    }

    // ---------------------------------------------------------------- one menu's state machine (LeapGate)

    private fun pending(vararg h: LeapMenuReader.Head) = LeapMenuReader.Result.Pending(h.toList())

    private val run1Heads = listOf(
        LeapMenuReader.Head(11, "Waccoons", false),
        LeapMenuReader.Head(12, "PuppyGirlSunny", false),
        LeapMenuReader.Head(14, "go9o", false),
        LeapMenuReader.Head(15, "Maaxnn", true),
    )

    @Test
    fun eachTileIsClickableFromTheMomentItsHeadArrives() {
        val g = LeapGate(1000)
        g.update(pending(), 1005)
        assertEquals(LeapGate.PENDING, g.state)
        // A press before the head is there sends nothing, and nothing is kept for later.
        assertEquals(LeapGate.Press.NotYet, g.press("go9o", true))
        g.update(pending(run1Heads[2]), 1100)
        assertEquals(LeapGate.PENDING, g.state)
        assertEquals(LeapGate.Press.Send(14), g.press("go9o", true))
        assertEquals(LeapGate.Press.NotYet, g.press("Waccoons", true))
        assertEquals(100L, g.readyAfter("GO9O"))
        assertNull(g.readyAfter("Waccoons"))
        g.update(LeapMenuReader.Result.Ok(run1Heads), 1150)
        assertEquals(LeapGate.ACTIVE, g.state)
        assertEquals(150L, g.readyAfter("Waccoons"))
        assertEquals(100L, g.readyAfter("go9o"))
        assertEquals("go9o", g.arrivals().first().first)
        assertEquals(LeapGate.Press.Send(11), g.press("waccoons", true))
        assertEquals(LeapGate.Press.NotInMenu, g.press("SoulReturns", true))
        assertEquals(LeapGate.Press.Dead, g.press("Maaxnn", true))
        assertEquals(LeapGate.Press.Send(15), g.press("Maaxnn", false))
    }

    @Test
    fun oneLeapThenNothing() {
        val g = LeapGate(0)
        g.update(LeapMenuReader.Result.Ok(run1Heads), 16)
        g.sent()
        assertEquals(LeapGate.SENT, g.state)
        run1Heads.forEach { assertEquals(LeapGate.Press.Done, g.press(it.name, false)) }
        // Vanilla's prediction empties the clicked slot afterwards: not judged, still SENT.
        g.update(LeapMenuReader.Result.Fail("slot 11 empty"), 40)
        assertEquals(LeapGate.SENT, g.state)
    }

    @Test
    fun unknownOrSlowMenusGoBackToVanilla() {
        val slow = LeapGate(0)
        slow.update(pending(run1Heads[0]), 1500)
        assertEquals(LeapGate.PENDING, slow.state)
        slow.update(pending(run1Heads[0]), LeapGate.LOAD_TIMEOUT_MS + 1)
        assertEquals(LeapGate.FAILED, slow.state)
        assertEquals(LeapGate.Press.Done, slow.press("Waccoons", true))

        val odd = LeapGate(0)
        odd.update(pending(run1Heads[0]), 10)
        odd.update(LeapMenuReader.Result.Fail("slot 31 holds barrier"), 20)
        assertEquals(LeapGate.FAILED, odd.state)
        assertEquals("slot 31 holds barrier", odd.why)
        assertEquals(LeapGate.Press.Done, odd.press("Waccoons", true))
    }

    @Test
    fun partialMenusKeepTheHeadsThatArrived() {
        val r = LeapMenuReader.read(menu(11 to head("Waccoons"), 12 to null, 14 to head("go9o", true), 15 to null))
        assertIs<LeapMenuReader.Result.Pending>(r)
        assertEquals(listOf(11 to "Waccoons", 14 to "go9o"), r.heads.map { it.slot to it.name })
        assertTrue(r.heads[1].dead)
    }

    // ---------------------------------------------------------------- party chat (research §3.2)

    @Test
    fun parsesRealCallouts() {
        mapOf(
            "at ss!" to "SS", "at ee3!" to "ee3", "at levers dev!" to "Lights", "at arrows dev!" to "Arrows", "at core!" to "Core",
            "at mid!" to "Mid", "at p5!" to "P5", "At ee2!" to "ee2", "At core!" to "Core", "i4" to "i4", "im at pre 4" to "Pre 4",
        ).forEach { (body, label) -> assertEquals(Callout.Spot(label), PartyCallouts.parse(body), body) }
    }

    @Test
    fun parsesLeapsAndCoordinates() {
        assertEquals(Callout.Leap("go9o"), PartyCallouts.parse("Leaped to go9o!"))
        assertEquals(Callout.Leap("Waccoons"), PartyCallouts.parse("Boo! Leaped to Waccoons"))
        assertEquals(Callout.Leap("Maaxnn"), PartyCallouts.parse("[Skyblocker] Leaped to Maaxnn!"))
        assertEquals(Callout.Leap("SoulReturns"), PartyCallouts.parse("[Skyblocker] yeeted to SoulReturns!"))
        assertEquals(Callout.Coords(92.0, 122.0, 101.0), PartyCallouts.parse("x: 92, y: 122, z: 101"))
    }

    @Test
    fun ignoresEverythingElse() {
        listOf(
            "at least they said", "ss bugged", "Melody 33%", "Spirit Procced! (1/3)", "[Skyblocker] Crypts: 2/5", "Bat Killed!",
            "Mimic dead!", "dont leap", "Gained strength from Ragnarock: 939", "[Skyblocker] 300 Score Reached!", "gg",
        ).forEach { assertNull(PartyCallouts.parse(it), it) }
    }

    @Test
    fun partyLineFormat() {
        assertEquals("go9o" to "at ss!", PartyCallouts.partyLine("Party > [MVP+] go9o: at ss!"))
        assertEquals("SoulReturns" to "x: 92, y: 122, z: 101", PartyCallouts.partyLine("Party > [MVP++] SoulReturns: x: 92, y: 122, z: 101"))
        assertEquals("MageBot" to "At ee2!", PartyCallouts.partyLine("Party > MageBot: At ee2!"))
        assertNull(PartyCallouts.partyLine("[NPC] Mort: Good luck."))
    }

    @Test
    fun spotsFromRecordedPositions() {
        mapOf(
            Triple(108.5, 121.0, 102.7) to "SS", Triple(59.8, 132.0, 139.5) to "Lights", Triple(2.8, 116.8, 108.0) to "ee3",
            Triple(4.0, 121.0, 83.3) to "Arrows", Triple(57.7, 68.3, 71.9) to "Mid", Triple(56.2, 28.0, 75.6) to "P5",
            Triple(87.0, 166.0, 89.0) to "Acacia", Triple(100.0, 169.0, 64.0) to "Purple", Triple(92.0, 122.0, 101.0) to "S1",
            Triple(54.0, 115.0, 51.0) to "Core",
        ).forEach { (p, label) -> assertEquals(label, PartyCallouts.spotAt(p.first, p.second, p.third), p.toString()) }
        assertNull(PartyCallouts.spotAt(-150.0, 69.0, -100.0))
    }

    @Test
    fun phaseLines() {
        listOf(
            "[BOSS] Storm: Pathetic Maxor, just like expected.",
            "go9o completed a device! (7/7)", "PuppyGirlSunny activated a terminal! (8/8)", "go9o activated a lever! (7/7)",
            "The Core entrance is opening!", "[BOSS] Necron: You went further than any human before, congratulations.",
        ).forEach { assertTrue(PartyCallouts.isPhaseLine(it), it) }
        listOf(
            "[BOSS] Storm: I should have known that I stood no chance.", "[BOSS] Goldor: Who dares trespass into my domain?",
            "[BOSS] Wither King: You... again?", "go9o activated a terminal! (3/7)",
        ).forEach { assertTrue(!PartyCallouts.isPhaseLine(it), it) }
    }

    @Test
    fun theHealersSsCalloutSurvivesStormsEndLine() {
        // M7 2026-11-13 run 1 (ms of the recording): `at ss!` 4.6 s before Storm's end line, the leap menu 0.5 s after it,
        // the leap to go9o at SS. The healer was out of range: the end line used to clear the call-out and leave `?`.
        val lines = listOf(
            36_200L to "Party > [MVP+] go9o: at ss!",
            40_400L to "Party > [MVP+] Maaxnn: Boo! Leaped to go9o",
            40_900L to "[BOSS] Storm: I should have known that I stood no chance.",
        )
        val l = TeammateLocations()
        for ((t, s) in lines) {
            val party = PartyCallouts.partyLine(s)
            if (party != null) l.onPartyLine(party.first, party.second, t) { l.locate(it, null, t).label.takeIf { x -> x != "?" } } else if (PartyCallouts.isPhaseLine(s)) l.onPhaseLine()
        }
        assertEquals("SS", l.locate("go9o", null, 41_400).label)
        assertEquals("SS", l.locate("Maaxnn", null, 41_400).label)
    }

    // ---------------------------------------------------------------- where teammates are (SPEC §6)

    @Test
    fun seenWinsThenNewerOfLastSeenAndReportThenUnknown() {
        val l = TeammateLocations()
        assertEquals(TeammateLocations.UNKNOWN, l.locate("go9o", null, 0))
        assertEquals(TeammateLocations.Location(TeammateLocations.Source.SEEN, "SS"), l.locate("go9o", "SS", 1_000))
        // Out of range: last seen, with its age.
        assertEquals(TeammateLocations.Location(TeammateLocations.Source.LAST_SEEN, "SS", 4_000), l.locate("go9o", null, 5_000))
        // A newer party message wins over the older last-seen place.
        l.onPartyLine("go9o", "at levers dev!", 8_000) { null }
        assertEquals(TeammateLocations.Location(TeammateLocations.Source.CHAT, "Lights", 2_000), l.locate("go9o", null, 10_000))
        // Seen again: seen wins, and that becomes the newest last-seen place.
        l.locate("go9o", "ee2", 12_000)
        assertEquals(TeammateLocations.Location(TeammateLocations.Source.LAST_SEEN, "ee2", 1_000), l.locate("go9o", null, 13_000))
    }

    @Test
    fun ageing() {
        val l = TeammateLocations()
        l.onPartyLine("go9o", "at ee3!", 0) { null }
        assertTrue(!l.locate("go9o", null, 20_000).stale)
        assertTrue(l.locate("go9o", null, 20_001).stale)
        assertEquals("ee3", l.locate("go9o", null, 60_000).label)
        assertEquals(TeammateLocations.UNKNOWN, l.locate("go9o", null, 60_001))
    }

    @Test
    fun aDistanceIsNotRememberedAsLastSeen() {
        val l = TeammateLocations()
        l.locate("go9o", "18m", 0, remember = false)
        assertEquals(TeammateLocations.UNKNOWN, l.locate("go9o", null, 1_000))
    }

    @Test
    fun leapLinesTakeTheTargetsPlace() {
        val l = TeammateLocations()
        l.onPartyLine("Maaxnn", "Boo! Leaped to go9o", 0) { if (it == "go9o") "SS" else null }
        assertEquals("SS", l.locate("Maaxnn", null, 1_000).label)
        l.onPartyLine("Maaxnn", "Boo! Leaped to Waccoons", 2_000) { null }
        assertEquals("with Waccoons", l.locate("Maaxnn", null, 3_000).label)
    }

    @Test
    fun deathRevivesAndPhaseLinesDropEverything() {
        val l = TeammateLocations()
        l.onPartyLine("go9o", "at ss!", 0) { null }
        l.locate("Waccoons", "Core", 0)
        l.forget("go9o")
        assertEquals(TeammateLocations.UNKNOWN, l.locate("go9o", null, 1_000))
        assertEquals("Core", l.locate("Waccoons", null, 1_000).label)
        l.onPhaseLine()
        assertEquals(TeammateLocations.UNKNOWN, l.locate("Waccoons", null, 2_000))
    }

    @Test
    fun run1ChatInOrder() {
        // Run 1 (+mm:ss.s of the recording, as ms): the healer's call-outs between the boss lines.
        val lines = listOf(
            232_700L to "[BOSS] Storm: I should have known that I stood no chance.",
            234_100L to "Party > [MVP+] go9o: at ss!",
            235_200L to "[BOSS] Goldor: Who dares trespass into my domain?",
            247_900L to "go9o completed a device! (7/7)",
            268_200L to "PuppyGirlSunny activated a terminal! (8/8)",
            268_400L to "Party > [MVP+] go9o: at ee3!",
            297_000L to "go9o activated a lever! (7/7)",
            305_100L to "[BOSS] Necron: You went further than any human before, congratulations.",
            308_500L to "Party > [MVP+] go9o: at mid!",
            319_200L to "Party > [MVP+] go9o: at p5!",
            336_900L to "[BOSS] Wither King: You... again?",
        )
        val l = TeammateLocations()
        var next = 0
        fun feed(until: Long) {
            while (next < lines.size && lines[next].first <= until) {
                val (t, s) = lines[next++]
                val party = PartyCallouts.partyLine(s)
                if (party != null) l.onPartyLine(party.first, party.second, t) { null } else if (PartyCallouts.isPhaseLine(s)) l.onPhaseLine()
            }
        }
        fun at(t: Long) = l.locate("go9o", null, t).label
        feed(236_000); assertEquals("SS", at(236_000)) // still SS after Who dares
        feed(248_000); assertEquals("?", at(248_000)) // S1 done: he leaves SS
        feed(270_000); assertEquals("ee3", at(270_000))
        feed(306_000); assertEquals("?", at(306_000)) // P4
        feed(337_000); assertEquals("P5", at(337_000)) // `at p5!` survives the Wither King's line
    }
}
