package com.soulreturns.features

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft

private const val DOUBLE_HOOK_PATTERN = "Double Hook!"

object DoubleHookResponse {
    private val logger = SoulLogger("Soul/Fishing")

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.fishing.chat.doubleHookMessageToggle()) return
        if (!MessageDetector.containsPattern(event.raw, DOUBLE_HOOK_PATTERN)) return

        val player = Minecraft.getInstance().player ?: return

        if (!PartyManager.isInParty()) {
            DebugLogger.logFeatureEvent("DoubleHookResponse: Double Hook detected but not in a party — skipping /pc")
            return
        }

        val text = cfg.fishing.chat.doubleHookMessageText()
        logger.info("Double Hook detected, sending party message: {}", text)
        player.connection.sendCommand("pc $text")
    }
}
