package com.soulreturns.features.profileviewer.gui.kit

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.profileviewer.gui.ProfileFigure
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * A [SoulScreenBase] that paints kit [Node] trees: the one [KitCanvas] implementation, shared by the
 * Profile Viewer and the Contests screen. A subclass calls [beginFrame] at the top of its
 * `extractRenderState` (inside its `SoulUi` pose), points [canvas]'s view band at its scrolled body
 * ([bodyRect], [ScreenCanvas.viewTop]/[ScreenCanvas.viewBottom], [ScreenCanvas.culling]) and calls
 * [endFrame] after popping the pose.
 */
abstract class KitScreen(title: Component) : SoulScreenBase(title) {
    protected var frame: GuiGraphicsExtractor? = null
    protected var mx = 0
    protected var my = 0
    protected var now = 0L
    protected var rawMouseX = 0f
    protected var rawMouseY = 0f

    /** The scrolled body's rect (x0, y0, x1, y1): kit hover and clicks are clipped to it. */
    protected val bodyRect = IntArray(4)
    protected var pendingItem: ItemStack? = null
    protected val viewState = ViewState()
    private val faces = HashMap<String, ResolvableProfile>()

    /** A popover or menu is open over the body: kit hover, tooltips and item tooltips are off. */
    protected open fun kitBlocked(): Boolean = false

    /** A kit field takes focus: drop the screen's own focus (header search, popovers). */
    protected open fun onKitFieldFocus() {}

    /** The icon drawn at a kit field's left, by field key; null draws none. */
    protected open fun kitFieldIcon(key: String): String? = "pv_search"

    protected open fun kitOpenSection(id: String) {}

    /** Scrolls the body so [y0]..[y1] (screen Soul px) is in view; see [KitCanvas.reveal]. */
    protected open fun kitReveal(y0: Int, y1: Int) {}

    /** The last [KitCanvas.reveal] token served, so each one scrolls once. */
    private var revealed: String? = null

    protected open fun kitPlayerModel(uuidUndashed: String, name: String, x0: Int, y0: Int, x1: Int, y1: Int, armor: List<ItemStack?>?): Int = PlayerModel.UNAVAILABLE

    /** Starts a frame: clears regions, records the pointer in Soul px and the clock. */
    protected fun beginFrame(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        regions.clear()
        scrollTargets.clear()
        frame = g
        pendingItem = null
        now = System.currentTimeMillis()
        val s = SoulUi.factor()
        mx = (mouseX / s).toInt()
        my = (mouseY / s).toInt()
        rawMouseX = mouseX.toFloat()
        rawMouseY = mouseY.toFloat()
    }

    /** Ends a frame (after the pose pop): the pending vanilla item tooltip. */
    protected fun endFrame(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        pendingItem?.let { if (!kitBlocked()) g.setTooltipForNextFrame(Minecraft.getInstance().font, it, mouseX, mouseY) }
        frame = null
    }

    // ------------------------------------------------------------------ drawing helpers

    protected fun hit(x0: Int, y0: Int, x1: Int, y1: Int): Boolean = mx in x0 until x1 && my in y0 until y1

    protected fun pipeline(corner: Int): RenderPipeline? = when (corner) {
        Corner.SMALL -> SoulRenderPipelines.ROUND_SMALL
        Corner.MEDIUM -> SoulRenderPipelines.ROUND_MEDIUM
        Corner.LARGE -> SoulRenderPipelines.ROUND_LARGE
        Corner.PILL -> SoulRenderPipelines.ROUND_PILL
        else -> null
    }

    protected fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {
        val g = frame ?: return
        if (x1 <= x0 || y1 <= y0 || color ushr 24 == 0) return
        val p = pipeline(corner)
        if (p == null) g.fill(x0, y0, x1, y1, color) else roundRect(g, x0, y0, x1, y1, color, p)
    }

    /** The outer radius of [corner] for [SoulPanel] (the same radii as [pipeline]'s shapes). */
    protected fun radius(corner: Int): Float = when (corner) {
        Corner.SMALL -> SoulPanel.SMALL
        Corner.MEDIUM -> SoulPanel.MEDIUM
        Corner.LARGE -> SoulPanel.LARGE
        Corner.PILL -> SoulPanel.PILL
        else -> 0f
    }

    protected fun weight(bold: Boolean): MsdfFont = if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular

    protected fun txt(s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean = false) {
        val g = frame ?: return
        if (s.isEmpty()) return
        // Dark text sits on light fills (podium blocks): a drop shadow there reads as a blurred double.
        val dark = (color shr 16 and 0xFF) + (color shr 8 and 0xFF) + (color and 0xFF) < 0x90
        if (dark) SoulText.drawVCentered(g, s, x, (top + bottom) / 2, size, color, weight(bold), forceCustom = true, shadow = false, scissor = currentClip)
        else drawTextV(g, s, x, top, bottom, color, size, weight(bold))
    }

    protected fun tw(s: String, size: Float, bold: Boolean): Int = textWidth(s, size, weight(bold))

    private fun trackingPx(size: Float, tracking: Float): Float = tracking * size / MsdfFont.LEGACY_EM

    protected fun trackedW(s: String, size: Float, tracking: Float, bold: Boolean = true): Int =
        SoulText.widthTracked(s, size, weight(bold), trackingPx(size, tracking)).roundToInt()

    protected fun tracked(g: GuiGraphicsExtractor, s: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean = true) =
        SoulText.drawCenteredTracked(g, s, cx, cy, size, color, weight(bold), trackingPx(size, tracking), scissor = currentClip)

    protected fun face(g: GuiGraphicsExtractor, uuidUndashed: String, x: Int, y: Int, size: Int) {
        val profile = faces.getOrPut(uuidUndashed) { ProfileFigure.profile(uuidUndashed) }
        PlayerFaceExtractor.extractRenderState(g, profile, x, y, size)
    }

    // ------------------------------------------------------------------ the kit's canvas

    protected val canvas = ScreenCanvas()

    inner class ScreenCanvas : KitCanvas {
        override var viewTop = 0
        override var viewBottom = 0
        override val nowMs: Long get() = now
        override val state: ViewState get() = viewState

        /**
         * While the body paints, a draw entirely outside the visible band is dropped: the scissor would
         * discard it anyway, and every submitted element costs vanilla a scan of its layer.
         */
        var culling = false

        private fun outside(y0: Int, y1: Int): Boolean = culling && (y1 <= viewTop || y0 >= viewBottom)

        override fun width(text: String, size: Float, bold: Boolean): Int = tw(text, size, bold)

        override fun trackedWidth(text: String, size: Float, tracking: Float, bold: Boolean): Int = trackedW(text, size, tracking, bold)

        override fun rect(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, corner: Int) {
            if (!outside(y0, y1)) this@KitScreen.rect(x0, y0, x1, y1, color, corner)
        }

        override fun panel(x0: Int, y0: Int, x1: Int, y1: Int, fill: Int, border: Int, corner: Int) {
            val g = frame ?: return
            if (x1 <= x0 || y1 <= y0 || outside(y0, y1)) return
            this@KitScreen.panel(g, x0, y0, x1, y1, fill, border, radius(corner))
        }

        override fun halo(x0: Int, y0: Int, x1: Int, y1: Int, color: Int, out: Int, corner: Int) {
            val g = frame ?: return
            if (x1 <= x0 || y1 <= y0 || color ushr 24 == 0 || outside(y0 - out, y1 + out)) return
            this@KitScreen.panel(g, x0 - out, y0 - out, x1 + out, y1 + out, color, 0, SoulPanel.outer(radius(corner), out.toFloat()))
        }

        override fun slice(x0: Int, y0: Int, x1: Int, y1: Int, corner: Int, sx0: Int, sy0: Int, sx1: Int, sy1: Int, color: Int) {
            val g = frame ?: return
            if (outside(sy0, sy1)) return
            this@KitScreen.slice(g, x0, y0, x1, y1, radius(corner), sx0, sy0, sx1, sy1, color)
        }

        override fun text(text: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, bold: Boolean) {
            if (!outside(top, bottom)) txt(text, x, top, bottom, color, size, bold)
        }

        override fun tracked(text: String, cx: Int, cy: Int, color: Int, size: Float, tracking: Float, bold: Boolean) {
            if (outside(cy - size.toInt(), cy + size.toInt())) return
            frame?.let { tracked(it, text, cx, cy, color, size, tracking, bold) }
        }

        override fun icon(name: String, x: Int, y: Int, size: Int, color: Int) {
            if (outside(y, y + size)) return
            frame?.let { drawIcon(it, name, x, y, size, color) }
        }

        override fun item(stack: ItemStack, x: Int, y: Int, size: Int, count: Boolean) {
            if (outside(y, y + size)) return
            val g = frame ?: return
            g.pose().pushMatrix()
            g.pose().translate(x.toFloat(), y.toFloat())
            g.pose().scale(size / 16f, size / 16f)
            g.fakeItem(stack, 0, 0)
            if (count) g.itemDecorations(Minecraft.getInstance().font, stack, 0, 0)
            g.pose().popMatrix()
        }

        override fun layer() {
            frame?.nextStratum()
        }

        override fun face(uuidUndashed: String, x: Int, y: Int, size: Int) {
            frame?.let { face(it, uuidUndashed, x, y, size) }
        }

        override fun hovered(x0: Int, y0: Int, x1: Int, y1: Int): Boolean =
            !kitBlocked() && hit(x0, y0, x1, y1) && my >= bodyRect[1] && my < bodyRect[3] && mx >= bodyRect[0] && mx < bodyRect[2]

        override fun onClick(x0: Int, y0: Int, x1: Int, y1: Int, action: () -> Unit) = clippedRegion(x0, y0, x1, y1, bodyRect, action)

        override fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String?) {
            if (hovered(x0, y0, x1, y1)) this@KitScreen.tooltip(x0, y0, x1, y1, lines, key)
        }

        override fun itemTooltip(stack: ItemStack) {
            if (!kitBlocked()) pendingItem = stack
        }

        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String) = field(key, x0, y0, x1, y1, placeholder, 0)

        override fun field(key: String, x0: Int, y0: Int, x1: Int, y1: Int, placeholder: String, trailing: Int) {
            val g = frame ?: return
            val f = viewState.field(key)
            val icon = kitFieldIcon(key)
            drawTextField(g, x0, y0, x1, y1, f, placeholder, viewport = bodyRect, inset = if (icon != null) 20 else 6, background = KitStyle.FIELD_BG, border = KitStyle.FIELD_EDGE, trailing = trailing) {
                onKitFieldFocus()
                viewState.blurFields()
                f.focused = true
            }
            if (icon != null) drawIcon(g, icon, x0 + 6, y0 + (y1 - y0 - 11) / 2, 11, if (f.focused) Theme.TEXT else Theme.TEXT_DIM)
        }

        override fun openSection(id: String) = kitOpenSection(id)

        override fun reveal(token: String, y0: Int, y1: Int) {
            if (token == revealed) return
            revealed = token
            kitReveal(y0, y1)
        }

        override fun gradient(x0: Int, y0: Int, x1: Int, y1: Int, top: Int, bottom: Int, corner: Int) {
            val g = frame ?: return
            val p = pipeline(corner)
            if (p == null) {
                g.fillGradient(x0, y0, x1, y1, top, bottom)
                return
            }
            // Rounded ends in the end colors, the vertical gradient between them.
            val r = min(6, (y1 - y0) / 2)
            roundRect(g, x0, y0, x1, y0 + 2 * r, top, p)
            roundRect(g, x0, y1 - 2 * r, x1, y1, bottom, p)
            g.fillGradient(x0, y0 + r, x1, y1 - r, top, bottom)
        }

        override fun playerModel(uuidUndashed: String, name: String, x0: Int, y0: Int, x1: Int, y1: Int, armor: List<ItemStack?>?): Int =
            kitPlayerModel(uuidUndashed, name, x0, y0, x1, y1, armor)
    }
}
