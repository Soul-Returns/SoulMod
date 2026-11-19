package com.soulreturns.util

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.FenceBlock
import net.minecraft.world.level.block.IronBarsBlock
import net.minecraft.world.level.block.TintedGlassBlock
import net.minecraft.world.level.block.TransparentBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Shared helpers for locating named SkyBlock mobs on the client. **All public methods enforce
 * line-of-sight** — [findVisible] with vanilla `LivingEntity.hasLineOfSight` (one eye-to-eye ray
 * blocked by collision shapes), [findAllVisibleIn] with [seesBody] (a clear ray to any of several body
 * points; bars, panes, fences and glass do not block) — that is the Hypixel ToS guard against
 * wallhack-style behaviour. There is intentionally **no** "find without LOS" variant, and adding
 * one would make every caller non-compliant at once, so do not.
 *
 * Hypixel renders most named mobs as `Player`-typed entities at the mob's **feet**, with an
 * invisible armour-stand nametag floating ~2 blocks above. [findVisible] therefore prefers the
 * `Player` variant, so a caller reading `target.x/y/z` gets the mob's actual base position rather
 * than the nametag's eye-level one — which matters for anything that shares coordinates.
 *
 * Ported verbatim in spirit from the legacy 1.21.11 mod; only `Entity.hasLineOfSight` moved to
 * `LivingEntity` in 26.2, which `LocalPlayer` is.
 */
object MobSpotter {
    /**
     * First entity whose [Entity.getName] contains [nameContains] **and** is currently visible to the
     * local player, or null. Resolves player/level from [Minecraft.getInstance].
     */
    fun findVisible(nameContains: String): Entity? {
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return null
        val level = mc.level ?: return null
        return findVisible(level, self, nameContains)
    }

    /**
     * Same as [findVisible] but with an explicit level/self — for callers that already hold them.
     *
     * Line of sight **selects** the candidate rather than filtering one chosen earlier. That
     * distinction only shows up when more than one entity matches the name — Glacite Mineshafts turn
     * out to be able to hold two Littlefoots — where picking one arbitrarily and then testing it
     * returns null whenever *that* one happens to be behind rock, even with another in plain view. The
     * result is a ping that never fires while you are looking straight at the mob.
     *
     * It cannot loosen the ToS guard: every `return` below has already passed [LocalPlayer.hasLineOfSight],
     * so this only ever finds *more of what we were always allowed to see*, never anything we were not.
     */
    fun findVisible(
        level: ClientLevel,
        self: LocalPlayer,
        nameContains: String,
    ): Entity? {
        var fallback: Entity? = null
        for (entity in level.entitiesForRendering()) {
            if (entity === self) continue
            if (!entity.name.string.contains(nameContains)) continue
            if (!self.hasLineOfSight(entity)) continue
            // Prefer the Player-typed rendering: it stands at the mob's feet, where the nametag
            // armour stand floats ~2 blocks up, and callers share these coordinates.
            if (entity is Player) return entity
            if (fallback == null) fallback = entity
        }
        return fallback
    }

    /**
     * The visible entities whose bounding box meets [box] with a non-null [rank] (lowest first), at
     * most [limit] sight tests (on the best-ranked matches, so the work stays bounded). For things that
     * are not named mobs (e.g. corpse armor stands, by their helmet). Line of sight selects among the
     * matches, as in [findVisible], but means what a player means ([seesBody]): some point of the body
     * in clear view, not one ray to the eyes.
     */
    fun findAllVisibleIn(box: AABB, limit: Int, rank: (Entity) -> Double?): List<Entity> {
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return emptyList()
        val level = mc.level ?: return emptyList()
        val matches = ArrayList<Pair<Entity, Double>>()
        for (entity in level.getEntities(self, box)) rank(entity)?.let { matches += entity to it }
        matches.sortBy { it.second }
        val out = ArrayList<Entity>()
        for ((entity, _) in matches.take(limit)) if (seesBody(level, self, entity)) out += entity
        return out
    }

    /**
     * Whether the player sees every one of [points] (a place, not an entity): each has a clear ray from
     * the eyes under the same rules as [seesBody]. False for no points. For "I looked there and nothing
     * stood there" (the Corpse locator's empty spot), which only sight may conclude.
     */
    fun seesAll(points: List<Vec3>): Boolean {
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return false
        val level = mc.level ?: return false
        val eye = self.eyePosition
        return points.isNotEmpty() && points.all { it.distanceTo(eye) <= SIGHT_RANGE && clearRay(level, eye, it) }
    }

    /** Vanilla's sight range for [LocalPlayer.hasLineOfSight]. */
    private const val SIGHT_RANGE = 128.0

    /**
     * Whether one of [bodyPoints] of [entity] has an unobstructed ray from the player's eyes. Strict:
     * no clear ray, not seen. A block obstructs when the ray meets its collision shape, except blocks
     * a player looks through ([seeThrough]): vanilla's single eye-to-eye ray is stopped by the full-face
     * collision shape of iron bars although the corpse behind them is in plain view.
     */
    private fun seesBody(level: ClientLevel, self: LocalPlayer, entity: Entity): Boolean {
        if (entity.level() !== level) return false
        val eye = self.eyePosition
        val b = entity.boundingBox
        if (eye.distanceTo(b.center) > SIGHT_RANGE) return false
        for (p in bodyPoints(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ)) {
            if (clearRay(level, eye, Vec3(p[0], p[1], p[2]))) return true
        }
        return false
    }

    private fun clearRay(level: ClientLevel, from: Vec3, to: Vec3): Boolean =
        BlockGetter.traverseBlocks<Boolean, ClientLevel>(from, to, level, { l, pos ->
            val state = l.getBlockState(pos)
            if (seeThrough(state)) null else state.getCollisionShape(l, pos).clip(from, to, pos)?.let { false }
        }, { true })

    /** Bars, panes, fences and clear glass: the player sees through their gaps or panes. */
    private fun seeThrough(state: BlockState): Boolean {
        val block = state.block
        return block is IronBarsBlock || block is FenceBlock || (block is TransparentBlock && block !is TintedGlassBlock)
    }

    /**
     * Points spread over a body box: feet, chest and head on the center line, and the four sides at
     * chest height, each inset a tenth of the box so it lies on the body rather than its edge.
     */
    fun bodyPoints(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double): List<DoubleArray> {
        val cx = (minX + maxX) / 2
        val cz = (minZ + maxZ) / 2
        val h = maxY - minY
        val ix = (maxX - minX) * 0.1
        val iz = (maxZ - minZ) * 0.1
        val chest = minY + h * 0.5
        return listOf(
            doubleArrayOf(cx, minY + h * 0.9, cz),
            doubleArrayOf(cx, chest, cz),
            doubleArrayOf(cx, minY + h * 0.1, cz),
            doubleArrayOf(minX + ix, chest, cz),
            doubleArrayOf(maxX - ix, chest, cz),
            doubleArrayOf(cx, chest, minZ + iz),
            doubleArrayOf(cx, chest, maxZ - iz),
        )
    }
}
