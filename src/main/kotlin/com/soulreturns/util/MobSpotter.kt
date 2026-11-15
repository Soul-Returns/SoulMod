package com.soulreturns.util

import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.player.Player

/**
 * Shared helpers for locating named SkyBlock mobs on the client. **All public methods enforce
 * line-of-sight** (vanilla `LivingEntity.hasLineOfSight`, an eye-to-eye raycast blocked by solid
 * geometry) — that is the Hypixel ToS guard against wallhack-style behaviour. There is
 * intentionally **no** "find without LOS" variant, and adding one would make every caller
 * non-compliant at once, so do not.
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
}
