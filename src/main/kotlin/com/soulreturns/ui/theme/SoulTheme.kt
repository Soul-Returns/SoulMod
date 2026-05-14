package com.soulreturns.ui.theme

import com.soulreturns.platform.render.nvg.NvgFont
import com.soulreturns.platform.render.nvg.NvgRenderer

/**
 * Centralized palette + spacing + typography tokens for the Soul UI framework.
 *
 * Composables read from [SoulTheme.colors], [SoulTheme.dimens], [SoulTheme.typography]
 * rather than hard-coding values so the look-and-feel can be swapped wholesale later (e.g.
 * for a high-contrast accessibility variant, or future per-user theme customization).
 *
 * Mirrors the static `Theme.kt` palette already used by the legacy owo-ui screens — kept
 * intentionally aligned so HUDs migrated to the Soul framework stay visually consistent
 * with screens still on the old framework during the P4 migration. Full theme abstraction
 * (light/dark modes, accent customization, runtime swap) lands in P5.
 *
 * Numeric units are **logical pixels** at the panel's coordinate system (which NanoVG
 * up-scales by DPR for crispness — see [com.soulreturns.platform.render.nvg.NvgPipRenderer]).
 */
object SoulTheme {
    val colors: SoulColors = SoulColors.Default
    val dimens: SoulDimens = SoulDimens.Default
    val typography: SoulTypography = SoulTypography.Default
}

/** ARGB-packed (`0xAARRGGBB`) color tokens. */
data class SoulColors(
    /** Page / root background. Rare to use directly; most surfaces sit on [panel]. */
    val background: Int,
    /** Standard surface color — translucent dark gray for HUD panels. */
    val panel: Int,
    /** Slightly elevated surface — hover state, nested panels. */
    val panelHover: Int,
    /** Inset / pressed surface — sub-regions, depressed buttons. */
    val panelInset: Int,
    /** Hairline separator between sections. */
    val separator: Int,
    /** Accent color for active states, primary actions, key data. */
    val accent: Int,
    /** Slightly darker accent for hover/pressed accent surfaces. */
    val accentDim: Int,
    /** Foreground text on standard surfaces. */
    val text: Int,
    /** Secondary text — labels, captions, dim values. */
    val textDim: Int,
    /** Tertiary text — almost-disabled, placeholder. */
    val textFaint: Int,
) {
    companion object {
        /** Default dark palette. ARGB values mirror the legacy `Theme.kt` constants. */
        val Default: SoulColors =
            SoulColors(
                background = 0xFF0D0D0D.toInt(),
                panel = 0xD81A1A1A.toInt(),
                panelHover = 0xE0242424.toInt(),
                panelInset = 0xFF1E1E1E.toInt(),
                separator = 0xFF2A2A2A.toInt(),
                accent = 0xFF3B82F6.toInt(),
                accentDim = 0xFF2563EB.toInt(),
                text = 0xFFEEEEEE.toInt(),
                textDim = 0xFF888888.toInt(),
                textFaint = 0xFF555555.toInt(),
            )
    }
}

/** Spacing + corner-radius tokens. */
data class SoulDimens(
    val radiusSmall: Float,
    val radiusMedium: Float,
    val radiusLarge: Float,
    val paddingTight: Float,
    val paddingSmall: Float,
    val paddingMedium: Float,
    val paddingLarge: Float,
    val separatorThickness: Float,
) {
    companion object {
        val Default: SoulDimens =
            SoulDimens(
                radiusSmall = 4f,
                radiusMedium = 6f,
                radiusLarge = 10f,
                paddingTight = 4f,
                paddingSmall = 8f,
                paddingMedium = 12f,
                paddingLarge = 16f,
                separatorThickness = 1f,
            )
    }
}

/** Typography scale: face + default size for each role. */
data class SoulTypography(
    val title: SoulTextStyle,
    val heading: SoulTextStyle,
    val body: SoulTextStyle,
    val caption: SoulTextStyle,
    val mono: SoulTextStyle,
) {
    companion object {
        val Default: SoulTypography =
            SoulTypography(
                title = SoulTextStyle(NvgRenderer.semiBoldFont, 16f),
                heading = SoulTextStyle(NvgRenderer.semiBoldFont, 13f),
                body = SoulTextStyle(NvgRenderer.defaultFont, 11f),
                caption = SoulTextStyle(NvgRenderer.defaultFont, 10f),
                // Inter is not monospace, but bundling JetBrains Mono is deferred. Medium
                // weight gives slightly more visual weight for numeric columns vs Regular.
                mono = SoulTextStyle(NvgRenderer.mediumFont, 11f),
            )
    }
}

/** Pairing of [font] + [size] for use with [com.soulreturns.ui.foundation.Text]. */
data class SoulTextStyle(val font: NvgFont, val size: Float)
