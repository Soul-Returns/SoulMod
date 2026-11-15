package com.soulreturns.features.dungeons.sync

import kotlinx.serialization.json.JsonObject

/**
 * A transport that carries dungeon secret-sync messages between party members. The messages
 * themselves (`room_match`, `room_secret_count`, `mimic_killed`, `prince_killed`) are built and
 * applied once by [SecretSyncHandler]; each channel only moves the JSON over its own wire.
 *
 * Channels in play:
 *  - [SoulBackendChannel] — our own backend (Mercure SSE + REST). Primary.
 *  - [DevonianChannel] — devonian's ws server, for teammates on that mod. Fallback, partial.
 *  - [SkyblockerChannel] — Skyblocker's ws (opt-in; blocked by their UA whitelist for now).
 */
interface SecretSyncChannel {
    val id: String

    /** Config gate — read `cfg` live, never cache. */
    fun isEnabled(): Boolean

    /** Player entered a dungeon instance: connect/subscribe for [serverId]. */
    fun onDungeonStart(serverId: String)

    /** Player left the dungeon: disconnect/unsubscribe. */
    fun onDungeonEnd()

    /** Broadcast an inner message (the canonical Skyblocker-schema object) to party members. */
    fun publish(message: JsonObject)
}
