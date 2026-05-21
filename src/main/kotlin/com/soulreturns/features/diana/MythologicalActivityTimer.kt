package com.soulreturns.features.diana

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.MythologicalMobCatalog
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Session-only stopwatch that runs while the player is actively engaged with Mayor Diana's
 * Mythological Ritual — measured by chat "You dug out a ..." lines plus player movement.
 *
 * **Active state begins** when ANY dig-out chat line is seen (mob, burrow, or rare drop).
 * The activity timer doesn't distinguish flavours — a long burrow chain between mob spawns
 * still counts as actively engaged, so derived "mobs/hour" / "burrows/hour" rates use the
 * same denominator.
 *
 * **Active state ends** after [PAUSE_AFTER_NO_MOVEMENT_MS] (20s) of no player xyz
 * movement. Movement is xyz only — camera yaw / pitch jitter doesn't count as movement so
 * idle players don't keep the timer alive just by panning the camera.
 *
 * **Resume** is gated to dig-out chat lines only: once paused, the timer stays paused
 * until the next `"You dug out a ..."` arrives. Movement during PAUSED is ignored — the
 * user can wander around the Hub without inflating the active-time clock until they
 * actually start digging again.
 *
 * Session-only — resets to 0 on every client launch and via [resetSession].
 */
object MythologicalActivityTimer {
    /** No-movement window before the timer pauses. The Mythological mob spawn intervals are
     * such that a player actively digging is moving constantly; 20 s is long enough to absorb
     * a brief pause to read chat / sort inventory but short enough that AFK time doesn't
     * inflate the mobs/hour rate. */
    private const val PAUSE_AFTER_NO_MOVEMENT_MS = 20_000L

    /** xyz delta below this is treated as not-moving — covers per-tick subpixel jitter from
     * the sneak / swim / climbing animations without counting as real movement. */
    private const val MOVE_EPSILON = 0.01

    private enum class State { PAUSED, ACTIVE }

    @Volatile private var state: State = State.PAUSED

    @Volatile private var accumulatedMs = 0L

    @Volatile private var activeStartTime = 0L

    @Volatile private var lastX = 0.0

    @Volatile private var lastY = 0.0

    @Volatile private var lastZ = 0.0

    @Volatile private var lastMoveTime = 0L

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
    }

    fun resetSession() {
        state = State.PAUSED
        accumulatedMs = 0L
    }

    val isPaused: Boolean get() = state == State.PAUSED

    val totalMs: Long get() =
        if (state == State.ACTIVE) {
            accumulatedMs + (System.currentTimeMillis() - activeStartTime)
        } else {
            accumulatedMs
        }

    /**
     * Returns the active-time growth since the last [consumeDelta] call. Used by
     * [MythologicalMobTracker] to feed the persisted Event / Total active-time clocks each
     * tick — the timer itself only tracks the session clock; the tracker amortises the
     * delta into its persistent buckets so totals survive a client restart.
     */
    fun consumeDelta(): Long {
        val current = totalMs
        val delta = (current - lastConsumedMs).coerceAtLeast(0L)
        lastConsumedMs = current
        return delta
    }

    /**
     * Resets the consume-delta cursor without altering the session clock. Called when the
     * persistent clocks reload from disk so the next [consumeDelta] only credits time
     * elapsed AFTER reload — avoids double-counting if the JVM has been running long enough
     * for the session clock to grow between init and the tracker's first tick.
     */
    fun resetConsumeCursor() {
        lastConsumedMs = totalMs
    }

    @Volatile private var lastConsumedMs = 0L

    fun formatTime(): String = formatMs(totalMs)

    /**
     * Format an arbitrary active-time millisecond value as `hh:mm:ss`. The HUD calls this
     * for the Event and Total tabs (the tracker's persisted clocks) — the session-only
     * [totalMs] path goes through [formatTime].
     */
    fun formatMs(ms: Long): String {
        val totalSec = ms / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    /** Per-hour rate of [count] derived from an arbitrary active-time clock [ms]. Returns 0
     * until at least 60 s of active time have accumulated — short clocks produce wildly
     * misleading rates ("12,000 mobs/hour" after the first kill is noise). */
    fun perHour(count: Long, ms: Long = totalMs): Long {
        if (ms < 60_000L) return 0L
        return (count.toDouble() / ms * 3_600_000.0).toLong()
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        if (!MythologicalMobCatalog.isDigOutLine(stripped)) return
        val now = System.currentTimeMillis()
        lastMoveTime = now
        if (state == State.PAUSED) {
            state = State.ACTIVE
            activeStartTime = now
        }
    }

    private fun tick(client: Minecraft) {
        val player = client.player ?: return
        val now = System.currentTimeMillis()
        val dx = player.x - lastX
        val dy = player.y - lastY
        val dz = player.z - lastZ
        val moved = dx * dx + dy * dy + dz * dz > MOVE_EPSILON * MOVE_EPSILON
        if (moved) {
            lastX = player.x
            lastY = player.y
            lastZ = player.z
            // Movement only matters while ACTIVE — it refreshes the no-move clock so the
            // 20s pause threshold resets. Movement during PAUSED is intentionally ignored:
            // a player wandering the Hub between digs shouldn't keep the active-time clock
            // accumulating. Resume happens only on the next "You dug out a ..." chat line
            // (see [onChat]).
            if (state == State.ACTIVE) lastMoveTime = now
            return
        }
        if (state != State.ACTIVE) return
        if (now - lastMoveTime > PAUSE_AFTER_NO_MOVEMENT_MS) {
            accumulatedMs += now - activeStartTime
            state = State.PAUSED
        }
    }
}
