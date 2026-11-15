package com.soulreturns.features.dungeons.sync

import com.soulreturns.util.MojangUuid
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

internal object DungeonSyncPayload {
    fun build(serverId: String, roster: Sequence<UUID?>, self: UUID, message: JsonObject): JsonObject? {
        val recipients = roster.filterNotNull().filter { it != self }.distinct().toList()
        if (recipients.isEmpty()) return null
        return buildJsonObject {
            put("serverId", serverId)
            put("recipients", buildJsonArray {
                for (uuid in recipients) add(JsonPrimitive(MojangUuid.undashed(uuid)))
            })
            put("message", message)
        }
    }

    fun accepts(topic: String?, message: JsonObject, serverId: String?, self: UUID): Boolean =
        serverId != null && topic == "user/${MojangUuid.undashed(self)}" &&
            (message["serverId"] as? JsonPrimitive)?.content == serverId
}
