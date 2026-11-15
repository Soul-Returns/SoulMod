package com.soulreturns.ui.hud.alert

import com.soulreturns.sound.SoulSounds
import com.soulreturns.sound.SoundRef
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * The one on-screen alert every feature uses ([AlertHud] draws it). A feature keeps its own toggles
 * and sound setting and only hands the visual part here:
 * - **Timed**: [show] (fades out over the last [FADE_MS]); showing an id again replaces its text and
 *   restarts the clock without replaying the sound.
 * - **Persistent**: [showWhile], registered once; up while its condition holds, the sound plays on
 *   every rising edge; its text may be live (read every tick while it is up).
 *
 * Several active: the highest [Alert.priority] shows (newest first among equals) with a small `+N`.
 * Client thread only (conditions and sounds read the world).
 */
object Alerts {
    const val RED = 0xFFFF5555.toInt()
    const val DEFAULT_MS = 4_000L
    const val FADE_MS = 500L

    class Alert(
        val id: String,
        val title: String,
        val subtitle: String?,
        val color: Int,
        val priority: Int,
        val startedAt: Long,
        /** Wall-clock end of a timed alert; [Long.MAX_VALUE] for a persistent one. */
        val until: Long,
        /** Drawn clear of the boss timers' block while those show (unless the player moved the alert). */
        val avoidBossTimers: Boolean = false,
    )

    private class Persistent(
        val id: String,
        val title: () -> String,
        val subtitle: () -> String?,
        val color: Int,
        val priority: Int,
        val sound: () -> SoundRef?,
        val enabled: () -> Boolean,
        val condition: () -> Boolean,
        val avoidBossTimers: Boolean,
    )

    private val logger = SoulLogger("Soul/Alerts")
    private val timed = LinkedHashMap<String, Alert>()
    private val persistent = ArrayList<Persistent>()
    private val shown = LinkedHashMap<String, Alert>()
    private val sources = ArrayList<() -> Boolean>()
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    /**
     * Declares a feature that posts timed alerts, so the Move GUI lists the alert while [enabled]
     * (its own toggles) holds. Persistent alerts declare themselves through [showWhile].
     */
    fun declare(enabled: () -> Boolean) {
        sources.add(enabled)
    }

    /** Timed alert. The caller has already checked its feature toggle. */
    fun show(
        id: String,
        title: String,
        subtitle: String? = null,
        color: Int = RED,
        sound: SoundRef? = null,
        durationMs: Long = DEFAULT_MS,
        priority: Int = 0,
    ) {
        val now = System.currentTimeMillis()
        val previous = timed[id]
        val running = previous != null && previous.until > now
        timed[id] = Alert(id, title, subtitle, color, priority, if (running) previous!!.startedAt else now, now + durationMs)
        if (!running) sound?.let { SoulSounds.play(it) }
    }

    /**
     * Persistent alert, registered once at startup: shown while [enabled] (the feature's toggles) and
     * [condition] hold, [sound] read live and played when it comes up.
     */
    fun showWhile(
        id: String,
        title: String,
        subtitle: String? = null,
        color: Int = RED,
        priority: Int = 0,
        sound: () -> SoundRef? = { null },
        enabled: () -> Boolean,
        condition: () -> Boolean,
    ) {
        showWhileLive(id, { title }, { subtitle }, color, priority, sound, enabled, condition = condition)
    }

    /**
     * [showWhile] with text read every client tick while the alert is up (a counter, a countdown).
     * [avoidBossTimers]: while the boss timers display is drawing, the alert (at its default spot) moves clear of it.
     */
    fun showWhileLive(
        id: String,
        title: () -> String,
        subtitle: () -> String? = { null },
        color: Int = RED,
        priority: Int = 0,
        sound: () -> SoundRef? = { null },
        enabled: () -> Boolean,
        avoidBossTimers: Boolean = false,
        condition: () -> Boolean,
    ) {
        persistent.removeAll { it.id == id }
        persistent.add(Persistent(id, title, subtitle, color, priority, sound, enabled, condition, avoidBossTimers))
    }

    fun dismiss(id: String) {
        timed.remove(id)
    }

    /** Whether any feature that uses the alert is switched on (the Move GUI lists it then). */
    fun anyConfiguredOn(): Boolean = sources.any { it() } || persistent.any { it.enabled() }

    /** Active alerts, the one to draw first. */
    fun active(): List<Alert> {
        val now = System.currentTimeMillis()
        val list = ArrayList<Alert>(shown.size + timed.size)
        list.addAll(shown.values)
        timed.values.filterTo(list) { it.until > now }
        list.sortWith(compareByDescending<Alert> { it.priority }.thenByDescending { it.startedAt })
        return list
    }

    private fun tick() {
        val now = System.currentTimeMillis()
        timed.values.removeAll { it.until <= now }
        for (p in persistent) {
            val on = try {
                p.enabled() && p.condition()
            } catch (t: Throwable) {
                logger.error("Alert '${p.id}' condition threw", t)
                false
            }
            if (!on) {
                shown.remove(p.id)
                continue
            }
            val was = shown[p.id]
            val title = p.title()
            val subtitle = p.subtitle()
            if (was == null) {
                shown[p.id] = Alert(p.id, title, subtitle, p.color, p.priority, now, Long.MAX_VALUE, p.avoidBossTimers)
                p.sound()?.let { SoulSounds.play(it) }
            } else if (was.title != title || was.subtitle != subtitle) {
                shown[p.id] = Alert(p.id, title, subtitle, p.color, p.priority, was.startedAt, Long.MAX_VALUE, p.avoidBossTimers)
            }
        }
    }
}
