package com.soulreturns.data.skyblock

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.model.FishingFestivalEvent
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Whether a Hypixel SkyBlock fishing festival is running right now.
 *
 * It lives in `data/skyblock/` beside the other state readers because it is world state, not a
 * fishing feature: it answers a question about the server, and anything could care.
 *
 * ### The underway message fires three ways
 * Hypixel sends `FISHING FESTIVAL The festival is now underway!` on the real start, **again** on
 * every server join while one is already active, and sometimes a third time mid-festival through a
 * long-standing bug. Only the first occurrence per festival counts as a start; the rest are no-ops.
 * The concluded message, by contrast, is always authoritative.
 *
 * ### The one-hour cap is a safety net, not the clock
 * If the concluded message is swallowed — a chat filter, a disconnect at the wrong moment — nothing
 * would ever end the festival. So [firstSeenStartAt] plus one hour, Hypixel's advertised duration,
 * ends it. It is a backstop: the concluded message is what normally ends a festival, and it wins.
 *
 * ### The start timestamp outlives the client
 * A relaunch mid-festival must resume the same bucket rather than start a second one, so the
 * timestamp is persisted. **How** it is persisted is not this class's business: fishing owns the
 * stats file, and a `data/` reader reaching into `features/` for it would be exactly backwards. So
 * the feature installs [loadPersistedStart] / [savePersistedStart] at startup, and the defaults make
 * this class work standalone (no persistence, no resume) if it ever runs without them.
 */
object FishingFestivalState {
    private val logger = SoulLogger("Soul/Fishing")

    private const val FESTIVAL_UNDERWAY = "FISHING FESTIVAL The festival is now underway"
    private const val FESTIVAL_CONCLUDED = "FISHING FESTIVAL The festival has concluded"

    /** Hypixel's advertised festival duration, and therefore the missed-conclusion backstop. */
    const val MAX_DURATION_MS: Long = 60L * 60L * 1000L

    /** Where the first-seen start timestamp is read from at startup. 0 = no festival was running. */
    @Volatile var loadPersistedStart: () -> Long = { 0L }

    /** Where it is written on every transition. Called with 0 when the festival ends. */
    @Volatile var savePersistedStart: (Long) -> Unit = {}

    @Volatile
    var active: Boolean = false
        private set

    /** Epoch ms of the first underway message seen for the running festival; 0 when none is. */
    @Volatile
    var firstSeenStartAt: Long = 0L
        private set

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        resume()
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick() })
    }

    /** Milliseconds left before the one-hour cap; 0 when no festival is active. */
    fun remainingMs(): Long {
        if (!active) return 0L
        return (MAX_DURATION_MS - (System.currentTimeMillis() - firstSeenStartAt)).coerceAtLeast(0L)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        if (stripped.contains(FESTIVAL_UNDERWAY)) {
            onUnderway()
        } else if (stripped.contains(FESTIVAL_CONCLUDED)) {
            onConcluded()
        }
    }

    /** Re-derive [active] from the persisted timestamp, if it is still inside the one-hour window. */
    private fun resume() {
        val persisted = loadPersistedStart()
        if (persisted <= 0L) return
        val elapsed = System.currentTimeMillis() - persisted
        if (elapsed < MAX_DURATION_MS) {
            active = true
            firstSeenStartAt = persisted
            logger.info("Resumed a fishing festival in progress (started $persisted, ${elapsed / 1000}s ago)")
        } else {
            logger.info("Cleared an expired fishing festival start ($persisted)")
            savePersistedStart(0L)
        }
    }

    private fun onUnderway() {
        // Already running: this is a server-join echo or the duplicate-message bug, not a new
        // festival. Restarting here would wipe the festival counters mid-festival.
        if (active) return
        val now = System.currentTimeMillis()
        active = true
        firstSeenStartAt = now
        savePersistedStart(now)
        logger.info("Fishing festival started (first seen at $now)")
        Events.publish(FishingFestivalEvent.Started(now))
    }

    private fun onConcluded() {
        if (!active) return
        end(System.currentTimeMillis(), FishingFestivalEvent.EndReason.CONCLUDED_MESSAGE)
    }

    private fun tick() {
        if (!active) return
        val now = System.currentTimeMillis()
        if (now - firstSeenStartAt >= MAX_DURATION_MS) end(now, FishingFestivalEvent.EndReason.ONE_HOUR_CAP)
    }

    private fun end(
        now: Long,
        reason: FishingFestivalEvent.EndReason,
    ) {
        val started = firstSeenStartAt
        active = false
        firstSeenStartAt = 0L
        savePersistedStart(0L)
        logger.info("Fishing festival ended (reason=${reason.name}, started=$started, ended=$now)")
        Events.publish(FishingFestivalEvent.Ended(started, now, reason))
    }
}
