package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.mining.mineshaft.MineshaftCorpses
import com.soulreturns.features.mining.nucleus.BalState
import com.soulreturns.features.pets.PetView
import com.soulreturns.features.scoreboard.lines.TabWidgetCatalog
import com.soulreturns.features.scoreboard.lines.TabWidgetLine
import com.soulreturns.features.scoreboard.lines.soul.BalLine
import com.soulreturns.features.scoreboard.lines.soul.ComboLine
import com.soulreturns.features.scoreboard.lines.soul.DragonEyesLine
import com.soulreturns.features.scoreboard.lines.soul.DrillFuelLine
import com.soulreturns.features.scoreboard.lines.soul.DungeonBlessingsLine
import com.soulreturns.features.scoreboard.lines.soul.DungeonRoomLine
import com.soulreturns.features.scoreboard.lines.soul.FishingSessionLine
import com.soulreturns.features.scoreboard.lines.soul.FriendsContestLine
import com.soulreturns.features.scoreboard.lines.soul.IslandWeatherLine
import com.soulreturns.features.scoreboard.lines.soul.MimicPrinceLine
import com.soulreturns.features.scoreboard.lines.soul.MineshaftCorpsesLine
import com.soulreturns.features.scoreboard.lines.soul.NucleusRunLine
import com.soulreturns.features.scoreboard.lines.soul.PetXpLine
import com.soulreturns.features.scoreboard.lines.soul.SackGainsLine
import com.soulreturns.features.scoreboard.lines.soul.SkillGainLine
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreboardSoulLinesTest {
    @kotlin.test.BeforeTest
    fun resetGainTrackers() = com.soulreturns.features.scoreboard.lines.GainTracker.resetAll()

    private val tab: List<String> =
        Json.parseToJsonElement(javaClass.getResource("/scoreboard/tab-dwarven-2026-11-15.json")!!.readText()).jsonObject["lines"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun build(id: String, s: ScoreboardSnapshot, options: JsonObject = JsonObject(emptyMap()), now: Long = 0L) =
        ScoreboardBuilder.build(listOf(ScoreboardEntry(id, options = options)), s, ScoreboardLook(), now).map { it.text }

    private fun snap(
        tabLines: List<String> = emptyList(),
        footer: List<String> = emptyList(),
        bar: String? = null,
        mode: String? = null,
        area: String? = null,
    ): ScoreboardSnapshot {
        val base = ScoreboardSnapshot.ofSidebar("", emptyList(), tabLines)
        return ScoreboardSnapshot(
            tabWidgets = base.tabWidgets, tabFooter = footer.map(SourceLine::of), actionBar = bar?.let(SourceLine::of), mode = mode, area = area,
        )
    }

    // ------------------------------------------------------------------ tab widgets

    private val mockNames =
        listOf(
            "Area", "Profile", "Pet", "Daily quests", "Skills", "Election", "Bestiary", "Collection", "Stats", "Event", "Event trackers", "Active effects", "Timers",
            "Fire sales", "Minions", "Pity", "Slayer", "Forges", "Commissions", "Powders", "Crystals", "Mining event", "Frozen corpses", "Pickaxe ability", "Worms",
            "Agatha's contest", "Starborn Temple", "Forest whispers", "Desert whispers", "Moonglade beacon", "Torrhus beacon", "Shard traps", "Salts", "Composter",
            "Jacob's contest", "Pests", "Pest traps", "Visitors", "Trapper", "Crop milestones", "Reputation", "Trophy fish", "Faction quests", "Dragon", "Trophy frogs",
            "Downed", "Team deaths", "Discoveries", "Puzzles", "RNG meter", "Party", "Dungeons", "Essence", "Good to know", "Shen's auction", "Store line",
        )

    @Test
    fun allFiftySixWidgetsAreRegisteredWithTheMocksNames() {
        assertEquals(56, mockNames.size)
        val lines = TabWidgetCatalog.all
        assertEquals(mockNames, lines.map { it.name })
        assertEquals(56, lines.map { it.id }.toSet().size)
        for (l in lines) {
            assertEquals(ScoreboardCatalog.TAB_LIST, l.group)
            assertEquals("tab list", l.tag)
            assertTrue(l.example.first().contains("§l") && l.example.first().contains(":"), l.id)
            assertTrue(l.description.isNotBlank(), l.id)
            assertTrue(ScoreboardCatalog.type(l.id) === l, l.id)
        }
        assertTrue(TabWidgetCatalog.verified.containsAll(listOf("tArea", "tProfile", "tPet", "tForges", "tComms", "tPowders", "tPickaxe", "tBestiary")))
    }

    @Test
    fun exampleLinesAreDrawnAsHypixelDrawsThem() {
        val by = TabWidgetCatalog.all.associateBy { it.id }
        assertEquals(listOf("§e§lPet:", " §6[Lv100] Golden Dragon"), by.getValue("tPet").example)
        assertEquals(listOf("§b§lArea:§r §bDwarven Mines", " Server: §8mini26CH"), by.getValue("tArea").example)
        assertEquals(listOf("§f§lPowders:", " Mithril: §266,017", " Gemstone: §d13,076"), by.getValue("tPowders").example)
        assertEquals(listOf("§e§lAdvertisement:", " §aRanks, Boosters & MORE! §c§lSTORE.HYPIXEL.NET"), by.getValue("tAd").example)
        assertEquals(listOf("§b§lDragon:§r §cSuperior Dragon", "§b§lEyes placed:§r §e8/8"), by.getValue("tDragon").example)
    }

    @Test
    fun theDwarvenMinesTabFixtureFillsExactlyTheLinesItHas() {
        val s = snap(tab, mode = "mining_3", area = "Dwarven Mines")
        val drawn = TabWidgetCatalog.all.filter { it.lines(contextFor(it, s)).isNotEmpty() }.map { it.id }
        assertEquals(listOf("tArea", "tProfile", "tPet", "tBestiary", "tForges", "tComms", "tPowders", "tPickaxe").sorted(), drawn.sorted())
        val forges = build("tForges", s)
        assertEquals(8, forges.size)
        assertTrue(LegacyText.strip(forges[0]).startsWith("Forges:") && LegacyText.strip(forges[1]).trim() == "1) Gemstone Mixture: 1h 40m")
    }

    private fun contextFor(t: LineType, s: ScoreboardSnapshot) =
        LineContext(s, ScoreboardEntry(t.id), OptionValues(JsonObject(emptyMap()), t.options), ScoreboardLook(), 0L)

    @Test
    fun theEventHeaderBelongsToTheMiningLineOnMiningIslandsAndToTheEventLineElsewhere() {
        val lines = listOf("§r§e§lEvent: §r§6Raffle", "§r Ends In: §r§e11m")
        val mining = snap(lines, mode = "mining_3", area = "Dwarven Mines")
        assertEquals(2, build("tMiningEvent", mining).size)
        assertTrue(build("tEvent", mining).isEmpty())
        val hub = snap(lines, area = "Hub")
        assertTrue(build("tMiningEvent", hub).isEmpty())
        assertEquals(2, build("tEvent", hub).size)
    }

    @Test
    fun oneLineCanDrawSeveralHeaders() {
        val s = snap(listOf("§r§b§lDragon: §r§f(Young)", "", "§r§e§lEyes placed: §r§e3", "", "§r§e§lMage Reputation: §r§510", "", "§r§c§lBarbarian Reputation: §r§c2"))
        assertEquals(2, build("tDragon", s).size)
        assertEquals(2, build("tRep", s).size)
        assertTrue(build("tTimers", s).isEmpty())
    }

    // ------------------------------------------------------------------ Soul lines

    @Test
    fun weatherReadsRunningAndNextFromTheTabRows() {
        val s = snap(tab)
        assertEquals(listOf("§fBreeze §7in 18m"), build("weather", s))
        assertTrue(build("weather", s, JsonObject(mapOf(IslandWeatherLine.NEXT to JsonPrimitive(false)))).isEmpty())
        val running = snap(listOf("§r§b§lArea: §r§7Crystal Hollows", "§r Rockfall§r§f: §r§cfor 8m §r§c\uE09C", "§r Mist§r§f: §r§bin 37m §r§b\uE101"))
        assertEquals(listOf("§9Rockfall §7for 8m §8· §bMist §7in 37m"), build("weather", running))
        assertEquals("§9Rockfall §7(8m)", IslandWeatherLine.running(running))
        assertNull(IslandWeatherLine.running(snap(tab)))
        // Not a weather row: no trailing icon.
        assertTrue(build("weather", snap(listOf("§r§e§lJerry: §r§7in 5m"))).isEmpty())
    }

    @Test
    fun petXpShowsNameLevelPercentAndRate() {
        val v = PetView("Golden Dragon", SkyblockRarity.LEGENDARY, false, 187, 200, false, 620.0, 1000.0, null, 5_000_000.0, null, null, 1_200_000.0)
        assertEquals("§6Golden Dragon §7Lv187 §8· §a62% §8· §b+1.2M/h", PetXpLine.format(v, true))
        assertEquals("§6Golden Dragon §7Lv187 §8· §a62%", PetXpLine.format(v, false))
        val maxed = PetView("Flying Fish", SkyblockRarity.EPIC, false, 100, 100, true, null, null, 4.0, null, null, null, null)
        assertEquals("§5Flying Fish §7Lv100 §8· §aMAX", PetXpLine.format(maxed, true))
        val unknown = PetView("Ammonite", null, false, null, 100, false, null, null, null, null, null, null, null)
        assertEquals("§fAmmonite", PetXpLine.format(unknown, true))
    }

    @Test
    fun comboShowsCountTierAndBonuses() {
        assertEquals("§fCombo: §642 §8(§3+4☯ Wisdom§8)", ComboLine.format(42, 4.0, 0.0))
        assertEquals("§fCombo: §a10", ComboLine.format(10, 0.0, 0.0))
        assertEquals("§fCombo: §915 §8(§3+2.5☯ Wisdom§8, §6+30 coins§8)", ComboLine.format(15, 2.5, 30.0))
    }

    @Test
    fun fishingNeedsATimeOrACatch() {
        assertNull(FishingSessionLine.format(0, 0))
        assertEquals("§fFishing: §f12:41 §8· §b38 §7creatures", FishingSessionLine.format(761_000, 38))
        assertEquals("§fFishing: §f1:02:03 §8· §b1 §7creature", FishingSessionLine.format(3_723_000, 1))
    }

    @Test
    fun sackGainsShowTheBiggestAndCountTheRest() {
        val g = listOf(SackGainsLine.Gain("Coal", "§8", 90), SackGainsLine.Gain("Mithril", "§b", 1204), SackGainsLine.Gain("Gold", "§6", -5), SackGainsLine.Gain("Iron", "§f", 7))
        assertEquals("§fSacks: §a+1,204 §bMithril §8(+3 more)", SackGainsLine.format(g))
        assertEquals("§fSacks: §c-5 §6Gold", SackGainsLine.format(listOf(g[2])))
        assertNull(SackGainsLine.format(emptyList()))
    }

    @Test
    fun nucleusAndBalFormats() {
        assertEquals("§fNucleus: §a3§7/§a5 §7crystals §8· §f12:04", NucleusRunLine.format(3, 5, 724_000))
        assertEquals("§fNucleus: §a0§7/§a5 §7crystals", NucleusRunLine.format(0, 5, null))
        assertEquals("§fBal: §cup!", BalLine.format(BalState.Phase.UP, false))
        assertEquals("§fBal: §espawning", BalLine.format(BalState.Phase.SPAWNING, false))
        assertEquals("§fBal: §adown §7(about)", BalLine.format(BalState.Phase.DOWN, true))
        assertNull(BalLine.format(BalState.Phase.UNKNOWN, false))
    }

    @Test
    fun corpsesCountLootedAndNameTheBestUnlooted() {
        val m = mapOf("Lapis" to MineshaftCorpses.Counts(1, 2), "Umber" to MineshaftCorpses.Counts(0, 1), "Glacite" to MineshaftCorpses.Counts(1, 0))
        assertEquals("§fCorpses: §a3§7/§a5 §7looted §8· §9Lapis", MineshaftCorpsesLine.format(m) { "§9" })
        assertEquals("Glacite", MineshaftCorpsesLine.best(mapOf("Glacite" to MineshaftCorpses.Counts(1, 0), "Zed" to MineshaftCorpses.Counts(2, 0))))
        assertEquals("§fCorpses: §a1§7/§a1 §7looted", MineshaftCorpsesLine.format(mapOf("Lapis" to MineshaftCorpses.Counts(0, 1))) { "§9" })
        assertNull(MineshaftCorpsesLine.format(emptyMap()) { "§9" })
    }

    @Test
    fun dragonEyesComeFromTheTabWidget() {
        val s = snap(listOf("§r§b§lDragon: §r§f(Young)", "", "§r§e§lEyes placed: §r§e8/8"))
        assertEquals(8 to 8, DragonEyesLine.placed(s))
        assertEquals(3 to 8, DragonEyesLine.placed(snap(listOf("§r§e§lEyes placed: §r§e3"))))
        assertNull(DragonEyesLine.placed(snap(tab)))
        assertEquals("§fEyes: §d8§7/§d8 §8· §fyours §d2", DragonEyesLine.format(8 to 8, 2))
        assertEquals("§fEyes: §d8§7/§d8", DragonEyesLine.format(8 to 8, 0))
    }

    @Test
    fun roomAndMimicFormats() {
        assertEquals(3 to 5, DungeonRoomLine.barSecrets("⁂ 3/5 Secrets  ❤"))
        assertNull(DungeonRoomLine.barSecrets("nothing"))
        assertEquals("§fRoom: §bWithermancers §7(§a3§7/§e5 §7secrets)", DungeonRoomLine.format("Withermancers", 3 to 5))
        assertEquals("§fRoom: §bFairy", DungeonRoomLine.format("Fairy", 0 to 0))
        assertEquals("§7Mimic §a✔ §8· §7Prince §c✖", MimicPrinceLine.format(true, false))
    }

    @Test
    fun blessingsComeFromTheTabFooter() {
        val footer = listOf("§r§aDungeon Buffs", "§r§r§fBlessing of Power XII§r", "§r§r§fBlessing of Life IX§r", "§r§r§fBlessing of Wisdom VIII§r")
        assertEquals(listOf("§7Power §cXII §8· §7Life §aIX §8· §7Wisdom §bVIII"), build("blessings", snap(footer = footer)))
        assertTrue(build("blessings", snap(footer = listOf("§aDungeon Buffs", "§7No Buffs active. Find them by exploring the Dungeon!"))).isEmpty())
        assertTrue(build("blessings", snap(footer = listOf("§aSomething else", "§fBlessing of Power V"))).isEmpty())
        assertEquals("§7Newbless §f5", DungeonBlessingsLine.format(listOf(DungeonBlessingsLine.Blessing("Newbless", "5"))))
    }

    @Test
    fun drillFuelIsReadFromTheActionBarAndHeldBriefly() {
        DrillFuelLine.reset()
        val bar = "§62,849/2,474\uE010     §22,136/3k Drill Fuel     §a859\uE008"
        assertEquals(listOf("§fDrill Fuel: §22,136§7/§23k"), build("drill", snap(bar = bar), now = 1_000))
        assertEquals(listOf("§fDrill Fuel: §22,136§7/§23k"), build("drill", snap(bar = "§7other"), now = 5_000))
        assertTrue(build("drill", snap(bar = null), now = 20_000).isEmpty())
        DrillFuelLine.reset()
    }

    @Test
    fun skillGainIsReadFromTheActionBarAndHeldBriefly() {
        SkillGainLine.reset()
        val bar = "§3+200.5 Combat (659,020,723/0)     §b1,404/1,606\uE003"
        assertEquals(listOf("§3+200.5 Combat §7(659,020,723/0)"), build("skillgain", snap(bar = bar), now = 1_000))
        assertEquals(listOf("§3+200.5 Combat §7(659,020,723/0)"), build("skillgain", snap(), now = 15_000))
        assertTrue(build("skillgain", snap(), now = 30_000).isEmpty())
        assertEquals(listOf("§3+42.7 Combat §7(39.52%)"), build("skillgain", snap(bar = "§3+42.7 Combat (39.52%)"), now = 31_000))
        SkillGainLine.reset()
    }

    @Test
    fun contestFormat() {
        assertEquals("§fContest: §6#2 §7of 5 §8· §e4.1M", FriendsContestLine.format(FriendsContestLine.Standing(2, 5, 4_100_000), true))
        assertEquals("§fContest: §6#1 §7of 3 §8· §e4,100", FriendsContestLine.format(FriendsContestLine.Standing(1, 3, 4_100), false))
    }

    @Test
    fun theSoulPlaceholdersAreRegistered() {
        for (key in listOf("pet", "petxp", "combo", "weather", "fuel", "skill")) {
            val v = ScoreboardValues.get(key)
            assertTrue(v != null && v.group == "Soul", key)
        }
        for (id in listOf("weather", "petxp", "combo", "fishing", "sacks", "nucleus", "bal", "corpses", "eyes", "room", "mimic", "blessings", "drill", "skillgain", "contest")) {
            val t = ScoreboardCatalog.type(id)
            assertTrue(t != null && t.group == ScoreboardCatalog.SOUL && t.tag == "Soul", id)
        }
        val ctx = LineContext(snap(bar = "§3+42.7 Combat (39.52%)"), ScoreboardEntry("custom"), OptionValues(JsonObject(emptyMap()), emptyList()), ScoreboardLook(), 500L)
        assertEquals("§7Gain: §3+42.7 Combat§7!", ScoreboardValues.expand("§7Gain: {skill}!", ctx))
        SkillGainLine.reset()
    }
}
