package com.soulreturns.features.mining.commissions

import com.soulreturns.config.CommissionsConfig
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.SlotMark
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f

/** "Commissions HUD": one row per commission with its progress, DONE in green. A pure view over [Commissions]. */
object CommissionsHud : MovableHud("commissions", "Commissions", "mining") {
    private const val SIZE = 10f
    private const val PAD = 5
    private const val GAP = 10
    private const val TITLE = "Commissions"

    private const val COLOR_TITLE = 0xFFFFFFFF.toInt()
    private const val COLOR_TEXT = 0xFFE0E0E0.toInt()
    private const val COLOR_DIM = 0xFF808080.toInt()

    private val PREVIEW = listOf(
        Commission("Goblin Raid Slayer", "0%", false),
        Commission("Royal Mines Mithril", "45%", false),
        Commission("Upper Mines Titanium", CommissionParsing.DONE, true),
    )

    override fun isEnabled(): Boolean = cfg.mining.commissions.hud && !Commissions.current().isNullOrEmpty()

    override fun configuredOn(): Boolean = cfg.mining.commissions.hud

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "commissionshud")

    override fun readTransform(): HudTransform = cfg.mining.commissions.hudTransform

    override fun writeTransform(transform: HudTransform) = update { copy(hudTransform = transform) }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.commissions.hudTransform

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.mining.commissions.hudBackground }) { update { copy(hudBackground = !hudBackground) } },
    )

    private fun update(block: CommissionsConfig.() -> CommissionsConfig) {
        ConfigManager.update { copy(mining = mining.copy(commissions = mining.commissions.block())) }
    }

    private fun rows(preview: Boolean): List<Commission> = if (preview) PREVIEW else Commissions.current().orEmpty()

    private fun step(): Int = (SIZE * 1.4f).toInt()

    /** Width and height; progress is right-aligned past the widest name. */
    private fun size(rows: List<Commission>, bold: Boolean, c: Int): Pair<Int, Int> {
        val nameW = rows.maxOfOrNull { width(it.name, bold, c) } ?: 0
        val progressW = rows.maxOfOrNull { width(it.progress ?: "?", bold, c) } ?: 0
        val w = maxOf(width(TITLE, true, c), nameW + GAP + progressW) + PAD * 2
        return w to PAD * 2 + (rows.size + 1) * step()
    }

    override fun contentSize(preview: Boolean): Pair<Int, Int> = size(rows(preview), boldFont(), fontChoiceIndex())

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val rows = rows(preview)
        val c = fontChoiceIndex()
        val bold = boldFont()
        val shadow = textShadow()
        val (w, h) = size(rows, bold, c)
        if (cfg.mining.commissions.hudBackground) roundRect(g, w, h, cfg.mining.commissions.hudBackgroundColor.effective())
        draw(g, TITLE, PAD, PAD, COLOR_TITLE, true, c, shadow)
        var y = PAD + step()
        for (row in rows) {
            draw(g, row.name, PAD, y, CommissionColors.forName(row.name), bold, c, shadow)
            val progress = row.progress ?: "?"
            draw(g, progress, w - PAD - width(progress, bold, c), y, if (row.done) SlotMark.DONE else if (row.progress == null) COLOR_DIM else COLOR_TEXT, bold, c, shadow)
            y += step()
        }
    }

    private fun width(text: String, bold: Boolean, choice: Int): Int =
        if (text.isEmpty()) 0 else SoulText.width(text, SIZE, fontFor(bold, choice), fontChoice = choice)

    private fun draw(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int, bold: Boolean, choice: Int, shadow: Boolean) {
        SoulText.draw(g, text, x, y, SIZE, color, font = fontFor(bold, choice), forceCustom = false, shadow = shadow, fontChoice = choice)
    }

    private fun fontFor(bold: Boolean, choice: Int) = if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)

    private fun roundRect(g: GuiGraphicsExtractor, w: Int, h: Int, color: Int) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(SoulRenderPipelines.ROUND_SMALL, TextureSetup.noTexture(), Matrix3x2f(g.pose()), 0, 0, w, h, color, null),
        )
    }
}
