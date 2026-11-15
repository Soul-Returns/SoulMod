package com.soulreturns.features.notifications

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
 * The toast stack for backend notifications ([BackendNotificationCenter]).
 *
 * A [MovableHud], so the player positions it in the Move GUI like every other overlay. Each toast
 * is word-wrapped to [MAX_WIDTH] and colored by its severity; the newest is drawn last (bottom).
 */
object NotificationHud : MovableHud("notifications", "Notifications", "general") {
    private const val SIZE = 10f
    private const val PAD = 5

    /** Word-wrap width in Soul-logical px. The backend caps a message at 500 chars. */
    private const val MAX_WIDTH = 220

    /** Gap between stacked toasts. */
    private const val GAP = 3

    private val PREVIEW = listOf(
        BackendNotificationCenter.Toast("Scheduled maintenance in 10 minutes.", "warning", 0xFFFFD744.toInt(), 0),
        BackendNotificationCenter.Toast("Your profile sync is up to date.", "info", 0xFFFFFFFF.toInt(), 0),
    )

    override fun isEnabled(): Boolean =
        cfg.notifications.toastEnabled && BackendNotificationCenter.active().isNotEmpty()

    override fun configuredOn(): Boolean = cfg.notifications.toastEnabled

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forSettings(parent, "chat", "backend")

    override fun readTransform(): HudTransform = cfg.notifications.hud

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(notifications = notifications.copy(hud = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().notifications.hud

    override fun menuToggles(): List<HudMenuToggle> =
        listOf(
            HudMenuToggle("Background", { cfg.notifications.background }) {
                ConfigManager.update { copy(notifications = notifications.copy(background = !notifications.background)) }
            },
        )

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val cards = layout(preview)
        if (cards.isEmpty()) return 0 to 0
        val w = cards.maxOf { it.width }
        val h = cards.sumOf { it.height } + GAP * (cards.size - 1)
        return w to h
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val cards = layout(preview)
        if (cards.isEmpty()) return
        val bold = boldFont()
        val choice = fontChoiceIndex()
        val font = if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)
        val shadow = textShadow()
        val width = cards.maxOf { it.width }

        var y = 0
        for (card in cards) {
            if (cfg.notifications.background) {
                roundRect(g, 0, y, width, y + card.height, cfg.notifications.backgroundColor.effective())
            }
            for ((i, line) in card.lines.withIndex()) {
                SoulText.draw(
                    g, line, PAD, y + PAD + i * lineStep(), SIZE, card.color,
                    font = font, forceCustom = false, shadow = shadow, fontChoice = choice,
                )
            }
            y += card.height + GAP
        }
    }

    // ------------------------------------------------------------------ layout

    private class Card(val lines: List<String>, val color: Int, val width: Int, val height: Int)

    private fun lineStep(): Int = (SIZE * 1.3f).toInt()

    private fun layout(preview: Boolean): List<Card> {
        val source = if (preview) PREVIEW else BackendNotificationCenter.active()
        if (source.isEmpty()) return emptyList()
        val choice = fontChoiceIndex()
        val font = if (boldFont()) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)
        return source.map { toast ->
            val lines = wrap(toast.message, font, choice)
            val w = (lines.maxOfOrNull { SoulText.width(it, SIZE, font, fontChoice = choice) } ?: 0) + PAD * 2
            Card(lines, toast.color, w, PAD * 2 + lines.size * lineStep())
        }
    }

    /** Greedy word wrap at [MAX_WIDTH]; a single word longer than that is left to overflow. */
    private fun wrap(text: String, font: com.soulreturns.config.gui.render.MsdfFont, choice: Int): List<String> {
        val words = text.split(' ').filter { it.isNotEmpty() }
        if (words.isEmpty()) return listOf(text)
        val lines = mutableListOf<String>()
        var current = StringBuilder()
        for (word in words) {
            val candidate = if (current.isEmpty()) word else "$current $word"
            if (SoulText.width(candidate, SIZE, font, fontChoice = choice) > MAX_WIDTH && current.isNotEmpty()) {
                lines += current.toString()
                current = StringBuilder(word)
            } else {
                current = StringBuilder(candidate)
            }
        }
        if (current.isNotEmpty()) lines += current.toString()
        return lines
    }

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
