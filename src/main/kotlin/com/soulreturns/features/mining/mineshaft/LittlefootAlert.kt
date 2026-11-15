package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MobSpotter
import net.minecraft.world.entity.Entity
import kotlin.math.roundToInt

/**
 * The community "ptme + waypoint" flow for the Littlefoot mineshaft mini-boss, as two independent
 * triggers:
 *
 * 1. **`!ptme` on detection** (`littlefootPtme`) — the first time Littlefoot is in **line of sight**
 *    during a visit, send `/pc !ptme Found Littlefoot`. Other members' mods react by running
 *    `/party transfer <us>`, which makes the finder leader so they can `/p warp` everyone in.
 * 2. **Waypoint share after the warp** (`littlefootWaypoint`, a sub-toggle) — Hypixel prints
 *    `SkyBlock Party Warp` when the leader warps the party; the members finish loading about a second
 *    later. Two seconds after that line we send `/pc x: <x>, y: <y>, z: <z>`, the exact format
 *    Skytils / SkyHanni / Patcher parse into a waypoint with no click from anyone.
 *
 * ### Line of sight is the ToS guard, in both phases
 * Every coordinate that leaves this client was confirmed by [MobSpotter], which raycasts. The
 * waypoint phase prefers a **fresh** LOS fix at fire time and falls back to the most recent stored
 * sighting — which was itself LOS-confirmed, just up to [SIGHTING_VALIDITY_MS] old. That fallback is
 * compliant precisely because it is a memory of something we legitimately saw; a plain entity scan at
 * fire time would not be, however convenient. There is deliberately no such variant to reach for.
 *
 * Both phases also pass through [MineshaftAlertGate], so neither can announce a find in a mineshaft
 * we were warped into, and neither can send into a party that does not exist.
 */
object LittlefootAlert {
    internal const val TARGET_NAME = "Littlefoot"

    /** How long a stored LOS-confirmed sighting stays usable as the waypoint fallback. */
    private const val SIGHTING_VALIDITY_MS = 60_000L

    /** Gap between the warp line and the coordinates going out, so members have finished loading. */
    private const val POST_WARP_FIRE_DELAY_MS = 2_000L

    private val PARTY_WARP_PATTERN = Regex("SkyBlock Party Warp", RegexOption.IGNORE_CASE)

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.mining.mineshaft.littlefootPtme || !cfg.mining.mineshaft.littlefootWaypoint) return
        if (!PARTY_WARP_PATTERN.containsMatchIn(MessageDetector.stripColorCodes(event.raw))) return
        MineshaftState.warpSeenAtMs = System.currentTimeMillis()
        DebugLogger.logFeatureEvent("Mineshaft: 'SkyBlock Party Warp' seen — waypoint share in 2s")
    }

    /**
     * Driven by [MineshaftTick], so only inside the mineshaft area.
     *
     * @param littlefoot this tick's line-of-sight-confirmed Littlefoot, or null. [MineshaftTick] owns
     *   the single [MobSpotter] walk per tick; this alert never scans on its own, which is also what
     *   guarantees the waypoint share can only ever use a position we were allowed to see.
     */
    fun tick(nowMs: Long, littlefoot: Entity?) {
        if (!cfg.mining.mineshaft.littlefootPtme) return
        updateSightingAndMaybePing(nowMs, littlefoot)
        maybeShareWaypoint(nowMs, littlefoot)
    }

    /** Refresh the stored sighting whenever Littlefoot is genuinely visible; ping on the first one. */
    private fun updateSightingAndMaybePing(nowMs: Long, littlefoot: Entity?) {
        val target = littlefoot ?: return
        MineshaftState.recordSighting(nowMs, target.x.roundToInt(), target.y.roundToInt(), target.z.roundToInt())

        if (MineshaftState.littlefootPinged) return
        // Set before sending: whether the gate lets it through or not, this visit has had its one shot.
        MineshaftState.littlefootPinged = true
        MineshaftAlertGate.send("!ptme Found Littlefoot", "Littlefoot in line of sight")
    }

    /** Two seconds after the warp line, share coordinates we actually saw — or nothing at all. */
    private fun maybeShareWaypoint(nowMs: Long, littlefoot: Entity?) {
        val warpSeen = MineshaftState.warpSeenAtMs
        if (warpSeen == 0L || nowMs - warpSeen < POST_WARP_FIRE_DELAY_MS) return
        MineshaftState.warpSeenAtMs = 0L

        if (!cfg.mining.mineshaft.littlefootWaypoint) return

        val fresh = littlefoot
        val x: Int
        val y: Int
        val z: Int
        val via: String
        if (fresh != null) {
            x = fresh.x.roundToInt()
            y = fresh.y.roundToInt()
            z = fresh.z.roundToInt()
            via = "fresh line of sight"
        } else if (MineshaftState.sightingAtMs != 0L && nowMs - MineshaftState.sightingAtMs <= SIGHTING_VALIDITY_MS) {
            x = MineshaftState.sightingX
            y = MineshaftState.sightingY
            z = MineshaftState.sightingZ
            via = "last confirmed sighting"
        } else {
            // Nothing we are allowed to share. Sending an unverified position is exactly what the LOS
            // rule forbids, so the correct behaviour here is silence.
            DebugLogger.logFeatureEvent("Mineshaft: warp seen but no LOS-confirmed Littlefoot sighting to share")
            return
        }

        MineshaftAlertGate.send("x: $x, y: $y, z: $z", "Littlefoot waypoint via $via", chime = false)
    }
}
