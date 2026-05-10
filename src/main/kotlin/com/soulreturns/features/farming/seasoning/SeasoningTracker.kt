package com.soulreturns.features.farming.seasoning

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.model.HarvestFeastSnapshot
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

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

    fun register() {
        SeasoningState.init()
        Events.subscribe(this)
    }

    /** Wipe persisted total + session metrics — invoked by `/soul dev resetSeasonings`. */
    fun reset() = SeasoningState.reset()

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        // Hypixel sends e.g. "§6§lRARE CROP! §r§eSeasoning (automatically donated)" — strip codes first.
        if (!MessageDetector.containsPattern(event.raw, "RARE CROP! Seasoning")) return
        SeasoningState.incrementFromChat()
    }

    @HandleEvent
    fun onHarvestFeastSnapshot(event: HarvestFeastSnapshot) {
        SeasoningState.applyMenuSnapshot(total = event.total, targets = event.targets)
        if (event.total != null) {
            logger.info("Hard-updated seasonings from Harvest Feast menu: ${event.total}")
        }
    }
}
