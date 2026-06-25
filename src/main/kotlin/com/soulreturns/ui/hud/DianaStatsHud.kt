package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.features.diana.DianaStatRow
import com.soulreturns.features.diana.MythologicalStatsTracker
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme

/**
 * Diana Stats HUD — renders [MythologicalStatsTracker]'s per-row counters in a compact
 * list (one row per [DianaStatRow]). Each row shows `<label>: <regular>` and optionally
 * `, since [LS]: <lootshare>` when the row's `lootshareResetItem` is non-null.
 *
 * Visible only when the player is in the Hub (Diana's island) AND the master Mythological
 * tracker toggle is on AND the HUD-specific toggle is on. Default off — opt-in feature
 * policy.
 *
 * Row source is currently hardcoded in [MythologicalStatsTracker]; backend-driven catalog
 * lands in a follow-up (see TODO `prompts/diana-stats/`).
 */
object DianaStatsHud {
    private const val HUD_ID = "diana_stats"

    /**
     * Header color (yellow) is the only color controlled mod-side — it's a per-HUD
     * detail, not a per-row property, so it doesn't live on the catalog row.
     */
    private const val HEADER_COLOR = 0xFFFFFF55.toInt() // yellow

    /**
     * Safety-net colors used when a catalog row arrives with `null` label / value color.
     * The canonical defaults — the SBO-matching red + aqua — live in the backend seed
     * (see `prompts/diana-stats/02-row-colors.md`). This fallback is plain white so a
     * misconfigured row stays legible without pretending to know what the admin intended.
     */
    private const val FALLBACK_LABEL_COLOR = 0xFFFFFFFF.toInt()
    private const val FALLBACK_VALUE_COLOR = 0xFFFFFFFF.toInt()

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            // Width / height are MAX bounds — Mojang allocates a PIP texture of exactly
            // this size, so over-sizing wastes a chunk of GPU memory but under-sizing
            // clips the panel. We oversize generously so any reasonable backend-curated
            // row count + label length fits without re-registration. The Surface inside
            // sizes to its actual content, so the on-screen panel + the `/soul gui`
            // selection box grow / shrink with the catalog automatically.
            //
            // 400 × 400 covers ~25 rows with long labels ("Sphinxes since Food, since
            // [LS]: 1234"); single 4-byte texture allocation is ~640 KB — negligible.
            width = 400,
            height = 400,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.1,
            defaultHorizontalAnchor = HudHorizontalAnchor.Start,
            settingsCategory = "combat",
            settingsSubcategory = "diana",
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        if (!cfg.combat.diana.showStatsHud()) {
            Box {}
            return
        }
        if (!cfg.dev.trackers.mythologicalTracker()) {
            Box {}
            return
        }
        if (!LocationApi.isInArea("Hub")) {
            Box {}
            return
        }
        val rows = MythologicalStatsTracker.rows()
        if (rows.isEmpty()) {
            Box {}
            return
        }
        Surface(
            color = if (SoulHud.shouldDrawBackground(HUD_ID)) SoulTheme.colors.panel else 0x00000000,
        ) {
            Column(gap = 2f) {
                Text(
                    text = "Diana Stats",
                    size = SoulTheme.typography.heading.size,
                    color = HEADER_COLOR,
                    font = SoulTheme.typography.heading.font,
                )
                for (row in rows) {
                    RowLine(row)
                }
            }
        }
    }

    /**
     * One row of the stats list — a multi-chunk [Row] so the label and the numeric value
     * can carry distinct colors. Chunks rendered left-to-right, no gap (the trailing
     * space inside each chunk handles separation):
     *
     *  - `" - <label>: "` in labelColor
     *  - `"<regular>"` in valueColor
     *  - (if `hasLootshareColumn`) `", since [LS]: "` in labelColor + `"<ls>"` in valueColor
     */
    @SoulComposable
    private fun RowLine(row: DianaStatRow) {
        val labelColor = row.labelColor ?: FALLBACK_LABEL_COLOR
        val valueColor = row.valueColor ?: FALLBACK_VALUE_COLOR
        val regular = MythologicalStatsTracker.regular(row.id)
        Row {
            chunk(text = " - ${row.label}: ", color = labelColor)
            chunk(text = regular.toString(), color = valueColor)
            if (row.hasLootshareColumn) {
                val ls = MythologicalStatsTracker.lootshare(row.id)
                chunk(text = ", since [LS]: ", color = labelColor)
                chunk(text = ls.toString(), color = valueColor)
            }
        }
    }

    @SoulComposable
    private fun chunk(text: String, color: Int) {
        Text(
            text = text,
            size = SoulTheme.typography.body.size,
            color = color,
            font = SoulTheme.typography.body.font,
        )
    }
}
