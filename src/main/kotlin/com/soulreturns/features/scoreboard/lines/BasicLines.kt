package com.soulreturns.features.scoreboard.lines

import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineText
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** A gap. Two in a row fold into one; gaps at the top or bottom are dropped. */
object EmptyLine : LineType(
    "sep", ScoreboardCatalog.BASICS, "Empty line",
    "A gap. Two gaps in a row fold into one.",
    listOf(""),
) {
    override val repeatable = true

    override fun lines(ctx: LineContext): List<String> = GAP

    private val GAP = listOf("")
}

/** Your own text with colors and `{value}` placeholders ([com.soulreturns.features.scoreboard.ScoreboardValues]). */
object CustomTextLine : LineType(
    "custom", ScoreboardCatalog.BASICS, "Custom text",
    "Your own text with colors and live values. Add as many as you like.",
    listOf("§d✦ §fYour text here"),
) {
    const val TEXT = "text"

    override val tag = "Soul"
    override val repeatable = true

    override val options =
        listOf(
            LineText(TEXT, "Text", "§fNew line", rich = true, values = true, width = 330),
            LineOptions.align(),
        )

    override fun lines(ctx: LineContext): List<String> = listOf(ctx.expand(ctx.options.string(TEXT)))

    /** The picker's new entry carries its text so it is visible at once. */
    override fun newEntry(): ScoreboardEntry = ScoreboardEntry(id, options = JsonObject(mapOf(TEXT to JsonPrimitive("§fNew line"))))
}
