package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.features.fishing.BobbinSpotter
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme

/**
 * Bobbin Time HUD — reads [BobbinSpotter.nearbyBobbers] each frame and renders a small panel.
 *
 * The spotter must register *before* this HUD so the count read here is from the current
 * tick, not the previous one. [com.soulreturns.Soul.registerFeatures] enforces the order.
 *
 * Migrated from legacy `GuiLayoutApi.updateTextBlock` to [SoulHud.register] per the P3
 * framework migration (`docs/ui-framework-roadmap.md`).
 */
object BobbinHud {
    private const val HUD_ID = "bobbin_time_counter"
    private const val ACCENT_CYAN = 0xFF00FFFF.toInt()

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 180,
            height = 60,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.35,
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        if (!cfg.fishing.bobbinTime.enableBobbinTimeCounter()) {
            Box {}
            return
        }
        Surface {
            Column(gap = 4f) {
                Text(
                    text = "Bobbin Time",
                    size = SoulTheme.typography.heading.size,
                    color = ACCENT_CYAN,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text = "Nearby bobbers: ${BobbinSpotter.nearbyBobbers}",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }
}
