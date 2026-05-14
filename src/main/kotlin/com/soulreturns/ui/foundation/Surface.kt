package com.soulreturns.ui.foundation

import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.theme.SoulTheme

/**
 * Themed background container — a [Box] with a rounded background applied from [SoulTheme].
 *
 * This is the canonical way to start any sub-panel in a Soul HUD or screen. Defaults to:
 *  - background color: [SoulTheme.colors.panel] (translucent dark gray)
 *  - corner radius: [SoulTheme.dimens.radiusMedium]
 *  - content padding: [SoulTheme.dimens.paddingMedium] on every side
 *
 * Any non-null override replaces just that token; pass `padding = 0f` to disable padding,
 * or wrap children in a [Box] for irregular insets.
 */
@SoulComposable
fun Surface(
    modifier: SoulModifier = SoulModifier.Empty,
    color: Int = SoulTheme.colors.panel,
    radius: Float = SoulTheme.dimens.radiusMedium,
    padding: Float = SoulTheme.dimens.paddingMedium,
    content: @SoulComposable () -> Unit = {},
) {
    val styled = modifier.background(color = color, radius = radius).padding(padding)
    Box(modifier = styled, content = content)
}
