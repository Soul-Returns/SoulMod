package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.clickable
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.theme.SoulTheme

/**
 * Compact rounded button with hover feedback.
 *
 * Three visual states:
 *  - **default**: `Theme.colors.panelHover` background — matches Toggle's off-state track
 *    color choice so the button reads as a distinct surface against the `panelInset`
 *    section cards on the config screen (using `panelInset` for idle would blend the button
 *    into the card and erase its outline). Also visible against translucent HUD `panel`
 *    surfaces.
 *  - **hovered**: `Theme.colors.controlHover` background — a step lighter than the idle
 *    `panelHover`, gives a clear hover lift on top of the already-lifted idle color.
 *  - **accent**: `Theme.colors.accent` (or `accentDim` when hovered) — used by callers that
 *    want a primary / pressed-state look (e.g. a toggle button whose value is on).
 *
 * Hover state is driven by [SoulInput.isHovered] keyed on [key]. The default [key] is
 * pulled from the composer's monotonic auto-key counter — stable as long as the composable
 * tree shape is deterministic between frames. **Dynamic lists must pass an explicit
 * content-derived key** (e.g. `key = creature.name`) so hover stays attached to the right
 * row when items are added/removed/reordered.
 *
 * Clicks only fire while a Minecraft Screen is open — the Soul HUD adapter wires
 * [SoulInput.queueClick] from `ScreenMouseEvents.allowMouseClick`, which doesn't fire on
 * the bare HUD. Buttons therefore feel "live" inside inventories / overlays and "dead"
 * outside, by design.
 *
 * @param centerLabel When the caller passes `modifier = SoulModifier.fillMaxWidth()` (or
 *   anything else that gives the button extra horizontal space) the default label layout
 *   leaves the text pinned at the start of the button. Set [centerLabel] to `true` to
 *   center the text instead — the Surface fills the available width but the label is laid
 *   out by a `Row(fillMaxWidth, Arrangement.Center)` so it sits in the middle.
 */
@SoulComposable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    key: Any = SoulComposer.current.nextAutoKey(),
    accent: Boolean = false,
    centerLabel: Boolean = false,
) {
    val isHovered = SoulInput.isHovered(key)
    val baseColor =
        when {
            accent && isHovered -> SoulTheme.colors.accentDim
            accent -> SoulTheme.colors.accent
            isHovered -> SoulTheme.colors.controlHover
            else -> SoulTheme.colors.panelHover
        }
    Surface(
        modifier = modifier.clickable(key, onClick),
        color = baseColor,
        radius = SoulTheme.dimens.radiusSmall,
        padding = SoulTheme.dimens.paddingSmall,
    ) {
        if (centerLabel) {
            Row(
                modifier = SoulModifier.Empty.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = label,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.body.font,
                )
            }
        } else {
            Box(modifier = SoulModifier.Empty.padding(horizontal = 2f)) {
                Text(
                    text = label,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }
}
