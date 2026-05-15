package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.features.farming.FarmingTimer
import com.soulreturns.features.farming.seasoning.SeasoningState
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import net.minecraft.client.Minecraft

/**
 * Seasoning HUD overlay — reads from [SeasoningState] / [FarmingTimer] / [LocationApi] / [cfg]
 * and renders Total / Next Milestone / Farming Time / Per Hour lines plus a `Reset Session`
 * button (only clickable while a screen is open — keeps it out of the way during play).
 *
 * Migrated from legacy `GuiLayoutApi.updateTextBlock` + manual hit-region tracking to
 * [SoulHud.register] + the framework's [Button] composable per the P3 framework migration.
 */
object SeasoningHud {
    private const val HUD_ID = "seasoning_tracker"
    private const val COLOR_TITLE_GREEN = 0xFF55FF55.toInt() // §a
    private const val COLOR_PAUSED_RED = 0xFFFF5555.toInt() // §c
    private const val COLOR_MAXED_RED = 0xFFFF5555.toInt() // §c §l

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 220,
            height = 160,
            // Top-left. Shares its default slot with the Fishing tracker (only one is
            // contextually relevant per area — Farming/Garden vs Fishing islands).
            defaultAnchorX = 0.01,
            defaultAnchorY = 0.02,
            settingsCategory = "farming",
            settingsSubcategory = "seasonings",
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        val cfgFlags = cfg.farming.seasonings
        val showHud = cfgFlags.enableTracker() && LocationApi.isInArea("Garden")
        if (!showHud) {
            Box {}
            return
        }
        val anyScreenOpen = Minecraft.getInstance().screen != null
        val total = SeasoningState.total
        val targets = SeasoningState.targets

        Surface(
            color = if (SoulHud.shouldDrawBackground(HUD_ID)) SoulTheme.colors.panel else 0x00000000,
        ) {
            Column(gap = 4f) {
                Text(
                    text = "Seasonings",
                    size = SoulTheme.typography.heading.size,
                    color = COLOR_TITLE_GREEN,
                    font = SoulTheme.typography.heading.font,
                )

                val totalLabel =
                    if (cfgFlags.showMaxMilestone() && targets.isNotEmpty()) {
                        "Total: $total/${targets.last()}"
                    } else {
                        "Total: $total"
                    }
                Text(
                    text = totalLabel,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.body.font,
                )

                if (cfgFlags.showNextMilestone()) {
                    val next = targets.firstOrNull { it > total }
                    when {
                        targets.isEmpty() ->
                            Text(
                                text = "Next Milestone: ?",
                                size = SoulTheme.typography.body.size,
                                color = SoulTheme.colors.textDim,
                                font = SoulTheme.typography.body.font,
                            )
                        next == null ->
                            Row(gap = 4f) {
                                Text(
                                    text = "Next Milestone:",
                                    size = SoulTheme.typography.body.size,
                                    color = SoulTheme.colors.textDim,
                                    font = SoulTheme.typography.body.font,
                                )
                                Text(
                                    text = "Maxed",
                                    size = SoulTheme.typography.body.size,
                                    color = COLOR_MAXED_RED,
                                    font = SoulTheme.typography.heading.font,
                                )
                            }
                        else ->
                            Text(
                                text = "Next Milestone: $total/$next",
                                size = SoulTheme.typography.body.size,
                                color = SoulTheme.colors.text,
                                font = SoulTheme.typography.body.font,
                            )
                    }
                }

                if (cfgFlags.showFarmingTime()) {
                    val timeStr = formatDuration(SeasoningState.seasoningFarmingMs())
                    Row(gap = 4f) {
                        Text(
                            text = "Farming Time: $timeStr",
                            size = SoulTheme.typography.body.size,
                            color = SoulTheme.colors.text,
                            font = SoulTheme.typography.body.font,
                        )
                        if (FarmingTimer.isPaused) {
                            Text(
                                text = "(Paused)",
                                size = SoulTheme.typography.body.size,
                                color = COLOR_PAUSED_RED,
                                font = SoulTheme.typography.body.font,
                            )
                        }
                    }
                }

                if (cfgFlags.showPerHour()) {
                    val perHour = computePerHour()
                    Text(
                        text = "Per hour: ${if (perHour == null) "—" else "%,d".format(perHour)}",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.body.font,
                    )
                }

                // Reset session button — only while a screen is open so it doesn't get
                // mis-clicked during play. Hit-testing only fires inside container screens
                // anyway (see SoulGuiHudAdapter.registerScreenOverlay), but the button is
                // hidden unconditionally outside any screen for visual cleanliness.
                if (anyScreenOpen) {
                    Row(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 4f) {
                        Button(
                            label = "Reset Session",
                            onClick = { SeasoningState.resetSession() },
                            key = "$HUD_ID.reset",
                        )
                    }
                }
            }
        }
    }

    /**
     * Per-hour rate based on [SeasoningState.sessionChatGain] divided by active farming time.
     * Returns null until we have ≥5 s of active farming AND ≥1 chat-counted seasoning (avoids
     * a "0 per hour" flicker right after entering the Garden).
     */
    private fun computePerHour(): Long? {
        val farmingMs = SeasoningState.seasoningFarmingMs()
        if (farmingMs < 5_000L || SeasoningState.sessionChatGain <= 0L) return null
        val hours = farmingMs / 3_600_000.0
        return (SeasoningState.sessionChatGain / hours).toLong()
    }

    private fun formatDuration(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }
}
