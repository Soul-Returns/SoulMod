package com.soulreturns.ui

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.minecraft.client.Minecraft
import org.slf4j.LoggerFactory
import kotlin.math.roundToInt

/**
 * Picks [SoulUi.scale] from the monitor the game window is on, once.
 *
 * `general.uiScale` is null in a config that never chose a size. The first client start (window
 * exists by then, the config loads before it) writes [pick] into it, so from then on it is an
 * ordinary fixed number: resizing, fullscreen or another monitor never change it. Configs that
 * already hold a value are untouched; the "Auto" button in Settings > Interface re-runs the pick.
 */
object AutoUiScale {
    /** What [SoulUi.scale] returns while nothing is chosen yet, and when no monitor can be read. */
    const val FALLBACK = 2f

    /** Monitor height in pixels that maps to scale 1. */
    private const val REFERENCE_HEIGHT = 720f

    private const val STEP = 0.25f

    // Plain slf4j: SoulLogger.info stays silent without debug mode, and this line is the only record of a pick.
    private val logger by lazy { LoggerFactory.getLogger("Soul/UiScale") }

    /** [monitorHeightPx] / 720 to the nearest 0.25, within [SoulUi.MIN_SCALE]..[SoulUi.MAX_SCALE]. */
    fun forMonitorHeight(monitorHeightPx: Int): Float =
        ((monitorHeightPx / REFERENCE_HEIGHT / STEP).roundToInt() * STEP).coerceIn(SoulUi.MIN_SCALE, SoulUi.MAX_SCALE)

    /** First start only; later starts find a value and keep it. */
    fun register() {
        ClientLifecycleEvents.CLIENT_STARTED.register { pickIfUnchosen() }
    }

    fun pickIfUnchosen() {
        if (cfg.general.uiScale == null) applyPick()
    }

    /** Pick for the current monitor and store it as the player's value. */
    fun applyPick() {
        val scale = forCurrentMonitor(log = true)
        ConfigManager.update { copy(general = general.copy(uiScale = scale)) }
    }

    /** Pick for the monitor the window is on now, [FALLBACK] when it cannot be read. Stores nothing. */
    fun forCurrentMonitor(log: Boolean = false): Float {
        val monitor = Minecraft.getInstance()?.window?.findBestMonitor()
        val mode = monitor?.currentMode()
        if (monitor == null || mode == null || mode.height <= 0) {
            if (log) logger.warn("[Soul/UiScale] No monitor height available; UI scale set to $FALLBACK")
            return FALLBACK
        }
        val scale = forMonitorHeight(mode.height)
        if (log) logger.info("[Soul/UiScale] UI scale $scale picked from monitor '${monitor.monitorName()}' (${mode.width}x${mode.height})")
        return scale
    }
}
