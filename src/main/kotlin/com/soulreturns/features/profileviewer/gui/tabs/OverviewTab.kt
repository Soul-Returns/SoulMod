package com.soulreturns.features.profileviewer.gui.tabs

import com.soulreturns.config.gui.Theme
import com.soulreturns.data.hypixel.calc.DungeonClassNames
import com.soulreturns.data.hypixel.calc.LevelCalculator
import com.soulreturns.data.hypixel.calc.XpTables
import com.soulreturns.features.profileviewer.gui.ProfileCanvas
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileTab
import com.soulreturns.features.profileviewer.gui.formatAverage

/**
 * The tab the screen opens on: the handful of numbers a player actually asks for before deciding
 * whether to look deeper — Skill Average, Class Average, Catacombs, total slayer XP — plus the state
 * of the profile itself (game mode, whether Hypixel API access is on).
 *
 * Everything here is also somewhere else, deliberately. This is a summary, not a source.
 */
object OverviewTab : ProfileTab {
    override val id = "overview"
    override val label = "Profile"

    override fun draw(c: ProfileCanvas, ctx: ProfileContext) {
        val m = ctx.member

        c.section("Profile") {
            statRow("Player", ctx.identity.name, Theme.TEXT, bold = true)
            statRow("Profile", ctx.profile.cuteName)
            statRow("Game mode", ctx.profile.gameMode?.let { prettyGameMode(it) } ?: "Normal")
            if (ctx.profile.selected) statRow("Currently playing", "yes", Theme.ACCENT)
            val coop = ctx.profile.memberUuids().size
            if (coop > 1) statRow("Co-op members", coop.toString())
        }

        // SkyBlock Level first and on its own: it is the one number that moves with every part of
        // the game, so it is what "how far along is this account" actually means.
        val sbLevel = m.skyblockLevel
        c.section("SkyBlock Level") {
            if (sbLevel == null) {
                note("This profile has no SkyBlock Level data.")
            } else {
                // A SkyBlock level is exactly 100 XP, so progress through it is the readable number;
                // the running total ("60.4k") is just the level again with a zero on the end.
                levelRow(
                    "SkyBlock Level",
                    sbLevel,
                    m.skyblockExperience,
                    highlight = true,
                    trailing = "${sbLevel.xpIntoLevel(m.skyblockExperience)}/100",
                )
            }
        }

        c.section("At a glance") {
            val average = m.skillAverageCapped(ctx.skillCaps)
            statRow(
                "Skill Average",
                if (average == null) "—" else formatAverage(average),
                if (average == null) Theme.TEXT_FAINT else Theme.TEXT,
                bold = average != null,
            )
            if (average != null && m.skillCount > 0) {
                statRow("Skills counted", "${m.skillCount} of ${nonCosmeticTotal()}")
            }
            divider()
            if (m.hasDungeons) {
                // One Catacombs level, deliberately: Master Mode shares it in game, so a second
                // "Master Catacombs" level would be an artifact of the response, not a fact.
                statRow("Catacombs", m.catacombsLevel.describe(), Theme.TEXT, bold = true)
                statRow("Class Average", formatAverage(m.classAverage))
                statRow(
                    "Selected class",
                    m.selectedDungeonClass?.let { DungeonClassNames.displayName(it) } ?: "—",
                )
                statRow("Dungeon runs", m.totalDungeonRuns.toString())
            } else {
                statRow("Catacombs", "never entered", Theme.TEXT_FAINT)
            }
            divider()
            val slayerXp = m.totalSlayerXp
            statRow(
                "Slayer XP",
                if (slayerXp > 0) LevelCalculator.formatXp(slayerXp) else "—",
                if (slayerXp > 0) Theme.TEXT else Theme.TEXT_FAINT,
            )
        }

        // Two states worth stating plainly rather than leaving the reader to infer from blank tabs.
        if (m.apiAccessDisabled) {
            c.section("Hypixel API access is off") {
                note(
                    "${ctx.identity.name} has turned inventory sharing off in game (SkyBlock Menu → " +
                        "Settings → API Settings), so nothing inventory-shaped can be shown. Skills, " +
                        "slayers and dungeons are unaffected and are shown in full.",
                )
            }
        }
        if (ctx.profile.isSpecialGameMode) {
            c.section("Special game mode") {
                note(
                    "This is a ${prettyGameMode(ctx.profile.gameMode ?: "")} profile. Its progression " +
                        "rules differ from a normal profile, so comparing these numbers against one is " +
                        "not meaningful.",
                )
            }
        }
    }

    /** How many skills count toward Skill Average at all, for the "N of M" readout. */
    private fun nonCosmeticTotal(): Int = XpTables.nonCosmeticSkills.size

    /**
     * Hypixel's `game_mode` values are wire-shaped: `island` is what a player calls Stranded.
     * `when (String)` is safe — the banned form is a subject `when` over an enum or sealed type.
     */
    private fun prettyGameMode(mode: String): String =
        when (mode) {
            "ironman" -> "Ironman"
            "island" -> "Stranded"
            "bingo" -> "Bingo"
            else -> mode.replaceFirstChar { it.uppercase() }
        }
}
