package com.soulreturns.profileviewer.gui

import com.google.gson.JsonObject
import com.soulreturns.config.gui.Theme
import com.soulreturns.profileviewer.model.DungeonsView
import com.soulreturns.profileviewer.model.FloorStats
import com.soulreturns.profileviewer.service.DungeonClassNames
import com.soulreturns.profileviewer.service.DungeonsCalculator
import com.soulreturns.profileviewer.service.LevelInfo
import com.soulreturns.profileviewer.service.masterFloorName
import com.soulreturns.profileviewer.service.normalFloorName
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.core.HorizontalAlignment
import io.wispforest.owo.ui.core.Insets
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.Surface
import io.wispforest.owo.ui.core.VerticalAlignment
import net.minecraft.text.Text
import java.util.Locale

object DungeonsTab {

    fun build(member: JsonObject): FlowLayout {
        val container = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        container.gap(8)

        val dungeonsObj = member.getAsJsonObject("dungeons")
        if (dungeonsObj == null) {
            container.child(
                UIComponents.label(Text.literal("This profile has no dungeon data."))
                    .color(Theme.color(Theme.TEXT_DIM))
            )
            return container
        }

        val view = DungeonsView(dungeonsObj)

        section(container, "Catacombs") {
            it.child(levelRow("Catacombs", DungeonsCalculator.catacombsLevel(view.catacombsExperience), view.catacombsExperience, cap = 50))
            it.child(levelRow("Master Catacombs", DungeonsCalculator.masterCatacombsLevel(view.masterCatacombsExperience), view.masterCatacombsExperience, cap = 7))
        }

        section(container, "Classes") {
            val selected = view.selectedClass?.let { c -> DungeonClassNames.displayName(c) } ?: "—"
            val selRow = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
            selRow.gap(8)
            selRow.child(UIComponents.label(Text.literal("Selected")).color(Theme.color(Theme.TEXT_DIM)).sizing(Sizing.fixed(130), Sizing.content()))
            selRow.child(UIComponents.label(Text.literal(selected)).color(Theme.color(Theme.TEXT)))
            it.child(selRow)
            it.child(rowDivider())
            for (cls in DungeonClassNames.ORDER) {
                val xp = view.classExperience[cls] ?: 0.0
                it.child(levelRow(DungeonClassNames.displayName(cls), DungeonsCalculator.classLevel(xp), xp, cap = 50))
            }
        }

        section(container, "Catacombs Floors") {
            it.child(floorTable((0..7).map { i -> i to view.normalFloor(i) }) { i -> normalFloorName(i) })
        }

        section(container, "Master Catacombs Floors") {
            it.child(floorTable((1..7).map { i -> i to view.masterFloor(i) }) { i -> masterFloorName(i) })
        }

        section(container, "Totals") {
            statRow(it, "Total runs", view.totalCatacombsCompletions.toString())
            view.totalSecrets?.let { s -> statRow(it, "Total secrets", s.toString()) }
        }

        return container
    }

    private fun section(parent: FlowLayout, label: String, block: (FlowLayout) -> Unit) {
        val lbl = UIComponents.label(Text.literal(label)).color(Theme.color(Theme.TEXT_DIM))
        lbl.margins(Insets.of(4, 0, 0, 6))
        parent.child(lbl)

        val card = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        card.surface(Theme.panelInsetSurface)
        card.padding(Insets.of(10))
        card.gap(6)
        block(card)
        parent.child(card)
    }

    private fun levelRow(label: String, info: LevelInfo, xp: Double, cap: Int): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.gap(10)
        row.verticalAlignment(VerticalAlignment.CENTER)

        val lvlText = if (info.level >= cap) "MAX" else info.level.toString()
        row.child(UIComponents.label(Text.literal(label)).color(Theme.color(Theme.TEXT)).sizing(Sizing.fixed(140), Sizing.content()))
        row.child(UIComponents.label(Text.literal("Lv $lvlText")).color(Theme.color(Theme.ACCENT)).sizing(Sizing.fixed(52), Sizing.content()))
        row.child(xpBar(info.progress))
        row.child(UIComponents.label(Text.literal(DungeonsCalculator.formatXp(xp))).color(Theme.color(Theme.TEXT_DIM)))

        return row
    }

    private fun statRow(parent: FlowLayout, label: String, value: String) {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.gap(10)
        row.child(UIComponents.label(Text.literal(label)).color(Theme.color(Theme.TEXT_DIM)).sizing(Sizing.fixed(130), Sizing.content()))
        row.child(UIComponents.label(Text.literal(value)).color(Theme.color(Theme.TEXT)))
        parent.child(row)
    }

    private fun floorTable(floors: List<Pair<Int, FloorStats>>, nameFor: (Int) -> String): FlowLayout {
        val table = UIContainers.verticalFlow(Sizing.fill(100), Sizing.content())
        table.gap(3)
        table.child(floorRow("Floor", "Runs", "Best", "Fastest", "S", "S+", isHeader = true))
        for ((tier, stats) in floors) {
            table.child(floorRow(
                nameFor(tier),
                if (stats.completions > 0) stats.completions.toString() else "—",
                if (stats.bestScore > 0) stats.bestScore.toString() else "—",
                DungeonsCalculator.formatTime(stats.fastestTimeMs),
                DungeonsCalculator.formatTime(stats.fastestSMs),
                DungeonsCalculator.formatTime(stats.fastestSPlusMs),
                isHeader = false,
            ))
        }
        return table
    }

    private fun floorRow(c0: String, c1: String, c2: String, c3: String, c4: String, c5: String, isHeader: Boolean): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.gap(4)
        row.verticalAlignment(VerticalAlignment.CENTER)
        val color = if (isHeader) Theme.TEXT_DIM else Theme.TEXT
        fun cell(text: String, w: Int) = UIComponents.label(Text.literal(text)).color(Theme.color(color)).sizing(Sizing.fixed(w), Sizing.content())
        row.child(cell(c0, 32))
        row.child(cell(c1, 42))
        row.child(cell(c2, 46))
        row.child(cell(c3, 62))
        row.child(cell(c4, 62))
        row.child(cell(c5, 62))
        return row
    }

    private fun xpBar(progress: Double): FlowLayout {
        val p = progress.coerceIn(0.0, 1.0)
        val bar = UIContainers.horizontalFlow(Sizing.fixed(130), Sizing.fixed(6))
        bar.surface(Surface { ctx, c ->
            DrawContextRenderer.roundedFill(ctx, c.x(), c.y(), c.x() + c.width(), c.y() + c.height(), Theme.PANEL_HOVER, 3f)
            val fillW = (c.width() * p).toInt().coerceAtLeast(if (p > 0.01) 6 else 0)
            if (fillW > 0) DrawContextRenderer.roundedFill(ctx, c.x(), c.y(), c.x() + fillW, c.y() + c.height(), Theme.ACCENT, 3f)
        })
        return bar
    }

    private fun rowDivider(): FlowLayout {
        val d = UIContainers.verticalFlow(Sizing.fill(100), Sizing.fixed(1))
        d.surface(Surface.flat(Theme.SEPARATOR))
        d.margins(Insets.vertical(2))
        return d
    }
}
