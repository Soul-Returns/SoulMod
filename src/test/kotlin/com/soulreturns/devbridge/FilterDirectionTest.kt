package com.soulreturns.devbridge

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** `record --preset full --dir both` once recorded no outbound packet at all: the preset's type list had no serverbound type. */
class FilterDirectionTest {
    private fun filter(vararg q: Pair<String, String>) = Filter.parse(mapOf(*q), recording = true)

    @Test
    fun presetFullWithDirBothObservesThePlayersOwnActions() {
        val f = filter("preset" to "full", "dir" to "both")
        assertTrue(f.inbound && f.outbound)
        for (type in listOf("use_item_on", "use_item", "interact", "attack", "player_action", "swing", "set_carried_item", "container_click", "container_close", "player_input", "player_command", "move_player_pos", "chat_command")) {
            assertTrue(f.wantsPacket(false, type, null), type)
        }
        assertTrue(f.wantsPacket(true, "add_entity", null))
    }

    @Test
    fun outboundMatchesByExactIdAndInboundIsUnchanged() {
        val f = filter("preset" to "full", "dir" to "both")
        assertFalse(f.wantsPacket(false, "chat_session_update", null))
        assertFalse(f.wantsPacket(false, "custom_payload", null))
        assertFalse(f.wantsPacket(true, "use_item_on", null))
        assertFalse(f.wantsPacket(true, "player_chat", null))
    }

    @Test
    fun theOldOutFlagAndTheDefaultRecordingFollowTheSameRule() {
        val flagged = filter("preset" to "full", "out" to "1")
        assertTrue(flagged.outbound && flagged.wantsPacket(false, "swing", null))
        val plain = filter("preset" to "full")
        assertFalse(plain.outbound)
        assertFalse(plain.wantsPacket(false, "swing", null))
        val both = filter("dir" to "both")
        assertEquals(Filter.FULL_OUT_PACKETS, both.outTypes)
        assertTrue(both.wantsPacket(false, "container_click", null))
        assertEquals("both", both.describe()["dir"])
    }

    @Test
    fun countsShowEachDirection() {
        val l = Listener(1, filter("preset" to "full", "dir" to "both"), "recording")
        l.countPacket(true, "add_entity")
        l.countPacket(false, "swing")
        l.countPacket(false, "swing")
        assertEquals(mapOf("in" to 1L, "out" to 2L, "out_types" to sortedMapOf("swing" to 2L)), l.packetCounts())
    }
}
