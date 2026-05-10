package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.gui.lib.GuiLayoutApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/**
 * Legion HUD — counts other real players within a 30-block radius and exposes a
 * positionable text block (anchored via [GuiLayoutApi]).
 *
 * No persistent state: the count is recomputed each tick. Pure view; nothing else
 * in the codebase reads "legion count," so there's no separate state object.
 */
object LegionHud {
    private const val ELEMENT_ID = "legion_counter"
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    private fun tick(client: Minecraft) {
        val player = client.player ?: return
        val world = client.level ?: return

        // Hypixel SkyBlock NPCs typically have non-v4 UUIDs; filtering by UUID version
        // gives us a count that closely matches Legion stacks.
        val count =
            world.players().count { other ->
                other !== player &&
                    other.isRealPlayer() &&
                    player.distanceToSqr(other) <= RADIUS_SQ
            }

        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "Legion",
            lines = listOf("Nearby players: $count"),
            color = 0xFFFFFFFF.toInt(),
            enabled = cfg.render.overlays.enableLegionCounter(),
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.3,
            defaultScale = 1.0f,
        )
    }

    private fun Player.isRealPlayer(): Boolean = uuid?.let { it.version() == 4 } ?: false
}
