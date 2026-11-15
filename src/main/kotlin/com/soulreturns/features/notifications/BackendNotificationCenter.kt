package com.soulreturns.features.notifications

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.platform.realtime.BackendNotification
import com.soulreturns.sound.SoulSounds
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Receives [BackendNotification]s off the realtime thread and shows them.
 *
 * Two surfaces, deliberately both: an on-screen toast ([NotificationHud]) that the player may have
 * been looking away from, and a chat line that is still there when they look back. The legacy mod
 * rendered these as a full-screen alert through `RenderUtils.showAlert` + a `GuiMixin`; neither
 * exists in 26.2 and neither is needed — the movable HUD host does the job with no mixin.
 *
 * The queue is the first (and cheapest) consumer of the shared realtime client, which makes it the
 * thing that proves that path end to end: `POST /admin/players/<uuid>/notify` on the backend should
 * appear here within a second.
 */
object BackendNotificationCenter {
    private val logger = SoulLogger("Soul/Notify")

    /** At most this many toasts stack; the oldest is dropped rather than queued behind. */
    private const val MAX_TOASTS = 3

    /** One notification waiting on screen. [color] is resolved from the severity at arrival. */
    class Toast(val message: String, val severity: String, val color: Int, val expiresAt: Long)

    // Written from the realtime thread, read from the render thread.
    private val toasts = CopyOnWriteArrayList<Toast>()

    fun register() {
        Events.subscribe<BackendNotification> { onNotification(it) }
    }

    private fun onNotification(event: BackendNotification) {
        logger.info("Backend notification [${event.severity}]: ${event.message}")
        // SoulSounds hops to the client thread itself; this runs on the realtime thread.
        cfg.notifications.sound?.let { SoulSounds.play(it) }

        if (cfg.notifications.chatEnabled) {
            soulChat(chatColor(event.severity) + event.message)
        }
        if (!cfg.notifications.toastEnabled) return

        val seconds = cfg.notifications.toastSeconds.coerceIn(1f, 60f)
        toasts.add(
            Toast(
                message = event.message,
                severity = event.severity,
                color = severityColor(event.severity),
                expiresAt = System.currentTimeMillis() + (seconds * 1000f).toLong(),
            ),
        )
        while (toasts.size > MAX_TOASTS) toasts.removeAt(0)
    }

    /** The toasts still on screen, oldest first. Expired entries are dropped on the way past. */
    fun active(): List<Toast> {
        if (toasts.isEmpty()) return emptyList()
        val now = System.currentTimeMillis()
        val expired = toasts.filter { it.expiresAt <= now }
        if (expired.isNotEmpty()) toasts.removeAll(expired)
        return toasts.toList()
    }

    /** Push a toast locally, without the backend — used by `/soul dev notify <text>`. */
    fun test(message: String, severity: String) {
        Events.publish(BackendNotification(message, severity))
    }

    /** Severity → toast color. Ported verbatim from the legacy notification center. */
    fun severityColor(severity: String): Int =
        when (severity.lowercase()) {
            "error" -> 0xFFFF5555.toInt()
            "warning", "warn" -> 0xFFFFD744.toInt()
            else -> 0xFFFFFFFF.toInt()
        }

    /** The same three severities as legacy §-codes, for the chat fallback. */
    private fun chatColor(severity: String): String =
        when (severity.lowercase()) {
            "error" -> "§c"
            "warning", "warn" -> "§e"
            else -> "§f"
        }
}
