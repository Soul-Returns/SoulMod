package com.soulreturns.features.scoreboard

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.Chroma
import com.soulreturns.config.gui.render.GradientClock
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRichText
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.features.scoreboard.gui.ScoreboardSetupScreen
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen

/**
 * The scoreboard as a Soul HUD (spec `mocks/scoreboard/`, "The HUD: one Soul card, Baloo 2 at size 10,
 * line 13 + spacing"): the lines [Scoreboard.lines] built, on the Look's background and border, each line
 * aligned on its own. Hypixel's resource-pack glyphs and symbols no MSDF family has draw with the
 * Minecraft font in place ([SoulRichText]); chroma goes through the shared screen-space field.
 */
object ScoreboardHud : MovableHud("scoreboard", "Scoreboard", "general") {
    private const val SIZE = 10f
    private const val LINE_H = 13
    private const val MIN_W = 120

    private val WHITE = 0xFFFFFFFF.toInt()
    private val CHROMA_RGB: Int get() = ChromaColor(WHITE, chroma = true).effective() and 0xFFFFFF

    private val spanCache = HashMap<String, List<SoulRichText.Span>>()

    /** The Move GUI's sample: the 2026-11-15 Upper Mines capture (`src/test/resources/scoreboard/`). */
    private val SAMPLE: ScoreboardSnapshot by lazy {
        val s =
            ScoreboardSnapshot.ofSidebar(
                "§f§lSKYBLOCK §7♲",
                listOf(
                    "§711/15/26 §8m144BH", "", " Early Summer 11th", " §71:20am §b☽", " §7⏣ §bUpper Mines", "",
                    "Purse: §64,464,676 §e(+5)", "Bits: §b1,034", "", "§ewww.hypixel.net",
                ),
            )
        ScoreboardSnapshot(title = s.title, sidebar = s.sidebar, area = "Dwarven Mines", subLocation = "Upper Mines", profileType = ProfileType.IRONMAN)
    }

    override fun isEnabled(): Boolean = cfg.scoreboard.enabled && !hiddenNow() && Scoreboard.lines().isNotEmpty()

    override fun configuredOn(): Boolean = cfg.scoreboard.enabled

    override fun supportsTextShadow(): Boolean = false

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "scoreboard")

    /** Look › When to show: tab list held, chat open, F3 open; never under the setup screen, whose preview draws it. */
    private fun hiddenNow(): Boolean {
        val look = cfg.scoreboard.look
        val mc = Minecraft.getInstance()
        if (mc.gui.screen() is ScoreboardSetupScreen) return true
        if (look.hideWithTab && mc.options.keyPlayerList.isDown) return true
        if (look.hideWithChat && mc.gui.screen() is ChatScreen) return true
        if (look.hideWithF3 && mc.debugEntries.isOverlayVisible) return true
        return false
    }

    /** The activity whose own position applies now, or null for the Everywhere position. */
    private fun positionKey(c: ScoreboardConfig): String? = ScoreboardActivities.positioned(c, ScoreboardSources.snapshot)?.id

    override fun readTransform(): HudTransform {
        val c = cfg.scoreboard
        return positionKey(c)?.let { c.activityPositions[it] } ?: c.hud
    }

    override fun writeTransform(transform: HudTransform) {
        val key = positionKey(cfg.scoreboard)
        ConfigManager.update {
            copy(
                scoreboard =
                    if (key == null) scoreboard.copy(hud = transform) else scoreboard.copy(activityPositions = scoreboard.activityPositions + (key to transform)),
            )
        }
    }

    override fun defaultTransform(): HudTransform = ScoreboardConfig().hud

    private fun lines(preview: Boolean): List<BuiltLine> {
        val live = Scoreboard.lines()
        if (live.isNotEmpty() || !preview) return live
        val c = cfg.scoreboard
        return ScoreboardBuilder.build(c.main.entries, SAMPLE, c.look, System.currentTimeMillis())
    }

    private fun spans(text: String): List<SoulRichText.Span> {
        spanCache[text]?.let { return it }
        if (spanCache.size > 256) spanCache.clear()
        val chroma = CHROMA_RGB
        val s = LegacyText.runs(text).map { SoulRichText.Span(it.text, if (it.chroma) chroma else it.rgb, it.bold, it.underline, it.strike) }
        spanCache[text] = s
        return s
    }

    private fun lineH(look: ScoreboardLook) = LINE_H + look.lineSpacing.coerceIn(0, 10)

    override fun contentSize(preview: Boolean): Pair<Int, Int> = measure(lines(preview), cfg.scoreboard.look)

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) = draw(g, lines(preview), cfg.scoreboard.look)

    /** Box size of [lines] in [look]; the HUD and the setup screen's preview measure alike. */
    fun measure(lines: List<BuiltLine>, look: ScoreboardLook): Pair<Int, Int> {
        val pad = look.padding.coerceIn(0, 20)
        val font = fontChoiceIndex()
        val bold = boldFont()
        val w = lines.maxOfOrNull { SoulRichText.width(spans(it.text), SIZE, font, bold) } ?: 0
        val h = if (lines.isEmpty()) LINE_H else lines.size * lineH(look) - look.lineSpacing.coerceIn(0, 10)
        return maxOf(MIN_W, w + pad * 2) to h + pad * 2
    }

    /** Top of drawn row [index] inside the box, and the row height ([rowHeight]): the preview's hover outlines. */
    fun rowTop(index: Int, look: ScoreboardLook): Int = look.padding.coerceIn(0, 20) + index * lineH(look)

    fun rowHeight(): Int = LINE_H

    /** Draw [lines] in [look] with the box's top-left at the pose's origin: the HUD and the setup screen's preview. */
    fun draw(g: GuiGraphicsExtractor, lines: List<BuiltLine>, look: ScoreboardLook) {
        if (lines.isEmpty()) return
        val (w, h) = measure(lines, look)
        val pad = look.padding.coerceIn(0, 20)
        val fill =
            if (look.background) withAlpha(look.backgroundColor.effective(), look.backgroundOpacity.coerceIn(0, 100) * 255 / 100) else 0
        drawBox(g, w, h, look, fill)
        val font = fontChoiceIndex()
        val bold = boldFont()
        for ((i, line) in lines.withIndex()) {
            if (line.gap) continue
            val spans = spans(line.text)
            val lw = SoulRichText.width(spans, SIZE, font, bold)
            val x =
                when (line.align) {
                    "center" -> (w - lw) / 2
                    "right" -> w - pad - lw
                    else -> pad
                }
            // Text sits in the 13 px row like the mock's line box: cap top 2 px under the row top.
            SoulRichText.draw(g, spans, x, rowTop(i, look) + 2, SIZE, WHITE, 255, font, look.textShadow, bold)
        }
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0xFFFFFF)

    /** Turns the gradient border; shared by the HUD and the setup screen's preview. */
    private val borderClock = GradientClock()

    /**
     * Background and border as one [SoulPanel]: the 2 px border inside the box's edge, the background inset under it
     * with the concentric radius. Chroma on the border (Look › Chroma on › Border) beats its own colors.
     */
    private fun drawBox(g: GuiGraphicsExtractor, w: Int, h: Int, look: ScoreboardLook, fill: Int) {
        val radius = look.cornerRadius.coerceIn(0, 20).toFloat()
        if (!look.border) {
            SoulPanel.draw(g, 0, 0, w, h, radius, fill)
        } else if ("border" in look.chromaOn) {
            SoulPanel.draw(g, 0, 0, w, h, radius, fill, ChromaColor(WHITE, chroma = true).effective(), BORDER_W)
        } else if (look.borderStyle == ScoreboardLook.BORDER_SOLID) {
            SoulPanel.draw(g, 0, 0, w, h, radius, fill, look.borderColor.effective(), BORDER_W)
        } else {
            val phase = borderClock.phase(Chroma.cycleSeconds(look.borderSpeed.coerceIn(ScoreboardLook.MIN_BORDER_SPEED, ScoreboardLook.MAX_BORDER_SPEED)))
            SoulPanel.drawGradient(g, 0, 0, w, h, radius, fill, look.borderColor.effective(), look.borderColor2.effective(), phase, BORDER_W)
        }
    }

    private const val BORDER_W = 2f
}
