package com.soulreturns.features.scoreboard

import com.soulreturns.config.ScoreboardLook
import com.soulreturns.features.scoreboard.importer.CustomScoreboardConverter
import com.soulreturns.features.scoreboard.importer.MiniMessage
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ScoreboardImportTest {
    @kotlin.test.BeforeTest
    fun resetGainTrackers() = com.soulreturns.features.scoreboard.lines.GainTracker.resetAll()

    private val real = javaClass.getResource("/scoreboard/import/config.jsonc")!!.readText()

    @Test
    fun ownersFileConverts() {
        val c = assertNotNull(CustomScoreboardConverter.convert(real, ScoreboardLook()))
        val ids = c.setup.entries.map { it.type }
        assertEquals(listOf("header", "server", "date", "time", "players", "area", "profile", "visiting", "sep", "sep"), ids.take(10))
        assertEquals("footer", ids[ids.size - 2])
        assertEquals("extra", ids.last())
        val look = c.look
        assertEquals(31, look.backgroundOpacity)
        assertEquals(10, look.cornerRadius)
        assertEquals(5, look.padding)
        assertEquals("long", look.numbers)
        assertEquals("tcn", look.numberStyle)
        assertEquals("left", look.align)
        assertTrue(!c.keepVanilla)
        val header = c.setup.entries.first().options
        assertEquals("§6§lSKYBLOCK", (header["text"] as JsonPrimitive).content)
        assertEquals("center", (header["align"] as JsonPrimitive).content)
        val events = c.setup.entries.first { it.type == "events" }.options["events"] as JsonArray
        assertEquals(24, events.size)
        assertEquals("evVoting", (events[0] as JsonPrimitive).content)
    }

    @Test
    fun unknownElementsAreSkippedAndReported() {
        val text = """{"customization":{"appearance":["TITLE","PURSE","FUTURE_THING","FOOTER"],"events":["VOTING","NEW_EVENT"]}}"""
        val c = assertNotNull(CustomScoreboardConverter.convert(text, ScoreboardLook()))
        assertEquals(listOf("header", "purse", "footer"), c.setup.entries.map { it.type })
        assertTrue(c.result.skipped.any { it.contains("Future Thing") })
        assertTrue(c.result.skipped.any { it.contains("New Event") })
    }

    @Test
    fun corruptOrForeignIsNotAvailable() {
        assertNull(CustomScoreboardConverter.convert("{ not json", ScoreboardLook()))
        assertNull(CustomScoreboardConverter.convert("", ScoreboardLook()))
        assertNull(CustomScoreboardConverter.convert("""{"a":1}""", ScoreboardLook()))
    }

    @Test
    fun miniMessage() {
        assertEquals("§6§lSKYBLOCK", MiniMessage.toLegacy("<gold><bold>SKYBLOCK</bold></gold>"))
        assertEquals("§#112233x§ay", MiniMessage.toLegacy("<#112233>x</#112233><green>y"))
    }
}
