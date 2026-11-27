package com.soulreturns.features.scoreboard.lines.soul

import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import net.minecraft.client.Minecraft

/**
 * `Room: Withermancers (3/5 secrets)`: the room you stand in and its secret count ([DungeonRunState] /
 * [DungeonGrid], rule://dungeons). The count is the one the run state keeps from the action bar's
 * `x/y Secrets` (with its guard against the previous room's numbers); only while the room has no count
 * yet does the action bar itself fill in. Nothing outside a run or while the room is unidentified.
 */
object DungeonRoomLine : SoulLine(
    "room", "Dungeon room", "The room you are in and its secrets.",
    listOf("§fRoom: §bWithermancers §7(§a3§7/§e5 §7secrets)"), "Dungeons",
) {
    override val colors = LineColors.LabelValue(0xFFFFFF, 0x55FFFF)

    private val SECRETS = Regex("\\b(\\d+)/(\\d+) Secrets\\b")

    /** `found/total` from an action bar's text, or null. */
    fun barSecrets(text: String?): Pair<Int, Int>? =
        text?.let { SECRETS.find(it) }?.let { it.groupValues[1].toInt() to it.groupValues[2].toInt() }

    fun format(name: String, secrets: Pair<Int, Int>?): String =
        "§fRoom: §b$name" + (secrets?.takeIf { it.second > 0 }?.let { " §7(§a${it.first}§7/§e${it.second} §7secrets)" } ?: "")

    override fun lines(ctx: LineContext): List<String> {
        if (!DungeonRunState.inRun) return emptyList()
        val p = Minecraft.getInstance().player ?: return emptyList()
        val room = DungeonGrid.roomAtWorld(p.x, p.z) ?: return emptyList()
        val name = room.roomName ?: return emptyList()
        val own = if (room.secretsFound >= 0 && room.secretsTotal > 0) room.secretsFound to room.secretsTotal else null
        return listOf(format(name, own ?: barSecrets(ctx.sources.actionBar?.text)))
    }
}
