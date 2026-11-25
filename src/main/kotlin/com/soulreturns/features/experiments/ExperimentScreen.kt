package com.soulreturns.features.experiments

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.experiments.ExpMenus.Kind
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.mojang.blaze3d.pipeline.RenderPipeline
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Soul's Experimentation Table screen (spec `mocks/experiments/`, variant A "Real items", next-click
 * emphasis "ring + order badges"), drawn instead of [vanilla] while the real menu stays open. Every
 * control maps to one real slot; a press goes through [Experiments.press] (games: judged first). Esc and
 * the inventory key close the menu as vanilla does, never blocked. When the menu stops matching
 * ([ExpFlow.State.FAILED]) the vanilla screen comes back.
 *
 * Draws from the packet model ([ExpFlow]) and the stacks the server sent, never from `slot.getItem()`;
 * nothing animates in or out, so a lit note shows in the frame after its packet.
 */
class ExperimentScreen(private val vanilla: ContainerScreen, private val menu: ExpFlow.Menu) : SoulScreenBase(vanilla.title) {
    private companion object {
        const val PAD = 14
        const val HEADER_H = 24
        const val GAP = 10
        const val PHASE_H = 44
        const val STRIP_H = 24
        const val FOOT_H = 12
        const val CONTENT_W = 436
        const val FIT_MARGIN = 16
        const val CHRONO_TILE = 70
        const val CHRONO_GAP = 10
        const val ULTRA_CELL = 44
        const val ULTRA_GAP = 5
        const val SP_CELL = 56
        const val SP_GAP = 6

        /** Superpairs card: icon below the corner badges, label under it. */
        const val SP_ICON = 26
        const val SP_ICON_Y = 16
        const val SP_LABEL_Y = 48
        const val CHIP = 18
        const val CHIP_GAP = 4
        /** Row pitch of the Superpairs Known strip once its chips wrap. */
        const val KNOWN_ROW = 22
        const val FLASH_MS = 180L
        const val REASON_MS = 1800L
        const val SENT_MS = 1000L
        const val PRESS_MS = 400L
        const val RARE_BANNER_MS = 10_000L

        // SoulText nominal sizes (the mock's T)
        const val F_TITLE = 11.75f
        const val F_WORD = 14.5f
        const val F_BODY = 8.25f
        const val F_SMALL = 7.5f
        const val F_TINY = 6.5f

        val PANEL_BG = 0xF21A1A1A.toInt()
        val PANEL_EDGE = 0xFF333333.toInt()
        val TEXT = 0xFFEEEEEE.toInt()
        val DIM = 0xFF888888.toInt()
        val FAINT = 0xFF555555.toInt()
        val CELL = 0xFF1E1E1E.toInt()
        val CELL_HI = 0xFF2A2A2A.toInt()
        val CARD = 0xFF1F1F1F.toInt()
        val CARD_HI = 0xFF252525.toInt()
        val CARD_EDGE = 0xFF2C2C2C.toInt()
        val WATCH = 0xFFFACC15.toInt()
        val TURN = 0xFF4ADE80.toInt()
        val TURN_FILL = 0xFF15803D.toInt()
        val BLOCK = 0xFFEF4444.toInt()
        val WAIT = 0xFFA78BFA.toInt()
        val OFF = 0xFF9CA3AF.toInt()
        val ACCENT = 0xFF3B82F6.toInt()
        val ACCENT_DIM = 0xFF2563EB.toInt()
        val GLASS = 0xFF123B3E.toInt()
        val GLASS_HI = 0xFF174A4E.toInt()
        val GLASS_EDGE = 0x4722D3EE
        val AMBER = 0xFFF59E0B.toInt()
        val RARE = 0xFFE879F9.toInt()
        val OK_GREEN = 0xFF7DFF9A.toInt()

        val PAIR_COLORS =
            intArrayOf(
                0xFF60A5FA.toInt(), 0xFFF472B6.toInt(), 0xFFFACC15.toInt(), 0xFFC084FC.toInt(), 0xFF34D399.toInt(), 0xFFFB923C.toInt(), 0xFF22D3EE.toInt(),
                0xFFE879F9.toInt(), 0xFFA3E635.toInt(), 0xFFF87171.toInt(), 0xFF2DD4BF.toInt(), 0xFFFBBF24.toInt(), 0xFF93C5FD.toInt(), 0xFFFDA4AF.toInt(),
            )

        /** Minecraft dye colors (the color a glass/terracotta/pane is named after). */
        val DYE =
            mapOf(
                "white" to 0xF9FFFE, "orange" to 0xF9801D, "magenta" to 0xC74EBD, "light_blue" to 0x3AB3DA, "yellow" to 0xFED83D, "lime" to 0x80C71F,
                "pink" to 0xF38BAA, "gray" to 0x474F52, "light_gray" to 0x9D9D97, "cyan" to 0x169C9C, "purple" to 0x8932B8, "blue" to 0x3C44AA,
                "brown" to 0x835432, "green" to 0x5E7C16, "red" to 0xB02E26, "black" to 0x1D1D21,
            )
        val LIGHT_DYES = setOf(0x80C71F, 0xFED83D, 0x3AB3DA, 0xF38BAA, 0xF9801D, 0xF9FFFE)
        val MC = intArrayOf(0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF)

        fun mc(code: Char): Int = 0xFF000000.toInt() or MC["0123456789abcdef".indexOf(code.lowercaseChar()).coerceAtLeast(0)]

        fun alpha(color: Int, a: Float): Int = (((color ushr 24) * a.coerceIn(0f, 1f)).roundToInt() shl 24) or (color and 0xFFFFFF)

        fun opaque(rgb: Int): Int = 0xFF000000.toInt() or rgb

        fun dyeOf(id: String): Int {
            val base = id.removeSuffix("_stained_glass_pane").removeSuffix("_stained_glass").removeSuffix("_terracotta").removeSuffix("_dye")
            return DYE[base] ?: 0x888888
        }
    }

    private class Hit(val key: String, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val action: ((Int) -> Unit)?)

    private val hits = ArrayList<Hit>()
    private val over = ArrayList<() -> Unit>()
    private var g: GuiGraphicsExtractor? = null
    private var hover: String? = null
    private var handedBack = false
    private var press: Pair<String, Long>? = null
    private val sentAt = HashMap<Int, Long>()

    // panel transform: Soul px = origin + local * k
    private var ox = 0f
    private var oy = 0f
    private var k = 1f

    override fun isPauseScreen(): Boolean = false

    override fun isInGameUi(): Boolean = true

    override fun tick() {
        if (menu.state == ExpFlow.State.FAILED) handBack()
    }

    private fun handBack() {
        if (handedBack) return
        handedBack = true
        val mc = minecraft
        mc.execute { if (mc.gui.screen() === this) mc.gui.setScreen(vanilla) }
    }

    override fun onClose() {
        val player = minecraft.player
        if (player != null && player.containerMenu.containerId == menu.cid) player.closeContainer()
        if (minecraft.gui.screen() === this) super.onClose()
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (event.key() == GLFW.GLFW_KEY_ESCAPE || minecraft.options.keyInventory.matches(event)) onClose()
        return true
    }

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        closeTooltip()
        val sc = SoulUi.factor()
        val lx = ((click.x() / sc).toFloat() - ox) / k
        val ly = ((click.y() / sc).toFloat() - oy) / k
        val hit = hits.lastOrNull { lx >= it.x0 && lx < it.x1 && ly >= it.y0 && ly < it.y1 } ?: return true
        hit.action?.invoke(click.button())
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean = true

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean = true

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean = true

    // ---------------------------------------------------------------- input → real slots

    /** A menu control: a left press (or a right press on Practice) sends [click] on [slot]. */
    private fun menuButton(slot: Int, key: String, click: Experiments.Click = Experiments.MENU_CLICK): (Int) -> Unit = { button ->
        if (button == 0 || click === Experiments.PRACTICE_CLICK && button == 1) {
            press = key to System.currentTimeMillis()
            Experiments.press(menu, slot, click, judge = false)
        }
    }

    /** Games: only a left press sends (as the terminals: middle, right and shift send nothing). */
    private fun gamePress(slot: Int, click: Experiments.Click): (Int) -> Unit = { button ->
        if (button == 0 && Experiments.press(menu, slot, click, judge = true) && click === Experiments.SUPERPAIRS_CLICK) sentAt[slot] = System.currentTimeMillis()
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        Experiments.tick()
        if (menu.state == ExpFlow.State.FAILED) {
            handBack()
            return
        }
        this.g = g
        hits.clear()
        over.clear()
        val sc = SoulUi.factor()
        val uiW = width / sc
        val uiH = height / sc
        val now = System.currentTimeMillis()
        g.fill(0, 0, width, height, 0x66000000)
        val (pw, ph) = size()
        k = min(1f, min((uiW - FIT_MARGIN) / pw, (uiH - FIT_MARGIN) / ph))
        ox = ((uiW - pw * k) / 2).toInt().toFloat()
        oy = ((uiH - ph * k) / 2).toInt().toFloat()
        val lmx = ((mouseX / sc) - ox) / k
        val lmy = ((mouseY / sc) - oy) / k
        g.pose().pushMatrix()
        g.pose().scale(sc, sc)
        g.pose().translate(ox, oy)
        g.pose().scale(k, k)
        // Hover from the last frame's hit boxes (same layout every frame).
        hover = lastHits.lastOrNull { lmx >= it.x0 && lmx < it.x1 && lmy >= it.y0 && lmy < it.y1 }?.key
        rr(-1, -1, pw.toInt() + 1, ph.toInt() + 1, PANEL_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
        rr(0, 0, pw.toInt(), ph.toInt(), PANEL_BG, SoulRenderPipelines.ROUND_MEDIUM)
        if (menu.state == ExpFlow.State.PENDING) {
            header(PAD, CONTENT_W, menu.title, null, 'f') { }
            later { text("Loading…", PAD, PAD + HEADER_H + GAP, PAD + HEADER_H + GAP + 20, F_BODY, DIM) }
        } else {
            when (menu.kind) {
                Kind.CHRONO -> chrono(menu.model as ChronoModel, now)
                Kind.ULTRA -> ultra(menu.model as UltraModel, now)
                Kind.SUPERPAIRS -> superpairs(menu.model as SuperpairsModel, now)
                Kind.TABLE -> menu.table()?.let { table(it) }
                Kind.STAKES -> menu.stakes()?.let { stakes(it) }
                Kind.OVER -> menu.over()?.let { over(it) }
                Kind.REWARDS -> menu.rewards()?.let { rewards(it) }
                Kind.RNG -> {}
            }
        }
        g.nextStratum()
        over.forEach { it() }
        g.pose().popMatrix()
        lastHits.clear()
        lastHits.addAll(hits)
        g.nextStratum()
        g.pose().pushMatrix()
        g.pose().scale(sc, sc)
        drawTooltip(g, uiW.toInt(), uiH.toInt())
        g.pose().popMatrix()
    }

    private val lastHits = ArrayList<Hit>()

    /** Panel size per screen (local Soul px, before the fit). */
    private fun size(): Pair<Float, Float> {
        val game = { bh: Int -> (CONTENT_W + 2 * PAD).toFloat() to (PAD + HEADER_H + GAP + PHASE_H + GAP + bh + GAP + STRIP_H + 8 + FOOT_H + PAD).toFloat() }
        return when (menu.kind) {
            Kind.CHRONO -> game(2 * CHRONO_TILE + CHRONO_GAP)
            Kind.ULTRA -> game(4 * ULTRA_CELL + 3 * ULTRA_GAP)
            Kind.SUPERPAIRS -> game(4 * SP_CELL + 3 * SP_GAP).let { (pw, ph) ->
                val extra = (menu.model as? SuperpairsModel)?.let { (known(it, knownPairs(it), CONTENT_W).rows - 1) * KNOWN_ROW } ?: 0
                pw to ph + extra
            }
            Kind.TABLE -> {
                val renew = menu.table()?.renew != null
                (572f + 2 * PAD) to (PAD + HEADER_H + GAP + 176 + GAP + (if (renew) 46 + GAP else 0) + 30 + PAD).toFloat()
            }
            Kind.STAKES -> {
                val n = menu.stakes()?.tiers?.size ?: 1
                (600f + 2 * PAD) to (PAD + HEADER_H + GAP + n * 56 - 6 + GAP + 26 + PAD).toFloat()
            }
            Kind.OVER, Kind.REWARDS -> {
                val rewards = if (menu.kind == Kind.OVER) menu.over()?.rewards?.size ?: 0 else menu.rewards()?.rewards?.size ?: 0
                val rows = if (menu.kind == Kind.OVER) 2 else 3
                val yB = PAD + 40 + rows * 16 + 8 + 20 + rewards * 15 + 12
                (400f + 2 * PAD) to (if (menu.kind == Kind.REWARDS) yB + 30 + PAD else yB + 34 + 26 + PAD).toFloat()
            }
            Kind.RNG -> 100f to 100f
        }
    }

    // ---------------------------------------------------------------- primitives (local px)

    private fun rr(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, pipe: RenderPipeline = SoulRenderPipelines.ROUND_SMALL) = roundRect(g!!, x0, y0, x1, y1, color, pipe)

    private fun fill(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) = g!!.fill(x0, y0, x1, y1, color)

    /**
     * A rounded outline [w] px wide inside the box, one SDF stroke following the corner radius: [corner] 2-3 = a
     * cell's own corners (4), 5 = a ring 3-4 px outside a cell (7-8), 6 = 6 px outside (10).
     */
    private fun ring(x0: Int, y0: Int, x1: Int, y1: Int, w: Int, color: Int, corner: Int = 3) {
        val pipe =
            if (w >= 3) SoulRenderPipelines.STROKE_3_R8
            else if (w == 1) SoulRenderPipelines.STROKE_1_R4
            else if (corner >= 6) SoulRenderPipelines.STROKE_2_R10
            else if (corner >= 5) SoulRenderPipelines.STROKE_2_R7
            else SoulRenderPipelines.STROKE_2_R4
        rr(x0, y0, x1, y1, color, pipe)
    }

    /** The "after that" outline: a dashed rounded ring 3 px outside a cell. */
    private fun dashed(x0: Int, y0: Int, x1: Int, y1: Int, color: Int) = rr(x0, y0, x1, y1, color, SoulRenderPipelines.DASH_2_R7)

    private fun later(block: () -> Unit) {
        over += block
    }

    private fun text(s: String, x: Int, top: Int, bottom: Int, size: Float, color: Int, semi: Boolean = true) =
        SoulText.drawVCentered(g!!, s, x, (top + bottom) / 2, size, color, if (semi) MsdfFonts.menuSemibold else MsdfFonts.menuRegular, forceCustom = true, shadow = false)

    private fun textR(s: String, right: Int, top: Int, bottom: Int, size: Float, color: Int) = text(s, right - width(s, size), top, bottom, size, color)

    private fun textC(s: String, cx: Int, cy: Int, size: Float, color: Int) =
        SoulText.drawCenteredOn(g!!, s, cx, cy, size, color, MsdfFonts.menuSemibold, forceCustom = true, shadow = false)

    private fun width(s: String, size: Float): Int = SoulText.width(s, size, MsdfFonts.menuSemibold, forceCustom = true)

    private fun item(stack: ItemStack?, x: Int, y: Int, size: Int) {
        if (stack == null || stack.isEmpty) return
        val gg = g!!
        gg.pose().pushMatrix()
        gg.pose().translate(x.toFloat(), y.toFloat())
        gg.pose().scale(size / 16f, size / 16f)
        gg.fakeItem(stack, 0, 0)
        gg.pose().popMatrix()
    }

    private fun hit(key: String, x0: Int, y0: Int, x1: Int, y1: Int, action: ((Int) -> Unit)?) {
        hits += Hit(key, x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), action)
    }

    private fun tip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>) =
        tooltip((ox + x0 * k).toInt(), (oy + y0 * k).toInt(), (ox + x1 * k).toInt(), (oy + y1 * k).toInt(), lines)

    private fun pulse(now: Long): Float = 0.55f + 0.45f * sin(now / 1000.0 * 2 * Math.PI).toFloat()

    private fun flashOf(slot: Int, now: Long): Float {
        val b = menu.blocked ?: return 0f
        if (b.slot != slot) return 0f
        val a = 1f - (now - b.at).toFloat() / FLASH_MS
        return if (a > 0f) a else 0f
    }

    private fun flashBox(slot: Int, x0: Int, y0: Int, x1: Int, y1: Int, now: Long) {
        val a = flashOf(slot, now)
        if (a <= 0f) return
        later {
            rr(x0, y0, x1, y1, alpha(BLOCK, 0.19f * a))
            ring(x0, y0, x1, y1, 1, alpha(BLOCK, 0.8f * a))
        }
    }

    private fun chip(x: Int, y: Int, s: String, color: Int, size: Float = F_SMALL, h: Int = 15): Int {
        val w = width(s, size) + 12
        rr(x, y, x + w, y + h, alpha(color, 0.14f), SoulRenderPipelines.ROUND_SMALL)
        later { text(s, x + 6, y, y + h, size, color) }
        return w
    }

    private fun badge(x: Int, y: Int, s: String, bg: Int, fg: Int, right: Boolean = false): Int {
        val w = max(14, width(s, F_TINY) + 6)
        val bx = if (right) x - w else x
        later {
            rr(bx, y, bx + w, y + 14, bg, SoulRenderPipelines.ROUND_PILL)
            textC(s, bx + w / 2, y + 7, F_TINY, fg)
        }
        return w
    }

    private fun header(y: Int, w: Int, title: String, tier: String?, tierCode: Char, right: () -> Unit) {
        later { text(title, PAD, y, y + HEADER_H, F_TITLE, TEXT) }
        if (tier != null) chip(PAD + width(title, F_TITLE) + 7, y + (HEADER_H - 15) / 2, tier, mc(tierCode))
        right()
    }

    private class Phase(val tone: Int, val word: String, val sub: String, val right: (() -> Unit)? = null, val bar: Float? = null, val barColor: Int? = null)

    private fun phaseBar(y: Int, w: Int, p: Phase, now: Long) {
        val b = menu.blocked
        val age = if (b != null) now - b.at else Long.MAX_VALUE
        val showReason = age < REASON_MS
        val edge = if (age < FLASH_MS) 1f - age.toFloat() / FLASH_MS else 0f
        val x0 = PAD
        val x1 = PAD + w
        rr(x0, y, x1, y + PHASE_H, CELL)
        rr(x0, y, x1, y + PHASE_H, alpha(p.tone, 0.1f))
        fill(x0, y + 2, x0 + 4, y + PHASE_H - 2, p.tone)
        later {
            if (edge > 0f) ring(x0, y, x1, y + PHASE_H, 2, alpha(BLOCK, 0.9f * edge)) else ring(x0, y, x1, y + PHASE_H, 1, alpha(p.tone, 0.28f))
            text(p.word, x0 + 14, y + 5, y + 23, F_WORD, p.tone)
            if (showReason && b != null) {
                rr(x0 + 14, y + PHASE_H - 18, x0 + 25, y + PHASE_H - 7, BLOCK, SoulRenderPipelines.ROUND_PILL)
                drawIcon(g!!, "close", x0 + 15, y + PHASE_H - 17, 9, 0xFFFFFFFF.toInt())
                text("Blocked · ${b.text}", x0 + 30, y + PHASE_H - 20, y + PHASE_H - 5, F_BODY, 0xFFFF9A9A.toInt())
            } else {
                text(p.sub, x0 + 14, y + PHASE_H - 20, y + PHASE_H - 5, F_BODY, 0xFFBBBBBB.toInt())
            }
        }
        p.right?.invoke()
        val bar = p.bar
        if (bar != null) {
            fill(x0 + 4, y + PHASE_H - 3, x1, y + PHASE_H, 0x0FFFFFFF)
            fill(x0 + 4, y + PHASE_H - 3, x0 + 4 + ((w - 4) * bar.coerceIn(0f, 1f)).toInt(), y + PHASE_H, p.barColor ?: p.tone)
        }
    }

    /** Right side of the phase bar: a caption and a value, right-aligned. */
    private fun phaseRight(y: Int, w: Int, caption: String, value: String, valueColor: Int = TEXT) {
        later {
            val right = PAD + w - 12
            val vw = width(value, F_TITLE)
            text(value, right - vw, y, y + PHASE_H - 4, F_TITLE, valueColor)
            textR(caption, right - vw - 6, y, y + PHASE_H - 4, F_SMALL, DIM)
        }
    }

    private fun escFooter(y: Int, w: Int, hint: String, hintColor: Int = FAINT) {
        later { text(hint, PAD, y, y + FOOT_H, F_SMALL, hintColor) }
        button(PAD + w - 64, y - 3, 64, FOOT_H + 6, "Close · Esc", "esc", "plain") { onClose() }
    }

    private fun button(x: Int, y: Int, w: Int, h: Int, label: String, key: String, kind: String, labelColor: Int? = null, action: (Int) -> Unit) {
        val hv = hover == key
        val pressed = press?.let { it.first == key && System.currentTimeMillis() - it.second < PRESS_MS } == true
        val bg = when (kind) {
            "primary" -> if (hv) ACCENT else ACCENT_DIM
            "go" -> if (hv) 0xFF16A34A.toInt() else TURN_FILL
            "danger" -> if (hv) 0x38EF4444 else 0x1FEF4444
            "muted" -> if (hv) 0xFF2C2C2C.toInt() else 0xFF242424.toInt()
            else -> if (hv) 0xFF353535.toInt() else 0xFF262626.toInt()
        }
        val fg = labelColor ?: when (kind) {
            "primary", "go" -> 0xFFFFFFFF.toInt()
            "danger" -> 0xFFFF8A8A.toInt()
            "muted" -> DIM
            else -> if (key == "esc") 0xFFBBBBBB.toInt() else TEXT
        }
        rr(x, y, x + w, y + h, bg)
        if (kind == "danger") later { ring(x, y, x + w, y + h, 1, 0x8CEF4444.toInt()) }
        if (pressed) rr(x, y, x + w, y + h, 0x30FFFFFF)
        later { textC(label, x + w / 2, y + h / 2, if (key == "esc") F_SMALL else F_BODY, fg) }
        hit(key, x, y, x + w, y + h, action)
    }

    // ---------------------------------------------------------------- Chronomatron

    private fun chrono(s: ChronoModel, now: Long) {
        val w = CONTENT_W
        val n = s.round
        val bw = 5 * CHRONO_TILE + 4 * CHRONO_GAP
        val bh = 2 * CHRONO_TILE + CHRONO_GAP
        val yH = PAD
        val yP = yH + HEADER_H + GAP
        val yB = yP + PHASE_H + GAP
        val yS = yB + bh + GAP
        val yF = yS + STRIP_H + 8
        header(yH, w, "Chronomatron", s.tier, 'd') { roundLabel(yH, w, n) }
        val i = s.k + s.pending.size
        val phase =
            if (s.off != null) {
                Phase(OFF, "Solver off", "Lost track (${s.off}): clicks are no longer judged")
            } else if (s.phase == ChronoModel.Phase.DISPLAY) {
                val total = (ChronoModel.FIRST_NOTE + ChronoModel.NOTE_GAP * (n - 1) - 1) * 50f
                Phase(
                    WATCH, "Watch",
                    if (s.plings > 0) "Note ${s.plings} of $n${if (s.plings < n) "" else ": last one"} · your turn starts on the last note" else "Round $n: the pattern is about to play",
                    right = { watchDots(yP, w, s) }, bar = (now - s.roundAt) / total,
                )
            } else {
                val clock = s.clockAt != null
                val secs = if (clock) s.secondsLeft(now) ?: 0 else n + 2
                val bar = if (clock && s.timerMax > 0) (s.timer!! - (now - s.timerAt) / 1000f) / s.timerMax else 1f
                val next = s.next()
                val progress = "${s.k} of $n confirmed${if (s.pending.size > 0) " · ${s.pending.size} sent" else ""}"
                Phase(
                    TURN, "Your turn",
                    if (i >= n) "$progress · all sent, waiting for Hypixel" else "$progress · next ${next ?: "?"}${if (!clock) " · answers count already" else ""}",
                    right = { phaseRight(yP, w, if (clock) "Timer" else "clock follows", "${secs}s", if (clock && secs <= 2) 0xFFFF8A8A.toInt() else if (clock) TEXT else DIM) },
                    bar = bar,
                )
            }
        phaseBar(yP, w, phase, now)
        // board: one tile per color (its two stacked slots), 5 x 2
        val bx = PAD + (w - bw) / 2
        val upcoming = HashMap<String, MutableList<Pair<Int, Int>>>()
        if (s.phase == ChronoModel.Phase.INPUT && s.off == null) for (o in 0 until 3) s.next(o)?.let { c -> upcoming.getOrPut(c) { ArrayList() } += (i + o + 1) to o }
        val pulse = pulse(now)
        s.colors.forEachIndexed { ci, c ->
            val x = bx + (ci % 5) * (CHRONO_TILE + CHRONO_GAP)
            val y = yB + (ci / 5) * (CHRONO_TILE + CHRONO_GAP)
            val x1 = x + CHRONO_TILE
            val y1 = y + CHRONO_TILE
            val key = "chrono:${c.name}"
            // Lit tiles are the pattern playing; in your turn the server's echo of a correct click is not drawn.
            val lit = s.phase == ChronoModel.Phase.DISPLAY && s.litColor(c.name)
            val stack = Experiments.stacks.getOrNull(c.top)
            val base = opaque(dyeOf(BuiltinId.of(stack)))
            rr(x, y, x1, y1, if (lit) alpha(base, 0.32f) else if (hover == key) CELL_HI else CELL)
            if (lit) {
                rr(x - 3, y - 3, x1 + 3, y1 + 3, alpha(base, 0.35f), SoulRenderPipelines.ROUND_MEDIUM)
                rr(x, y, x1, y1, alpha(base, 0.32f))
                later { ring(x, y, x1, y1, 2, alpha(0xFFFFFFFF.toInt(), 0.85f)) }
            }
            item(stack, x + (CHRONO_TILE - 32) / 2, y + 10, 32)
            later { textC(c.name, x + CHRONO_TILE / 2, y1 - 11, F_SMALL, if (lit) 0xFFFFFFFF.toInt() else DIM) }
            if (s.phase == ChronoModel.Phase.DISPLAY && s.off == null) {
                val pos = s.notes.withIndex().filter { it.value == c.name }.map { it.index + 1 }
                if (pos.isNotEmpty()) badge(x + 4, y + 4, pos.joinToString(" · "), 0x8C000000.toInt(), 0xFFDDDDDD.toInt())
            }
            val up = upcoming[c.name].orEmpty()
            up.forEachIndexed { j, (p, o) ->
                if (o == 0) badge(x + 4 + j * 20, y + 4, p.toString(), TURN, 0xFF062B12.toInt()) else badge(x + 4 + j * 20, y + 4, p.toString(), 0x99000000.toInt(), alpha(0xFFFFFFFF.toInt(), if (o == 1) 0.85f else 0.55f))
            }
            flashBox(c.top, x, y, x1, y1, now)
            val isNext = up.any { it.second == 0 }
            val isNext2 = !isNext && up.any { it.second == 1 }
            if (isNext) later { ring(x - 4, y - 4, x1 + 4, y1 + 4, 3, TURN, 5); ring(x - 6, y - 6, x1 + 6, y1 + 6, 2, alpha(TURN, 0.35f * pulse), 6) }
            if (isNext2) later { dashed(x - 3, y - 3, x1 + 3, y1 + 3, alpha(TURN, 0.5f)) }
            hit(key, x, y, x1, y1, gamePress(c.top, Experiments.CHRONO_CLICK))
        }
        // pattern strip
        val dyeOfColor = s.colors.associate { it.name to opaque(dyeOf(BuiltinId.of(Experiments.stacks.getOrNull(it.top)))) }
        strip(yS, w, n, { j ->
            val c = if (j < s.notes.size) s.notes[j] else if (j == n - 1) s.lastColor else null
            val col = c?.let { dyeOfColor[it] }
            val state =
                if (s.phase == ChronoModel.Phase.DISPLAY) {
                    if (c == null) "todo" else if (j == s.notes.size - 1 && s.litColor(c)) "live" else "seen"
                } else if (j < s.k) "done" else if (j < i) "sent" else if (j == i) "next" else "todo"
            col to state
        }, if (s.phase == ChronoModel.Phase.INPUT) "${s.k} confirmed${if (s.pending.size > 0) " · ${s.pending.size} sent" else ""}" else "${s.notes.size}/$n seen")
        escFooter(yF, w, "Esc closes the game · Hypixel then opens Experiment Over")
    }

    private fun roundLabel(y: Int, w: Int, n: Int) {
        later {
            val v = n.toString()
            val vw = width(v, F_TITLE)
            text(v, PAD + w - vw, y, y + HEADER_H, F_TITLE, TEXT)
            textR("Round", PAD + w - vw - 5, y, y + HEADER_H, F_BODY, DIM)
        }
    }

    private fun watchDots(y: Int, w: Int, s: ChronoModel) {
        val n = s.round
        val per = 3 + 7
        val dotsW = min(n, 15) * per
        val x0 = PAD + w - 12 - dotsW
        for (j in 0 until min(n, 15)) {
            val c = s.notes.getOrNull(j)
            val color = if (c != null) opaque(dyeOf(BuiltinId.of(Experiments.stacks.getOrNull(s.colors.first { it.name == c }.top)))) else if (j < s.plings) WATCH else 0x24FFFFFF
            val x = x0 + j * per
            rr(x, y + 16, x + 7, y + 23, color, SoulRenderPipelines.ROUND_PILL)
        }
    }

    /** Pattern strip: one chip per answer. state: done / sent / next / todo / seen / live. */
    private fun strip(y: Int, w: Int, n: Int, at: (Int) -> Pair<Int?, String>, right: String) {
        later { text("Pattern", PAD, y, y + STRIP_H, F_SMALL, DIM) }
        val total = n * CHIP + (n - 1) * CHIP_GAP
        val kk = min(1f, (w - 150f) / max(1, total))
        val c = (CHIP * kk).toInt().coerceAtLeast(6)
        val gp = (CHIP_GAP * kk).toInt().coerceAtLeast(1)
        val x0 = PAD + 54
        for (j in 0 until n) {
            val (col, state) = at(j)
            val x = x0 + j * (c + gp)
            val yy = y + (STRIP_H - c) / 2
            val fill = col ?: 0xFF888888.toInt()
            when (state) {
                "todo" -> if (col != null) rr(x, yy, x + c, yy + c, alpha(fill, 0.55f)) else later { ring(x, yy, x + c, yy + c, 1, 0x2EFFFFFF, 2) }
                "seen" -> rr(x, yy, x + c, yy + c, fill)
                "live" -> {
                    rr(x - 2, yy - 2, x + c + 2, yy + c + 2, 0xFFFFFFFF.toInt())
                    rr(x, yy, x + c, yy + c, fill)
                }
                "done" -> rr(x, yy, x + c, yy + c, alpha(fill, 0.3f))
                "sent" -> {
                    rr(x, yy, x + c, yy + c, alpha(fill, 0.55f))
                    later { ring(x, yy, x + c, yy + c, 1, 0xBFFFFFFF.toInt(), 2) }
                }
                "next" -> {
                    rr(x - 2, yy - 2, x + c + 2, yy + c + 2, TURN)
                    rr(x, yy, x + c, yy + c, fill)
                }
            }
            val light = col != null && (col and 0xFFFFFF) in LIGHT_DYES
            later {
                if (state == "done") drawIcon(g!!, "check", x + (c - 9) / 2, yy + (c - 9) / 2, 9, OK_GREEN)
                else if (state == "sent") textC("…", x + c / 2, yy + c / 2, F_TINY, 0xFFFFFFFF.toInt())
                else if (state == "next" || state == "todo" && col == null) textC((j + 1).toString(), x + c / 2, yy + c / 2, F_TINY, if (light) 0xFF111111.toInt() else 0xFFFFFFFF.toInt())
            }
        }
        later { textR(right, PAD + w, y, y + STRIP_H, F_SMALL, DIM) }
    }

    // ---------------------------------------------------------------- Ultrasequencer

    private fun ultra(s: UltraModel, now: Long) {
        val w = CONTENT_W
        val n = s.round
        val bw = 9 * ULTRA_CELL + 8 * ULTRA_GAP
        val bh = 4 * ULTRA_CELL + 3 * ULTRA_GAP
        val yH = PAD
        val yP = yH + HEADER_H + GAP
        val yB = yP + PHASE_H + GAP
        val yS = yB + bh + GAP
        val yF = yS + STRIP_H + 8
        header(yH, w, "Ultrasequencer", s.tier, 'd') { roundLabel(yH, w, n) }
        val i = s.k + s.pending.size
        val phase =
            if (s.off != null) {
                Phase(OFF, "Solver off", "${s.off}: clicks are not judged")
            } else if (s.phase == UltraModel.Phase.DISPLAY) {
                val total = (UltraModel.SHOW_BASE + UltraModel.SHOW_STEP * (n - 1).coerceAtLeast(0)) * 50f
                Phase(WATCH, "Memorize", "$n number${if (n == 1) "" else "s"} · your turn starts when they hide",
                    right = { phaseRight(yP, w, "hide in", String.format(Locale.ROOT, "%.1fs", max(0L, s.hideEta(now)) / 1000.0)) },
                    bar = 1f - (now - s.displayAt) / total)
            } else {
                val secs = s.secondsLeft(now) ?: 0
                val progress = "${s.k} of $n confirmed${if (s.pending.size > 0) " · ${s.pending.size} sent" else ""}"
                Phase(TURN, "Your turn", if (i >= n) "$progress · all sent, waiting for Hypixel" else "$progress · next ${i + 1}",
                    right = { phaseRight(yP, w, "Timer", "${secs}s", if (secs <= 2) 0xFFFF8A8A.toInt() else TEXT) },
                    bar = if (s.timerMax > 0) ((s.timer ?: 0) - (now - s.timerAt) / 1000f) / s.timerMax else 1f)
            }
        phaseBar(yP, w, phase, now)
        val bx = PAD + (w - bw) / 2
        val paneCol = opaque(DYE[s.pane] ?: 0x888888)
        val numAt = HashMap<Int, Int>()
        if (s.phase == UltraModel.Phase.DISPLAY) numAt.putAll(s.live) else for ((num, slot) in s.nums) numAt[slot] = num
        val nextN = if (s.phase == UltraModel.Phase.INPUT && s.off == null) s.nextNum() else null
        val next2 = if (nextN != null && nextN < n) nextN + 1 else null
        val pulse = pulse(now)
        for (slot in s.board) {
            val x = bx + (slot % 9) * (ULTRA_CELL + ULTRA_GAP)
            val y = yB + (slot / 9 - 1) * (ULTRA_CELL + ULTRA_GAP)
            val x1 = x + ULTRA_CELL
            val y1 = y + ULTRA_CELL
            val key = "ultra:$slot"
            rr(x, y, x1, y1, CELL)
            rr(x, y, x1, y1, alpha(paneCol, if (hover == key) 0.2f else 0.11f))
            val num = numAt[slot]
            if (num != null) {
                val state = when {
                    s.phase == UltraModel.Phase.DISPLAY -> "show"
                    num <= s.k -> "done"
                    num <= i -> "sent"
                    num == nextN -> "next"
                    num == next2 -> "next2"
                    else -> "mem"
                }
                val a = if (state == "done") 0.3f else if (state == "mem") 0.55f else 1f
                val stack = Experiments.seen[slot]?.takeIf { it.count == num } ?: Experiments.stacks.getOrNull(slot)
                item(stack, x + 5, y + 4, 26)
                later {
                    if (a < 1f) rr(x + 4, y + 3, x + 32, y + 31, alpha(CELL, 1f - a))
                    val numColor = alpha(if (state == "next") TURN else 0xFFFFFFFF.toInt(), a)
                    textR(num.toString(), x1 - 5, y1 - 20, y1 - 4, F_BODY + 3, numColor)
                    if (state == "done") drawIcon(g!!, "check", x1 - 13, y + 2, 10, OK_GREEN)
                    if (state == "sent") ring(x + 2, y + 2, x1 - 2, y1 - 2, 1, 0xB3FFFFFF.toInt(), 3)
                    if (state == "next") { ring(x - 4, y - 4, x1 + 4, y1 + 4, 3, TURN, 5); ring(x - 6, y - 6, x1 + 6, y1 + 6, 2, alpha(TURN, 0.35f * pulse), 6) }
                    if (state == "next2") dashed(x - 3, y - 3, x1 + 3, y1 + 3, alpha(TURN, 0.5f))
                }
            }
            flashBox(slot, x, y, x1, y1, now)
            hit(key, x, y, x1, y1, gamePress(slot, Experiments.ULTRA_CLICK))
        }
        strip(yS, w, n, { j ->
            0xFF4B5563.toInt() to (if (s.phase == UltraModel.Phase.DISPLAY) "seen" else if (j < s.k) "done" else if (j < i) "sent" else if (j == i) "next" else "todo")
        }, if (s.phase == UltraModel.Phase.INPUT) "${s.k} confirmed${if (s.pending.size > 0) " · ${s.pending.size} sent" else ""}" else "$n to remember")
        escFooter(yF, w, "Esc closes the game · Hypixel then opens Experiment Over")
    }

    // ---------------------------------------------------------------- Superpairs

    /** Short label under a card and its accent color. */
    private fun short(it: ExpItem): String {
        val n = it.plain
        Regex("^(\\d+)k Enchanting Exp$").find(n)?.let { m -> return "${m.groupValues[1]}k" }
        if (n == "Enchanted Book") return SuperpairsModel.cardLabel(it)
        Regex("^(Titanic|Grand) Experience Bottle$").find(n)?.let { m -> return m.groupValues[1] }
        if (n.startsWith("[Lvl")) return n.substringAfter("] ")
        if (n == "Instant Find") return "Instant"
        if (n.startsWith("Gained +3")) return "+3"
        return n
    }

    private fun superpairs(s: SuperpairsModel, now: Long) {
        s.settle(now)
        val w = CONTENT_W
        val cols = 7
        val bw = cols * SP_CELL + (cols - 1) * SP_GAP
        val bh = 4 * SP_CELL + 3 * SP_GAP
        val yH = PAD
        val yP = yH + HEADER_H + GAP
        val yB = yP + PHASE_H + GAP
        val yS = yB + bh + GAP
        val pairs = knownPairs(s)
        val kn = known(s, pairs, w)
        val yF = yS + STRIP_H + (kn.rows - 1) * KNOWN_ROW + 8
        header(yH, w, "Superpairs", s.tier, 'c') {
            later {
                var x = PAD + w
                val t = "${s.timer}s"
                x -= width(t, F_TITLE)
                text(t, x, yH, yH + HEADER_H, F_TITLE, TEXT)
                x -= width("Timer", F_BODY) + 5
                text("Timer", x, yH, yH + HEADER_H, F_BODY, DIM)
                val r = s.remaining.toString()
                x -= width(r, F_TITLE) + 14
                text(r, x, yH, yH + HEADER_H, F_TITLE, if (s.remaining <= 3) 0xFFFFB86B.toInt() else TEXT)
                x -= width("Clicks", F_BODY) + 5
                text("Clicks", x, yH, yH + HEADER_H, F_BODY, DIM)
            }
        }
        val partner = s.partner()
        val openIt = s.open?.let { s.mem[it]?.item }
        val phase = when {
            s.final -> Phase(WAIT, "Board revealed", "Every card face-up · click any card for your rewards")
            s.ending -> Phase(WAIT, "Out of clicks", "Click any card to reveal the whole board")
            s.state == SuperpairsModel.State.MISMATCH -> {
                val left = SuperpairsModel.HIDE_MS - (now - s.mmAt)
                Phase(WAIT, "No match", "Both are remembered · they hide on their own, or a click hides them now (free)",
                    right = { phaseRight(yP, w, "hides in", String.format(Locale.ROOT, "%.1fs", max(0L, left) / 1000.0)) },
                    bar = left.toFloat() / SuperpairsModel.HIDE_MS)
            }
            s.state == SuperpairsModel.State.INSTANT -> Phase(TURN, "Instant Find", "Armed: the next card is revealed together with its pair")
            s.state == SuperpairsModel.State.FIRST ->
                Phase(TURN, "Find its match", if (partner != null) "Open: ${openIt?.let { SuperpairsModel.cardLabel(it) } ?: "?"} · its partner is known, ringed" else "Open: ${openIt?.let { SuperpairsModel.cardLabel(it) } ?: "?"} · partner not seen yet, any unknown card is a fair try")
            else -> Phase(TURN, "Pick a card", if (pairs.isNotEmpty()) "${pairs.size} known pair${if (pairs.size > 1) "s" else ""} marked with letters · any card is fine" else "Any hidden card · Soul remembers every face it sees")
        }
        phaseBar(yP, w, phase, now)
        val bx = PAD + (w - bw) / 2
        val pairOf = HashMap<Int, Int>()
        pairs.forEachIndexed { j, pr -> pr.forEach { pairOf[it] = j } }
        val pulse = pulse(now)
        sentAt.entries.removeIf { now - it.value > SENT_MS || it.key in s.face }
        var hovered: Triple<Int, Int, Int>? = null
        s.grid.forEachIndexed { gi, slot ->
            val x = bx + (gi % cols) * (SP_CELL + SP_GAP)
            val y = yB + (gi / cols) * (SP_CELL + SP_GAP)
            val x1 = x + SP_CELL
            val y1 = y + SP_CELL
            val key = "sp:$slot"
            if (hover == key) hovered = Triple(slot, x, y)
            val face = s.face[slot]
            val mem = s.mem[slot]
            val rare = slot in s.rareSlots && cfg.experiments.rareWarning
            if (face != null) {
                val isOpen = slot == s.open && !s.final
                val inMm = slot in s.mm && !s.final
                val matched = slot in s.matched || (s.final && face.glint)
                val power = slot in s.powers || SuperpairsModel.isPower(face)
                val kind = if (isOpen) "open" else if (inMm) "mm" else if (power) "power" else if (matched) "matched" else "face"
                rr(x, y, x1, y1, if (kind == "matched") 0xFF161616.toInt() else 0xFF262626.toInt())
                item(Experiments.stacks.getOrNull(slot), x + (SP_CELL - SP_ICON) / 2, y + SP_ICON_Y, SP_ICON)
                later {
                    if (kind == "matched") rr(x + (SP_CELL - SP_ICON) / 2, y + SP_ICON_Y, x + (SP_CELL + SP_ICON) / 2, y + SP_ICON_Y + SP_ICON, alpha(0xFF161616.toInt(), 0.55f))
                    textC(short(face), x + SP_CELL / 2, y + SP_LABEL_Y, F_TINY, alpha(0xFFFFFFFF.toInt(), if (kind == "matched") 0.45f else 0.85f))
                    when (kind) {
                        "open" -> ring(x, y, x1, y1, 2, 0xFFFFFFFF.toInt())
                        "mm" -> ring(x, y, x1, y1, 2, alpha(WAIT, 0.9f))
                        "power" -> ring(x, y, x1, y1, 2, alpha(AMBER, 0.8f))
                    }
                }
                // Corner badges: top-right = what the card is doing (open / matched / powerup), top-left = RARE; never over icon or label.
                if (kind == "open") badge(x1 - 2, y + 2, "open", 0xFFFFFFFF.toInt(), 0xFF111111.toInt(), right = true)
                if (kind == "matched") later {
                    rr(x1 - 15, y + 2, x1 - 2, y + 15, alpha(TURN, 0.25f), SoulRenderPipelines.ROUND_PILL)
                    drawIcon(g!!, "check", x1 - 13, y + 4, 9, OK_GREEN)
                }
                if (kind == "power") later {
                    rr(x1 - 15, y + 2, x1 - 2, y + 15, AMBER, SoulRenderPipelines.ROUND_PILL)
                    drawIcon(g!!, "burst", x1 - 13, y + 4, 9, 0xFF111111.toInt())
                }
            } else {
                val pj = pairOf[slot]
                val isPartner = slot == partner
                val kind = if (isPartner) "partner" else if (pj != null) "pair" else if (mem != null) "seen" else "hidden"
                rr(x, y, x1, y1, if (hover == key) GLASS_HI else GLASS)
                later { ring(x, y, x1, y1, 1, GLASS_EDGE) }
                if (mem != null) {
                    item(Experiments.seen[slot], x + (SP_CELL - SP_ICON) / 2, y + SP_ICON_Y, SP_ICON)
                    val a = if (kind == "seen") 0.5f else 0.7f
                    later {
                        rr(x + (SP_CELL - SP_ICON) / 2, y + SP_ICON_Y, x + (SP_CELL + SP_ICON) / 2, y + SP_ICON_Y + SP_ICON, alpha(if (hover == key) GLASS_HI else GLASS, 1f - a))
                        textC(short(mem.item), x + SP_CELL / 2, y + SP_LABEL_Y, F_TINY, alpha(0xFFFFFFFF.toInt(), 0.95f * a))
                    }
                }
                // Seen once: the dimmed face says it; a small dot in the free top-left corner marks it.
                if (kind == "seen" && !rare) later { rr(x + 5, y + 5, x + 11, y + 11, 0xCC99AABB.toInt(), SoulRenderPipelines.ROUND_PILL) }
                if (kind == "pair") {
                    val pc = PAIR_COLORS[pj!! % PAIR_COLORS.size]
                    later { ring(x, y, x1, y1, 2, alpha(pc, 0.85f)) }
                    badge(x1 - 2, y + 2, ('A' + pj).toString(), pc, 0xFF111111.toInt(), right = true)
                }
                if (slot in sentAt) later { ring(x + 3, y + 3, x1 - 3, y1 - 3, 1, 0xB3FFFFFF.toInt(), 3) }
                if (isPartner) {
                    later { ring(x - 4, y - 4, x1 + 4, y1 + 4, 3, TURN, 5); ring(x - 6, y - 6, x1 + 6, y1 + 6, 2, alpha(TURN, 0.35f * pulse), 6) }
                    pj?.let { badge(x1 - 2, y + 2, ('A' + it).toString(), PAIR_COLORS[it % PAIR_COLORS.size], 0xFF111111.toInt(), right = true) }
                }
            }
            if (rare) {
                later { ring(x - 3, y - 3, x1 + 3, y1 + 3, 2, alpha(RARE, 0.6f + 0.4f * pulse), 5) }
                badge(x + 2, y + 2, "RARE", RARE, 0xFF111111.toInt())
            }
            flashBox(slot, x, y, x1, y1, now)
            hit(key, x, y, x1, y1, gamePress(slot, Experiments.SUPERPAIRS_CLICK))
        }
        // known pairs (letter + label chips, wrapping) + counts
        later { text("Known", PAD, yS, yS + STRIP_H, F_SMALL, DIM) }
        kn.labels.forEachIndexed { j, label ->
            val cx = PAD + kn.pos[j].first
            val cy = yS + kn.pos[j].second * KNOWN_ROW
            val cw = kn.widths[j]
            val pc = PAIR_COLORS[j % PAIR_COLORS.size]
            rr(cx, cy + 3, cx + cw, cy + STRIP_H - 3, alpha(pc, 0.14f))
            later {
                ring(cx, cy + 3, cx + cw, cy + STRIP_H - 3, 1, alpha(pc, 0.5f), 2)
                rr(cx + 3, cy + 6, cx + 15, cy + 18, pc, SoulRenderPipelines.ROUND_PILL)
                textC(('A' + j).toString(), cx + 9, cy + 12, F_TINY, 0xFF111111.toInt())
                text(label, cx + 19, cy, cy + STRIP_H, F_TINY, 0xFFDDDDDD.toInt())
            }
        }
        if (pairs.isEmpty()) later { text("none yet", PAD + 44, yS, yS + STRIP_H, F_SMALL, FAINT) }
        later { textR(kn.stats, PAD + w, yS, yS + STRIP_H, F_SMALL, DIM) }
        val hint = if (cfg.experiments.rareWarning && !RngMeterStore.known()) "Open /rng → Experimentation Table once to enable rare-card warnings" else "Esc closes · Hypixel then opens Superpairs Rewards"
        escFooter(yF, w, hint)
        rareBanner(s, now, w)
        hovered?.let { (slot, x, y) ->
            val it = s.face[slot] ?: s.mem[slot]?.item ?: return@let
            val lines = listOf("§f${it.plain}") + it.plainLore.filter { l -> l != SuperpairsModel.PROCEED && l.isNotBlank() }.map { l -> "§7$l" }
            tip(x, y, x + SP_CELL, y + SP_CELL, if (slot in s.face) lines else lines + "§8Remembered from an earlier reveal")
        }
    }

    /** The rare reward banner: above the panel while the newest rare card is fresh or still unmatched. */
    private fun rareBanner(s: SuperpairsModel, now: Long, w: Int) {
        if (!cfg.experiments.rareWarning) return
        val item = s.lastRare ?: return
        val unmatched = s.rareSlots.any { it !in s.matched }
        if (now - s.lastRareAt > RARE_BANNER_MS && !unmatched) return
        val label = "Rare reward: ${SuperpairsModel.cardLabel(item)}"
        val sub = if (s.rareSlots.all { it in s.matched }) "matched!" else "match it before your clicks run out"
        val tw = width(label, F_BODY + 1) + width(sub, F_SMALL) + 44
        val x0 = PAD + (w - tw) / 2
        val y0 = -30
        rr(x0 - 1, y0 - 1, x0 + tw + 1, y0 + 25, alpha(RARE, 0.8f), SoulRenderPipelines.ROUND_PILL)
        rr(x0, y0, x0 + tw, y0 + 24, 0xFF240F2A.toInt(), SoulRenderPipelines.ROUND_PILL)
        later {
            rr(x0 + 8, y0 + 7, x0 + 18, y0 + 17, RARE, SoulRenderPipelines.ROUND_PILL)
            text(label, x0 + 24, y0, y0 + 24, F_BODY + 1, RARE)
            text(sub, x0 + 30 + width(label, F_BODY + 1), y0, y0 + 24, F_SMALL, 0xFFDDBBEE.toInt())
        }
    }

    // ---------------------------------------------------------------- menus

    private fun table(t: ExpMenus.Table) {
        val w = 572
        val cw = intArrayOf(178, 192, 178)
        val cg = 12
        val ch = 176
        val yH = PAD
        val yC = yH + HEADER_H + GAP
        val yR = yC + ch + GAP
        val yA = yR + if (t.renew != null) 46 + GAP else 0
        header(yH, w, "Experimentation Table", null, 'f') { }
        t.close?.let { button(PAD + w - 64, yH + 2, 64, 20, "Close", "menu:$it", "plain", action = menuButton(it, "menu:$it")) }
        var x = PAD
        val cards = listOf("chrono" to t.chrono.slot, "sp" to t.sp.slot, "ultra" to t.ultra.slot)
        cards.forEachIndexed { ci, (game, slot) ->
            val cwid = cw[ci]
            val key = "card:$game"
            val hv = hover == key || hover?.startsWith("menu:$slot") == true
            rr(x - 1, yC - 1, x + cwid + 1, yC + ch + 1, CARD_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
            rr(x, yC, x + cwid, yC + ch, if (hv) CARD_HI else CARD, SoulRenderPipelines.ROUND_MEDIUM)
            hit(key, x, yC, x + cwid, yC + ch, null)
            item(Experiments.stacks.getOrNull(slot), x + 10, yC + 10, 26)
            val name = ExpMenus.GAME_NAME[game]!!
            val cx = x
            later {
                text(name, cx + 44, yC + 11, yC + 23, F_BODY + 1.5f, mc('d'))
                text(if (game == "sp") "Main experiment" else "Add-on experiment", cx + 44, yC + 26, yC + 36, F_TINY, DIM)
            }
            val by = yC + ch - 34
            if (game == "sp") {
                val sp = t.sp
                later {
                    var px = cx + 10
                    for (j in 0 until sp.max) {
                        rr(px, yC + 51, px + 9, yC + 60, if (j < (sp.charges ?: 0)) mc('d') else 0xFF3A3A3A.toInt(), SoulRenderPipelines.ROUND_PILL)
                        px += 12
                    }
                    text("${sp.charges ?: "?"}/${sp.max} charges", px + 2, yC + 48, yC + 62, F_SMALL, 0xFFCCCCCC.toInt())
                    if (sp.next.isNotEmpty()) text("Next charge in ${sp.next}", cx + 10, yC + 66, yC + 76, F_SMALL, DIM)
                    lines(cx + 10, yC + 84, listOf("Find pairs of items on the grid", "to unlock them. Enchanting Exp", "and enchanted books every day."))
                }
                val ok = sp.status == "Click to play!"
                button(x + 10, by, cwid - 20, 24, if (ok) "Play" else sp.status.removeSuffix("!"), "menu:$slot", if (ok) "go" else "muted", action = menuButton(slot, "menu:$slot"))
            } else {
                val a = if (game == "chrono") t.chrono else t.ultra
                chip(x + 10, yC + 48, if (a.done) "Completed · +${a.bonus} clicks" else "Pending", if (a.done) TURN else DIM)
                later {
                    lines(cx + 10, yC + 70, if (game == "chrono") listOf("Repeat the musical pattern to", "form the longest chain of notes.", "Extra Enchanting Exp and", "Superpairs clicks.") else listOf("Numbers appear, then all", "disappear: click them in order", "from memory. Extra Exp and", "Superpairs clicks."))
                }
                if (a.status == "Click to play!") {
                    button(x + 10, by, cwid - 20, 24, "Play", "menu:$slot", "go", action = menuButton(slot, "menu:$slot"))
                } else {
                    button(x + 10, by, cwid - 20 - (if (a.practice) 76 else 0), 24, a.status.removeSuffix("!").ifEmpty { "Play" }, "menu:$slot", "muted", action = menuButton(slot, "menu:$slot"))
                    if (a.practice) button(x + cwid - 82, by, 72, 24, "Practice", "menu:${slot}r", "plain", action = menuButton(slot, "menu:${slot}r", Experiments.PRACTICE_CLICK))
                }
            }
            x += cwid + cg
        }
        t.renew?.let { rn ->
            rr(PAD - 1, yR - 1, PAD + w + 1, yR + 47, CARD_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
            rr(PAD, yR, PAD + w, yR + 46, CARD, SoulRenderPipelines.ROUND_MEDIUM)
            item(Experiments.stacks.getOrNull(rn.slot), PAD + 10, yR + 10, 26)
            later {
                text("Renew Experiments", PAD + 44, yR + 10, yR + 22, F_BODY + 1, mc('a'))
                text("Buy 1 additional charge · renewed ${rn.renewed}/3 today", PAD + 44, yR + 26, yR + 36, F_SMALL, DIM)
                val cost = listOfNotNull(rn.levels, rn.bits).joinToString(" · ")
                text("Costs", PAD + 300, yR, yR + 46, F_SMALL, DIM)
                text(cost, PAD + 300 + width("Costs ", F_SMALL), yR, yR + 46, F_SMALL, mc('b'))
            }
            button(PAD + w - 110, yR + 11, 100, 24, "Purchase", "menu:${rn.slot}", "primary", action = menuButton(rn.slot, "menu:${rn.slot}"))
        }
        t.rng?.let { rng ->
            val key = "menu:${rng.slot}"
            rr(PAD, yA, PAD + 300, yA + 30, if (hover == key) 0xFF2C2C2C.toInt() else 0xFF222222.toInt())
            hit(key, PAD, yA, PAD + 300, yA + 30, menuButton(rng.slot, key))
            later {
                var px = PAD + 10
                text("RNG Meter", px, yA, yA + 30, F_SMALL, mc('d'))
                px += width("RNG Meter", F_SMALL) + 6
                text(rng.drop, px, yA, yA + 30, F_SMALL, mc('a'))
                px += width(rng.drop, F_SMALL) + 8
                rr(px, yA + 13, px + 70, yA + 18, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_PILL)
                val pct = rng.pct.toFloatOrNull() ?: 0f
                if (pct > 0f) rr(px, yA + 13, px + max(5, (70 * pct / 100f).toInt()), yA + 18, mc('d'), SoulRenderPipelines.ROUND_PILL)
                px += 76
                text("${rng.pct}% · ${rng.amount}", px, yA, yA + 30, F_SMALL, 0xFFCCCCCC.toInt())
            }
        }
        t.bottles?.let { b -> button(PAD + 310, yA, 150, 30, "Experience Bottles", "menu:$b", "plain", mc('3'), menuButton(b, "menu:$b")) }
    }

    private fun lines(x: Int, y: Int, lines: List<String>, color: Int = 0xFFAAAAAA.toInt(), size: Float = F_SMALL) {
        lines.forEachIndexed { j, l -> text(l, x, y + j * 12, y + j * 12 + 12, size, color, semi = false) }
    }

    private fun stakes(v: ExpMenus.Stakes) {
        val w = 600
        val rowH = 50
        val rg = 6
        val yH = PAD
        val yL = yH + HEADER_H + GAP
        val yN = yL + v.tiers.size * (rowH + rg) - rg + GAP
        header(yH, w, "${ExpMenus.GAME_NAME[v.game]} · Stakes", null, 'f') { }
        val captured = mapOf("chrono" to setOf("Metaphysical"), "ultra" to setOf("Metaphysical"), "sp" to setOf("Transcendent"))[v.game].orEmpty()
        val now = System.currentTimeMillis()
        v.tiers.forEachIndexed { j, t ->
            val y = yL + j * (rowH + rg)
            val key = "menu:${t.slot}"
            val hv = hover == key && t.playable
            rr(PAD - 1, y - 1, PAD + w + 1, y + rowH + 1, CARD_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
            rr(PAD, y, PAD + w, y + rowH, if (hv) CARD_HI else CARD, SoulRenderPipelines.ROUND_MEDIUM)
            item(Experiments.stacks.getOrNull(t.slot), PAD + 12, y + (rowH - 24) / 2, 24)
            val dim = if (t.playable) 1f else 0.55f
            if (t.tier !in captured) chip(PAD + 46, y + 29, "vanilla board", DIM, F_TINY, 11)
            val facts = when (v.game) {
                "chrono" -> "${t.colors ?: "?"} colors · ${t.xp ?: "?"} XP per note in your longest chain"
                "ultra" -> "Grid ${t.grid ?: "?"} · ${t.xp ?: "?"} XP per number in your highest series"
                else -> "Grid ${t.grid ?: "?"} · ${t.clicks ?: "?"} clicks (${t.clickParts.joinToString(" ")})"
            }
            val second = if (v.game == "sp") t.pairs.take(3).joinToString(" · ") + (if (t.pairs.size > 3) " · +${t.pairs.size - 3} more" else "") else "Superpairs clicks: ${t.rewards.joinToString(" · ")}"
            later {
                text(t.tier, PAD + 46, y + 9, y + 22, F_BODY + 1.5f, alpha(mc(t.color), dim))
                text(facts, PAD + 150, y + 9, y + 21, F_SMALL, alpha(0xFFCCCCCC.toInt(), dim))
                text(ellipsize(second, 300, F_TINY + 0.5f), PAD + 150, y + 27, y + 39, F_TINY + 0.5f, alpha(DIM, dim))
                val req = "Enchanting ${t.requires?.removePrefix("Enchanting ") ?: "?"}"
                val cost = t.cost?.let { " · $it XP Levels" } ?: ""
                textR(req + cost, PAD + w - 12, y + 9, y + 21, F_SMALL, alpha(0xFFAAAAAA.toInt(), dim))
                if (t.playable) textR("Play ›", PAD + w - 12, y + 27, y + 39, F_SMALL, TURN) else textR(t.status, PAD + w - 12, y + 27, y + 39, F_SMALL, alpha(mc('c'), dim))
            }
            if (!t.playable) later { rr(PAD, y, PAD + w, y + rowH, 0x55101010, SoulRenderPipelines.ROUND_MEDIUM) }
            flashBox(t.slot, PAD, y, PAD + w, y + rowH, now)
            hit(key, PAD, y, PAD + w, y + rowH, menuButton(t.slot, key))
        }
        v.back?.let { button(PAD, yN, 110, 26, "‹ Go Back", "menu:$it", "plain", action = menuButton(it, "menu:$it")) }
        v.close?.let { button(PAD + w - 90, yN, 90, 26, "Close", "menu:$it", "plain", action = menuButton(it, "menu:$it")) }
    }

    /** Known pairs among hidden cards, then the open card with its known partner (lettered like the others). */
    private fun knownPairs(s: SuperpairsModel): List<List<Int>> {
        val o = s.open
        val p = s.partner()
        return if (o != null && p != null) s.pairs() + listOf(listOf(o, p)) else s.pairs()
    }

    private class Known(val labels: List<String>, val pos: List<Pair<Int, Int>>, val widths: List<Int>, val rows: Int, val stats: String)

    /** Chips (letter + label) after "Known": the first row stops short of the stats, further rows use the full width. */
    private fun known(s: SuperpairsModel, pairs: List<List<Int>>, w: Int): Known {
        val inPair = pairs.flatten().toSet()
        val seenOnce = s.grid.count { s.hidden(it) && s.mem.containsKey(it) && it !in inPair }
        val stats = "$seenOnce seen once · ${s.matched.size / 2} matched · ${s.powers.size} powerup${if (s.powers.size == 1) "" else "s"}"
        val firstEnd = w - width(stats, F_SMALL) - 10
        val labels = pairs.map { ellipsize(s.mem[it[0]]?.item?.let(::short) ?: "?", 90, F_TINY) }
        val pos = ArrayList<Pair<Int, Int>>()
        val widths = ArrayList<Int>()
        var x = 44
        var row = 0
        for (l in labels) {
            val cw = 24 + width(l, F_TINY)
            if (x > 44 && x + cw > (if (row == 0) firstEnd else w)) {
                row++
                x = 44
            }
            pos += x to row
            widths += cw
            x += cw + 4
        }
        return Known(labels, pos, widths, row + 1, stats)
    }

    private fun ellipsize(s: String, maxW: Int, size: Float): String {
        if (width(s, size) <= maxW) return s
        var t = s
        while (t.isNotEmpty() && width("$t…", size) > maxW) t = t.dropLast(1)
        return "$t…"
    }

    private fun resultHead(slot: Int, game: String, title: String, sub: String, subColor: Int) {
        item(Experiments.stacks.getOrNull(slot), PAD, PAD, 30)
        later {
            text(ExpMenus.GAME_NAME[game] ?: title, PAD + 40, PAD + 1, PAD + 15, F_TITLE, mc('d'))
            text(sub, PAD + 40, PAD + 18, PAD + 30, F_BODY, subColor)
        }
    }

    private fun resultRows(rows: List<Pair<String, String>>, rewards: List<String>, w: Int): Int {
        val yR = PAD + 40
        later {
            rows.forEachIndexed { j, (k, v) ->
                text(k, PAD, yR + j * 16, yR + j * 16 + 14, F_BODY, DIM)
                text(v, PAD + 110, yR + j * 16, yR + j * 16 + 14, F_BODY, TEXT)
            }
        }
        val yW = yR + rows.size * 16 + 8
        val hW = 14 + rewards.size * 15
        rr(PAD - 1, yW - 1, PAD + w + 1, yW + hW + 1, CARD_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
        rr(PAD, yW, PAD + w, yW + hW, CARD, SoulRenderPipelines.ROUND_MEDIUM)
        later {
            text("Rewards", PAD + 10, yW + 2, yW + 12, F_TINY, DIM)
            rewards.forEachIndexed { j, l ->
                val c = if (l.contains("Enchanting Exp")) mc('3') else if (l.contains("click", ignoreCase = true)) mc('d') else mc('9')
                text(l, PAD + 10, yW + 14 + j * 15, yW + 27 + j * 15, F_BODY, c)
            }
        }
        return yW + 20 + rewards.size * 15 + 12
    }

    private fun over(v: ExpMenus.Over) {
        val w = 400
        resultHead(v.slot, v.game, "Experiment Over", v.reason, 0xFFFF7B7B.toInt())
        val yB = resultRows(listOfNotNull("Stakes" to v.stakes, v.chain?.let { c -> c.substringBefore(":") to c.substringAfter(":").trim() }), v.rewards, w)
        button(PAD, yB, w - 130, 28, "Claim rewards", "menu:${v.slot}", "go", action = menuButton(v.slot, "menu:${v.slot}"))
        v.decline?.let { d -> button(PAD + w - 120, yB, 120, 28, "Decline", "menu:$d", "danger", action = menuButton(d, "menu:$d")) }
        later {
            text("Decline your rewards to play one more time today.", PAD, yB + 34, yB + 46, F_SMALL, DIM, semi = false)
            text("Subsequent attempts today will cost XP levels.", PAD, yB + 46, yB + 58, F_SMALL, 0xFFFF8A8A.toInt(), semi = false)
        }
    }

    private fun rewards(v: ExpMenus.Rewards) {
        val w = 400
        resultHead(v.slot, "sp", "Superpairs Rewards", "Experiment over", DIM)
        val yB = resultRows(listOf("Stakes" to v.stakes, "Clicks used" to v.used, "Pairs found" to v.pairs), v.rewards, w)
        button(PAD, yB, w, 28, "Claim rewards", "menu:${v.slot}", "go", action = menuButton(v.slot, "menu:${v.slot}"))
    }

    /** Item id path of a stack (for its dye color). */
    private object BuiltinId {
        fun of(stack: ItemStack?): String = if (stack == null || stack.isEmpty) "" else net.minecraft.core.registries.BuiltInRegistries.ITEM.getKey(stack.item).path
    }
}
