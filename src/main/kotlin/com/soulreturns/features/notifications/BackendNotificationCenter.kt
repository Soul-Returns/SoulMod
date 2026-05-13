package com.soulreturns.features.notifications

import com.soulreturns.core.events.Events
import com.soulreturns.platform.realtime.BackendNotification
import com.soulreturns.util.RenderUtils
import com.soulreturns.util.SoulLogger

/**
 * Receives [BackendNotification][com.soulreturns.platform.realtime.BackendNotification]
 * events off the realtime thread and forwards them to [RenderUtils.showAlert] — the same
 * full-screen, scaled, sound-equipped alert system used by `/soul dev testAlert`. The alert
 * system handles rendering (via `GuiMixin`), queueing, expiry, and the bell chime.
 */
object BackendNotificationCenter {
    private val logger = SoulLogger("Soul/Notify")

    /** How long each notification stays on screen. */
    private const val DISPLAY_MS = 6_000L

    /** Text scale handed to RenderUtils — matches the testAlert default. */
    private const val SCALE = 4.0f

    fun register() {
        Events.subscribe<BackendNotification> { event -> onNotification(event) }
    }

    private fun onNotification(event: BackendNotification) {
        logger.info("Backend notification: [${event.severity}] ${event.message}")
        RenderUtils.showAlert(
            text = event.message,
            color = severityColor(event.severity),
            textScale = SCALE,
            durationMs = DISPLAY_MS,
        )
    }

    private fun severityColor(severity: String): Int =
        when (severity.lowercase()) {
            "error" -> 0xFFFF5555.toInt()
            "warning", "warn" -> 0xFFFFD744.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
}
