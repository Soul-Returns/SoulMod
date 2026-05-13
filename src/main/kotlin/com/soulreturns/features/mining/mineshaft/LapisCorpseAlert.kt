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
 * Watches for the "Sending to Mineshaft..." server message. Within a 10s window after that,
 * reads the Lapis count from [MineshaftCorpses]. If the count reaches the configured
 * threshold the feature fires `/pc !ptme Found N Lapis Corpses in Mineshaft` exactly once
 * per "Sending..." event so party members can quickly assess loot potential.
 */
object LapisCorpseAlert {
    private val logger = SoulLogger("Soul/LapisCorpseAlert")

    private const val WINDOW_MS = 10_000L
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
        if (!cfg.mining.mineshaft.enableLapisPtme()) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        if (!SENDING_PATTERN.containsMatchIn(stripped)) return
        armedAtMs = System.currentTimeMillis()
        fired = false
        DebugLogger.logFeatureEvent("LapisCorpseAlert armed: 'Sending to Mineshaft...' seen")
    }

    private fun tick() {
        if (armedAtMs == 0L || fired) return
        if (!cfg.mining.mineshaft.enableLapisPtme()) {
            armedAtMs = 0L
            return
        }
        if (System.currentTimeMillis() - armedAtMs > WINDOW_MS) {
            armedAtMs = 0L
            return
        }
        val threshold = cfg.mining.mineshaft.lapisCorpseThreshold()
        val count = MineshaftCorpses.totalOf("Lapis")
        if (count >= threshold) {
            if (!PartyManager.isInParty()) {
                DebugLogger.logFeatureEvent("LapisCorpseAlert: $count Lapis corpses but not in a party — skipping !ptme")
                fired = true
                armedAtMs = 0L
                return
            }
            val player = Minecraft.getInstance().player ?: return
            val noun = if (count == 1) "Lapis Corpse" else "Lapis Corpses"
            player.connection.sendCommand("pc !ptme Found $count $noun in Mineshaft")
            logger.info("Sent /pc !ptme for $count Lapis corpses (threshold $threshold)")
            fired = true
            armedAtMs = 0L
        }
    }
}
