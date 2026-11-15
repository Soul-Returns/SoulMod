package com.soulreturns.features.fishing

import com.soulreturns.config.cfg
import com.soulreturns.sound.SoulSounds
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.util.soulChat
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.projectile.FishingHook

/**
 * Counts the fishing bobbers around you and says so once the pond gets crowded — "Bobbin Time", the
 * moment a group has enough rods in the water for the shared spawn to be worth staying for.
 *
 * ### It announces to you, not to the party
 * The legacy version drew a full-screen banner through a render helper and a GUI mixin, neither of
 * which exists in this baseline and neither of which is missed. This says it in chat, client-side.
 * Nothing is sent to anyone.
 *
 * ### The count is a state, the alert is an edge
 * [nearbyBobbers] is refreshed continuously and drawn on the fishing HUD; the message fires once
 * when the count *crosses* the threshold and re-arms only after it drops back under, with a short
 * cooldown on top so a bobber flickering across the boundary cannot spam.
 *
 * Two knobs the legacy version had are deliberately gone: an item-name filter and a "sync the
 * threshold with party size" mode. Both were configuration standing in for a decision — the
 * threshold slider says the same thing in one number.
 */
object BobbinSpotter {
    private const val RADIUS_SQ = 30.0 * 30.0
    private const val ALERT_COOLDOWN_MS = 3_000L

    /** Half a second. A crowd does not form fast enough to need per-tick precision. */
    private const val COUNT_INTERVAL_TICKS = 10

    /** Most recent count, refreshed while the alert is on. Read by the HUD. */
    @Volatile
    var nearbyBobbers: Int = 0
        private set

    private var ticksSinceCount = 0
    private var armed = true
    private var lastAlertAt = 0L

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
    }

    private fun tick(client: Minecraft) {
        // Off, or not in SkyBlock: the feature costs two boolean reads and nothing else.
        if (!cfg.fishing.bobbinAlert || !SkyblockState.onSkyblock) {
            if (nearbyBobbers != 0) nearbyBobbers = 0
            armed = true
            return
        }
        if (++ticksSinceCount < COUNT_INTERVAL_TICKS) return
        ticksSinceCount = 0

        val player = client.player ?: return
        val level = client.level ?: return

        val count = level.entitiesForRendering()
            .filterIsInstance<FishingHook>()
            .count { it.distanceToSqr(player) <= RADIUS_SQ }
        nearbyBobbers = count

        val threshold = cfg.fishing.bobbinThreshold.coerceAtLeast(1)
        if (count < threshold) {
            armed = true
            return
        }
        if (!armed) return

        val now = System.currentTimeMillis()
        if (now - lastAlertAt < ALERT_COOLDOWN_MS) return
        armed = false
        lastAlertAt = now
        soulChat("§bBobbin Time §7— §f$count§7 bobbers in the water")
        cfg.fishing.bobbinAlertSound?.let { SoulSounds.play(it) }
    }
}
