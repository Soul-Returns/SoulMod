package com.soulreturns.config.gui.components

import com.soulreturns.config.gui.Theme
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.UIComponent
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.Click
import org.lwjgl.glfw.GLFW
import java.util.Locale

/**
 * A modern styled slider with a rounded track, accent fill, and an inline value label.
 * The component is 160 px wide and 20 px tall.  The right-most 36 px are reserved for the
 * value text; the rest is the interactive track area.
 */
class SoulSlider(
    private val min: Double,
    private val max: Double,
    initial: Double,
    private val decimals: Int,
) : BaseUIComponent() {

    var value: Double = initial.coerceIn(min, max)
        private set

    private val changedListeners = mutableListOf<(Double) -> Unit>()
    private val slideEndListeners = mutableListOf<() -> Unit>()

    init {
        cursorStyle(CursorStyle.MOVE)
        horizontalSizing(Sizing.fixed(TOTAL_W))
        verticalSizing(Sizing.fixed(HEIGHT))
    }

    // ── Drawing ──────────────────────────────────────────────────────────────

    override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val trackStart = x + KNOB / 2
        val trackEnd   = x + trackWidth() + KNOB / 2
        val trackY     = y + (height - TRACK_H) / 2

        // Background track
        DrawContextRenderer.roundedFill(
            context, trackStart, trackY, trackEnd, trackY + TRACK_H,
            Theme.PANEL_HOVER, TRACK_H / 2f,
        )

        // Filled (accent) portion
        val progress = progress()
        val fillEnd  = trackStart + (trackWidth() * progress).toInt()
        if (fillEnd > trackStart) {
            DrawContextRenderer.roundedFill(
                context, trackStart, trackY, fillEnd, trackY + TRACK_H,
                Theme.ACCENT, TRACK_H / 2f,
            )
        }

        // Knob
        val knobX = trackStart + (trackWidth() * progress).toInt() - KNOB / 2
        val knobY = y + (height - KNOB) / 2
        DrawContextRenderer.roundedFill(
            context, knobX, knobY, knobX + KNOB, knobY + KNOB,
            Theme.TEXT, KNOB / 2f,
        )

        // Value label (right-aligned in the reserved label area)
        val label  = formatValue()
        val tr     = MinecraftClient.getInstance().textRenderer
        val labelAreaX = x + TOTAL_W - LABEL_W
        val labelX = labelAreaX + (LABEL_W - tr.getWidth(label)) / 2
        val labelY = y + (height - tr.fontHeight) / 2
        context.drawText(tr, label, labelX, labelY, Theme.TEXT_DIM, false)
    }

    // ── Mouse input ──────────────────────────────────────────────────────────

    override fun onMouseDown(click: Click, doubled: Boolean): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateFromClick(click.x())
            return true
        }
        return super.onMouseDown(click, doubled)
    }

    override fun onMouseDrag(click: Click, deltaX: Double, deltaY: Double): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            updateFromClick(click.x())
            return true
        }
        return super.onMouseDrag(click, deltaX, deltaY)
    }

    override fun onMouseUp(click: Click): Boolean {
        slideEndListeners.forEach { it() }
        return super.onMouseUp(click)
    }

    // ── Public API ───────────────────────────────────────────────────────────

    fun onChanged(listener: (Double) -> Unit): SoulSlider { changedListeners.add(listener); return this }
    fun onSlideEnd(listener: () -> Unit): SoulSlider { slideEndListeners.add(listener); return this }

    fun setValue(v: Double) { value = v.coerceIn(min, max) }
    fun discreteValue(): Double = value

    // ── Internals ────────────────────────────────────────────────────────────

    private fun trackWidth(): Int = TOTAL_W - LABEL_W - KNOB

    private fun progress(): Double = ((value - min) / (max - min)).coerceIn(0.0, 1.0)

    private fun updateFromClick(clickX: Double) {
        val tStart = KNOB / 2.0
        val tWidth = trackWidth().toDouble()
        val p = ((clickX - tStart) / tWidth).coerceIn(0.0, 1.0)
        value = min + (max - min) * p
        if (decimals == 0) value = Math.round(value).toDouble()
        changedListeners.forEach { it(value) }
    }

    private fun formatValue(): String =
        if (decimals == 0) value.toLong().toString()
        else String.format(Locale.ROOT, "%.${decimals}f", value)

    override fun determineHorizontalContentSize(sizing: Sizing): Int = TOTAL_W
    override fun determineVerticalContentSize(sizing: Sizing): Int = HEIGHT
    override fun canFocus(source: UIComponent.FocusSource): Boolean = true

    companion object {
        const val TOTAL_W = 160
        const val HEIGHT  = 20
        private const val TRACK_H = 4
        private const val KNOB    = 14
        private const val LABEL_W = 36
    }
}
