package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/**
 * A tab list widget, shown as Hypixel draws it (header and body lines), optionally tinted. One class
 * for every widget: the catalog lists one instance per widget name ([widget], matched case-insensitively,
 * never validated: a widget Hypixel renames simply shows nothing).
 */
class TabWidgetLine(
    id: String,
    val widget: String,
    name: String,
    description: String,
    example: List<String>,
    override val where: String? = null,
) : LineType(id, ScoreboardCatalog.TAB_LIST, name, description, example) {
    override val tag = "tab list"

    override val colors = LineColors.Tint

    override fun lines(ctx: LineContext): List<String> = ctx.sources.widget(widget)?.lines?.map { it.legacy } ?: emptyList()
}
