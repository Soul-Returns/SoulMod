package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.features.scoreboard.lines.ActiveEffects
import com.soulreturns.features.scoreboard.lines.GodPotionLine
import com.soulreturns.features.scoreboard.lines.MayorData
import com.soulreturns.features.scoreboard.lines.MayorLine
import com.soulreturns.features.scoreboard.lines.NewLinesLine
import com.soulreturns.features.scoreboard.lines.PartyInfo
import com.soulreturns.features.scoreboard.lines.PartyLine
import com.soulreturns.features.scoreboard.lines.PetInfo
import com.soulreturns.features.scoreboard.lines.PetLine
import com.soulreturns.features.scoreboard.lines.events.EventDefs
import com.soulreturns.features.scoreboard.lines.events.EventsLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Buffs & world (cookie, God Potion, pet, mayor, party) and the Events group (the Events line and the 28
 * events). Real captures: `scoreboard/events/dungeon-2026-11-12.json` (F7 sidebars, the tab footer). Every
 * other event is tested on SkyHanni's reference test lines, not on a Hypixel capture (see [EventDefs]).
 */
class ScoreboardEventsTest {
    private val file = Json.parseToJsonElement(javaClass.getResource("/scoreboard/events/dungeon-2026-11-12.json")!!.readText()).jsonObject
    private val look = ScoreboardLook()

    private fun real(name: String): ScoreboardSnapshot {
        val o = file["sidebars"]!!.jsonArray.map { it.jsonObject }.first { it["name"]!!.jsonPrimitive.content == name }
        return ScoreboardSnapshot(
            title = SourceLine.of(o["title"]!!.jsonPrimitive.content),
            sidebar = o["lines"]!!.jsonArray.map { SourceLine.of(it.jsonPrimitive.content) },
            tabFooter = footer(),
            mode = "dungeon",
            area = "Dungeon",
            subLocation = "The Catacombs (F7)",
        )
    }

    private fun footer(): List<SourceLine> = file["footer"]!!.jsonPrimitive.content.split('\n').map(SourceLine::of)

    /** A snapshot of [lines] (as Hypixel colors them) with a footer line at the end like Hypixel's. */
    private fun snap(
        lines: List<String>,
        area: String? = null,
        mode: String? = null,
        tab: List<String> = emptyList(),
        tabFooter: List<String> = emptyList(),
    ): ScoreboardSnapshot {
        val base = ScoreboardSnapshot.ofSidebar("§e§lSKYBLOCK", lines + listOf("", "§ewww.hypixel.net"), tab)
        return ScoreboardSnapshot(
            title = base.title, sidebar = base.sidebar, tabWidgets = base.tabWidgets, tabFooter = tabFooter.map(SourceLine::of), area = area, mode = mode,
        )
    }

    private fun entry(id: String, options: Map<String, Any> = emptyMap()): ScoreboardEntry =
        ScoreboardEntry(
            id,
            options = JsonObject(
                options.mapValues { (_, v) ->
                    when (v) {
                        is Boolean -> JsonPrimitive(v)
                        is Int -> JsonPrimitive(v)
                        is List<*> -> JsonArray(v.map { JsonPrimitive(it as String) })
                        else -> JsonPrimitive(v.toString())
                    }
                },
            ),
        )

    private fun build(e: ScoreboardEntry, s: ScoreboardSnapshot, now: Long = 0L) =
        ScoreboardBuilder.build(listOf(e), s, look, now).map { LegacyText.strip(it.text).trim() }

    private fun build(id: String, s: ScoreboardSnapshot) = build(entry(id), s)

    @AfterTest
    fun reset() {
        MayorData.election = null
        MayorData.autoFetch = true
        PartyLine.source = { null }
        PetLine.source = { null }
    }

    // ------------------------------------------------------------------ the catalogue

    @Test
    fun theTwentyEightEventsAreAllInTheCatalogue() {
        assertEquals(28, EventDefs.all.size)
        assertEquals(28, EventDefs.all.map { it.id }.toSet().size)
        for (d in EventDefs.all) {
            val t = ScoreboardCatalog.type(d.id)
            assertNotNull(t, d.id)
            assertEquals(ScoreboardCatalog.EVENTS, t.group)
            assertEquals(d.name, t.name)
        }
        assertEquals(ScoreboardCatalog.EVENTS, ScoreboardCatalog.type("events")!!.group)
        for (id in listOf("cookie", "godpot", "pet", "mayor", "party")) assertEquals(ScoreboardCatalog.BUFFS, ScoreboardCatalog.type(id)!!.group, id)
    }

    // ------------------------------------------------------------------ real dungeon sidebars

    @Test
    fun realDungeonRunTakesKeysTimeClearedAndSolo() {
        val s = real("f7-keys-cleared")
        assertEquals(listOf("Keys: ■ ✗ ■ 0x", "Time Elapsed: 01m 46s", "Cleared: 33% (62)", "Solo"), build("evDungeons", s))
        assertEquals(listOf(6, 7, 8, 10), ScoreboardCatalog.type("evDungeons")!!.claims(s).sorted())
        assertEquals(listOf("Time Elapsed: 01m 58s", "Cleared: 36% (62)", "Solo"), build("evDungeons", real("f7-no-keys")))
    }

    @Test
    fun realStartingAndAutoClosingTimersAreDungeonLines() {
        assertEquals(listOf("Auto-closing in: 1:56"), build("evDungeons", real("f7-auto-closing")))
        assertEquals(listOf("Starting in: 0:05"), build("evDungeons", real("f7-starting-in")))
    }

    @Test
    fun realTeammateRowGetsAPartyHeading() {
        val s = real("f7-auto-closing")
        assertEquals(listOf(6), PartyLine.claims(s).toList())
        assertEquals(listOf("Party (1)", "[M] SoulReturns [Lv48]"), build("party", s))
    }

    @Test
    fun nothingTheseLinesOwnIsLeftOverInARealDungeon() {
        for (name in listOf("f7-keys-cleared", "f7-no-keys", "f7-auto-closing", "f7-starting-in")) {
            val s = real(name)
            val left = NewLinesLine.unknownIndexes(s).map { s.sidebar[it].text.trim() }
            assertTrue(left.none { it.startsWith("Time Elapsed") || it.startsWith("Cleared") || it.startsWith("Keys") || it.startsWith("[M]") || it == "Solo" || it.startsWith("Auto-closing") || it.startsWith("Starting in") }, "$name: $left")
        }
    }

    @Test
    fun theEventsLineInARealDungeonShowsTheRun() {
        assertEquals(listOf("Keys: ■ ✗ ■ 0x", "Time Elapsed: 01m 46s", "Cleared: 33% (62)", "Solo"), build("events", real("f7-keys-cleared")))
    }

    // ------------------------------------------------------------------ the 28 events on reference lines

    private class Case(val id: String, val lines: List<String>, val expect: List<String>, val area: String? = null, val mode: String? = null, val tab: List<String> = emptyList(), val footer: List<String> = emptyList())

    private val cases =
        listOf(
            Case(
                "evDungeons",
                listOf("Starting in: §a0:05", "Keys: §c■ §a✓ §8■ §a0x", "Time Elapsed: §a08m 12s", "Cleared: §c64% §8(312)", "§3§lSolo", "§8- §6Flame Dragon§a 360M§c❤", "§cNo Alive Dragons", "- §cChaos§a 1"),
                listOf("Starting in: 0:05", "Keys: ■ ✓ ■ 0x", "Time Elapsed: 08m 12s", "Cleared: 64% (312)", "Solo", "- Flame Dragon 360M❤", "No Alive Dragons", "- Chaos 1"),
                mode = "dungeon",
            ),
            Case(
                "evKuudra",
                listOf("Auto-closing in: §c1:58", "Time Elapsed: §a48s", "§f§lWave: §c§l2 §8- §a0:09", "§fTokens: §565", "Submerges In: §e01m 00s", "Instance Shutdown In: §a01m 59s"),
                listOf("Auto-closing in: 1:58", "Time Elapsed: 48s", "Wave: 2 - 0:09", "Tokens: 65", "Submerges In: 01m 00s", "Instance Shutdown In: 01m 59s"),
                mode = "kuudra",
            ),
            Case(
                "evMining",
                listOf("Event: §6§lRAFFLE", "Find tickets on the", "ground and bring them", "to the raffle box", "Tickets: §a8 §7(17.4%)", "Pool: §646", "Nearby Players: §a3"),
                listOf("Event: RAFFLE", "Tickets: 8 (17.4%)", "Pool: 46", "Nearby Players: 3"),
                area = "Dwarven Mines",
            ),
            Case(
                "evMining",
                listOf("Event: §c§lGOBLIN RAID", "Zone: §bGoblin Burrows", "Your kills: §c85 ☠ §a(+3)", "Remaining: §a2 goblins", "§7Kill goblins!", "Event Bonus: §6+4⸕", "Fossil Dust: §f3,281 §e(+1)"),
                listOf("Event: GOBLIN RAID", "in Goblin Burrows", "Your kills: 85 ☠ (+3)", "Remaining: 2 goblins", "Event Bonus: +4⸕", "Fossil Dust: 3,281 (+1)"),
            ),
            Case(
                "evMining",
                listOf("§9Wind Compass", "  §e≈  ", "Remaining: §a80 Tasty Mithril", "Your Tasty Mithril: §c70 §a(+70)", "§7Give Tasty Mithril to Don!"),
                listOf("Wind Compass", "≈", "Remaining: 80 Tasty Mithril", "Your Tasty Mithril: 70 (+70)"),
            ),
            Case("evServer", listOf("§cServer closing: 03:11 §8m77A"), listOf("Server closing: 03:11")),
            Case(
                "evVoting",
                listOf("§6Year 384 Votes", "§f||||||||||||||| §aFoxy", "§7Waiting for", "§7your vote..."),
                listOf("Year 384 Votes", "||||||||||||||| Foxy", "Waiting for", "your vote..."),
            ),
            Case(
                "evDojo",
                listOf("Challenge: §6Force", "Difficulty: §aEasy", "Points: §a10 §7(§a+§a10§7)", "Time: §a20s"),
                listOf("Challenge: Force", "Difficulty: Easy", "Points: 10 (+10)", "Time: 20s"),
                area = "Dojo",
            ),
            Case(
                "evDarkAuction",
                listOf("Starting in: §a0:30", "Time Left: §b11", "Current Item:", "§5Travel Scroll to Sirius"),
                listOf("Starting in: 0:30", "Time Left: 11", "Current Item:", "Travel Scroll to Sirius"),
                area = "Dark Auction",
            ),
            Case(
                "evJacob",
                listOf("§eJacob's Contest", "§e○ §fCarrot §a18m17s", " Collected §e8,264", "", "Purse: §61"),
                listOf("Jacob's Contest", "○ Carrot 18m17s", "Collected 8,264"),
            ),
            Case(
                "evMedals",
                listOf("§6§lGOLD §fmedals: §6111", "§f§lSILVER §fmedals: §f1,154", "§c§lBRONZE §fmedals: §c268"),
                listOf("GOLD medals: 111", "SILVER medals: 1,154", "BRONZE medals: 268"),
            ),
            Case("evTrapper", listOf("Pelts: §5711", "Tracker Mob Location:", "§bMushroom Gorge"), listOf("Pelts: 711", "Tracker Mob Location:", "Mushroom Gorge")),
            Case(
                "evGarden",
                listOf("   §aPlot §7- §b3 §c✖ x8", "   §fCleanup§7: §e0.3%", "   §fBarn Pasting§7: §e10.2%", "   §cLocked"),
                listOf("Plot - 3 ✖ x8", "Cleanup: 0.3%", "Barn Pasting: 10.2%", "Locked"),
                mode = "garden",
            ),
            Case("evFlight", listOf("Flight Duration: §a202:46:12"), listOf("Flight Duration: 202:46:12")),
            Case(
                "evWinter",
                listOf("§cWave 5", "Magma Cubes Left: §c3", "Next Wave: §a§aSoon!", "Your Total Damage: §c13,804 §e(#4)", "Your Cube Damage: §c303", "Event Start: §a2:38"),
                listOf("Wave 5", "Magma Cubes Left: 3", "Your Total Damage: 13,804 (#4)", "Your Cube Damage: 303", "Event Start: 2:38"),
            ),
            Case("evNewYear", listOf("§dNew Year Event!§f 17:53"), listOf("New Year Event! 17:53")),
            Case(
                "evSpooky",
                listOf("§6Spooky Festival§f 50:54"),
                listOf("Spooky Festival 50:54", "Your Candy:", "1 Green, 0 Purple (1 pts.)"),
                footer = listOf("Your Candy: §a1 Green§7, §50 Purple §7(§61 §7pts.)"),
            ),
            Case("evBrood", listOf("§4Broodmother§7: §6Soon"), listOf("Broodmother: Soon")),
            Case(
                "evGalatea",
                listOf("Whispers: §317.5k§b (+50)", "    §aHOTF§f: §a28k§9 (+29 Exp)", "§eAgatha's Contest §a5m28s", "§fFirst", "§fSecond", "§fThird"),
                listOf("Whispers: 17.5k (+50)", "HOTF: 28k (+29 Exp)", "Agatha's Contest 5m28s", "First", "Second"),
            ),
            Case("evSafari", listOf("Captured Mobs: §e14"), listOf("Captured Mobs: 14")),
            Case(
                "evDamage",
                listOf("Dragon HP: §a2,317,156 §c❤", "Your Damage: §c439,753.6"),
                listOf("Dragon HP: 2,317,156 ❤", "Your Damage: 439,753.6"),
            ),
            Case(
                "evMagma",
                listOf("§7Boss: §c45%", "§7Damage Soaked:", "§a" + "▎".repeat(5) + "§7" + "▎".repeat(15), "§cThe boss is reforming!"),
                listOf("Boss: 45%", "Damage Soaked:", "▎".repeat(20), "The boss is reforming!"),
            ),
            Case(
                "evCarnival",
                listOf("§eCarnival§f 85:33:57", "§3§lCatch a Fish", "§fCarnival Tokens: §e129", "§fFruits: §a2§7/§c10", "§fAccuracy: §a81.82%"),
                listOf("Carnival 85:33:57", "Catch a Fish", "Carnival Tokens: 129", "Fruits: 2/10", "Accuracy: 81.82%"),
            ),
            Case(
                "evRift",
                listOf(" §fRift Dimension", "Hay Eaten: §e2,477/3,000", "Clues: §a0/8", "Time Left: §b5m"),
                listOf("Rift Dimension", "Hay Eaten: 2,477/3,000", "Clues: 0/8", "Time Left: 5m"),
                mode = "rift",
            ),
            Case("evEssence", listOf("Dragon Essence: §d2,442"), listOf("Dragon Essence: 2,442")),
            Case(
                "evQueue",
                listOf("Queued: §aThe Catacombs", "Tier: §eFloor VI", "Position: §b#2 §fSince: §a00:01"),
                listOf("Queued: The Catacombs", "Tier: Floor VI", "Position: #2 Since: 00:01"),
            ),
            Case("evAnniversary", listOf("§d5th Anniversary§f 167:59:54"), listOf("5th Anniversary 167:59:54")),
            Case(
                "evActiveTab",
                emptyList(), listOf("Hoppity's Hunt", "Ends in: 26h"),
                tab = listOf("Event: §dHoppity's Hunt", " Ends In: 26h"),
            ),
            Case(
                "evSoonTab",
                emptyList(), listOf("Mining Fiesta", "Starts in: 52min"),
                tab = listOf("Event: §6Mining Fiesta", " Starts In: 52min"),
            ),
            Case("evRedstone", listOf(" §e§l⚡ §cRedstone: §e§b7%"), listOf("⚡ Redstone: 7%")),
        )

    @Test
    fun everyEventReadsItsReferenceLines() {
        for (c in cases) {
            val s = snap(c.lines, c.area, c.mode, c.tab, c.footer)
            assertEquals(c.expect, build(c.id, s), c.id + " " + c.lines)
        }
        // Every one of the 28 has at least one case.
        assertEquals(EventDefs.all.map { it.id }.toSet(), cases.map { it.id }.toSet())
    }

    @Test
    fun eventLinesClaimTheirSidebarLinesAndNothingElse() {
        val s = snap(listOf("Purse: §61", "§eJacob's Contest", "§e○ §fCarrot §a18m17s", "", "Bits: §b2"))
        assertEquals(listOf(1, 2), ScoreboardCatalog.type("evJacob")!!.claims(s).sorted())
        assertEquals(emptyList(), ScoreboardCatalog.type("evSafari")!!.claims(s).toList())
    }

    @Test
    fun anUnrecognizedLineIsNotTaken() {
        val s = snap(listOf("Boss: §cmystery", "Tracker: §7???"), mode = "dungeon")
        for (d in EventDefs.all) assertEquals(emptyList(), build(d.id, s), d.id)
    }

    @Test
    fun kuudraAndDungeonLinesStayApart() {
        val lines = listOf("Time Elapsed: §a48s", "Starting in: §a0:05")
        assertEquals(emptyList(), build("evKuudra", snap(lines, mode = "dungeon")))
        assertEquals(emptyList(), build("evDungeons", snap(lines, mode = "kuudra")))
        assertEquals(listOf("Time Elapsed: 48s", "Starting in: 0:05"), build("evKuudra", snap(lines, mode = "kuudra")))
    }

    @Test
    fun theTabEventsLeaveWhatTheSidebarShows() {
        val tab = listOf("Event: §aCarnival", " Ends In: 3d")
        assertEquals(emptyList(), build("evActiveTab", snap(emptyList(), tab = tab)))
    }

    // ------------------------------------------------------------------ the Events line

    private val running = listOf("Event: §6§lRAFFLE", "Tickets: §a8 §7(17.4%)", "§cServer closing: 00:59 §8m1A", "Flight Duration: §a10m 0s")

    @Test
    fun theEventsLineShowsEveryRunningEventInPriorityOrder() {
        val s = snap(running, area = "Dwarven Mines")
        // Default order: mining comes before server closing before flight.
        assertEquals(listOf("Event: RAFFLE", "Tickets: 8 (17.4%)", "Server closing: 00:59", "Flight Duration: 10m 0s"), build("events", s))
        assertEquals(
            listOf("Flight Duration: 10m 0s", "Event: RAFFLE", "Tickets: 8 (17.4%)"),
            build(entry("events", mapOf("events" to listOf("evFlight", "evMining", "evUnknown"))), s),
        )
    }

    @Test
    fun anEventNotInTheStoredListIsOff() {
        val s = snap(running)
        assertEquals(listOf("Flight Duration: 10m 0s"), build(entry("events", mapOf("events" to listOf("evFlight"))), s))
        assertEquals(emptyList(), build(entry("events", mapOf("events" to emptyList<String>())), s))
    }

    @Test
    fun onlyTheFirstEvent() {
        val s = snap(running)
        assertEquals(listOf("Event: RAFFLE", "Tickets: 8 (17.4%)"), build(entry("events", mapOf("evAll" to "top")), s))
    }

    @Test
    fun gapBetweenEventsAndThePrefixOption() {
        val s = snap(running)
        val built = ScoreboardBuilder.build(listOf(entry("events", mapOf("evGap" to true, "evPrefix" to false))), s, look, 0L).map { LegacyText.strip(it.text).trim() }
        assertEquals(listOf("RAFFLE", "Tickets: 8 (17.4%)", "", "Server closing: 00:59", "", "Flight Duration: 10m 0s"), built)
    }

    @Test
    fun theTabEventShowsUnlessTurnedOffInInstances() {
        val tab = listOf("Event: §dHoppity's Hunt", " Ends In: 26h")
        val outside = snap(emptyList(), tab = tab)
        assertEquals(listOf("Event: Hoppity's Hunt", "Ends in: 26h"), build("events", outside))
        val dungeon = snap(listOf("Time Elapsed: §a01s"), mode = "dungeon", tab = tab)
        assertEquals(listOf("Time Elapsed: 01s", "Event: Hoppity's Hunt", "Ends in: 26h"), build("events", dungeon))
        assertEquals(listOf("Time Elapsed: 01s"), build(entry("events", mapOf("evTabInst" to false)), dungeon))
    }

    @Test
    fun theEventsLineClaimsWhatAnyEventTakes() {
        val s = snap(running, area = "Dwarven Mines")
        assertEquals(listOf(0, 1, 2, 3), EventsLine.claims(s).sorted())
    }

    // ------------------------------------------------------------------ cookie and God Potion (real footer)

    private fun footerSnap() = ScoreboardSnapshot(tabFooter = footer())

    @Test
    fun realFooterGivesCookieAndGodPotion() {
        val s = footerSnap()
        assertEquals(listOf("Cookie Buff: 2d 21h"), build("cookie", s))
        assertEquals(listOf("God Potion: 39h"), build("godpot", s))
    }

    @Test
    fun cookieAndGodPotionKeepTheirLabelsAndColors() {
        val s = footerSnap()
        assertEquals("§dCookie Buff: §f2d 21h", ScoreboardBuilder.build(listOf(ScoreboardEntry("cookie")), s, look, 0L).single().text)
        val custom = entry("cookie", mapOf("label" to "Cookie:"))
        assertEquals(listOf("Cookie: 2d 21h"), build(custom, s))
        val shown = ScoreboardBuilder.build(listOf(ScoreboardEntry("cookie")), ScoreboardSnapshot(), ScoreboardLook(hideEmpty = false), 0L)
        assertEquals(listOf("Cookie Buff: -"), shown.map { LegacyText.strip(it.text) })
    }

    @Test
    fun noCookieAndNoPotionShowNothing() {
        val none = ScoreboardSnapshot(tabFooter = listOf("§d§lCookie Buff", "§7Not active! Obtain booster cookies from the community shop in the hub.").map(SourceLine::of))
        assertEquals(emptyList(), build("cookie", none))
        assertEquals(emptyList(), build("godpot", none))
        assertEquals("2d 21h", ActiveEffects.compact("2 days, 21 hours"))
        assertEquals("39h", ActiveEffects.compact("39 hours"))
    }

    // ------------------------------------------------------------------ pet

    @Test
    fun petLevelAndMax() {
        PetLine.source = { PetInfo("Golden Dragon", 0xFFAA00, 187, false) }
        val s = ScoreboardSnapshot()
        assertEquals(listOf("Pet: Golden Dragon [Lv187]"), build("pet", s))
        PetLine.source = { PetInfo("Golden Dragon", 0xFFAA00, 200, true) }
        assertEquals(listOf("Pet: Golden Dragon [Max]"), build("pet", s))
        assertEquals(listOf("Pet: Golden Dragon [Lv200]"), build(entry("pet", mapOf("petMax" to false)), s))
        PetLine.source = { PetInfo("Ender Dragon", null, null, false) }
        assertEquals(listOf("Pet: Ender Dragon"), build("pet", s))
        PetLine.source = { null }
        assertEquals(emptyList(), build("pet", s))
    }

    // ------------------------------------------------------------------ mayor

    private val electionJson =
        """
        {"success":true,"lastUpdated":1,"mayor":{"key":"diana","name":"Diana","perks":[
          {"name":"Lucky!","description":"§7Lucky"},{"name":"Mythological Ritual","description":"§7Ritual"},{"name":"Pet XP Buff","description":"§7Pet xp"}],
          "minister":{"key":"foxy","name":"Foxy","perk":{"name":"Extra Event","description":"§7Schedules an extra §bFishing Festival §7event during the year."}},
          "election":{"year":384,"candidates":[
            {"key":"diana","name":"Diana","perks":[{"name":"Lucky!","description":""},{"name":"Mythological Ritual","description":""}],"votes":5},
            {"key":"jerry","name":"Jerry","perks":[{"name":"Perkpocalypse","description":""},{"name":"Statspocalypse","description":""}],"votes":1}]}},
         "current":{"year":385,"candidates":[{"key":"cole","name":"Cole","perks":[{"name":"Prospection","description":""}],"votes":0}]}}
        """.trimIndent()

    /** The middle of Early Summer of year 385: 3 months + 26 days short of... see [MayorData.nextMayorAt]. */
    private val now = MayorData.EPOCH_MS + 385 * MayorData.YEAR_MS + 5 * (MayorData.YEAR_MS / 12)

    @Test
    fun theElectionJsonIsParsed() {
        val e = MayorData.parse(electionJson)!!
        assertEquals("Diana", e.mayor!!.name)
        assertEquals(listOf("Lucky!", "Mythological Ritual", "Pet XP Buff"), e.mayor!!.perks.map { it.name })
        assertEquals("Foxy", e.minister!!.name)
        assertEquals("Jerry", e.mayorWithPerk("Statspocalypse")!!.name)
        assertEquals("Cole", e.mayorWithPerk("Prospection")!!.name)
        assertNull(MayorData.parse("{\"success\":false}"))
        assertNull(MayorData.parse("not json"))
    }

    @Test
    fun theNextMayorIsLateSpring27() {
        val spring27 = MayorData.EPOCH_MS + 385 * MayorData.YEAR_MS + 2 * (MayorData.YEAR_MS / 12) + 26 * (MayorData.YEAR_MS / 12 / 31)
        assertEquals(spring27, MayorData.nextMayorAt(spring27 - 1000) )
        assertEquals(spring27 + MayorData.YEAR_MS, MayorData.nextMayorAt(spring27))
        assertEquals(spring27 + MayorData.YEAR_MS, MayorData.nextMayorAt(spring27 + 5_000))
        // From the middle of year 385 the hand-over is in year 386 (385 + 1).
        assertEquals(spring27 + MayorData.YEAR_MS, MayorData.nextMayorAt(now))
    }

    @Test
    fun durationsShowTwoUnits() {
        assertEquals("4d 12h", MayorData.duration((4 * 24 + 12) * 3_600_000L))
        assertEquals("4d 12h", MayorData.duration((4 * 24 + 12) * 3_600_000L + 59 * 60_000L))
        assertEquals("3h 20m", MayorData.duration(3 * 3_600_000L + 20 * 60_000L + 5_000))
        assertEquals("45m 5s", MayorData.duration(45 * 60_000L + 5_000))
        assertEquals("50s", MayorData.duration(50_000))
        assertEquals("0s", MayorData.duration(-5))
    }

    @Test
    fun mayorMinisterAndPerks() {
        MayorData.autoFetch = false
        MayorData.election = MayorData.parse(electionJson)
        val s = ScoreboardSnapshot()
        val days = MayorData.duration(MayorData.nextMayorAt(now) - now)
        assertEquals(
            listOf("Diana ($days)", "- Lucky!", "- Mythological Ritual", "- Pet XP Buff", "Minister: Foxy", "- Extra Event: Fishing Festival"),
            build(entry("mayor"), s, now),
        )
        assertEquals(listOf("Diana", "- 3 perks", "Minister: Foxy"), build(entry("mayor", mapOf("mayorTime" to false, "perks" to "count", "minister" to "compact", "foxy" to false)), s, now))
        assertEquals(listOf("Diana"), build(entry("mayor", mapOf("mayorTime" to false, "perks" to "off", "minister" to "off")), s, now))
        assertEquals(
            listOf("Diana", "Minister: Foxy", "- Extra event: Fishing Festival"),
            build(entry("mayor", mapOf("mayorTime" to false, "perks" to "off", "minister" to "full")), s, now),
        )
    }

    @Test
    fun mayorShowsNothingWithoutData() {
        MayorData.autoFetch = false
        MayorData.election = null
        assertEquals(emptyList(), build("mayor", ScoreboardSnapshot()))
    }

    @Test
    fun jerrysPerkpocalypseMayorFromTheCalendar() {
        MayorData.autoFetch = false
        val e = MayorData.parse(electionJson.replace("\"mayor\":{\"key\":\"diana\",\"name\":\"Diana\"", "\"mayor\":{\"key\":\"jerry\",\"name\":\"Jerry\""))!!
        MayorData.election = e
        val lore = listOf("§7Mayor Jerry", "", "§9Perkpocalypse Perks:", "", "§6Statspocalypse", "§7stuff")
        MayorData.readJerry(lore, e, now)
        assertEquals("Jerry", MayorData.jerryMayor!!.name)
        assertTrue(MayorData.jerryUntil > now)
        assertTrue(MayorData.jerryUntil - now <= 6 * 3_600_000L)
        val out = build(entry("mayor", mapOf("perks" to "off", "minister" to "off")), ScoreboardSnapshot(), now)
        assertEquals(2, out.size)
        assertTrue(out[1].startsWith("Extra mayor: Jerry ("), out.toString())
        assertEquals(listOf(out[0]), build(entry("mayor", mapOf("perks" to "off", "minister" to "off", "jerry" to false)), ScoreboardSnapshot(), now))
    }

    // ------------------------------------------------------------------ party

    @Test
    fun partyOutsideDungeonsNeedsEverywhereOrAPlaceHypixelListsIt() {
        PartyLine.source = { PartyInfo("SoulReturns", listOf("SoulReturns", "ArkCeleste", "Pawding", "Maaxnn", "Extra"), 5) }
        val hub = ScoreboardSnapshot(area = "Hub")
        assertEquals(emptyList(), build("party", hub))
        assertEquals(
            listOf("Party (5)", "- SoulReturns ♚", "- ArkCeleste", "- Pawding", "- Maaxnn", "- Extra"),
            build(entry("party", mapOf("partyAll" to true)), hub),
        )
        assertEquals(
            listOf("Party (5)", "- SoulReturns ♚", "- ArkCeleste", "- Pawding", "- Maaxnn", "- Extra"),
            build("party", ScoreboardSnapshot(area = "Dungeon Hub")),
        )
        // Without the leader mark the leader is one of the members; the cap counts members.
        assertEquals(listOf("Party (5)", "- SoulReturns", "- ArkCeleste"), build(entry("party", mapOf("partyAll" to true, "partyLeader" to false, "partyMax" to 2)), hub))
    }

    @Test
    fun noPartyShowsNothing() {
        PartyLine.source = { null }
        assertEquals(emptyList(), build(entry("party", mapOf("partyAll" to true)), ScoreboardSnapshot(area = "Hub")))
    }

    @Test
    fun dungeonTeammatesAreHypixelsRowsWithAHeading() {
        val s = snap(listOf("Time Elapsed: §a03m 21s", "§e[M] §bSoulReturns §a20,000§c❤", "§e[H] §aAlexTester §a14,250§c❤", "§e[B] §6BuilderBob §c§lDEAD"), mode = "dungeon")
        assertEquals(listOf("Party (3)", "[M] SoulReturns 20,000❤", "[H] AlexTester 14,250❤", "[B] BuilderBob DEAD"), build("party", s))
        assertEquals(listOf(1, 2, 3), PartyLine.claims(s).sorted())
        // The Dungeon run line leaves the teammates to Party.
        assertEquals(listOf("Time Elapsed: 03m 21s"), build("evDungeons", s))
    }

    @Test
    fun kuudraTeammatesCarryTheirHealth() {
        val s = snap(listOf("§fSoulReturns §a14,210§c❤", "§bArkCeleste §a9,880§c❤"), mode = "kuudra")
        assertEquals(listOf("Party (2)", "SoulReturns 14,210❤", "ArkCeleste 9,880❤"), build("party", s))
    }
}
