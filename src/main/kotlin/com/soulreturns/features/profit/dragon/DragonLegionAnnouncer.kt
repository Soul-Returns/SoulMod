package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import com.soulreturns.util.withOutgoingPrefix
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import java.util.Locale

/**
 * Broadcasts `Legion: N players (X.XX%)` on each dragon death — how much damage the crowd around you
 * was worth.
 *
 * Unrelated to profit, and the one part of this feature a player might find spammy, so it is its own
 * toggle and it is **off by default**.
 *
 * ### Why it waits ~500 ms
 * Hypixel's dragon-down banner is a 14-line block — top `▬▬▬` separator, the DOWN! line, the damage
 * breakdown, closing separator — and [DragonDeathDetector] matches on **line 2**. Sending
 * immediately drops the message between lines 2 and 3 and fractures the block. So the data is
 * captured at trigger time (the player count is a fact about the kill moment, not about half a second
 * later) and only the send is deferred.
 *
 * ### `/pc` gating
 * Same rule as everywhere: Hypixel silently drops `/pc` outside a party, so the sub-toggle falls back
 * to local `[Soul]` chat when solo rather than sending into the void. Conversational, so it takes the
 * outgoing prefix.
 *
 * ### Not a ToS concern
 * The nearby-player count comes from the client's own loaded entity list — players within render
 * distance, which the client already knows about — not from any through-wall inference. This is
 * unlike anything that *acts* on a mob's presence, which must go through
 * [com.soulreturns.util.MobSpotter] and its line-of-sight check.
 */
object DragonLegionAnnouncer {
    private val logger = SoulLogger("Soul/DragonLegion")

    /** Hypixel's own Legion constants. Stable game values. */
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS
    private const val BOOST_PER_LEVEL = 0.07
    private const val PLAYER_CAP = 20
    private const val ENCHANT_ID = "ultimate_legion"

    /** 10 ticks ≈ 500 ms — long enough for the banner block to finish flushing. */
    private const val SEND_DELAY_TICKS = 10

    private data class PendingSend(val sendAtTick: Int, val toParty: Boolean, val message: String)

    @Volatile private var pending: PendingSend? = null

    fun register() {
        Events.subscribe<DragonDied> { event -> onDragonDied(event) }
    }

    private fun onDragonDied(event: DragonDied) {
        if (!cfg.combat.dragons.sendLegionOnDeath) return
        val player = Minecraft.getInstance().player ?: return
        val count = countNearbyPlayers(player)
        val totalLevel = SkyblockItemUtils.summedArmorEnchantLevel(player, ENCHANT_ID)
        val boostPercent = totalLevel * BOOST_PER_LEVEL * count.coerceAtMost(PLAYER_CAP)
        val noun = if (count == 1) "player" else "players"
        val message = String.format(Locale.ROOT, "Legion: %d %s (%.2f%%)", count, noun, boostPercent)
        pending = PendingSend(
            sendAtTick = event.tick + SEND_DELAY_TICKS,
            toParty = cfg.combat.dragons.sendLegionToPartyChat,
            message = message,
        )
        logger.info("Queued legion announcement (toParty=${cfg.combat.dragons.sendLegionToPartyChat}): $message")
    }

    /** Called from [DragonTick]. Does nothing while nothing is queued. */
    internal fun tick() {
        val send = pending ?: return
        if (DragonDeathDetector.clientTick < send.sendAtTick) return
        pending = null
        val player = Minecraft.getInstance().player ?: return
        if (send.toParty && PartyManager.isInParty()) {
            player.connection.sendCommand("pc ${withOutgoingPrefix(send.message)}")
            logger.info("Sent /pc legion announcement: ${send.message}")
        } else {
            // Either the sub-toggle is off, or the party was left between trigger and send.
            soulChat(send.message)
            logger.info("Sent local legion announcement (toParty=${send.toParty}): ${send.message}")
        }
    }

    /**
     * Players within [RADIUS], excluding the local player and Hypixel's NPCs — which are filtered by
     * UUID version, since a real Mojang account is always v4 and Hypixel's fakes are not.
     */
    private fun countNearbyPlayers(player: Player): Int {
        val level = Minecraft.getInstance().level ?: return 0
        return level.players().count { other ->
            other !== player && other.uuid?.version() == 4 && player.distanceToSqr(other) <= RADIUS_SQ
        }
    }
}
