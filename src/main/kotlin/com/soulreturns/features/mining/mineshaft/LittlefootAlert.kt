package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.model.AreaChanged
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MobSpotter
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import kotlin.math.roundToInt

/**
 * Two independent triggers that together implement the community "ptme + waypoint" flow for
 * the Littlefoot Mineshaft mini-boss:
 *
 *  1. **`!ptme` on detection** (main toggle) — every tick while in `Area: Mineshaft` we ask
 *     [MobSpotter.findVisible] for a Littlefoot entity in line-of-sight. The first sighting
 *     per Mineshaft visit fires `/pc !ptme Found Littlefoot` — a chat-protocol message that
 *     other party members' mods listen for and react to by running `/party transfer <us>`,
 *     making the finder party leader so they can `/p warp` everyone in.
 *  2. **Waypoint share after warp** (sub toggle, `autoShareLittlefootWaypoint`) — when the
 *     finder runs `/p warp` Hypixel eventually emits a `SkyBlock Party Warp` chat message;
 *     ~1 s later all members are loaded into the same mineshaft instance. Two seconds after
 *     the warp message we send `/pc x: <x>, y: <y>, z: <z>`, which other clients' waypoint
 *     mods (Skytils/SkyHanni/Patcher/etc.) parse to auto-create a waypoint — no user click
 *     required.
 *
 * **LOS gating is mandatory** for both triggers: every position used was confirmed via
 * `Entity.hasLineOfSight` raycast. We never report or claim "Found Littlefoot" based on a
 * through-wall sighting (Hypixel ToS guard).
 *
 * The waypoint phase prefers a fresh LOS check at fire time; if Littlefoot is briefly
 * occluded that exact tick, it falls back to the most recent stored sighting (still
 * LOS-confirmed, just slightly older — capped by [SIGHTING_VALIDITY_MS]).
 *
 * The "already pinged" flag resets on every Mineshaft entry so a player who exits and
 * enters a new mineshaft gets a fresh `!ptme` on the next find.
 */
object LittlefootAlert {
    private val logger = SoulLogger("Soul/LittlefootAlert")

    private const val TARGET_NAME = "Littlefoot"
    private const val AREA_NAME = "Mineshaft"

    /** How long a stored sighting remains usable as a waypoint-phase fallback. */
    private const val SIGHTING_VALIDITY_MS = 60_000L

    /** Delay between the warp chat message and the waypoint coordinates going out. */
    private const val POST_WARP_FIRE_DELAY_MS = 2_000L

    private val PARTY_WARP_PATTERN = Regex("SkyBlock Party Warp", RegexOption.IGNORE_CASE)

    @Volatile private var lastSightingAtMs: Long = 0L

    @Volatile private var lastX: Int = 0

    @Volatile private var lastY: Int = 0

    @Volatile private var lastZ: Int = 0

    /** True once `/pc !ptme` has been sent for the current Mineshaft visit. Reset on area change. */
    @Volatile private var pingedThisVisit: Boolean = false

    /** When > 0, the waypoint share is scheduled for [warpSeenAtMs] + [POST_WARP_FIRE_DELAY_MS]. */
    @Volatile private var warpSeenAtMs: Long = 0L

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ -> tick() }
        )
    }

    @HandleEvent
    fun onAreaChanged(event: AreaChanged) {
        // Fresh mineshaft = fresh ping budget. Also clear when leaving so the flag never lingers.
        pingedThisVisit = false
        lastSightingAtMs = 0L
        DebugLogger.logFeatureEvent("LittlefootAlert: area changed → ${event.current}, ping state reset")
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.mining.mineshaft.enableLittlefootPtme()) return
        if (!cfg.mining.mineshaft.autoShareLittlefootWaypoint()) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        if (!PARTY_WARP_PATTERN.containsMatchIn(stripped)) return
        warpSeenAtMs = System.currentTimeMillis()
        DebugLogger.logFeatureEvent("LittlefootAlert: 'SkyBlock Party Warp' seen — waypoint share in 2s")
    }

    private fun tick() {
        if (!cfg.mining.mineshaft.enableLittlefootPtme()) return
        val now = System.currentTimeMillis()
        updateSightingAndMaybePing(now)
        maybeShareWaypoint(now)
    }

    /**
     * Refresh the stored sighting whenever the player can actually see Littlefoot, and fire
     * the one-shot `!ptme` on the first sighting per mineshaft visit.
     */
    private fun updateSightingAndMaybePing(now: Long) {
        if (!LocationApi.isInArea(AREA_NAME)) return
        val target = MobSpotter.findVisible(TARGET_NAME) ?: return
        lastX = target.x.roundToInt()
        lastY = target.y.roundToInt()
        lastZ = target.z.roundToInt()
        lastSightingAtMs = now

        if (pingedThisVisit) return
        if (MineshaftVisitTracker.isWarpedVisit()) {
            DebugLogger.logFeatureEvent("LittlefootAlert: warped into this mineshaft — skipping !ptme (not our discovery)")
            pingedThisVisit = true
            return
        }
        if (!PartyManager.isInParty()) {
            DebugLogger.logFeatureEvent("LittlefootAlert: Littlefoot in LOS but not in a party — skipping !ptme this visit")
            pingedThisVisit = true
            return
        }
        pingedThisVisit = true
        val player = Minecraft.getInstance().player ?: return
        player.connection.sendCommand("pc !ptme Found Littlefoot")
        logger.info("Sent /pc !ptme Found Littlefoot — first LOS in this mineshaft")
    }

    /**
     * 2 s after `SkyBlock Party Warp`, share the coordinates so other clients' waypoint mods
     * can auto-create a waypoint. Fresh LOS preferred, recent stored sighting as fallback.
     */
    private fun maybeShareWaypoint(now: Long) {
        if (warpSeenAtMs == 0L) return
        if (now - warpSeenAtMs < POST_WARP_FIRE_DELAY_MS) return

        warpSeenAtMs = 0L
        if (!cfg.mining.mineshaft.autoShareLittlefootWaypoint()) return
        if (MineshaftVisitTracker.isWarpedVisit()) {
            DebugLogger.logFeatureEvent("LittlefootAlert: warped into this mineshaft — skipping waypoint share")
            return
        }
        if (!PartyManager.isInParty()) {
            DebugLogger.logFeatureEvent("LittlefootAlert: warp seen but not in a party — skipping waypoint share")
            return
        }

        val fresh = MobSpotter.findVisible(TARGET_NAME)
        val x: Int
        val y: Int
        val z: Int
        val source: String
        if (fresh != null) {
            x = fresh.x.roundToInt()
            y = fresh.y.roundToInt()
            z = fresh.z.roundToInt()
            source = "fresh LOS"
        } else if (lastSightingAtMs != 0L && now - lastSightingAtMs <= SIGHTING_VALIDITY_MS) {
            x = lastX
            y = lastY
            z = lastZ
            source = "last known sighting"
        } else {
            DebugLogger.logFeatureEvent("LittlefootAlert: warp seen but no LOS-confirmed sighting to share")
            return
        }

        val player = Minecraft.getInstance().player ?: return
        player.connection.sendCommand("pc x: $x, y: $y, z: $z")
        logger.info("Sent /pc waypoint for Littlefoot at ($x, $y, $z) via $source")
    }
}
