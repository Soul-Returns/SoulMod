package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Sibling of [LapisCorpseAlert] for Vanguard Corpses. There is **at most one Vanguard Corpse
 * per mineshaft** (Hypixel constraint), so no threshold slider and no count interpolation —
 * within 10 s of `Sending to Mineshaft...`, the first time `MineshaftCorpses.totalOf("Vanguard")
 * > 0` we fire `/pc !ptme Found Vanguard Corpse in Mineshaft` once per send.
 */
object VanguardCorpseAlert {
    private val logger = SoulLogger("Soul/VanguardCorpseAlert")

    private const val WINDOW_MS = 10_000L
    private const val TARGET_TYPE = "Vanguard"
    private val SENDING_PATTERN = Regex("Sending to Mineshaft\\.\\.\\.", RegexOption.IGNORE_CASE)

    @Volatile private var armedAtMs: Long = 0L

    @Volatile private var fired: Boolean = false

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ -> tick() }
        )
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.mining.mineshaft.enableVanguardPtme()) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        if (!SENDING_PATTERN.containsMatchIn(stripped)) return
        armedAtMs = System.currentTimeMillis()
        fired = false
        DebugLogger.logFeatureEvent("VanguardCorpseAlert armed: 'Sending to Mineshaft...' seen")
    }

    private fun tick() {
        if (armedAtMs == 0L || fired) return
        if (!cfg.mining.mineshaft.enableVanguardPtme()) {
            armedAtMs = 0L
            return
        }
        if (System.currentTimeMillis() - armedAtMs > WINDOW_MS) {
            armedAtMs = 0L
            return
        }
        if (MineshaftCorpses.totalOf(TARGET_TYPE) > 0) {
            if (MineshaftVisitTracker.isWarpedVisit()) {
                DebugLogger.logFeatureEvent("VanguardCorpseAlert: warped into this mineshaft — skipping !ptme (not our discovery)")
                fired = true
                armedAtMs = 0L
                return
            }
            if (!PartyManager.isInParty()) {
                DebugLogger.logFeatureEvent("VanguardCorpseAlert: Vanguard detected but not in a party — skipping !ptme")
                fired = true
                armedAtMs = 0L
                return
            }
            val player = Minecraft.getInstance().player ?: return
            player.connection.sendCommand("pc !ptme Found Vanguard Corpse in Mineshaft")
            logger.info("Sent /pc !ptme — Vanguard Corpse detected")
            fired = true
            armedAtMs = 0L
        }
    }
}
