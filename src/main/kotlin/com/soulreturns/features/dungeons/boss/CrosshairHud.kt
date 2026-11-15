package com.soulreturns.features.dungeons.boss

import com.soulreturns.config.BossHelpersConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.Shapes
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.platform.ServerTime
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.HudElement
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sin

/**
 * The boss timers at the crosshair (spec and reference: `mocks/boss-timers/`, layout A): four fixed
 * places from [CrosshairTimers], each always meaning the same thing. Above the crosshair the clocks:
 * the pad tick (ring + digits) and over it Storm's phase line; below it what you do: the action line
 * and under it the crush window or its result. Not a movable HUD: anchored on the crosshair, laid
 * out in Soul px with the mock's CSS metrics (Baloo 2 SemiBold, line-height 1).
 */
object CrosshairHud : HudElement("boss_timers") {
    /** Baloo 2 (OS/2 typo metrics, line-height 1): the baseline sits 0.777 em below a line's top. */
    private const val BASELINE_IN_LINE = 0.777f
    /** SoulText draws capitals from y; their top is the baseline minus the cap height (7/8 of the nominal size). */
    private val CAP_EM = MsdfFont.CAP_HEIGHT * MsdfFont.LEGACY_EM

    private const val RING_GAP = 7f
    private const val RING_CLEAR = 8f
    private const val STROKE = 1.6f
    private const val TRACK_DARK = 0x73000000
    private const val TRACK_LIGHT = 0x38FFFFFF
    private const val HOT_SCALE = 1.14f

    override fun isEnabled(): Boolean = (cfg.dungeons.boss.crushWindow && F7Boss.model != null) || DragonCountdown.next() != null

    fun options(b: BossHelpersConfig = cfg.dungeons.boss) = CrosshairOptions(
        ticks = b.units == 1, pad = b.showPad, goldor = b.showGoldor, action = b.showAction, purple = b.showPurple, arm = b.showArm,
        window = b.showWindow, giga = b.showGiga, high = b.showHigh, padWhen = b.padWhen, stayTime = b.stayTime, goldorRing = b.goldorRing,
        lateAmount = b.lateAmount, eta = b.stormEta, fireball = b.nextFireball, terminals = b.showTerminals,
    )

    @Volatile private var extentTop = 0f
    @Volatile private var extentBottom = 0f
    @Volatile private var extentAt = 0L

    /** Top and bottom (Soul px) of what the timers drew last frame, or null when they are not showing. */
    fun extent(): Pair<Float, Float>? =
        if (System.nanoTime() - extentAt < EXTENT_FRESH_NS) extentTop to extentBottom else null

    private const val EXTENT_FRESH_NS = 250_000_000L

    override fun extract(g: GuiGraphicsExtractor) {
        val m = F7Boss.model?.takeIf { cfg.dungeons.boss.crushWindow }
        val b = cfg.dungeons.boss
        val o = options(b)
        var v = if (m != null) {
            CrosshairTimers.view(CrosshairTimers.places(m, ServerTime.exact() ?: m.now.toDouble(), o), o, !ServerTime.fresh())
        } else {
            PlacesView(null, null, null, null, false)
        }
        // P5: the next dragon's spawn takes the phase place (nothing else is there then).
        val dragon = DragonCountdown.next()
        if (dragon != null && v.phase == null) {
            v = PlacesView(v.pad, v.action, v.window, PhaseView("${dragon.first.name.uppercase()} DRAGON", CrosshairTimers.number(dragon.second, o), null, dragon.first.color), v.title)
        }
        if (v.empty) return
        val font = MsdfFonts.semiboldFor(MsdfFonts.BALOO2)
        if (!font.loaded) return
        val mc = Minecraft.getInstance()
        val factor = SoulUi.factor()
        val gs = mc.window.guiScale.toFloat() / SoulUi.scale
        val px = 1f / SoulUi.scale
        // Vanilla draws the 15 px crosshair sprite at ((w - 15) / 2, (h - 15) / 2) in GUI px.
        val cx = ((g.guiWidth() - 15) / 2 + 7.5f) / factor
        val cy = ((g.guiHeight() - 15) / 2 + 7.5f) / factor
        val r = 5.5f * gs + RING_GAP
        val d = b.digitSize * 0.92f / MsdfFont.LEGACY_EM
        val top = max(b.offset, r + RING_CLEAR)
        // The vanilla Giga title sits 4..40 GUI px above the center: while one is up the clocks lift above it.
        val lift = if (v.title) 40f * gs + 8f else r + 6f
        var spanTop = cy
        var spanBottom = cy
        if (v.pad != null) spanTop = minOf(spanTop, cy - lift - 1.1f * d)
        if (v.phase != null) spanTop = minOf(spanTop, cy - lift - 1.04f * d - 0.5f * d)
        if (v.action != null) spanBottom = maxOf(spanBottom, cy + top + 0.54f * d)
        if (v.window != null) spanBottom = maxOf(spanBottom, cy + top + 0.66f * d + 2.2f * (b.digitSize * 0.55f / MsdfFont.LEGACY_EM))
        extentTop = spanTop
        extentBottom = spanBottom
        extentAt = System.nanoTime()
        v.pad?.let { p ->
            if (p.ring) ring(g, p, cx, cy, r, px)
            digits(g, p.text, cx - cellsWidth(p.text, d, font) / 2f, cy - lift - d + BASELINE_IN_LINE * d, d, p.color, font, glow(d, 1f))
        }
        v.phase?.let { phase(g, it, cx, cy - lift - 1.04f * d, 0.46f * d, font) }
        v.action?.let { action(g, it, cx, cy + top, 0.54f * d, font) }
        v.window?.let { block(g, it, cx, cy + top + 0.66f * d, b.digitSize * 0.55f, px, font) }
    }

    private fun ring(g: GuiGraphicsExtractor, v: PadView, cx: Float, cy: Float, r: Float, px: Float) {
        val s = Shapes(px)
        s.arc(cx, cy, r, STROKE + 1.6f, 0.0, 2 * PI, TRACK_DARK)
        s.arc(cx, cy, r, STROKE, 0.0, 2 * PI, TRACK_LIGHT)
        s.arc(cx, cy, r, STROKE, 0.0, 2 * PI * v.fraction, v.color)
        s.submit(g)
    }

    /** `WORD 12.3 · next fireball 1.2` on one baseline, its bottom edge at [bottom]; [em] = the digits' size. */
    private fun phase(g: GuiGraphicsExtractor, v: PhaseView, cx: Float, bottom: Float, em: Float, font: MsdfFont) {
        val color = alpha(v.color, 0.92f)
        val small = 0.7f * em
        val tracking = 0.06f * small
        val space = font.widthF(" ", em * MsdfFont.LEGACY_EM)
        val fb = v.fireball
        val width = runWidth(v.word, small, tracking, font) + space + cellsWidth(v.digits, em, font) + (fb?.let { runWidth(it, small, 0f, font) } ?: 0f)
        val baseline = bottom - em + BASELINE_IN_LINE * em
        var x = cx - width / 2f
        x += run(g, v.word, x, baseline, small, tracking, color, font, glow(em, 0.92f)) + space
        x += digits(g, v.digits, x, baseline, em, color, font, glow(em, 0.92f))
        if (fb != null) run(g, fb, x, baseline, small, 0f, alpha(v.color, 0.92f * 0.85f), font, glow(em, 0.92f * 0.85f))
    }

    /** The action line from [top]: words letter-spaced 0.05 em, then the time in tabular digits. */
    private fun action(g: GuiGraphicsExtractor, v: ActionView, cx: Float, top: Float, em: Float, font: MsdfFont) {
        val a = v.alpha.toFloat().coerceIn(0f, 1f)
        if (a <= 0f) return
        val tracking = 0.05f * em
        val time = v.time
        val width = runWidth(v.text, em, tracking, font) + (time?.let { cellsWidth(it, em, font) } ?: 0f)
        val baseline = top + BASELINE_IN_LINE * em
        val copies = glow(em, a)
        val x = cx - width / 2f
        val w = run(g, v.text, x, baseline, em, tracking, alpha(v.color, a), font, copies)
        if (time != null) digits(g, time, x + w, baseline, em, alpha(v.color, a), font, copies)
    }

    /** The crush window: label, body and hint centered on [cx] from [top] down; [nominal] = the digits' SoulText size. */
    private fun block(g: GuiGraphicsExtractor, v: BlockView, cx: Float, top: Float, nominal: Float, px: Float, font: MsdfFont) {
        val em = nominal / MsdfFont.LEGACY_EM
        val a = v.alpha.toFloat().coerceIn(0f, 1f)
        if (a <= 0f) return
        val glow = glow(em, a)
        // Label: 0.42 em, tracking 0.06 of its size, opacity .95, then 0.08 em gap.
        line(g, v.label, cx, top, 0.42f * em, 0.06f * 0.42f * em, alpha(v.color, 0.95f * a), font, glow)
        val mainTop = top + 0.50f * em
        val mainH: Float
        val pose = g.pose()
        if (v.body == CrosshairTimers.BODY_DIGITS || v.body == CrosshairTimers.BODY_WORD) {
            mainH = if (v.body == CrosshairTimers.BODY_WORD) 0.72f * em else em
            if (v.hot) {
                pose.pushMatrix()
                pose.translate(cx, mainTop + mainH)
                pose.scale(HOT_SCALE, HOT_SCALE)
                pose.translate(-cx, -(mainTop + mainH))
            }
            val copies = if (v.hot) hotGlow(em, v.color, a) else glow
            if (v.body == CrosshairTimers.BODY_WORD) {
                line(g, v.bodyText, cx, mainTop, 0.72f * em, 0.04f * 0.72f * em, alpha(v.color, a), font, copies)
            } else {
                digits(g, v.bodyText, cx - cellsWidth(v.bodyText, em, font) / 2f, mainTop + BASELINE_IN_LINE * em, em, alpha(v.color, a), font, copies)
            }
            if (v.hot) pose.popMatrix()
        } else {
            mainH = 0.8f * em
            // The mock sizes the icon box by the nominal digit size: check 0.8x, cross 0.7x, square, centered in 0.8 em.
            val side = (if (v.body == CrosshairTimers.BODY_CHECK) 0.8f else 0.7f) * nominal
            icon(g, v.body == CrosshairTimers.BODY_CHECK, cx - side / 2, mainTop + (mainH - side) / 2, side, alpha(v.color, a), px)
        }
        v.hint?.let { line(g, it, cx, mainTop + mainH + 0.10f * em, 0.44f * em, 0.02f * 0.44f * em, alpha(v.hintColor, 0.9f * a), font, glow) }
    }

    /** One centered line in a CSS line box of [css] px from [lineTop]; tracking after every glyph like CSS letter-spacing. */
    private fun line(g: GuiGraphicsExtractor, text: String, cx: Float, lineTop: Float, css: Float, tracking: Float, color: Int, font: MsdfFont, copies: List<SoulText.Copy>) {
        run(g, text, cx - runWidth(text, css, tracking, font) / 2f, lineTop + BASELINE_IN_LINE * css, css, tracking, color, font, copies)
    }

    private fun runWidth(text: String, css: Float, tracking: Float, font: MsdfFont): Float = font.widthF(text, css * MsdfFont.LEGACY_EM) + tracking * text.length

    /** Text from [x] on [baseline] at [css] px; returns its advance. */
    private fun run(g: GuiGraphicsExtractor, text: String, x: Float, baseline: Float, css: Float, tracking: Float, color: Int, font: MsdfFont, copies: List<SoulText.Copy>): Float {
        SoulText.drawWithCopies(g, text, x, baseline - CAP_EM * css, css * MsdfFont.LEGACY_EM, color, font, tracking, copies)
        return runWidth(text, css, tracking, font)
    }

    /** Tabular digits: 0.6 em cells, a 0.26 em cell for `.`, anything else its width plus 0.04 em each side. */
    private fun cells(text: String, em: Float, font: MsdfFont): List<Float> =
        text.map { c -> if (c.isDigit()) 0.6f * em else if (c == '.') 0.26f * em else font.widthF(c.toString(), em * MsdfFont.LEGACY_EM) + 0.08f * em }

    private fun cellsWidth(text: String, em: Float, font: MsdfFont): Float = cells(text, em, font).sum()

    /** Tabular digits from [left] on [baseline]; returns their width. */
    private fun digits(g: GuiGraphicsExtractor, text: String, left: Float, baseline: Float, em: Float, color: Int, font: MsdfFont, copies: List<SoulText.Copy>): Float {
        val size = em * MsdfFont.LEGACY_EM
        val cells = cells(text, em, font)
        var x = left
        val y = baseline - CAP_EM * em
        for ((i, c) in text.withIndex()) {
            val s = c.toString()
            SoulText.drawWithCopies(g, s, x + (cells[i] - font.widthF(s, size)) / 2f, y, size, color, font, 0f, copies)
            x += cells[i]
        }
        return x - left
    }

    /** The mock's SVG icons on a 12-unit box: a check (stroke 2, round) or a cross. */
    private fun icon(g: GuiGraphicsExtractor, check: Boolean, x: Float, y: Float, side: Float, color: Int, px: Float) {
        val u = side / 12f
        val w = 2f * u
        val s = Shapes(px)
        if (check) {
            s.line(x + 1.8f * u, y + 6.4f * u, x + 4.6f * u, y + 9.2f * u, w, color)
            s.line(x + 4.6f * u, y + 9.2f * u, x + 10.2f * u, y + 3.0f * u, w, color)
        } else {
            s.line(x + 2.4f * u, y + 2.4f * u, x + 9.6f * u, y + 9.6f * u, w, color)
            s.line(x + 9.6f * u, y + 2.4f * u, x + 2.4f * u, y + 9.6f * u, w, color)
        }
        s.submit(g)
    }

    // ------------------------------------------------------------------ glow (CSS text-shadow, approximated by copies)

    /** `0 0 0.08em #000, 0 0 0.2em rgba(0,0,0,.85), 0.03em 0.03em 0 rgba(0,0,0,.9)` on the digit em. */
    private fun glow(em: Float, a: Float): List<SoulText.Copy> =
        blur(0.2f * em, 0xFF000000.toInt(), 0.85f * a) + blur(0.08f * em, 0xFF000000.toInt(), a) +
            SoulText.Copy(0.03f * em, 0.03f * em, alpha(0xFF000000.toInt(), 0.9f * a), soft = false)

    /** The last second: `0 0 0.26em <color>, 0 0 0.08em #000`. */
    private fun hotGlow(em: Float, color: Int, a: Float): List<SoulText.Copy> =
        blur(0.26f * em, color, a) + blur(0.08f * em, 0xFF000000.toInt(), a)

    /**
     * A CSS blur radius [radius] (Gaussian sigma = radius / 2) as soft-edged copies on three rings,
     * weighted by the Gaussian and scaled so the composite reaches about [strength] next to the glyph.
     */
    private fun blur(radius: Float, color: Int, strength: Float): List<SoulText.Copy> {
        val sigma = radius / 2f
        val rings = floatArrayOf(0f, 0.7f, 1.4f, 2.2f)
        val counts = intArrayOf(1, 6, 10, 14)
        val weights = ArrayList<Float>()
        val out = ArrayList<Pair<Pair<Float, Float>, Float>>()
        for ((k, f) in rings.withIndex()) {
            val n = counts[k]
            val w = exp(-(f * f) / 2f)
            for (i in 0 until n) {
                val ang = 2 * PI * i / n + k * 0.4
                out += ((f * sigma * cos(ang).toFloat()) to (f * sigma * sin(ang).toFloat())) to w
                weights += w
            }
        }
        val total = weights.sum()
        return out.map { (d, w) -> SoulText.Copy(d.first, d.second, alpha(color, (strength * BLUR_GAIN * w / total).coerceAtMost(1f)), soft = true) }
    }

    /** Overlapping translucent copies composite below their summed alpha; this brings the halo near the CSS one. */
    private const val BLUR_GAIN = 2.2f

    private fun alpha(color: Int, a: Float): Int {
        val base = (color ushr 24 and 0xFF) / 255f
        val v = (base * a * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (v shl 24) or (color and 0x00FFFFFF)
    }
}
