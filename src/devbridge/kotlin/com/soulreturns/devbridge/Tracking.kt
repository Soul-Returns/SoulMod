package com.soulreturns.devbridge

import net.minecraft.world.entity.player.Player
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * When each other player entity started / stopped being tracked by the client. Fed from the
 * ClientLevel add/remove hooks (client thread), always on: a map entry per player spawn, so
 * an agent attaching mid-run still sees since when everyone is tracked.
 */
object Tracking {
    private val since = ConcurrentHashMap<UUID, Pair<Long, Long?>>()
    private val untracks = ArrayDeque<Map<String, Any?>>()
    private val lastSeen = LinkedHashMap<String, Pair<Long, List<Double>>>()

    fun since(uuid: UUID): Pair<Long, Long?>? = since[uuid]

    @JvmStatic
    fun added(player: Player) {
        val now = System.currentTimeMillis()
        since[player.uuid] = now to Ticks.tick()
        Bridge.emit("tracking") {
            json(linkedMapOf("event" to "tracked", "name" to player.gameProfile.name, "uuid" to player.uuid.toString(), "real" to (player.uuid.version() == 4), "pos" to listOf(player.x, player.y, player.z)))
        }
    }

    @JvmStatic
    fun removed(player: Player) {
        val now = System.currentTimeMillis()
        val start = since.remove(player.uuid)
        val pos = listOf(player.x, player.y, player.z)
        val name = player.gameProfile.name
        val entry = linkedMapOf<String, Any?>("name" to name, "uuid" to player.uuid.toString(), "at" to now, "tick" to Ticks.tick(), "last_pos" to pos, "tracked_ms" to start?.let { now - it.first })
        synchronized(this) {
            untracks.addLast(entry)
            while (untracks.size > MAX_UNTRACKS) untracks.removeFirst()
            lastSeen[name] = now to pos
            if (lastSeen.size > MAX_NAMES) lastSeen.remove(lastSeen.keys.first())
        }
        Bridge.emit("tracking") { json(entry + ("event" to "untracked")) }
    }

    @Synchronized
    fun recentUntracks(): List<Map<String, Any?>> {
        val cutoff = System.currentTimeMillis() - RECENT_MS
        return untracks.filter { (it["at"] as Long) >= cutoff }
    }

    @Synchronized
    fun lastSeen(name: String): Pair<Long, List<Double>>? = lastSeen[name]

    /** World change: every entity goes away without a remove. */
    fun clear() {
        since.clear()
    }

    private const val MAX_UNTRACKS = 64
    private const val MAX_NAMES = 256
    private const val RECENT_MS = 60_000L
}
