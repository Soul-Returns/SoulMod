package com.soulreturns.features.mobhealth

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulRichText
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import kotlin.math.roundToInt

/**
 * A small card for [MobHealthTracker.current]: name and level, a health bar colored like Hypixel's
 * numbers (green above half, yellow above a quarter, red below), and `current / max` with the
 * percentage. Tags without a max show the number only, no bar.
 */
object MobHealthHud : MovableHud("mob_health", "Mob Health", "combat") {
    private const val NAME_SIZE = 10f
    private const val SMALL_SIZE = 8f
    private const val PAD = 5
    private const val GAP = 3
    private const val BAR_H = 4
    private const val MIN_W = 110

    private const val NAME_COLOR = 0xFFFFFFFF.toInt()
    private const val DIM_COLOR = 0xFFAAAAAA.toInt()
    private const val TRACK_COLOR = 0x60FFFFFF
    private const val GREEN = 0xFF55FF55.toInt()
    private const val YELLOW = 0xFFFFFF55.toInt()
    private const val RED = 0xFFFF5555.toInt()

    private val SAMPLE = MobHealth("Voidgloom Seraph IV", 655, 68.6e6, 210e6, "68.6M", "210M")

    private var spansFor: String? = null
    private var spans: List<SoulRichText.Span> = emptyList()

    override fun isEnabled(): Boolean = cfg.combat.mobHealth.enabled && MobHealthTracker.current != null

    override fun configuredOn(): Boolean = cfg.combat.mobHealth.enabled

    override fun settingsScreen(parent: Screen?): Screen =
        SoulConfigScreen.forFeature(parent, "mobhealth")

    override fun readTransform(): HudTransform = cfg.combat.mobHealth.hud

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(combat = combat.copy(mobHealth = combat.mobHealth.copy(hud = transform))) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().combat.mobHealth.hud

    override fun menuToggles(): List<HudMenuToggle> =
        listOf(
            HudMenuToggle("Background", { cfg.combat.mobHealth.background }) {
                ConfigManager.update {
                    copy(combat = combat.copy(mobHealth = combat.mobHealth.copy(background = !combat.mobHealth.background)))
                }
            },
        )

    private fun shown(preview: Boolean): MobHealth? = MobHealthTracker.current ?: if (preview) SAMPLE else null

    /** The name goes through [SoulRichText] so symbols no MSDF family has (`✯`, `☠`) still draw. */
    private fun nameSpans(name: String): List<SoulRichText.Span> {
        if (spansFor != name) {
            spans = SoulRichText.spans(Component.literal(name))
            spansFor = name
        }
        return spans
    }

    private fun levelText(m: MobHealth): String = m.level?.let { "Lv$it" } ?: ""

    private fun healthText(m: MobHealth): String = if (m.maxText != null) "${m.currentText} / ${m.maxText}" else m.currentText

    private fun percentText(m: MobHealth): String = m.fraction?.let { MobNametag.percent(it) } ?: ""

    private fun font(bold: Boolean, choice: Int) = if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)

    private fun nameLine(): Int = (NAME_SIZE * 9f / 8f).roundToInt()

    private fun smallLine(): Int = (SMALL_SIZE * 9f / 8f).roundToInt()

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val m = shown(preview) ?: return 0 to 0
        val choice = fontChoiceIndex()
        val bold = boldFont()
        val top = SoulRichText.width(nameSpans(m.name), NAME_SIZE, choice, bold) + 8 +
            SoulText.width(levelText(m), SMALL_SIZE, font(bold, choice), fontChoice = choice)
        val bottom = SoulText.width(healthText(m), SMALL_SIZE, font(bold, choice), fontChoice = choice) + 8 +
            SoulText.width(percentText(m), SMALL_SIZE, font(bold, choice), fontChoice = choice)
        val w = maxOf(MIN_W, top, bottom) + PAD * 2
        val bar = if (m.fraction != null) BAR_H + GAP else 0
        return w to (PAD + nameLine() + GAP + bar + smallLine() + PAD)
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val m = shown(preview) ?: return
        val (w, h) = contentSize(preview)
        val choice = fontChoiceIndex()
        val bold = boldFont()
        val shadow = textShadow()
        val small = font(bold, choice)
        val mob = cfg.combat.mobHealth
        if (mob.background) rect(g, SoulRenderPipelines.ROUND_SMALL, 0, 0, w, h, mob.backgroundColor.effective())

        var y = PAD
        SoulRichText.draw(g, nameSpans(m.name), PAD, y, NAME_SIZE, NAME_COLOR, 255, choice, shadow, bold)
        val level = levelText(m)
        if (level.isNotEmpty()) {
            val lw = SoulText.width(level, SMALL_SIZE, small, fontChoice = choice)
            val ly = y + nameLine() - smallLine()
            SoulText.draw(g, level, w - PAD - lw, ly, SMALL_SIZE, DIM_COLOR, font = small, shadow = shadow, fontChoice = choice)
        }
        y += nameLine() + GAP

        val fraction = m.fraction
        val color = if (fraction == null || fraction > 0.5f) GREEN else if (fraction > 0.25f) YELLOW else RED
        if (fraction != null) {
            val barW = w - PAD * 2
            rect(g, SoulRenderPipelines.ROUND_PILL, PAD, y, PAD + barW, y + BAR_H, TRACK_COLOR)
            val fill = (barW * fraction).roundToInt()
            if (fill > 0) rect(g, SoulRenderPipelines.ROUND_PILL, PAD, y, PAD + fill.coerceAtLeast(BAR_H), y + BAR_H, color)
            y += BAR_H + GAP
        }

        SoulText.draw(g, healthText(m), PAD, y, SMALL_SIZE, color, font = small, shadow = shadow, fontChoice = choice)
        val pct = percentText(m)
        if (pct.isNotEmpty()) {
            val pw = SoulText.width(pct, SMALL_SIZE, small, fontChoice = choice)
            SoulText.draw(g, pct, w - PAD - pw, y, SMALL_SIZE, DIM_COLOR, font = small, shadow = shadow, fontChoice = choice)
        }
    }

    private fun rect(g: GuiGraphicsExtractor, pipeline: com.mojang.blaze3d.pipeline.RenderPipeline, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null),
        )
    }
}
