package com.soulreturns.features.qol

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f

/**
 * A tiny "Sprint toggled" indicator shown while [com.soulreturns.features.qol.ToggleSprint] is active
 * (`general.toggleSprint`). It draws the label or nothing — a binary readout of the sprint-toggle
 * state, handy when the state is flipped by the toggle keybind rather than the config checkbox.
 *
 * A [MovableHud], so position/anchor/scale live in [GeneralConfig.toggleSprintHudTransform] and are
 * edited in the Move GUI; background + font follow the same per-HUD pattern as [ComboHud]. Defaults:
 * no background, bottom of the screen just left of the hotbar.
 */
object ToggleSprintHud : MovableHud("toggle_sprint", "Sprint Toggle", "general") {
    private const val SIZE = 10f
    private const val PAD = 4
    private const val TEXT = "Sprint toggled"

    private fun step(): Int = (SIZE * 1.3f).toInt()

    /** Live: only while the HUD is on AND sprint is toggled on. Off → nothing renders. */
    override fun isEnabled(): Boolean = cfg.general.toggleSprintHud && cfg.general.toggleSprint

    override fun configuredOn(): Boolean = cfg.general.toggleSprintHud

    override fun settingsScreen(parent: Screen?): Screen =
        SoulConfigScreen.forFeature(parent, "sprint")

    override fun readTransform(): HudTransform = cfg.general.toggleSprintHudTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(general = general.copy(toggleSprintHudTransform = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().general.toggleSprintHudTransform

    override fun menuToggles(): List<HudMenuToggle> =
        listOf(
            HudMenuToggle("Background", { cfg.general.toggleSprintHudBackground }) {
                ConfigManager.update {
                    copy(general = general.copy(toggleSprintHudBackground = !general.toggleSprintHudBackground))
                }
            },
        )

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val choice = fontChoiceIndex()
        val w = SoulText.width(TEXT, SIZE, fontFor(boldFont(), choice), fontChoice = choice)
        return (w + PAD * 2) to (PAD * 2 + step())
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val choice = fontChoiceIndex()
        val bold = boldFont()
        val (w, h) = contentSize(preview)

        if (cfg.general.toggleSprintHudBackground) {
            roundRect(g, 0, 0, w, h, cfg.general.toggleSprintHudBackgroundColor.effective())
        }

        SoulText.draw(
            g,
            TEXT,
            PAD,
            PAD,
            SIZE,
            cfg.general.toggleSprintHudColor.effective(),
            font = fontFor(bold, choice),
            forceCustom = false,
            shadow = textShadow(),
            fontChoice = choice,
        )
    }

    private fun fontFor(bold: Boolean, choice: Int) =
        if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)

    private fun roundRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(
                SoulRenderPipelines.ROUND_SMALL,
                TextureSetup.noTexture(),
                Matrix3x2f(g.pose()),
                x0, y0, x1, y1, color, null,
            ),
        )
    }
}
