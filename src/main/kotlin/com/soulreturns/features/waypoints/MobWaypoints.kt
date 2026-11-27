package com.soulreturns.features.waypoints

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.DevState
import com.soulreturns.features.mining.TargetWaypoint
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MobSpotter
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Waypoints on mobs the player sees, shared with the party (rule://waypoints). One line-of-sight scan
 * per [MobWaypointDef] every [SCAN_TICKS] through [MobSpotter] (the corpse locator's sight rules); what it
 * sees goes into that kind's [MobSightings], which also takes party members' lines; [render] draws them
 * with the mining solvers' [TargetWaypoint].
 *
 * Mobs are on the client through walls: an entity is read (position, state) only after the player was
 * shown to see it, and a remembered spot is dropped only when the player sees it empty. Nothing that
 * leaves this client comes from anything else.
 */
object MobWaypoints {
    /** Twice the corpse scan's rate: these mobs walk, and the waypoint follows them only from scans. */
    const val SCAN_TICKS = 5

    /** Mobs within this many blocks, at most [SCAN_LIMIT] sight tests per scan (the corpse scan's numbers). */
    private const val SCAN_RADIUS = 32.0
    private const val SCAN_LIMIT = 8

    /** A remembered spot is judged empty only this close to the eyes (also the scan's reach). */
    private const val EMPTY_RANGE = 32.0

    /** A shared sighting may be at most this old when it goes out (rule://mineshaft: a stored LOS sighting <= 60 s). */
    const val SHARE_MAX_AGE_MS = 60_000L

    private const val LINE_WIDTH = 3f

    /** Drawn alpha of a waypoint whose mob is not in sight right now (last seen there, or shared). */
    private const val UNSEEN_ALPHA = 0.55f

    private val logger = SoulLogger("Soul/Waypoints")

    /** Every mob kind; a new one is one [MobWaypointDef] here (rule://waypoints). */
    val defs: List<MobWaypointDef> = listOf(Pangolin.def)

    private val stores = defs.associateWith { MobSightings() }
    private val limiter = ShareLimiter()
    private var ticks = 0

    private var sharesSent = 0
    private var sharesReceived = 0
    private var sharesDropped = 0
    private var lastShare: String? = null
    private var lastReceived: String? = null
    private var lastDrop: String? = null
    private var removedEmpty = 0
    private var expired = 0

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { tick() })
        // Party chat is a PLAYER-source line (MessageDetector); the parser is anchored on `Party > `.
        Events.subscribe<ChatMessage> { onChat(it.raw) }
        Events.subscribe<AreaChanged> { reset() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
        DevState.register("mob_waypoints") { state() }
    }

    /** The feature is on and the player is where this mob lives. */
    fun active(def: MobWaypointDef): Boolean =
        def.config().enabled && SkyblockState.onSkyblock && (def.area == null || SkyblockLocation.isInArea(def.area))

    private fun reset() {
        for (s in stores.values) s.clear()
        limiter.forgetSpots()
    }

    private fun tick() {
        val scan = ++ticks % SCAN_TICKS == 0
        val now = System.currentTimeMillis()
        for (def in defs) {
            val store = stores.getValue(def)
            if (!active(def)) {
                if (store.all.isNotEmpty()) store.clear()
                continue
            }
            if (scan) scan(def, store, now)
            expired += store.expire(now).size
            if (def.config().share) share(def, store, now)
        }
    }

    private fun scan(def: MobWaypointDef, store: MobSightings, now: Long) {
        val p = Minecraft.getInstance().player ?: return
        val r = SCAN_RADIUS
        var candidates = 0
        val visible =
            MobSpotter.findAllVisibleIn(AABB(p.x - r, p.y - r, p.z - r, p.x + r, p.y + r, p.z + r), SCAN_LIMIT) {
                if (!it.isAlive || !def.matches(it)) return@findAllVisibleIn null
                candidates++
                it.distanceToSqr(p)
            }
        store.see(visible.map { SeenMob(it.id, it.x, it.y, it.z, def.state(it)) }, now)
        // Beyond the limit some matches were never sight-tested, so "nothing visible there" is not known.
        if (candidates > SCAN_LIMIT) return
        val eye = p.eyePosition
        removedEmpty +=
            store.endScan { s ->
                val points = spotPoints(s)
                points[1].distanceTo(eye) <= EMPTY_RANGE && MobSpotter.seesAll(points)
            }.size
    }

    /** The center line of a remembered mob's box (feet, chest, head): all in clear view = the spot is seen. */
    private fun spotPoints(s: Sighting): List<Vec3> = listOf(Vec3(s.x, s.y + 0.1, s.z), Vec3(s.x, s.y + 0.5, s.z), Vec3(s.x, s.y + 0.9, s.z))

    private fun share(def: MobWaypointDef, store: MobSightings, now: Long) {
        if (!PartyManager.isInParty()) return
        val s = store.nextToShare(now, SHARE_MAX_AGE_MS) ?: return
        if (limiter.covered(s.x, s.y, s.z, now)) {
            store.markAnnounced(s)
            return
        }
        if (!limiter.allows(now)) return
        val player = Minecraft.getInstance().player ?: return
        val line = WaypointShareLine.format(s.x, s.y, s.z, def.name)
        store.markAnnounced(s)
        limiter.onSent(s.x, s.y, s.z, now)
        player.connection.sendCommand("pc $line")
        sharesSent++
        lastShare = line
        logger.info("Sent /pc $line (${def.id}, seen ${(now - s.seenAtMs) / 1000} s ago)")
    }

    private fun onChat(raw: String) {
        val shared = WaypointShareLine.parse(MessageDetector.stripColorCodes(raw)) ?: return
        val def = defs.firstOrNull { it.name.equals(shared.label, ignoreCase = true) } ?: return
        if (shared.sender.equals(Minecraft.getInstance().user.name, ignoreCase = true)) return
        val why =
            when {
                !def.config().enabled -> "off"
                !def.config().share -> "sharing off"
                !active(def) -> "not in ${def.area}"
                else -> null
            }
        if (why != null) {
            sharesDropped++
            lastDrop = "${shared.sender} ${def.id}: $why"
            DebugLogger.logFeatureEvent("Mob waypoints: dropped ${def.id} from ${shared.sender} ($why)")
            return
        }
        val now = System.currentTimeMillis()
        limiter.onHeard(shared.x, shared.y, shared.z, now)
        stores.getValue(def).received(shared.sender, shared.x, shared.y, shared.z, now)
        sharesReceived++
        lastReceived = "${shared.sender}: ${shared.x}, ${shared.y}, ${shared.z} ${def.id}"
        logger.info("Took ${def.id} from ${shared.sender} at ${shared.x}, ${shared.y}, ${shared.z}")
    }

    private fun render() {
        if (stores.values.all { it.all.isEmpty() }) return
        val mc = Minecraft.getInstance()
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (def in defs) {
                if (!active(def)) continue
                val c = def.config()
                for (s in stores.getValue(def).all) {
                    val base = (if (s.state != null && s.inSight) c.stateColor else c.color).flat()
                    val color = if (s.inSight) base else TargetWaypoint.alpha(base, UNSEEN_ALPHA)
                    val label =
                        when {
                            s.sharedBy != null -> "${def.name} (${s.sharedBy})"
                            s.inSight && s.state != null -> "${def.name} (${s.state})"
                            else -> def.name
                        }
                    val box = AABB(s.x - 0.5, s.y, s.z - 0.5, s.x + 0.5, s.y + 1.0, s.z + 0.5)
                    TargetWaypoint.drawWithDistance(box, color, LINE_WIDTH, label, c.line)
                }
            }
        }
    }

    private fun state(): Map<String, Any?> {
        val now = System.currentTimeMillis()
        return mapOf(
            "kinds" to
                defs.associate { def ->
                    def.id to
                        mapOf(
                            "enabled" to def.config().enabled,
                            "share" to def.config().share,
                            "area" to def.area,
                            "active" to active(def),
                            "sightings" to
                                stores.getValue(def).all.map { s ->
                                    mapOf(
                                        "pos" to listOf(round(s.x), round(s.y), round(s.z)),
                                        "state" to s.state,
                                        "in_sight" to s.inSight,
                                        "age_s" to (now - s.seenAtMs) / 1000.0,
                                        "entity" to s.entityId,
                                        "shared_by" to s.sharedBy,
                                        "announced" to s.announced,
                                        "empty_scans" to s.emptyScans,
                                    )
                                },
                        )
                },
            "shares_sent" to sharesSent,
            "shares_in_window" to limiter.recent,
            "last_share" to lastShare,
            "shares_received" to sharesReceived,
            "last_received" to lastReceived,
            "shares_dropped" to sharesDropped,
            "last_drop" to lastDrop,
            "removed_seen_empty" to removedEmpty,
            "expired" to expired,
        )
    }

    private fun round(v: Double): Double = Math.round(v * 100) / 100.0
}
