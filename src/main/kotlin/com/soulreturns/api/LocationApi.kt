package com.soulreturns.api

import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/**
 * Where the player currently is on Hypixel, fed by [HypixelModApi.LocationChangeEvent].
 *
 * `serverId` is the Hypixel instance id (e.g. `mini121A`) — everyone in the same dungeon run
 * shares one, which is why it keys the Skyblocker-compatible websocket channel.
 */
object LocationApi {
    var serverId: String? = null
        private set
    var serverType: String? = null
        private set
    var mode: String? = null
        private set
    var map: String? = null
        private set

    val inSkyblock: Boolean get() = serverType == "SKYBLOCK"

    /** In a Catacombs instance (includes boss + the pre-blood staging area). */
    val inDungeon: Boolean get() = inSkyblock && mode == "dungeon"

    /** Fired whenever [inDungeon] flips; features reset their run state on `false`. */
    data class DungeonStateEvent(val inDungeon: Boolean, val serverId: String?) : Event

    fun register() {
        Events.subscribe<HypixelModApi.LocationChangeEvent> { event ->
            val wasInDungeon = inDungeon
            serverId = event.serverName
            serverType = event.serverType
            mode = event.mode
            map = event.map
            if (inDungeon != wasInDungeon) {
                Events.publish(DungeonStateEvent(inDungeon, serverId))
            }
        }

        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            val wasInDungeon = inDungeon
            serverId = null
            serverType = null
            mode = null
            map = null
            if (wasInDungeon) Events.publish(DungeonStateEvent(false, null))
        }
    }
}
