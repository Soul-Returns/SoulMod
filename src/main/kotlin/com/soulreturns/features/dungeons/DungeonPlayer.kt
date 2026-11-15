package com.soulreturns.features.dungeons

import com.soulreturns.features.dungeons.map.MapPlayerPos
import net.minecraft.world.entity.player.Player
import java.util.UUID
import kotlin.math.PI

/** The one dungeon class palette (the leap menu's, from Odin): map, leap menu and every class-colored display read [color]. */
enum class DungeonClass(val displayName: String, val color: Int) {
    ARCHER("Archer", 0xFFFFAA00.toInt()),
    BERSERK("Berserk", 0xFFFF5555.toInt()),
    MAGE("Mage", 0xFF55FFFF.toInt()),
    HEALER("Healer", 0xFFFF55FF.toInt()),
    TANK("Tank", 0xFF55C855.toInt()),
    UNKNOWN("Unknown", 0xFFAAAAAA.toInt());

    companion object {
        fun from(name: String): DungeonClass {
            for (c in entries) if (c.displayName == name) return c
            return UNKNOWN
        }
    }
}

/**
 * One of the up-to-5 players in the run. Position is tracked in map/component units with a
 * one-step lerp buffer: the vanilla map item only refreshes decorations every few ticks, so the
 * HUD interpolates between the last two updates to keep markers moving smoothly.
 */
class DungeonPlayer(val name: String) {
    var uuid: UUID? = null
    var dungeonClass = DungeonClass.UNKNOWN
    var classLevel = 0

    /** Dead = currently a ghost. Tab shows `(DEAD)` and a chat line announces it. */
    var isDead = false

    /** Live entity when the player is in render distance — preferred over map decorations. */
    var entity: Player? = null

    /** Last map-marker position in world coordinates (x, z, yaw) and when it was read; dev bridge only. */
    var mapWorld: DoubleArray? = null
    var mapAt = 0L

    private var pos: MapPlayerPos? = null
    private var posTimeMs = 0L
    private var lastPos: MapPlayerPos? = null
    private var lastPosTimeMs = 0L

    private companion object {
        const val POS_EPS = 0.02 // component units
        const val ROT_EPS = 0.02 // radians
    }

    fun pushPosition(newPos: MapPlayerPos) {
        val now = System.nanoTime() / 1_000_000
        val cur = pos
        if (cur == null) {
            pos = newPos
            posTimeMs = now
            return
        }
        // Dedup identical pushes: we poll the map every tick, but a teammate's decoration only
        // changes when a new map packet arrives (~1s). Recording only *changes* makes lastPos→pos
        // span the real interval, so the lerp is smooth (matches devonian's per-packet cadence).
        if (kotlin.math.abs(cur.x - newPos.x) < POS_EPS &&
            kotlin.math.abs(cur.z - newPos.z) < POS_EPS &&
            kotlin.math.abs(cur.rot - newPos.rot) < ROT_EPS
        ) {
            return
        }
        lastPos = cur
        lastPosTimeMs = posTimeMs
        pos = newPos
        posTimeMs = now
    }

    /** Feed position from the live entity if we have one (called every client tick). */
    fun tickEntity() {
        val ent = entity ?: return
        if (ent.isRemoved || ent.isDeadOrDying) {
            entity = null
            return
        }
        // y-down screen coords: (yaw + 90°), NOT negated — see DungeonRunState.tickSelfPosition.
        pushPosition(MapPlayerPos.fromWorld(ent.x, ent.z, (ent.yRot.toDouble() + 90.0) * PI / 180.0))
    }

    fun lerpedPosition(): MapPlayerPos? {
        val current = pos ?: return null
        val previous = lastPos ?: return current
        val span = (posTimeMs - lastPosTimeMs).toDouble()
        if (span <= 0.0) return current
        val f = ((System.nanoTime() / 1_000_000 - posTimeMs) / span).coerceIn(0.0, 1.0)
        return MapPlayerPos(
            previous.x + (current.x - previous.x) * f,
            previous.z + (current.z - previous.z) * f,
            lerpAngle(previous.rot, current.rot, f),
        )
    }

    private fun lerpAngle(a: Double, b: Double, f: Double): Double {
        var delta = (b - a) % (2 * PI)
        if (delta > PI) delta -= 2 * PI
        if (delta < -PI) delta += 2 * PI
        return a + delta * f
    }

    override fun toString(): String = "DungeonPlayer[$name, $dungeonClass, dead=$isDead]"
}
