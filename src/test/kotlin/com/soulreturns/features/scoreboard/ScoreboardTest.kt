package com.soulreturns.features.scoreboard

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ConfigCodec
import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.config.ScoreboardSetup
import com.soulreturns.config.SoulConfig
import com.soulreturns.features.scoreboard.lines.NewLinesLine
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

class ScoreboardTest {
    private class Capture(val name: String, val title: String, val lines: List<String>)

    /** Real sidebars from dev-bridge snapshots (2026-11-15/16). */
    private val captures: List<Capture> =
        Json.parseToJsonElement(javaClass.getResource("/scoreboard/sidebars.json")!!.readText()).jsonObject["sidebars"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            Capture(o["name"]!!.jsonPrimitive.content, o["title"]!!.jsonPrimitive.content, o["lines"]!!.jsonArray.map { it.jsonPrimitive.content })
        }

    private fun capture(name: String) = captures.first { it.name == name }

    private fun snapshot(c: Capture, area: String? = "Dwarven Mines", mode: String? = "mining_3") =
        ScoreboardSnapshot.ofSidebar(c.title, c.lines).let { s ->
            ScoreboardSnapshot(title = s.title, sidebar = s.sidebar, area = area, mode = mode, serverId = "fallback")
        }

    private val look = ScoreboardLook()

    private fun build(entries: List<ScoreboardEntry>, s: ScoreboardSnapshot, l: ScoreboardLook = look) =
        ScoreboardBuilder.build(entries, s, l, 0L).map { it.text }

    private fun entries(vararg ids: String) = ids.map { ScoreboardEntry(it) }

    // ------------------------------------------------------------------ sidebar parser

    @Test
    fun theFixedBlockIsFoundInEveryCapture() {
        assertEquals(4, captures.size)
        for (c in captures) {
            val f = SidebarParser.parse(c.lines.map(SourceLine::of))
            assertEquals(0, f.dateIndex, c.name)
            assertEquals(2, f.skyblockDateIndex, c.name)
            assertEquals(3, f.timeIndex, c.name)
            assertEquals(4, f.areaIndex, c.name)
            assertEquals(6, f.purseIndex, c.name)
            assertEquals(7, f.bitsIndex, c.name)
            assertEquals(c.lines.lastIndex, f.footerIndex, c.name)
            assertTrue(f.purse != null && f.bits != null && f.serverId != null && f.time != null, c.name)
        }
    }

    @Test
    fun valuesComeOutOfHypixelsSplitLines() {
        val f = SidebarParser.parse(capture("upper-mines-2026-11-15").lines.map(SourceLine::of))
        assertEquals("11/15/26", f.date)
        assertEquals("m144BH", f.serverId)
        assertEquals("1:20am", f.time)
        assertEquals(4_464_676.0, f.purse)
        assertEquals("Purse", f.purseLabel)
        assertEquals("+5", f.purseGain)
        assertEquals(1_034.0, f.bits)
        assertNull(f.bitsGain)
    }

    @Test
    fun normalizeDropsHypixelsLineKeys() {
        assertEquals("§b §7\uE067 §bUpper Mines", LegacyText.normalize("§r§b §7\uE067 §bUpper Min§r§b§q§r§b§bes"))
        assertEquals("§fPurse: §64,464,676 §e(+5)", LegacyText.normalize("§r§fPurse: §64,464,6§r§6§j§r§6§676 §e(+5)"))
        assertEquals("§711/15/26 §8m144BH", LegacyText.normalize("§r§8§711/15/26 §8m14§r§8§v§r§8§84BH"))
        assertEquals("Upper Mines", LegacyText.strip("§b§#ff00aaUpper §zMines"))
    }

    @Test
    fun unknownSidebarLinesAreNewLines() {
        val forge = snapshot(capture("forge-2026-11-16"))
        // `Nearby Players:` was the one new line here until the Mining events line (lines/events) claimed it.
        assertTrue(NewLinesLine.unknown(forge).isEmpty())
        val mystery = ScoreboardSnapshot.ofSidebar("", capture("forge-2026-11-16").lines.let { it.dropLast(1) + "§7Mystery: §a1" + it.last() })
        assertEquals(listOf("Mystery: 1"), NewLinesLine.unknown(mystery).map { it.text })
        assertTrue(NewLinesLine.unknown(snapshot(capture("upper-mines-2026-11-15"))).isEmpty())
    }

    @Test
    fun tabWidgetsSplitAndDrawAsHypixelDoes() {
        val tab = Json.parseToJsonElement(javaClass.getResource("/scoreboard/tab-dwarven-2026-11-15.json")!!.readText()).jsonObject["lines"]!!.jsonArray.map { it.jsonPrimitive.content }
        val s = ScoreboardSnapshot.ofSidebar("", emptyList(), tab)
        assertEquals(listOf("Area", "Profile", "Pet", "Forges", "Commissions", "Powders", "Pickaxe Ability", "Bestiary"), s.tabWidgets.map { it.name })
        assertEquals(
            listOf("§9§lCommissions:", " §fGoblin Raid Slayer: §c0%", " §fRaffle: §c0%", " §fRoyal Mines Mithril: §c0%", " §fUpper Mines Titanium: §aDONE"),
            build(entries("tComms"), s),
        )
        val tinted = ScoreboardEntry("tPowders", colors = mapOf("tint" to ChromaColor(0xFFFFFFFF.toInt())))
        assertEquals(listOf("§fPowders:", " §fMithril: 66,017", " §fGemstone: 13,076", " §fGlacite: 0"), build(listOf(tinted), s))
    }

    // ------------------------------------------------------------------ building and folding

    @Test
    fun theDefaultSetupDrawsABelievableBoard() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        assertEquals(
            listOf(
                "§z§lSKYBLOCK",
                "§711/15/26 §8m144BH",
                "§f Early Summer 11th",
                "§b §71:20am §b☽",
                "§b §7\uE067 §bUpper Mines",
                "",
                "§7㋖ §aDwarven Mines",
                "§fPurse: §64,464,676 §e(+5)",
                "§fBits: §b1,034",
                "",
                "§ewww.hypixel.net",
            ),
            build(ScoreboardConfig().main.entries, s),
        )
    }

    @Test
    fun gapsFoldAndDropAtTheEnds() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        assertEquals(listOf("§fBits: §b1,034"), build(entries("sep", "sep", "bits", "sep", "sep"), s))
        assertEquals(listOf("§fBits: §b1,034", "", "§fPurse: §64,464,676 §e(+5)"), build(entries("bits", "sep", "unknownType", "sep", "purse", "sep"), s))
        // A line with no value between two gaps leaves one gap.
        assertEquals(listOf("§fBits: §b1,034", "", "§fPurse: §64,464,676 §e(+5)"), build(entries("bits", "sep", "objective", "sep", "purse"), s))
    }

    @Test
    fun headerAndFooterArePinnedAndHiddenEntriesSkipped() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        val list = listOf(ScoreboardEntry("footer"), ScoreboardEntry("bits", hidden = true), ScoreboardEntry("purse"), ScoreboardEntry("header"))
        assertEquals(listOf("§z§lSKYBLOCK", "§fPurse: §64,464,676 §e(+5)", "", "§ewww.hypixel.net"), build(list, s))
    }

    /** Hidden (flag or the Header/Footer "Hidden" mode) draws nothing and still claims its sidebar line. */
    @Test
    fun hiddenLinesStayClaimed() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        val off = kotlinx.serialization.json.JsonObject(mapOf("show" to kotlinx.serialization.json.JsonPrimitive("off")))
        val flagged = listOf(ScoreboardEntry("header", hidden = true), ScoreboardEntry("purse", hidden = true), ScoreboardEntry("extra"), ScoreboardEntry("footer", hidden = true))
        val moded = listOf(ScoreboardEntry("header", options = off), ScoreboardEntry("purse", hidden = true), ScoreboardEntry("extra"), ScoreboardEntry("footer", options = off))
        for (list in listOf(flagged, moded)) {
            val text = build(list, s).map(LegacyText::strip)
            assertTrue(text.none { "hypixel.net" in it || "Purse" in it || "SKYBLOCK" in it }, text.joinToString("\n"))
        }
    }

    @Test
    fun pickedColorsAndChromaParts() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        val gold = ScoreboardEntry("bits", colors = mapOf("l" to ChromaColor(0xFFFFAA00.toInt()), "v" to ChromaColor(0xFF123456.toInt())))
        assertEquals(listOf("§fBits: §b1,034".replace("§fBits:", "§f§6Bits:§r").replace("§b1", "§#1234561")), build(listOf(gold), s))
        val chroma = build(listOf(ScoreboardEntry("bits")), s, look.copy(chromaOn = listOf("labels")))
        assertEquals(listOf("§f§zBits:§r §b1,034"), chroma)
    }

    @Test
    fun shortNumbersAndNumberStyles() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        assertEquals(listOf("§fPurse: §64.46M §e(+5)"), build(entries("purse"), s, look.copy(numbers = "short")))
        assertEquals(listOf("§b1,034 §fBits"), build(entries("bits"), s, look.copy(numberStyle = "cnrt")))
        assertEquals("1.19B", ScoreboardFormat.number(1_193_004_241.0, true))
        assertEquals("13.8k", ScoreboardFormat.number(13_845.0, true))
        assertEquals("1,193,004,241", ScoreboardFormat.number(1_193_004_241.0, false))
    }

    // ------------------------------------------------------------------ placeholders

    @Test
    fun placeholdersExpandAndTheStyleResumes() {
        val s = snapshot(capture("upper-mines-2026-11-15"))
        val custom = ScoreboardEntry("custom", options = JsonObject(mapOf("text" to JsonPrimitive("§7Goal: {purse} left {nope} on {server}"))))
        assertEquals(listOf("§7Goal: §64,464,676§7 left {nope} on §8m144BH§7"), build(listOf(custom), s))
        val missing = ScoreboardEntry("custom", options = JsonObject(mapOf("text" to JsonPrimitive("{bank}"))))
        assertEquals(listOf("§8-§r"), build(listOf(missing), s))
    }

    // ------------------------------------------------------------------ config

    @Test
    fun unknownEntriesOptionsAndActivitiesSurviveSaveAndLoad() {
        val future = ScoreboardEntry("lineFromANewerVersion", options = JsonObject(mapOf("mode" to JsonPrimitive("x"), "n" to JsonPrimitive(3))), colors = mapOf("q" to ChromaColor(0xFF00FF00.toInt(), chroma = true)))
        val purse = ScoreboardEntry("purse", options = JsonObject(mapOf("someNewOption" to JsonPrimitive(true))))
        val cfg = SoulConfig(
            scoreboard = ScoreboardConfig(
                enabled = true,
                main = ScoreboardSetup(listOf(ScoreboardEntry("header"), future, purse, ScoreboardEntry("footer"))),
                activities = mapOf("someFutureActivity" to ScoreboardSetup(listOf(future))),
                look = ScoreboardLook(numbers = "aNewStyle"),
            ),
        )
        val decoded = ConfigCodec.decode(ConfigCodec.encode(cfg))
        assertEquals(emptyList(), decoded.notes)
        assertEquals(cfg.scoreboard, decoded.config.scoreboard)
        // And it draws: the unknown entry is skipped, the unknown number style reads as long.
        val s = snapshot(capture("upper-mines-2026-11-15"))
        assertEquals(listOf("§z§lSKYBLOCK", "§fPurse: §64,464,676 §e(+5)", "", "§ewww.hypixel.net"), build(decoded.config.scoreboard.main.entries, s, decoded.config.scoreboard.look))
    }

    // ------------------------------------------------------------------ activities

    @Test
    fun activitiesFallBackToTheMainSetup() {
        val dungeon = ScoreboardSetup(ScoreboardActivities.startingSetup(ScoreboardConfig(), ScoreboardActivities.DUNGEONS).entries)
        val on = ScoreboardConfig(perActivity = true, activities = mapOf(ScoreboardActivities.DUNGEONS to dungeon))
        val sidebar = capture("upper-mines-2026-11-15")
        assertEquals(dungeon, ScoreboardActivities.setup(on, snapshot(sidebar, area = "Dungeon", mode = "dungeon")))
        assertEquals(on.main, ScoreboardActivities.setup(on, snapshot(sidebar, area = "Dungeon Hub", mode = "dungeon_hub")))
        assertEquals(on.main, ScoreboardActivities.setup(on, snapshot(sidebar, area = "Some Island Added Next Year", mode = "new_island")))
        assertEquals(on.main, ScoreboardActivities.setup(on.copy(perActivity = false), snapshot(sidebar, area = "Dungeon", mode = "dungeon")))
        // Kuudra has no setup: main, though Kuudra is going on.
        assertEquals("kuudra", ScoreboardActivities.current(snapshot(sidebar, area = "Kuudra", mode = "kuudra"))?.id)
        assertEquals(on.main, ScoreboardActivities.setup(on, snapshot(sidebar, area = "Kuudra", mode = "kuudra")))
    }
}
