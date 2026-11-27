package com.soulreturns.features.dev

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import kotlin.math.roundToInt

/**
 * The window every `/soul dev …` command prints into — a scrollable dump with a **Copy output**
 * button. See [DevOutput] for why diagnostics do not go to chat.
 *
 * It renders [DevOutput.snapshot] fresh every frame rather than being told when to update, because
 * most commands finish asynchronously and append as their results land. That also makes it follow a
 * long-running command live (`/soul dev backend test` fills in step by step), which is why it sticks
 * to the bottom until you scroll up.
 */
class DevOutputScreen private constructor() : SoulScreenBase(Component.literal("Soul Dev Output")) {

    companion object {
        private const val HEADER_H = 34
        private const val FOOTER_H = 38
        private const val PAD = 14
        private const val LINE_H = 12
        private const val SCROLL_STEP = 24
        private const val CLOSE_SIZE = 18

        /** How long the copy button confirms itself before going back to its normal label. */
        private const val COPIED_MS = 1_500L

        /**
         * Show the current capture. Deferred through `execute` because a `/soul dev …` typed into
         * chat is dispatched *before* the chat screen tears itself down, and an inline
         * `setScreenAndShow` would be undone by it (the same reason `/soul` defers).
         */
        fun open() {
            val mc = Minecraft.getInstance()
            if (mc.gui?.screen() is DevOutputScreen) return
            mc.execute { mc.setScreenAndShow(DevOutputScreen()) }
        }

        /** Legacy `§` color codes → ARGB. The 16 vanilla colors; style codes are skipped. */
        private val COLORS: Map<Char, Int> = mapOf(
            '0' to 0xFF000000.toInt(), '1' to 0xFF0000AA.toInt(), '2' to 0xFF00AA00.toInt(),
            '3' to 0xFF00AAAA.toInt(), '4' to 0xFFAA0000.toInt(), '5' to 0xFFAA00AA.toInt(),
            '6' to 0xFFFFAA00.toInt(), '7' to 0xFFAAAAAA.toInt(), '8' to 0xFF555555.toInt(),
            '9' to 0xFF5555FF.toInt(), 'a' to 0xFF55FF55.toInt(), 'b' to 0xFF55FFFF.toInt(),
            'c' to 0xFFFF5555.toInt(), 'd' to 0xFFFF55FF.toInt(), 'e' to 0xFFFFFF55.toInt(),
            'f' to 0xFFFFFFFF.toInt(),
        )
    }

    /** One stretch of text sharing a color — what a `§`-coded line decomposes into. */
    private class Run(val text: String, val color: Int)

    private var scroll = 0f
    private var contentH = 0
    private var bodyRect = intArrayOf(0, 0, 0, 0)

    /**
     * Stick to the newest line while a command is still writing, and stop the moment the reader
     * scrolls up — following the tail under someone who is reading is worse than not following it.
     */
    private var followTail = true
    private var copiedAt = 0L

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        regions.clear()

        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()

        g.pose().pushMatrix()
        g.pose().scale(s, s)

        val cardW = (uiW * 0.92f).toInt().coerceAtMost(940).coerceAtMost(uiW - 8)
        val cardH = (uiH * 0.9f).toInt().coerceAtMost(720).coerceAtMost(uiH - 8)
        val x0 = (uiW - cardW) / 2
        val y0 = (uiH - cardH) / 2
        val x1 = x0 + cardW
        val y1 = y0 + cardH

        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)

        drawHeader(g, mx, my, x0, y0, x1)
        drawBody(g, x0, y0 + HEADER_H, x1, y1 - FOOTER_H)
        drawFooter(g, mx, my, x0, y1 - FOOTER_H, x1, y1)

        g.pose().popMatrix()
    }

    private fun drawHeader(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int) {
        roundRect(g, x0, y0, x1, y0 + HEADER_H, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fill(x0, y0 + HEADER_H, x1, y0 + HEADER_H + 1, Theme.SEPARATOR)
        drawTextV(g, DevOutput.title, x0 + PAD, y0, y0 + HEADER_H, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)

        val closeX0 = x1 - 8 - CLOSE_SIZE
        val closeY0 = y0 + (HEADER_H - CLOSE_SIZE) / 2
        val hovered = mx in closeX0..(closeX0 + CLOSE_SIZE) && my in closeY0..(closeY0 + CLOSE_SIZE)
        if (hovered) {
            roundRect(g, closeX0, closeY0, closeX0 + CLOSE_SIZE, closeY0 + CLOSE_SIZE, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        }
        // `×` is U+00D7 — present in every MSDF family, unlike `✕`.
        drawTextV(
            g,
            "×",
            closeX0 + (CLOSE_SIZE - textWidth("×", fHeading)) / 2,
            closeY0,
            closeY0 + CLOSE_SIZE,
            if (hovered) Theme.TEXT else Theme.TEXT_DIM,
            fHeading,
        )
        region(closeX0, closeY0, closeX0 + CLOSE_SIZE, closeY0 + CLOSE_SIZE) { onClose() }
    }

    private fun drawBody(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        bodyRect = intArrayOf(x0, y0, x1, y1)
        val maxW = (x1 - x0) - PAD * 2 - 8
        val visual = wrapRuns(DevOutput.snapshot(), maxW)
        contentH = visual.size * LINE_H + PAD * 2

        val viewportH = y1 - y0
        if (followTail) scroll = (contentH - viewportH).coerceAtLeast(0).toFloat()
        scroll = clampScroll(scroll, contentH, viewportH)

        beginClip(g, x0, y0, x1, y1)
        var y = y0 + PAD - scroll.roundToInt()
        for (line in visual) {
            // Cheap cull: a dump can run to hundreds of wrapped lines and each glyph is a quad.
            if (y + LINE_H >= y0 && y <= y1) {
                val pen = SoulText.Line(g, x0 + PAD, y, fCaption, forceCustom = true)
                for (run in line) pen.text(run.text, run.color, MsdfFonts.menuRegular, scissor = currentClip)
            }
            y += LINE_H
        }
        endClip(g)

        drawScrollbar(g, x1 - 7, y0 + 4, y1 - 4, viewportH)
    }

    private fun drawScrollbar(g: GuiGraphicsExtractor, x: Int, y0: Int, y1: Int, viewportH: Int) {
        val overflow = contentH - viewportH
        if (overflow <= 0) return
        val trackH = y1 - y0
        val thumbH = (trackH * viewportH.toFloat() / contentH).roundToInt().coerceIn(16, trackH)
        val travel = trackH - thumbH
        val thumbY = y0 + (travel * (scroll / overflow)).roundToInt().coerceIn(0, travel)
        roundRect(g, x, y0, x + 3, y1, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
        roundRect(g, x, thumbY, x + 3, thumbY + thumbH, Theme.TEXT_FAINT, SoulRenderPipelines.ROUND_PILL)
    }

    private fun drawFooter(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        g.fill(x0, y0, x1, y0 + 1, Theme.SEPARATOR)
        roundRect(g, x0, y0 + 1, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_BOTTOM)

        val count = DevOutput.snapshot().size
        drawTextV(g, "$count line${if (count == 1) "" else "s"}", x0 + PAD, y0, y1, Theme.TEXT_FAINT, fCaption)

        val btnY0 = y0 + 8
        val btnY1 = y1 - 8
        val closeW = 64
        val closeX0 = x1 - PAD - closeW
        drawButton(g, mx, my, closeX0, btnY0, closeX0 + closeW, btnY1, "Close") { onClose() }

        val copied = System.currentTimeMillis() - copiedAt < COPIED_MS
        val label = if (copied) "Copied!" else "Copy output"
        val copyW = (textWidth(label, fBody) + 28).coerceAtLeast(96)
        val copyX0 = closeX0 - 8 - copyW
        drawButton(g, mx, my, copyX0, btnY0, copyX0 + copyW, btnY1, label, accent = true) { copyToClipboard() }
        drawTextV(g, "Ctrl+C", copyX0 - 8 - textWidth("Ctrl+C", fCaption), y0, y1, Theme.TEXT_FAINT, fCaption)
    }

    /** Copy the whole capture, color codes stripped — see [DevOutput.plainText]. */
    private fun copyToClipboard() {
        minecraft?.keyboardHandler?.setClipboard(DevOutput.plainText())
        copiedAt = System.currentTimeMillis()
    }

    // ------------------------------------------------------------------ text layout

    /**
     * Lay `§`-coded lines out into wrapped visual lines of colored runs.
     *
     * Wrapping has to happen *after* the color parse, not before: splitting the raw string would cut
     * a `§c` off from the word it colors, and wrapping the stripped text would lose the mapping back.
     * The active color also carries across a wrap, so a long red line stays red on its second row.
     */
    private fun wrapRuns(lines: List<String>, maxW: Int): List<List<Run>> {
        val out = ArrayList<List<Run>>(lines.size)
        for (line in lines) {
            if (line.isEmpty()) {
                out.add(emptyList())
                continue
            }
            var current = ArrayList<Run>()
            var width = 0
            var color = Theme.TEXT
            var pending = StringBuilder()

            fun flushPending() {
                if (pending.isNotEmpty()) {
                    current.add(Run(pending.toString(), color))
                    pending = StringBuilder()
                }
            }
            fun newLine() {
                flushPending()
                out.add(current)
                current = ArrayList()
                width = 0
            }

            var i = 0
            while (i < line.length) {
                val ch = line[i]
                if (ch == '§' && i + 1 < line.length) {
                    flushPending()
                    val code = line[i + 1].lowercaseChar()
                    // A color or a reset changes the pen; the style codes (l/o/n/m/k) have no MSDF
                    // equivalent here and are simply dropped rather than rendered as literal text.
                    if (code == 'r') color = Theme.TEXT else COLORS[code]?.let { color = it }
                    i += 2
                    continue
                }
                val w = textWidth(ch.toString(), fCaption)
                // Break before the character that would overflow, but never emit an empty line for a
                // viewport too narrow to fit even one glyph.
                if (width + w > maxW && (width > 0 || pending.isNotEmpty())) newLine()
                pending.append(ch)
                width += w
                i++
            }
            flushPending()
            out.add(current)
        }
        return out
    }

    // ------------------------------------------------------------------ input

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        val s = SoulUi.factor()
        val mx = (mouseX / s).roundToInt()
        val my = (mouseY / s).roundToInt()
        if (!within(bodyRect, mx, my)) return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
        val viewportH = bodyRect[3] - bodyRect[1]
        scroll = clampScroll(scroll - scrollY.toFloat() * SCROLL_STEP, contentH, viewportH)
        // Resume following only once the reader is back at the very bottom.
        followTail = scroll >= (contentH - viewportH).coerceAtLeast(0).toFloat() - 0.5f
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        // Ctrl+C is what someone looking at a wall of diagnostics reaches for first. `isCopy()` comes
        // from InputWithModifiers, so it also honours the platform's own copy chord.
        if (event.isCopy) {
            copyToClipboard()
            return true
        }
        return super.keyPressed(event)
    }
}
