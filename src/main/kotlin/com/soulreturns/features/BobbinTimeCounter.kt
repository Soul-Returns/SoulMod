package com.soulreturns.features

import com.soulreturns.config.categories.fishing.BobbinTimeSubCategory
import com.soulreturns.config.config
import com.soulreturns.features.party.PartyManager
import com.soulreturns.gui.lib.GuiLayoutApi
import com.soulreturns.util.RenderUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.entity.player.PlayerEntity
import net.minecraft.entity.projectile.FishingBobberEntity

/**
 * Bobbin Time helper feature.
 *
 * Counts nearby fishing bobbers within a 30 block radius and exposes a
 * separate HUD counter via the GUI layout library. Also triggers a
 * configurable one-shot alert (title + sound) when at least X bobbers are
 * detected, where X comes from the Fishing → Bobbin Time config.
 */
object BobbinTimeCounter {
    private const val ELEMENT_ID = "bobbin_time_counter"
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS

    private var alertTriggered = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick(client)
        }
    }

    private fun tick(client: MinecraftClient) {
        val player = client.player ?: return
        val world = client.world ?: return

        val fishingConfig = config.fishingCategory.bobbinTimeSubCategory

        // Count fishing bobbers in range
        val count = world.entities
            .filterIsInstance<FishingBobberEntity>()
            .count { bobber ->
                bobber.squaredDistanceTo(player) <= RADIUS_SQ
            }

        // Update or create the Bobbin Time HUD text block. Position and scale
        // are only taken from the defaults the first time; subsequent calls
        // keep the player's edited layout from /soul gui.
        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "Bobbin Time",
            lines = listOf("Nearby bobbers: $count"),
            color = 0xFF00FFFF.toInt(), // cyan-ish
            enabled = fishingConfig.enableBobbinTimeCounter,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.35,
            defaultScale = 1.0f,
        )

        handleAlert(player, count, fishingConfig)
    }

    private fun handleAlert(player: PlayerEntity, count: Int, fishingConfig: BobbinTimeSubCategory) {
        if (!fishingConfig.enableBobbinTimeAlert) {
            alertTriggered = false
            return
        }

        val filters = fishingConfig.alertItemNameFilter
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (filters.isNotEmpty()) {
            val inventory = player.inventory
            val hasMatchingItem = (0 until inventory.size()).any { slot ->
                val stack = inventory.getStack(slot)
                if (stack.isEmpty) return@any false

                val name = stack.name.string
                filters.any { filter ->
                    name.contains(filter, ignoreCase = true)
                }
            }

            if (!hasMatchingItem) {
                alertTriggered = false
                return
            }
        }

        // Determine effective threshold: either the static slider value, or
        // (party size - 1) capped at 5 when sync-with-party is enabled.
        val staticThreshold = fishingConfig.alertBobberCount.coerceIn(1, 5)
        val partySize = PartyManager.getPartySize()
        val partyThreshold = if (partySize > 0) {
            (partySize - 1).coerceIn(1, 5)
        } else {
            null
        }
        val threshold = if (fishingConfig.syncBobbinAlertWithParty && partyThreshold != null) {
            partyThreshold
        } else {
            staticThreshold
        }

        // Fire alert once when we reach the desired bobber count; reset once
        // we drop below so it can trigger again on future cycles.
        if (count >= threshold && !alertTriggered) {
            alertTriggered = true
            RenderUtils.showAlert(
                text = "Bobbin Time Ready ($count bobbers)",
                color = 0xFF00FFFF.toInt(),
                textScale = 3.0f,
                durationMs = 4000L,
            )
        }

        if (count < threshold) {
            alertTriggered = false
        }
    }
}
