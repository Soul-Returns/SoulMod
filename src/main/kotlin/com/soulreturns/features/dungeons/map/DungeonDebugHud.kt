package com.soulreturns.features.dungeons.map

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.platform.http.BackendAuth
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.ui.hud.HudElement
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import org.joml.Matrix3x2f

/**
 * Compact debug overlay for the dungeon map + sync, drawn just below the map card. Toggle:
 * Dungeons → Map → "Debug overlay" (developer access only, like the overlay itself). Shows
 * location/run state, dataset + auth status, per-channel config gates, and a one-line-per-room dump
 * (name, checkmark, secret count, source).
 */
object DungeonDebugHud : HudElement("dungeon_debug") {
    private const val LINE = 7
    private const val PAD = 4
    private const val WIDTH = 150
    private const val BG = 0xC8000000.toInt()
    private const val LABEL = 0xFF8AD8FF.toInt()
    private const val VALUE = 0xFFE8E8E8.toInt()
    private const val WARN = 0xFFFFC030.toInt()

    override fun isEnabled(): Boolean = DevAccess.unlocked && cfg.dungeons.debugHudEnabled && LocationApi.inDungeon

    override fun extract(g: GuiGraphicsExtractor) {
        val conf = cfg.dungeons
        val lines = buildLines()

        // Independent position + scale (own config), so the overlay can sit clear of the map.
        g.pose().pushMatrix()
        g.pose().translate(conf.debugHudX, conf.debugHudY)
        g.pose().scale(conf.debugHudScale, conf.debugHudScale)

        val height = PAD * 2 + lines.size * LINE
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(
                SoulRenderPipelines.ROUND_SMALL,
                TextureSetup.noTexture(),
                Matrix3x2f(g.pose()),
                0, 0, WIDTH, height, BG, null,
            ),
        )

        var ly = PAD
        for ((text, color) in lines) {
            SoulText.draw(g, text, PAD, ly, 5.5f, color)
            ly += LINE
        }
        g.pose().popMatrix()
    }

    private fun buildLines(): List<Pair<String, Int>> {
        val out = ArrayList<Pair<String, Int>>()
        out.add("SOUL DUNGEON DEBUG" to LABEL)
        out.add("instance ${LocationApi.serverId ?: "?"}  ${DungeonRunState.floor.shortName.ifEmpty { "?" }}" to VALUE)

        val dataset = if (DungeonDataset.loaded) "loaded" to VALUE else "loading…" to WARN
        out.add("dataset ${dataset.first}" to dataset.second)

        val auth = if (BackendAuth.token() != null) "backend auth ok" to VALUE else "backend auth none" to WARN
        out.add(auth.first to auth.second)

        // One master toggle drives both channels (backend for Soul users, devonian fallback).
        out.add((if (cfg.dungeons.secretsSyncEnabled) "sync on (backend+devonian)" else "sync off") to VALUE)

        val roster = DungeonRunState.players.filterNotNull()
        out.add("players ${roster.size}  mimic ${if (DungeonRunState.mimicKilled) "y" else "n"}" to VALUE)

        val rooms = DungeonGrid.uniqueRooms()
        val matched = rooms.count { it.identified }
        out.add("rooms ${rooms.size} (matched $matched)" to LABEL)

        // Room lines: matched rooms first, capped so the overlay stays compact.
        val shown = rooms.sortedByDescending { it.identified }.take(14)
        for (room in shown) {
            val name = room.roomName ?: room.type.name.lowercase()
            val secrets = if (room.secretsTotal > 0) "${room.secretsFound}/${room.secretsTotal}" else "-"
            val src = if (room.fromSync) "*" else ""
            val check = when (room.checkmark) {
                Checkmark.GREEN -> "G"
                Checkmark.WHITE -> "W"
                Checkmark.FAILED -> "F"
                Checkmark.UNEXPLORED -> "?"
                Checkmark.NONE -> "-"
            }
            out.add("$check ${name.take(16)} $secrets$src" to VALUE)
        }
        if (rooms.size > shown.size) out.add("… +${rooms.size - shown.size} more" to VALUE)

        return out
    }
}
