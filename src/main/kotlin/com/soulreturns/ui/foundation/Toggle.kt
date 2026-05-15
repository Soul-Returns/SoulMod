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
 * Pill-shaped on/off switch (~36×18 logical pixels).
 *
 * Stateless — the caller owns [value] and gets [onChange] when the user clicks. The widget
 * renders the current state purely from [value]; the click handler flips it via [onChange].
 *
 * Visual states:
 *  - **off**: dark track (`Theme.colors.panelInset`), knob on the left at [Theme.colors.textDim].
 *  - **on**: accent track (`Theme.colors.accent`), knob on the right at white.
 *  - **hovered**: track shifts to `panelHover` / `accentDim` accordingly.
 */
@SoulComposable
fun Toggle(
    value: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    key: Any = SoulComposer.current.nextAutoKey(),
) {
    SoulComposer.current.composable(
        ToggleNode(
            value = value,
            onChange = { onChange(!value) },
            ownerKey = key,
            modifier = modifier.clickable(key) { onChange(!value) },
        ),
    )
}

internal class ToggleNode(
    private val value: Boolean,
    private val onChange: () -> Unit,
    private val ownerKey: Any,
    override val modifier: SoulModifier,
) : SoulNode() {
    companion object {
        private const val DEFAULT_W = 36f
        private const val DEFAULT_H = 18f
        private const val PAD = 2f
    }

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)
        val (w, h) = c.constrain(DEFAULT_W, DEFAULT_H)
        val out = SoulMeasured(w, h)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        val m = measured ?: return
        val hovered = SoulInput.isHovered(ownerKey)
        // Off-state track uses `panelHover` (not `panelInset`) so the track stays visible
        // when the toggle sits inside a `panelInset` row / section — otherwise the entire
        // pill blends into its container and only the knob is visible.
        val trackColor =
            when {
                value && hovered -> SoulTheme.colors.accentDim
                value -> SoulTheme.colors.accent
                hovered -> 0xFF2F2F2F.toInt()
                else -> SoulTheme.colors.panelHover
            }
        val knobColor = if (value) 0xFFFFFFFFu.toInt() else SoulTheme.colors.textDim

        NvgRenderer.rect(x, y, m.width, m.height, trackColor, m.height / 2f)

        val knobR = (m.height / 2f) - PAD
        val cy = y + m.height / 2f
        val cx =
            if (value) x + m.width - PAD - knobR else x + PAD + knobR
        NvgRenderer.circle(cx, cy, knobR, knobColor)
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
    }
}
