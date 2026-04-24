package com.soulreturns.profileviewer.gui

import com.google.gson.JsonObject
import com.soulreturns.profileviewer.model.DungeonsView
import com.soulreturns.profileviewer.model.FloorStats
import com.soulreturns.profileviewer.service.DungeonClassNames
import com.soulreturns.profileviewer.service.DungeonsCalculator
import com.soulreturns.profileviewer.service.LevelInfo
import com.soulreturns.profileviewer.service.masterFloorName
import com.soulreturns.profileviewer.service.normalFloorName
import io.wispforest.owo.ui.component.UIComponents
import io.wispforest.owo.ui.component.LabelComponent
import io.wispforest.owo.ui.container.UIContainers
import io.wispforest.owo.ui.container.FlowLayout
import io.wispforest.owo.ui.container.GridLayout
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
            .gap(6) as FlowLayout

        val dungeonsObj = member.getAsJsonObject("dungeons")
        if (dungeonsObj == null) {
            container.child(UIComponents.label(Text.literal("§7This profile has no dungeon data.")))
            return container
        }

        val view = DungeonsView(dungeonsObj)

        // Section: Catacombs / Master Catacombs levels
        container.child(sectionHeader("Catacombs"))
        val cataLevel = DungeonsCalculator.catacombsLevel(view.catacombsExperience)
        container.child(levelRow("Catacombs", cataLevel, view.catacombsExperience))

        val masterLevel = DungeonsCalculator.masterCatacombsLevel(view.masterCatacombsExperience)
        container.child(levelRow("Master Catacombs", masterLevel, view.masterCatacombsExperience))

        // Section: classes
        container.child(sectionHeader("Classes"))
        val selected = view.selectedClass ?: "—"
        container.child(UIComponents.label(Text.literal("§7Selected: §f${DungeonClassNames.displayName(selected)}")))
        val xpMap = view.classExperience
        for (cls in DungeonClassNames.ORDER) {
            val xp = xpMap[cls] ?: 0.0
            val info = DungeonsCalculator.classLevel(xp)
            container.child(levelRow(DungeonClassNames.displayName(cls), info, xp))
        }

        // Section: floor stats
        container.child(sectionHeader("Catacombs Floors"))
        container.child(floorTable("Catacombs", (0..7).map { it to view.normalFloor(it) }) { normalFloorName(it) })

        container.child(sectionHeader("Master Catacombs Floors"))
        container.child(floorTable("Master", (1..7).map { it to view.masterFloor(it) }) { masterFloorName(it) })

        // Totals
        container.child(sectionHeader("Totals"))
        container.child(UIComponents.label(Text.literal("§7Total runs: §f${view.totalCatacombsCompletions}")))
        view.totalSecrets?.let {
            container.child(UIComponents.label(Text.literal("§7Secrets: §f$it")))
        }

        return container
    }

    private fun sectionHeader(title: String): LabelComponent {
        return UIComponents.label(Text.literal("§e§l$title"))
    }

    private fun levelRow(label: String, info: LevelInfo, xp: Double): FlowLayout {
        val row = UIContainers.horizontalFlow(Sizing.fill(100), Sizing.content())
        row.gap(6)
        row.verticalAlignment(VerticalAlignment.CENTER)
        row.child(UIComponents.label(Text.literal("§f$label §7Lv ")).sizing(Sizing.fixed(150), Sizing.content()))
        val pct = String.format(Locale.ROOT, "%.1f%%", info.progressPct())
        row.child(UIComponents.label(Text.literal("§b${info.level} §7($pct)")).sizing(Sizing.fixed(80), Sizing.content()))
        row.child(UIComponents.label(Text.literal("§7XP: §f${DungeonsCalculator.formatXp(xp)}")))
        return row
    }

    private fun floorTable(branch: String, floors: List<Pair<Int, FloorStats>>, nameFor: (Int) -> String): GridLayout {
        // Columns: Floor | Completions | Best Score | Fastest | S | S+
        val rows = floors.size + 1
        val grid = UIContainers.grid(Sizing.fill(100), Sizing.content(), rows, 6)
        grid.padding(Insets.of(4))
        grid.surface(Surface.DARK_PANEL)

        listOf("Floor", "Runs", "Score", "Fastest", "S", "S+").forEachIndexed { i, h ->
            grid.child(UIComponents.label(Text.literal("§e§l$h")), 0, i)
        }
        floors.forEachIndexed { rowIdx, (tier, stats) ->
            val r = rowIdx + 1
            grid.child(UIComponents.label(Text.literal("§f${nameFor(tier)}")), r, 0)
            grid.child(UIComponents.label(Text.literal("§f${stats.completions}")), r, 1)
            grid.child(UIComponents.label(Text.literal("§f${stats.bestScore}")), r, 2)
            grid.child(UIComponents.label(Text.literal("§f${DungeonsCalculator.formatTime(stats.fastestTimeMs)}")), r, 3)
            grid.child(UIComponents.label(Text.literal("§f${DungeonsCalculator.formatTime(stats.fastestSMs)}")), r, 4)
            grid.child(UIComponents.label(Text.literal("§f${DungeonsCalculator.formatTime(stats.fastestSPlusMs)}")), r, 5)
        }
        return grid
    }
}
