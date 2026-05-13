package com.soulreturns.util

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Shared helpers for locating named SkyBlock mobs on the client. **All public methods enforce
 * line-of-sight** (vanilla `Entity.hasLineOfSight`, eye-to-eye raycast blocked by solid
 * geometry) — that's the ToS guard against wallhack-style behavior. There is intentionally no
 * "find without LOS" variant.
 *
 * Hypixel renders most named mobs as `Player`-typed entities at the mob's feet, with an
 * invisible armor-stand nametag floating ~2 blocks above. The pickers below prefer the Player
 * variant so callers reading `target.x/y/z` get the mob's actual base position, not the
 * nametag's eye-level position.
 */
object MobSpotter {
    /**
     * First entity whose [Entity.getName] string contains [nameContains] **and** is currently
     * visible to the local player, or null. Uses [Minecraft.getInstance] for player/level.
     */
    fun findVisible(nameContains: String): Entity? {
        val mc = Minecraft.getInstance()
        val self = mc.player ?: return null
        val level = mc.level ?: return null
        return findVisible(level, self, nameContains)
    }

    /** Same as [findVisible] but with explicit level/self — useful when callers already have them. */
    fun findVisible(
        level: ClientLevel,
        self: LocalPlayer,
        nameContains: String
    ): Entity? {
        val candidate = pickCandidate(level, self, nameContains) ?: return null
        if (!self.hasLineOfSight(candidate)) return null
        return candidate
    }

    private fun pickCandidate(
        level: ClientLevel,
        self: LocalPlayer,
        nameContains: String
    ): Entity? {
        var fallback: Entity? = null
        for (entity in level.entitiesForRendering()) {
            if (entity === self) continue
            if (!entity.name.string.contains(nameContains)) continue
            if (entity is Player) return entity
            if (fallback == null) fallback = entity
        }
        return fallback
    }
}
