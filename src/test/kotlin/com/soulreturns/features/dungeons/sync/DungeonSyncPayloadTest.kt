package com.soulreturns.features.dungeons.sync

import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DungeonSyncPayloadTest {
    private val self = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa")
    private val teammate = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb")
    private val message = buildJsonObject {
        put("type", "room_secret_count")
        put("roomId", 7)
        put("secretCount", 3)
    }

    @Test
    fun fivePersonRosterTargetsExactlyFourTeammates() {
        val roster = sequenceOf(self, teammate, UUID(0, 2), UUID(0, 3), UUID(0, 4))
        val body = assertNotNull(DungeonSyncPayload.build("mini62V", roster, self, message))
        assertEquals("mini62V", body["serverId"]?.jsonPrimitive?.content)
        assertEquals(
            listOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", "00000000000000000000000000000002", "00000000000000000000000000000003", "00000000000000000000000000000004"),
            body["recipients"]?.jsonArray?.map { it.jsonPrimitive.content },
        )
        assertEquals(message, body["message"])
    }

    @Test
    fun unknownSelfAndRepeatedRosterEntriesAreNotRecipients() {
        val body = assertNotNull(DungeonSyncPayload.build("mini62V", sequenceOf(null, teammate, self, teammate), self, message))
        assertEquals(listOf("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"), body["recipients"]?.jsonArray?.map { it.jsonPrimitive.content })
        assertNull(DungeonSyncPayload.build("mini62V", sequenceOf(self, null), self, message))
        assertNull(DungeonSyncPayload.build("mini62V", emptySequence(), self, message))
    }

    @Test
    fun recipientsAreRebuiltWhenRosterChanges() {
        val old = assertNotNull(DungeonSyncPayload.build("mini62V", sequenceOf(self, teammate), self, message))
        val replacement = assertNotNull(DungeonSyncPayload.build("mini62V", sequenceOf(self, UUID(0, 2)), self, message))
        assertEquals("bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", old["recipients"]?.jsonArray?.single()?.jsonPrimitive?.content)
        assertEquals("00000000000000000000000000000002", replacement["recipients"]?.jsonArray?.single()?.jsonPrimitive?.content)
    }

    @Test
    fun inboundRequiresOwnPrivateTopicAndCurrentInstanceWithoutFallback() {
        val event = buildJsonObject { put("serverId", "mini62V") }
        val topic = "user/aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        assertTrue(DungeonSyncPayload.accepts(topic, event, "mini62V", self))
        assertFalse(DungeonSyncPayload.accepts("user/bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb", event, "mini62V", self))
        assertFalse(DungeonSyncPayload.accepts("dungeon/mini62V", event, "mini62V", self))
        assertFalse(DungeonSyncPayload.accepts("dungeon/{serverId}", event, "mini62V", self))
        assertFalse(DungeonSyncPayload.accepts(null, event, "mini62V", self))
        assertFalse(DungeonSyncPayload.accepts(topic, event, "mini99A", self))
        assertFalse(DungeonSyncPayload.accepts(topic, event, null, self))
        assertFalse(DungeonSyncPayload.accepts(topic, message, "mini62V", self))
        assertFalse(DungeonSyncPayload.accepts(topic, buildJsonObject { put("serverId", message) }, "mini62V", self))
    }
}
