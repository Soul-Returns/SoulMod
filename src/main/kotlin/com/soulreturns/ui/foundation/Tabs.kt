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
import com.soulreturns.ui.composer.recordHitRegions
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.theme.SoulTheme

/**
 * Horizontal segmented selector strip. Stateless — the caller owns [selectedIndex] and gets
 * [onSelect] with the clicked index.
 *
 * Each tab is a rounded rectangle with body-text label and `Theme.dimens.paddingSmall` of
 * horizontal padding. The selected tab uses [SoulTheme.colors.accent] background + white
 * text; unselected tabs use [SoulTheme.colors.panel] + [SoulTheme.colors.text], shifting to
 * [SoulTheme.colors.panelHover] on hover.
 *
 * Tabs default to "hug content width" — width is the sum of each label's width plus
 * padding plus inter-tab gap. Use `Modifier.fillMaxWidth()` to spread them across the
 * available width (each tab still hugs its own label; the strip just measures wider).
 *
 * @param keyPrefix Stable prefix for the per-tab hover key. Each tab uses
 *   `"$keyPrefix:$index"` so hover state stays attached to the right tab even when the
 *   `options` list reorders. Pass a feature-specific string like `"fishing_tabs"`.
 */
@SoulComposable
fun Tabs(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    keyPrefix: Any = SoulComposer.current.nextAutoKey(),
) {
    SoulComposer.current.composable(
        TabsNode(
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            keyPrefix = keyPrefix,
            modifier = modifier,
        ),
    )
}

internal class TabsNode(
    private val options: List<String>,
    private val selectedIndex: Int,
    private val onSelect: (Int) -> Unit,
    private val keyPrefix: Any,
    override val modifier: SoulModifier,
) : SoulNode() {
    companion object {
        private const val TAB_H = 14f
        private const val TAB_PAD_X = 8f
        private const val TAB_GAP = 4f
        private val FONT
            get() = SoulTheme.typography.body.font
        private val FONT_SIZE
            get() = SoulTheme.typography.body.size
    }

    // Computed in measure, consumed in drawSelf.
    private var tabBounds: List<Triple<Float, Float, Float>> = emptyList() // x, width, _

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)

        val widths = options.map { NvgRenderer.textWidth(it, FONT_SIZE, FONT) + TAB_PAD_X * 2f }
        val contentW = widths.sum() + TAB_GAP * (options.size - 1).coerceAtLeast(0)
        val (w, h) = c.constrain(contentW, TAB_H)

        // Hug content even if parent says "fill" — but if fillMaxWidth forced us wider,
        // distribute the extra space as gap between tabs (centered).
        val extraSpace = (w - contentW).coerceAtLeast(0f)
        val gapAdjust = if (options.size > 1) extraSpace / (options.size - 1) else 0f
        val effectiveGap = TAB_GAP + gapAdjust

        var cursor = 0f
        tabBounds =
            widths.map { width ->
                val xStart = cursor
                cursor += width + effectiveGap
                Triple(xStart, width, 0f)
            }

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
        for ((i, label) in options.withIndex()) {
            val (relX, width) = tabBounds[i]
            val selected = i == selectedIndex
            val tabKey = TabKey(keyPrefix, i)
            val hovered = SoulInput.isHovered(tabKey)
            val bg =
                when {
                    selected && hovered -> SoulTheme.colors.accentDim
                    selected -> SoulTheme.colors.accent
                    hovered -> SoulTheme.colors.panelHover
                    else -> SoulTheme.colors.panel
                }
            val textColor = if (selected) 0xFFFFFFFFu.toInt() else SoulTheme.colors.text
            val absX = x + relX
            NvgRenderer.rect(absX, y, width, m.height, bg, SoulTheme.dimens.radiusSmall)

            val labelW = NvgRenderer.textWidth(label, FONT_SIZE, FONT)
            val textX = absX + (width - labelW) / 2f
            // Center the text vertically within the tab height (font size = glyph cap height).
            val textY = y + (m.height - FONT_SIZE) / 2f
            NvgRenderer.text(label, textX, textY, FONT_SIZE, textColor, FONT)

            // Per-tab hit region for hover + click routing.
            com.soulreturns.ui.input.SoulInput.recordRegion(
                com.soulreturns.ui.input.HitRegion(
                    key = tabKey,
                    x = absX,
                    y = y,
                    width = width,
                    height = m.height,
                    // Deeper than the parent strip so individual tabs win in the hit-test.
                    depth = depth + 1,
                    onClick = { onSelect(i) },
                ),
            )
        }
        // Hover/click on the strip itself (e.g. for an outer container's clickable modifier).
        modifier.recordHitRegions(x, y, m.width, m.height, depth)
    }

    private data class TabKey(val prefix: Any, val index: Int)
}
