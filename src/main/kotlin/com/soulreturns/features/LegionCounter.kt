package com.soulreturns.features

import com.soulreturns.config.config
import com.soulreturns.gui.lib.GuiLayoutApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity

/**
 * Legion counter feature for Hypixel Skyblock.
 *
 * Counts other players within a 30 block radius and updates a TextBlockElement
 * in the GUI layout so it can be positioned and scaled via the Edit GUI.
 */
object LegionCounter {
    private const val ELEMENT_ID = "legion_counter"
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick(client)
        }
    }

    private fun tick(client: MinecraftClient) {
        val player = client.player ?: return
        val world = client.world ?: return

        // Count other *real* players in a 30-block radius.
        // Hypixel Skyblock NPCs typically have "[NPC]" in their display name;
        // we filter those out so the number more closely matches Legion stacks.
        val count = world.players.count { other ->
            other !== player &&
                other.isRealPlayer() &&
                player.squaredDistanceTo(other) <= RADIUS_SQ
        }

        // Update or create the Legion HUD text block. Position and scale are
        // only taken from the defaults the first time; subsequent calls keep
        // the player's edited layout from /soul gui.
        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "Legion",
            lines = listOf("Nearby players: $count"),
            color = 0xFFFFFFFF.toInt(),
            enabled = config.renderCategory.overlaysSubCategory.enableLegionCounter,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.3,
            defaultScale = 1.0f,
        )
    }

    private fun PlayerEntity.isRealPlayer(): Boolean =
        uuid?.let { it.version() == 4 } ?: false
}
