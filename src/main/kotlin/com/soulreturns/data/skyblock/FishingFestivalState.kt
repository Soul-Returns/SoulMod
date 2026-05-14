package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.model.FishingFestivalEvent
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Tracks whether a Hypixel-SkyBlock fishing festival is currently active.
 *
 * The festival lifecycle has two chat-line signals:
 *  - **Underway message** (`FISHING FESTIVAL The festival is now underway! …`) — sent by
 *    Hypixel on real festival start, **also** on server join while one is already active, and
 *    sometimes duplicated mid-festival by a known Hypixel bug. The first occurrence per
 *    festival counts as start; subsequent occurrences are no-ops.
 *  - **Concluded message** (`FISHING FESTIVAL The festival has concluded! …`) — fires once at
 *    real festival end. Always authoritative.
 *
 * A safety-net 1-hour cap from `firstSeenStartAt` ends the festival if the concluded message
 * is missed (network blip / chat filter swallow). The cap matches Hypixel's advertised
 * festival duration.
 *
 * `firstSeenStartAt` is persisted to `stats.json` so a mid-festival client restart resumes
 * the same bucket. On load we re-derive `active` from the persisted timestamp (within the
 * 1-hour window).
 */
object FishingFestivalState {
    private val logger = SoulLogger("Soul/Fishing")

    private const val FESTIVAL_UNDERWAY = "FISHING FESTIVAL The festival is now underway"
    private const val FESTIVAL_CONCLUDED = "FISHING FESTIVAL The festival has concluded"
    private const val FESTIVAL_MAX_DURATION_MS = 60L * 60L * 1000L

    @Volatile
    var active: Boolean = false
        private set

    @Volatile
    var firstSeenStartAt: Long = 0L
        private set

    fun register() {
        // Restore from persisted state if a festival was in progress when the client last closed.
        val persistedStart = PersistentStats.current.festivalStartAt
        if (persistedStart > 0L) {
            val elapsed = System.currentTimeMillis() - persistedStart
            if (elapsed < FESTIVAL_MAX_DURATION_MS) {
                active = true
                firstSeenStartAt = persistedStart
                logger.info("Resumed in-progress fishing festival (started at $persistedStart, ${elapsed / 1000}s elapsed)")
            } else {
                logger.info("Cleared expired fishing festival start ($persistedStart) on load")
                PersistentStats.update { festivalStartAt = 0L }
            }
        }
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ -> tick() })
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        when {
            stripped.contains(FESTIVAL_UNDERWAY) -> onUnderwayMessage()
            stripped.contains(FESTIVAL_CONCLUDED) -> onConcludedMessage()
        }
    }

    /** Milliseconds remaining in the current festival (capped at 1h); 0 if not active. */
    fun remainingMs(): Long {
        if (!active) return 0L
        val elapsed = System.currentTimeMillis() - firstSeenStartAt
        return (FESTIVAL_MAX_DURATION_MS - elapsed).coerceAtLeast(0L)
    }

    private fun onUnderwayMessage() {
        if (active) return
        val now = System.currentTimeMillis()
        active = true
        firstSeenStartAt = now
        PersistentStats.update { festivalStartAt = now }
        logger.info("Fishing festival started (first-seen at $now)")
        Events.publish(FishingFestivalEvent.Started(now))
    }

    private fun onConcludedMessage() {
        if (!active) return
        endFestival(System.currentTimeMillis(), FishingFestivalEvent.EndReason.CONCLUDED_MESSAGE)
    }

    private fun tick() {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - firstSeenStartAt >= FESTIVAL_MAX_DURATION_MS) {
            endFestival(now, FishingFestivalEvent.EndReason.ONE_HOUR_CAP)
        }
    }

    private fun endFestival(
        now: Long,
        reason: FishingFestivalEvent.EndReason
    ) {
        val started = firstSeenStartAt
        active = false
        firstSeenStartAt = 0L
        PersistentStats.update { festivalStartAt = 0L }
        logger.info("Fishing festival ended (reason=$reason, started=$started, ended=$now)")
        Events.publish(FishingFestivalEvent.Ended(started, now, reason))
    }
}
