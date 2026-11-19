package com.soulreturns.api

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.util.SoulLogger
import net.hypixel.modapi.HypixelModAPI
import net.hypixel.modapi.packet.impl.clientbound.ClientboundHelloPacket
import net.hypixel.modapi.packet.impl.clientbound.ClientboundPartyInfoPacket
import net.hypixel.modapi.packet.impl.clientbound.event.ClientboundLocationPacket
import net.hypixel.modapi.packet.impl.serverbound.ServerboundPartyInfoPacket
import java.util.UUID
import kotlin.jvm.optionals.getOrNull

/**
 * Bridge from the official Hypixel Mod API (the bundled `hypixel-mod-api` Fabric mod) onto the
 * Soul event bus. The mod-api mod owns the plugin-channel plumbing; we only register handlers.
 *
 * [LocationChangeEvent] is the authoritative "where am I" signal — Hypixel pushes it on every
 * server switch (lobby → island → dungeon instance), no /locraw polling needed. `serverName` is
 * the instance id (e.g. `mini121A`) that keys the dungeon websocket channel.
 */
object HypixelModApi {
    private val logger = SoulLogger("Soul/HypixelModApi")

    data class LocationChangeEvent(
        val serverName: String,
        val serverType: String?,
        val lobbyName: String?,
        val mode: String?,
        val map: String?,
    ) : Event

    data class PartyInfoEvent(
        val inParty: Boolean,
        val members: Set<UUID>,
        val leader: UUID? = null,
        val moderators: Set<UUID> = emptySet(),
    ) : Event

    fun register() {
        val api = HypixelModAPI.getInstance()

        // Location is an *event* packet: Hypixel only pushes it after an explicit subscription.
        api.subscribeToEventPacket(ClientboundLocationPacket::class.java)
        api.createHandler(ClientboundLocationPacket::class.java) { packet ->
            val event = LocationChangeEvent(
                serverName = packet.serverName,
                serverType = packet.serverType.getOrNull()?.name(),
                lobbyName = packet.lobbyName.getOrNull(),
                mode = packet.mode.getOrNull(),
                map = packet.map.getOrNull(),
            )
            logger.debug("Location: $event")
            Events.publish(event)
        }

        api.createHandler(ClientboundHelloPacket::class.java) { packet ->
            HypixelNetwork.onEnvironment(packet.environment.name)
        }

        api.createHandler(ClientboundPartyInfoPacket::class.java) { packet ->
            val inParty = packet.isInParty
            val members: Set<UUID> = if (inParty) packet.members else emptySet()
            val leader = if (inParty) packet.leader.getOrNull() else null
            val moderators = if (inParty) packet.memberMap.values.filter { it.role.name == "MOD" }.mapTo(HashSet()) { it.uuid } else emptySet()
            Events.publish(PartyInfoEvent(inParty, members, leader, moderators))
        }

        logger.info("Hypixel Mod API handlers registered")
    }

    /** Ask Hypixel to push a fresh [PartyInfoEvent] (party tracking, the devonian party hash, corpse sharing). */
    fun requestPartyInfo() {
        try {
            HypixelModAPI.getInstance().sendPacket(ServerboundPartyInfoPacket())
        } catch (t: Throwable) {
            logger.debug("requestPartyInfo failed: ${t.message}")
        }
    }
}
