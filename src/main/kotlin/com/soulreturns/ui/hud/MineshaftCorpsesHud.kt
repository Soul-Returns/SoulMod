package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.features.mining.mineshaft.MineshaftCorpses
import com.soulreturns.gui.lib.GuiLayoutApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Mineshaft corpse overlay — one line per corpse type currently visible in the tab list, with
 * `unlooted` (§c red) and `looted` (§a green) counts. Only shown while in `Area: Mineshaft`.
 *
 * Pure view; reads from [MineshaftCorpses] which owns the tab-list scan.
 */
object MineshaftCorpsesHud {
    private const val ELEMENT_ID = "mineshaft_corpses"

    /** Display color per corpse type. Unknown types fall back to white. */
    private val TYPE_COLOR =
        mapOf(
            "Lapis" to "§9",
            "Umber" to "§6",
            "Tungsten" to "§f",
            "Vanguard" to "§5",
        )

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ -> updateHud() }
        )
    }

    private fun updateHud() {
        val enabled = cfg.mining.mineshaft.showCorpsesHud() && LocationApi.isInArea("Mineshaft")
        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "§bCorpses",
            lines = if (enabled) buildLines() else listOf(""),
            color = 0xFFFFFFFF.toInt(),
            enabled = enabled,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.5,
            defaultScale = 1.0f,
        )
    }

    private fun buildLines(): List<String> {
        val grouped = MineshaftCorpses.byType
        if (grouped.isEmpty()) return listOf("§7None")
        return grouped.entries
            .sortedWith(compareByDescending<Map.Entry<String, MineshaftCorpses.Counts>> { it.value.total }.thenBy { it.key })
            .map { (type, c) ->
                val color = TYPE_COLOR[type] ?: "§f"
                "$color$type: §a${c.looted} §7/ §c${c.total}"
            }
    }
}
