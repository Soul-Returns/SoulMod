package com.soulreturns.features.farming

import com.soulreturns.data.location.LocationApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks

/**
 * Stopwatch that runs while the player is actively breaking SkyBlock-Garden crops.
 *
 * Active state begins when a harvestable crop block is broken on the Garden island.
 * Active state ends 2s after the most recent crop break (idle window) — this grace
 * is included in the accumulated time, so a continuous farm pulses through brief
 * cactus-knife instabreak gaps without losing the stopwatch.
 *
 * Session-only: resets to 0 on every client launch. Not persisted.
 */
object FarmingTimer {
    private const val IDLE_TIMEOUT_MS = 2_000L

    /** Block list per-spec — every harvestable Garden crop block. Stage doesn't matter; the block class is the same. */
    private val HARVESTABLE_CROPS: Set<Block> =
        setOf(
            Blocks.WHEAT, Blocks.POTATOES, Blocks.CARROTS,
            Blocks.CACTUS, Blocks.MELON, Blocks.PUMPKIN,
            Blocks.SUGAR_CANE,
            Blocks.RED_MUSHROOM, Blocks.BROWN_MUSHROOM,
            Blocks.ROSE_BUSH, Blocks.SUNFLOWER,
            Blocks.NETHER_WART, Blocks.COCOA
        )

    private enum class State { PAUSED, ACTIVE }

    @Volatile private var state: State = State.PAUSED

    @Volatile private var lastBreakTime = 0L

    @Volatile private var activeStartTime = 0L

    @Volatile private var accumulatedMs = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ -> tick() })
    }

    /** Invoked from MultiPlayerGameModeMixin on each client-side block destroy. */
    fun onBlockBreak(block: Block) {
        if (block !in HARVESTABLE_CROPS) return
        if (!LocationApi.isInArea("Garden")) return
        val now = System.currentTimeMillis()
        if (state == State.PAUSED) {
            state = State.ACTIVE
            activeStartTime = now
        }
        lastBreakTime = now
    }

    private fun tick() {
        if (state == State.ACTIVE) {
            val now = System.currentTimeMillis()
            if (now - lastBreakTime > IDLE_TIMEOUT_MS) {
                // Commit the active period including the 2s grace window — the timer "stops 2s
                // after the last break", so that grace is part of farming time.
                accumulatedMs += (lastBreakTime + IDLE_TIMEOUT_MS) - activeStartTime
                state = State.PAUSED
            }
        }
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
}
