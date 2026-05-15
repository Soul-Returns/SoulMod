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
) {
    SoulComposer.current.composable(
        MultiSelectDropdownNode(
            label = label,
            options = options,
            expanded = expanded,
            onExpandedChange = onExpandedChange,
            triggerKey = key,
            modifier = modifier,
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
    NvgRenderer.text(label, x + DropdownChrome.TRIGGER_PAD_H, textY, bodySize, SoulTheme.colors.text, bodyFont)

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
) : SoulNode() {
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

        val px = x
        val py = y - popupH - DropdownChrome.POPUP_GAP_FROM_TRIGGER
        drawPopupChrome(
            px,
            py,
            popupW,
            popupH,
            depth = depth,
            triggerKey = triggerKey,
            onDismiss = { onExpandedChange(false) },
        )

        val bodySize = SoulTheme.typography.body.size
        val bodyFont = SoulTheme.typography.body.font
        var ry = py + DropdownChrome.POPUP_PAD_V
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
            NvgRenderer.text(option.label, labelX, labelY, bodySize, SoulTheme.colors.text, bodyFont)

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
) : SoulNode() {
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

        val px = x
        val py = y - popupH - DropdownChrome.POPUP_GAP_FROM_TRIGGER
        drawPopupChrome(
            px,
            py,
            popupW,
            popupH,
            depth = depth,
            triggerKey = triggerKey,
            onDismiss = { onExpandedChange(false) },
        )

        val bodySize = SoulTheme.typography.body.size
        val bodyFont = SoulTheme.typography.body.font
        var ry = py + DropdownChrome.POPUP_PAD_V
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
            NvgRenderer.text(optionLabel, labelX, labelY, bodySize, labelColor, bodyFont)

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
    }
}
