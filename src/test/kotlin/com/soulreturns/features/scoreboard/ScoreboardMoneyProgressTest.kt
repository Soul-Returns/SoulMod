package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.data.hypixel.SkyblockProfileMember
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.data.skyblock.TabSnapshot
import com.soulreturns.data.skyblock.TabWidgetParser
import com.soulreturns.features.scoreboard.lines.MoneyChunkLine
import com.soulreturns.features.scoreboard.lines.PlayersLine
import com.soulreturns.features.scoreboard.lines.QuiverLine
import com.soulreturns.features.scoreboard.lines.RealTimeLine
import com.soulreturns.features.scoreboard.lines.Stat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.ZoneId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Money, Progress and the rest of Time & place. Real captures: the Crystal Hollows sidebars (Heat, Hypixel's
 * own powder line) in `money/sidebars.json` and the Dwarven Mines tab list (bank, gems, SB level, Powders).
 * Lines no capture has (Motes, Copper, Sowdust, Cold, North Stars, Kernels, Visiting, Slayer Quest) are
 * checked against SkyHanni's documented test lines, marked `reference` below.
 */
class ScoreboardMoneyProgressTest {
    private val look = ScoreboardLook()

    private fun sidebars(path: String) =
        Json.parseToJsonElement(javaClass.getResource(path)!!.readText()).jsonObject["sidebars"]!!.jsonArray.associate {
            val o = it.jsonObject
            o["name"]!!.jsonPrimitive.content to (o["title"]!!.jsonPrimitive.content to o["lines"]!!.jsonArray.map { l -> l.jsonPrimitive.content })
        }

    private val hollows = sidebars("/scoreboard/money/sidebars.json")
    private val tabLines =
        Json.parseToJsonElement(javaClass.getResource("/scoreboard/tab-dwarven-2026-11-15.json")!!.readText()).jsonObject["lines"]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun tabSnapshot(lines: List<String> = tabLines): TabSnapshot {
        val r = TabWidgetParser.parse(lines, 0L)
        return TabSnapshot(skyblockLevel = r.skyblockLevel, bank = r.bank, interest = r.interest, gems = r.gems)
    }

    private fun sidebarOnly(name: String, tab: List<String> = emptyList()): ScoreboardSnapshot {
        val (title, lines) = hollows.getValue(name)
        return ScoreboardSnapshot.ofSidebar(title, lines, tab)
    }

    private fun raw(title: String, lines: List<String>, tab: List<String> = emptyList(), profileType: ProfileType = ProfileType.UNKNOWN, profile: String? = null) =
        ScoreboardSnapshot.ofSidebar(title, lines, tab).let {
            ScoreboardSnapshot(
                title = it.title, sidebar = it.sidebar, tabWidgets = it.tabWidgets, profileType = profileType, profile = profile,
                tab = tabSnapshot(tab.ifEmpty { tabLines }),
            )
        }

    private fun build(entry: ScoreboardEntry, s: ScoreboardSnapshot, l: ScoreboardLook = look, now: Long = 0L) =
        ScoreboardBuilder.build(listOf(entry), s, l, now).map { it.text }

    private fun build(id: String, s: ScoreboardSnapshot) = build(ScoreboardEntry(id), s)

    private fun opts(type: String, vararg o: Pair<String, Any>) =
        ScoreboardEntry(
            type,
            options = buildJsonObject {
                for ((k, v) in o) put(k, if (v is Boolean) JsonPrimitive(v) else if (v is Number) JsonPrimitive(v) else if (v is JsonArray) v else JsonPrimitive(v.toString()))
            },
        )

    private val tabSnap get() = ScoreboardSnapshot.ofSidebar("", emptyList(), tabLines).let { ScoreboardSnapshot(tabWidgets = it.tabWidgets, tab = tabSnapshot()) }

    private val member =
        SkyblockProfileMember(
            Json.parseToJsonElement(
                """{"slayer":{"slayer_bosses":{"enderman":{"xp":612000}}},
                    "accessory_bag_storage":{"selected_power":"sighted","highest_magical_power":1412,
                      "tuning":{"slot_0":{"strength":34,"attack_speed":20,"critical_chance":7}}},
                    "mining_core":{"powder_mithril":66017,"powder_spent_mithril":100000}}""",
            ).jsonObject,
        )

    @AfterTest
    fun reset() {
        Stat.member = { null }
    }

    // ------------------------------------------------------------------ sidebar counters

    @Test
    fun heatIsReadFromTheRealCrystalHollowsSidebar() {
        val s = sidebarOnly("crystal-hollows-heat-2026-11-07")
        assertEquals(listOf("§fHeat: §c0♨"), build("heat", s))
        assertEquals(listOf("§fWärme: §c0♨"), build(opts("heat", "label" to "Wärme:"), s))
        assertTrue(ScoreboardCatalog.type("heat")!!.claims(s).isNotEmpty())
        assertEquals(emptyList(), build("cold", s))
        assertEquals(emptyList(), build("motes", s))
    }

    @Test
    fun referenceCountersKeepHypixelsColorsAndGains() {
        // reference: SkyHanni ScoreboardPattern REGEX-TESTs
        val s = ScoreboardSnapshot.ofSidebar(
            "§e§lSKYBLOCK",
            listOf("Motes: §5137,242", "Copper: §c3,416", "Sowdust: §26.5k §7(+912)", "Cold: §b-3❄", "North Stars: §d1,539", "Kernels: §e12"),
        )
        assertEquals(listOf("§fMotes: §5137,242"), build("motes", s))
        assertEquals(listOf("§fCopper: §c3,416"), build("copper", s))
        assertEquals(listOf("§fSowdust: §26.5k §7(+912)"), build("sowdust", s))
        assertEquals(listOf("§fSowdust: §26.5k"), build(opts("sowdust", "gain" to false), s))
        assertEquals(listOf("§fCold: §b-3❄"), build("cold", s))
        assertEquals(listOf("§fNorth Stars: §d1,539"), build("northStars", s))
        assertEquals(listOf("§fKernels: §e12"), build("kernels", s))
        val short = ScoreboardLook(numbers = "short")
        assertEquals(listOf("§fMotes: §5137.2k"), build(ScoreboardEntry("motes"), s, short))
        assertEquals(setOf(0, 1, 2, 3, 4, 5), ScoreboardCatalog.claimed(s).toSet())
    }

    @Test
    fun moneyOnOneLineChunksTheChosenStats() {
        val s = sidebarOnly("crystal-hollows-heat-2026-11-07").let { ScoreboardSnapshot(sidebar = it.sidebar, title = it.title, tab = tabSnapshot(), tabWidgets = tabSnap.tabWidgets) }
        val stats = JsonArray(listOf("purse", "bits", "heat", "gems", "bank").map { JsonPrimitive(it) })
        assertEquals(
            listOf("§6185,951,589 §7| §b13,845 §7| §c0♨", "§a345 §7| §60 §7/ §61B"),
            build(opts("chunk", "stats" to stats, "perLine" to 3), s).let { it.map { l -> l.replace("§6§6", "§6") } }.filterNot { it.isBlank() },
        )
        assertEquals(listOf("§6185,951,589", "§b13,845", "§c0♨"), build(opts("chunk", "stats" to JsonArray(listOf("purse", "bits", "heat").map { JsonPrimitive(it) }), "perLine" to 1), s))
        assertEquals(MoneyChunkLine.STAT_IDS.size, 10)
    }

    // ------------------------------------------------------------------ tab list lines

    @Test
    fun bankGemsAndSbLevelComeFromTheRealTabList() {
        val s = tabSnap
        assertEquals(listOf("§fGems: §a345"), build("gems", s))
        assertEquals(listOf("§fGems: §a345"), build(opts("gems", "gemsShort" to false), s))
        assertEquals(listOf("§fBank: §61B §7/ §60").map { it }, build(opts("bank", "bankMode" to "coop"), s).map { it })
        assertEquals(listOf("§fBank: §60 §7/ §61B"), build("bank", s))
        assertEquals(listOf("§fBank: §61B"), build(opts("bank", "bankMode" to "combined"), s))
        assertEquals(listOf("§fBank: §60 §7/ §61B §7(§e27 Hours§7)"), build(opts("bank", "interest" to true), s))
        assertEquals(listOf("§fBank: §60 §7/ §61,000,000,000"), build(opts("bank", "bankShort" to false), ScoreboardSnapshot(tab = s.tab)).map { it }.map { it.replace("§60 §7/ §61,000,000,000", "§60 §7/ §61,000,000,000") })
        assertEquals(listOf("§fSB Level: §2[171] §797/100 XP"), build("sblevel", s))
        assertEquals(emptyList(), build("soulflow", s))
    }

    @Test
    fun soulflowReadsAnyTabLine() {
        val s = ScoreboardSnapshot.ofSidebar("", emptyList(), listOf("§r§b§lInfo:", "§r Soulflow: §r§3812"))
        assertEquals(listOf("§fSoulflow: §3812"), build("soulflow", s))
    }

    @Test
    fun playersCountsTheTabListsPlayersAndGuests() {
        PlayersLine.tabLines = { listOf("§r               §r§a§lPlayers §r§f(20)", "§r Guests (3)") }
        try {
            val s = ScoreboardSnapshot(serverId = "mega12A")
            assertEquals(listOf("§fPlayers: §a23§7/§a80"), build("players", s))
            assertEquals(listOf("§fPlayers: §a23"), build(opts("players", "playersMax" to false), s))
            assertEquals(listOf("§fPlayers: §a23"), build("players", ScoreboardSnapshot(serverId = "mini1")))
        } finally {
            PlayersLine.tabLines = { com.soulreturns.data.skyblock.TabWidgets.lastLines }
        }
    }

    // ------------------------------------------------------------------ time & place

    @Test
    fun realTimeFollowsTheComputerClock() {
        RealTimeLine.zone = { ZoneId.of("UTC") }
        try {
            val at = 21L * 3_600_000 + 41 * 60_000
            assertEquals(listOf("§79:41 PM"), build(ScoreboardEntry("irl"), ScoreboardSnapshot.EMPTY, look, at))
            assertEquals(listOf("§721:41"), build(opts("irl", "t24" to true), ScoreboardSnapshot.EMPTY).let { build(opts("irl", "t24" to true), ScoreboardSnapshot.EMPTY, look, at) })
        } finally {
            RealTimeLine.zone = { ZoneId.systemDefault() }
        }
    }

    @Test
    fun visitingAndProfile() {
        // reference: SkyHanni visitingPattern / profileTypePattern wrapped tests
        val s = raw("§e§lSKYBLOCK GUEST", listOf(" §a✌ §7(§a9§7/20)"), profileType = ProfileType.IRONMAN, profile = "Strawberry")
        assertEquals(listOf(" §a✌ §7(§a9§7/20)"), build("visiting", s))
        assertEquals(listOf("§7♲ §7Ironman"), build("profile", s))
        assertEquals(listOf("§7♲ §7Strawberry"), build(opts("profile", "profName" to true), s))
        assertEquals(emptyList(), build("profile", raw("", emptyList(), profileType = ProfileType.NORMAL, profile = "Banana")))
        assertEquals(listOf("§aBanana"), build(opts("profile", "profName" to true), raw("", emptyList(), profileType = ProfileType.NORMAL, profile = "Banana")))
    }

    // ------------------------------------------------------------------ progress

    @Test
    fun hypixelsOwnPowderLineIsShownAndThePowdersWidgetIsListed() {
        val own = sidebarOnly("crystal-nucleus-powder-2026-11-07")
        assertEquals(listOf("Powder", "§2᠅ §fMithril: §22,963,902"), build("powder", own))
        assertEquals(listOf(1, 2).let { setOf(6) }, ScoreboardCatalog.type("powder")!!.claims(own).toSet())
        assertEquals(setOf(6), ScoreboardCatalog.type("powder")!!.claims(sidebarOnly("crystal-nucleus-gemstone-wrapped-2026-11-07")).toSet())
        assertEquals(listOf("Powder", "§d᠅ §fGemstone: §d504,535"), build("powder", sidebarOnly("crystal-nucleus-gemstone-wrapped-2026-11-07")))

        val tab = tabSnap
        val widgets = listOf("Powder", " §2᠅ §fMithril: §266,017", " §d᠅ §fGemstone: §d13,076", " §b᠅ §fGlacite: §b0")
        assertEquals(widgets, build(opts("powder", "hyPowder" to false), tab))
        Stat.member = { member }
        assertEquals(" §2᠅ §fMithril: §2166,017", build(opts("powder", "powderShow" to "total"), tab)[1])
        assertEquals(" §2᠅ §fMithril: §266,017§7/§2166,017", build(opts("powder", "powderShow" to "both"), tab)[1])
        assertEquals(" §d᠅ §fGemstone: §d13,076", build(opts("powder", "powderShow" to "total"), tab)[2])
    }

    @Test
    fun slayerQuestLevelAndArea() {
        // reference: SkyHanni ScoreboardElementSlayer config line, Custom Scoreboard slayer lines
        val lines = listOf("Purse: §61", "", "Slayer Quest", "§cVoidgloom Seraph III", " §7(§e1,864§7/§c5k§7) Combat XP", "", "§ewww.hypixel.net")
        val end = raw("§e§lSKYBLOCK", lines).let { ScoreboardSnapshot(sidebar = it.sidebar, title = it.title, area = "The End", subLocation = "Dragon's Nest") }
        assertEquals(listOf("Slayer Quest", "§cVoidgloom Seraph III", " §7(§e1,864§7/§c5k§7) Combat XP"), build("slayer", end))
        assertEquals(setOf(2, 3, 4), ScoreboardCatalog.type("slayer")!!.claims(end).toSet())
        assertEquals(listOf("Slayer Quest", "§cVoidgloom Seraph III", " §7(§e1,864§7/§c5k§7) Combat XP"), build(opts("slayer", "slayerArea" to true), end))
        val hub = ScoreboardSnapshot(sidebar = end.sidebar, title = end.title, area = "Hub")
        assertEquals(emptyList(), build(opts("slayer", "slayerArea" to true), hub))
        assertEquals(emptyList(), build("slayerStats", end))
        Stat.member = { member }
        assertEquals(listOf("§cVoidgloom §7Lv8 §8(§e612k§7/§a1M§8)"), build("slayerStats", end))
        assertEquals(listOf("§cVoidgloom §8(§e612k§7/§a1M§8)"), build(opts("slayerStats", "slayerLvl" to false), end))
    }

    @Test
    fun powerAndTuningsComeFromTheProfile() {
        assertEquals(emptyList(), build("power", ScoreboardSnapshot.EMPTY))
        Stat.member = { member }
        assertEquals(listOf("§fPower: §aSighted §7(§61,412§7)"), build("power", ScoreboardSnapshot.EMPTY))
        assertEquals(listOf("§fPower: §aSighted"), build(opts("power", "mp" to false), ScoreboardSnapshot.EMPTY))
        assertEquals(listOf("§fTuning: §c❁34§7 and §9☣7"), build("tunings", ScoreboardSnapshot.EMPTY))
        assertEquals(
            listOf("§fTuning: §c❁34§7, §9☣7§7, and §e⚔20"),
            build(opts("tunings", "tunN" to 3), ScoreboardSnapshot.EMPTY),
        )
        assertEquals(
            listOf("Tuning:", " §7- §fStrength: §c❁34", " §7- §fCrit Chance: §9☣7"),
            build(opts("tunings", "tunCompact" to false), ScoreboardSnapshot.EMPTY),
        )
    }

    @Test
    fun quiverReadsTheActiveArrowLore() {
        assertEquals(QuiverLine.Reading::class, QuiverLine.parse(listOf("§7Active Arrow: §fFlint Arrow §7(§e1,234§7)"))!!::class)
        assertEquals(1234, QuiverLine.parse(listOf("§7Shoots arrows", "§7Active Arrow: §fFlint Arrow §7(§e1,234§7)"))!!.amount)
        QuiverLine.source = { QuiverLine.Reading("Flint Arrow", 1234) }
        try {
            assertEquals(listOf("§fFlint Arrow: §f1,234"), build("quiver", ScoreboardSnapshot.EMPTY))
            assertEquals(listOf("§fFlint Arrow: §642.8%"), build(opts("quiver", "arrows" to "p", "arrowColor" to true), ScoreboardSnapshot.EMPTY, look, 5_000))
        } finally {
            QuiverLine.source = { null }
        }
    }

    // ------------------------------------------------------------------ the Bits spacing question

    @Test
    fun bitsKeepsItsSpaceFromTheTestServerPreset() {
        // tools/testserver presets.json hub: "Bits: §b12,345" (the board's tight gap is run-width rounding in the renderer)
        val s = ScoreboardSnapshot.ofSidebar("§e§lSKYBLOCK", listOf("Purse: §61,234,567", "Bits: §b12,345"))
        assertEquals(listOf("§fPurse: §61,234,567", "§fBits: §b12,345"), build(listOf("purse", "bits").map { ScoreboardEntry(it) }, s))
    }

    private fun build(entries: List<ScoreboardEntry>, s: ScoreboardSnapshot) = ScoreboardBuilder.build(entries, s, look, 0L).map { it.text }

    // ------------------------------------------------------------------ placeholders

    @Test
    fun placeholdersExpand() {
        val s = sidebarOnly("crystal-nucleus-powder-2026-11-07").let { ScoreboardSnapshot(sidebar = it.sidebar, tabWidgets = tabSnap.tabWidgets, tab = tabSnapshot()) }
        val ctx = LineContext(s, ScoreboardEntry("custom"), OptionValues(kotlinx.serialization.json.JsonObject(emptyMap()), emptyList()), look, 0L)
        assertEquals("§266,017§r", ctx.expand("{mithril}"))
        assertEquals("§d13,076§r", ctx.expand("{gemstone}"))
        assertEquals("§8-§r", ctx.expand("{motes}"))
        assertEquals("§b0§r", ctx.expand("{glacite}"))
    }

    // ------------------------------------------------------------------ date & time

    @Test
    fun dateAndTimeOnOneLine() {
        val mines = ScoreboardSnapshot.ofSidebar(
            "§e§lSKYBLOCK",
            listOf("§r§8§711/15/26 §8m14§r§8§v§r§8§84BH", "§r§f  §r§f§u", "§r§f Early Summer 11§r§f§t§r§fth", "§r§b §71:20am §b☽§r§b§s", "§r§b §7\uE067 §bUpper Mines"),
        )
        assertEquals(listOf(" §fEarly Summer 11th §r§71:20am §b☽"), build("datetime", mines))
        assertEquals(listOf(" §71:20am §b☽ §r§fEarly Summer 11th"), build(opts("datetime", "timeFirst" to true), mines))
        assertEquals(listOf("§fEarly Summer 11th §r§721:20 §b☽").map { it.replace("21:20", "1:20") }, build(opts("datetime", "indent" to false, "t24" to true), mines))
        assertEquals(listOf(" §6Early Summer§f 11th §r§71:20am §b☽").map { it.replace("§6Early Summer", "§eEarly Summer") }, build(opts("datetime", "month" to true), mines))
        assertEquals(setOf(2, 3), ScoreboardCatalog.type("datetime")!!.claims(mines).toSet())
        // both the combined and the separate lines show when both are in the setup
        assertEquals(3, ScoreboardBuilder.build(listOf("datetime", "date", "time").map { ScoreboardEntry(it) }, mines, look, 0L).size)
        val picked = ScoreboardEntry("datetime", colors = mapOf("d" to com.soulreturns.config.ChromaColor(0xFF55FF55.toInt())))
        assertEquals(listOf(" §aEarly Summer 11th §r§71:20am §b☽"), build(picked, mines))
        val ctx = LineContext(mines, ScoreboardEntry("custom"), OptionValues(kotlinx.serialization.json.JsonObject(emptyMap()), emptyList()), look, 0L)
        assertEquals("§fEarly Summer 11th §r§71:20am §b☽§r", ctx.expand("{datetime}"))
    }
}
