package com.soulreturns.features.scoreboard

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.Chroma
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulRichText
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f

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

    /** Look › When to show: tab list held, chat open, F3 open. */
    private fun hiddenNow(): Boolean {
        val look = cfg.scoreboard.look
        val mc = Minecraft.getInstance()
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

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val look = cfg.scoreboard.look
        val lines = lines(preview)
        val pad = look.padding.coerceIn(0, 20)
        val font = fontChoiceIndex()
        val bold = boldFont()
        val w = lines.maxOfOrNull { SoulRichText.width(spans(it.text), SIZE, font, bold) } ?: 0
        val h = if (lines.isEmpty()) LINE_H else lines.size * lineH(look) - look.lineSpacing.coerceIn(0, 10)
        return maxOf(MIN_W, w + pad * 2) to h + pad * 2
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val look = cfg.scoreboard.look
        val lines = lines(preview)
        if (lines.isEmpty()) return
        val (w, h) = contentSize(preview)
        val pad = look.padding.coerceIn(0, 20)
        val radius = look.cornerRadius.coerceIn(0, 20)
        if (look.background) {
            val alpha = (look.backgroundOpacity.coerceIn(0, 100) * 255 / 100)
            if (alpha > 0) rect(g, fillPipeline(radius), 0, 0, w, h, withAlpha(look.backgroundColor.effective(), alpha), radius == 0)
        }
        if (look.border) {
            val color = if ("border" in look.chromaOn) ChromaColor(WHITE, chroma = true).effective() else look.borderColor.effective()
            border(g, radius, w, h, color)
        }
        val font = fontChoiceIndex()
        val bold = boldFont()
        val step = lineH(look)
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
            SoulRichText.draw(g, spans, x, pad + i * step + 2, SIZE, WHITE, 255, font, look.textShadow, bold)
        }
    }

    private fun withAlpha(argb: Int, alpha: Int): Int = (alpha shl 24) or (argb and 0xFFFFFF)

    /** The SDF pipelines have fixed radii: 4, 6 and 10 (rule://render-layer); the nearest one draws. */
    private fun fillPipeline(radius: Int): RenderPipeline =
        if (radius <= 5) SoulRenderPipelines.ROUND_SMALL else if (radius <= 8) SoulRenderPipelines.ROUND_MEDIUM else SoulRenderPipelines.ROUND_LARGE

    private fun border(g: GuiGraphicsExtractor, radius: Int, w: Int, h: Int, color: Int) {
        if (radius == 0) {
            Chroma.fill(g, 0, 0, w, 2, color)
            Chroma.fill(g, 0, h - 2, w, h, color)
            Chroma.fill(g, 0, 2, 2, h - 2, color)
            Chroma.fill(g, w - 2, 2, w, h - 2, color)
            return
        }
        val pipeline = if (radius <= 5) SoulRenderPipelines.STROKE_2_R4 else if (radius <= 8) SoulRenderPipelines.STROKE_2_R7 else SoulRenderPipelines.STROKE_2_R10
        rect(g, pipeline, 0, 0, w, h, color, false)
    }

    private fun rect(g: GuiGraphicsExtractor, pipeline: RenderPipeline, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, square: Boolean) {
        if ((color ushr 24) == 0) return
        if (square) {
            Chroma.fill(g, x0, y0, x1, y1, color)
            return
        }
        g.guiRenderState.addGuiElement(RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null))
    }
}
