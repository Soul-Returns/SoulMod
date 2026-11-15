package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * The **one** `END_CLIENT_TICK` handler for the whole dragon feature, and the place its per-tick cost
 * is bounded.
 *
 * Three things need a tick: the clock the Legion announcer's delay is measured against, the scan
 * window, and the Legion send itself. Registering three handlers for that would be the legacy shape —
 * the mineshaft lane found five of them and consolidated to one for the same reason.
 *
 * ### What it costs when nothing is happening
 * With the feature off: one boolean read. With it on but no dragon dying: a counter increment and two
 * null checks. The armour-stand walk — the only expensive thing here — happens twice a second and
 * only while a 30 s scan window is open, so it is bounded by how often a dragon actually dies. There
 * is no unconditional walk of `entitiesForRendering()`.
 *
 * The tick counter is still advanced while the feature is off, so a pending send scheduled just
 * before someone flipped the toggle cannot sit forever waiting for a clock that stopped.
 */
object DragonTick {
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick {
                DragonDeathDetector.advanceTick()
                if (!cfg.combat.dragons.enabled) return@EndTick
                DragonLootScanner.tick()
                DragonLegionAnnouncer.tick()
            },
        )
    }
}
