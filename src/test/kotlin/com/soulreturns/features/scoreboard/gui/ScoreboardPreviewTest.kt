package com.soulreturns.features.scoreboard.gui

import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardActivities
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.lines.CustomTextLine
import com.soulreturns.features.scoreboard.lines.EmptyLine
import com.soulreturns.features.scoreboard.lines.NewLinesLine
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScoreboardPreviewTest {
    private val now = 1_764_000_000_000L

    /** Every catalogue line that shows everywhere it has a value shows something in every preview place. */
    @Test
    fun everyLineShowsWhereItWould() {
        val c = ScoreboardConfig()
        val places = listOf<String?>(null) + ScoreboardActivities.all.map { it.id }
        val types = ScoreboardCatalog.lines.filter { it.pinned == LineType.PIN_NONE && it !== EmptyLine && it !== NewLinesLine }
        val entries = types.map { it.newEntry() }
        for (place in places) {
            val r = ScoreboardPreview.build(c, entries, place, now)
            val p = ScoreboardPreview.place(place)
            for ((i, t) in types.withIndex()) {
                if (!ScoreboardPreview.showsIn(t, p)) continue
                assertTrue(i in r.here, "${t.id} shows nothing in the ${p.label} preview")
            }
        }
    }

    @Test
    fun whereMatchesWholePlaces() {
        val dungeon = ScoreboardPreview.place(ScoreboardActivities.DUNGEONS)
        val objective = ScoreboardCatalog.lines.first { it.where == "Outside dungeons" }
        assertFalse(ScoreboardPreview.showsIn(objective, dungeon), "\"Outside dungeons\" must not match the Dungeons place")
        assertTrue(ScoreboardPreview.showsIn(objective, ScoreboardPreview.place(null)))
    }

    @Test
    fun customTextValuesShowExamples() {
        val e = CustomTextLine.newEntry().copy(options = JsonObject(mapOf(CustomTextLine.TEXT to JsonPrimitive("§7Goal: {purse} left"))))
        val r = ScoreboardPreview.build(ScoreboardConfig(), listOf(e), null, now)
        val text = LegacyText.strip(r.lines.single().text)
        assertTrue(text.startsWith("Goal: "), text)
        assertFalse("{purse}" in text || text.contains(" - left"), text)
    }

    @Test
    fun hiddenLinesStayInTheHereSet() {
        val c = ScoreboardConfig()
        val entries = c.main.entries.map { if (it.type == "purse") it.copy(hidden = true) else it }
        val r = ScoreboardPreview.build(c, entries, null, now)
        val purse = entries.indexOfFirst { it.type == "purse" }
        assertTrue(purse in r.here)
        assertEquals(0, r.lines.count { it.entry == purse })
    }

    @Test
    fun dungeonPreviewShowsTheRunLines() {
        val c = ScoreboardConfig()
        val entries = ScoreboardActivities.startingSetup(c, ScoreboardActivities.DUNGEONS).entries
        val text = ScoreboardPreview.build(c, entries, ScoreboardActivities.DUNGEONS, now).lines.map { LegacyText.strip(it.text) }
        assertTrue(text.any { it.startsWith("Time Elapsed") }, text.joinToString("\n"))
    }
}
