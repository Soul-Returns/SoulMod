package com.soulreturns.platform.render.nvg

import com.soulreturns.config.cfg
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Text
import net.minecraft.client.gui.GuiGraphics

/**
 * Temporary scaffolding to verify both the NanoVG PIP pipeline and the declarative Soul UI
 * framework end-to-end. Renders a small panel with three lines of text in the top-left while
 * `cfg.dev.debug.debugMode` is enabled.
 *
 * Remove this file (and its call from
 * [com.soulreturns.platform.mixinbridge.SoulGuiHudAdapter]) once real consumers exist — the
 * framework's `SoulHud` integration (P2.5) will replace this entry point.
 */
object NvgSmokeTest {
    fun render(context: GuiGraphics) {
        if (!cfg.dev.debug.debugMode()) return

        NvgFrame.submit(context, x = 4, y = 4, w = 220, h = 88) {
            val root =
                SoulComposer.create().build {
                    Column(
                        modifier =
                            SoulModifier.Empty
                                .background(color = 0xD81A1A1A.toInt(), radius = 6f)
                                .padding(12f),
                        gap = 4f,
                    ) {
                        Text(
                            text = "Soul UI framework",
                            size = 13f,
                            color = 0xFFEEEEEE.toInt(),
                            font = NvgRenderer.semiBoldFont,
                        )
                        Text(
                            text = "Regular — sphinx of black quartz",
                            size = 11f,
                            color = 0xFFCCCCCC.toInt(),
                            font = NvgRenderer.defaultFont,
                        )
                        Text(
                            text = "Medium — judge my vow",
                            size = 11f,
                            color = 0xFFCCCCCC.toInt(),
                            font = NvgRenderer.mediumFont,
                        )
                        Text(
                            text = "SemiBold — 1234567890",
                            size = 11f,
                            color = 0xFF3B82F6.toInt(),
                            font = NvgRenderer.semiBoldFont,
                        )
                    }
                }
            root.draw(0f, 0f, SoulConstraints(maxWidth = 220f, maxHeight = 88f))
        }
    }
}
