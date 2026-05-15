package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme

/**
 * Small "Fishing Festival 38m 12s left" sticker, separate from the main [FishingHud] so the
 * user can position it independently via `/soul gui`.
 *
 * Visible only while a festival is active (driven by [FishingFestivalState.active]) AND
 * `cfg.fishing.fishingHud.showFestivalTimer()`. Auto-hides on festival end / when leaving
 * Skyblock.
 */
object FishingFestivalHud {
    private const val HUD_ID = "fishing_festival_sticker"
    private const val FESTIVAL_GOLD = 0xFFFFAA00.toInt() // §6

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            // "Fishing Festival — 38m 12s left" at heading-size weight + 12 px Surface
            // padding on each side adds up to ~250 logical px wide; round to 280 so the
            // PIP texture doesn't clip the trailing "left" on the right edge. Height bumped
            // to 44 so the heading text (13 px) clears its 12 px top + bottom padding.
            width = 280,
            height = 44,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.42,
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        val visible =
            cfg.fishing.fishingHud.showHud() &&
                cfg.fishing.fishingHud.showFestivalTimer() &&
                cfg.fishing.fishingTracker.enableTracker() &&
                SkyblockApi.isOnSkyblock &&
                FishingFestivalState.active
        if (!visible) {
            Box {}
            return
        }
        Surface(
            color = if (SoulHud.shouldDrawBackground(HUD_ID)) SoulTheme.colors.panel else 0x00000000,
        ) {
            Row(gap = 6f) {
                Text(
                    text = "Fishing Festival",
                    size = SoulTheme.typography.heading.size,
                    color = FESTIVAL_GOLD,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text = "—",
                    size = SoulTheme.typography.heading.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text = "${formatDuration(FishingFestivalState.remainingMs())} left",
                    size = SoulTheme.typography.heading.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.heading.font,
                )
            }
        }
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val m = totalSec / 60
        val s = totalSec % 60
        return "%dm %02ds".format(m, s)
    }
}
