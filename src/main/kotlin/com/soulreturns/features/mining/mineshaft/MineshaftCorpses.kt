package com.soulreturns.features.mining.mineshaft

import com.soulreturns.data.location.LocationApi
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Polls the tab list each client tick while in `Area: Mineshaft` and groups Frozen Corpse
 * entries by type. Single source of truth used by both `LapisCorpseAlert` (party-chat trigger)
 * and `MineshaftCorpsesHud` (overlay).
 *
 * Hypixel renders one tab row per corpse instance, in the form ` <Type>: NOT LOOTED` or
 * ` <Type>: LOOTED`. Outside the Mineshaft area the map is empty.
 */
object MineshaftCorpses {
    /** Per-type breakdown of corpse counts. */
    data class Counts(val unlooted: Int, val looted: Int) {
        val total: Int get() = unlooted + looted
    }

    private val LINE = Regex("""\s*(\w+):\s+(?:✔\s*)?(NOT LOOTED|LOOTED)\s*""")

    @Volatile var byType: Map<String, Counts> = emptyMap()
        private set

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ -> tick() }
        )
    }

    /** Convenience accessor — total corpses of [type], or 0. Case-sensitive. */
    fun totalOf(type: String): Int = byType[type]?.total ?: 0

    private fun tick() {
        if (!LocationApi.isInArea("Mineshaft")) {
            if (byType.isNotEmpty()) byType = emptyMap()
            return
        }
        val conn = Minecraft.getInstance().player?.connection ?: return
        val acc = mutableMapOf<String, Counts>()
        for (info in conn.listedOnlinePlayers) {
            val displayName = info.tabListDisplayName?.string ?: continue
            val stripped = MessageDetector.stripColorCodes(displayName)
            val m = LINE.matchEntire(stripped) ?: continue
            val type = m.groupValues[1]
            val looted = m.groupValues[2] == "LOOTED"
            val prev = acc[type] ?: Counts(0, 0)
            acc[type] =
                if (looted) prev.copy(looted = prev.looted + 1) else prev.copy(unlooted = prev.unlooted + 1)
        }
        byType = acc
    }
}
