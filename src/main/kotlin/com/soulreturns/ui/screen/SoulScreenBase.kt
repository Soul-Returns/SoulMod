package com.soulreturns.ui.screen

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.MsdfTextRenderState
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.SoulUi
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.navigation.ScreenRectangle
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import org.lwjgl.glfw.GLFW
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Shared base for **every** Soul screen: the click-region hit test, the SDF rounded-rect and MSDF
 * text draw helpers, scissor-aware clipping, scroll clamping, a button, and the centered result /
 * confirm modal. Nothing here knows anything about the config model — the config-specific widgets
 * (option rows, toggle, text field, slider, color picker, dropdown, keybind capture) live one level
 * down in [com.soulreturns.config.gui.ConfigScreenBase].
 *
 * ### The coordinate system
 * Everything is drawn in **Soul-logical** coordinates. A subclass's `extractRenderState` starts with
 *
 * ```kotlin
 * val s = SoulUi.factor()
 * val uiW = (width / s).toInt()
 * val uiH = (height / s).toInt()
 * val mx = (mouseX / s).toInt()
 * val my = (mouseY / s).toInt()
 * g.pose().pushMatrix()
 * g.pose().scale(s, s)
 * ```
 *
 * and ends with `g.pose().popMatrix()`. So the screen lays out at `screenSize / factor` and renders
 * at [SoulUi.scale] regardless of Minecraft's own GUI Scale. Mouse input arriving here is divided
 * back by the same factor ([mouseClicked] does it; a subclass overriding `mouseScrolled` or
 * `mouseDragged` must do it too). A consequence worth knowing before laying anything out:
 * **card sizes must be relative to the composable viewport** (`uiW * 0.9f`, capped) and must never
 * have a fixed minimum — at scale 6 the viewport is small and a fixed minimum overflows it.
 *
 * ### Two traps that have each cost a debugging session
 *
 * **Overlay gotcha — draw popups LAST.** A popup (dropdown, color picker, modal, context menu) must
 * be the final draw in `extractRenderState`, after all the content. Two reasons: the GUI renderer
 * draws in submission order, and [region] is registered in draw order while the hit test walks
 * [regions] in *reverse*, so a later-registered region wins. Get it wrong and the symptom is
 * a control that highlights on click with no popup ever appearing.
 *
 * **Clipping gotcha — the GL scissor is not automatic.** [GuiGraphicsExtractor.fill]/`.text` capture
 * the extractor's scissor themselves; our custom [RoundRectRenderState] and [MsdfTextRenderState] do
 * **not**. A scrollable region must therefore be wrapped in [beginClip]/[endClip], which enables the
 * scissor *and* records the rect so [roundRect] and the `drawText*` helpers thread it through.
 * Without it, scrolled rows bleed over the header and footer.
 *
 * One more, for anything hand-rolling a render state: a [net.minecraft.client.gui.render.state.GuiElementRenderState]
 * with a **null `bounds()` is culled** — it looks exactly like a draw call that silently did nothing.
 */
abstract class SoulScreenBase(title: Component) : Screen(title) {
    protected companion object {
        // Soul scrollbar (spec `mocks/config-menu/`, SB_* there).
        const val SB_THICK = 3
        const val SB_INSET = 2
        const val SB_MIN_THUMB = 20
        const val SB_HIT = 9
        const val SB_PAGE = 0.9f
        const val ICON_TEX = 64

        // Soul tooltip (spec `mocks/inventory-buttons-editor/`, STIP_* there).
        const val TIP_DELAY_MS = 400L
        const val TIP_MAX_W = 220
        const val TIP_GAP = 4
        const val TIP_PAD_X = 6
        const val TIP_PAD_Y = 2
        const val TIP_LINE_H = 12
        private val TIP_BG = 0xFF242424.toInt()
        private val TIP_EDGE = 0xFF333333.toInt()
        private val iconIds = HashMap<String, net.minecraft.resources.Identifier>()
    }

    protected class Region(
        val x0: Int,
        val y0: Int,
        val x1: Int,
        val y1: Int,
        val onClick: () -> Unit,
    )

    /**
     * Clickable rects for the current frame, in draw order. Rebuilt every frame — a subclass's
     * `extractRenderState` must start with `regions.clear()`. The hit test walks this **in reverse**
     * so the last thing drawn is the first thing clicked (see the overlay gotcha above).
     */
    protected val regions = ArrayList<Region>()

    /** While true, [region] drops registrations — used to make a disabled row non-interactive. */
    protected var suppressRegions = false

    // Shared typography: the legacy 16/13/11/10 em sizes in SoulText's vanilla-relative scale, so
    // the default family (Baloo 2) keeps the size these layouts were tuned on.
    protected val fTitle = 16f * MsdfFont.LEGACY_EM
    protected val fHeading = 13f * MsdfFont.LEGACY_EM
    protected val fBody = 11f * MsdfFont.LEGACY_EM
    protected val fCaption = 10f * MsdfFont.LEGACY_EM
    protected val font = fBody

    // Switch size (config rows, the editor's rows).
    protected val toggleW = 36
    protected val toggleH = 18

    /** Last known cursor position in Soul-logical coordinates (updated by the input handlers). */
    protected var lastMouseX = 0
    protected var lastMouseY = 0

    // Current clip rect (device coords) for our custom SDF/text elements — they don't auto-capture
    // the extractor's GL scissor like fill()/text() do, so we thread it through explicitly. Set by
    // beginClip/endClip; a scrollable body clips its rows to itself instead of overflowing the card.
    private var clip: ScreenRectangle? = null

    // Centered result modal (see [drawAlertModal]): closed unless [alertOpen]. While [alertBusy] a
    // background task is running — no Close button, and Escape/outside clicks don't dismiss it; the
    // task flips it to the result via [showAlert]. A full-screen catch region makes it modal.
    private var alertOpen = false
    private var alertTitle = ""
    private var alertMessage = ""
    private var alertBusy = false
    private var alertSuccess = false

    // Confirm mode (see [showConfirm]): non-null label => two buttons instead of one, and the action
    // runs only via the confirm button. Escape / Cancel / an outside click drop the action unrun,
    // which is what makes "closed without deciding" mean "no".
    private var alertConfirmLabel: String? = null
    private var alertOnConfirm: (() -> Unit)? = null

    // Optional check row of a confirm (see the [showConfirm] overload with a check label): its state
    // reaches the confirm action.
    private var alertCheckLabel: String? = null
    private var alertChecked = false

    // Prompt mode (see [showPrompt]): non-null field => a text field, an inline status line and
    // Cancel + submit. Enter submits, Escape cancels; [setPromptState] shows progress or an error
    // without closing, so a wrong answer can be corrected in place.
    private var promptField: SoulTextField? = null
    private var promptSubmitLabel = ""
    private var promptOnSubmit: ((String) -> Unit)? = null
    private var promptError: String? = null

    // ---------------------------------------------------------------- input

    override fun mouseClicked(
        click: MouseButtonEvent,
        doubleClick: Boolean,
    ): Boolean {
        val s = SoulUi.factor()
        val mx = (click.x() / s).roundToInt()
        val my = (click.y() / s).roundToInt()
        lastMouseX = mx
        lastMouseY = my
        closeTooltip()
        if (interceptClick(click)) return true
        for (r in regions.asReversed()) {
            if (mx in r.x0..r.x1 && my in r.y0..r.y1) {
                r.onClick()
                return true
            }
        }
        // Clicked empty space → let the screen drop its focus.
        onEmptyClick()
        return super.mouseClicked(click, doubleClick)
    }

    /**
     * Hook for a click that must pre-empt the regions entirely (keybind capture is the one case).
     * Return true to consume it. Coordinates are already in [lastMouseX]/[lastMouseY].
     */
    protected open fun interceptClick(click: MouseButtonEvent): Boolean = false

    /** Called when a click hit no region — screens drop their focus (an editor, a search field). */
    protected open fun onEmptyClick() {}

    override fun keyPressed(event: KeyEvent): Boolean {
        // A result modal is up: swallow all keys (Escape closes it, unless a task is still running)
        // so input can't leak to the screen behind it. A prompt's field gets the editing keys.
        if (alertOpen) {
            val field = promptField
            if (alertBusy) return true
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
                closeAlert()
            } else if (field != null) {
                if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
                    submitPrompt()
                } else {
                    field.keyPressed(event, minecraft?.keyboardHandler)
                }
            }
            return true
        }
        return super.keyPressed(event)
    }

    /**
     * Typed characters go to an open prompt's field and nowhere else while any modal is up. A
     * subclass overriding `charTyped` must call this first whenever [alertModalOpen].
     */
    override fun charTyped(event: CharacterEvent): Boolean {
        if (!alertOpen) return super.charTyped(event)
        if (!alertBusy) promptField?.charTyped(event)
        return true
    }

    // ---------------------------------------------------------------- draw helpers

    /**
     * Draw a rounded, anti-aliased rect via an SDF pipeline. Copies the extractor's current pose
     * (carrying the SoulUi scale) so rounded rects scale identically to fill/text.
     */
    protected fun roundRect(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
        pipeline: RenderPipeline = SoulRenderPipelines.ROUND_MEDIUM,
    ) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(pipeline, TextureSetup.noTexture(), Matrix3x2f(g.pose()), x0, y0, x1, y1, color, clip),
        )
    }

    /**
     * Enable a scissor AND record it (in device coords) so our custom SDF/text elements clip to it
     * too — [GuiGraphicsExtractor.fill]/`.text` capture the GL scissor automatically, but our custom
     * [RoundRectRenderState]/[MsdfTextRenderState] must be handed the rect. Pair with [endClip].
     */
    protected fun beginClip(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        g.enableScissor(x0, y0, x1, y1)
        clip = ScreenRectangle(x0, y0, x1 - x0, y1 - y0).transformMaxBounds(Matrix3x2f(g.pose()))
    }

    protected fun endClip(g: GuiGraphicsExtractor) {
        g.disableScissor()
        clip = null
    }

    /** The clip rect [beginClip] set (null outside one), for custom draws that take a scissor. */
    protected val currentClip: ScreenRectangle? get() = clip

    // ---------------------------------------------------------------- icons

    /**
     * A menu icon `assets/soul/textures/gui/icons/<name>.png` (64 px, white strokes, linear
     * filtering; generated from `mocks/config-menu/` by `scripts/config_icons.mjs`) drawn [size]
     * px square at ([x], [y]), tinted [color]. The `flame` logo keeps its own colors (pass white).
     */
    protected fun drawIcon(g: GuiGraphicsExtractor, name: String, x: Int, y: Int, size: Int, color: Int) {
        val id = iconIds.getOrPut(name) { net.minecraft.resources.Identifier.fromNamespaceAndPath("soul", "textures/gui/icons/$name.png") }
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, x, y, 0f, 0f, size, size, ICON_TEX, ICON_TEX, ICON_TEX, ICON_TEX, color)
    }

    // ---------------------------------------------------------------- scrollbars

    /**
     * One scrollable area's state: [pos] in px, [content] and [view] lengths along its axis (set by
     * the screen each frame while it lays the area out).
     */
    class ScrollState {
        var pos = 0f
        var content = 0
        var view = 0
        val max: Int get() = max(0, content - view)

        fun clamp() {
            pos = pos.coerceIn(0f, max.toFloat())
        }

        fun scrollBy(delta: Float) {
            pos += delta
            clamp()
        }

        /** Scrolls the least so that [a]..[b] (content coordinates) is in view, with [pad] around it. */
        fun reveal(a: Int, b: Int, pad: Int = 0) {
            if (a - pad < pos) pos = (a - pad).toFloat() else if (b + pad > pos + view) pos = (b + pad - view).toFloat()
            clamp()
        }
    }

    /** Where the wheel scrolls this frame: areas in draw order, hit-tested in reverse. */
    protected class ScrollTarget(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val state: ScrollState, val horizontal: Boolean)

    protected val scrollTargets = ArrayList<ScrollTarget>()

    private var scrollDrag: ScrollState? = null
    private var scrollDragHorizontal = false
    private var scrollDragTrack = 0
    private var scrollDragThumb = 0
    private var scrollDragGrab = 0

    /** The wheel target under ([x], [y]), topmost first. */
    protected fun scrollTargetAt(x: Int, y: Int): ScrollTarget? = scrollTargets.asReversed().firstOrNull { x in it.x0..it.x1 && y in it.y0..it.y1 }

    /**
     * Registers [st] as the wheel target of the viewport [x0]..[x1] × [y0]..[y1] and draws its Soul
     * scrollbar (spec `mocks/config-menu/`): never fading, none while the content fits; a 3 px track
     * (#2a2a2a) and thumb (#555, hover #777, dragging accent), 2 px inset, thumb at least 20 px, a 9 px
     * hit strip. Drag the thumb; a click on the track pages by 90 % of the view. Vertical bars sit
     * inside the viewport's right edge; a horizontal one sits 4 px under it (tab strips).
     */
    protected fun drawScrollbar(g: GuiGraphicsExtractor, mx: Int, my: Int, st: ScrollState, x0: Int, y0: Int, x1: Int, y1: Int, horizontal: Boolean = false) {
        scrollTargets += ScrollTarget(x0, y0, x1, y1, st, horizontal)
        st.clamp()
        if (st.content <= st.view + 1) return
        val track = (if (horizontal) x1 - x0 else y1 - y0) - SB_INSET * 2
        if (track <= SB_MIN_THUMB) return
        val thumb = max(SB_MIN_THUMB, (track.toLong() * st.view / max(1, st.content)).toInt())
        val at = ((track - thumb) * st.pos / max(1, st.max)).roundToInt()
        val hx0: Int
        val hy0: Int
        val hx1: Int
        val hy1: Int
        if (horizontal) {
            hx0 = x0 + SB_INSET; hx1 = hx0 + track; hy0 = y1 + 4; hy1 = hy0 + SB_HIT
        } else {
            hx1 = x1 - SB_INSET; hx0 = hx1 - SB_HIT; hy0 = y0 + SB_INSET; hy1 = hy0 + track
        }
        val dragging = scrollDrag === st
        val hover = mx in hx0..hx1 && my in hy0..hy1
        val thumbColor = if (dragging) Theme.ACCENT else if (hover) 0xFF777777.toInt() else 0xFF555555.toInt()
        if (horizontal) {
            roundRect(g, hx0, hy0 + 3, hx1, hy0 + 3 + SB_THICK, Theme.SEPARATOR, SoulRenderPipelines.ROUND_PILL)
            roundRect(g, hx0 + at, hy0 + 3, hx0 + at + thumb, hy0 + 3 + SB_THICK, thumbColor, SoulRenderPipelines.ROUND_PILL)
        } else {
            roundRect(g, hx1 - SB_THICK, hy0, hx1, hy1, Theme.SEPARATOR, SoulRenderPipelines.ROUND_PILL)
            roundRect(g, hx1 - SB_THICK, hy0 + at, hx1, hy0 + at + thumb, thumbColor, SoulRenderPipelines.ROUND_PILL)
        }
        val trackStart = if (horizontal) hx0 else hy0
        region(hx0, hy0, hx1, hy1) {
            val c = if (horizontal) lastMouseX else lastMouseY
            if (c in trackStart + at..trackStart + at + thumb) {
                scrollDrag = st
                scrollDragHorizontal = horizontal
                scrollDragTrack = track
                scrollDragThumb = thumb
                scrollDragGrab = c - (trackStart + at) + trackStart
            } else {
                st.scrollBy(Math.signum((c - (trackStart + at + thumb / 2)).toFloat()) * st.view * SB_PAGE)
            }
        }
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val st = scrollDrag ?: return super.mouseDragged(click, dragX, dragY)
        val s = SoulUi.factor()
        val c = ((if (scrollDragHorizontal) click.x() else click.y()) / s).roundToInt()
        st.pos = (c - scrollDragGrab).toFloat() / max(1, scrollDragTrack - scrollDragThumb) * st.max
        st.clamp()
        return true
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        scrollDrag = null
        return super.mouseReleased(click)
    }

    protected fun drawText(
        g: GuiGraphicsExtractor,
        s: String,
        x: Int,
        y: Int,
        color: Int,
        size: Float = font,
        weight: MsdfFont = MsdfFonts.menuRegular,
    ) = SoulText.draw(g, s, x, y, size, color, weight, forceCustom = true, scissor = clip)

    protected fun drawTextCentered(
        g: GuiGraphicsExtractor,
        s: String,
        cx: Int,
        y: Int,
        color: Int,
        size: Float = font,
        weight: MsdfFont = MsdfFonts.menuRegular,
    ) = SoulText.drawCentered(g, s, cx, y, size, color, weight, forceCustom = true, scissor = clip)

    /** Draw text vertically centered between [top] and [bottom] (cap-height centered). */
    protected fun drawTextV(
        g: GuiGraphicsExtractor,
        s: String,
        x: Int,
        top: Int,
        bottom: Int,
        color: Int,
        size: Float = font,
        weight: MsdfFont = MsdfFonts.menuRegular,
    ) = SoulText.drawVCentered(g, s, x, (top + bottom) / 2, size, color, weight, forceCustom = true, scissor = clip)

    protected fun textWidth(
        s: String,
        size: Float = font,
        weight: MsdfFont = MsdfFonts.menuRegular,
    ): Int = SoulText.width(s, size, weight, forceCustom = true)

    /**
     * Greedy word-wrap [text] (honoring embedded newlines) to lines that each fit within [maxW] px
     * at [size]. A single word wider than the line is hard-broken mid-word.
     */
    protected fun wrapText(text: String, maxW: Int, size: Float = font): List<String> {
        if (text.isEmpty()) return emptyList()
        val out = ArrayList<String>()
        for (paragraph in text.split("\n")) {
            if (paragraph.isEmpty()) {
                out.add("")
                continue
            }
            var line = ""
            for (word in paragraph.split(" ")) {
                val candidate = if (line.isEmpty()) word else "$line $word"
                if (textWidth(candidate, size) <= maxW) {
                    line = candidate
                    continue
                }
                if (line.isNotEmpty()) out.add(line)
                if (textWidth(word, size) > maxW) {
                    // Hard-break a single word that's wider than the line.
                    var chunk = ""
                    for (ch in word) {
                        if (chunk.isNotEmpty() && textWidth(chunk + ch, size) > maxW) {
                            out.add(chunk)
                            chunk = ch.toString()
                        } else {
                            chunk += ch
                        }
                    }
                    line = chunk
                } else {
                    line = word
                }
            }
            out.add(line)
        }
        return out
    }

    /** Greedy wrap of colored words: each phrase splits at spaces; a line is a list of (word, color). */
    protected fun wrapWords(words: List<Pair<String, Int>>, maxW: Int, size: Float): List<List<Pair<String, Int>>> {
        val out = ArrayList<List<Pair<String, Int>>>()
        var line = ArrayList<Pair<String, Int>>()
        var w = 0
        val space = textWidth(" ", size)
        for ((phrase, color) in words) {
            for (word in phrase.split(" ")) {
                val ww = textWidth(word, size)
                if (line.isNotEmpty() && w + space + ww > maxW) {
                    out += line
                    line = ArrayList()
                    w = 0
                }
                w += (if (line.isEmpty()) 0 else space) + ww
                line += word to color
            }
        }
        if (line.isNotEmpty()) out += line
        return out
    }

    /** [text] cut to fit [maxW] px at [size], with a trailing ellipsis when shortened. */
    protected fun ellipsize(
        text: String,
        maxW: Int,
        size: Float = font,
        weight: MsdfFont = MsdfFonts.menuRegular,
    ): String {
        if (textWidth(text, size, weight) <= maxW) return text
        var end = text.length
        while (end > 0 && textWidth(text.substring(0, end) + "…", size, weight) > maxW) end--
        return text.substring(0, end).trimEnd() + "…"
    }

    private class RichTok(val text: String, val style: Int, val w: Int, val spaceBefore: Boolean)

    /**
     * Wraps [RichText] [spans] into [x0]..[x1] ([lineH] per line, words kept whole): plain in
     * [color], bold as white semibold, `code` as an unbreakable chip at 9/10 size. Draws only when
     * [g] is non-null, so the same call measures. Returns the height.
     */
    protected fun drawRich(g: GuiGraphicsExtractor?, spans: List<RichText.Span>, x0: Int, y0: Int, x1: Int, size: Float, color: Int, lineH: Int): Int {
        val semi = MsdfFonts.menuSemibold
        val codeSize = size * 0.905f
        val toks = ArrayList<RichTok>()
        var space = false
        for (sp in spans) {
            if (sp.style == RichText.CODE) {
                toks += RichTok(sp.text, sp.style, textWidth(sp.text, codeSize) + 6, space)
                space = false
                continue
            }
            for ((i, part) in sp.text.split(' ').withIndex()) {
                if (i > 0) space = true
                if (part.isEmpty()) continue
                val w = if (sp.style == RichText.BOLD) textWidth(part, size, semi) else textWidth(part, size)
                toks += RichTok(part, sp.style, w, space)
                space = false
            }
        }
        val spaceW = textWidth(" ", size)
        var lines = 1
        var x = x0
        var y = y0
        for (t in toks) {
            val gap = if (t.spaceBefore && x > x0) spaceW else 0
            if (x > x0 && x + gap + t.w > x1) {
                lines++
                x = x0
                y += lineH
            } else {
                x += gap
            }
            if (g != null) {
                if (t.style == RichText.CODE) {
                    roundRect(g, x, y + 1, x + t.w, y + lineH - 1, 0xFF262626.toInt(), SoulRenderPipelines.ROUND_SMALL)
                    drawTextV(g, t.text, x + 3, y, y + lineH, 0xFFDDDDDD.toInt(), codeSize)
                } else if (t.style == RichText.BOLD) {
                    drawTextV(g, t.text, x, y, y + lineH, 0xFFFFFFFF.toInt(), size, semi)
                } else {
                    drawTextV(g, t.text, x, y, y + lineH, color, size)
                }
            }
            x += t.w
        }
        return lines * lineH
    }

    protected fun drawButton(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        label: String,
        accent: Boolean = false,
        onClick: () -> Unit,
    ) {
        val hovered = mx in x0..x1 && my in y0..y1
        val bg =
            if (accent) {
                if (hovered) Theme.ACCENT else Theme.ACCENT_DIM
            } else {
                if (hovered) Theme.CONTROL_HOVER else Theme.PANEL_HOVER
            }
        roundRect(g, x0, y0, x1, y1, bg, SoulRenderPipelines.ROUND_SMALL)
        SoulText.drawCenteredOn(g, label, (x0 + x1) / 2, (y0 + y1) / 2, fBody, Theme.TEXT, forceCustom = true, scissor = clip)
        region(x0, y0, x1, y1, onClick)
    }

    /** Pill switch at ([x], [y]), [w]×[h]: accent track when [on], knob = height - 4. */
    protected fun drawToggle(
        g: GuiGraphicsExtractor,
        x: Int,
        y: Int,
        on: Boolean,
        w: Int = toggleW,
        h: Int = toggleH,
        enabled: Boolean = true,
        onClick: () -> Unit,
    ) {
        val track = if (on) Theme.ACCENT else Theme.PANEL_HOVER
        roundRect(g, x, y, x + w, y + h, if (enabled) track else (track and 0xFFFFFF) or 0x66000000, SoulRenderPipelines.ROUND_PILL)
        val knobD = h - 4 // pad 2 each side
        val knobX = if (on) x + w - 2 - knobD else x + 2
        roundRect(g, knobX, y + 2, knobX + knobD, y + 2 + knobD, if (enabled) Theme.TEXT else Theme.TEXT_FAINT, SoulRenderPipelines.ROUND_PILL)
        if (enabled) region(x, y, x + w, y + h, onClick)
    }

    /** A small right-pointing chevron (two 1 px strokes). */
    protected fun caretRight(g: GuiGraphicsExtractor, cx: Int, cy: Int, color: Int) {
        for (i in 0..3) {
            g.fill(cx - 2 + i, cy - 4 + i, cx - 1 + i, cy - 3 + i, color)
            g.fill(cx - 2 + i, cy + 3 - i, cx - 1 + i, cy + 4 - i, color)
        }
    }

    /**
     * A single-line text box: border, field background, the text clipped to the field from the left
     * (so the tail being typed stays visible) and a blinking caret while [focused]. Holds no state —
     * the caller owns the buffer and the focus flag, and [onClick] is where it takes focus.
     */
    protected fun drawTextBox(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        text: String,
        placeholder: String,
        focused: Boolean,
        onClick: () -> Unit,
    ) {
        roundRect(g, x0, y0, x1, y1, if (focused) Theme.ACCENT else Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_SMALL) // border
        roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
        val inner = x0 + 6
        if (text.isEmpty() && !focused) {
            drawTextV(g, placeholder, inner, y0, y1, Theme.TEXT_FAINT, fBody)
        } else {
            val maxW = x1 - x0 - 12
            var shown = text
            while (shown.isNotEmpty() && textWidth(shown, fBody) > maxW) shown = shown.substring(1)
            drawTextV(g, shown, inner, y0, y1, Theme.TEXT, fBody)
            if (focused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                val cx = inner + textWidth(shown, fBody)
                g.fill(cx, y0 + 3, cx + 1, y1 - 3, Theme.TEXT)
            }
        }
        region(x0, y0, x1, y1, onClick)
    }

    /**
     * A full single-line editor bound to [field]: selection highlight, caret, horizontal scroll that
     * keeps the caret in view, and click-to-place-caret. [viewport] (x0, y0, x1, y1) clips the hit
     * region for a field inside a scrolled body; [onFocus] runs after the field took focus. [inset] is
     * the text's left padding (a field with a fixed prefix drawn by the caller widens it). A
     * [SoulTextField.masked] field draws one bullet per char, so every index still lines up.
     */
    protected fun drawTextField(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        field: SoulTextField,
        placeholder: String = "",
        viewport: IntArray? = null,
        inset: Int = 6,
        background: Int = Theme.FIELD_BG,
        border: Int = Theme.FIELD_BORDER,
        onFocus: () -> Unit = {},
    ) {
        val text = if (field.masked) "•".repeat(field.text.length) else field.text
        roundRect(g, x0, y0, x1, y1, if (field.focused) Theme.ACCENT else border, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, background, SoulRenderPipelines.ROUND_SMALL)
        val available = (x1 - x0 - inset - 6).coerceAtLeast(1)
        field.visibleStart = field.visibleStart.coerceIn(0, field.cursor)
        while (field.visibleStart < field.cursor && textWidth(text.substring(field.visibleStart, field.cursor)) > available - 1) {
            field.visibleStart = text.offsetByCodePoints(field.visibleStart, 1)
        }
        var end = field.visibleStart
        while (end < text.length) {
            val next = text.offsetByCodePoints(end, 1)
            if (textWidth(text.substring(field.visibleStart, next)) > available) break
            end = next
        }
        if (text.isEmpty() && !field.focused) {
            drawTextV(g, placeholder, x0 + inset, y0, y1, Theme.TEXT_FAINT)
        } else {
            val selectionStart = max(minOf(field.cursor, field.anchor), field.visibleStart)
            val selectionEnd = minOf(max(field.cursor, field.anchor), end)
            if (field.focused && selectionEnd > selectionStart) {
                val sx = x0 + inset + textWidth(text.substring(field.visibleStart, selectionStart))
                val ex = x0 + inset + textWidth(text.substring(field.visibleStart, selectionEnd))
                g.fill(sx, y0 + 3, ex, y1 - 3, Theme.ACCENT_DIM)
            }
            drawTextV(g, text.substring(field.visibleStart, end), x0 + inset, y0, y1, Theme.TEXT)
            if (field.focused && (System.currentTimeMillis() / 500) % 2 == 0L) {
                val cx = x0 + inset + textWidth(text.substring(field.visibleStart, field.cursor))
                g.fill(cx, y0 + 3, cx + 1, y1 - 3, Theme.TEXT)
            }
        }
        val shownEnd = end
        val place = {
            field.focused = true
            val target = lastMouseX - x0 - inset
            var position = field.visibleStart
            while (position < shownEnd) {
                val next = text.offsetByCodePoints(position, 1)
                val before = textWidth(text.substring(field.visibleStart, position))
                val after = textWidth(text.substring(field.visibleStart, next))
                if (target < (before + after) / 2) break
                position = next
            }
            field.cursor = position
            field.anchor = position
            onFocus()
        }
        if (viewport == null) region(x0, y0, x1, y1, place) else clippedRegion(x0, y0, x1, y1, viewport, place)
    }

    /** [drawButton] whose hit region is cut to [viewport], for buttons inside a scrolled body. */
    protected fun clippedButton(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        label: String,
        viewport: IntArray,
        accent: Boolean = false,
        action: () -> Unit,
    ) {
        val suppressed = suppressRegions
        suppressRegions = true
        drawButton(g, mx, my, x0, y0, x1, y1, label, accent, action)
        suppressRegions = suppressed
        clippedRegion(x0, y0, x1, y1, viewport, action)
    }

    /** Register [action] for the part of the rect that lies inside [viewport]; nothing if none does. */
    protected fun clippedRegion(x0: Int, y0: Int, x1: Int, y1: Int, viewport: IntArray, action: () -> Unit) {
        val top = max(y0, viewport[1])
        val bottom = minOf(y1, viewport[3] - 1)
        val left = max(x0, viewport[0])
        val right = minOf(x1, viewport[2] - 1)
        if (top <= bottom && left <= right) region(left, top, right, bottom, action)
    }

    protected fun region(
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        onClick: () -> Unit,
    ) {
        if (suppressRegions) return
        regions.add(Region(x0, y0, x1, y1, onClick))
    }

    // ---------------------------------------------------------------- Soul tooltip

    // Requested during the frame by the hovered element (the last request wins). Shown once the same
    // content has been hovered for TIP_DELAY_MS; a click closes it until the pointer leaves that content.
    private var tipLines: List<String>? = null
    private var tipIdentity: String? = null
    private val tipRect = IntArray(4)
    private var tipKey: String? = null
    private var tipSince = 0L
    private var tipBlocked: String? = null

    /**
     * Ask for a Soul tooltip for the hovered element at [x0]..[x1] × [y0]..[y1] (Soul px); call it only
     * while the pointer is over it. [lines] may switch color with `§7` (gray), `§8` (dark gray), `§a`
     * (green) and `§f` (white); each line wraps at [TIP_MAX_W]. [drawTooltip] draws it.
     *
     * [key] names WHAT is hovered when the lines carry a live value (a countdown): the show delay and a
     * click's close are tracked by it, not by the text, which would restart the delay at every tick and make
     * the tooltip blink. Default = the text itself.
     */
    protected fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, lines: List<String>, key: String? = null) {
        if (lines.isEmpty()) return
        tipLines = lines
        tipIdentity = key
        tipRect[0] = x0
        tipRect[1] = y0
        tipRect[2] = x1
        tipRect[3] = y1
    }

    protected fun tooltip(x0: Int, y0: Int, x1: Int, y1: Int, text: String) = tooltip(x0, y0, x1, y1, listOf(text))

    /** Close the open tooltip until the pointer leaves its element; every click does this. */
    protected fun closeTooltip() {
        tipBlocked = tipKey
    }

    /**
     * Draw this frame's requested tooltip (after the content, before dropdowns and modals) and clear
     * the request. It sits beside the element, centered under it or above it when it would leave the
     * [uiW]×[uiH] viewport, never on it. Nothing while a dropdown or a modal is open.
     */
    protected fun drawTooltip(g: GuiGraphicsExtractor, uiW: Int, uiH: Int) {
        val lines = tipLines
        tipLines = null
        val key = if (lines == null || alertOpen || openDropdownToken != null) null else tipIdentity ?: lines.joinToString("\n")
        tipIdentity = null
        if (key != tipKey) {
            tipKey = key
            tipSince = System.currentTimeMillis()
            if (key != tipBlocked) tipBlocked = null
        }
        if (lines == null || key == null || key == tipBlocked || System.currentTimeMillis() - tipSince < TIP_DELAY_MS) return
        val rows = ArrayList<List<Pair<String, Int>>>()
        for (line in lines) rows += wrapWords(tipRuns(line), TIP_MAX_W - 2 * TIP_PAD_X, fCaption).ifEmpty { listOf(emptyList()) }
        val space = textWidth(" ", fCaption)
        val textW = rows.maxOf { row -> row.sumOf { textWidth(it.first, fCaption) } + space * max(0, row.size - 1) }
        val w = minOf(TIP_MAX_W, textW + 2 * TIP_PAD_X)
        val h = rows.size * TIP_LINE_H + 2 * TIP_PAD_Y
        val y = if (tipRect[3] + TIP_GAP + h <= uiH - 2) tipRect[3] + TIP_GAP else tipRect[1] - TIP_GAP - h
        val x = ((tipRect[0] + tipRect[2]) / 2 - w / 2).coerceIn(2, max(2, uiW - w - 2))
        roundRect(g, x - 1, y - 1, x + w + 1, y + h + 1, TIP_EDGE, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x, y, x + w, y + h, TIP_BG, SoulRenderPipelines.ROUND_SMALL)
        var ly = y + TIP_PAD_Y
        for (row in rows) {
            var lx = x + TIP_PAD_X
            for ((word, color) in row) {
                drawTextV(g, word, lx, ly, ly + TIP_LINE_H, color, fCaption)
                lx += textWidth(word, fCaption) + space
            }
            ly += TIP_LINE_H
        }
    }

    /** A tooltip line split at its `§` color codes into (text, color) runs. */
    private fun tipRuns(line: String): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        var color = Theme.TEXT
        var start = 0
        var i = 0
        while (i < line.length) {
            if (line[i] == '§' && i + 1 < line.length) {
                if (i > start) out += line.substring(start, i) to color
                color = when (line[i + 1]) {
                    '7' -> 0xFFAAAAAA.toInt()
                    '8' -> 0xFF777777.toInt()
                    'a' -> 0xFF4ADE80.toInt()
                    '6' -> 0xFFFFAA00.toInt()
                    else -> Theme.TEXT
                }
                i += 2
                start = i
            } else {
                i++
            }
        }
        if (start < line.length) out += line.substring(start) to color
        return out
    }

    // ---------------------------------------------------------------- dropdown

    // The open dropdown, identified by an opaque token so any screen can own one without this class
    // knowing what it selects: the config menu passes its CycleOption, the Profile Viewer its own
    // marker object. Labels/selection/callback are captured when it opens.
    private var openDropdownToken: Any? = null
    private var dropdownRect = intArrayOf(0, 0, 0, 0)
    private var dropdownLabels: List<String> = emptyList()
    private var dropdownSelected = 0
    private var dropdownOnPick: (Int) -> Unit = {}

    protected val dropdownOpen: Boolean get() = openDropdownToken != null

    /** True when [token]'s dropdown is the open one (identity, not equality). */
    protected fun isDropdownOpen(token: Any): Boolean = openDropdownToken === token

    /**
     * Open a dropdown anchored to the button at [x0]..[x1] / [y0]..[y1], listing [labels] with
     * [selected] highlighted. [onPick] gets the chosen index and the list closes itself.
     *
     * Draw it with [drawOpenDropdown] as the LAST thing in the frame — opening only records state.
     */
    protected fun openDropdownAt(
        token: Any,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        labels: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ) {
        openDropdownToken = token
        dropdownRect = intArrayOf(x0, y0, x1, y1)
        dropdownLabels = labels
        dropdownSelected = selected
        dropdownOnPick = onPick
    }

    protected fun closeDropdown() {
        openDropdownToken = null
    }

    /** A collapsed dropdown button: bordered field, current value, and a caret. */
    protected fun drawDropdownButton(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, text: String, open: Boolean) {
        roundRect(g, x0, y0, x1, y1, if (open) Theme.ACCENT else Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, text, x0 + 6, y0, y1, Theme.TEXT, fBody)
        caret(g, x1 - 9, (y0 + y1) / 2 - 1)
    }

    /** Scanline fill of a polygon given in Soul-logical coords, at physical-pixel resolution. */
    protected fun fillPolygon(
        g: GuiGraphicsExtractor,
        points: FloatArray,
        color: Int,
    ) {
        val k = SoulUi.scale
        val n = points.size / 2
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until n) {
            minY = minOf(minY, points[i * 2 + 1] * k)
            maxY = maxOf(maxY, points[i * 2 + 1] * k)
        }
        g.pose().pushMatrix()
        g.pose().scale(1f / k, 1f / k)
        val xs = FloatArray(n)
        for (py in floor(minY).toInt() until ceil(maxY).toInt()) {
            val sy = py + 0.5f
            var count = 0
            for (i in 0 until n) {
                val ax = points[i * 2] * k
                val ay = points[i * 2 + 1] * k
                val j = (i + 1) % n
                val bx = points[j * 2] * k
                val by = points[j * 2 + 1] * k
                if ((ay <= sy && by > sy) || (by <= sy && ay > sy)) {
                    xs[count++] = ax + (sy - ay) / (by - ay) * (bx - ax)
                }
            }
            xs.sort(0, count)
            var c = 0
            while (c + 1 < count) {
                val x0 = xs[c].roundToInt()
                val x1 = xs[c + 1].roundToInt()
                if (x1 > x0) g.fill(x0, py, x1, py + 1, color)
                c += 2
            }
        }
        g.pose().popMatrix()
    }

    /** Play triangle for a round button of radius [r] centered at ([cx], [cy]). */
    protected fun playTriangle(g: GuiGraphicsExtractor, cx: Float, cy: Float, r: Float, color: Int) =
        fillPolygon(g, floatArrayOf(cx - r * 0.3f, cy - r * 0.48f, cx + r * 0.5f, cy, cx - r * 0.3f, cy + r * 0.48f), color)

    /** A small downward triangle (▾ isn't in the MSDF atlas, so draw it from fill strips). */
    protected fun caret(g: GuiGraphicsExtractor, cx: Int, cy: Int) {
        for (i in 0..2) g.fill(cx - (2 - i), cy + i, cx + (2 - i) + 1, cy + i + 1, Theme.TEXT_DIM)
    }

    /**
     * Draw the open dropdown's item list (if any) over the [uiW]×[uiH] viewport. Call LAST in the
     * subclass render so it overlays everything; a full-screen catch region closes it on an outside
     * click.
     */
    protected fun drawOpenDropdown(g: GuiGraphicsExtractor, uiW: Int, uiH: Int, mx: Int, my: Int) {
        if (openDropdownToken == null) return
        region(0, 0, uiW, uiH) { closeDropdown() }

        val ax0 = dropdownRect[0]
        val ax1 = dropdownRect[2]
        val itemH = 16
        val listH = dropdownLabels.size * itemH + 2
        // Below the button, or flipped above if it would run off the bottom.
        val top = if (dropdownRect[3] + 1 + listH <= uiH) dropdownRect[3] + 1 else (dropdownRect[1] - 1 - listH).coerceAtLeast(2)

        roundRect(g, ax0 - 1, top - 1, ax1 + 1, top + listH + 1, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, ax0, top, ax1, top + listH, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
        region(ax0, top, ax1, top + listH) {} // consume clicks on the list background

        var y = top + 1
        for ((i, label) in dropdownLabels.withIndex()) {
            val iy0 = y
            val iy1 = y + itemH
            val selected = i == dropdownSelected
            val hovered = mx in ax0..ax1 && my in iy0..iy1
            if (selected) {
                roundRect(g, ax0 + 1, iy0, ax1 - 1, iy1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            } else if (hovered) {
                roundRect(g, ax0 + 1, iy0, ax1 - 1, iy1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            }
            drawTextV(g, label, ax0 + 6, iy0, iy1, if (selected) 0xFFFFFFFF.toInt() else Theme.TEXT, fBody)
            val pick = dropdownOnPick
            region(ax0, iy0, ax1, iy1) { pick(i); closeDropdown() }
            y += itemH
        }
    }

    // ---------------------------------------------------------------- scrolling

    protected fun clampScroll(
        value: Float,
        contentH: Int,
        viewportH: Int,
    ): Float = value.coerceIn(0f, max(0f, (contentH - viewportH).toFloat()))

    protected fun within(
        rect: IntArray,
        x: Int,
        y: Int,
    ): Boolean = x in rect[0]..rect[2] && y in rect[1]..rect[3]

    // ---------------------------------------------------------------- alert modal

    protected val alertModalOpen: Boolean get() = alertOpen

    /**
     * Open (or update) the centered result modal. [busy] = a task is running: no Close button, and
     * the modal can't be dismissed until a follow-up [showAlert] flips it to the result. Safe to call
     * from any thread's client-thread hop (mutates only render-read state).
     */
    protected fun showAlert(title: String, message: String, busy: Boolean = false, success: Boolean = false) {
        dismissTransientEditors()
        alertOpen = true
        alertTitle = title
        alertMessage = message
        alertBusy = busy
        alertSuccess = success
        alertConfirmLabel = null
        alertOnConfirm = null
        alertCheckLabel = null
        promptField = null
        promptOnSubmit = null
        promptError = null
    }

    /**
     * Ask before doing something. [onConfirm] runs only when the user presses the confirm button;
     * dismissing the modal any other way (Cancel, Escape, a click outside) discards it, so the safe
     * outcome is the default one.
     */
    protected fun showConfirm(
        title: String,
        message: String,
        confirmLabel: String,
        onConfirm: () -> Unit,
    ) {
        showAlert(title, message, busy = false, success = false)
        alertConfirmLabel = confirmLabel
        alertOnConfirm = onConfirm
    }

    /**
     * [showConfirm] with a check row labeled [checkLabel] (e.g. "Don't show this again") between the
     * message and the buttons; [onConfirm] receives whether it was ticked.
     */
    protected fun showConfirm(
        title: String,
        message: String,
        confirmLabel: String,
        checkLabel: String,
        onConfirm: (Boolean) -> Unit,
    ) {
        showConfirm(title, message, confirmLabel) { onConfirm(alertChecked) }
        alertCheckLabel = checkLabel
        alertChecked = false
    }

    /**
     * Ask for one line of text. [onSubmit] gets the text on the submit button or Enter and decides
     * what happens next: [closeAlert], [setPromptState] for progress or an inline error, or a
     * [showAlert] result. [masked] hides the input (passwords); [initial] pre-fills the field.
     * Cancel / Escape / a click outside discard it.
     */
    protected fun showPrompt(
        title: String,
        message: String,
        submitLabel: String,
        masked: Boolean = false,
        initial: String = "",
        onSubmit: (String) -> Unit,
    ) {
        showAlert(title, message)
        promptField = SoulTextField().also {
            it.masked = masked
            it.set(initial)
            it.focused = true
        }
        promptSubmitLabel = submitLabel
        promptOnSubmit = onSubmit
    }

    /** For an open prompt: [busy] = checking (no buttons, field locked), else show [error] if any. */
    protected fun setPromptState(busy: Boolean, error: String? = null) {
        if (promptField == null) return
        alertBusy = busy
        promptError = error
    }

    private fun submitPrompt() {
        val field = promptField ?: return
        if (alertBusy || field.text.isEmpty()) return
        promptOnSubmit?.invoke(field.text)
    }

    protected fun closeAlert() {
        alertOpen = false
        alertConfirmLabel = null
        alertOnConfirm = null
        alertCheckLabel = null
        promptField = null
        promptOnSubmit = null
        promptError = null
    }

    /**
     * Hook: drop any in-progress inline edit and close any open popup, because a modal is about to
     * cover them. The base has neither; [com.soulreturns.config.gui.ConfigScreenBase] commits its
     * editors and closes its color picker / dropdown.
     */
    protected open fun dismissTransientEditors() {}

    /**
     * Draw the centered result modal (if open) over the [uiW]×[uiH] viewport: a dark scrim, a titled
     * panel with a word-wrapped message, a prompt's field + status line, and its buttons (hidden
     * while the task is running). Call LAST in the subclass render (after every other popup) so it
     * sits on top; a full-screen catch region makes it modal (clicks behind it are swallowed).
     */
    protected fun drawAlertModal(g: GuiGraphicsExtractor, uiW: Int, uiH: Int, mx: Int, my: Int) {
        if (!alertOpen) return

        // Scrim + full-screen catch: darken the screen and swallow clicks behind the modal.
        g.fill(0, 0, uiW, uiH, 0xB0000000.toInt())
        region(0, 0, uiW, uiH) {}

        val panelW = 300
        val pad = 16
        val lineH = 14
        val titleH = 20
        val btnH = 20

        val fieldH = 20
        val field = promptField

        val lines = wrapText(alertMessage, panelW - pad * 2, fBody)
        val msgH = lines.size * lineH
        // The prompt's status line: progress while busy, else the error (wrapped), else blank. The
        // button row stays while busy so the panel does not jump under the cursor.
        val status = if (alertBusy) listOf("Checking…") else promptError?.let { wrapText(it, panelW - pad * 2, fCaption) } ?: emptyList()
        val promptH = if (field != null) 6 + fieldH + 4 + status.size.coerceAtLeast(1) * lineH else 0
        val checkLabel = alertCheckLabel
        val checkH = if (checkLabel != null) 18 else 0
        val buttonsH = if (alertBusy && field == null) pad else 12 + btnH + pad
        val panelH = pad + titleH + 8 + msgH + promptH + checkH + buttonsH

        val px = (uiW - panelW) / 2
        val py = (uiH - panelH) / 2

        roundRect(g, px - 1, py - 1, px + panelW + 1, py + panelH + 1, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, px, py, px + panelW, py + panelH, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)
        region(px, py, px + panelW, py + panelH) {} // consume clicks on the panel background

        val titleColor = if (alertBusy || field != null) Theme.TEXT else if (alertSuccess) Theme.ACCENT else 0xFFE05252.toInt()
        drawTextV(g, alertTitle, px + pad, py + pad, py + pad + titleH, titleColor, fHeading, MsdfFonts.menuSemibold)

        var ly = py + pad + titleH + 8
        for (line in lines) {
            drawTextV(g, line, px + pad, ly, ly + lineH, Theme.TEXT_DIM, fBody)
            ly += lineH
        }

        if (field != null) {
            val fy0 = ly + 6
            drawTextField(g, px + pad, fy0, px + panelW - pad, fy0 + fieldH, field)
            var sy0 = fy0 + fieldH + 4
            val statusColor = if (alertBusy) Theme.TEXT_DIM else 0xFFE05252.toInt()
            for (line in status) {
                drawTextV(g, line, px + pad, sy0, sy0 + lineH, statusColor, fCaption)
                sy0 += lineH
            }
        }

        if (checkLabel != null) {
            val cy0 = ly + 6
            val box = 10
            val hovered = mx in px + pad..px + pad + box + 6 + textWidth(checkLabel, fBody) && my in cy0..cy0 + box
            if (alertChecked) {
                roundRect(g, px + pad, cy0, px + pad + box, cy0 + box, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
                // Tick: two stepped strokes (no check glyph in every MSDF family).
                for (i in 0..1) g.fill(px + pad + 2 + i, cy0 + 4 + i, px + pad + 4 + i, cy0 + 6 + i, 0xFFFFFFFF.toInt())
                for (i in 0..3) g.fill(px + pad + 4 + i, cy0 + 5 - i, px + pad + 6 + i, cy0 + 7 - i, 0xFFFFFFFF.toInt())
            } else {
                roundRect(g, px + pad, cy0, px + pad + box, cy0 + box, 0xFF4A4A4A.toInt(), SoulRenderPipelines.ROUND_SMALL)
                roundRect(g, px + pad + 1, cy0 + 1, px + pad + box - 1, cy0 + box - 1, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
            }
            drawTextV(g, checkLabel, px + pad + box + 6, cy0, cy0 + box, if (hovered) Theme.TEXT else Theme.TEXT_DIM, fBody)
            region(px + pad, cy0 - 2, px + pad + box + 6 + textWidth(checkLabel, fBody), cy0 + box + 2) { alertChecked = !alertChecked }
        }

        // Buttons — none while a task is running (the modal can't be dismissed then).
        if (!alertBusy) {
            val by0 = py + panelH - pad - btnH
            val confirmLabel = if (field != null) promptSubmitLabel else alertConfirmLabel
            if (confirmLabel == null) {
                val bw = 72
                val bx0 = px + panelW - pad - bw
                drawButton(g, mx, my, bx0, by0, bx0 + bw, by0 + btnH, "Close", accent = true) { closeAlert() }
            } else {
                val cw = (textWidth(confirmLabel, fBody) + 28).coerceAtLeast(72)
                val cx0 = px + panelW - pad - cw
                drawButton(g, mx, my, cx0, by0, cx0 + cw, by0 + btnH, confirmLabel, accent = true) {
                    if (field != null) {
                        submitPrompt()
                    } else {
                        // Take the action before closing: closeAlert() clears it.
                        val action = alertOnConfirm
                        closeAlert()
                        action?.invoke()
                    }
                }
                val nw = 72
                val nx0 = cx0 - 8 - nw
                drawButton(g, mx, my, nx0, by0, nx0 + nw, by0 + btnH, "Cancel") { closeAlert() }
            }
        }
    }
}
