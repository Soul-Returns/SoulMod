package com.soulreturns.features.notifications

import com.soulreturns.core.events.Events
import com.soulreturns.platform.realtime.BackendNotification
import com.soulreturns.util.SoulLogger
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Backing queue for [BackendNotification][com.soulreturns.platform.realtime.BackendNotification]
 * events: receives them off the realtime thread, tracks each one's expiry, and exposes a
 * thread-safe snapshot for the renderer.
 *
 * Pure state holder — the rendering side lives in
 * [BackendNotificationHud][com.soulreturns.ui.hud.BackendNotificationHud].
 */
object BackendNotificationCenter {
    private val logger = SoulLogger("Soul/Notify")

    /** How long each notification stays on screen. */
    private const val DISPLAY_MS = 6_000L

    /** Hard cap on simultaneous notifications. Older entries fall off the top. */
    private const val MAX_VISIBLE = 4

    data class Entry(
        val message: String,
        val severity: String,
        val expiresAt: Long,
    )

    private val active = CopyOnWriteArrayList<Entry>()

    fun register() {
        Events.subscribe<BackendNotification> { event -> onNotification(event) }
    }

    private fun onNotification(event: BackendNotification) {
        val entry =
            Entry(
                message = event.message,
                severity = event.severity,
                expiresAt = System.currentTimeMillis() + DISPLAY_MS,
            )
        active.add(entry)
        while (active.size > MAX_VISIBLE) active.removeAt(0)
        logger.info("Backend notification: [${event.severity}] ${event.message}")
    }

    /** Snapshot of currently-visible entries. Caller may treat it as immutable. */
    fun visible(): List<Entry> {
        val now = System.currentTimeMillis()
        // Lazy expiry: prune as the renderer asks for the list.
        active.removeIf { it.expiresAt <= now }
        return active.toList()
    }
}
