package com.soulreturns.data.skyblock

import com.soulreturns.util.MojangUuid

/**
 * Hypixel's profile identity pair in chat: a name line, then `Profile ID: <uuid>`.
 *
 * A SkyBlock server join prints `You are playing on profile: Blueberry (Co-op)`; a `/profile` switch
 * prints `Your profile was changed to: Blueberry (Co-op)` instead, with no join line after it.
 */
object ProfileChatLines {
    private val NAME = Regex("(?:You are playing on profile|Your profile was changed to): ([A-Za-z][A-Za-z0-9_]*)(?:\\s*\\([^)]+\\))?")
    private val ID = Regex("Profile ID: ([0-9a-fA-F-]{32,36})")

    /** The profile name on a color-stripped, trimmed name line, else null. */
    fun name(line: String): String? = NAME.matchEntire(line)?.groupValues?.get(1)

    /** The dashed lowercase id on a color-stripped, trimmed `Profile ID:` line, else null. */
    fun id(line: String): String? = ID.matchEntire(line)?.let { MojangUuid.parse(it.groupValues[1])?.toString() }
}

/**
 * The exact profile id announced for one world: a [ProfileChatLines] name line, then the id line,
 * both received in that world. Caches keyed by exact profile use it so an id never outlives the
 * server it was announced on and a bare `Profile ID:` line resolves nothing.
 */
class FreshProfileId {
    private var nameWorld: Any? = null
    private var idWorld: Any? = null

    /** Dashed lowercase; check it with [resolved]. */
    var id: String? = null
        private set

    /** Feeds one color-stripped, trimmed server line received in [world]; true when it announced the id. */
    fun offer(line: String, world: Any?): Boolean {
        if (world == null) return false
        if (ProfileChatLines.name(line) != null) {
            nameWorld = world
            return false
        }
        val next = ProfileChatLines.id(line) ?: return false
        if (nameWorld !== world) return false
        nameWorld = null
        id = next
        idWorld = world
        return true
    }

    /** The id while still in the [world] it was announced in and [SkyblockProfileState] reports the same [stateId]. */
    fun resolved(world: Any?, stateId: String?): String? = id?.takeIf { world != null && world === idWorld && it == stateId }

    fun reset() {
        nameWorld = null
        idWorld = null
        id = null
    }
}
