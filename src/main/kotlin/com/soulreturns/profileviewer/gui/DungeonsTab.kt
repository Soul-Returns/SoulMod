package com.soulreturns.profileviewer.gui

import com.google.gson.JsonObject
import com.soulreturns.profileviewer.model.DungeonsView
import com.soulreturns.profileviewer.model.FloorStats
import com.soulreturns.profileviewer.service.DungeonClassNames
import com.soulreturns.profileviewer.service.DungeonsCalculator
import com.soulreturns.profileviewer.service.LevelInfo
import com.soulreturns.profileviewer.service.masterFloorName
import com.soulreturns.profileviewer.service.normalFloorName
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.height
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.ProgressBar
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Spacer
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.theme.SoulTheme

/**
 * Dungeons tab content for the Profile Viewer. Pure presentation — all parsing /
 * level / time math lives in [DungeonsView], [DungeonsCalculator] and the floor-name
 * helpers under `profileviewer/service/`. This composable just builds the layout.
 */
@SoulComposable
fun DungeonsTabContent(member: JsonObject) {
    val dungeons = member.getAsJsonObject("dungeons")
    if (dungeons == null) {
        Text(
            text = "This profile has no dungeon data.",
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textDim,
            font = SoulTheme.typography.body.font,
        )
        return
    }
    val view = DungeonsView(dungeons)

    Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 8f) {
        Section("Catacombs") {
            LevelRow(
                label = "Catacombs",
                info = DungeonsCalculator.catacombsLevel(view.catacombsExperience),
                xp = view.catacombsExperience,
                cap = 50,
            )
            LevelRow(
                label = "Master Catacombs",
                info = DungeonsCalculator.masterCatacombsLevel(view.masterCatacombsExperience),
                xp = view.masterCatacombsExperience,
                cap = 7,
            )
        }
        Section("Classes") {
            val selected = view.selectedClass?.let { c -> DungeonClassNames.displayName(c) } ?: "—"
            Row(
                modifier = SoulModifier.Empty.fillMaxWidth(),
                gap = 10f,
                verticalAlignment = VerticalAlignment.Center,
            ) {
                LabelCell("Selected", widthPx = 130f, color = SoulTheme.colors.textDim)
                Text(
                    text = selected,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.body.font,
                )
            }
            RowDivider()
            for (cls in DungeonClassNames.ORDER) {
                val xp = view.classExperience[cls] ?: 0.0
                LevelRow(
                    label = DungeonClassNames.displayName(cls),
                    info = DungeonsCalculator.classLevel(xp),
                    xp = xp,
                    cap = 50,
                )
            }
        }
        Section("Catacombs Floors") {
            FloorTable((0..7).map { i -> i to view.normalFloor(i) }) { i -> normalFloorName(i) }
        }
        Section("Master Catacombs Floors") {
            FloorTable((1..7).map { i -> i to view.masterFloor(i) }) { i -> masterFloorName(i) }
        }
        Section("Totals") {
            StatRow("Total runs", view.totalCatacombsCompletions.toString())
            view.totalSecrets?.let { StatRow("Total secrets", it.toString()) }
        }
    }
}

@SoulComposable
private fun Section(
    label: String,
    content: @SoulComposable () -> Unit,
) {
    Text(
        text = label,
        size = SoulTheme.typography.body.size,
        color = SoulTheme.colors.textDim,
        font = SoulTheme.typography.body.font,
        modifier = SoulModifier.Empty.padding(left = 4f, bottom = 6f),
    )
    Surface(
        modifier = SoulModifier.Empty.fillMaxWidth(),
        color = SoulTheme.colors.panelInset,
        radius = SoulTheme.dimens.radiusMedium,
        padding = 10f,
    ) {
        Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
            content()
        }
    }
}

@SoulComposable
private fun LevelRow(
    label: String,
    info: LevelInfo,
    xp: Double,
    cap: Int,
) {
    val lvlText = if (info.level >= cap) "MAX" else info.level.toString()
    Row(
        modifier = SoulModifier.Empty.fillMaxWidth(),
        gap = 10f,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        LabelCell(label, widthPx = 140f, color = SoulTheme.colors.text)
        LabelCell("Lv $lvlText", widthPx = 52f, color = SoulTheme.colors.accent)
        Box(modifier = SoulModifier.Empty.width(130f).height(6f)) {
            ProgressBar(
                progress = info.progress.toFloat(),
                modifier = SoulModifier.Empty.fillMaxWidth(),
                height = 6f,
            )
        }
        Text(
            text = DungeonsCalculator.formatXp(xp),
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.textDim,
            font = SoulTheme.typography.body.font,
        )
    }
}

@SoulComposable
private fun StatRow(
    label: String,
    value: String,
) {
    Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 10f) {
        LabelCell(label, widthPx = 130f, color = SoulTheme.colors.textDim)
        Text(
            text = value,
            size = SoulTheme.typography.body.size,
            color = SoulTheme.colors.text,
            font = SoulTheme.typography.body.font,
        )
    }
}

@SoulComposable
private fun FloorTable(
    floors: List<Pair<Int, FloorStats>>,
    nameFor: (Int) -> String,
) {
    Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 3f) {
        FloorRow("Floor", "Runs", "Best", "Fastest", "S", "S+", isHeader = true)
        for ((tier, stats) in floors) {
            FloorRow(
                c0 = nameFor(tier),
                c1 = if (stats.completions > 0) stats.completions.toString() else "—",
                c2 = if (stats.bestScore > 0) stats.bestScore.toString() else "—",
                c3 = DungeonsCalculator.formatTime(stats.fastestTimeMs),
                c4 = DungeonsCalculator.formatTime(stats.fastestSMs),
                c5 = DungeonsCalculator.formatTime(stats.fastestSPlusMs),
                isHeader = false,
            )
        }
    }
}

@SoulComposable
private fun FloorRow(
    c0: String,
    c1: String,
    c2: String,
    c3: String,
    c4: String,
    c5: String,
    isHeader: Boolean,
) {
    val color = if (isHeader) SoulTheme.colors.textDim else SoulTheme.colors.text
    Row(
        modifier = SoulModifier.Empty.fillMaxWidth(),
        gap = 4f,
        verticalAlignment = VerticalAlignment.Center,
    ) {
        LabelCell(c0, widthPx = 32f, color = color)
        LabelCell(c1, widthPx = 42f, color = color)
        LabelCell(c2, widthPx = 46f, color = color)
        LabelCell(c3, widthPx = 62f, color = color)
        LabelCell(c4, widthPx = 62f, color = color)
        LabelCell(c5, widthPx = 62f, color = color)
    }
}

@SoulComposable
private fun LabelCell(
    text: String,
    widthPx: Float,
    color: Int,
) {
    Box(modifier = SoulModifier.Empty.width(widthPx)) {
        Text(
            text = text,
            size = SoulTheme.typography.body.size,
            color = color,
            font = SoulTheme.typography.body.font,
        )
    }
}

@SoulComposable
private fun RowDivider() {
    Box(
        modifier =
            SoulModifier.Empty
                .fillMaxWidth()
                .height(1f)
                .background(color = SoulTheme.colors.separator, radius = 0f),
    ) { Spacer() }
}
