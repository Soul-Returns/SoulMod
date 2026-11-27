package com.soulreturns.features.qol

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.hud.movable.HudMoveScreen
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.Screens
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.AbstractWidget
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.screens.PauseScreen
import net.minecraft.network.chat.Component

/**
 * "Soul GUI" (Move GUI) and "Soul Config" (config menu) in the pause menu's bottom left corner, each with
 * the pause menu as parent so closing returns to it. Vanilla-sized buttons, side by side; stacked
 * when that would touch another widget, and left out when even that would (a tiny window).
 */
object PauseMenuButtons {
    private const val MARGIN = 4
    private const val GAP = 4
    private const val PAD_X = 16
    private const val MIN_W = 44

    fun register() {
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { client, screen, _, height ->
                if (screen !is PauseScreen || !screen.showsPauseMenu() || !cfg.general.pauseMenuButtons) return@AfterInit
                val font = Screens.getFont(screen)
                val gui = button(Component.literal("Soul GUI"), font.width("Soul GUI")) { client.setScreenAndShow(HudMoveScreen(screen)) }
                val soul = button(Component.literal("Soul Config"), font.width("Soul Config")) { client.setScreenAndShow(SoulConfigScreen(screen)) }
                val widgets = Screens.getWidgets(screen)
                val bottom = height - MARGIN - Button.DEFAULT_HEIGHT
                val placed =
                    place(widgets, gui to (MARGIN to bottom), soul to (MARGIN + gui.width + GAP to bottom)) ||
                        place(widgets, gui to (MARGIN to bottom - GAP - Button.DEFAULT_HEIGHT), soul to (MARGIN to bottom))
                if (placed) {
                    widgets.add(gui)
                    widgets.add(soul)
                }
            },
        )
    }

    private fun button(label: Component, textWidth: Int, onPress: () -> Unit): Button =
        SoulPauseButton(label, maxOf(MIN_W, textWidth + PAD_X), onPress)

    /**
     * A vanilla [Button] (placement, focus, narration, click) drawn with Soul's SDF layer in the
     * config screen's button look. The pause screen is vanilla-scaled, so it draws in vanilla GUI
     * coordinates (no SoulUi factor) and lines up with vanilla's buttons at every GUI scale.
     */
    private class SoulPauseButton(label: Component, w: Int, private val action: () -> Unit) :
        Button(0, 0, w, DEFAULT_HEIGHT, label, { action() }, DEFAULT_NARRATION) {
        override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, a: Float) {
            val lit = isHoveredOrFocused
            val bg = if (lit) Theme.ACCENT else Theme.PANEL_HOVER
            // Focused by keyboard: a 1 px accent ring round the button, concentric with its corners.
            if (isFocused && !isHovered) SoulPanel.draw(g, x - 1, y - 1, x + width + 1, y + height + 1, SoulPanel.outer(SoulPanel.SMALL, 1f), bg, Theme.ACCENT)
            else SoulPanel.draw(g, x, y, x + width, y + height, SoulPanel.SMALL, bg)
            SoulText.drawCenteredOn(
                g, message.string, x + width / 2, y + height / 2, 9f * MsdfFont.LEGACY_EM, Theme.TEXT,
                MsdfFonts.menuSemibold, forceCustom = true,
            )
        }
    }

    /** Moves the buttons to their spots when none of them touches an existing widget. */
    private fun place(widgets: List<AbstractWidget>, vararg spots: Pair<Button, Pair<Int, Int>>): Boolean {
        for ((button, at) in spots) {
            val (x, y) = at
            if (y < 0) return false
            for (other in widgets) {
                if (!other.visible) continue
                val clear =
                    x + button.width + GAP <= other.x || other.x + other.width + GAP <= x ||
                        y + button.height + GAP <= other.y || other.y + other.height + GAP <= y
                if (!clear) return false
            }
        }
        for ((button, at) in spots) button.setPosition(at.first, at.second)
        return true
    }
}
