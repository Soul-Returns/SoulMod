package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.util.MobSpotter
import com.soulreturns.features.dev.DevState
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * The **one** per-tick handler for the whole mineshaft feature, and the only place any of its parts is
 * registered.
 *
 * The legacy mod registered five separate `END_CLIENT_TICK` handlers for this single feature — the
 * visit tracker, the corpse scan and all three alerts — each re-deriving whether it was in a
 * mineshaft. Here the area test happens **once**, and outside a Glacite Mineshaft the entire feature
 * costs one string comparison per tick.
 *
 * Order inside the gate is deliberate: the corpse scan runs first so both corpse alerts see this
 * tick's counts rather than the previous one's, and the tracker's opportunistic capture runs before
 * the alerts so [MineshaftState.isWarpedVisit] is settled before anything can send.
 *
 * The Littlefoot lookup is done **here, once**, and handed to both consumers. It walks every rendered
 * entity, and the tracker (which wants to know whether Littlefoot was in this mineshaft) and the alert
 * (which wants a position to share) would otherwise each run their own walk every tick. It is skipped
 * entirely once the tracker has its answer and the alert is switched off — which is the common case
 * for a player who only wants the telemetry.
 */
object MineshaftTick {
    fun register() {
        MineshaftVisitTracker.register()
        LittlefootAlert.register()
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick() })
        DevState.register("mineshaft") {
            val s = MineshaftState
            linkedMapOf(
                "in_mineshaft" to SkyblockLocation.isInArea(MineshaftState.AREA),
                "entered_at" to s.enteredAt?.toString(),
                "visit_id" to s.visitId,
                "type" to s.mineshaftType,
                "server" to s.serverInstance,
                "source" to s.source,
                "warped_by" to s.warpedBy,
                "summoned_by" to s.summonedBy,
                "profile_id" to s.profileId,
                "profile_name" to s.profileName,
                "littlefoot_found" to s.littlefootFound,
                "littlefoot_pinged" to s.littlefootPinged,
                "sighting" to if (s.sightingAtMs > 0) listOf(s.sightingX, s.sightingY, s.sightingZ) else null,
                "lapis_alert_fired" to s.lapisFired,
                "vanguard_alert_fired" to s.vanguardFired,
                "corpses" to MineshaftCorpses.byType.mapValues { (_, c) -> linkedMapOf("unlooted" to c.unlooted, "looted" to c.looted) },
            )
        }
    }

    /** True while anything still cares: the tracker until it has seen one, the alert for as long as it is on. */
    private fun needsLittlefootScan(): Boolean =
        !MineshaftState.littlefootFound || cfg.mining.mineshaft.littlefootPtme

    /**
     * Whether anything consumes what this tick produces. With every mineshaft toggle off, the scans
     * feed a HUD that is not drawn, alerts that will not fire and a payload that is not sent — so a
     * player who switched the feature off gets a feature that genuinely does nothing, rather than one
     * that merely stays quiet while still walking the tab list and the entity list twenty times a
     * second.
     *
     * The visit lifecycle itself is **not** gated by this: `AreaChanged` still opens and closes a
     * visit and still resolves the source, which costs nothing and keeps `/soul mineshaft` able to say
     * where you are and how you got in.
     */
    private fun anyConsumer(): Boolean {
        val m = cfg.mining.mineshaft
        return m.logVisits || m.corpsesHud || m.littlefootPtme || m.lapisPtme || m.vanguardPtme
    }

    private fun tick() {
        if (!SkyblockLocation.isInArea(MineshaftState.AREA)) return
        if (!anyConsumer()) return
        val now = System.currentTimeMillis()

        MineshaftCorpses.scan()
        val littlefoot = if (needsLittlefootScan()) MobSpotter.findVisible(LittlefootAlert.TARGET_NAME) else null
        MineshaftVisitTracker.tick(littlefoot)
        LittlefootAlert.tick(now, littlefoot)
        LapisCorpseAlert.tick(now)
        VanguardCorpseAlert.tick(now)
    }
}
