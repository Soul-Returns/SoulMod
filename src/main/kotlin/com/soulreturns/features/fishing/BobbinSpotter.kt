package com.soulreturns.features.fishing

import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.RenderUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook

/**
 * Counts nearby fishing bobbers and fires the "Bobbin Time" alert when a configurable
 * threshold is crossed. Pure model — exposes [nearbyBobbers] for the HUD to render but
 * never touches the layout system itself.
 */
object BobbinSpotter {
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS
    private const val ALERT_COOLDOWN_MS = 3000L

    /** Most recent bobber count, refreshed each client tick. Read by the HUD. */
    @Volatile var nearbyBobbers: Int = 0
        private set

    private var alertTriggered = false
    private var lastAlertTimeMs: Long = 0L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    private fun tick(client: Minecraft) {
        val player = client.player ?: return
        val world = client.level ?: return

        nearbyBobbers = world.entitiesForRendering()
            .filterIsInstance<FishingHook>()
            .count { bobber -> bobber.distanceToSqr(player) <= RADIUS_SQ }

        handleAlert(player, nearbyBobbers, cfg.fishing.bobbinTime)
    }

    private fun handleAlert(player: Player, count: Int, fishingConfig: SoulConfig.BobbinTime) {
        if (!fishingConfig.enableBobbinTimeAlert()) {
            alertTriggered = false
            return
        }

        val filters = fishingConfig.alertItemNameFilter()
            .split(',')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

        if (filters.isNotEmpty()) {
            val inventory = player.inventory
            val hasMatchingItem = (0 until inventory.containerSize).any { slot ->
                val stack = inventory.getItem(slot)
                if (stack.isEmpty) return@any false
                val name = stack.hoverName.string
                filters.any { filter -> name.contains(filter, ignoreCase = true) }
            }
            if (!hasMatchingItem) {
                alertTriggered = false
                return
            }
        }

        // Effective threshold: static slider, or party-size-minus-self capped at 5
        // when "sync with party" is on.
        val staticThreshold = fishingConfig.alertBobberCount().coerceIn(1, 5)
        val partySize = PartyManager.getPartySize()
        val partyThreshold = if (partySize > 0) (partySize - 1).coerceIn(1, 5) else null
        val threshold = if (fishingConfig.syncBobbinAlertWithParty() && partyThreshold != null) {
            partyThreshold
        } else {
            staticThreshold
        }

        // Fire once when count crosses threshold; cooldown stops re-cast spam.
        val now = System.currentTimeMillis()
        if (count >= threshold && !alertTriggered && now - lastAlertTimeMs >= ALERT_COOLDOWN_MS) {
            alertTriggered = true
            lastAlertTimeMs = now
            RenderUtils.showAlert(
                text = "Bobbin Time Ready ($count bobbers)",
                color = 0xFF00FFFF.toInt(),
                textScale = 3.0f,
                durationMs = 4000L,
            )
        }

        if (count < threshold) alertTriggered = false
    }
}
