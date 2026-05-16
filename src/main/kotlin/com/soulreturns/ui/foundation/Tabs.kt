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
) : SoulNode(),
    com.soulreturns.ui.composer.MojangTextEmitter {
    companion object {
        // Match `Button`'s overall geometry exactly: `Surface(padding = paddingSmall = 8f)`
        // wraps `Box(padding(horizontal = 2f))` wraps body-text. So total height = font + 16,
        // total width per label = text + 20. Matching values keeps a Tabs strip vertically
        // aligned with adjacent Button chips in mixed Rows (HUD footer, screen toolbars, etc).
        private const val TAB_PAD_V = 8f
        private const val TAB_PAD_X = 10f
        private const val TAB_GAP = 4f
        private val FONT
            get() = SoulTheme.typography.body.font
        private val FONT_SIZE
            get() = SoulTheme.typography.body.size
        private val TAB_HEIGHT
            get() = FONT_SIZE + TAB_PAD_V * 2f
    }

    // Computed in measure, consumed in drawSelf.
    private var tabBounds: List<Triple<Float, Float, Float>> = emptyList() // x, width, _

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val c = modifier.applySizeOverride(constraints)

        val widths = options.map { NvgRenderer.textWidth(it, FONT_SIZE, FONT) + TAB_PAD_X * 2f }
        val contentW = widths.sum() + TAB_GAP * (options.size - 1).coerceAtLeast(0)
        val (w, h) = c.constrain(contentW, TAB_HEIGHT)

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
        // If this Tabs sits inside a HUD with `useMinecraftFont` on, the label glyphs are
        // dispatched separately via `SoulHud.renderOne`'s Mojang pipeline (see
        // `emitMojangTexts` below) — skip the `NvgRenderer.text` call here so we don't
        // double-render Inter underneath the Mojang glyphs.
        val hudId = SoulInput.currentHudId
        val skipNvgText =
            hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseMinecraftFont(hudId)
        for ((i, label) in options.withIndex()) {
            val (relX, width) = tabBounds[i]
            val selected = i == selectedIndex
            val tabKey = TabKey(keyPrefix, i)
            val hovered = SoulInput.isHovered(tabKey)
            // "Tab navigation" look — unselected tabs render without a fill so the bar reads
            // as a navigation strip, not a stack of clickable chips. Hover gives a subtle
            // panelHover; selection gets the accent fill. This is what visually distinguishes
            // Tabs from Button despite the matching geometry — Buttons are always filled.
            val bg =
                when {
                    selected && hovered -> SoulTheme.colors.accentDim
                    selected -> SoulTheme.colors.accent
                    hovered -> SoulTheme.colors.panelHover
                    else -> 0
                }
            val absX = x + relX
            if (bg != 0) NvgRenderer.rect(absX, y, width, m.height, bg, SoulTheme.dimens.radiusSmall)

            if (!skipNvgText) {
                val textColor =
                    when {
                        selected -> 0xFFFFFFFFu.toInt()
                        hovered -> SoulTheme.colors.text
                        else -> SoulTheme.colors.textDim
                    }
                // Mirror the per-HUD override logic that `TextNode.drawSelf` runs: pick the
                // bold-variant font when `shouldUseBoldFont` is true, and route through
                // `textShadow` (with the configured multiplier) when `shouldDrawTextShadow`
                // is true. Without this Tabs labels were always painted via the plain
                // `NvgRenderer.text` codepath, ignoring the HUD's shadow / bold toggles.
                val useBold =
                    hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseBoldFont(hudId)
                val effectiveFont = if (useBold) NvgRenderer.boldVariantOf(FONT) else FONT
                val withShadow =
                    hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldDrawTextShadow(hudId)
                val labelW = NvgRenderer.textWidth(label, FONT_SIZE, effectiveFont)
                val textX = absX + (width - labelW) / 2f
                // Center the text vertically within the tab height (font size = glyph cap height).
                val textY = y + (m.height - FONT_SIZE) / 2f
                if (withShadow) {
                    val mult = com.soulreturns.ui.runtime.SoulHud.hudTextShadowSize()
                    NvgRenderer.textShadow(label, textX, textY, FONT_SIZE, textColor, effectiveFont, mult)
                } else {
                    NvgRenderer.text(label, textX, textY, FONT_SIZE, textColor, effectiveFont)
                }
            }

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

    /**
     * Emit each tab label as a `MojangTextSpec` so SoulHud's pre-pass walker can route them
     * through Mojang's font when `useMinecraftFont` is on. Text positions mirror exactly
     * what `drawSelf` computes — same horizontal centering, same vertical centering.
     * Hover/selection color also mirrored so the visual matches.
     */
    override fun emitMojangTexts(
        x: Float,
        y: Float,
        clip: com.soulreturns.ui.composer.ClipRect?,
        emit: (com.soulreturns.ui.composer.MojangTextSpec) -> Unit,
    ) {
        val m = measured ?: return
        for ((i, label) in options.withIndex()) {
            val (relX, width) = tabBounds[i]
            val selected = i == selectedIndex
            val hovered = SoulInput.isHovered(TabKey(keyPrefix, i))
            val textColor =
                when {
                    selected -> 0xFFFFFFFFu.toInt()
                    hovered -> SoulTheme.colors.text
                    else -> SoulTheme.colors.textDim
                }
            val labelW = NvgRenderer.textWidth(label, FONT_SIZE, FONT)
            val absX = x + relX
            val textX = absX + (width - labelW) / 2f
            val textY = y + (m.height - FONT_SIZE) / 2f
            emit(com.soulreturns.ui.composer.MojangTextSpec(label, textX, textY, FONT_SIZE, textColor))
        }
    }

    private data class TabKey(val prefix: Any, val index: Int)
}
