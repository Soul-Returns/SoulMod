package com.soulreturns.ui.components

import com.soulreturns.render.DrawContextRenderer
import com.soulreturns.ui.theme.Theme
import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.UIComponent
import net.minecraft.client.Minecraft
import net.minecraft.client.input.MouseButtonEvent
import org.lwjgl.glfw.GLFW

/**
 * Integer-valued sibling of [SoulSlider]. Identical layout (160 × 20, 36 px label area on the
 * right) but the knob snaps to whole integer steps and a small tick mark is rendered on the
 * track at each step — visually distinguishing discrete int controls from continuous floats.
 *
 * Ticks are only drawn when the step count is small enough (≤ [MAX_TICKS]); for larger ranges
 * the slider still snaps to ints but skips ticks to avoid visual noise.
 */
class SoulIntSlider(
    private val min: Int,
    private val max: Int,
    initial: Int,
) : BaseUIComponent() {
    var value: Int = initial.coerceIn(min, max)
        private set

    private val changedListeners = mutableListOf<(Int) -> Unit>()
    private val slideEndListeners = mutableListOf<() -> Unit>()

    init {
        cursorStyle(CursorStyle.MOVE)
        horizontalSizing(Sizing.fixed(TOTAL_W))
        verticalSizing(Sizing.fixed(HEIGHT))
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun draw(
        context: OwoUIGraphics,
        mouseX: Int,
        mouseY: Int,
        partialTicks: Float,
        delta: Float
    ) {
        val trackStart = x + KNOB / 2
        val trackEnd = x + trackWidth() + KNOB / 2
        val trackY = y + (height - TRACK_H) / 2

        DrawContextRenderer.roundedFill(
            context,
            trackStart,
            trackY,
            trackEnd,
            trackY + TRACK_H,
            Theme.PANEL_HOVER,
            TRACK_H / 2f,
        )

        val steps = max - min
        if (steps in 1..MAX_TICKS) {
            // Inner ticks only — the rounded track ends already mark the extremes.
            val centerY = trackY + TRACK_H / 2
            for (i in 1 until steps) {
                val tx = trackStart + (trackWidth() * (i.toDouble() / steps)).toInt()
                context.fill(tx, centerY - TICK_H / 2, tx + 1, centerY + TICK_H / 2, Theme.TEXT_DIM)
            }
        }

        val progress = progress()
        val fillEnd = trackStart + (trackWidth() * progress).toInt()
        if (fillEnd > trackStart) {
            DrawContextRenderer.roundedFill(
                context,
                trackStart,
                trackY,
                fillEnd,
                trackY + TRACK_H,
                Theme.ACCENT,
                TRACK_H / 2f,
            )
        }

        val knobX = trackStart + (trackWidth() * progress).toInt() - KNOB / 2
        val knobY = y + (height - KNOB) / 2
        DrawContextRenderer.roundedFill(
            context,
            knobX,
            knobY,
            knobX + KNOB,
            knobY + KNOB,
            Theme.TEXT,
            KNOB / 2f,
        )

        val label = value.toString()
        val tr = Minecraft.getInstance().font
        val labelAreaX = x + TOTAL_W - LABEL_W
        val labelX = labelAreaX + (LABEL_W - tr.width(label)) / 2
        val labelY = y + (height - tr.lineHeight) / 2
        context.drawString(tr, label, labelX, labelY, Theme.TEXT_DIM, false)
    }

    // ── Mouse input ──────────────────────────────────────────────────────────

    override fun onMouseDown(
        click: MouseButtonEvent,
        doubled: Boolean
    ): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateFromClick(click.x())
            return true
        }
        return super.onMouseDown(click, doubled)
    }

    override fun onMouseDrag(
        click: MouseButtonEvent,
        deltaX: Double,
        deltaY: Double
    ): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateFromClick(click.x())
            return true
        }
        return super.onMouseDrag(click, deltaX, deltaY)
    }

    override fun onMouseUp(click: MouseButtonEvent): Boolean {
        slideEndListeners.forEach { it() }
        return super.onMouseUp(click)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun onChanged(listener: (Int) -> Unit): SoulIntSlider {
        changedListeners.add(listener)
        return this
    }

    fun onSlideEnd(listener: () -> Unit): SoulIntSlider {
        slideEndListeners.add(listener)
        return this
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private fun trackWidth(): Int = TOTAL_W - LABEL_W - KNOB

    private fun progress(): Double {
        val span = (max - min).toDouble()
        if (span <= 0.0) return 0.0
        return ((value - min) / span).coerceIn(0.0, 1.0)
    }

    private fun updateFromClick(clickX: Double) {
        val tStart = KNOB / 2.0
        val tWidth = trackWidth().toDouble()
        val p = ((clickX - tStart) / tWidth).coerceIn(0.0, 1.0)
        val raw = min + (max - min) * p
        val snapped = Math.round(raw).toInt().coerceIn(min, max)
        if (snapped != value) {
            value = snapped
            changedListeners.forEach { it(value) }
        }
    }

    override fun determineHorizontalContentSize(sizing: Sizing): Int = TOTAL_W

    override fun determineVerticalContentSize(sizing: Sizing): Int = HEIGHT

    override fun canFocus(source: UIComponent.FocusSource): Boolean = true

    companion object {
        const val TOTAL_W = 160
        const val HEIGHT = 20
        private const val TRACK_H = 4
        private const val KNOB = 14
        private const val LABEL_W = 36
        private const val TICK_H = 8
        private const val MAX_TICKS = 12
    }
}
