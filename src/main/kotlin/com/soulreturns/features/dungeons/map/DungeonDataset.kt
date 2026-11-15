package com.soulreturns.features.dungeons.map

import com.soulreturns.util.SoulLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.concurrent.ConcurrentHashMap

/**
 * The dungeon room dataset, sourced from devonian's `rooms.json` (bundled at
 * `assets/soul/dungeons/devonian_rooms.json`, GPL-3.0). Each room carries:
 *  - `roomID` — the canonical integer identity, used as our room key AND the devonian ws wire key.
 *  - `name` — display name (e.g. "Waterfall").
 *  - `secrets` — total secret count.
 *  - `cores` — one or more `String.hashCode()` fingerprints of the ceiling block column at the
 *    room's centre (per rotation). [CeilingHashMatcher] hashes a live column and looks it up here,
 *    which is how rooms are identified without the player entering them.
 */
object DungeonDataset {
    private val logger = SoulLogger("Soul/DungeonDataset")
    private val json = Json { ignoreUnknownKeys = true }

    class RoomInfo(val roomId: Int, val name: String, val secrets: Int, val type: RoomType)

    private fun parseType(s: String?): RoomType = when (s?.lowercase()) {
        "entrance" -> RoomType.ENTRANCE
        "puzzle" -> RoomType.PUZZLE
        "trap" -> RoomType.TRAP
        "fairy" -> RoomType.FAIRY
        "blood" -> RoomType.BLOOD
        "rare" -> RoomType.RARE
        "yellow", "miniboss" -> RoomType.MINIBOSS
        else -> RoomType.NORMAL
    }

    private val byCore = ConcurrentHashMap<Int, RoomInfo>()
    private val byId = ConcurrentHashMap<Int, RoomInfo>()

    @Volatile
    var loaded = false
        private set
    private var loadStarted = false

    /** Identify a room from a live ceiling core-hash. */
    fun roomForCore(coreHash: Int): RoomInfo? = byCore[coreHash]

    fun roomForId(roomId: Int): RoomInfo? = byId[roomId]

    @Synchronized
    fun ensureLoaded() {
        if (loadStarted) return
        loadStarted = true
        // Small file; load synchronously off the caller (first dungeon tick). ~140 rooms.
        load()
    }

    private fun load() {
        val start = System.currentTimeMillis()
        try {
            val stream = javaClass.getResourceAsStream("/assets/soul/dungeons/devonian_rooms.json")
            if (stream == null) {
                logger.error("devonian_rooms.json not found — room identification disabled")
                return
            }
            val text = stream.bufferedReader().use { it.readText() }
            val arr = json.parseToJsonElement(text).jsonArray
            var cores = 0
            for (entry in arr) {
                val obj = entry.jsonObject
                val roomId = obj["roomID"]?.jsonPrimitive?.int ?: continue
                val name = obj["name"]?.jsonPrimitive?.content ?: continue
                val secrets = obj["secrets"]?.jsonPrimitive?.int ?: 0
                val type = parseType(obj["type"]?.jsonPrimitive?.content)
                val info = RoomInfo(roomId, name, secrets, type)
                byId[roomId] = info
                obj["cores"]?.jsonArray?.forEach { core ->
                    core.jsonPrimitive.content.toIntOrNull()?.let { byCore[it] = info; cores++ }
                }
            }
            loaded = byCore.isNotEmpty()
            logger.info("Dungeon dataset loaded: ${byId.size} rooms, $cores cores in ${System.currentTimeMillis() - start}ms")
        } catch (t: Throwable) {
            logger.error("Dungeon dataset load failed", t)
        }
    }
}
