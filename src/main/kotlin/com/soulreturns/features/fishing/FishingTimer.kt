package com.soulreturns.features.fishing

import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.projectile.FishingHook

/**
 * Session-only stopwatch that runs while the player is actively fishing.
 *
 * **Active state begins** when the local player's own [FishingHook] is in water or lava —
 * `isInWater() || isInLava()` is true on the bobber the player just cast. Hypixel's bobbing
 * state for the catch logic kicks in the same instant the hook splashes, so the water-touch
 * proxy lines up with "bobber hit water/lava". Other players' bobbers are ignored.
 *
 * **Active state ends** on any of:
 *   1. The player moved >50 blocks (xyz) from the fishing anchor — instant pause. The anchor
 *      is captured at activation; the radius keeps the timer running while the player darts
 *      around fighting a tougher sea creature in the same spot, and cuts it off the moment
 *      they wander off.
 *   2. 5 s have passed with no active own bobber AND the player hasn't moved (xyz). Covers
 *      "user stopped casting and stepped away from the keyboard".
 *   3. 60 s have passed since the bobber last existed, regardless of in-area movement.
 *      Safety cap for marathon sea creature fights where the bobber is long gone but the
 *      player is still in combat.
 *
 * Movement is xyz only — camera yaw / pitch changes do not count as movement. Session-only:
 * resets to 0 on every client launch and on [resetSession] (called from the HUD Reset
 * button via `FishingTracker.resetSession`).
 */
object FishingTimer {
    private const val IDLE_NO_BOBBER_MS = 5_000L
    private const val POST_BOBBER_CAP_MS = 60_000L
    private const val RADIUS_BLOCKS = 50.0
    private const val RADIUS_SQ = RADIUS_BLOCKS * RADIUS_BLOCKS

    // Position-change threshold to count as "moved". Smaller than one block so any deliberate
    // walking step refreshes the no-move timer, but the per-tick rotation/sneak/swim subpixel
    // jitter doesn't.
    private const val MOVE_EPSILON = 0.01

    private enum class State { PAUSED, ACTIVE }

    @Volatile private var state: State = State.PAUSED

    @Volatile private var accumulatedMs = 0L

    @Volatile private var activeStartTime = 0L

    // Anchor captured at the transition into ACTIVE. The 50-block radius is checked against this.
    @Volatile private var anchorX = 0.0

    @Volatile private var anchorY = 0.0

    @Volatile private var anchorZ = 0.0

    // Last observed xyz; refreshed only when the player actually moves (xyz delta > epsilon).
    @Volatile private var lastX = 0.0

    @Volatile private var lastY = 0.0

    @Volatile private var lastZ = 0.0

    @Volatile private var lastMoveTime = 0L

    // Last tick we saw the player's own bobber in water/lava.
    @Volatile private var lastBobberTime = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
    }

    fun resetSession() {
        state = State.PAUSED
        accumulatedMs = 0L
    }

    val isPaused: Boolean get() = state == State.PAUSED

    val totalMs: Long get() =
        when (state) {
            State.PAUSED -> accumulatedMs
            State.ACTIVE -> accumulatedMs + (System.currentTimeMillis() - activeStartTime)
        }

    fun formatTime(): String {
        val totalSec = totalMs / 1000
        val h = totalSec / 3600
        val m = (totalSec % 3600) / 60
        val s = totalSec % 60
        return "%02d:%02d:%02d".format(h, m, s)
    }

    private fun tick(client: Minecraft) {
        val player = client.player ?: return
        val world = client.level ?: return
        val now = System.currentTimeMillis()

        // Refresh lastMove against the previous tick's xyz. Camera yaw/pitch are ignored
        // because we only compare position deltas.
        val dx = player.x - lastX
        val dy = player.y - lastY
        val dz = player.z - lastZ
        if (dx * dx + dy * dy + dz * dz > MOVE_EPSILON * MOVE_EPSILON) {
            lastX = player.x
            lastY = player.y
            lastZ = player.z
            lastMoveTime = now
        }

        // Find the local player's own bobber, if any, that's in water or lava.
        val ownBobberInLiquid =
            world.entitiesForRendering()
                .filterIsInstance<FishingHook>()
                .any { it.playerOwner === player && (it.isInWater || it.isInLava) }

        if (ownBobberInLiquid) {
            lastBobberTime = now
            if (state == State.PAUSED) {
                state = State.ACTIVE
                activeStartTime = now
                anchorX = player.x
                anchorY = player.y
                anchorZ = player.z
                lastX = player.x
                lastY = player.y
                lastZ = player.z
                lastMoveTime = now
            }
            return
        }

        if (state != State.ACTIVE) return

        // Active but no own bobber in liquid — evaluate the three stop conditions.
        val ax = player.x - anchorX
        val ay = player.y - anchorY
        val az = player.z - anchorZ
        val outOfRadius = (ax * ax + ay * ay + az * az) > RADIUS_SQ
        val timedOutByCap = now - lastBobberTime > POST_BOBBER_CAP_MS
        val idleStill = (now - lastMoveTime > IDLE_NO_BOBBER_MS) && (now - lastBobberTime > IDLE_NO_BOBBER_MS)
        if (outOfRadius || timedOutByCap || idleStill) {
            accumulatedMs += now - activeStartTime
            state = State.PAUSED
        }
    }
}
