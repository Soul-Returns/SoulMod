package com.soulreturns.features.fishing

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.DoubleHookDetected
import com.soulreturns.features.party.PartyManager
import com.soulreturns.sound.SoulSounds
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.withOutgoingPrefix
import net.minecraft.client.Minecraft

/**
 * Announces a double hook to party chat — the Woot-Woot send.
 *
 * ### It no longer reads chat
 * It used to carry its own `Double Hook!` substring check. [FishingTracker] is now the mod's single
 * detector for that line and this subscribes to [DoubleHookDetected], so there is one regex to keep
 * correct the next time Hypixel rewords the message rather than two that can silently disagree.
 *
 * A compactor-coalesced `It's a Double Hook! (3)` arrives here as one event with `count = 3`. That
 * is deliberately ignored: the count matters to a counter, not to an announcement — three messages
 * for one line the party never saw three of would just be spam.
 *
 * ### Two gates that are not optional
 * Hypixel **silently drops** `/pc` when you are not in a party — no error, no feedback — so an
 * ungated send looks like a broken feature rather than a rejected one. And the message is
 * *conversational*, so it takes the `[Soul]` outgoing prefix when that setting is on, unlike a
 * protocol message such as `!ptme` or a waypoint share, which must stay raw for other mods' parsers.
 */
object DoubleHookResponse {
    private val logger = SoulLogger("Soul/Fishing")

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    @Suppress("UNUSED_PARAMETER")
    fun onDoubleHook(event: DoubleHookDetected) {
        cfg.fishing.doubleHookSound?.let { SoulSounds.play(it) }
        if (!cfg.fishing.doubleHookMessageEnabled) return
        val player = Minecraft.getInstance().player ?: return

        if (!PartyManager.isInParty()) {
            DebugLogger.logFeatureEvent("DoubleHookResponse: double hook detected but not in a party — skipping /pc")
            return
        }

        val text = cfg.fishing.doubleHookMessageText
        logger.info("Double hook detected, sending party message: $text")
        player.connection.sendCommand("pc ${withOutgoingPrefix(text)}")
    }
}
