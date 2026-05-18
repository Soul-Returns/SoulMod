package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.soulChat
import com.soulreturns.util.withOutgoingPrefix
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import java.util.Locale

/**
 * Listens for the `"<TYPE> DRAGON DOWN!"` chat banner and broadcasts the local Legion
 * stats to party chat as `Legion: N players (X.XX%)`. Single-firing per banner — same
 * regex as [DragonDeathDetector], so the two co-listen without coupling.
 *
 * **Why a separate object instead of folding into [DragonDeathDetector]:** the death
 * detector's purpose is to trigger the [DragonLootScanner] window; injecting a chat
 * announcer into it would mix two unrelated side effects under the same gate. Keeping
 * them as parallel subscribers also means flipping `sendLegionOnDeath` off while leaving
 * profit tracking on (or vice-versa) is a clean, isolated change.
 *
 * **Gating chain:** master toggle on → in The End is implicitly enforced because dragons
 * don't die anywhere else. Sub-toggle [cfg.combat.dragons.sendLegionToPartyChat] picks the
 * destination: ON → `/pc` when in a party, **falls back to local `[Soul]` chat when solo**
 * (Hypixel silently drops `/pc` outside a party per the `Hypixel SkyBlock conventions`
 * block in CLAUDE.md, so we never blind-send); OFF → local `[Soul]` chat always.
 * No LOS/ToS concern — nearby-player count is read from the loaded entity list, not from
 * through-wall data, and even unrelated mods publish stats like this freely.
 *
 * **Send delay.** The dragon-down banner is a 14-chat-line block from Hypixel
 * (top `▬▬▬▬` separator + DRAGON DOWN! line + damage breakdown + bottom separator).
 * The `DRAGON DOWN!` regex matches on **line 2** — sending immediately would land the
 * `/pc` between lines 2 and 3, fracturing the visual block. A [SEND_DELAY_TICKS] delay
 * (~500 ms) lets the rest of the block flush to chat first, so the announcement appears
 * after the closing `▬▬▬▬`. The legion data (player count, boost %) is **captured at
 * trigger time**, not at send time — values reflect the kill moment, not 500 ms later
 * when players may have moved.
 */
object DragonLegionAnnouncer {
    private val logger = SoulLogger("Soul/DragonLegion")

    /** Matches [DragonDeathDetector.DEATH_REGEX] verbatim — kept in lockstep manually. */
    private val DEATH_REGEX = Regex("^(PROTECTOR|OLD|WISE|UNSTABLE|YOUNG|STRONG|SUPERIOR) DRAGON DOWN!$")

    /** Mirrors the LegionHud-internal Hypixel constants. Stable game values. */
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS
    private const val BOOST_PER_LEVEL = 0.07
    private const val PLAYER_CAP = 20
    private const val ENCHANT_ID = "ultimate_legion"

    /** 10 ticks ≈ 500 ms. See class kdoc for the rationale. */
    private const val SEND_DELAY_TICKS = 10

    /** Pending send queued at trigger time; consumed by [tick] after the delay elapses. */
    private data class PendingSend(val sendAtTick: Int, val toParty: Boolean, val msg: String)

    @Volatile private var pending: PendingSend? = null

    @Volatile private var clientTick: Int = 0

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ -> tick() })
    }

    private fun tick() {
        clientTick++
        val p = pending ?: return
        if (clientTick < p.sendAtTick) return
        pending = null
        val player = Minecraft.getInstance().player ?: return
        if (p.toParty && PartyManager.isInParty()) {
            player.connection.sendCommand("pc ${withOutgoingPrefix(p.msg)}")
            logger.info("Sent /pc on dragon down (delayed): ${p.msg}")
        } else {
            // Either the sub-toggle is off, or the player isn't in a party (or left between
            // trigger and send). Fall back to local `[Soul]` chat — /pc would silently drop.
            soulChat(p.msg)
            logger.info("Sent local chat on dragon down (delayed, toParty=${p.toParty}): ${p.msg}")
        }
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (!cfg.combat.dragons.sendLegionOnDeath()) return
        // Same Dragon's Nest gate as [DragonDeathDetector] — keeps M7 dragon kills from
        // triggering an "end dragon" legion announcement.
        if (!LocationApi.isInSublocation("Dragon's Nest")) return
        val clean = MessageDetector.stripColorCodes(event.raw).trim()
        if (!DEATH_REGEX.matches(clean)) return
        val player = Minecraft.getInstance().player ?: return
        val toParty = cfg.combat.dragons.sendLegionToPartyChat()
        val count = countNearbyPlayers(player)
        val totalLevel = SkyblockItemUtils.summedArmorEnchantLevel(player, ENCHANT_ID)
        val cappedCount = count.coerceAtMost(PLAYER_CAP)
        val boostPercent = totalLevel * BOOST_PER_LEVEL * cappedCount
        val noun = if (count == 1) "player" else "players"
        val msg = String.format(Locale.ROOT, "Legion: %d %s (%.2f%%)", count, noun, boostPercent)
        pending = PendingSend(sendAtTick = clientTick + SEND_DELAY_TICKS, toParty = toParty, msg = msg)
        logger.info("Queued dragon-down announcement (toParty=$toParty, fires in $SEND_DELAY_TICKS ticks): $msg")
    }

    /**
     * Same shape as `LegionHud.countNearbyPlayers` (UUID v4 filter excludes Hypixel NPCs).
     * Reads from the loaded entity list — the player has full client-side knowledge of
     * other players within render distance, so this is not a ToS-relevant LOS check.
     */
    private fun countNearbyPlayers(player: Player): Int {
        val world = Minecraft.getInstance().level ?: return 0
        return world.players().count { other ->
            other !== player &&
                (other.uuid?.let { it.version() == 4 } ?: false) &&
                player.distanceToSqr(other) <= RADIUS_SQ
        }
    }
}
