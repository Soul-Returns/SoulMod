package com.soulreturns.ui.foundation

import com.soulreturns.platform.render.nvg.NvgRenderer
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulMeasured
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.SoulNode
import com.soulreturns.ui.composer.applySizeOverride
import com.soulreturns.ui.composer.composable
import com.soulreturns.ui.input.HitRegion
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.theme.SoulTheme

/**
 * Single-line editable text field.
 *
 * Stateless — caller owns [value] and gets [onChange] when the user types or deletes.
 * Focus is handled internally via [SoulInput.focusedKey] keyed on [key]; click to focus,
 * Escape or click elsewhere to blur.
 *
 * **Minimal v1**: append-at-end typing, backspace, click-to-focus, blinking caret. No
 * mid-string editing, no selection, no copy/paste. Mid-string + selection land in P5 polish
 * along with arrow-key cursor movement; the [SoulInput.EditKey] enum already has the entries
 * reserved.
 *
 * Sizing: defaults to `[width = 160px, height = 18px]`. Override via
 * `Modifier.width(...)` / `.height(...)` / `.fillMaxWidth()` as needed.
 *
 * @param placeholder Greyed-out text shown when [value] is empty.
 */
@SoulComposable
fun TextField(
    value: String,
    onChange: (String) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    placeholder: String = "",
    key: Any = SoulComposer.current.nextAutoKey(),
) {
    SoulComposer.current.composable(
        TextFieldNode(
            value = value,
            onChange = onChange,
            placeholder = placeholder,
            ownerKey = key,
            modifier = modifier,
        ),
    )
}

internal class TextFieldNode(
    private val value: String,
    private val onChange: (String) -> Unit,
    private val placeholder: String,
    private val ownerKey: Any,
    override val modifier: SoulModifier,
) : SoulNode() {
    companion object {
        private const val DEFAULT_W = 160f
        private const val DEFAULT_H = 18f
        private const val PADDING_X = 6f
        private const val CARET_PERIOD_MS = 1000L
    }

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        val w = if (c.hasBoundedWidth()) c.maxWidth.coerceAtLeast(DEFAULT_W) else DEFAULT_W
        val (cw, ch) = c.constrain(w, DEFAULT_H)
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

        val isFocused = SoulInput.focusedKey == ownerKey
        val isHovered = SoulInput.isHovered(ownerKey)

        // Apply queued edits while focused — drain chars + edit keys, recompute value.
        // The caller's `value` is the source of truth between frames; we mutate via
        // [onChange] and let the next frame's compose receive the updated value.
        if (isFocused) {
            applyPendingEdits()
        }

        // Background — `panelHover` for all states so the field stays visible when sitting
        // inside a `panelInset` section card (otherwise the field's bg matches the section
        // and the chip disappears). Hover shifts slightly brighter for affordance feedback.
        val bgColor =
            when {
                isHovered -> 0xFF2A2A2A.toInt()
                else -> SoulTheme.colors.panelHover
            }
        NvgRenderer.rect(x, y, m.width, m.height, bgColor, SoulTheme.dimens.radiusSmall)

        // Border — always present (subtle when unfocused, accent when focused).
        val borderColor = if (isFocused) SoulTheme.colors.accent else 0xFF333333.toInt()
        NvgRenderer.hollowRect(
            x,
            y,
            m.width,
            m.height,
            thickness = 1f,
            color = borderColor,
            radius = SoulTheme.dimens.radiusSmall,
        )

        // Text or placeholder
        val font = SoulTheme.typography.body.font
        val size = SoulTheme.typography.body.size
        val textY = y + (m.height - size) / 2f
        if (value.isEmpty() && !isFocused && placeholder.isNotEmpty()) {
            NvgRenderer.text(placeholder, x + PADDING_X, textY, size, SoulTheme.colors.textFaint, font)
        } else {
            NvgRenderer.text(value, x + PADDING_X, textY, size, SoulTheme.colors.text, font)
        }

        // Caret — blink when focused
        if (isFocused) {
            val blinkOn = (System.currentTimeMillis() / (CARET_PERIOD_MS / 2)) % 2L == 0L
            if (blinkOn) {
                val textW = NvgRenderer.textWidth(value, size, font)
                val caretX = x + PADDING_X + textW + 1f
                NvgRenderer.line(
                    caretX,
                    textY,
                    caretX,
                    textY + size,
                    thickness = 1f,
                    color = SoulTheme.colors.text,
                )
            }
        }

        // Record hit region with both click + focus side-effect. Clicking inside focuses;
        // clicking outside any focusable region clears focus (the screen-level mouseClicked
        // path doesn't know about this, so we cooperate via SoulInput.setFocus on press).
        SoulInput.recordRegion(
            HitRegion(
                key = ownerKey,
                x = x,
                y = y,
                width = m.width,
                height = m.height,
                depth = depth,
                onClick = { SoulInput.setFocus(ownerKey) },
            ),
        )
    }

    private fun applyPendingEdits() {
        var current = value
        val chars = SoulInput.drainChars()
        if (chars.isNotEmpty()) {
            val sb = StringBuilder(current)
            for (cp in chars) {
                // Skip non-printable control chars.
                if (cp >= 0x20 && cp != 0x7F) {
                    sb.appendCodePoint(cp)
                }
            }
            current = sb.toString()
        }
        for (key in SoulInput.drainEditKeys()) {
            when (key) {
                SoulInput.EditKey.Backspace ->
                    if (current.isNotEmpty()) current = current.dropLast(1)
                // Other edit keys deliberately ignored in v1 — covered in P5.
                else -> {}
            }
        }
        if (current != value) onChange(current)
    }
}
