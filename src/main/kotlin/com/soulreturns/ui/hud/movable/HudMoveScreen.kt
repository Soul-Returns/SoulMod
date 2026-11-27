package com.soulreturns.ui.hud.movable

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.MoveGuiConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.inventory.InventoryPreview
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The "Move GUI" editor, drawn 1:1 over the running game: the active category's HUDs (every
 * category with Show all) render exactly where they will be, resolved by the same [HudLayout] as the
 * live HUD, next to the real vanilla HUD under a light dim.
 *
 * Chrome: a top bar fixed top-center (category segmented control, Show all, Undo/Redo/Reset/Done,
 * pin, cog with the editor settings) that slides away when unpinned; the inspector, hidden until
 * right-click or the "…" pill, placed beside the selection away from its link target; and the
 * Controls reference top-left, which fades out of the way of a HUD being moved near it.
 *
 * Dragging snaps to guides (screen edges at the edge-snap distance, centers, other HUDs, the vanilla
 * HUD; Alt ignores them) and attaches a HUD dropped next to another element (blue edge + chip);
 * an attached HUD slides along its target's edge, glues to an edge or the center, and detaches when
 * pulled more than [HudSnap.UNLINK_PX] away. Every position edit goes through [HudEdits].
 *
 * Everything is in Soul-logical px, the space [HudTransform] is stored in.
 */
class HudMoveScreen(
    private val parent: Screen? = null,
) : SoulScreenBase(Component.literal("Move GUI")) {
    private companion object {
        const val PAD = 6
        const val GAP = 6
        const val BTN_H = 18
        const val ROW_H = 16
        const val ROW_GAP = 3
        const val FIELD_H = 14
        const val LABEL_W = 42
        const val HEADER_H = 26
        const val SEC_H = 20
        const val SCALE_MIN = 0.25f
        const val SCALE_MAX = 4f
        const val COALESCE_MS = 800L
        const val BAR_HIDE_MS = 1400L
        const val SLIDE_MS = 220f
        const val FADE_MS = 180f
        const val KEYS_NEAR = 24f
        const val KEYS_LINGER_MS = 600L
        const val GLOW_MS = 900L
        const val SCROLL_STEP = 20f
        const val DRAG_START = 2.0

        val DIM = 0x30000000
        val GUIDE = 0xFFEC4899.toInt()
        val HOVER = 0xFF60A5FA.toInt()
        val LINK = 0xFF3B82F6.toInt()
        val LINK_LIGHT = 0xFF93C5FD.toInt()
        val IDLE_OUTLINE = 0x40FFFFFF
        val WARN = 0xFFFBBF24.toInt()
        val PANEL_SOLID = 0xFF1A1A1A.toInt()
        val POPUP = 0xFF1B1B1B.toInt()
        val BADGE = 0xFF1E3A6B.toInt()
        val DANGER = 0xFF7F1D1D.toInt()
        val DANGER_TEXT = 0xFFFECACA.toInt()
        val WHITE = 0xFFFFFFFF.toInt()

        val H_NAMES = listOf("left", "center", "right")
        val V_NAMES = listOf("top", "center", "bottom")

        // Session state: kept while the game runs, never saved.
        var showAll = false
        var styleOpen = false
        var advancedOpen = false
        var lastCat = "general"
    }

    // ---------------------------------------------------------------- fonts

    private val f10 = fCaption
    private val f9h = 9.5f * MsdfFont.LEGACY_EM
    private val f9 = 9f * MsdfFont.LEGACY_EM
    private val f8h = 8.5f * MsdfFont.LEGACY_EM
    private val f11 = fBody
    private val f13 = fHeading
    private val regular: MsdfFont get() = MsdfFonts.menuRegular
    private val semi: MsdfFont get() = MsdfFonts.menuSemibold

    // ---------------------------------------------------------------- model

    private fun hud(id: String): MovableHud? = MovableHudRegistry.all().firstOrNull { it.id == id }

    private val sizes = HashMap<String, FloatArray>()

    /** Whether a HUD has a settings screen (asked once; building one per frame would be wasteful). */
    private val settingsAvailable = HashMap<String, Boolean>()

    /** The editor's view of the HUDs: preview sizes, "shown" = feature on; writes go to config. */
    private val store =
        object : HudEditStore {
            override fun ids(): List<String> = MovableHudRegistry.all().map { it.id }

            override fun transform(id: String): HudTransform? = hud(id)?.readTransform()

            override fun size(id: String): FloatArray =
                sizes.getOrPut(id) { hud(id)!!.contentSize(true).let { floatArrayOf(it.first.toFloat(), it.second.toFloat()) } }

            override fun shown(id: String): Boolean = hud(id)?.configuredOn() == true

            override fun set(id: String, transform: HudTransform) {
                val h = hud(id) ?: return
                if (h.readTransform() != transform) h.writeTransform(transform)
            }

            override fun layout(): HudLayout = HudLayouts.build(this, dragOverride())
        }

    private val edits = HudEdits(store) { settings().linkGap }

    private fun settings(): MoveGuiConfig = cfg.general.moveGui

    private fun updateSettings(change: MoveGuiConfig.() -> MoveGuiConfig) =
        ConfigManager.update { copy(general = general.copy(moveGui = general.moveGui.change())) }

    /** This frame's layout (rebuilt after every edit). */
    private var layout: HudLayout = store.layout()

    private fun relayout() {
        sizes.clear()
        layout = store.layout()
    }

    private val screenW: Float get() = layout.screenW
    private val screenH: Float get() = layout.screenH

    private fun categories(): List<HudCategory> =
        HudCategories.ordered(MovableHudRegistry.all().filter { it.configuredOn() }.map { it.categoryId }.toSet())

    private fun inView(h: MovableHud): Boolean = h.configuredOn() && (showAll || pick != null || h.categoryId == activeCat)

    /** HUDs drawn and editable now, in draw order. */
    private fun visibleIds(): List<String> = MovableHudRegistry.all().filter(::inView).map { it.id }

    private fun box(id: String): HudBox? = layout.box(id)

    /** HUDs under a point, top-most (last drawn, the selection on top) first. */
    private fun hudsAt(x: Float, y: Float): List<String> {
        val ids = visibleIds().filter { box(it)?.contains(x, y) == true }.asReversed()
        val s = sel
        return if (s != null && s in ids) listOf(s) + (ids - s) else ids
    }

    private fun catTitle(id: String): String = HudCategories.of(id).title

    private fun name(id: String): String = hud(id)?.displayName ?: id

    private fun targetName(to: String): String =
        HudTargets.vanillaId(to)?.let { VanillaIds.name(it) } ?: HudTargets.hudId(to)?.let(::name) ?: to

    private fun hudLabel(id: String): String =
        if (showAll || pick != null) "${name(id)} · ${catTitle(hud(id)?.categoryId ?: "")}" else name(id)

    /** The inventory frame in GUI px, centered like `AbstractContainerScreen` places it. */
    private fun inventoryGui(): IntArray {
        val left = (width - InventoryPreview.WIDTH) / 2
        val top = (height - InventoryPreview.HEIGHT) / 2
        return intArrayOf(left, top, left + InventoryPreview.WIDTH, top + InventoryPreview.HEIGHT)
    }

    private fun inventorySoul(): FloatArray {
        val f = SoulUi.factor()
        val r = inventoryGui()
        return floatArrayOf(r[0] / f, r[1] / f, r[2] / f, r[3] / f)
    }

    private fun backdropShown(): Boolean =
        if (showAll || pick != null) visibleIds().any { HudCategories.of(hud(it)!!.categoryId).inventoryBackdrop }
        else HudCategories.of(activeCat).inventoryBackdrop

    // ---------------------------------------------------------------- state

    private var activeCat = lastCat
    private var sel: String? = null
    private var inspOpen = false
    private var inspRight = true
    private var cogOpen = false

    /** The HUD whose link target is being picked on screen (crosshair), or null. */
    private var pick: String? = null

    private var peek = false
    private var barLeftAt = 0L
    private var barSlide = 0f
    private var keysAlpha = 1f
    private var lastFrameAt = System.currentTimeMillis()
    private var touchId: String? = null
    private var touchUntil = 0L
    private var glowId: String? = null
    private var glowUntil = 0L

    private class Press(val id: String, val x: Float, val y: Float, val cycle: Boolean, val under: List<String>)

    private class Slide(val axis: Int, val main0: Float, val target: FloatArray, val name: String)

    private class Drag(val id: String, val px: Float, val py: Float, val start: HudBox, val slide: Slide?) {
        var box: HudBox = start
        val guidesX = ArrayList<Float>()
        val guidesY = ArrayList<Float>()
        var link: HudSnap.Attach? = null
        var keep = false
        var glue: Int? = null
    }

    private var press: Press? = null
    private var drag: Drag? = null

    private fun dragOverride(): Map<String, HudBox> = drag?.let { mapOf(it.id to it.box) } ?: emptyMap()

    private var sliderId: String? = null
    private var sliderBefore: Map<String, HudTransform>? = null
    private var sliderX0 = 0
    private var sliderX1 = 1

    // Panel rects of the last frame (Soul px), null = not shown.
    private var barRect: IntArray? = null
    private var handleRect: IntArray? = null
    private var inspRect: IntArray? = null
    private var cogRect: IntArray? = null
    private var keysRect: IntArray? = null
    private var pickBarRect: IntArray? = null
    private var inspBody = intArrayOf(0, 0, 0, 0)
    private var inspScroll = 0f
    private var inspContentH = 0

    // ---------------------------------------------------------------- undo

    private class Step(val before: Map<String, HudTransform>, var after: Map<String, HudTransform>, val key: String?, var at: Long, val focus: String?)

    private val undoStack = ArrayList<Step>()
    private val redoStack = ArrayList<Step>()

    private fun snapshot(): Map<String, HudTransform> = MovableHudRegistry.all().associate { it.id to it.readTransform() }

    /** One undoable step; same-[key] steps in quick succession merge (scroll / nudge). */
    private fun edit(key: String? = null, focus: String? = sel, change: () -> Unit) {
        val before = snapshot()
        val ks = edits.anchorKs()
        change()
        relayout()
        edits.rebaseFollowers(ks)
        relayout()
        pushStep(before, snapshot(), key, focus)
    }

    private fun pushStep(before: Map<String, HudTransform>, after: Map<String, HudTransform>, key: String?, focus: String?) {
        if (before == after) return
        val now = System.currentTimeMillis()
        val last = undoStack.lastOrNull()
        if (key != null && last != null && last.key == key && now - last.at < COALESCE_MS) {
            last.after = after
            last.at = now
        } else {
            undoStack.add(Step(before, after, key, now, focus))
        }
        redoStack.clear()
    }

    private fun restore(state: Map<String, HudTransform>) {
        for ((id, t) in state) store.set(id, t)
        relayout()
    }

    private fun undo() {
        val step = undoStack.removeLastOrNull() ?: return
        restore(step.before)
        redoStack.add(step)
        focusStep(step)
    }

    private fun redo() {
        val step = redoStack.removeLastOrNull() ?: return
        restore(step.after)
        undoStack.add(step)
        focusStep(step)
    }

    /** Show what an undo/redo touched: select that HUD (switching category). */
    private fun focusStep(step: Step) {
        val h = step.focus?.let(::hud) ?: return
        if (!h.configuredOn()) return
        if (!showAll) activeCat = h.categoryId
        sel = h.id
    }

    private fun resetHuds(ids: List<String>) {
        for (id in ids) hud(id)?.let { store.set(id, it.defaultTransform()) }
    }

    // ---------------------------------------------------------------- draw helpers

    /** Opacity applied to everything the current panel draws (the inspector fades while dragging / picking). */
    private var alpha = 1f

    /** Measure pass: layout runs, nothing is drawn or clickable. */
    private var measuring = false

    /** Clicks inside a scrolled body are cut to it. */
    private var hitClip: IntArray? = null

    private val rightClick = java.util.Collections.newSetFromMap(java.util.IdentityHashMap<Any, Boolean>())

    private fun c(color: Int): Int = if (alpha >= 1f) color else (((color ushr 24) * alpha).toInt() shl 24) or (color and 0xFFFFFF)

    private fun rr(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, pipe: com.mojang.blaze3d.pipeline.RenderPipeline = SoulRenderPipelines.ROUND_SMALL) {
        if (!measuring && x1 > x0 && y1 > y0) roundRect(g, x0, y0, x1, y1, c(color), pipe)
    }

    private fun fl(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        if (!measuring) g.fill(x0, y0, x1, y1, c(color))
    }

    /** A fill at physical-pixel precision (small icon strokes). */
    private fun ff(g: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, color: Int) {
        if (measuring) return
        val k = SoulUi.scale
        g.pose().pushMatrix()
        g.pose().scale(1f / k, 1f / k)
        g.fill((x0 * k).roundToInt(), (y0 * k).roundToInt(), (x1 * k).roundToInt(), (y1 * k).roundToInt(), c(color))
        g.pose().popMatrix()
    }

    private fun poly(g: GuiGraphicsExtractor, points: FloatArray, color: Int) {
        if (!measuring) fillPolygon(g, points, c(color))
    }

    private fun tx(g: GuiGraphicsExtractor, s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float = f10, weight: MsdfFont = regular) {
        if (!measuring && s.isNotEmpty()) drawTextV(g, s, x, top, bottom, c(color), size, weight)
    }

    private fun txCenter(g: GuiGraphicsExtractor, s: String, x0: Int, x1: Int, top: Int, bottom: Int, color: Int, size: Float = f10, weight: MsdfFont = regular) =
        tx(g, s, (x0 + x1 - textWidth(s, size, weight)) / 2, top, bottom, color, size, weight)

    private fun hit(x0: Int, y0: Int, x1: Int, y1: Int, right: Boolean = false, action: () -> Unit) {
        if (measuring || suppressRegions) return
        var a0 = x0
        var b0 = y0
        var a1 = x1
        var b1 = y1
        hitClip?.let { v ->
            a0 = max(a0, v[0])
            b0 = max(b0, v[1])
            a1 = min(a1, v[2] - 1)
            b1 = min(b1, v[3] - 1)
            if (a0 > a1 || b0 > b1) return
        }
        val r = Region(a0, b0, a1, b1, action)
        regions.add(r)
        if (right) rightClick.add(r)
    }

    private fun hovered(mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int): Boolean =
        !measuring && mx in x0..x1 && my in y0..y1 && hitClip.let { it == null || (mx in it[0] until it[2] && my in it[1] until it[3]) }

    private fun outline(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        fl(g, x0, y0, x1, y0 + 1, color)
        fl(g, x0, y1 - 1, x1, y1, color)
        fl(g, x0, y0 + 1, x0 + 1, y1 - 1, color)
        fl(g, x1 - 1, y0 + 1, x1, y1 - 1, color)
    }

    private fun dashed(g: GuiGraphicsExtractor, r: FloatArray, color: Int) {
        val x0 = floor(r[0]).toInt()
        val y0 = floor(r[1]).toInt()
        val x1 = max(x0 + 1, r[2].roundToInt())
        val y1 = max(y0 + 1, r[3].roundToInt())
        var x = x0
        while (x < x1) {
            val e = min(x + 3, x1)
            fl(g, x, y0, e, y0 + 1, color)
            fl(g, x, y1 - 1, e, y1, color)
            x += 5
        }
        var y = y0
        while (y < y1) {
            val e = min(y + 3, y1)
            fl(g, x0, y, x0 + 1, e, color)
            fl(g, x1 - 1, y, x1, e, color)
            y += 5
        }
    }

    private fun rotated(cx: Float, cy: Float, angle: Float, vararg pts: Float): FloatArray {
        val out = FloatArray(pts.size)
        val cs = cos(angle)
        val sn = sin(angle)
        var i = 0
        while (i < pts.size) {
            val x = pts[i]
            val y = pts[i + 1]
            out[i] = cx + x * cs - y * sn
            out[i + 1] = cy + x * sn + y * cs
            i += 2
        }
        return out
    }

    // ---------------------------------------------------------------- icons (no glyphs in the MSDF atlases)

    /** Chevron pointing up or down, [s] half-width. */
    private fun chevron(g: GuiGraphicsExtractor, cx: Float, cy: Float, s: Float, up: Boolean, color: Int) {
        val t = max(1f, s * 0.45f)
        val d = if (up) -1f else 1f
        poly(g, floatArrayOf(cx - s, cy - d * s / 2, cx, cy + d * s / 2, cx + s, cy - d * s / 2, cx + s, cy - d * s / 2 + d * t, cx, cy + d * s / 2 + d * t, cx - s, cy - d * s / 2 + d * t), color)
    }

    private fun triangle(g: GuiGraphicsExtractor, cx: Float, cy: Float, s: Float, dir: Int, color: Int) {
        // dir: 0 up, 1 right, 2 down
        when (dir) {
            0 -> poly(g, floatArrayOf(cx - s, cy + s / 2, cx, cy - s / 2, cx + s, cy + s / 2), color)
            1 -> poly(g, floatArrayOf(cx - s / 2, cy - s, cx + s / 2, cy, cx - s / 2, cy + s), color)
            else -> poly(g, floatArrayOf(cx - s, cy - s / 2, cx + s, cy - s / 2, cx, cy + s / 2), color)
        }
    }

    private fun circle(g: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, color: Int) {
        rr(g, (cx - r).roundToInt(), (cy - r).roundToInt(), (cx + r).roundToInt(), (cy + r).roundToInt(), color, SoulRenderPipelines.ROUND_PILL)
    }

    private fun ring(g: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, w: Float, color: Int) {
        // A ring from 24 stroke segments (no stroked SDF pipeline).
        val n = 24
        for (i in 0 until n) {
            val a0 = (i * 2 * Math.PI / n).toFloat()
            val a1 = ((i + 1) * 2 * Math.PI / n).toFloat()
            val ro = r + w / 2
            val ri = r - w / 2
            poly(g, floatArrayOf(cx + ro * cos(a0), cy + ro * sin(a0), cx + ro * cos(a1), cy + ro * sin(a1), cx + ri * cos(a1), cy + ri * sin(a1), cx + ri * cos(a0), cy + ri * sin(a0)), color)
        }
    }

    /** Thick line segment. */
    private fun stroke(g: GuiGraphicsExtractor, x0: Float, y0: Float, x1: Float, y1: Float, w: Float, color: Int) {
        val len = hypot(x1 - x0, y1 - y0).coerceAtLeast(0.001f)
        val nx = -(y1 - y0) / len * w / 2
        val ny = (x1 - x0) / len * w / 2
        poly(g, floatArrayOf(x0 + nx, y0 + ny, x1 + nx, y1 + ny, x1 - nx, y1 - ny, x0 - nx, y0 - ny), color)
    }

    /** Two chain links on the diagonal, [s] = icon size. */
    private fun iconLink(g: GuiGraphicsExtractor, cx: Float, cy: Float, s: Float, color: Int) {
        val r = s * 0.24f
        val o = s * 0.2f
        val w = max(0.9f, s * 0.13f)
        ring(g, cx - o, cy + o, r, w, color)
        ring(g, cx + o, cy - o, r, w, color)
        stroke(g, cx - o * 0.9f, cy + o * 0.9f, cx + o * 0.9f, cy - o * 0.9f, w, color)
    }

    private fun iconPin(g: GuiGraphicsExtractor, cx: Float, cy: Float, s: Float, upright: Boolean, color: Int) {
        val a = if (upright) 0f else (Math.PI / 4).toFloat()
        val u = s / 10f
        // Head, collar and needle of a pushpin, drawn upright then rotated.
        poly(g, rotated(cx, cy, a, -2f * u, -5f * u, 2f * u, -5f * u, 2f * u, -3.5f * u, -2f * u, -3.5f * u), color)
        poly(g, rotated(cx, cy, a, -1.4f * u, -3.5f * u, 1.4f * u, -3.5f * u, 1.6f * u, 0f, -1.6f * u, 0f), color)
        poly(g, rotated(cx, cy, a, -3.2f * u, 0f, 3.2f * u, 0f, 3.2f * u, 1.2f * u, -3.2f * u, 1.2f * u), color)
        poly(g, rotated(cx, cy, a, -0.45f * u, 1.2f * u, 0.45f * u, 1.2f * u, 0.2f * u, 5f * u, -0.2f * u, 5f * u), color)
    }

    private fun iconCog(g: GuiGraphicsExtractor, cx: Float, cy: Float, s: Float, color: Int) {
        val u = s / 10f
        for (i in 0 until 8) {
            val a = (i * Math.PI / 4).toFloat()
            poly(g, rotated(cx, cy, a, 3f * u, -1.1f * u, 5f * u, -1.1f * u, 5f * u, 1.1f * u, 3f * u, 1.1f * u), color)
        }
        ring(g, cx, cy, 2.9f * u, 1.9f * u, color)
    }

    private fun iconCross(g: GuiGraphicsExtractor, cx: Float, cy: Float, s: Float, color: Int) {
        val u = s / 16f
        val w = max(0.8f, 1.6f * u)
        ring(g, cx, cy, 4.6f * u, w, color)
        ff(g, cx - w / 2, cy - 7.2f * u, cx + w / 2, cy - 2.8f * u, color)
        ff(g, cx - w / 2, cy + 2.8f * u, cx + w / 2, cy + 7.2f * u, color)
        ff(g, cx - 7.2f * u, cy - w / 2, cx - 2.8f * u, cy + w / 2, color)
        ff(g, cx + 2.8f * u, cy - w / 2, cx + 7.2f * u, cy + w / 2, color)
    }

    private fun iconGrid(g: GuiGraphicsExtractor, x: Float, y: Float, s: Float, color: Int) {
        val q = s * 0.42f
        for (i in 0..1) for (j in 0..1) {
            val x0 = x + i * (s - q)
            val y0 = y + j * (s - q)
            ff(g, x0, y0, x0 + q, y0 + q, color)
        }
    }

    private fun iconKeys(g: GuiGraphicsExtractor, x: Float, y: Float, s: Float, color: Int) {
        val u = s / 16f
        val x0 = x + 1.2f * u
        val y0 = y + 4f * u
        val x1 = x + 14.8f * u
        val y1 = y + 12.4f * u
        val w = 1.1f * u
        ff(g, x0, y0, x1, y0 + w, color)
        ff(g, x0, y1 - w, x1, y1, color)
        ff(g, x0, y0, x0 + w, y1, color)
        ff(g, x1 - w, y0, x1, y1, color)
        for (i in 0..3) {
            val dx = x + (4f + i * 2.67f) * u
            ff(g, dx - 0.6f * u, y + 6.1f * u, dx + 0.6f * u, y + 7.3f * u, color)
        }
        ff(g, x + 5.2f * u, y + 9f * u, x + 10.8f * u, y + 10.2f * u, color)
    }

    private fun switch(g: GuiGraphicsExtractor, x: Int, y: Int, on: Boolean) {
        rr(g, x, y, x + 22, y + 12, if (on) Theme.ACCENT else Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
        val kx = if (on) x + 12 else x + 2
        rr(g, kx, y + 2, kx + 8, y + 10, Theme.TEXT, SoulRenderPipelines.ROUND_PILL)
    }

    // ---------------------------------------------------------------- render

    /** A light dim instead of the menu blur, so the game and its HUD stay readable underneath. */
    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        g.fill(0, 0, width, height, DIM)
        minecraft?.gui?.hud?.extractDeferredSubtitles()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        rightClick.clear()
        val s = SoulUi.factor()
        val fx = (mouseX / s)
        val fy = (mouseY / s)
        val mx = fx.roundToInt()
        val my = fy.roundToInt()
        lastMouseX = mx
        lastMouseY = my
        val now = System.currentTimeMillis()
        val dt = (now - lastFrameAt).coerceIn(0L, 100L).toFloat()
        lastFrameAt = now
        relayout()
        shownFields.clear()

        val cats = categories()
        if (cats.isNotEmpty() && cats.none { it.id == activeCat }) activeCat = cats.first().id
        lastCat = activeCat
        sel?.let { s0 -> if (hud(s0)?.let(::inView) != true) sel = null }
        if (sel == null) inspOpen = false
        if (pick != null && (pick != sel || !inspOpen)) pick = null
        val ids = visibleIds()
        val uiW = screenW.toInt()
        val uiH = screenH.toInt()

        if (backdropShown()) {
            val inv = inventoryGui()
            g.pose().pushMatrix()
            g.pose().translate(inv[0].toFloat(), inv[1].toFloat())
            InventoryPreview.drawFrame(g)
            g.pose().popMatrix()
            InventoryPreview.drawPlayer(g, inv[0], inv[1], mouseX, mouseY)
        }

        g.pose().pushMatrix()
        g.pose().scale(s, s)

        // HUDs; the selection draws last so one picked from under another is visible.
        for (id in ids) if (id != sel) box(id)?.let { b -> hud(id)?.renderAt(g, b, preview = true) }
        sel?.takeIf { it in ids }?.let { id -> box(id)?.let { b -> hud(id)?.renderAt(g, b, preview = true) } }
        // Picking: the other categories' HUDs are pickable but faded.
        if (pick != null && !showAll) {
            for (id in ids) if (hud(id)?.categoryId != activeCat) box(id)?.let { b -> fl(g, floor(b.x0).toInt(), floor(b.y0).toInt(), b.x1.roundToInt(), b.y1.roundToInt(), 0x90000000.toInt()) }
        }

        val barVisible = updateBar(dt, mx, my, cats)
        val free = !alertModalOpen && popup == null && drag == null && press == null && sliderId == null && !onPanel(mx, my)
        val hoveredHud = if (free && pick == null) hudsAt(fx, fy).firstOrNull() else null
        drawOverlay(g, ids, hoveredHud, fx, fy, free, mx, my)

        if (ids.isEmpty()) {
            val text = "No HUDs enabled in this category. Turn one on in /soul."
            tx(g, text, (uiW - textWidth(text, f11)) / 2, uiH / 2 - 6, uiH / 2 + 6, Theme.TEXT, f11)
        }

        drawKeys(g, dt, mx, my, uiW, uiH)
        drawInspector(g, mx, my, uiW, uiH)
        if (barVisible || barSlide < 1f) drawBar(g, mx, my, cats, uiW) else barRect = null
        drawHandle(g, mx, my, uiW)
        drawCog(g, mx, my, uiW, uiH)
        drawPickBar(g, mx, my, uiW)
        drawPopup(g, mx, my, uiW, uiH)
        drawAlertModal(g, uiW, uiH, mx, my)
        g.pose().popMatrix()
    }

    /** True when the point is on a visible panel (clicks and hover belong to the panel then). */
    private fun onPanel(x: Int, y: Int): Boolean {
        fun within(r: IntArray?) = r != null && x in r[0]..r[2] && y in r[1]..r[3]
        return within(barRect) || within(handleRect) || (pick == null && within(inspRect)) || within(cogRect) || within(keysRect) || within(pickBarRect)
    }

    // ---------------------------------------------------------------- overlay

    private fun drawOverlay(g: GuiGraphicsExtractor, ids: List<String>, hoveredHud: String?, fx: Float, fy: Float, free: Boolean, mx: Int, my: Int) {
        val d = drag
        val s = sel?.takeIf { it in ids }
        val now = System.currentTimeMillis()

        // Selected + attached: what it is attached to.
        if (s != null && d == null) {
            val b = box(s)
            val target = b?.info?.target
            val link = hud(s)?.readTransform()?.link
            if (b != null && target != null && link != null) {
                dashed(g, target, 0x8C60A5FA.toInt())
                attachCue(g, b, target, LINK)
                val tid = HudTargets.hudId(link.target)
                if (tid != null && tid !in ids) {
                    tx(g, "${name(tid)} (${catTitle(hud(tid)?.categoryId ?: "")}, not shown)", target[0].toInt() + 2, target[1].toInt() + 2, target[1].toInt() + 12, LINK_LIGHT)
                }
            }
        }
        // Just attached: the shared edge glows for a moment.
        val gid = glowId
        if (gid != null && now < glowUntil) {
            val b = box(gid)
            val target = b?.info?.target
            if (b != null && target != null) {
                val a = (glowUntil - now) / GLOW_MS.toFloat()
                val e = attachEdge(b, target)
                if (e != null) {
                    ff(g, e[0] - 2f, e[1] - 2f, e[2] + 2f, e[3] + 2f, ((0x70 * a).toInt() shl 24) or (HOVER and 0xFFFFFF))
                    ff(g, e[0], e[1], e[2], e[3], ((0xFF * a).toInt() shl 24) or (HOVER and 0xFFFFFF))
                }
            }
        }

        // Picking: every vanilla element is a candidate too.
        var pickHover: PickCandidate? = null
        if (pick != null) {
            for (v in layout.vanilla.values) if (!v.hidden) dashed(g, v.rect, 0x6693C5FD)
            if (free) pickHover = pickCandidateAt(fx, fy)
        }

        for (id in ids) {
            if (id == s) continue
            val b = box(id) ?: continue
            val hov = id == hoveredHud
            hudOutline(g, b, if (hov) HOVER else IDLE_OUTLINE)
            if (hov) label(g, b, hudLabel(id), HOVER)
        }
        pickHover?.let { p ->
            val r = p.rect
            val x0 = floor(r[0]).toInt() - 1
            val y0 = floor(r[1]).toInt() - 1
            val x1 = r[2].roundToInt() + 1
            val y1 = r[3].roundToInt() + 1
            if (!p.disabled) fl(g, x0, y0, x1, y1, 0x1A3B82F6)
            outline(g, x0, y0, x1, y1, if (p.disabled) 0xFF6B7280.toInt() else LINK)
            label(g, HudBox(r[0], r[1], r[2] - r[0], r[3] - r[1]), p.name + if (p.disabled) " · follows this HUD, can not link" else "", if (p.disabled) 0xFF9CA3AF.toInt() else LINK_LIGHT)
        }
        if (s != null) {
            val b = box(s)
            if (b != null) {
                hudOutline(g, b, Theme.ACCENT)
                label(g, b, hudLabel(s), Theme.ACCENT)
                val note = offscreenNote(b)
                if (note.isNotEmpty()) {
                    val y = min(screenH.toInt() - 12, max(2, b.y1.roundToInt() + 3))
                    tx(g, note, max(2, floor(b.x0).toInt()), y, y + 9, WARN, f8h)
                }
            }
        }

        // 🔗 on every attached HUD; pressing it detaches.
        if (d == null && pick == null) {
            for (id in ids) {
                val b = box(id) ?: continue
                if (hud(id)?.readTransform()?.link == null || !b.info.linked) continue
                val bx = (b.x1.roundToInt() - 5).coerceIn(1, max(1, screenW.toInt() - 12))
                val by = (floor(b.y0).toInt() - 5).coerceIn(1, max(1, screenH.toInt() - 12))
                val over = mx in bx..bx + 11 && my in by..by + 11
                val prev = alpha
                alpha = if (over || id == s || id == hoveredHud) 1f else 0.55f
                rr(g, bx - 1, by - 1, bx + 12, by + 12, if (over) 0x99FECACA.toInt() else 0x8093C5FD.toInt(), SoulRenderPipelines.ROUND_PILL)
                rr(g, bx, by, bx + 11, by + 11, if (over) DANGER else BADGE, SoulRenderPipelines.ROUND_PILL)
                iconLink(g, bx + 5.5f, by + 5.5f, 7f, if (over) DANGER_TEXT else LINK_LIGHT)
                alpha = prev
                hit(bx, by, bx + 11, by + 11) {
                    sel = id
                    askDetach(id)
                }
            }
        }
        // "…": on the hovered and the selected HUD, toggles its settings panel.
        if (!settings().openOnSelect && d == null && pick == null) {
            for (id in listOfNotNull(s, hoveredHud).distinct()) {
                val b = box(id) ?: continue
                val badge = hud(id)?.readTransform()?.link != null && b.info.linked
                val w = 13
                val h = 8
                val right = b.x1.roundToInt() - if (badge) 8 else 2
                val top = if (b.h >= h + 6) floor(b.y0).toInt() + 2 else floor(b.y0).toInt() - h - 2
                val x = (right - w).coerceIn(1, max(1, screenW.toInt() - w - 1))
                val y = top.coerceIn(1, max(1, screenH.toInt() - h - 1))
                val open = inspOpen && id == s
                val over = mx in x..x + w && my in y..y + h
                if (open) {
                    rr(g, x, y, x + w, y + h, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
                } else {
                    // The ring lies outside the box, so the box keeps the open state's radius; the fill shows as it did on the ring.
                    val edge = if (over) 0x73FFFFFF else 0x38FFFFFF
                    val fill = if (over) 0xFF2A2A2A.toInt() else SoulPanel.over(0xD1141414.toInt(), edge)
                    panel(g, x - 1, y - 1, x + w + 1, y + h + 1, fill, edge, SoulPanel.outer(SoulPanel.SMALL, 1f))
                }
                val dot = if (open || over) WHITE else 0xFFCFCFCF.toInt()
                for (i in -1..1) ff(g, x + w / 2f + i * 3.2f - 0.8f, y + h / 2f - 0.8f, x + w / 2f + i * 3.2f + 0.8f, y + h / 2f + 0.8f, dot)
                hit(x, y, x + w, y + h, right = true) { toggleInsp(id) }
            }
        }

        // Drag: snap = pink guides; attach = blue edge + chip.
        if (d != null) {
            for (gx in d.guidesX) ff(g, gx - 0.5f, 0f, gx + 0.5f, screenH, GUIDE)
            for (gy in d.guidesY) ff(g, 0f, gy - 0.5f, screenW, gy + 0.5f, GUIDE)
            val b = d.box
            val lk = d.link
            val slide = d.slide
            if (lk != null || (d.keep && slide != null)) {
                val target = lk?.rect ?: slide!!.target
                val tName = if (lk != null) targetName(lk.to) else slide!!.name
                val glue = if (lk != null) lk.glue else d.glue
                dashed(g, target, 0x8C60A5FA.toInt())
                attachCue(g, b, target, LINK)
                val text = (if (d.keep) "Attached to " else "Attach to ") + tName + if (glue == null) "" else if (glue == 1) " · centered" else " · corner"
                chip(g, b, text, Theme.ACCENT_DIM, WHITE)
            } else if (hud(d.id)?.readTransform()?.link != null) {
                chip(g, b, "Detach", 0xFF3A3A3A.toInt(), 0xFFBBBBBB.toInt())
            }
        }
    }

    private fun hudOutline(g: GuiGraphicsExtractor, b: HudBox, color: Int) =
        outline(g, floor(b.x0).toInt() - 1, floor(b.y0).toInt() - 1, b.x1.roundToInt() + 1, b.y1.roundToInt() + 1, color)

    private fun label(g: GuiGraphicsExtractor, b: HudBox, text: String, color: Int) {
        val x0 = floor(b.x0).toInt() - 1
        val y0 = floor(b.y0).toInt() - 1
        val y1 = b.y1.roundToInt() + 1
        val y = if (y0 - 10 >= 0) y0 - 10 else y1 + 2
        tx(g, text, x0, y, y + 9, color)
    }

    private fun chip(g: GuiGraphicsExtractor, b: HudBox, text: String, bg: Int, fg: Int) {
        val w = textWidth(text, f9h) + 10 + 11
        val y = if (b.y1 + 16 < screenH) b.y1.roundToInt() + 4 else max(2, b.y0.roundToInt() - 16)
        val x = b.x0.roundToInt().coerceIn(2, max(2, screenW.toInt() - w - 2))
        rr(g, x, y, x + w, y + 13, bg, SoulRenderPipelines.ROUND_SMALL)
        iconLink(g, x + 8.5f, y + 6.5f, 8f, fg)
        tx(g, text, x + 15, y, y + 13, fg, f9h)
    }

    /** The strip of the target's edge an attached HUD sits against (null when they don't face each other). */
    private fun attachEdge(b: HudBox, t: FloatArray): FloatArray? {
        val gx = if (b.x1 <= t[0] + 0.5f) t[0] else if (b.x0 >= t[2] - 0.5f) t[2] else null
        val gy = if (b.y1 <= t[1] + 0.5f) t[1] else if (b.y0 >= t[3] - 0.5f) t[3] else null
        if (gy != null && gx == null) {
            val a = max(b.x0, t[0])
            val z = min(b.x1, t[2])
            return if (z > a) floatArrayOf(a, gy - 1f, z, gy + 1f) else null
        }
        if (gx != null && gy == null) {
            val a = max(b.y0, t[1])
            val z = min(b.y1, t[3])
            return if (z > a) floatArrayOf(gx - 1f, a, gx + 1f, z) else null
        }
        return null
    }

    /** The shared edge, or a short connector between the HUD and the rect it follows. */
    private fun attachCue(g: GuiGraphicsExtractor, b: HudBox, t: FloatArray, color: Int) {
        val e = attachEdge(b, t)
        if (e != null) {
            ff(g, e[0], e[1], e[2], e[3], color)
            return
        }
        val tcx = (t[0] + t[2]) / 2f
        val tcy = (t[1] + t[3]) / 2f
        val px = tcx.coerceIn(b.x0, b.x1)
        val py = tcy.coerceIn(b.y0, b.y1)
        val qx = px.coerceIn(t[0], t[2])
        val qy = py.coerceIn(t[1], t[3])
        if (hypot(qx - px, qy - py) >= 1f) stroke(g, px, py, qx, qy, 1.2f, color)
        ff(g, qx - 1.5f, qy - 1.5f, qx + 1.5f, qy + 1.5f, color)
    }

    private fun offscreenNote(b: HudBox): String {
        val i = b.info
        if (abs(i.clampX) > 0.5f || abs(i.clampY) > 0.5f) return "kept on screen (pushed ${i.pushed().roundToInt()} px)"
        i.hiddenTarget?.let { return "${targetName(it)} hidden: on its screen anchor" }
        if (i.cycle != null) return "link cycle: on its screen anchor"
        if (i.flipped) return "no room on its side: shown on the opposite side"
        return ""
    }

    // ---------------------------------------------------------------- pick mode

    private class PickCandidate(val to: String, val name: String, val rect: FloatArray, val disabled: Boolean)

    /** The HUD (any category) or vanilla element under the cursor, other than the picking HUD. */
    private fun pickCandidateAt(x: Float, y: Float): PickCandidate? {
        val me = pick ?: return null
        val near = { o: String -> if (showAll || hud(o)?.categoryId == activeCat) 0 else 1 }
        for (o in hudsAt(x, y).sortedBy(near)) {
            if (o == me) continue
            val b = box(o) ?: continue
            return PickCandidate(HudTargets.hud(o), name(o), b.rect(), edits.dependsOn(o, me))
        }
        var best: PickCandidate? = null
        var bestA = Float.MAX_VALUE
        for (v in layout.vanilla.values) {
            val r = v.rect
            if (v.hidden || x < r[0] || x > r[2] || y < r[1] || y > r[3]) continue
            val a = (r[2] - r[0]) * (r[3] - r[1])
            if (a < bestA) {
                bestA = a
                best = PickCandidate(HudTargets.vanilla(v.id), v.name, r, false)
            }
        }
        return best
    }

    private fun drawPickBar(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int) {
        val id = pick
        if (id == null) {
            pickBarRect = null
            return
        }
        val parts = listOf("Click what " to regular, name(id) to semi, " attaches to: any HUD or vanilla element" to regular)
        val textW = parts.sumOf { textWidth(it.first, f10, it.second) }
        val cancelW = textWidth("Cancel", f10) + 14
        val escW = textWidth("Esc", f10)
        val w = 9 + 11 + 6 + textW + 6 + 8 + escW + 6 + cancelW + 9
        val h = 21
        val top = (if (barRect != null && settings().barPinned) barPinned(uiW)[3] else 0) + PAD
        val x0 = (uiW - w) / 2
        panel(g, x0 - 1, top - 1, x0 + w + 1, top + h + 1, PANEL_SOLID, LINK, SoulPanel.SMALL)
        hit(x0, top, x0 + w, top + h) {}
        var x = x0 + 9
        iconCross(g, x + 5.5f, top + h / 2f, 11f, LINK_LIGHT)
        x += 11 + 6
        for ((s, wt) in parts) {
            tx(g, s, x, top, top + h, if (wt === semi) LINK_LIGHT else Theme.TEXT, f10, wt)
            x += textWidth(s, f10, wt)
        }
        x += 6
        fl(g, x, top + 5, x + 1, top + h - 5, Theme.SEPARATOR)
        x += 8
        tx(g, "Esc", x, top, top + h, Theme.TEXT_FAINT)
        x += escW + 6
        button(g, mx, my, x, top + 3, x + cancelW, "Cancel", h = h - 6) { pick = null }
        pickBarRect = intArrayOf(x0, top, x0 + w, top + h)
    }

    // ---------------------------------------------------------------- top bar

    private fun btnW(label: String): Int = textWidth(label, f10) + 14

    private fun tabW(cat: HudCategory): Int = textWidth(cat.title, f10) + 18

    private fun showAllW(): Int = 7 + 10 + 5 + textWidth("Show all", f10) + 8

    private fun resetLabel(): String = if (minecraft?.hasShiftDown() == true || showAll) "Reset all" else "Reset category"

    private fun group1W(cats: List<HudCategory>): Int {
        val seg = if (cats.isEmpty()) 0 else 4 + cats.sumOf { tabW(it) } + (cats.size - 1) + 4
        return seg + 4 + showAllW()
    }

    private fun group2W(): Int = btnW("Undo") + 4 + btnW("Redo") + 4 + btnW(resetLabel()) + 4 + btnW("Done") + 4 + 5 + 4 + BTN_H + 4 + BTN_H

    /** The bar's rect where it sits when shown, and whether it wraps to two rows. */
    private fun barPinned(uiW: Int, cats: List<HudCategory> = categories()): IntArray {
        val g1 = group1W(cats)
        val g2 = group2W()
        val one = 3 + g1 + 12 + g2 + 3
        return if (one <= uiW - 2 * PAD) {
            intArrayOf((uiW - one) / 2, PAD, (uiW + one) / 2, PAD + BTN_H + 6)
        } else {
            val w = min(uiW - 2 * PAD, max(g1, g2) + 6)
            intArrayOf((uiW - w) / 2, PAD, (uiW + w) / 2, PAD + 2 * BTN_H + 10)
        }
    }

    /**
     * Unpinned, the bar slides off the top 1.4 s after the cursor left it (unless its menu is open);
     * the handle brings it back. Pinned or not, it slides away while a dragged HUD runs under it.
     */
    private fun updateBar(dt: Float, mx: Int, my: Int, cats: List<HudCategory>): Boolean {
        val uiW = screenW.toInt()
        val r = barPinned(uiW, cats)
        val now = System.currentTimeMillis()
        val pinned = settings().barPinned
        if (!pinned) {
            val overBar = barRect?.let { mx in it[0]..it[2] && my in it[1]..it[3] + 2 } == true
            val overHandle = handleRect?.let { mx in it[0]..it[2] && my in it[1]..it[3] } == true
            if (overHandle) peek = true
            if (overBar || overHandle) barLeftAt = now
            if (peek && now - barLeftAt > BAR_HIDE_MS) peek = false
        }
        var away = !pinned && !peek && !cogOpen
        val d = drag
        if (d != null) {
            val b = d.box
            if (b.x0 < r[2] + 4 && b.x1 > r[0] - 4 && b.y0 < r[3] + 4 && b.y1 > r[1] - 4) away = true
        }
        val target = if (away) 1f else 0f
        val step = dt / SLIDE_MS
        barSlide = if (barSlide < target) min(target, barSlide + step) else max(target, barSlide - step)
        return !away
    }

    private fun drawBar(g: GuiGraphicsExtractor, mx: Int, my: Int, cats: List<HudCategory>, uiW: Int) {
        val pr = barPinned(uiW, cats)
        val h = pr[3] - pr[1]
        val ease = barSlide * barSlide * (3 - 2 * barSlide)
        val dy = -((h + 10 + PAD) * ease).roundToInt()
        val r = intArrayOf(pr[0], pr[1] + dy, pr[2], pr[3] + dy)
        if (r[3] <= 0) {
            barRect = null
            return
        }
        barRect = if (barSlide <= 0f) r else null
        val interactive = barSlide <= 0f
        panel(g, r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, Theme.PANEL_FLOATING, Theme.FIELD_BORDER)
        val prevSuppress = suppressRegions
        suppressRegions = !interactive
        hit(r[0], r[1], r[2], r[3]) {}

        val twoRows = h > BTN_H + 6
        var x = r[0] + 3
        val y = r[1] + 3
        // Category segmented control.
        if (cats.isNotEmpty()) {
            val segW = 4 + cats.sumOf { tabW(it) } + (cats.size - 1) + 4
            rr(g, x, y, x + segW, y + BTN_H, 0x57000000, SoulRenderPipelines.ROUND_MEDIUM)
            var tx0 = x + 2
            for (cat in cats) {
                val w = tabW(cat)
                val on = !showAll && cat.id == activeCat
                val over = hovered(mx, my, tx0, y + 2, tx0 + w, y + 2 + 14)
                val bg = if (on) Theme.ACCENT else if (showAll) 0x293B82F6 else if (over) 0x12FFFFFF else 0
                if (bg != 0) rr(g, tx0, y + 2, tx0 + w, y + 16, bg, SoulRenderPipelines.ROUND_SMALL)
                val fg = if (on) WHITE else if (showAll) 0xFFC7D8FB.toInt() else if (over) Theme.TEXT else Theme.TEXT_DIM
                txCenter(g, cat.title, tx0, tx0 + w, y + 2, y + 16, fg)
                val id = cat.id
                hit(tx0, y + 2, tx0 + w, y + 16) {
                    showAll = false
                    if (activeCat != id) {
                        activeCat = id
                        sel = null
                        popup = null
                    }
                }
                tx0 += w + 1
            }
            x += segW + 4
        }
        // Show all toggle.
        val saW = showAllW()
        val saOver = hovered(mx, my, x, y, x + saW, y + BTN_H)
        if (showAll) {
            panel(g, x, y, x + saW, y + BTN_H, 0xFF1F2B40.toInt(), 0x993B82F6.toInt(), SoulPanel.SMALL)
        } else {
            rr(g, x, y, x + saW, y + BTN_H, if (saOver) Theme.CONTROL_HOVER else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        }
        val saFg = if (showAll) 0xFFDBE7FF.toInt() else if (saOver) Theme.TEXT else Theme.TEXT_DIM
        iconGrid(g, x + 7f, y + 4f, 10f, saFg)
        tx(g, "Show all", x + 7 + 10 + 5, y, y + BTN_H, saFg)
        hit(x, y, x + saW, y + BTN_H) {
            showAll = !showAll
            popup = null
            if (!showAll) sel?.let { s -> hud(s)?.let { activeCat = it.categoryId } }
        }

        // Actions: right-aligned, own row when wrapping.
        val ay = if (twoRows) y + BTN_H + 4 else y
        var right = r[2] - 3
        val cogOn = cogOpen
        val cogOver = hovered(mx, my, right - BTN_H, ay, right, ay + BTN_H)
        rr(g, right - BTN_H, ay, right, ay + BTN_H, if (cogOn) Theme.CONTROL_HOVER else if (cogOver) Theme.CONTROL_HOVER else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        iconCog(g, right - BTN_H / 2f, ay + BTN_H / 2f, 10f, if (cogOn || cogOver) WHITE else Theme.TEXT)
        hit(right - BTN_H, ay, right, ay + BTN_H) { cogOpen = !cogOpen }
        right -= BTN_H + 4
        val pinned = settings().barPinned
        val pinOver = hovered(mx, my, right - BTN_H, ay, right, ay + BTN_H)
        rr(g, right - BTN_H, ay, right, ay + BTN_H, if (pinned) 0x2E3B82F6 else if (pinOver) Theme.CONTROL_HOVER else Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        iconPin(g, right - BTN_H / 2f, ay + BTN_H / 2f, 10f, pinned, if (pinned) LINK_LIGHT else if (pinOver) Theme.TEXT else Theme.TEXT_DIM)
        hit(right - BTN_H, ay, right, ay + BTN_H) {
            updateSettings { copy(barPinned = !barPinned) }
            // Unpinned with the cursor on it: it slides away once the cursor leaves.
            peek = settings().barPinned.not()
            barLeftAt = System.currentTimeMillis()
        }
        right -= BTN_H + 4
        fl(g, right - 3, ay + 3, right - 2, ay + BTN_H - 3, 0xFF3A3A3A.toInt())
        right -= 5 + 4
        val doneW = btnW("Done")
        button(g, mx, my, right - doneW, ay, right, "Done", accent = true, h = BTN_H) { onClose() }
        right -= doneW + 4
        val reset = resetLabel()
        val resetW = btnW(reset)
        val canReset = categories().isNotEmpty() || reset == "Reset all"
        button(g, mx, my, right - resetW, ay, right, reset, enabled = canReset, h = BTN_H) { confirmReset(reset == "Reset all") }
        right -= resetW + 4
        val redoW = btnW("Redo")
        button(g, mx, my, right - redoW, ay, right, "Redo", enabled = redoStack.isNotEmpty(), h = BTN_H) { redo() }
        right -= redoW + 4
        val undoW = btnW("Undo")
        button(g, mx, my, right - undoW, ay, right, "Undo", enabled = undoStack.isNotEmpty(), h = BTN_H) { undo() }
        suppressRegions = prevSuppress
    }

    /** The small top-center tab that brings an unpinned bar back. */
    private fun drawHandle(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int) {
        val shown = !settings().barPinned && !peek && !cogOpen
        if (!shown || barSlide < 1f) {
            handleRect = null
            if (!shown) return
        }
        val x0 = uiW / 2 - 20
        val r = intArrayOf(x0, 0, x0 + 40, 10)
        val over = mx in r[0]..r[2] && my in r[1]..r[3]
        panel(g, r[0] - 1, -7, r[2] + 1, r[3] + 1, Theme.PANEL_FLOATING, Theme.FIELD_BORDER)
        chevron(g, uiW / 2f, 5f, 2.5f, false, if (over) Theme.TEXT else Theme.TEXT_DIM)
        if (barSlide >= 1f) {
            handleRect = r
            hit(r[0], r[1], r[2], r[3]) {
                peek = true
                barLeftAt = System.currentTimeMillis()
            }
        }
    }

    /** A compact button; disabled ones draw faint and do nothing. */
    private fun button(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        label: String,
        enabled: Boolean = true,
        accent: Boolean = false,
        h: Int = 0,
        y1: Int = 0,
        size: Float = f10,
        onClick: () -> Unit,
    ) {
        val bottom = if (h > 0) y0 + h else y1
        val over = enabled && hovered(mx, my, x0, y0, x1, bottom)
        val bg = if (accent) (if (over) Theme.ACCENT else Theme.ACCENT_DIM) else if (over) Theme.CONTROL_HOVER else Theme.PANEL_HOVER
        rr(g, x0, y0, x1, bottom, bg, SoulRenderPipelines.ROUND_SMALL)
        txCenter(g, label, x0, x1, y0, bottom, if (enabled) Theme.TEXT else Theme.TEXT_FAINT, size)
        hit(x0, y0, x1, bottom) { if (enabled) onClick() }
    }

    private fun confirmReset(all: Boolean) {
        val catTitle = catTitle(activeCat)
        if (all) {
            showConfirm(
                "Reset all HUDs",
                "Every HUD in every category goes back to its default position, size, anchor and font. Ctrl+Z undoes it.",
                "Reset all",
            ) { edit { resetHuds(MovableHudRegistry.all().map { it.id }) } }
        } else {
            showConfirm(
                "Reset $catTitle",
                "Every $catTitle HUD goes back to its default position, size, anchor and font. Hold Shift to reset all categories instead. Ctrl+Z undoes it.",
                "Reset",
            ) { edit { resetHuds(MovableHudRegistry.all().filter { it.categoryId == activeCat }.map { it.id }) } }
        }
    }

    // ---------------------------------------------------------------- cog menu (editor settings)

    private class NumField(val key: String) {
        val text = SoulTextField()
        var rect = intArrayOf(0, 0, 0, 0)
    }

    private val edgeSnapField = NumField("edgeSnap")
    private val linkGapField = NumField("linkGap")
    private val fieldX = NumField("x")
    private val fieldY = NumField("y")
    private val fieldScale = NumField("scale")
    private val fieldGap = NumField("gap")
    private val fieldShift = NumField("shift")
    private val fieldLx = NumField("lx")
    private val fieldLy = NumField("ly")
    private val inspectorFields = listOf(fieldX, fieldY, fieldScale, fieldGap, fieldShift, fieldLx, fieldLy)
    private val allFields = inspectorFields + listOf(edgeSnapField, linkGapField)

    /** Fields drawn this frame, in order, for Tab. */
    private val shownFields = ArrayList<NumField>()

    private fun focused(): NumField? = allFields.firstOrNull { it.text.focused }

    private fun drawCog(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int, uiH: Int) {
        if (!cogOpen) {
            cogRect = null
            // Typed but not yet confirmed: a closing cog keeps it (committing also unfocuses).
            for (f in listOf(edgeSnapField, linkGapField)) if (f.text.focused) commit(f)
            return
        }
        val w = 200
        val br = barPinned(uiW)
        val x0 = (br[2] - w).coerceIn(PAD, max(PAD, uiW - PAD - w))
        val y0 = br[3] + 4
        val s = settings()
        val ix0 = x0 + 9
        val ix1 = x0 + w - 9
        // Measure, then draw.
        var h = 0
        for (pass in 0..1) {
            measuring = pass == 0
            if (pass == 1) {
                panel(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, PANEL_SOLID, Theme.FIELD_BORDER)
                hit(x0, y0, x0 + w, y0 + h) {}
            }
            var y = y0 + 7
            tx(g, "Editor settings", ix0, y, y + 13, Theme.TEXT, f11, semi)
            y += 13 + 5
            y = cogStepperRow(g, mx, my, ix0, ix1, y, "Edge snap", "Snap line this far inside each screen edge", edgeSnapField, s.edgeSnap)
            y = cogStepperRow(g, mx, my, ix0, ix1, y, "Link gap", "Space a new link leaves to its target", linkGapField, s.linkGap)
            y = cogToggleRow(g, mx, my, ix0, ix1, y, "Confirm before detaching", "Ask when the link icon on a HUD is clicked", s.confirmDetach) {
                updateSettings { copy(confirmDetach = !confirmDetach) }
            }
            y = cogToggleRow(g, mx, my, ix0, ix1, y, "Open settings when selecting", "Right-click a HUD to open its settings.", s.openOnSelect) {
                updateSettings { copy(openOnSelect = !openOnSelect) }
            }
            fl(g, x0 + 9, y, x0 + w - 9, y + 1, Theme.SEPARATOR)
            y += 6
            tx(g, "Saved for every session", ix0, y, y + 16, Theme.TEXT_FAINT, f9)
            val dw = btnW("Defaults")
            button(g, mx, my, ix1 - dw, y, ix1, "Defaults", h = 16) {
                val d = MoveGuiConfig()
                updateSettings { copy(edgeSnap = d.edgeSnap, linkGap = d.linkGap, confirmDetach = d.confirmDetach, openOnSelect = d.openOnSelect) }
                edgeSnapField.text.focused = false
                linkGapField.text.focused = false
            }
            y += 16 + 7
            h = y - y0
        }
        measuring = false
        cogRect = intArrayOf(x0, y0, x0 + w, y0 + h)
    }

    private fun cogLabel(g: GuiGraphicsExtractor, x0: Int, x1: Int, y: Int, title: String, desc: String, color: Int): Int {
        tx(g, title, x0, y, y + 12, color)
        var yy = y + 12 + 1
        for (line in wrapText(desc, x1 - x0, f9)) {
            tx(g, line, x0, yy, yy + 10, Theme.TEXT_DIM, f9)
            yy += 10
        }
        return yy
    }

    private fun cogStepperRow(g: GuiGraphicsExtractor, mx: Int, my: Int, ix0: Int, ix1: Int, y0: Int, title: String, desc: String, field: NumField, value: Float): Int {
        fl(g, ix0, y0, ix1, y0 + 1, Theme.SEPARATOR)
        val ctlW = 14 + 2 + 28 + 2 + 14 + 2 + textWidth("px", f10)
        val y = y0 + 5
        val end = cogLabel(g, ix0, ix1 - ctlW - 8, y, title, desc, Theme.TEXT)
        val rowH = end - y
        val cy = y + rowH / 2
        var x = ix1 - ctlW
        val limits = if (field === edgeSnapField) 0f..20f else 0f..40f
        val set = { v: Float ->
            val clamped = ((v * 2f).roundToInt() / 2f).coerceIn(limits.start, limits.endInclusive)
            if (field === edgeSnapField) updateSettings { copy(edgeSnap = clamped) } else updateSettings { copy(linkGap = clamped) }
        }
        button(g, mx, my, x, cy - 7, x + 14, "-", h = 14, size = f11) { set(value - 1f) }
        x += 16
        syncField(field, fmt(value))
        numberField(g, mx, my, x, cy - 7, x + 28, cy + 7, field, arrows = false)
        x += 30
        button(g, mx, my, x, cy - 7, x + 14, "+", h = 14, size = f11) { set(value + 1f) }
        x += 16
        tx(g, "px", x, cy - 6, cy + 6, Theme.TEXT_DIM)
        return end + 5
    }

    private fun cogToggleRow(g: GuiGraphicsExtractor, mx: Int, my: Int, ix0: Int, ix1: Int, y0: Int, title: String, desc: String, on: Boolean, toggle: () -> Unit): Int {
        fl(g, ix0, y0, ix1, y0 + 1, Theme.SEPARATOR)
        val y = y0 + 5
        val over = hovered(mx, my, ix0, y0, ix1, y0 + 30)
        val end = cogLabel(g, ix0, ix1 - 30, y, title, desc, if (over) WHITE else Theme.TEXT)
        val cy = (y + end) / 2
        switch(g, ix1 - 22, cy - 6, on)
        hit(ix0, y0 + 1, ix1, end + 5) { toggle() }
        return end + 5
    }

    // ---------------------------------------------------------------- number fields

    private fun fmt(v: Float): String {
        val r = (v * 100f).roundToInt() / 100f
        return if (r == r.toInt().toFloat()) r.toInt().toString() else String.format(Locale.ROOT, "%.2f", r).trimEnd('0').trimEnd('.')
    }

    private fun formatScale(v: Float): String {
        val s = String.format(Locale.ROOT, "%.2f", v).trimEnd('0')
        return if (s.endsWith('.')) s + "0" else s
    }

    /** Mirror the live value into an idle field (a focused one keeps what is being typed). */
    private fun syncField(field: NumField, value: String) {
        if (!field.text.focused && field.text.text != value) field.text.set(value)
    }

    /**
     * A number field: border, value, and (with [arrows]) small up/down arrows on its right edge. The
     * wheel over it and the arrows step it (1, Shift 10; scale 0.1, Shift 0.05) and commit at once.
     */
    private fun numberField(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, field: NumField, arrows: Boolean = true, center: Boolean = true) {
        if (measuring) return
        field.rect = intArrayOf(x0, y0, x1, y1)
        shownFields += field
        val t = field.text
        val over = hovered(mx, my, x0, y0, x1, y1)
        panel(g, x0, y0, x1, y1, Theme.FIELD_BG, if (t.focused) Theme.ACCENT else Theme.FIELD_BORDER, SoulPanel.SMALL)
        val arrowW = if (arrows) 11 else 0
        val ax0 = x1 - 1 - arrowW
        val inner0 = x0 + 4
        val inner1 = if (arrows) ax0 - 1 else x1 - 4
        val text = t.text
        val shown = ellipsizeTail(text, inner1 - inner0)
        val tw = textWidth(shown, f10)
        val tx0 = if (center && !t.focused) (inner0 + inner1 - tw) / 2 else inner0
        if (t.focused && t.cursor != t.anchor) {
            val a = min(t.cursor, t.anchor).coerceAtMost(shown.length)
            val b = max(t.cursor, t.anchor).coerceAtMost(shown.length)
            fl(g, tx0 + textWidth(shown.substring(0, a), f10), y0 + 2, tx0 + textWidth(shown.substring(0, b), f10), y1 - 2, Theme.ACCENT_DIM)
        }
        tx(g, shown, tx0, y0, y1, Theme.TEXT)
        if (t.focused && (System.currentTimeMillis() / 500) % 2 == 0L) {
            val cx = tx0 + textWidth(shown.substring(0, t.cursor.coerceAtMost(shown.length)), f10)
            fl(g, cx, y0 + 3, cx + 1, y1 - 3, Theme.TEXT)
        }
        hit(x0, y0, if (arrows) ax0 else x1, y1) {
            focusOnly(field)
            t.focused = true
            t.cursor = t.text.length
            t.anchor = 0
        }
        if (arrows) {
            val mid = (y0 + y1) / 2f
            val upOver = hovered(mx, my, ax0, y0 + 1, x1 - 1, mid.toInt())
            val dnOver = hovered(mx, my, ax0, mid.toInt(), x1 - 1, y1 - 1)
            val base = if (over) Theme.TEXT_DIM else 0x8C888888.toInt()
            if (upOver) rr(g, ax0, y0 + 1, x1 - 1, mid.roundToInt(), Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
            if (dnOver) rr(g, ax0, mid.roundToInt(), x1 - 1, y1 - 1, Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
            val acx = (ax0 + x1 - 1) / 2f
            triangle(g, acx, (y0 + 1 + mid) / 2f + 0.25f, 2f, 0, if (upOver) Theme.TEXT else base)
            triangle(g, acx, (mid + y1 - 1) / 2f - 0.25f, 2f, 2, if (dnOver) Theme.TEXT else base)
            hit(ax0, y0, x1, mid.toInt()) { bump(field, 1) }
            hit(ax0, mid.toInt() + 1, x1, y1) { bump(field, -1) }
        }
    }

    private fun ellipsizeTail(text: String, maxW: Int): String {
        var s = text
        while (s.isNotEmpty() && textWidth(s, f10) > maxW) s = s.substring(1)
        return s
    }

    private fun focusOnly(field: NumField) {
        for (f in allFields) if (f !== field && f.text.focused) commit(f)
    }

    private fun stepOf(field: NumField, shift: Boolean): Float = if (field === fieldScale) (if (shift) 0.05f else 0.1f) else if (shift) 10f else 1f

    /** Arrow / wheel step on a field: applied and committed at once. */
    private fun bump(field: NumField, dir: Int) {
        val v = field.text.text.trim().replace(',', '.').toFloatOrNull() ?: return
        val step = stepOf(field, minecraft?.hasShiftDown() == true)
        val next = Math.round((v + dir * step) / step) * step
        field.text.set(if (field === fieldScale) formatScale(next) else fmt(next))
        commit(field, key = "bump:${field.key}:${sel}")
    }

    /** Apply a field's text; bad input just reverts on the next frame. */
    private fun commit(field: NumField, key: String? = null) {
        field.text.focused = false
        val v = field.text.text.trim().replace(',', '.').toFloatOrNull() ?: return
        if (field === edgeSnapField) {
            updateSettings { copy(edgeSnap = ((v * 2f).roundToInt() / 2f).coerceIn(0f, 20f)) }
            return
        }
        if (field === linkGapField) {
            updateSettings { copy(linkGap = ((v * 2f).roundToInt() / 2f).coerceIn(0f, 40f)) }
            return
        }
        val id = sel ?: return
        val b = box(id) ?: return
        edit(key) {
            when (field.key) {
                "scale" -> store.set(id, hud(id)!!.readTransform().copy(scale = (Math.round(v * 100f) / 100f).coerceIn(SCALE_MIN, SCALE_MAX)))
                "x" -> edits.moveBy(id, v.roundToInt() - b.x0, 0f, true)
                "y" -> edits.moveBy(id, 0f, v.roundToInt() - b.y0, true)
                "gap" -> edits.setLinkField(id, HudEdits.GAP, v)
                "shift" -> edits.setLinkField(id, HudEdits.SHIFT, v)
                "lx" -> edits.setLinkField(id, HudEdits.LINK_X, v)
                "ly" -> edits.setLinkField(id, HudEdits.LINK_Y, v)
            }
        }
    }

    // ---------------------------------------------------------------- inspector

    /** Right-click or "…": open the settings panel on [id], or close it if it is open there. */
    private fun toggleInsp(id: String) {
        if (id != sel) inspRight = true
        inspOpen = !(inspOpen && id == sel)
        if (id != sel) commitInspectorFields()
        sel = id
    }

    /** Select [id]; opens its settings panel only with "Open settings when selecting". */
    private fun select(id: String) {
        if (id != sel) commitInspectorFields()
        sel = id
        if (settings().openOnSelect) inspOpen = true
    }

    private fun commitInspectorFields() {
        for (f in inspectorFields) if (f.text.focused) commit(f)
    }

    private fun drawInspector(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int, uiH: Int) {
        val id = sel
        val b = id?.let(::box)
        val h = id?.let(::hud)
        if (id == null || b == null || h == null || !inspOpen) {
            inspRect = null
            inspectorFields.forEach { it.text.focused = false }
            return
        }
        val iw = min((uiW * 0.3f).coerceIn(160f, 204f).roundToInt(), uiW - 2 * PAD)
        // Natural height from a measure pass.
        measuring = true
        val natBody = inspectorBody(g, mx, my, id, h, b, 0, iw - 16, 0)
        measuring = false
        val nat = HEADER_H + 6 + natBody + 8
        val linkTarget = b.info.target.takeIf { b.info.linked }
        val r = placeInspector(b, iw, nat, uiW, uiH, linkTarget)
        inspRect = r
        val dragging = drag?.id == id
        val prevAlpha = alpha
        alpha = if (dragging) 0.32f else if (pick != null) 0.3f else 1f
        val prevSuppress = suppressRegions
        suppressRegions = dragging || pick != null || prevSuppress

        panel(g, r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, Theme.PANEL_FLOATING, Theme.FIELD_BORDER)
        hit(r[0], r[1], r[2], r[3]) {}

        // Header: name, the category in Show all, ×.
        val hx0 = r[0] + 8
        var hx1 = r[2] - 8
        if (!settings().openOnSelect) {
            val xOver = hovered(mx, my, hx1 - 12, r[1] + 6, hx1 + 2, r[1] + 20)
            txCenter(g, "×", hx1 - 12, hx1 + 2, r[1] + 5, r[1] + 21, if (xOver) Theme.TEXT else Theme.TEXT_FAINT, 14f * MsdfFont.LEGACY_EM)
            hit(hx1 - 12, r[1] + 5, hx1 + 2, r[1] + 21) { inspOpen = false }
            hx1 -= 18
        }
        if (showAll) {
            val cat = catTitle(h.categoryId)
            val cw = textWidth(cat, f9) + 8
            rr(g, hx1 - cw, r[1] + 7, hx1, r[1] + 19, Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
            txCenter(g, cat, hx1 - cw, hx1, r[1] + 7, r[1] + 19, Theme.TEXT_DIM, f9)
            hx1 -= cw + 6
        }
        tx(g, ellipsize(h.displayName, hx1 - hx0, f13, semi), hx0, r[1] + 5, r[1] + 21, Theme.TEXT, f13, semi)
        fl(g, r[0], r[1] + HEADER_H - 1, r[2], r[1] + HEADER_H, Theme.SEPARATOR)

        // Body: scrolls when the panel is capped by the screen height.
        val by0 = r[1] + HEADER_H
        val by1 = r[3] - 2
        inspBody = intArrayOf(r[0], by0, r[2], by1)
        inspContentH = nat - HEADER_H
        inspScroll = clampScroll(inspScroll, inspContentH, by1 - by0)
        beginClip(g, r[0], by0, r[2], by1)
        hitClip = inspBody
        inspectorBody(g, mx, my, id, h, b, r[0] + 8, r[2] - 8, by0 + 6 - inspScroll.roundToInt())
        hitClip = null
        endClip(g)
        alpha = prevAlpha
        suppressRegions = prevSuppress
    }

    /**
     * Right or left of the selection, whichever fits (sticky). A linked HUD's inspector keeps its
     * target [target] uncovered: if the usual side covers it, the other side, then below / above the
     * selection are tried; when none fits, the usual rule stands.
     */
    private fun placeInspector(b: HudBox, iw: Int, nat: Int, uiW: Int, uiH: Int, target: FloatArray?): IntArray {
        val maxX = max(PAD, uiW - PAD - iw)
        val br = barRect?.let { barPinned(uiW) }
        fun barMinY(x: Int): Int = if (br != null && settings().barPinned && x < br[2] + 2 && x + iw > br[0] - 2) br[3] + PAD else PAD
        class Cand(val side: Int, val x: Int, val y: Int, val ih: Int, val fits: Boolean) {
            fun rect() = floatArrayOf(x.toFloat(), y.toFloat(), (x + iw).toFloat(), (y + ih).toFloat())
        }
        // side: 0 right, 1 left, 2 below, 3 above; [alt] also steps past the target / aligns right.
        fun at(side: Int, alt: Boolean = false): Cand {
            if (side == 0 || side == 1) {
                val right = if (alt && target != null) max(b.x1, target[2]) else b.x1
                val left = if (alt && target != null) min(b.x0, target[0]) else b.x0
                val x = (if (side == 0) right.roundToInt() + GAP else left.roundToInt() - GAP - iw).coerceIn(PAD, maxX)
                val minY = barMinY(x)
                val ih = min(nat, max(40, uiH - minY - PAD))
                val y = b.y0.roundToInt().coerceIn(minY, max(minY, uiH - PAD - ih))
                val fits = if (side == 0) right + GAP + iw <= uiW - PAD else left - GAP - iw >= PAD
                return Cand(side, x, y, ih, fits)
            }
            val x = (if (alt) b.x1.roundToInt() - iw else b.x0.roundToInt()).coerceIn(PAD, maxX)
            val minY = barMinY(x)
            val need = min(nat, 120)
            if (side == 2) {
                val y = max(minY, b.y1.roundToInt() + GAP)
                return Cand(side, x, y, min(nat, max(40, uiH - PAD - y)), y + need <= uiH - PAD)
            }
            val ih = min(nat, max(40, b.y0.roundToInt() - GAP - minY))
            return Cand(side, x, b.y0.roundToInt() - GAP - ih, ih, b.y0 - GAP - need >= minY)
        }
        val other = { s: Int -> if (s == 0) 1 else 0 }
        var c = at(if (inspRight) 0 else 1)
        if (!c.fits) {
            val o = at(other(c.side))
            c = if (o.fits) o else at(if (uiW - b.x1 >= b.x0) 0 else 1)
        }
        if (target != null && HudLayout.overlaps(c.rect(), target)) {
            val alts =
                listOf(at(other(c.side)), at(c.side, true), at(other(c.side), true), at(2), at(2, true), at(3), at(3, true))
                    .filter { it.fits && !HudLayout.overlaps(it.rect(), target) && !HudLayout.overlaps(it.rect(), b.rect()) }
            alts.maxByOrNull { it.ih }?.let { c = it }
        }
        if (c.side == 0 || c.side == 1) inspRight = c.side == 0
        return intArrayOf(c.x, c.y, c.x + iw, c.y + c.ih)
    }

    private fun rowLabel(g: GuiGraphicsExtractor, label: String, x: Int, y: Int) = tx(g, label, x, y, y + ROW_H, Theme.TEXT_DIM)

    /** Draws (or measures) the inspector body for HUD [id] between [x0] and [x1] from [top]; returns its height. */
    private fun inspectorBody(g: GuiGraphicsExtractor, mx: Int, my: Int, id: String, h: MovableHud, b: HudBox, x0: Int, x1: Int, top: Int): Int {
        val t = h.readTransform()
        val link = t.link
        var y = top
        val cx0 = x0 + LABEL_W + 4

        // Warnings.
        val warns = ArrayList<String>()
        val i = b.info
        i.hiddenTarget?.let { warns += "${targetName(it)} is hidden: using the screen anchor." }
        if (i.cycle != null) warns += "Link cycle: using the screen anchor."
        if (i.flipped && link != null) warns += "No room ${sideWord(link).lowercase(Locale.ROOT)} ${targetName(link.target)}: shown on the opposite side until there is."
        i.tookPlaceOf?.let { warns += "${targetName(it)} is hidden: took its place." }
        if (abs(i.clampX) > 0.5f || abs(i.clampY) > 0.5f) warns += "Kept on screen: pushed ${i.pushed().roundToInt()} px for now. Its place is kept for when there is room."
        for (w in warns) {
            for (line in wrapText(w, x1 - x0, f9h)) {
                tx(g, line, x0, y, y + 11, WARN, f9h)
                y += 11
            }
            y += 4
        }

        // Position.
        rowLabel(g, "Position", x0, y)
        syncField(fieldX, b.x0.roundToInt().toString())
        syncField(fieldY, b.y0.roundToInt().toString())
        val tagW = textWidth("x", f10) + 4
        val half = (x1 - cx0 - 4) / 2
        val fy0 = y + 1
        tx(g, "x", cx0, y, y + ROW_H, Theme.TEXT_DIM)
        numberField(g, mx, my, cx0 + tagW, fy0, cx0 + half, fy0 + FIELD_H, fieldX)
        tx(g, "y", cx0 + half + 4, y, y + ROW_H, Theme.TEXT_DIM)
        numberField(g, mx, my, cx0 + half + 4 + tagW, fy0, x1, fy0 + FIELD_H, fieldY)
        y += ROW_H + ROW_GAP

        // Scale: slider + number.
        rowLabel(g, "Scale", x0, y)
        syncField(fieldScale, formatScale(t.scale))
        val numW = 40
        val s0 = cx0 + 4
        val s1 = x1 - numW - 8
        val cy = y + ROW_H / 2
        if (s1 > s0 + 8) {
            val frac = ((t.scale - SCALE_MIN) / (SCALE_MAX - SCALE_MIN)).coerceIn(0f, 1f)
            rr(g, s0, cy - 2, s1, cy + 2, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
            val knob = s0 + (frac * (s1 - s0)).roundToInt()
            if (knob > s0 + 1) rr(g, s0, cy - 2, knob, cy + 2, Theme.ACCENT, SoulRenderPipelines.ROUND_PILL)
            rr(g, knob - 5, cy - 5, knob + 5, cy + 5, Theme.TEXT, SoulRenderPipelines.ROUND_PILL)
            hit(s0 - 5, cy - 6, s1 + 5, cy + 6) {
                sliderId = id
                sliderBefore = snapshot()
                sliderX0 = s0
                sliderX1 = s1
                setScaleFromSlider(lastMouseX.toDouble())
            }
        }
        numberField(g, mx, my, x1 - numW, y + 1, x1, y + 1 + FIELD_H, fieldScale)
        y += ROW_H + ROW_GAP

        // Background (the one display switch on first sight).
        val toggles = h.menuToggles()
        toggles.firstOrNull { it.label == "Background" }?.let { y = toggleRow(g, mx, my, x0, x1, y, it) }

        // Attach line.
        y = attachLine(g, mx, my, x0, x1, y + 4, id, link) + 1

        // Style.
        val styleRows = h.supportsFontOverride() || h.supportsTextShadow() || toggles.any { it.label != "Background" }
        if (styleRows) {
            y = section(g, mx, my, x0, x1, y, "Style", styleSummary(h, t), styleOpen, { styleOpen = !styleOpen }) { yy -> styleBody(g, mx, my, x0, x1, yy, h, t) }
        }
        y = section(g, mx, my, x0, x1, y, "Advanced", advancedSummary(t), advancedOpen, { advancedOpen = !advancedOpen }) { yy -> advancedBody(g, mx, my, x0, x1, cx0, yy, id, t) }

        // Settings… + Reset.
        fl(g, x0 - 8, y, x1 + 8, y + 1, Theme.SEPARATOR)
        y += 6
        val bw = (x1 - x0 - 6) / 2
        val hasSettings = settingsAvailable.getOrPut(id) { h.settingsScreen(null) != null }
        button(g, mx, my, x0, y, x0 + bw, "Settings…", enabled = hasSettings, h = ROW_H, size = f11) {
            minecraft?.setScreenAndShow(h.settingsScreen(this) ?: return@button)
        }
        button(g, mx, my, x1 - bw, y, x1, "Reset", h = ROW_H, size = f11) { edit { store.set(id, h.defaultTransform()) } }
        y += ROW_H
        return y - top
    }

    private fun toggleRow(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, x1: Int, y: Int, toggle: HudMenuToggle): Int {
        tx(g, ellipsize(toggle.label, x1 - x0 - 30, f10), x0, y, y + ROW_H, Theme.TEXT)
        switch(g, x1 - 22, y + 2, toggle.get())
        hit(x0, y, x1, y + ROW_H) {
            toggle.toggle()
            relayout()
        }
        return y + ROW_H
    }

    private fun sideWord(link: HudLink): String =
        when (link.mainAxis) {
            HudAxis.X -> if (link.h == HudPlace.OUT_START) "Left of" else "Right of"
            HudAxis.Y -> if (link.v == HudPlace.OUT_START) "Above" else "Below"
            else -> "On"
        }

    /** "(below, left edges)": the side, plus the edge it is glued to when it follows both axes. */
    private fun attachDetail(link: HudLink): String {
        val side = when (sideWord(link)) {
            "Left of" -> "left"
            "Right of" -> "right"
            "Above" -> "above"
            "Below" -> "below"
            else -> "on top"
        }
        val m = link.mainAxis
        if (m == HudAxis.NONE || !link.follow) return side
        val cross = if (m == HudAxis.X) link.v else link.h
        val shift = if (m == HudAxis.X) link.offsetY else link.offsetX
        if (HudPlace.isOut(cross)) return "$side, corner"
        val edge = (if (m == HudAxis.X) listOf("top edges", "centered", "bottom edges") else listOf("left edges", "centered", "right edges"))[cross]
        return "$side, $edge" + if (shift != 0f) " ${if (shift > 0) "+" else "-"}${fmt(abs(shift))} px" else ""
    }

    /** The one line the attach state gets on first sight. */
    private fun attachLine(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, x1: Int, y0: Int, id: String, link: HudLink?): Int {
        if (link == null) {
            val lines = wrapText("Drop next to another element to attach", x1 - x0, f9h)
            var y = y0
            for (line in lines) {
                tx(g, line, x0, y, y + 11, Theme.TEXT_DIM, f9h)
                y += 11
            }
            return max(y, y0 + 18)
        }
        val detachW = textWidth("Detach", f9h) + 4
        val textX = x0 + 15
        val textW = x1 - detachW - 4 - textX
        val words = ArrayList<Pair<String, MsdfFont>>()
        "Attached to".split(" ").forEach { words += it to regular }
        targetName(link.target).split(" ").forEach { words += it to semi }
        "(${attachDetail(link)})".split(" ").forEach { words += it to regular }
        val lines = richWrap(words, textW, f9h)
        val h = max(18, lines.size * 11)
        var y = y0 + (h - lines.size * 11) / 2
        for (line in lines) {
            var x = textX
            for ((w, wt) in line) {
                tx(g, w, x, y, y + 11, Theme.TEXT, f9h, wt)
                x += textWidth("$w ", f9h, wt)
            }
            y += 11
        }
        iconLink(g, x0 + 5f, y0 + h / 2f, 10f, LINK_LIGHT)
        val over = hovered(mx, my, x1 - detachW, y0, x1, y0 + h)
        tx(g, "Detach", x1 - detachW + 2, y0, y0 + h, if (over) WHITE else LINK_LIGHT, f9h)
        if (over) fl(g, x1 - detachW + 2, y0 + h / 2 + 5, x1 - 2, y0 + h / 2 + 6, WHITE)
        hit(x1 - detachW, y0, x1, y0 + h) { edit { edits.detach(id) } }
        return y0 + h
    }

    private fun richWrap(words: List<Pair<String, MsdfFont>>, maxW: Int, size: Float): List<List<Pair<String, MsdfFont>>> {
        val lines = ArrayList<List<Pair<String, MsdfFont>>>()
        var cur = ArrayList<Pair<String, MsdfFont>>()
        var w = 0
        for (word in words) {
            val ww = textWidth(word.first, size, word.second)
            val space = textWidth(" ", size, word.second)
            if (cur.isNotEmpty() && w + space + ww > maxW) {
                lines += cur
                cur = ArrayList()
                w = 0
            }
            w += (if (cur.isEmpty()) 0 else space) + ww
            cur += word
        }
        if (cur.isNotEmpty()) lines += cur
        return lines
    }

    /** A collapsible inspector section; open/closed is remembered for the session. */
    private fun section(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        x1: Int,
        y0: Int,
        title: String,
        summary: String,
        open: Boolean,
        toggle: () -> Unit,
        body: (Int) -> Int,
    ): Int {
        var y = y0 + 5
        fl(g, x0 - 8, y, x1 + 8, y + 1, Theme.SEPARATOR)
        val over = hovered(mx, my, x0 - 8, y + 1, x1 + 8, y + SEC_H)
        if (over) fl(g, x0 - 8, y + 1, x1 + 8, y + SEC_H, 0x0AFFFFFF)
        triangle(g, x0 + 2f, y + SEC_H / 2f + 0.5f, 2.2f, if (open) 2 else 1, Theme.TEXT_DIM)
        tx(g, title, x0 + 8, y, y + SEC_H, Theme.TEXT, f10, semi)
        val titleW = textWidth(title, f10, semi)
        val sum = ellipsize(summary, x1 - (x0 + 8 + titleW + 8), f9h)
        tx(g, sum, x1 - textWidth(sum, f9h), y, y + SEC_H, Theme.TEXT_DIM, f9h)
        hit(x0 - 8, y + 1, x1 + 8, y + SEC_H) { toggle() }
        y += SEC_H
        if (open) y = body(y + 2) + 4
        return y
    }

    private fun styleSummary(h: MovableHud, t: HudTransform): String {
        val font = ArrayList<String>()
        if (h.supportsFontOverride()) {
            if (t.fontChoice != 0) font += HudFontChoice.LABELS[t.fontChoice.coerceIn(0, 4)]
            if (t.fontMode != 0) font += HudFontMode.LABELS[t.fontMode.coerceIn(0, 2)]
        }
        if (h.supportsTextShadow() && t.textShadowMode != 0) font += if (t.textShadowMode == HudTextShadowMode.ON) "Shadow" else "No shadow"
        val on = h.menuToggles().filter { it.label != "Background" && it.get() }.map { it.label }
        return (listOf(font.joinToString(" · ").ifEmpty { "Default" }) + on).joinToString(" · ")
    }

    private fun segmented(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y: Int, labels: List<String>, current: Int, pickIt: (Int) -> Unit): Int {
        var x = x0
        for ((i, label) in labels.withIndex()) {
            val w = textWidth(label, f9) + 10
            val on = i == current
            val over = hovered(mx, my, x, y, x + w, y + 13)
            rr(g, x, y, x + w, y + 13, if (on) Theme.ACCENT else if (over) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
            txCenter(g, label, x, x + w, y, y + 13, if (on) WHITE else Theme.TEXT_DIM, f9)
            hit(x, y, x + w, y + 13) { pickIt(i) }
            x += w + 2
        }
        return x
    }

    private fun styleBody(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, x1: Int, top: Int, h: MovableHud, t: HudTransform): Int {
        var y = top
        val cx0 = x0 + LABEL_W + 4
        val id = h.id
        if (h.supportsFontOverride()) {
            rowLabel(g, "Font", x0, y)
            ddButton(g, mx, my, cx0, y + 1, x1, y + 1 + FIELD_H, "font", HudFontChoice.LABELS[t.fontChoice.coerceIn(0, 4)]) { anchor ->
                popup = Popup("font", anchor, HudFontChoice.LABELS.mapIndexed { i, l -> Opt(l, i, cur = i == t.fontChoice) }) { o ->
                    edit { store.set(id, hud(id)!!.readTransform().copy(fontChoice = o.value as Int)) }
                }
            }
            y += ROW_H + ROW_GAP
            rowLabel(g, "Weight", x0, y)
            segmented(g, mx, my, cx0, y + 1, HudFontMode.LABELS, t.fontMode) { v -> edit { store.set(id, hud(id)!!.readTransform().copy(fontMode = v)) } }
            y += ROW_H + ROW_GAP
        }
        if (h.supportsTextShadow()) {
            rowLabel(g, "Shadow", x0, y)
            segmented(g, mx, my, cx0, y + 1, HudTextShadowMode.LABELS, t.textShadowMode) { v -> edit { store.set(id, hud(id)!!.readTransform().copy(textShadowMode = v)) } }
            y += ROW_H + ROW_GAP
        }
        for (tog in h.menuToggles()) if (tog.label != "Background") y = toggleRow(g, mx, my, x0, x1, y, tog)
        return y
    }

    private fun posName(h: Int, v: Int): String = "${V_NAMES[v.coerceIn(0, 2)]}-${H_NAMES[h.coerceIn(0, 2)]}".replace("center-center", "center")

    private fun advancedSummary(t: HudTransform): String {
        val link = t.link ?: return "Screen ${posName(t.hAnchor, t.vAnchor)}${if (t.autoAnchor) " (auto)" else ""}"
        if (link.mainAxis == HudAxis.NONE) return "On top of it"
        val mode = listOf("auto", "follow", "fixed")[HudEdits.followMode(link)]
        return "Gap ${fmt(link.gap ?: 0f)} px · $mode"
    }

    private fun screenText(t: HudTransform): String {
        val hx = when (t.hAnchor) {
            0 -> "${fmt(t.offsetX)} px from the left"
            2 -> "${fmt(-t.offsetX)} px from the right"
            else -> "centered" + if (t.offsetX != 0f) " ${if (t.offsetX > 0) "+" else ""}${fmt(t.offsetX)} px" else ""
        }
        val vy = when (t.vAnchor) {
            0 -> "${fmt(t.offsetY)} px from the top"
            2 -> "${fmt(-t.offsetY)} px from the bottom"
            else -> "middle" + if (t.offsetY != 0f) " ${if (t.offsetY > 0) "+" else ""}${fmt(t.offsetY)} px" else ""
        }
        return "$hx, $vy"
    }

    private fun caption(g: GuiGraphicsExtractor, text: String, x0: Int, x1: Int, y0: Int, color: Int = Theme.TEXT_DIM): Int {
        var y = y0
        for (line in wrapText(text, x1 - x0, f9h)) {
            tx(g, line, x0, y, y + 11, color, f9h)
            y += 11
        }
        return y
    }

    private fun advancedBody(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, x1: Int, cx0: Int, top: Int, id: String, t: HudTransform): Int {
        var y = top
        val link = t.link
        // To: target dropdown + pick on screen.
        rowLabel(g, "To", x0, y)
        val pickX0 = x1 - 18
        ddButton(g, mx, my, cx0, y + 1, pickX0 - 4, y + 1 + FIELD_H, "target", link?.let { targetName(it.target) } ?: "Screen") { anchor ->
            popup = Popup("target", anchor, targetOptions(id, link?.target)) { o ->
                edit {
                    if (edits.setLinkTarget(id, o.value as String?)) glow(id)
                }
            }
        }
        val picking = pick == id
        val pOver = hovered(mx, my, pickX0, y + 1, x1, y + 1 + FIELD_H)
        if (picking) {
            rr(g, pickX0, y + 1, x1, y + 1 + FIELD_H, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
        } else {
            rr(g, pickX0, y + 1, x1, y + 1 + FIELD_H, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_SMALL)
            rr(g, pickX0 + 1, y + 2, x1 - 1, y + FIELD_H, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
        }
        iconCross(g, (pickX0 + x1) / 2f, y + 1 + FIELD_H / 2f, 10f, if (picking) WHITE else if (pOver) Theme.TEXT else Theme.TEXT_DIM)
        hit(pickX0, y + 1, x1, y + 1 + FIELD_H) {
            pick = if (pick == null) id else null
            popup = null
        }
        y += ROW_H + ROW_GAP

        if (link != null) {
            val m = link.mainAxis
            if (m != HudAxis.NONE) {
                val o = if (m == HudAxis.X) "y" else "x"
                val mode = HudEdits.followMode(link)
                rowLabel(g, "Along $o", x0, y)
                segmented(g, mx, my, cx0, y + 1, listOf("Auto", "Follow", "Fixed"), mode) { v -> edit { edits.setFollowMode(id, v) } }
                y += ROW_H + 1
                val glued = "Lined up with its ${if ((if (m == HudAxis.X) link.v else link.h) == 1) "center" else "edge"}: follows its moves and its size."
                val why = when (mode) {
                    HudEdits.FOLLOW_AUTO -> if (link.follow) glued else "Not lined up: follows its moves, not its size."
                    HudEdits.FOLLOW_ON -> "Follows its moves and its size."
                    else -> "Fixed: stays on its own screen anchor."
                }
                y = caption(g, why, cx0, x1, y, Theme.TEXT_FAINT) + 4
                rowLabel(g, "Gap", x0, y)
                syncField(fieldGap, fmt(link.gap ?: 0f))
                numberField(g, mx, my, cx0, y + 1, cx0 + 40, y + 1 + FIELD_H, fieldGap)
                tx(g, "px", cx0 + 44, y, y + ROW_H, Theme.TEXT_DIM)
                y += ROW_H + ROW_GAP
                if (link.follow) {
                    rowLabel(g, "Shift", x0, y)
                    syncField(fieldShift, fmt(if (m == HudAxis.X) link.offsetY else link.offsetX))
                    numberField(g, mx, my, cx0, y + 1, cx0 + 40, y + 1 + FIELD_H, fieldShift)
                    tx(g, "px ${if (o == "y") "down" else "right"}", cx0 + 44, y, y + ROW_H, Theme.TEXT_DIM)
                    y += ROW_H + ROW_GAP
                }
            } else {
                rowLabel(g, "Offset", x0, y)
                syncField(fieldLx, fmt(link.offsetX))
                syncField(fieldLy, fmt(link.offsetY))
                val tagW = textWidth("x", f10) + 4
                val half = (x1 - cx0 - 4) / 2
                tx(g, "x", cx0, y, y + ROW_H, Theme.TEXT_DIM)
                numberField(g, mx, my, cx0 + tagW, y + 1, cx0 + half, y + 1 + FIELD_H, fieldLx)
                tx(g, "y", cx0 + half + 4, y, y + ROW_H, Theme.TEXT_DIM)
                numberField(g, mx, my, cx0 + half + 4 + tagW, y + 1, x1, y + 1 + FIELD_H, fieldLy)
                y += ROW_H + ROW_GAP
            }
            rowLabel(g, "If hidden", x0, y)
            ddButton(g, mx, my, cx0, y + 1, x1, y + 1 + FIELD_H, "whenhidden", HudLinkHidden.LABELS[link.whenHidden.coerceIn(0, 1)]) { anchor ->
                popup = Popup("whenhidden", anchor, HudLinkHidden.LABELS.mapIndexed { i, l -> Opt(l, i, cur = i == link.whenHidden) }) { o ->
                    edit { store.set(id, hud(id)!!.readTransform().let { it.copy(link = it.link?.copy(whenHidden = o.value as Int)) }) }
                }
            }
            y += ROW_H + ROW_GAP
        }

        // Screen anchor: 3×3 grid (re-anchors in place), Auto, what it means.
        rowLabel(g, "Screen", x0, y + 2)
        val cell = 12
        val gx = cx0
        val gy = y + 2
        panel(g, gx, gy, gx + cell * 3, gy + cell * 3, Theme.FIELD_BG, Theme.FIELD_BORDER, SoulPanel.SMALL)
        for (v in 0..2) for (hh in 0..2) {
            val c0 = gx + hh * cell
            val r0 = gy + v * cell
            if (hovered(mx, my, c0, r0, c0 + cell - 1, r0 + cell - 1)) rr(g, c0 + 1, r0 + 1, c0 + cell - 1, r0 + cell - 1, 0x33FFFFFF, SoulRenderPipelines.ROUND_SMALL)
            val on = t.hAnchor == hh && t.vAnchor == v
            val rad = if (on) 3f else 2f
            circle(g, c0 + cell / 2f, r0 + cell / 2f, rad, if (on) Theme.ACCENT else Theme.TEXT_DIM)
            hit(c0, r0, c0 + cell - 1, r0 + cell - 1) { edit { edits.setScreenAnchor(id, hh, v) } }
        }
        val sx0 = gx + cell * 3 + 6
        var sy = gy
        val autoW = textWidth("Auto", f9) + 10
        val aOver = hovered(mx, my, sx0, sy, sx0 + autoW, sy + 13)
        rr(g, sx0, sy, sx0 + autoW, sy + 13, if (t.autoAnchor) Theme.ACCENT else if (aOver) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
        txCenter(g, "Auto", sx0, sx0 + autoW, sy, sy + 13, if (t.autoAnchor) WHITE else Theme.TEXT_DIM, f9)
        hit(sx0, sy, sx0 + autoW, sy + 13) { edit { edits.toggleAutoAnchor(id) } }
        sy += 13 + 3
        sy = caption(g, screenText(t), sx0, x1, sy)
        if (link != null) sy = caption(g, if (link.fixed) "Used on the fixed axis and while the target is hidden." else "Used only while the target is hidden.", sx0, x1, sy + 2, Theme.TEXT_FAINT)
        y = max(gy + cell * 3, sy) + 4
        val followers = edits.directFollowers(id, MovableHudRegistry.all().filter { it.configuredOn() }.map { it.id })
        if (followers.isNotEmpty()) y = caption(g, "Followed by ${followers.joinToString(", ") { name(it) }}", x0, x1, y + 2)
        return y
    }

    private fun targetOptions(id: String, current: String?): List<Opt> {
        val opts = ArrayList<Opt>()
        opts += Opt("Screen", null, cur = current == null)
        opts += Opt("Vanilla HUD", header = true)
        for (v in layout.vanilla.values) {
            if (v.id == VanillaIds.ACTION_BAR && v.hidden) continue // Soul draws the action bar: vanilla's is invisible
            val to = HudTargets.vanilla(v.id)
            opts += Opt(v.name, to, cur = current == to, note = if (v.hidden) "hidden now" else null)
        }
        for (cat in categories()) {
            val others = MovableHudRegistry.all().filter { it.categoryId == cat.id && it.id != id && it.configuredOn() }
            if (others.isEmpty()) continue
            opts += Opt("HUDs · ${cat.title}", header = true)
            for (o in others) {
                val cyc = edits.dependsOn(o.id, id)
                val to = HudTargets.hud(o.id)
                opts += Opt(o.displayName, to, disabled = cyc, note = if (cyc) "follows this one" else null, cur = current == to)
            }
        }
        return opts
    }

    // ---------------------------------------------------------------- dropdown

    private class Opt(val label: String, val value: Any? = null, val header: Boolean = false, val disabled: Boolean = false, val note: String? = null, val cur: Boolean = false)

    private class Popup(val token: String, val anchor: IntArray, val options: List<Opt>, val onPick: (Opt) -> Unit) {
        var scroll = 0f
        var rect = intArrayOf(0, 0, 0, 0)
        var contentH = 0
    }

    private var popup: Popup? = null

    private fun ddButton(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int, token: String, text: String, open: (IntArray) -> Unit) {
        val isOpen = popup?.token == token
        val over = hovered(mx, my, x0, y0, x1, y1)
        panel(g, x0, y0, x1, y1, if (over) Theme.CONTROL_HOVER else Theme.FIELD_BG, if (isOpen) Theme.ACCENT else Theme.FIELD_BORDER, SoulPanel.SMALL)
        tx(g, ellipsize(text, x1 - x0 - 16, f10), x0 + 5, y0, y1, Theme.TEXT)
        chevron(g, x1 - 6f, (y0 + y1) / 2f - 0.5f, 2f, false, Theme.TEXT_DIM)
        hit(x0, y0, x1, y1) {
            if (popup?.token == token) popup = null else open(intArrayOf(x0, y0, x1, y1))
        }
    }

    private fun drawPopup(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int, uiH: Int) {
        val p = popup ?: return
        hit(0, 0, uiW, uiH) { popup = null }
        val rowH = 18
        val headH = 15
        val contentH = p.options.sumOf { if (it.header) headH else rowH } + 4
        var w = p.anchor[2] - p.anchor[0]
        for (o in p.options) {
            val ow = if (o.header) textWidth(o.label.uppercase(Locale.ROOT), f8h) + 16 else textWidth(o.label, f10) + 16 + (o.note?.let { textWidth(it, f9) + 10 } ?: 0)
            w = max(w, ow)
        }
        val maxH = (uiH * 0.7f).toInt()
        val h = min(contentH, maxH)
        var y0 = p.anchor[3] + 1
        if (y0 + h > uiH - 2) y0 = max(2, p.anchor[1] - 1 - h)
        val x0 = p.anchor[0].coerceIn(2, max(2, uiW - w - 2))
        p.rect = intArrayOf(x0, y0, x0 + w, y0 + h)
        p.contentH = contentH
        p.scroll = clampScroll(p.scroll, contentH, h)
        panel(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, POPUP, Theme.FIELD_BORDER, SoulPanel.SMALL)
        hit(x0, y0, x0 + w, y0 + h) {}
        beginClip(g, x0, y0, x0 + w, y0 + h)
        hitClip = p.rect
        var y = y0 + 2 - p.scroll.roundToInt()
        for (o in p.options) {
            if (o.header) {
                tx(g, o.label.uppercase(Locale.ROOT), x0 + 8, y, y + headH, Theme.TEXT_FAINT, f8h)
                y += headH
                continue
            }
            val over = !o.disabled && hovered(mx, my, x0, y, x0 + w, y + rowH - 1)
            if (over) fl(g, x0, y, x0 + w, y + rowH, Theme.CONTROL_HOVER)
            tx(g, o.label, x0 + 8, y, y + rowH, if (o.disabled) Theme.TEXT_FAINT else if (o.cur) HOVER else Theme.TEXT)
            o.note?.let { n -> tx(g, n, x0 + w - 8 - textWidth(n, f9), y, y + rowH, Theme.TEXT_FAINT, f9) }
            if (!o.disabled) {
                hit(x0, y, x0 + w, y + rowH - 1) {
                    popup = null
                    p.onPick(o)
                }
            }
            y += rowH
        }
        hitClip = null
        endClip(g)
    }

    // ---------------------------------------------------------------- controls reference

    private fun keysRows(): Pair<List<Pair<String, String>>, List<List<Triple<String, String, String?>>>> {
        val basics = listOf("Select" to "Click", "Settings" to "Right-click", "Scale" to "Scroll", "Done" to "Esc")
        val advanced =
            listOf(
                listOf(Triple("Next HUD", "Tab", null)),
                listOf(Triple("Move", "Drag", "Alt: free"), Triple("Attach", "Drop next to it", null), Triple("Detach", "Pull away or \u0000", null)),
                listOf(Triple("Fine scale", "Shift+Scroll", null), Triple("Nudge", "Arrows", "Shift: 10 px")),
                listOf(Triple("Undo / Redo", "Ctrl+Z / Ctrl+Y", null)),
            )
        return basics to advanced
    }

    private fun keyW(k: String, note: String?): Int =
        (if (k.endsWith("\u0000")) textWidth(k.dropLast(1), f10) + 9 else textWidth(k, f10)) + (note?.let { textWidth(it, f10) + 3 } ?: 0)

    private fun drawKeys(g: GuiGraphicsExtractor, dt: Float, mx: Int, my: Int, uiW: Int, uiH: Int) {
        val s = settings()
        val (basics, advanced) = keysRows()
        val col1 = max(basics.maxOf { textWidth(it.first, f10) }, if (s.controlsAdvanced) advanced.flatten().maxOf { textWidth(it.first, f10) } else 0)
        val col2 = max(basics.maxOf { keyW(it.second, null) }, if (s.controlsAdvanced) advanced.flatten().maxOf { keyW(it.second, it.third) } else 0)
        val headW = 6 + 11 + 4 + textWidth("Controls", f10) + 4 + 5 + 6
        val w = if (s.controlsOpen) max(headW, 7 + col1 + 10 + col2 + 7) else headW
        val gridH = if (!s.controlsOpen) 0 else 1 + basics.size * 13 + 3 + 14 + (if (s.controlsAdvanced) advanced.sumOf { it.size * 13 } + (advanced.size - 1) * 5 else 0) + 5
        val h = 16 + gridH

        // Top-left; below or beside the top bar / inspector / cog menu when one covers the corner.
        val blocks = listOfNotNull(barRect, inspRect, cogRect)
        fun at(x: Int, y: Int) = intArrayOf(x, y, x + w, y + h)
        val cands = listOf(at(PAD, PAD)) + blocks.map { at(PAD, it[3] + PAD) } + blocks.map { at(it[2] + PAD, PAD) }
        fun ov(a: IntArray, b: IntArray) = a[0] < b[2] && a[2] > b[0] && a[1] < b[3] && a[3] > b[1]
        val r = cands.firstOrNull { c -> c[2] <= uiW - PAD && c[3] <= uiH - PAD && blocks.none { ov(c, it) } } ?: cands.first()

        // Fades out (and lets clicks through) while a HUD near it is dragged, pressed, scaled or nudged.
        val now = System.currentTimeMillis()
        val act = drag?.id ?: press?.id ?: touchId?.takeIf { now < touchUntil }
        val ab = act?.let { a -> drag?.box?.takeIf { drag?.id == a } ?: box(a) }
        val near = ab != null && ab.x0 < r[2] + KEYS_NEAR && ab.x1 > r[0] - KEYS_NEAR && ab.y0 < r[3] + KEYS_NEAR && ab.y1 > r[1] - KEYS_NEAR
        val target = if (near) 0f else 1f
        val step = dt / FADE_MS
        keysAlpha = if (keysAlpha < target) min(target, keysAlpha + step) else max(target, keysAlpha - step)
        keysRect = if (near || keysAlpha < 0.5f) null else r
        if (keysAlpha <= 0.01f) return
        val prevAlpha = alpha
        alpha = keysAlpha
        val prevSuppress = suppressRegions
        suppressRegions = near || prevSuppress

        panel(g, r[0] - 1, r[1] - 1, r[2] + 1, r[3] + 1, Theme.PANEL_FLOATING, Theme.FIELD_BORDER, SoulPanel.SMALL)
        hit(r[0], r[1], r[2], r[3]) {}
        val hOver = hovered(mx, my, r[0], r[1], r[2], r[1] + 16)
        val hc = if (hOver) Theme.TEXT else Theme.TEXT_DIM
        iconKeys(g, r[0] + 6f, r[1] + 2.5f, 11f, hc)
        tx(g, "Controls", r[0] + 6 + 11 + 4, r[1], r[1] + 16, hc)
        chevron(g, r[0] + 6 + 11 + 4 + textWidth("Controls", f10) + 4 + 2.5f, r[1] + 8f, 2.5f, s.controlsOpen, hc)
        hit(r[0], r[1], r[2], r[1] + 16) { updateSettings { copy(controlsOpen = !controlsOpen) } }
        if (s.controlsOpen) {
            var y = r[1] + 16 + 1
            val kx = r[0] + 7 + col1 + 10
            fun row(a: String, k: String, note: String?) {
                tx(g, a, r[0] + 7, y, y + 12, Theme.TEXT_DIM)
                var x = kx
                if (k.endsWith("\u0000")) {
                    val kk = k.dropLast(1)
                    tx(g, kk, x, y, y + 12, Theme.TEXT)
                    x += textWidth(kk, f10) + 1
                    iconLink(g, x + 4f, y + 6f, 8f, LINK_LIGHT)
                    x += 8
                } else {
                    tx(g, k, x, y, y + 12, Theme.TEXT)
                    x += textWidth(k, f10)
                }
                if (note != null) tx(g, note, x + 3, y, y + 12, Theme.TEXT_FAINT)
                y += 13
            }
            for ((a, k) in basics) row(a, k, null)
            y += 3
            fl(g, r[0] + 7, y - 1, r[2] - 7, y, Theme.SEPARATOR)
            val aOver = hovered(mx, my, r[0], y, r[2], y + 14)
            val ac = if (aOver) Theme.TEXT else Theme.TEXT_FAINT
            chevron(g, r[0] + 9.5f, y + 7.5f, 2.5f, s.controlsAdvanced, ac)
            tx(g, "Advanced", r[0] + 15, y + 1, y + 14, ac)
            hit(r[0], y, r[2], y + 14) { updateSettings { copy(controlsAdvanced = !controlsAdvanced) } }
            y += 14
            if (s.controlsAdvanced) {
                for ((gi, group) in advanced.withIndex()) {
                    if (gi > 0) {
                        fl(g, r[0] + 7, y + 2, r[2] - 7, y + 3, Theme.SEPARATOR)
                        y += 5
                    }
                    for ((a, k, n) in group) row(a, k, n)
                }
            }
        }
        alpha = prevAlpha
        suppressRegions = prevSuppress
    }

    // ---------------------------------------------------------------- attach / detach

    private fun glow(id: String) {
        glowId = id
        glowUntil = System.currentTimeMillis() + GLOW_MS
    }

    /** Link icon on a HUD: detach, after a confirmation unless the player turned it off. */
    private fun askDetach(id: String) {
        val link = hud(id)?.readTransform()?.link ?: return
        if (!settings().confirmDetach) {
            edit(focus = id) { edits.detach(id) }
            return
        }
        val n = name(id)
        val target = targetName(link.target)
        showConfirm("Detach $n from $target?", "$n stays where it is but no longer moves with $target. Ctrl+Z undoes it.", "Detach", "Don't show this again") { dontAsk ->
            if (dontAsk) updateSettings { copy(confirmDetach = false) }
            edit(focus = id) { edits.detach(id) }
        }
    }

    // ---------------------------------------------------------------- drag + snapping

    /** Snap lines on one axis: the edge-snap lines and centers, other HUDs, the vanilla HUD, the inventory frame. */
    private fun snapTargets(moving: String, horizontal: Boolean): List<Float> {
        val len = if (horizontal) screenW else screenH
        val e = settings().edgeSnap.coerceIn(0f, len / 2f)
        val out = arrayListOf(e, len / 2f, len - e)
        fun add(r: FloatArray) {
            val a0 = if (horizontal) r[0] else r[1]
            val a1 = if (horizontal) r[2] else r[3]
            out.add(a0)
            out.add((a0 + a1) / 2f)
            out.add(a1)
        }
        for (id in visibleIds()) if (id != moving && !edits.dependsOn(id, moving)) box(id)?.let { add(it.rect()) }
        for (v in layout.vanilla.values) if (!v.hidden) add(v.rect)
        if (backdropShown()) add(inventorySoul())
        return out
    }

    private fun linkCandidates(id: String): List<HudSnap.Candidate> {
        val out = ArrayList<HudSnap.Candidate>()
        for (v in layout.vanilla.values) if (!v.hidden) out += HudSnap.Candidate(HudTargets.vanilla(v.id), v.rect)
        for (o in visibleIds()) if (o != id && !edits.dependsOn(o, id)) box(o)?.let { out += HudSnap.Candidate(HudTargets.hud(o), it.rect()) }
        return out
    }

    private fun startDrag(p: Press) {
        val b = box(p.id) ?: return
        val link = hud(p.id)?.readTransform()?.link
        val m = if (link != null && b.info.linked) link.mainAxis else HudAxis.NONE
        val target = b.info.target
        val slide = if (m != HudAxis.NONE && target != null) Slide(m, if (m == HudAxis.X) b.x0 else b.y0, target, targetName(link!!.target)) else null
        drag = Drag(p.id, p.x, p.y, b, slide)
        popup = null
    }

    private fun dragTo(px: Float, py: Float) {
        val d = drag ?: return
        val sb = d.start
        val w = sb.w
        val h = sb.h
        // The dragged box can not leave the screen either.
        var x0 = (sb.x0 + (px - d.px)).coerceIn(0f, max(0f, screenW - w))
        var y0 = (sb.y0 + (py - d.py)).coerceIn(0f, max(0f, screenH - h))
        d.guidesX.clear()
        d.guidesY.clear()
        val snap = minecraft?.hasAltDown() != true
        // Attached: moving along the target's edge keeps it attached (slide); UNLINK_PX off the edge
        // detaches (or attaches elsewhere). Near one of the target's edges on the slide axis it glues.
        val sl = if (snap) d.slide else null
        d.keep = false
        d.glue = null
        if (sl != null && abs((if (sl.axis == HudAxis.X) x0 else y0) - sl.main0) <= HudSnap.UNLINK_PX) {
            val t = sl.target
            if (sl.axis == HudAxis.X) {
                x0 = sl.main0
                val gl = HudSnap.glueSnap(y0, h, t[1], t[3])
                if (gl != null) {
                    y0 = gl[0]
                    d.glue = gl[1].toInt()
                    d.guidesY += gl[2]
                } else {
                    val s = HudSnap.snapAxis(y0, h, snapTargets(d.id, false), d.guidesY)
                    y0 += s ?: (HudSnap.whole(y0) - y0)
                }
            } else {
                y0 = sl.main0
                val gl = HudSnap.glueSnap(x0, w, t[0], t[2])
                if (gl != null) {
                    x0 = gl[0]
                    d.glue = gl[1].toInt()
                    d.guidesX += gl[2]
                } else {
                    val s = HudSnap.snapAxis(x0, w, snapTargets(d.id, true), d.guidesX)
                    x0 += s ?: (HudSnap.whole(x0) - x0)
                }
            }
            d.box = HudBox(x0, y0, w, h)
            d.link = null
            d.keep = true
            return
        }
        // An attach wins over plain snapping on its axis: flush against the target plus the gap.
        var lk = if (snap) HudSnap.findLink(HudBox(x0, y0, w, h), linkCandidates(d.id), settings().linkGap) else null
        if (lk != null) {
            val r = lk.rect
            val gap = settings().linkGap
            val fy = if (lk.v == HudPlace.OUT_END) r[3] + gap else if (lk.v == HudPlace.OUT_START) r[1] - h - gap else null
            val fx = if (fy == null) (if (lk.h == HudPlace.OUT_END) r[2] + gap else r[0] - w - gap) else null
            if (fy != null && fy >= -0.01f && fy + h <= screenH + 0.01f) y0 = fy
            else if (fx != null && fx >= -0.01f && fx + w <= screenW + 0.01f) x0 = fx
            else lk = null
        }
        val vert = lk != null && (lk.v == HudPlace.OUT_START || lk.v == HudPlace.OUT_END)
        val horz = lk != null && !vert
        // Attached: the other axis glues to one of the target's edges when it is that close.
        val glue = if (lk == null) null else if (vert) HudSnap.glueSnap(x0, w, lk.rect[0], lk.rect[2]) else HudSnap.glueSnap(y0, h, lk.rect[1], lk.rect[3])
        if (glue != null) {
            if (vert) {
                x0 = glue[0]
                d.guidesX += glue[2]
            } else {
                y0 = glue[0]
                d.guidesY += glue[2]
            }
        }
        val freeX = !horz && !(glue != null && vert)
        val freeY = !vert && !(glue != null && horz)
        if (freeX) {
            val s = if (snap) HudSnap.snapAxis(x0, w, snapTargets(d.id, true), d.guidesX) else null
            x0 += s ?: (HudSnap.whole(x0) - x0)
        }
        if (freeY) {
            val s = if (snap) HudSnap.snapAxis(y0, h, snapTargets(d.id, false), d.guidesY) else null
            y0 += s ?: (HudSnap.whole(y0) - y0)
        }
        d.box = HudBox(x0, y0, w, h)
        if (lk != null) {
            val r = lk.rect
            lk.glue = glue?.get(1)?.toInt()
            if (vert) lk.h = glue?.get(1)?.toInt() ?: HudSnap.alignAxis(x0, x0 + w, r[0], r[2])
            else lk.v = glue?.get(1)?.toInt() ?: HudSnap.alignAxis(y0, y0 + h, r[1], r[3])
        }
        d.link = lk
    }

    private fun finishDrag() {
        val d = drag ?: return
        drag = null
        inspRight = true
        relayout()
        val id = d.id
        val b = d.box
        val slide = d.slide
        if (d.keep && slide != null) {
            edit(focus = id) { edits.placeAlong(id, b, slide.target) }
            return
        }
        val lk = d.link
        edit(focus = id) {
            if (lk != null) {
                edits.attachTo(id, lk.to, lk.rect, lk.h, lk.v, b)
                glow(id)
            } else {
                val t = hud(id)!!.readTransform().copy(link = null)
                store.set(id, HudLayout.screenFromBox(t, b, screenW, screenH, true))
            }
        }
    }

    private fun setScaleFromSlider(mx: Double) {
        val id = sliderId ?: return
        val frac = ((mx - sliderX0) / (sliderX1 - sliderX0).coerceAtLeast(1)).coerceIn(0.0, 1.0).toFloat()
        val next = (Math.round((SCALE_MIN + frac * (SCALE_MAX - SCALE_MIN)) * 20f) / 20f).coerceIn(SCALE_MIN, SCALE_MAX)
        val t = hud(id)?.readTransform() ?: return
        if (t.scale != next) {
            store.set(id, t.copy(scale = next))
            relayout()
        }
    }

    // ---------------------------------------------------------------- input

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val s = SoulUi.factor()
        val fx = (click.x() / s).toFloat()
        val fy = (click.y() / s).toFloat()
        val mx = fx.roundToInt()
        val my = fy.roundToInt()
        lastMouseX = mx
        lastMouseY = my
        val right = click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT
        val left = click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT

        if (!alertModalOpen) {
            focused()?.let { f -> if (!(mx in f.rect[0]..f.rect[2] && my in f.rect[1]..f.rect[3])) commit(f) }
            // The cog menu closes on any click outside it (its own button toggles it).
            val cr = cogRect
            if (cogOpen && cr != null && !(mx in cr[0]..cr[2] && my in cr[1]..cr[3]) && !onCogButton(mx, my)) cogOpen = false
        }
        for (r in regions.asReversed()) {
            if (mx in r.x0..r.x1 && my in r.y0..r.y1) {
                if (left || (right && r in rightClick)) r.onClick()
                return true
            }
        }
        if (alertModalOpen) return true

        if (pick != null) {
            val c = if (left) pickCandidateAt(fx, fy) else null
            if (c != null && !c.disabled) {
                val pid = pick!!
                pick = null
                edit(focus = pid) { if (edits.setLinkTarget(pid, c.to)) glow(pid) }
            }
            return true
        }
        val under = hudsAt(fx, fy)
        if (under.isEmpty()) {
            if (left) {
                commitInspectorFields()
                sel = null
            }
            return true
        }
        val current = sel
        val target = if (current != null && current in under) current else under.first()
        val cycle = target == current && under.size > 1
        if (target != current) inspRight = true
        if (right) {
            toggleInsp(target)
            return true
        }
        select(target)
        if (!left) {
            if (cycle) cycleSel(under)
            return true
        }
        press = Press(target, fx, fy, cycle, under)
        return true
    }

    private fun onCogButton(mx: Int, my: Int): Boolean {
        val r = barRect ?: return false
        val pr = barPinned(screenW.toInt())
        val twoRows = pr[3] - pr[1] > BTN_H + 6
        val ay = r[1] + 3 + if (twoRows) BTN_H + 4 else 0
        return mx in r[2] - 3 - BTN_H..r[2] - 3 && my in ay..ay + BTN_H
    }

    private fun cycleSel(under: List<String>) {
        val i = under.indexOf(sel)
        sel = under[(i + 1) % under.size]
        inspRight = true
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val s = SoulUi.factor()
        val fx = (click.x() / s).toFloat()
        val fy = (click.y() / s).toFloat()
        if (sliderId != null) {
            setScaleFromSlider(fx.toDouble())
            return true
        }
        val p = press ?: return super.mouseDragged(click, dragX, dragY)
        if (drag == null) {
            if (hypot((fx - p.x).toDouble(), (fy - p.y).toDouble()) < DRAG_START) return true
            startDrag(p)
        }
        dragTo(fx, fy)
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val sid = sliderId
        val before = sliderBefore
        if (sid != null && before != null) pushStep(before, snapshot(), null, sid)
        sliderId = null
        sliderBefore = null
        val p = press
        press = null
        if (drag != null) finishDrag() else if (p != null && p.cycle) cycleSel(p.under)
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (alertModalOpen) return true
        val s = SoulUi.factor()
        val fx = (mouseX / s).toFloat()
        val fy = (mouseY / s).toFloat()
        val mx = fx.roundToInt()
        val my = fy.roundToInt()
        val shift = minecraft?.hasShiftDown() == true
        // Some platforms turn Shift+wheel into a horizontal scroll; only the sign counts.
        val raw = if (scrollY != 0.0) scrollY else if (shift) scrollX else 0.0
        if (raw == 0.0) return true
        val dir = Math.signum(raw).toInt()
        popup?.let { p ->
            val r = p.rect
            if (mx in r[0]..r[2] && my in r[1]..r[3]) p.scroll = clampScroll(p.scroll - dir * SCROLL_STEP, p.contentH, r[3] - r[1])
            return true
        }
        if (pick != null) return true
        // A number field under the cursor steps.
        for (f in shownFields) {
            val r = f.rect
            if (mx in r[0]..r[2] && my in r[1]..r[3] && (f === edgeSnapField || f === linkGapField || inspRect != null)) {
                if (f === edgeSnapField || f === linkGapField) {
                    val v = if (f === edgeSnapField) settings().edgeSnap else settings().linkGap
                    val step = if (shift) 10f else 1f
                    val next = ((v + dir * step) * 2f).roundToInt() / 2f
                    if (f === edgeSnapField) updateSettings { copy(edgeSnap = next.coerceIn(0f, 20f)) } else updateSettings { copy(linkGap = next.coerceIn(0f, 40f)) }
                } else {
                    bump(f, dir)
                }
                return true
            }
        }
        val ir = inspRect
        if (ir != null && pick == null && mx in ir[0]..ir[2] && my in ir[1]..ir[3]) {
            inspScroll = clampScroll(inspScroll - dir * SCROLL_STEP, inspContentH, inspBody[3] - inspBody[1])
            return true
        }
        if (onPanel(mx, my) || press != null) return true
        val id = hudsAt(fx, fy).firstOrNull() ?: return true
        select(id)
        touch(id)
        val step = if (shift) 0.05f else 0.1f
        edit("scale:$id", id) {
            val t = hud(id)!!.readTransform()
            val next = (Math.round((t.scale + dir * step) / step) * step).let { Math.round(it * 100f) / 100f }.coerceIn(SCALE_MIN, SCALE_MAX)
            store.set(id, t.copy(scale = next))
        }
        return true
    }

    private fun touch(id: String) {
        touchId = id
        touchUntil = System.currentTimeMillis() + KEYS_LINGER_MS
    }

    /** The layout-mapped letter of a key, so Ctrl+Z/Y follow the player's keyboard layout. */
    private fun keyLetter(event: KeyEvent): Char? {
        val name = GLFW.glfwGetKeyName(event.key(), event.scancode())
        if (name != null && name.length == 1) return name[0].lowercaseChar()
        return when (event.key()) {
            GLFW.GLFW_KEY_Z -> 'z'
            GLFW.GLFW_KEY_Y -> 'y'
            else -> null
        }
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) {
            // Enter confirms a confirm modal too.
            return super.keyPressed(event)
        }
        val key = event.key()
        val field = focused()
        if (field != null) {
            when (key) {
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> commit(field)
                GLFW.GLFW_KEY_ESCAPE -> field.text.focused = false // the next frame re-syncs the live value
                GLFW.GLFW_KEY_TAB -> {
                    val order = shownFields.filter { it in inspectorFields == (field in inspectorFields) }
                    commit(field)
                    if (order.isNotEmpty()) {
                        val i = order.indexOf(field)
                        val next = order[(i + if (event.hasShiftDown()) order.size - 1 else 1).mod(order.size)]
                        next.text.focused = true
                        next.text.cursor = next.text.text.length
                        next.text.anchor = 0
                    }
                }
                else -> field.text.keyPressed(event, minecraft?.keyboardHandler)
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            when {
                popup != null -> popup = null
                pick != null -> pick = null
                cogOpen -> cogOpen = false
                inspOpen -> inspOpen = false
                else -> onClose()
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_TAB && press == null) {
            val ids = visibleIds()
            if (ids.isNotEmpty()) {
                val i = ids.indexOf(sel)
                val step = if (event.hasShiftDown()) ids.size - 1 else 1
                select(ids[if (i < 0) 0 else (i + step) % ids.size])
                inspRight = true
            }
            return true
        }
        if (event.hasControlDownWithQuirk()) {
            val letter = keyLetter(event)
            if (letter == 'z') {
                if (event.hasShiftDown()) redo() else undo()
                return true
            }
            if (letter == 'y') {
                redo()
                return true
            }
        }
        val id = sel
        if (id != null && press == null) {
            val step = if (event.hasShiftDown()) 10f else 1f
            val d =
                when (key) {
                    GLFW.GLFW_KEY_LEFT -> floatArrayOf(-step, 0f)
                    GLFW.GLFW_KEY_RIGHT -> floatArrayOf(step, 0f)
                    GLFW.GLFW_KEY_UP -> floatArrayOf(0f, -step)
                    GLFW.GLFW_KEY_DOWN -> floatArrayOf(0f, step)
                    else -> null
                }
            if (d != null) {
                touch(id)
                edit("nudge:$id", id) { if (!edits.nudgeLinked(id, d[0], d[1])) edits.moveBy(id, d[0], d[1], false) }
                return true
            }
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        val field = focused() ?: return super.charTyped(event)
        field.text.charTyped(event)
        return true
    }

    override fun dismissTransientEditors() {
        focused()?.let { commit(it) }
        allFields.forEach { it.text.focused = false }
        popup = null
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            return
        }
        focused()?.let { commit(it) }
        val target = parent
        if (target != null) minecraft?.setScreenAndShow(target) else super.onClose()
    }
}
