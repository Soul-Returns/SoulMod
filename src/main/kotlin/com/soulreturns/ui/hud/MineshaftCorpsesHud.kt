package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.features.mining.mineshaft.MineshaftCorpses
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme

/**
 * Mineshaft corpse overlay — one row per corpse type currently visible in the tab list, with
 * `looted` (green) and `total` (red) counts. Only shown while in `Area: Mineshaft`.
 *
 * Pure view; reads from [MineshaftCorpses] which owns the tab-list scan.
 *
 * Migrated from legacy `GuiLayoutApi.updateTextBlock` (which relied on `§`-formatted strings)
 * to [SoulHud.register] per the P3 framework migration. Color segments are now real
 * per-`Text` composable colors instead of embedded format codes.
 */
object MineshaftCorpsesHud {
    private const val HUD_ID = "mineshaft_corpses"

    private const val COLOR_LAPIS = 0xFF5555FF.toInt() // §9
    private const val COLOR_UMBER = 0xFFFFAA00.toInt() // §6
    private const val COLOR_TUNGSTEN = 0xFFFFFFFF.toInt() // §f
    private const val COLOR_VANGUARD = 0xFFAA00AA.toInt() // §5
    private const val COLOR_LOOTED = 0xFF55FF55.toInt() // §a green
    private const val COLOR_TOTAL = 0xFFFF5555.toInt() // §c red
    private const val COLOR_TITLE_AQUA = 0xFF55FFFF.toInt() // §b
    private const val COLOR_NONE = 0xFFAAAAAA.toInt() // §7 gray

    private val TYPE_COLOR =
        mapOf(
            "Lapis" to COLOR_LAPIS,
            "Umber" to COLOR_UMBER,
            "Tungsten" to COLOR_TUNGSTEN,
            "Vanguard" to COLOR_VANGUARD,
        )

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 220,
            height = 200,
            // Top-center default. The HUD only renders in `Area: Mineshaft`, so it never
            // competes for screen space with the other top-center HUDs (Bobbin / Legion).
            // `horizontalAnchor = Center` pivots on the actual rendered width so the panel
            // sits dead-center regardless of resolution / GUI scale.
            defaultAnchorX = 0.5,
            defaultAnchorY = 0.02,
            defaultHorizontalAnchor = com.soulreturns.gui.lib.HudHorizontalAnchor.Center,
            settingsCategory = "mining",
            settingsSubcategory = "mineshaft",
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        val enabled = cfg.mining.mineshaft.showCorpsesHud() && LocationApi.isInArea("Mineshaft")
        if (!enabled) {
            Box {}
            return
        }
        Surface {
            Column(gap = 4f) {
                Text(
                    text = "Corpses",
                    size = SoulTheme.typography.heading.size,
                    color = COLOR_TITLE_AQUA,
                    font = SoulTheme.typography.heading.font,
                )

                val grouped = MineshaftCorpses.byType
                if (grouped.isEmpty()) {
                    Text(
                        text = "None",
                        size = SoulTheme.typography.body.size,
                        color = COLOR_NONE,
                        font = SoulTheme.typography.body.font,
                    )
                    return@Column
                }
                val sorted =
                    grouped.entries.sortedWith(
                        compareByDescending<Map.Entry<String, MineshaftCorpses.Counts>> { it.value.total }
                            .thenBy { it.key },
                    )
                sorted.forEach { (type, counts) ->
                    Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 4f) {
                        Text(
                            text = "$type:",
                            size = SoulTheme.typography.body.size,
                            color = TYPE_COLOR[type] ?: SoulTheme.colors.text,
                            font = SoulTheme.typography.body.font,
                        )
                        Text(
                            text = "${counts.looted}",
                            size = SoulTheme.typography.body.size,
                            color = COLOR_LOOTED,
                            font = SoulTheme.typography.body.font,
                        )
                        Text(
                            text = "/",
                            size = SoulTheme.typography.body.size,
                            color = SoulTheme.colors.textFaint,
                            font = SoulTheme.typography.body.font,
                        )
                        Text(
                            text = "${counts.total}",
                            size = SoulTheme.typography.body.size,
                            color = COLOR_TOTAL,
                            font = SoulTheme.typography.body.font,
                        )
                    }
                }
            }
        }
    }
}
