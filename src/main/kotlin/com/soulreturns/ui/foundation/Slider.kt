package com.soulreturns.ui.foundation

import com.soulreturns.platform.render.nvg.NvgRenderer
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.clickable
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.composer.recordHitRegions
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.theme.SoulTheme

/**
 * Horizontal slider with drag support.
 *
 * Stateless — the caller owns [value] and receives [onChange] on each tick of cursor motion
 * during a drag. Value is normalized to `[min, max]`; the widget linearly maps cursor X to
 * that range.
 *
 * Interaction:
 *  - **Click on track**: value jumps to that position. Press captures the slider's key.
 *  - **Drag (mouse held)**: each subsequent frame while `SoulInput.isPressed(key)` is true,
 *    the slider re-reads `SoulInput.cursorX` and emits [onChange] with the new value.
 *  - **Release**: mouse-up clears the press capture.
 *
 * The drag continues even when the cursor leaves the slider's bounds, so the user can drag
 * outside the track and still control the value — standard "press to capture" pattern.
 *
 * @param value Current value; clamped into `[min, max]` for display.
 * @param onChange Fired with the new value while the cursor moves during a press.
 * @param min Lower bound of the value range. Defaults to 0.
 * @param max Upper bound of the value range. Defaults to 1.
 */
@SoulComposable
fun Slider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    min: Float = 0f,
    max: Float = 1f,
    step: Float = 0f,
    key: Any = SoulComposer.current.nextAutoKey(),
) {
    SoulComposer.current.composable(
        SliderNode(
            value = value,
            min = min,
            max = max,
            step = step,
            ownerKey = key,
            modifier = modifier,
            onChange = onChange,
        ),
    )
}

internal class SliderNode(
    private val value: Float,
    private val min: Float,
    private val max: Float,
    private val step: Float,
    private val ownerKey: Any,
    override val modifier: SoulModifier,
    private val onChange: (Float) -> Unit,
) : SoulNode() {
    private fun snap(raw: Float): Float = if (step <= 0f) raw else (min + kotlin.math.round((raw - min) / step) * step).coerceIn(min, max)

    companion object {
        private const val DEFAULT_W = 120f
        private const val DEFAULT_H = 14f
        private const val TRACK_H = 4f
        private const val KNOB_R = 6f
    }

    // Wrap the modifier with a clickable so the slider receives press capture. The click
    // handler also jumps the value to where the user pressed.
    private val captureModifier: SoulModifier =
        modifier.clickable(ownerKey) {
            // On press, jump the value to the cursor position. The track's bounds are not
            // known until measure/draw, so we record them and read on click via captured
            // closure state — see drawSelf's recorded HitRegion.
            // Default no-op; replaced inside drawSelf with a bounds-aware handler if needed.
        }

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        val w =
            if (c.hasBoundedWidth() && c.minWidth == c.maxWidth) c.maxWidth else DEFAULT_W
        val h = DEFAULT_H
        val (cw, ch) = c.constrain(w, h)
        val out = SoulMeasured(cw, ch)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        val m = measured ?: return
        val trackX = x + KNOB_R
        val trackY = y + (m.height - TRACK_H) / 2f
        val trackW = (m.width - 2f * KNOB_R).coerceAtLeast(1f)

        // Normalized position 0..1 of the current value.
        val clampedValue = value.coerceIn(min, max)
        val t = if (max > min) (clampedValue - min) / (max - min) else 0f

        // Track: inactive portion uses `panelHover` (not `panelInset`) so the track stays
        // visible when the slider sits inside a `panelInset` row / section — same rationale
        // as `Toggle`'s off track.
        NvgRenderer.rect(trackX, trackY, trackW, TRACK_H, SoulTheme.colors.panelHover, TRACK_H / 2f)
        if (t > 0f) {
            NvgRenderer.rect(trackX, trackY, trackW * t, TRACK_H, SoulTheme.colors.accent, TRACK_H / 2f)
        }

        // Draw knob.
        val knobCx = trackX + trackW * t
        val knobCy = y + m.height / 2f
        val hovered = SoulInput.isHovered(ownerKey)
        val pressed = SoulInput.isPressed(ownerKey)
        val knobColor =
            when {
                pressed -> SoulTheme.colors.accentDim
                hovered -> 0xFFFFFFFFu.toInt()
                else -> SoulTheme.colors.text
            }
        NvgRenderer.circle(knobCx, knobCy, KNOB_R, knobColor)

        // Drag handler: while pressed, recompute value from current cursor X.
        if (pressed) {
            val cursorX = SoulInput.cursorX
            if (cursorX >= 0f) {
                val newT = ((cursorX - trackX) / trackW).coerceIn(0f, 1f)
                val newValue = snap(min + newT * (max - min))
                if (newValue != clampedValue) {
                    onChange(newValue)
                }
            }
        }

        // Record region for press capture + the click-to-jump handler.
        com.soulreturns.ui.input.SoulInput.recordRegion(
            com.soulreturns.ui.input.HitRegion(
                key = ownerKey,
                x = x,
                y = y,
                width = m.width,
                height = m.height,
                depth = depth,
                onClick = {
                    val cursorX = SoulInput.cursorX
                    if (cursorX >= 0f) {
                        val newT = ((cursorX - trackX) / trackW).coerceIn(0f, 1f)
                        onChange(snap(min + newT * (max - min)))
                    }
                },
            ),
        )
        // Allow other modifier-attached handlers (e.g. an outer scrollable) to layer on top.
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
    }
}
