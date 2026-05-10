package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.features.farming.FarmingTimer
import com.soulreturns.features.farming.seasoning.SeasoningState
import com.soulreturns.gui.lib.GuiLayoutApi
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.TextBlockElement
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft

/**
 * The Seasoning HUD overlay — purely presentational, reads from [SeasoningState] /
 * [FarmingTimer] / [LocationApi] / [cfg]. Click handling for the `[Reset Session]` line
 * lives here too because it's a UI affordance, not application logic.
 */
object SeasoningHud {
    private const val ELEMENT_ID = "seasoning_tracker"
    private const val RESET_BUTTON_TEXT = "[Reset Session]"

    /** Bbox of the [Reset Session] button on screen, set during HUD render, cleared when the line isn't shown. */
    @Volatile
    private var resetButtonBbox: IntArray? = null

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ -> updateHud() })

        // Per-screen mouse listener so the [Reset Session] button is clickable while any screen is open.
        ScreenEvents.AFTER_INIT.register(ScreenEvents.AfterInit { _, screen, _, _ ->
            ScreenMouseEvents.beforeMouseClick(screen).register(
                ScreenMouseEvents.BeforeMouseClick { _, click ->
                    tryHandleResetClick(click.x().toInt(), click.y().toInt())
                }
            )
        })
    }

    private fun updateHud() {
        val total = SeasoningState.total
        val targets = SeasoningState.targets
        val cfgFlags = cfg.farming.seasonings

        val showHud = cfgFlags.enableTracker() && LocationApi.isInArea("Garden")
        val anyScreenOpen = Minecraft.getInstance().screen != null

        val lines = mutableListOf<String>()

        // Total: 64  OR  Total: 64/250
        lines += if (cfgFlags.showMaxMilestone() && targets.isNotEmpty())
            "Total: $total/${targets.last()}"
        else "Total: $total"

        if (cfgFlags.showNextMilestone()) {
            val next = targets.firstOrNull { it > total }
            lines += when {
                targets.isEmpty() -> "Next Milestone: ?"
                next == null      -> "Next Milestone: §c§lMaxed"
                else              -> "Next Milestone: $total/$next"
            }
        }

        if (cfgFlags.showFarmingTime()) {
            val timeStr = formatDuration(SeasoningState.seasoningFarmingMs())
            lines += if (FarmingTimer.isPaused) "Farming Time: $timeStr §c(Paused)"
                     else "Farming Time: $timeStr"
        }

        if (cfgFlags.showPerHour()) {
            val perHour = computePerHour()
            lines += "Per hour: ${if (perHour == null) "—" else "%,d".format(perHour)}"
        }

        // Reset session button — only while a screen is open (so it doesn't clutter normal play).
        if (showHud && anyScreenOpen) {
            lines += "§b§n$RESET_BUTTON_TEXT"
            updateResetButtonBbox(lineIndex = lines.size - 1)
        } else {
            resetButtonBbox = null
        }

        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "§aSeasonings",
            lines = lines,
            color = 0xFFFFFFFF.toInt(),
            enabled = showHud,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.4,
            defaultScale = 1.0f,
        )
    }

    /**
     * Per-hour rate based on [SeasoningState.sessionChatGain] divided by active farming time.
     * Returns null until we have ≥5 s of active farming AND ≥1 chat-counted seasoning (avoids
     * "0 per hour" flicker).
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

    private fun updateResetButtonBbox(lineIndex: Int) {
        val mc = Minecraft.getInstance()
        val window = mc.window
        val element = GuiLayoutManager.getLayout().elements
            .filterIsInstance<TextBlockElement>()
            .firstOrNull { it.id == ELEMENT_ID } ?: return
        if (!element.enabled) return

        val baseX = (element.anchorX * window.guiScaledWidth).toInt() + element.offsetX
        val baseY = (element.anchorY * window.guiScaledHeight).toInt() + element.offsetY
        val scale = element.scale.coerceAtLeast(0.25f)
        val lineStep = (10f * scale).toInt().coerceAtLeast(4)

        val titleOffset = if (element.title != null) lineStep else 0
        val y = baseY + titleOffset + lineIndex * lineStep

        val width = (mc.font.width(RESET_BUTTON_TEXT) * scale).toInt()
        val height = (mc.font.lineHeight * scale).toInt()
        resetButtonBbox = intArrayOf(baseX, y, width, height)
    }

    private fun tryHandleResetClick(mouseX: Int, mouseY: Int) {
        val bbox = resetButtonBbox ?: return
        val (x, y, w, h) = listOf(bbox[0], bbox[1], bbox[2], bbox[3])
        if (mouseX in x..(x + w) && mouseY in y..(y + h)) {
            SeasoningState.resetSession()
        }
    }
}
