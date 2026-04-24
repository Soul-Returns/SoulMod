package com.soulreturns.profileviewer.gui.tabs

import com.soulreturns.profileviewer.gui.ProfileViewerTab
import com.soulreturns.profileviewer.gui.TabArea
import com.soulreturns.profileviewer.model.DungeonsView
import com.soulreturns.profileviewer.model.SkyblockProfile
import com.soulreturns.profileviewer.service.DungeonClassNames
import com.soulreturns.profileviewer.service.DungeonsCalculator
import com.soulreturns.profileviewer.service.LevelInfo
import com.soulreturns.profileviewer.service.masterFloorName
import com.soulreturns.profileviewer.service.normalFloorName
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import net.minecraft.text.Text
import java.util.Locale

class DungeonsTab(
    private val supplier: () -> Pair<SkyblockProfile, String>,
) : ProfileViewerTab {
    override val id: String = "dungeons"
    override val label: Text = Text.literal("Dungeons")

    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, area: TabArea) {
        val (profile, uuidUndashed) = supplier()
        val member = profile.memberFor(uuidUndashed)
        val tr = MinecraftClient.getInstance().textRenderer

        if (member == null) {
            context.drawText(tr, Text.literal("§cMember data not present in this profile."), area.x, area.y, 0xFFFF5555.toInt(), true)
            return
        }
        val dungeonsRoot = member.getAsJsonObject("dungeons")
        if (dungeonsRoot == null) {
            context.drawText(tr, Text.literal("§7No dungeons data on this profile."), area.x, area.y, 0xFFAAAAAA.toInt(), true)
            return
        }
        val view = DungeonsView(dungeonsRoot)

        var y = area.y
        // Catacombs level
        val cataLevel = DungeonsCalculator.catacombsLevel(view.catacombsExperience)
        y = drawLevelLine(context, area.x, y, "Catacombs", cataLevel, view.catacombsExperience)

        // Master Catacombs level
        val mastLevel = DungeonsCalculator.masterCatacombsLevel(view.masterCatacombsExperience)
        y = drawLevelLine(context, area.x, y, "Master Catacombs", mastLevel, view.masterCatacombsExperience)

        y += 6
        // Class panel
        val selected = view.selectedClass
        context.drawText(tr, Text.literal("§eClasses${selected?.let { " §8(active: §f${DungeonClassNames.displayName(it)}§8)" } ?: ""}"), area.x, y, 0xFFFFFFFF.toInt(), true)
        y += 12
        val classXp = view.classExperience
        DungeonClassNames.ORDER.forEach { key ->
            val xp = classXp[key] ?: 0.0
            val info = DungeonsCalculator.classLevel(xp)
            val active = if (key == selected) "§a*" else " "
            val line = String.format(
                Locale.ROOT,
                "%s §f%-9s §7Lv §f%2d §7(%s)  §8XP %s",
                active,
                DungeonClassNames.displayName(key),
                info.level,
                progressString(info),
                DungeonsCalculator.formatXp(xp),
            )
            context.drawText(tr, Text.literal(line), area.x, y, 0xFFCCCCCC.toInt(), true)
            y += 11
        }

        y += 6
        // Floor table headers
        y = drawFloorHeader(context, area.x, y)
        for (tier in 0..7) {
            val stats = view.normalFloor(tier)
            if (stats.isEmpty()) continue
            y = drawFloorRow(context, area.x, y, normalFloorName(tier), stats)
        }
        y += 4
        context.drawText(tr, Text.literal("§eMaster Mode"), area.x, y, 0xFFFFFFFF.toInt(), true)
        y += 12
        y = drawFloorHeader(context, area.x, y)
        for (tier in 1..7) {
            val stats = view.masterFloor(tier)
            if (stats.isEmpty()) continue
            y = drawFloorRow(context, area.x, y, masterFloorName(tier), stats)
        }

        y += 6
        val secrets = view.totalSecrets
        if (secrets != null) {
            val totalRuns = view.totalCatacombsCompletions
            val perRun = if (totalRuns > 0) secrets.toDouble() / totalRuns else 0.0
            val line = String.format(Locale.ROOT, "§7Secrets: §f%d §8(avg §f%.2f§8/run)", secrets, perRun)
            context.drawText(tr, Text.literal(line), area.x, y, 0xFFFFFFFF.toInt(), true)
        } else {
            context.drawText(tr, Text.literal("§8Secrets: §7n/a (requires /v2/player)"), area.x, y, 0xFFAAAAAA.toInt(), true)
        }
    }

    private fun drawLevelLine(context: DrawContext, x: Int, y: Int, label: String, info: LevelInfo, xp: Double): Int {
        val tr = MinecraftClient.getInstance().textRenderer
        val line = String.format(
            Locale.ROOT,
            "§b%s §7Lv §f%d §7(%s)  §8total XP §f%s",
            label, info.level, progressString(info), DungeonsCalculator.formatXp(xp)
        )
        context.drawText(tr, Text.literal(line), x, y, 0xFFFFFFFF.toInt(), true)
        // Progress bar
        val barX = x
        val barY = y + 11
        val barW = 200
        val barH = 4
        context.fill(barX, barY, barX + barW, barY + barH, 0xFF202020.toInt())
        val fill = (barW * info.progress).toInt().coerceIn(0, barW)
        context.fill(barX, barY, barX + fill, barY + barH, 0xFF55FF55.toInt())
        return y + 22
    }

    private fun progressString(info: LevelInfo): String =
        String.format(Locale.ROOT, "§a%.1f%%§7", info.progressPct())

    private val tr get() = MinecraftClient.getInstance().textRenderer

    private fun drawFloorHeader(context: DrawContext, x: Int, y: Int): Int {
        val cols = "§7§lFloor   Comp.   Score   Time     Time S    Time S+"
        context.drawText(tr, Text.literal(cols), x, y, 0xFFFFFFFF.toInt(), true)
        return y + 11
    }

    private fun drawFloorRow(
        context: DrawContext, x: Int, y: Int, floor: String, s: com.soulreturns.profileviewer.model.FloorStats
    ): Int {
        val line = String.format(
            Locale.ROOT,
            "§f%-7s §f%-7d §f%-7d §f%-8s §f%-9s §f%-9s",
            floor,
            s.completions,
            s.bestScore,
            DungeonsCalculator.formatTime(s.fastestTimeMs),
            DungeonsCalculator.formatTime(s.fastestSMs),
            DungeonsCalculator.formatTime(s.fastestSPlusMs),
        )
        context.drawText(tr, Text.literal(line), x, y, 0xFFCCCCCC.toInt(), true)
        return y + 11
    }
}
