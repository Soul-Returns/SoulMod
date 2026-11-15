package com.soulreturns.features.profileviewer.gui.tabs

import com.soulreturns.config.gui.Theme
import com.soulreturns.data.hypixel.DungeonFloorProgress
import com.soulreturns.data.hypixel.calc.DungeonClassNames
import com.soulreturns.data.hypixel.calc.LevelCalculator
import com.soulreturns.features.profileviewer.gui.ProfileCanvas
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileTab
import com.soulreturns.features.profileviewer.gui.formatAverage

/**
 * Dungeons. The information design is the legacy mod's `profileviewer/gui/DungeonsTab.kt`, which was
 * a good tab and is kept as-is: Catacombs level, Master Catacombs, the five classes in
 * [DungeonClassNames.ORDER], then the per-floor tables and the totals. Only the drawing is new — the
 * legacy file was written against a Compose-style framework that does not exist in 26.2.
 *
 * A profile with no `dungeons` key at all is a labelled empty state, not a blank tab: "never entered"
 * is information, and a player who reads nothing assumes the lookup broke.
 */
object DungeonsTab : ProfileTab {
    override val id = "dungeons"
    override val label = "Dungeons"

    override fun draw(c: ProfileCanvas, ctx: ProfileContext) {
        val m = ctx.member
        if (!m.hasDungeons) {
            c.section("Catacombs") {
                note("${ctx.identity.name} has never entered the Catacombs on ${ctx.profile.cuteName}.")
            }
            return
        }

        // ONE Catacombs level. Hypixel tracks master_catacombs.experience separately, but Master
        // Mode has no level of its own in game — it shares this one — so showing a second level
        // would report an artifact of the response as a fact about the account. The Master data that
        // is real is per-floor, below.
        c.section("Catacombs") {
            levelRow("Catacombs", m.catacombsLevel, m.catacombsExperience)
            if (m.masterCatacombsExperience > 0) {
                statRow("Master Mode XP", LevelCalculator.formatXp(m.masterCatacombsExperience))
            }
        }

        c.section("Classes", "Class Average ${formatAverage(m.classAverage)}") {
            for (klass in m.dungeonClasses) {
                levelRow(klass.displayName, klass.level, klass.experience, highlight = klass.selected)
            }
            divider()
            statRow(
                "Selected",
                m.selectedDungeonClass?.let { DungeonClassNames.displayName(it) } ?: "—",
                if (m.selectedDungeonClass != null) Theme.ACCENT else Theme.TEXT_FAINT,
            )
        }

        floorSection(c, "Catacombs Floors", m.catacombsFloors)
        floorSection(c, "Master Mode Floors", m.masterCatacombsFloors)

        c.section("Totals") {
            statRow("Total runs", m.totalDungeonRuns.toString())
            // Absent on most accounts — the canonical total is on /v2/player, which we never fetch.
            val secrets = m.dungeonSecrets
            if (secrets != null) statRow("Secrets found", secrets.toString())
        }
    }

    /**
     * One floor table. Every floor gets a row even when unplayed (a dash, not a gap) so the tiers
     * read as a scale; a branch with no completed floor at all collapses to one line instead.
     */
    private fun floorSection(c: ProfileCanvas, title: String, floors: List<DungeonFloorProgress>) {
        c.section(title) {
            if (floors.none { it.played }) {
                note("No completed runs.")
                return@section
            }
            tableRow(listOf("Floor", "Runs", "Best", "Fastest", "S", "S+"), ProfileCanvas.FLOOR_COLUMNS, header = true)
            for (f in floors) {
                tableRow(
                    listOf(
                        f.displayName,
                        if (f.completions > 0) f.completions.toString() else "—",
                        if (f.bestScore > 0) f.bestScore.toString() else "—",
                        LevelCalculator.formatTime(f.fastestTimeMs),
                        LevelCalculator.formatTime(f.fastestSTimeMs),
                        LevelCalculator.formatTime(f.fastestSPlusTimeMs),
                    ),
                    ProfileCanvas.FLOOR_COLUMNS,
                )
            }
        }
    }
}
