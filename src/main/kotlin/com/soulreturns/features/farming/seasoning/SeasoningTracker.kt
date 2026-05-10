package com.soulreturns.features.farming.seasoning

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.model.HarvestFeastSnapshot
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

private const val SEASONING_PATTERN = "RARE CROP! Seasoning"

// Trailing " (N)" count appended by chat-compacting mods (Compacting, ChatPatches, etc.).
private val COMPACTED_COUNT_SUFFIX = Regex(""" \((\d+)\)$""")

// If the next compacted update arrives later than this, treat it as a new aggregate.
private const val COMPACTED_AGGREGATE_WINDOW_MS = 60_000L

/**
 * Orchestrator for the Seasoning Tracker feature.
 *
 * Layered responsibilities:
 *  - [SeasoningState]      — owns the mutable counter + session metrics.
 *  - [HarvestFeastReader]  — parses the chest GUI and publishes [HarvestFeastSnapshot].
 *  - [com.soulreturns.ui.hud.SeasoningHud] — renders the on-screen overlay.
 *  - this object           — wires events to state mutations. Tiny by design.
 */
object SeasoningTracker {
    private val logger = SoulLogger("Soul/Seasoning")

    // Track the last compacted-base text + count so receive-level compacting
    // (which can suppress intermediate emissions) is decoded as a delta.
    private var lastCompactedBase: String? = null
    private var lastCompactedCount: Int = 0
    private var lastCompactedAt: Long = 0L

    fun register() {
        SeasoningState.init()
        Events.subscribe(this)
    }

    /** Wipe persisted total + session metrics — invoked by `/soul dev resetSeasonings`. */
    fun reset() {
        lastCompactedBase = null
        lastCompactedCount = 0
        lastCompactedAt = 0L
        SeasoningState.reset()
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        // Hypixel sends e.g. "§6§lRARE CROP! §r§eSeasoning (automatically donated)" — strip codes first.
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        if (!stripped.contains(SEASONING_PATTERN, ignoreCase = true)) return

        // Chat-compacting mods append " (N)" when the same line repeats. If they're HUD-level the
        // listener still sees every individual drop and the suffix is absent — handled by the +1
        // fallback. If they're receive-level and suppress intermediate messages, the suffix is the
        // only signal of how many drops occurred between firings.
        val match = COMPACTED_COUNT_SUFFIX.find(stripped)
        val count = match?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val base = if (match != null) stripped.substring(0, match.range.first) else stripped

        val now = System.currentTimeMillis()
        val isAggregateContinuation = base == lastCompactedBase &&
            count > lastCompactedCount &&
            now - lastCompactedAt < COMPACTED_AGGREGATE_WINDOW_MS

        val delta = if (isAggregateContinuation) count - lastCompactedCount else 1

        lastCompactedBase = base
        lastCompactedCount = count
        lastCompactedAt = now

        repeat(delta) { SeasoningState.incrementFromChat() }
    }

    @HandleEvent
    fun onHarvestFeastSnapshot(event: HarvestFeastSnapshot) {
        SeasoningState.applyMenuSnapshot(total = event.total, targets = event.targets)
        if (event.total != null) {
            logger.info("Hard-updated seasonings from Harvest Feast menu: ${event.total}")
        }
    }
}
