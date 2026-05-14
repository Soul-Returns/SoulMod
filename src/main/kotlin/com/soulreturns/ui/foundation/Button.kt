package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.clickable
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.theme.SoulTheme

/**
 * Compact rounded button with hover feedback.
 *
 * Two visual states:
 *  - **default**: `Theme.colors.panel` background, body text.
 *  - **hovered**: `Theme.colors.panelHover` background.
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
 */
@SoulComposable
fun Button(
    label: String,
    onClick: () -> Unit,
    modifier: SoulModifier = SoulModifier.Empty,
    key: Any = SoulComposer.current.nextAutoKey(),
    accent: Boolean = false,
) {
    val isHovered = SoulInput.isHovered(key)
    val baseColor =
        when {
            accent && isHovered -> SoulTheme.colors.accentDim
            accent -> SoulTheme.colors.accent
            isHovered -> SoulTheme.colors.panelHover
            else -> SoulTheme.colors.panel
        }
    Surface(
        modifier = modifier.clickable(key, onClick),
        color = baseColor,
        radius = SoulTheme.dimens.radiusSmall,
        padding = SoulTheme.dimens.paddingSmall,
    ) {
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
