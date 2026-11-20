package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.DungeonFloorProgress
import com.soulreturns.data.hypixel.dungeonTreasures
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Cell
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Col
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.TableRow
import com.soulreturns.features.profileviewer.gui.kit.Ui
import java.util.Locale

/**
 * Catacombs level, classes, every floor in normal and Master Mode, secrets and runs (mock
 * `dungeonsSec`). The Master Catacombs "level" is deliberately not shown: Hypixel has no such level
 * (see `.agents/rules/profile-data.md`).
 */
object DungeonsSection : ProfileSection {
    override val id = "dungeons"
    override val title = "Dungeons"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_skull"

    override fun badge(ctx: ProfileContext): String? = if (ctx.member.hasDungeons) "${ctx.member.catacombsLevel.level}" else null

    /** Mock `GRADE`: the score grade and its color. */
    fun grade(score: Int): Pair<String, Int> = when {
        score >= 300 -> "S+" to KitStyle.MAXED
        score >= 270 -> "S" to KitStyle.MAXED
        score >= 230 -> "A" to KitStyle.ACCENT_LIGHT
        score >= 160 -> "B" to KitStyle.GOOD
        score >= 100 -> "C" to KitStyle.TEXT_MID
        else -> "D" to KitStyle.TEXT_DIM
    }

    override fun build(ctx: ProfileContext, ui: Ui) {
        val m = ctx.member
        if (!m.hasDungeons) {
            ui.empty("pv_skull", "No dungeon runs yet", "${ctx.name} hasn't entered the Catacombs on ${ctx.profile.cuteName}.")
            return
        }
        val cata = m.catacombsLevel
        val classes = m.dungeonClasses
        val runs = m.totalDungeonRuns
        ui.tiles {
            val into = (m.catacombsExperience - cata.currentLevelXp).coerceAtLeast(0.0)
            tile(
                "Catacombs", "${cata.level}",
                sub = if (cata.maxed) "max · ${Format.short(into)} XP past ${cata.level}" else "${Format.short(into)} / ${Format.short(cata.levelSpanXp)} XP",
                bar = cata.progress.toFloat(), maxed = cata.maxed,
            )
            tile("Class average", Format.avg(m.classAverage), sub = "${classes.count { it.level.maxed }} classes at 50")
            val secrets = m.dungeonSecrets
            if (secrets != null) {
                tile("Secrets", Format.num(secrets), sub = "${String.format(Locale.ROOT, "%.1f", secrets.toDouble() / maxOf(1, runs))} per run")
            } else {
                tile("Secrets", "—", sub = "not reported for this profile", tip = "Hypixel only puts secrets on some profiles")
            }
            val treasures = m.dungeonTreasures
            tile("Runs", Format.num(runs), sub = treasures?.let { "${Format.num(it.second)} chests in the last ${it.first} runs" } ?: "every floor")
        }
        val mode = ui.value("floorMode", "normal")
        ui.grid(300) {
            card("Classes") {
                for (c in classes) {
                    levelRow(
                        c.displayName, "${c.level.level}", c.level.progress.toFloat(), c.level.maxed,
                        selected = c.selected,
                        chip = if (c.selected) Chip.accent("PLAYING") else null,
                    )
                }
            }
            card("Floors", span = 2, tools = { seg("floorMode", "normal", listOf("normal" to "Catacombs", "master" to "Master Mode")) }) {
                val floors = if (mode == "master") m.masterCatacombsFloors else m.catacombsFloors
                if (floors.isEmpty()) {
                    empty("pv_skull", if (mode == "master") "No Master Mode runs" else "No floor runs", "")
                } else {
                    table(
                        listOf(Col("Floor"), Col("Runs"), Col("Best score"), Col("Fastest"), Col("Fastest S"), Col("Fastest S+")),
                        floors.map { floorRow(it, mode == "master") },
                    )
                }
            }
        }
    }

    private fun floorRow(f: DungeonFloorProgress, master: Boolean): TableRow {
        val played = f.completions > 0
        val name = if (master) "M${f.tier}" else if (f.tier == 0) "Entrance" else "F${f.tier}"
        val (g, color) = grade(f.bestScore)
        val score = if (played && f.bestScore > 0) Cell(Rich.join(Rich.colored(g, color, bold = true, minW = 18), Rich.of(" ${f.bestScore}"))) else Cell.DASH
        return TableRow(
            listOf(
                Cell.of(name, bold = true),
                if (played) Cell.of(Format.num(f.completions)) else Cell.DASH,
                score,
                time(f.fastestTimeMs),
                time(f.fastestSTimeMs),
                time(f.fastestSPlusTimeMs),
            ),
            dim = !played,
        )
    }

    private fun time(ms: Long): Cell = if (ms > 0) Cell.of(Format.dur(ms)) else Cell.DASH
}
