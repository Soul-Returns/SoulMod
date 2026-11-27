package com.soulreturns.features.pets

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.PetHudConfig
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulRichText
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.pets.PetHudModel.Run
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * The Pet HUD: the active pet as a Card (default) or a Compact line, every element its own toggle, the
 * name always on. Spec and every number: `mocks/pet-hud/` (`mock.js` `PET`, `cardHtml`, `lineHtml`,
 * `mock.css` animations). Data: [PetState] / [PetTracker]; rules: rule://pet-hud.
 */
object PetHud : MovableHud("pet_hud", "Pet HUD", "general") {
    private const val BADGE_MS = 2600L
    private const val SWAP_MS = 320L
    private const val LEVEL_FILL_MS = 470L
    private const val GLOW_MS = 900L
    private const val POP_MS = 600L
    private const val FILL_MS = 450L
    private const val GONE_MS = 260L
    private const val SHINE_MS = 3200L
    private const val POP_SCALE = 1.35f

    private const val BADGE_AUTOPET = 0xFFD94040.toInt()
    private const val BADGE_LEVEL = 0xFF3FA34D.toInt()
    private const val BADGE_SUMMON = 0xFF3B82F6.toInt()

    /** Move GUI sample while there is nothing live to place (Griffin, Lvl 87, 27 %). */
    private val SAMPLE: PetView = run {
        val rarity = SkyblockRarity.LEGENDARY
        val need = PetLevels.need(87, rarity, "Griffin").toDouble()
        val into = (0.27 * need).toLong().toDouble()
        PetView("Griffin", rarity, false, 87, 100, false, into, need, null, PetLevels.levelXp(87, rarity, "Griffin") + into, PetHeld("Antique Remedies", '5'), null, 1_200_000.0)
    }

    // ------------------------------------------------------------------ effects (client thread)
    private var badgeText: String? = null
    private var badgeColor = 0
    private var badgeAt = 0L
    private var swapAt = 0L
    private var levelPendingUntil = 0L
    private var levelFrom = 0
    private var levelDoneAt = 0L
    private var goneAt = 0L
    private var lastShown: Shown? = null

    private var fillFrom = 0.0
    private var fillTo = -1.0
    private var fillStart = 0L
    private var fillJump = true

    private class Shown(val view: PetView?, val state: Int)

    fun register() {
        PetState.tracker.fx = ::onFx
    }

    private fun onFx(fx: PetFx) {
        val c = cfg.petHud
        val now = System.currentTimeMillis()
        if (fx is PetFx.Swap) {
            if (c.animations) swapAt = now
            levelPendingUntil = 0L
            if (PetState.tracker.state != PetTracker.ACTIVE && c.noPet == PetHudFormats.HIDE_NO_PET && c.animations) goneAt = now
            fx.badge?.let { badge(it, if (it == "AUTOPET") BADGE_AUTOPET else BADGE_SUMMON, now) }
        } else if (fx is PetFx.LevelUp) {
            levelFrom = fx.from
            if (c.animations) {
                levelPendingUntil = now + LEVEL_FILL_MS
                levelDoneAt = now + LEVEL_FILL_MS
            } else {
                levelPendingUntil = 0L
                levelDoneAt = 0L
                fillJump = true
            }
            pendingBadge = if (fx.maxed) "MAX LEVEL" else "LEVEL UP"
            if (!c.animations) flushLevelBadge(now)
        }
    }

    private var pendingBadge: String? = null

    private fun flushLevelBadge(now: Long) {
        val text = pendingBadge ?: return
        pendingBadge = null
        badge(text, BADGE_LEVEL, now)
    }

    private fun badge(text: String, color: Int, now: Long) {
        if (!cfg.petHud.badges) return
        badgeText = text
        badgeColor = color
        badgeAt = now
    }

    // ------------------------------------------------------------------ MovableHud

    override fun isEnabled(): Boolean {
        val c = cfg.petHud
        if (!c.enabled || !SkyblockState.onSkyblock) return false
        return PetState.tracker.view(System.currentTimeMillis()) != null || c.noPet == PetHudFormats.SHOW_NO_PET || fading(System.currentTimeMillis())
    }

    override fun configuredOn(): Boolean = cfg.petHud.enabled

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "pethud")

    override fun readTransform(): HudTransform = cfg.petHud.hud

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(petHud = petHud.copy(hud = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().petHud.hud

    override fun menuToggles(): List<HudMenuToggle> =
        listOf(
            HudMenuToggle("Background", { cfg.petHud.background }) {
                ConfigManager.update { copy(petHud = petHud.copy(background = !petHud.background)) }
            },
        )

    private fun fading(now: Long): Boolean = goneAt > 0 && now - goneAt < GONE_MS && lastShown?.view != null

    /** What to draw now: the live pet, the "No pet" card, the fading last pet, or the Move GUI sample. */
    private fun shown(preview: Boolean, now: Long): Shown? {
        val c = cfg.petHud
        val tracker = PetState.tracker
        val view = tracker.view(now)
        if (view != null) return Shown(view, PetTracker.ACTIVE)
        if (c.noPet == PetHudFormats.SHOW_NO_PET) return Shown(null, tracker.state)
        if (fading(now)) return lastShown
        return if (preview) Shown(SAMPLE, PetTracker.ACTIVE) else null
    }

    private val measure = PetHudModel.Measure { runs, size -> SoulRichText.width(spans(runs), size, fontChoiceIndex(), boldFont()) }

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val s = shown(preview, System.currentTimeMillis()) ?: return 0 to 0
        val c = cfg.petHud
        val view = displayView(s.view, System.currentTimeMillis())
        return if (c.style == PetHudFormats.COMPACT) {
            PetHudModel.measureLine(PetHudModel.line(view, s.state, c), measure)
        } else {
            val box = PetHudModel.measureCard(PetHudModel.card(view, s.state, c), measure)
            box.width to box.height
        }
    }

    /** During a level-up's fill run the old level still shows. */
    private fun displayView(view: PetView?, now: Long): PetView? {
        if (view == null || now >= levelPendingUntil) return view
        return view.copy(level = levelFrom)
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val now = System.currentTimeMillis()
        val s = shown(preview, now) ?: return
        if (s.view != null && !fading(now)) lastShown = s
        if (levelDoneAt in 1..now && pendingBadge != null) {
            fillJump = true
            flushLevelBadge(levelDoneAt)
        }
        val c = cfg.petHud
        val view = displayView(s.view, now)
        val alpha = if (s.view != null && PetState.tracker.state != PetTracker.ACTIVE && fading(now)) 1f - (now - goneAt).toFloat() / GONE_MS else 1f
        val target = if (now < levelPendingUntil) 1.0 else view?.fraction
        val fill = target?.let { animatedFill(it, now) }

        if (c.style == PetHudFormats.COMPACT) {
            val line = PetHudModel.line(view, s.state, c)
            val (w, h) = PetHudModel.measureLine(line, measure)
            glow(g, view, w, h, now, c)
            if (c.background) rect(g, SoulRenderPipelines.ROUND_SMALL, 0, 0, w, h, fade(c.backgroundColor.effective(), alpha))
            withSwap(g, now, c) { a -> drawLine(g, line, view, fill, h, alpha * a, now, c) }
            drawBadge(g, w, now, alpha)
        } else {
            val card = PetHudModel.card(view, s.state, c)
            val box = PetHudModel.measureCard(card, measure)
            glow(g, view, box.width, box.height, now, c)
            if (c.background) rect(g, SoulRenderPipelines.ROUND_SMALL, 0, 0, box.width, box.height, fade(c.backgroundColor.effective(), alpha))
            withSwap(g, now, c) { a -> drawCard(g, card, box, view, fill, alpha * a, now, c) }
            drawBadge(g, box.width, now, alpha)
        }
    }

    /** Swap: the new pet slides in from 5 px left and fades in (`ph-swap`, .32 s ease-out). */
    private inline fun withSwap(g: GuiGraphicsExtractor, now: Long, c: PetHudConfig, body: (Float) -> Unit) {
        val t = if (c.animations && swapAt > 0) (now - swapAt).toFloat() / SWAP_MS else 1f
        if (t >= 1f) {
            body(1f)
            return
        }
        val e = Bezier.EASE_OUT.at(t.toDouble()).toFloat()
        g.pose().pushMatrix()
        g.pose().translate(-5f * (1f - e), 0f)
        body(e)
        g.pose().popMatrix()
    }

    // ------------------------------------------------------------------ card

    private fun drawCard(g: GuiGraphicsExtractor, card: PetHudModel.Card, box: PetHudModel.CardBox, view: PetView?, fill: Double?, alpha: Float, now: Long, c: PetHudConfig) {
        val pad = PetHudModel.PAD
        val nl = PetHudModel.line(PetHudModel.NAME)
        val sl = PetHudModel.line(PetHudModel.SMALL)
        var x = pad
        if (card.icon) {
            icon(g, view, x, pad + box.iconTop, PetHudModel.ICON, alpha)
            x += PetHudModel.ICON + PetHudModel.ICON_GAP
        }
        val right = x + box.columnW
        var y = pad
        if (card.name == null) {
            text(g, listOfNotNull(card.emptyTitle), x, y, PetHudModel.NAME, alpha)
            y += nl + PetHudModel.GAP
            text(g, listOfNotNull(card.emptyHint), x, y, PetHudModel.SMALL, alpha)
            return
        }
        text(g, card.name, x, y, PetHudModel.NAME, alpha)
        card.level?.let { lv -> levelText(g, lv, right, y + nl - sl, alpha, now, c) }
        y += nl
        if (card.bar && view != null) {
            y += PetHudModel.GAP
            bar(g, x, y, right, PetHudModel.BAR_H, fill ?: 0.0, view, alpha, now, c)
            y += PetHudModel.BAR_H
        }
        val rows = listOfNotNull(card.xpRow) + card.rows
        for (row in rows) {
            y += PetHudModel.GAP
            if (row.left.isNotEmpty()) text(g, row.left, x, y, PetHudModel.SMALL, alpha)
            if (row.right.isNotEmpty()) {
                val rw = measure.width(row.right, PetHudModel.SMALL)
                text(g, row.right, right - rw, y, PetHudModel.SMALL, alpha)
            }
            y += sl
        }
    }

    /**
     * The level, right-aligned at [right]; on a level-up it pops (scale 1.35 and white at 30 %, ease-out per
     * segment). The card scales it around 100 % / 80 % like the mock. [slack] set (the compact line) scales it
     * around its own center instead, capped so it grows at most [slack] px past each side: the level never
     * covers the icon or the name next to it.
     */
    private fun levelText(g: GuiGraphicsExtractor, lv: Run, right: Int, y: Int, alpha: Float, now: Long, c: PetHudConfig, slack: Int? = null) {
        val w = measure.width(listOf(lv), PetHudModel.SMALL)
        val sl = PetHudModel.line(PetHudModel.SMALL)
        val t = if (c.animations && levelDoneAt in 1..now) (now - levelDoneAt).toFloat() / POP_MS else 1f
        if (t >= 1f) {
            text(g, listOf(lv), right - w, y, PetHudModel.SMALL, alpha)
            return
        }
        val k = if (t < 0.3f) Bezier.EASE_OUT.at(t / 0.3).toFloat() else 1f - Bezier.EASE_OUT.at((t - 0.3) / 0.7).toFloat()
        val grow = if (slack == null || w <= 0) POP_SCALE - 1f else minOf(POP_SCALE - 1f, 2f * slack / w)
        val scale = 1f + grow * k
        val color = mix(lv.color, PetHudModel.WHITE, k)
        // Pivot: the mock's 100 % / 80 % on the card; the text's own center on the line.
        val px = if (slack == null) right.toFloat() else right - w / 2f
        val py = if (slack == null) y + sl * 0.8f else y + sl / 2f
        g.pose().pushMatrix()
        g.pose().translate(px, py)
        g.pose().scale(scale, scale)
        g.pose().translate(-px, -py)
        text(g, listOf(lv.copy(color = color)), right - w, y, PetHudModel.SMALL, alpha)
        g.pose().popMatrix()
    }

    // ------------------------------------------------------------------ line

    private fun drawLine(g: GuiGraphicsExtractor, line: PetHudModel.Line, view: PetView?, fill: Double?, h: Int, alpha: Float, now: Long, c: PetHudConfig) {
        val nl = PetHudModel.line(PetHudModel.NAME)
        val sl = PetHudModel.line(PetHudModel.SMALL)
        val top = PetHudModel.LINE_PAD_Y
        var x = PetHudModel.LINE_PAD_X
        if (line.icon) {
            icon(g, view, x, top + (nl - PetHudModel.LINE_ICON) / 2, PetHudModel.LINE_ICON, alpha)
            x += PetHudModel.LINE_ICON + PetHudModel.LINE_GAP
        }
        val smallY = top + (nl - sl) / 2
        if (line.name == null) {
            text(g, listOfNotNull(line.empty), x, top, PetHudModel.NAME, alpha)
            return
        }
        line.level?.let { lv ->
            val w = measure.width(listOf(lv), PetHudModel.SMALL)
            // The pop stays within the gaps around the level: icon (or padding) left, name right.
            val slack = minOf(PetHudModel.LINE_LV_GAP, if (line.icon) PetHudModel.LINE_GAP else PetHudModel.LINE_PAD_X)
            levelText(g, lv, x + w, smallY, alpha, now, c, slack)
            x += w + PetHudModel.LINE_LV_GAP
        }
        text(g, line.name, x, top, PetHudModel.NAME, alpha)
        x += measure.width(line.name, PetHudModel.NAME)
        if (line.bar && view != null) {
            x += PetHudModel.LINE_BAR_GAP
            val by = top + (nl - PetHudModel.LINE_BAR_H) / 2
            bar(g, x, by, x + PetHudModel.LINE_BAR_W, PetHudModel.LINE_BAR_H, fill ?: 0.0, view, alpha, now, c)
            x += PetHudModel.LINE_BAR_W
        }
        if (line.tail.isNotEmpty()) text(g, line.tail, x + PetHudModel.LINE_GAP, smallY, PetHudModel.SMALL, alpha)
    }

    // ------------------------------------------------------------------ bar

    /** The fill eases to a new value over .45 s (cubic-bezier(.3,.7,.3,1)); a level-up's restart jumps. */
    private fun animatedFill(target: Double, now: Long): Double {
        if (fillJump || fillTo < 0) {
            fillJump = false
            fillFrom = target
            fillTo = target
            fillStart = now
            return target
        }
        if (target != fillTo) {
            fillFrom = currentFill(now)
            fillTo = target
            fillStart = now
        }
        return currentFill(now)
    }

    private fun currentFill(now: Long): Double {
        val t = (now - fillStart).toDouble() / FILL_MS
        if (t >= 1.0) return fillTo
        return fillFrom + (fillTo - fillFrom) * Bezier.FILL.at(t)
    }

    private fun bar(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, h: Int, fraction: Double, view: PetView, alpha: Float, now: Long, c: PetHudConfig) {
        rect(g, SoulRenderPipelines.ROUND_PILL, x0, y0, x1, y0 + h, fade(PetHudModel.TRACK, alpha))
        val w = x1 - x0
        val fw = maxOf(h, (w * fraction.coerceIn(0.0, 1.0)).roundToInt())
        val fx1 = x0 + fw
        // A chroma bar color is a window onto Soul's shared screen-space rainbow (render/Chroma), like every chroma color.
        val color = if (c.barMode == PetHudFormats.BAR_RARITY) PetHudModel.rarityColor(view) else c.barColor.effective()
        rect(g, SoulRenderPipelines.ROUND_PILL, x0, y0, fx1, y0 + h, fade(color, alpha))
        if (c.animations && fraction >= 1.0) shine(g, x0, y0, fx1, h, now, alpha)
    }

    /** Max level: a slow highlight runs over the full bar (`ph-shine`, 3.2 s, peak .55 white). */
    private fun shine(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, h: Int, now: Long, alpha: Float) {
        val w = (x1 - x0).toFloat()
        val peak = -0.5f * w + 3f * w * ((now % SHINE_MS).toFloat() / SHINE_MS)
        val half = 0.45f * w
        val r = h / 2
        for (x in x0 + r until x1 - r) {
            val k = 1f - abs(x + 0.5f - x0 - peak) / half
            if (k <= 0f) continue
            g.fill(x, y0, x + 1, y0 + h, ((0.55f * k * alpha * 255).roundToInt() shl 24) or 0xFFFFFF)
        }
    }

    // ------------------------------------------------------------------ icon, badge, glow

    private fun icon(g: GuiGraphicsExtractor, view: PetView?, x: Int, y: Int, size: Int, alpha: Float) {
        val look = view?.icon
        if (look != null) {
            if (alpha < 0.5f) return
            val stack = InventoryButtonIcons.stack(InventoryButtonIcons.ItemLook(look.item, look.skin, look.model, look.glint))
            g.pose().pushMatrix()
            g.pose().translate(x.toFloat(), y.toFloat())
            g.pose().scale(size / 16f, size / 16f)
            g.fakeItem(stack, 0, 0)
            g.pose().popMatrix()
            return
        }
        if (view == null) {
            ring(g, x, y, size, fade(PetHudModel.EMPTY_ICON, alpha))
            return
        }
        // Not seen in a menu yet: a ring in the rarity color with the pet's initial.
        val color = PetHudModel.rarityColor(view)
        ring(g, x, y, size, fade((color and 0xFFFFFF) or 0x99000000.toInt(), alpha))
        val initial = listOf(Run(view.name.take(1).uppercase(), color))
        val ts = if (size >= 16) PetHudModel.SMALL else 6f
        val tw = measure.width(initial, ts)
        text(g, initial, x + (size - tw) / 2, y + ((size - ts * 7f / 8f) / 2f).roundToInt(), ts, alpha)
    }

    /** A 1 px ring inscribed in a [size] square, scanline-filled at quarter-pixel rows. */
    private fun ring(g: GuiGraphicsExtractor, x: Int, y: Int, size: Int, color: Int) {
        val q = 4
        val r = size * q / 2f
        val ri = r - q
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(1f / q, 1f / q)
        for (row in 0 until size * q) {
            val dy = row + 0.5f - r
            val xo = sqrt(maxOf(0f, r * r - dy * dy))
            val xi = if (abs(dy) < ri) sqrt(ri * ri - dy * dy) else 0f
            val a0 = (r - xo).roundToInt()
            val b1 = (r + xo).roundToInt()
            if (xi <= 0f) {
                if (b1 > a0) g.fill(a0, row, b1, row + 1, color)
            } else {
                val a1 = (r - xi).roundToInt()
                val b0 = (r + xi).roundToInt()
                if (a1 > a0) g.fill(a0, row, a1, row + 1, color)
                if (b1 > b0) g.fill(b0, row, b1, row + 1, color)
            }
        }
        g.pose().popMatrix()
    }

    /** AUTOPET / LEVEL UP / SUMMONED riding on the top edge, 6 px from the right (`ph-badge`, 2.6 s). */
    private fun drawBadge(g: GuiGraphicsExtractor, w: Int, now: Long, alpha: Float) {
        val text = badgeText ?: return
        val t = (now - badgeAt).toFloat() / BADGE_MS
        if (t < 0f) return
        if (t >= 1f || !cfg.petHud.badges) {
            badgeText = null
            return
        }
        val op = if (t < 0.08f) t / 0.08f else if (t <= 0.82f) 1f else 1f - (t - 0.82f) / 0.18f
        val dy = if (t < 0.08f) 2f * (1f - t / 0.08f) else 0f
        val size = PetHudModel.BADGE - 0.5f
        val runs = listOf(Run(text, PetHudModel.WHITE, bold = true))
        val tw = measure.width(runs, size)
        val x1 = w - 6
        val x0 = x1 - tw - 8
        g.pose().pushMatrix()
        g.pose().translate(0f, dy)
        rect(g, SoulRenderPipelines.ROUND_PILL, x0, -5, x1, 4, fade(badgeColor, op * alpha))
        text(g, runs, x0 + 4, -5 + ((9 - size * 7f / 8f) / 2f).roundToInt(), size, op * alpha)
        g.pose().popMatrix()
    }

    /** Level up: a glow in the rarity color around the card (`ph-glow`, .9 s, peak at 25 %). */
    private fun glow(g: GuiGraphicsExtractor, view: PetView?, w: Int, h: Int, now: Long, c: PetHudConfig) {
        if (!c.animations || view == null || levelDoneAt <= 0 || now < levelDoneAt) return
        val t = (now - levelDoneAt).toFloat() / GLOW_MS
        if (t >= 1f) return
        // CSS applies ease-out per keyframe segment: up to the peak at 25 %, then back down.
        val k = if (t < 0.25f) Bezier.EASE_OUT.at(t / 0.25).toFloat() else 1f - Bezier.EASE_OUT.at((t - 0.25) / 0.75).toFloat()
        PetGlow.draw(g, w, h, ((k.coerceIn(0f, 1f) * 255).roundToInt() shl 24) or (PetHudModel.rarityColor(view) and 0xFFFFFF))
    }

    // ------------------------------------------------------------------ primitives

    private val spanCache = HashMap<List<Run>, List<SoulRichText.Span>>()

    private fun spans(runs: List<Run>): List<SoulRichText.Span> {
        spanCache[runs]?.let { return it }
        if (spanCache.size > 128) spanCache.clear()
        val s = runs.map { SoulRichText.Span(it.text, it.color and 0xFFFFFF, it.bold, false, false) }
        spanCache[runs] = s
        return s
    }

    private fun text(g: GuiGraphicsExtractor, runs: List<Run>, x: Int, y: Int, size: Float, alpha: Float) {
        if (runs.isEmpty()) return
        SoulRichText.draw(g, spans(runs), x, y, size, PetHudModel.WHITE, (alpha * 255).roundToInt().coerceIn(0, 255), fontChoiceIndex(), textShadow(), boldFont())
    }

    private fun rect(g: GuiGraphicsExtractor, pipeline: RenderPipeline, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        if ((color ushr 24) == 0) return
        g.guiRenderState.addGuiElement(RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, null))
    }

    private fun fade(argb: Int, alpha: Float): Int {
        if (alpha >= 1f) return argb
        val a = (((argb ushr 24) and 0xFF) * alpha.coerceIn(0f, 1f)).roundToInt()
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    private fun mix(a: Int, b: Int, k: Float): Int {
        fun ch(s: Int) = (((a shr s) and 0xFF) + (((b shr s) and 0xFF) - ((a shr s) and 0xFF)) * k).roundToInt() and 0xFF
        return (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/** CSS `cubic-bezier(x1, y1, x2, y2)` timing. */
class Bezier(private val x1: Double, private val y1: Double, private val x2: Double, private val y2: Double) {
    private fun coord(t: Double, a: Double, b: Double): Double {
        val u = 1 - t
        return 3 * u * u * t * a + 3 * u * t * t * b + t * t * t
    }

    /** y at progress [x] (0..1), solving x(t) by bisection. */
    fun at(x: Double): Double {
        if (x <= 0) return 0.0
        if (x >= 1) return 1.0
        var lo = 0.0
        var hi = 1.0
        repeat(30) {
            val mid = (lo + hi) / 2
            if (coord(mid, x1, x2) < x) lo = mid else hi = mid
        }
        return coord((lo + hi) / 2, y1, y2)
    }

    companion object {
        /** CSS `ease-out`. */
        val EASE_OUT = Bezier(0.0, 0.0, 0.58, 1.0)

        /** The bar fill transition, `cubic-bezier(.3,.7,.3,1)`. */
        val FILL = Bezier(0.3, 0.7, 0.3, 1.0)
    }
}
