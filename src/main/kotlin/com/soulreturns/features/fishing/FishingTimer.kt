package com.soulreturns.features.fishing

import com.soulreturns.stats.TrackerSessions
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.projectile.FishingHook
import java.util.Locale

/**
 * A stopwatch that runs only while the player is actually fishing. Every rate the HUD shows divides
 * by this rather than by wall-clock, so five minutes in the auction house does not make an hour of
 * grinding look worse than it was, and every derived number shares one denominator.
 *
 * Its time belongs to the session ([FishingStats.sessionMs]): persisted per profile, checkpointed at
 * every [TrackerSessions] alive stamp while running, zeroed with the session counters. After a restart
 * it resumes from the stored time, not running until the next splash.
 *
 * ### Active begins at the splash
 * The local player's own [FishingHook] being in water or lava is the proxy: Hypixel's own catch
 * logic starts the instant the bobber lands, so the two line up. Other players' bobbers are ignored
 * — standing in a crowded pond is not fishing.
 *
 * ### Three independent end conditions, all of them earned
 * 1. **Moved more than 50 blocks from the anchor** captured at activation — instant. Wide enough to
 *    chase a sea creature around the same pond, tight enough to stop the moment you wander off.
 * 2. **5 s with no bobber *and* no movement** — the player stopped casting and stepped away.
 * 3. **60 s since the bobber last existed**, whatever the player is doing. The safety cap for a
 *    marathon fight where the bobber is long gone but the fight is still on.
 *
 * Movement is **xyz only**. Camera yaw and pitch deliberately do not count, or an idle player
 * panning the view would keep the clock running for as long as they fidget.
 */
object FishingTimer {
    private const val IDLE_NO_BOBBER_MS = 5_000L
    private const val POST_BOBBER_CAP_MS = 60_000L
    private const val RADIUS_SQ = 50.0 * 50.0

    /**
     * Below one block, so any deliberate step counts as movement, but above the sub-pixel jitter a
     * standing player produces from sneaking, swimming and mount bob.
     */
    private const val MOVE_EPSILON_SQ = 0.01 * 0.01

    @Volatile private var running = false

    /** Start of the running span not yet folded into [FishingStats.sessionMs]. */
    @Volatile private var startedAt = 0L

    // Anchor captured on the transition into running; condition 1 measures from here.
    private var anchorX = 0.0
    private var anchorY = 0.0
    private var anchorZ = 0.0

    // Last position that counted as movement, and when.
    private var lastX = 0.0
    private var lastY = 0.0
    private var lastZ = 0.0
    private var lastMoveAt = 0L

    private var lastBobberAt = 0L

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
        TrackerSessions.onStamp { if (running) fold(System.currentTimeMillis()) }
    }

    /** Stop the clock; [FishingTracker.resetSession] zeroes the stored time with the counters. */
    fun resetSession() {
        running = false
    }

    val isPaused: Boolean get() = !running

    val totalMs: Long get() {
        val stored = FishingStatsStore.read().sessionMs
        return if (running) stored + (System.currentTimeMillis() - startedAt) else stored
    }

    /** `HH:MM:SS`. */
    fun formatTime(): String {
        val seconds = totalMs / 1000
        return String.format(Locale.ROOT, "%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60)
    }

    /**
     * [value] per hour of active fishing, or null while the clock is still too short to divide by.
     * The one-minute floor keeps the first catch of a session from reporting an absurd rate.
     */
    fun perHour(value: Long): Double? {
        // Quantized to whole seconds. The clock advances every frame, so dividing by the raw
        // millisecond count re-renders a different number sixty times a second and the rate reads as
        // noise rather than as a measurement. One step per second is what a person can actually read,
        // and it keeps the value a pure function of the counters — no cached string, no timestamp, so
        // the measure pass and the draw pass cannot disagree about how wide it is.
        val seconds = totalMs / 1000L
        if (seconds < 60L) return null
        return value * 3600.0 / seconds
    }

    private fun tick(client: Minecraft) {
        val player = client.player ?: return
        val level = client.level ?: return
        val now = System.currentTimeMillis()

        val dx = player.x - lastX
        val dy = player.y - lastY
        val dz = player.z - lastZ
        if (dx * dx + dy * dy + dz * dz > MOVE_EPSILON_SQ) {
            lastX = player.x
            lastY = player.y
            lastZ = player.z
            lastMoveAt = now
        }

        val ownBobberInLiquid = level.entitiesForRendering()
            .filterIsInstance<FishingHook>()
            .any { it.playerOwner === player && (it.isInWater || it.isInLava) }

        if (ownBobberInLiquid) {
            lastBobberAt = now
            if (!running) {
                running = true
                startedAt = now
                anchorX = player.x
                anchorY = player.y
                anchorZ = player.z
                lastX = player.x
                lastY = player.y
                lastZ = player.z
                lastMoveAt = now
            }
            return
        }

        if (!running) return

        val ax = player.x - anchorX
        val ay = player.y - anchorY
        val az = player.z - anchorZ
        val leftTheSpot = ax * ax + ay * ay + az * az > RADIUS_SQ
        val bobberLongGone = now - lastBobberAt > POST_BOBBER_CAP_MS
        val idleAndStill = now - lastMoveAt > IDLE_NO_BOBBER_MS && now - lastBobberAt > IDLE_NO_BOBBER_MS

        if (leftTheSpot || bobberLongGone || idleAndStill) {
            fold(now)
            running = false
        }
    }

    /** Move the running span into the stored session time. */
    private fun fold(now: Long) {
        val span = now - startedAt
        startedAt = now
        if (span > 0L) FishingStatsStore.update { copy(sessionMs = sessionMs + span) }
    }
}
