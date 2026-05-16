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
import java.util.concurrent.ConcurrentHashMap

/**
 * Per-popup scroll offset, keyed by the dropdown's trigger key. Populated lazily — most
 * dropdowns never set `popupMaxHeight` and stay un-scrollable, so the map is small. Held
 * in module scope so the offset survives close/re-open cycles within one session (and
 * resets at JVM exit). Reading off the wrong thread is fine — modal popups only render
 * on the render thread, and `ConcurrentHashMap` makes the write-on-scroll safe.
 */
private val popupScrollOffsets: ConcurrentHashMap<Any, Float> = ConcurrentHashMap()

private data class DropdownScrollKey(val parent: Any)

/** One row inside a [MultiSelectDropdown]. */
data class DropdownOption(
    val label: String,
    val selected: Boolean,
    val onToggle: () -> Unit,
)

/**
 * Single-select dropdown. Trigger shows [triggerLabel]; clicking opens a popup above with one
 * row per entry in [options]. Clicking a row fires [onSelect] with its index AND closes the
 * popup. The currently-selected row is highlighted with accent-colored text.
 *
 * For multi-select use [MultiSelectDropdown]. Both variants share the same trigger chrome,
 * popup chrome, and click-routing model — see [MultiSelectDropdown]'s docstring for the
 * depth/scrim contract.
 */
@SoulComposable
fun Dropdown(
    triggerLabel: String,
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    key: Any = SoulComposer.current.nextAutoKey(),
    /**
     * Optional cap on the popup's height. When the option list exceeds [popupMaxHeight] the
     * popup renders at exactly this height, scissor-clips the option rows, and a mouse-
     * wheel scroll region is recorded over the popup body so the user can scroll through
     * the hidden options. `null` means "no cap" — popup grows to fit all options (legacy).
     */
    popupMaxHeight: Float? = null,
) {
    SoulComposer.current.composable(
        SingleSelectDropdownNode(
            triggerLabel = triggerLabel,
            options = options,
            selectedIndex = selectedIndex,
            onSelect = onSelect,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            triggerKey = key,
            modifier = modifier,
            popupMaxHeight = popupMaxHeight,
        ),
    )
}

/**
 * Multi-select dropdown. Trigger shows [label]; clicking opens a popup above with one row per
 * [options] entry, each with a checkbox showing its [DropdownOption.selected] state. Clicking
 * a row fires that option's [DropdownOption.onToggle] callback; the popup stays open so the
 * user can flip multiple in one interaction.
 *
 * **Layout flow.** The widget itself measures to only the trigger button's size — the popup
 * is painted as an *overlay* during the trigger's own `drawSelf` and does NOT affect parent
 * layout. The popup appears above the trigger; panels must have enough room above the trigger
 * or the popup paints outside the visible HUD bounds. This is the simplest viable popup model
 * in the framework today (no separate overlay layer); migrate to a true overlay pass if a
 * widget ever needs to escape a parent scissor.
 *
 * **Click routing.** Trigger + option-row hit regions are recorded at `depth + 200` and a
 * dismiss scrim is recorded at `depth + 100`. `SoulInput.deepestRegionAt` picks the highest
 * depth match, so:
 *  - Click on trigger → toggles `expanded`.
 *  - Click on an option row → fires that option's `onToggle`; popup stays open.
 *  - Click anywhere else in the panel → fires the scrim and closes the popup; the original
 *    click does NOT activate the underlying control (matches standard popup UX).
 *
 * **State ownership.** The caller owns the [expanded] flag. Stateless composables can't store
 * "is this open" across frames without external state — store it on the feature singleton /
 * settings object alongside other UI state (see `FishingHudSettings.columnDropdownOpen`).
 */
@SoulComposable
fun MultiSelectDropdown(
    label: String,
    options: List<DropdownOption>,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    key: Any = SoulComposer.current.nextAutoKey(),
    /** See [Dropdown.popupMaxHeight]. */
    popupMaxHeight: Float? = null,
) {
    SoulComposer.current.composable(
        MultiSelectDropdownNode(
            label = label,
            options = options,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            triggerKey = key,
            modifier = modifier,
            popupMaxHeight = popupMaxHeight,
        ),
    )
}

// ───────────────────────── shared chrome constants ─────────────────────────

private object DropdownChrome {
    // Trigger paddings match Button's effective text inset: Button wraps its label in
    // `Surface(padding = paddingSmall = 8f) { Box(padding = horizontal 2f) { Text(...) } }`,
    // so the text sits at (8+2, 8) inside the click rect. We collapse that into a single
    // horizontal/vertical pair so a dropdown trigger lines up flush with a sibling Button
    // in the same Row.
    const val TRIGGER_PAD_H = 10f
    const val TRIGGER_PAD_V = 8f

    // Caret triangle drawn via `NvgRenderer.filledTriangle` — Inter lacks the geometric
    // shape codepoints (▾/▴) so a primitive triangle is the only sharp option without
    // bundling an extra font.
    const val CARET_WIDTH = 7f
    const val CARET_HEIGHT = 4f
    const val CARET_GAP = 6f

    // Popup metrics.
    const val POPUP_GAP_FROM_TRIGGER = 4f
    const val POPUP_PAD_H = 6f
    const val POPUP_PAD_V = 6f
    const val OPTION_ROW_H = 18f
    const val OPTION_GAP_BETWEEN_INDICATOR_AND_LABEL = 6f
    const val CHECKBOX_SIZE = 12f
    const val CHECKBOX_RADIUS = 2f
    const val CHECKBOX_BORDER_THICKNESS = 1f
    const val CHECK_THICKNESS = 1.5f

    // Oversized scrim — `SoulInput.flush` converts cursor coords to panel-local before
    // hit-testing, so a huge rect just means "anywhere this panel sees the cursor."
    const val SCRIM_BOUND = 4096f

    // Depth boosts. Trigger + option rows live above the scrim so clicking them does NOT
    // also fire the dismiss-on-outside-click handler. Scrim sits well above sibling widgets
    // (Buttons, Toggles, ScrollableList rows) so clicks in the panel during an open
    // dropdown are absorbed for dismissal rather than activating those widgets.
    const val SCRIM_DEPTH_BOOST = 100
    const val INTERACTIVE_DEPTH_BOOST = 200
}

/** Measure the trigger box for a given label, honoring the modifier's size override. */
private fun measureTrigger(
    label: String,
    modifier: SoulModifier,
    constraints: SoulConstraints,
): Pair<Float, Float> {
    val outer = modifier.applySizeOverride(constraints)
    val bodySize = SoulTheme.typography.body.size
    val bodyFont = SoulTheme.typography.body.font
    val labelW = NvgRenderer.textWidth(label, bodySize, bodyFont)
    val intrinsicW = labelW + DropdownChrome.CARET_GAP + DropdownChrome.CARET_WIDTH + 2 * DropdownChrome.TRIGGER_PAD_H
    val intrinsicH = bodySize + 2 * DropdownChrome.TRIGGER_PAD_V
    val (w, h) = outer.constrain(intrinsicW, intrinsicH)
    return w to h
}

/** Paint the trigger pill and register its click region. Common to both dropdown variants. */
private fun drawTriggerChrome(
    label: String,
    x: Float,
    y: Float,
    width: Float,
    height: Float,
    expanded: Boolean,
    depth: Int,
    triggerKey: Any,
    onClick: () -> Unit,
) {
    val hovered = SoulInput.isHovered(triggerKey)
    val bg =
        when {
            expanded -> SoulTheme.colors.panelHover
            hovered -> SoulTheme.colors.panelHover
            else -> SoulTheme.colors.panelInset
        }
    NvgRenderer.rect(x, y, width, height, bg, SoulTheme.dimens.radiusSmall)

    val bodySize = SoulTheme.typography.body.size
    val bodyFont = SoulTheme.typography.body.font
    val textY = y + DropdownChrome.TRIGGER_PAD_V
    // When the host HUD has `useMinecraftFont` on, the Dropdown node implements
    // `MojangTextEmitter` and the trigger label is dispatched through Mojang's font by
    // `SoulHud.renderOne`'s pre-pass walker. Skip the NVG draw here so the same string
    // doesn't render twice (Inter underneath, Mojang glyphs on top). Popup option labels
    // below still render via NVG because they live in a deferred overlay the walker can't
    // reach.
    val hudId = SoulInput.currentHudId
    val mcFont =
        hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseMinecraftFont(hudId)
    if (!mcFont) {
        drawNvgLabelWithHudOverrides(
            label = label,
            x = x + DropdownChrome.TRIGGER_PAD_H,
            y = textY,
            size = bodySize,
            color = SoulTheme.colors.text,
            baseFont = bodyFont,
        )
    }

    // Caret triangle, vertically centered within the trigger. Points down when collapsed,
    // up when expanded — same convention as native OS dropdowns.
    val caretRightX = x + width - DropdownChrome.TRIGGER_PAD_H
    val caretLeftX = caretRightX - DropdownChrome.CARET_WIDTH
    val caretMidX = (caretLeftX + caretRightX) / 2f
    val caretCenterY = y + height / 2f
    val caretTopY = caretCenterY - DropdownChrome.CARET_HEIGHT / 2f
    val caretBottomY = caretCenterY + DropdownChrome.CARET_HEIGHT / 2f
    if (expanded) {
        NvgRenderer.filledTriangle(
            caretLeftX,
            caretBottomY,
            caretRightX,
            caretBottomY,
            caretMidX,
            caretTopY,
            SoulTheme.colors.textDim,
        )
    } else {
        NvgRenderer.filledTriangle(
            caretLeftX,
            caretTopY,
            caretRightX,
            caretTopY,
            caretMidX,
            caretBottomY,
            SoulTheme.colors.textDim,
        )
    }

    SoulInput.recordRegion(
        HitRegion(
            key = triggerKey,
            x = x,
            y = y,
            width = width,
            height = height,
            depth = depth + DropdownChrome.INTERACTIVE_DEPTH_BOOST,
            onClick = onClick,
        ),
    )
}

/**
 * Render [label] at `(x, y)` honoring the currently-active HUD's text-shadow / bold-font
 * overrides — same logic `TextNode.drawSelf` runs. Both Dropdown variants paint label
 * text directly via `NvgRenderer.text` (rather than embedding a `Text` composable), so
 * without this helper the trigger + popup labels would always be plain regular-weight
 * unshadowed glyphs regardless of the HUD's settings. Callers OUTSIDE a SoulHud frame
 * (e.g. dropdowns inside a `SoulScreen`) get the plain `NvgRenderer.text` codepath
 * automatically because `SoulInput.currentHudId` is null there.
 */
private fun drawNvgLabelWithHudOverrides(
    label: String,
    x: Float,
    y: Float,
    size: Float,
    color: Int,
    baseFont: com.soulreturns.platform.render.nvg.NvgFont,
) {
    val hudId = SoulInput.currentHudId
    val useBold =
        hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldUseBoldFont(hudId)
    val effectiveFont = if (useBold) NvgRenderer.boldVariantOf(baseFont) else baseFont
    val withShadow =
        hudId != null && com.soulreturns.ui.runtime.SoulHud.shouldDrawTextShadow(hudId)
    if (withShadow) {
        val mult = com.soulreturns.ui.runtime.SoulHud.hudTextShadowSize()
        NvgRenderer.textShadow(label, x, y, size, color, effectiveFont, mult)
    } else {
        NvgRenderer.text(label, x, y, size, color, effectiveFont)
    }
}

/** Paint the popup background + dismiss scrim. Caller draws the option rows inside. */
private fun drawPopupChrome(
    px: Float,
    py: Float,
    width: Float,
    height: Float,
    depth: Int,
    triggerKey: Any,
    onDismiss: () -> Unit,
) {
    SoulInput.recordRegion(
        HitRegion(
            key = DropdownScrimKey(triggerKey),
            x = -DropdownChrome.SCRIM_BOUND,
            y = -DropdownChrome.SCRIM_BOUND,
            width = 2 * DropdownChrome.SCRIM_BOUND,
            height = 2 * DropdownChrome.SCRIM_BOUND,
            depth = depth + DropdownChrome.SCRIM_DEPTH_BOOST,
            onClick = onDismiss,
        ),
    )
    NvgRenderer.dropShadow(
        px,
        py,
        width,
        height,
        blur = 8f,
        spread = 1f,
        radius = SoulTheme.dimens.radiusSmall,
    )
    NvgRenderer.rect(px, py, width, height, SoulTheme.colors.panelInset, SoulTheme.dimens.radiusSmall)
}

/** Compute popup width from option labels + a minimum of [minWidth] (the trigger width). */
private fun computePopupWidth(
    optionLabels: List<String>,
    minWidth: Float,
    indicatorWidth: Float,
): Float {
    val bodySize = SoulTheme.typography.body.size
    val bodyFont = SoulTheme.typography.body.font
    val maxLabelW = optionLabels.maxOfOrNull { NvgRenderer.textWidth(it, bodySize, bodyFont) } ?: 0f
    val contentW = indicatorWidth + DropdownChrome.OPTION_GAP_BETWEEN_INDICATOR_AND_LABEL + maxLabelW
    return (contentW + 2 * DropdownChrome.POPUP_PAD_H).coerceAtLeast(minWidth)
}

/**
 * Decide whether the popup should open above or below the trigger. Compares the panel-local
 * room available on each side — opens into whichever side fits the popup (or has more space
 * when neither side can hold it whole). Falls back to "above" when no panel size is known
 * (e.g. unit tests, or before any panel has rendered).
 *
 * Why panel-local instead of screen-relative: the popup is bounded by the PIP texture, not
 * by the screen. A HUD anchored to the top-left of the screen has plenty of room "below" in
 * screen terms, but if its footer trigger sits near the panel's bottom edge there's no room
 * for a tall popup inside the texture. Panel-local math catches that case.
 */
private fun computePopupY(
    triggerLocalY: Float,
    triggerHeight: Float,
    popupHeight: Float,
): Float {
    val panelH = SoulInput.panelHeight
    if (panelH <= 0f) {
        return triggerLocalY - popupHeight - DropdownChrome.POPUP_GAP_FROM_TRIGGER
    }
    val gap = DropdownChrome.POPUP_GAP_FROM_TRIGGER
    val roomAbove = triggerLocalY - gap
    val roomBelow = panelH - (triggerLocalY + triggerHeight) - gap
    val openBelow =
        when {
            roomBelow >= popupHeight -> true
            roomAbove >= popupHeight -> false
            else -> roomBelow >= roomAbove // neither fits fully — pick the larger side
        }
    return if (openBelow) {
        triggerLocalY + triggerHeight + gap
    } else {
        triggerLocalY - popupHeight - gap
    }
}

private data class DropdownScrimKey(val parent: Any)

private data class DropdownOptionKey(val parent: Any, val index: Int)

// ───────────────────────────── nodes ─────────────────────────────

internal class MultiSelectDropdownNode(
    private val label: String,
    private val options: List<DropdownOption>,
    private val expanded: Boolean,
    private val onExpandedChange: (Boolean) -> Unit,
    private val triggerKey: Any,
    override val modifier: SoulModifier,
    private val popupMaxHeight: Float? = null,
) : SoulNode(),
    com.soulreturns.ui.composer.MojangTextEmitter {
    /**
     * Emit just the trigger label. Popup option labels live in `SoulInput.queueOverlay`'s
     * deferred queue and aren't reachable from the SoulHud walker — they remain
     * NVG-rendered (Inter glyphs) even when the host HUD has `useMinecraftFont` on.
     */
    override fun emitMojangTexts(
        x: Float,
        y: Float,
        clip: com.soulreturns.ui.composer.ClipRect?,
        emit: (com.soulreturns.ui.composer.MojangTextSpec) -> Unit,
    ) {
        val bodySize = SoulTheme.typography.body.size
        emit(
            com.soulreturns.ui.composer.MojangTextSpec(
                text = label,
                x = x + DropdownChrome.TRIGGER_PAD_H,
                y = y + DropdownChrome.TRIGGER_PAD_V,
                size = bodySize,
                color = SoulTheme.colors.text,
            ),
        )
    }

    /**
     * Report the open popup's panel-local bounds so SoulHud's Mojang dispatcher can avoid
     * painting HUD row text over the popup. The popup itself + its option labels render in
     * the PIP via NanoVG (Inter glyphs); Mojang text dispatch happens AFTER the PIP
     * composites, so without this filter HUD row text would bleed over the popup.
     * Geometry mirrors what `drawSelf`'s overlay path computes (same `computePopupY`).
     */
    override fun emitOcclusions(
        x: Float,
        y: Float,
        emit: (com.soulreturns.ui.composer.ClipRect) -> Unit,
    ) {
        if (!expanded) return
        val effectiveH = popupMaxHeight?.coerceAtMost(popupH) ?: popupH
        val py = computePopupY(y, triggerH, effectiveH)
        emit(com.soulreturns.ui.composer.ClipRect(x, py, popupW, effectiveH))
    }
    private var triggerW = 0f
    private var triggerH = 0f
    private var popupW = 0f
    private var popupH = 0f

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val (w, h) = measureTrigger(label, modifier, constraints)
        triggerW = w
        triggerH = h
        popupW = computePopupWidth(options.map { it.label }, triggerW, DropdownChrome.CHECKBOX_SIZE)
        popupH = options.size * DropdownChrome.OPTION_ROW_H + 2 * DropdownChrome.POPUP_PAD_V
        val out = SoulMeasured(triggerW, triggerH)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        drawTriggerChrome(
            label = label,
            x = x,
            y = y,
            width = triggerW,
            height = triggerH,
            expanded = expanded,
            depth = depth,
            triggerKey = triggerKey,
            onClick = { onExpandedChange(!expanded) },
        )
        if (!expanded) return

        // Deferred paint: the popup needs to land **on top** of later siblings (the rest
        // of the footer column, the reset button, etc.). Queueing the visual + hit-region
        // work means the host (`SoulHud.renderOne`) drains it after the main tree finishes.
        SoulInput.queueOverlay {
            // Modal mode — only regions recorded inside this block are consulted for
            // click / hover dispatch. Sibling triggers (recorded in the regular drawSelf
            // pass before this overlay runs) become inert until the popup closes, so a
            // click on another dropdown's trigger goes to this popup's scrim instead.
            SoulInput.beginModal()

            val px = x
            val effectiveH = popupMaxHeight?.coerceAtMost(popupH) ?: popupH
            val maxScroll = (popupH - effectiveH).coerceAtLeast(0f)
            val scrollOffset = (popupScrollOffsets[triggerKey] ?: 0f).coerceIn(0f, maxScroll)
            if (scrollOffset != popupScrollOffsets[triggerKey] && maxScroll > 0f) {
                popupScrollOffsets[triggerKey] = scrollOffset
            }
            val py = computePopupY(y, triggerH, effectiveH)
            drawPopupChrome(
                px,
                py,
                popupW,
                effectiveH,
                depth = depth,
                triggerKey = triggerKey,
                onDismiss = { onExpandedChange(false) },
            )

            // Clip option rows to the popup's visible window so a scrolled-off row doesn't
            // bleed onto the surrounding HUD.
            NvgRenderer.pushScissor(px, py, popupW, effectiveH)

            val bodySize = SoulTheme.typography.body.size
            val bodyFont = SoulTheme.typography.body.font
            var ry = py + DropdownChrome.POPUP_PAD_V - scrollOffset
            for ((idx, option) in options.withIndex()) {
                val rowX = px + DropdownChrome.POPUP_PAD_H
                val rowY = ry
                val rowW = popupW - 2 * DropdownChrome.POPUP_PAD_H
                val optionKey = DropdownOptionKey(triggerKey, idx)

                if (SoulInput.isHovered(optionKey)) {
                    NvgRenderer.rect(rowX, rowY, rowW, DropdownChrome.OPTION_ROW_H, SoulTheme.colors.panelHover, DropdownChrome.CHECKBOX_RADIUS)
                }
                drawCheckbox(rowX, rowY + (DropdownChrome.OPTION_ROW_H - DropdownChrome.CHECKBOX_SIZE) / 2f, option.selected)

                val labelX = rowX + DropdownChrome.CHECKBOX_SIZE + DropdownChrome.OPTION_GAP_BETWEEN_INDICATOR_AND_LABEL
                val labelY = rowY + (DropdownChrome.OPTION_ROW_H - bodySize) / 2f
                drawNvgLabelWithHudOverrides(option.label, labelX, labelY, bodySize, SoulTheme.colors.text, bodyFont)

                SoulInput.recordRegion(
                    HitRegion(
                        key = optionKey,
                        x = rowX,
                        y = rowY,
                        width = rowW,
                        height = DropdownChrome.OPTION_ROW_H,
                        depth = depth + DropdownChrome.INTERACTIVE_DEPTH_BOOST,
                        onClick = { option.onToggle() },
                    ),
                )
                ry += DropdownChrome.OPTION_ROW_H
            }

            NvgRenderer.popScissor()

            // Scroll wheel region — covers the visible popup body. Sits at a depth between
            // the scrim and the option rows so option clicks still win when they overlap.
            if (maxScroll > 0f) {
                SoulInput.recordRegion(
                    HitRegion(
                        key = DropdownScrollKey(triggerKey),
                        x = px,
                        y = py,
                        width = popupW,
                        height = effectiveH,
                        depth = depth + DropdownChrome.SCRIM_DEPTH_BOOST + 10,
                        onScroll = { vsd ->
                            val raw = scrollOffset - vsd * DropdownChrome.OPTION_ROW_H
                            popupScrollOffsets[triggerKey] = raw.coerceIn(0f, maxScroll)
                        },
                    ),
                )
            }
        }
    }

    private fun drawCheckbox(
        x: Float,
        y: Float,
        selected: Boolean,
    ) {
        if (selected) {
            NvgRenderer.rect(
                x,
                y,
                DropdownChrome.CHECKBOX_SIZE,
                DropdownChrome.CHECKBOX_SIZE,
                SoulTheme.colors.accent,
                DropdownChrome.CHECKBOX_RADIUS
            )
            // Two-segment check tick. Coords tuned visually for the 12px box.
            val pad = DropdownChrome.CHECKBOX_SIZE * 0.18f
            val leftX = x + pad
            val midX = x + DropdownChrome.CHECKBOX_SIZE * 0.42f
            val midY = y + DropdownChrome.CHECKBOX_SIZE * 0.62f
            val rightX = x + DropdownChrome.CHECKBOX_SIZE - pad
            val bottomY = y + DropdownChrome.CHECKBOX_SIZE - pad
            val topY = y + pad
            NvgRenderer.line(leftX, midY, midX, bottomY, DropdownChrome.CHECK_THICKNESS, 0xFFFFFFFF.toInt())
            NvgRenderer.line(midX, bottomY, rightX, topY, DropdownChrome.CHECK_THICKNESS, 0xFFFFFFFF.toInt())
        } else {
            NvgRenderer.rect(
                x,
                y,
                DropdownChrome.CHECKBOX_SIZE,
                DropdownChrome.CHECKBOX_SIZE,
                SoulTheme.colors.panel,
                DropdownChrome.CHECKBOX_RADIUS
            )
            NvgRenderer.hollowRect(
                x,
                y,
                DropdownChrome.CHECKBOX_SIZE,
                DropdownChrome.CHECKBOX_SIZE,
                DropdownChrome.CHECKBOX_BORDER_THICKNESS,
                SoulTheme.colors.textDim,
                DropdownChrome.CHECKBOX_RADIUS,
            )
        }
    }
}

internal class SingleSelectDropdownNode(
    private val triggerLabel: String,
    private val options: List<String>,
    private val selectedIndex: Int,
    private val onSelect: (Int) -> Unit,
    private val expanded: Boolean,
    private val onExpandedChange: (Boolean) -> Unit,
    private val triggerKey: Any,
    override val modifier: SoulModifier,
    private val popupMaxHeight: Float? = null,
) : SoulNode(),
    com.soulreturns.ui.composer.MojangTextEmitter {
    /** See [MultiSelectDropdownNode.emitMojangTexts] — only the trigger label is reachable. */
    override fun emitMojangTexts(
        x: Float,
        y: Float,
        clip: com.soulreturns.ui.composer.ClipRect?,
        emit: (com.soulreturns.ui.composer.MojangTextSpec) -> Unit,
    ) {
        val bodySize = SoulTheme.typography.body.size
        emit(
            com.soulreturns.ui.composer.MojangTextSpec(
                text = triggerLabel,
                x = x + DropdownChrome.TRIGGER_PAD_H,
                y = y + DropdownChrome.TRIGGER_PAD_V,
                size = bodySize,
                color = SoulTheme.colors.text,
            ),
        )
    }

    /** See [MultiSelectDropdownNode.emitOcclusions]. */
    override fun emitOcclusions(
        x: Float,
        y: Float,
        emit: (com.soulreturns.ui.composer.ClipRect) -> Unit,
    ) {
        if (!expanded) return
        val effectiveH = popupMaxHeight?.coerceAtMost(popupH) ?: popupH
        val py = computePopupY(y, triggerH, effectiveH)
        emit(com.soulreturns.ui.composer.ClipRect(x, py, popupW, effectiveH))
    }
    companion object {
        // Width of the selected-row indicator (a thin accent bar on the left of the row).
        // Reuses the checkbox horizontal slot so popup-width math lines up.
        private const val INDICATOR_BAR_WIDTH = 3f
    }

    private var triggerW = 0f
    private var triggerH = 0f
    private var popupW = 0f
    private var popupH = 0f

    override fun measure(constraints: SoulConstraints): SoulMeasured {
        val (w, h) = measureTrigger(triggerLabel, modifier, constraints)
        triggerW = w
        triggerH = h
        popupW = computePopupWidth(options, triggerW, INDICATOR_BAR_WIDTH)
        popupH = options.size * DropdownChrome.OPTION_ROW_H + 2 * DropdownChrome.POPUP_PAD_V
        val out = SoulMeasured(triggerW, triggerH)
        measured = out
        return out
    }

    override fun drawSelf(
        x: Float,
        y: Float,
        depth: Int,
    ) {
        drawTriggerChrome(
            label = triggerLabel,
            x = x,
            y = y,
            width = triggerW,
            height = triggerH,
            expanded = expanded,
            depth = depth,
            triggerKey = triggerKey,
            onClick = { onExpandedChange(!expanded) },
        )
        if (!expanded) return

        // Deferred paint: see MultiSelectDropdownNode for the rationale (popups must paint
        // after every other widget in the same panel so they overlay correctly).
        SoulInput.queueOverlay {
            // Modal mode — see MultiSelectDropdownNode comment.
            SoulInput.beginModal()

            val px = x
            val effectiveH = popupMaxHeight?.coerceAtMost(popupH) ?: popupH
            val maxScroll = (popupH - effectiveH).coerceAtLeast(0f)
            val scrollOffset = (popupScrollOffsets[triggerKey] ?: 0f).coerceIn(0f, maxScroll)
            if (scrollOffset != popupScrollOffsets[triggerKey] && maxScroll > 0f) {
                popupScrollOffsets[triggerKey] = scrollOffset
            }
            val py = computePopupY(y, triggerH, effectiveH)
            drawPopupChrome(
                px,
                py,
                popupW,
                effectiveH,
                depth = depth,
                triggerKey = triggerKey,
                onDismiss = { onExpandedChange(false) },
            )

            // Clip option rows to popup's visible window (see MultiSelectDropdownNode).
            NvgRenderer.pushScissor(px, py, popupW, effectiveH)

            val bodySize = SoulTheme.typography.body.size
            val bodyFont = SoulTheme.typography.body.font
            var ry = py + DropdownChrome.POPUP_PAD_V - scrollOffset
            for ((idx, optionLabel) in options.withIndex()) {
                val rowX = px + DropdownChrome.POPUP_PAD_H
                val rowY = ry
                val rowW = popupW - 2 * DropdownChrome.POPUP_PAD_H
                val optionKey = DropdownOptionKey(triggerKey, idx)
                val isSelected = idx == selectedIndex

                if (SoulInput.isHovered(optionKey)) {
                    NvgRenderer.rect(
                        rowX,
                        rowY,
                        rowW,
                        DropdownChrome.OPTION_ROW_H,
                        SoulTheme.colors.panelHover,
                        DropdownChrome.CHECKBOX_RADIUS,
                    )
                }

                // Selected indicator: thin accent vertical bar on the left of the row.
                if (isSelected) {
                    val barH = DropdownChrome.OPTION_ROW_H * 0.6f
                    NvgRenderer.rect(
                        rowX,
                        rowY + (DropdownChrome.OPTION_ROW_H - barH) / 2f,
                        INDICATOR_BAR_WIDTH,
                        barH,
                        SoulTheme.colors.accent,
                        1f,
                    )
                }

                val labelX = rowX + INDICATOR_BAR_WIDTH + DropdownChrome.OPTION_GAP_BETWEEN_INDICATOR_AND_LABEL
                val labelY = rowY + (DropdownChrome.OPTION_ROW_H - bodySize) / 2f
                val labelColor = if (isSelected) SoulTheme.colors.accent else SoulTheme.colors.text
                drawNvgLabelWithHudOverrides(optionLabel, labelX, labelY, bodySize, labelColor, bodyFont)

                SoulInput.recordRegion(
                    HitRegion(
                        key = optionKey,
                        x = rowX,
                        y = rowY,
                        width = rowW,
                        height = DropdownChrome.OPTION_ROW_H,
                        depth = depth + DropdownChrome.INTERACTIVE_DEPTH_BOOST,
                        onClick = {
                            onSelect(idx)
                            onExpandedChange(false)
                        },
                    ),
                )
                ry += DropdownChrome.OPTION_ROW_H
            }

            NvgRenderer.popScissor()

            // Mouse-wheel scroll region — only when content actually overflows.
            if (maxScroll > 0f) {
                SoulInput.recordRegion(
                    HitRegion(
                        key = DropdownScrollKey(triggerKey),
                        x = px,
                        y = py,
                        width = popupW,
                        height = effectiveH,
                        depth = depth + DropdownChrome.SCRIM_DEPTH_BOOST + 10,
                        onScroll = { vsd ->
                            val raw = scrollOffset - vsd * DropdownChrome.OPTION_ROW_H
                            popupScrollOffsets[triggerKey] = raw.coerceIn(0f, maxScroll)
                        },
                    ),
                )
            }
        }
    }
}
