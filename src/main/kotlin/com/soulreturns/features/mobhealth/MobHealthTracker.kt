package com.soulreturns.features.mobhealth

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.FormattedText
import net.minecraft.network.chat.Style
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.LivingEntity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.phys.HitResult
import java.util.Optional

/**
 * Picks the mob for [MobHealthHud]: the one under the crosshair (up to [RANGE] blocks, stopped by
 * blocks), held [LOOK_HOLD_MS] after you look away, or the one you last hit, held [HIT_HOLD_MS].
 * The latest of the two wins. Read-only: nothing here acts on an entity.
 *
 * Hypixel puts the name on an invisible marker armor stand spawned right after the mob, so its
 * entity id is the mob's + 1 (true for every tag in the packet log); a stand standing on the mob's
 * column is the fallback, and the mob's own custom name comes first.
 */
object MobHealthTracker {
    private const val RANGE = 32.0
    private const val LOOK_HOLD_MS = 2_000L
    private const val HIT_HOLD_MS = 10_000L

    private var target: Entity? = null
    private var until = 0L

    /** The health shown right now, or null. */
    var current: MobHealth? = null
        private set

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
        AttackEntityCallback.EVENT.register { _, level, _, entity, _ ->
            if (level.isClientSide && cfg.combat.mobHealth.enabled && isMob(entity, null)) hold(entity, HIT_HOLD_MS)
            InteractionResult.PASS
        }
    }

    private fun hold(entity: Entity, ms: Long) {
        target = entity
        until = System.currentTimeMillis() + ms
    }

    private fun clear() {
        target = null
        current = null
    }

    private fun tick(mc: Minecraft) {
        val level = mc.level
        val player = mc.player
        if (!cfg.combat.mobHealth.enabled || level == null || player == null) {
            clear()
            return
        }
        lookedAt(mc, level)?.let { looked -> if (tagOf(level, looked) != null) hold(looked, LOOK_HOLD_MS) }
        val t = target
        if (t == null || t.isRemoved || t.level() !== level || System.currentTimeMillis() > until) {
            clear()
            return
        }
        current = tagOf(level, t)
        if (current == null) target = null
    }

    private fun isMob(entity: Entity, self: Entity?): Boolean =
        entity is LivingEntity && entity !is ArmorStand && entity !== self && !entity.isSpectator

    private fun lookedAt(mc: Minecraft, level: ClientLevel): Entity? {
        val cam = mc.cameraEntity ?: return null
        val self = mc.player
        val eye = cam.getEyePosition(1f)
        val dir = cam.getViewVector(1f)
        var end = eye.add(dir.scale(RANGE))
        val block = level.clip(ClipContext(eye, end, ClipContext.Block.COLLIDER, ClipContext.Fluid.NONE, cam))
        if (block.type != HitResult.Type.MISS) end = block.location
        val box = cam.boundingBox.expandTowards(dir.scale(RANGE)).inflate(1.0)
        return ProjectileUtil.getEntityHitResult(
            cam,
            eye,
            end,
            box,
            { isMob(it, self) && it !== cam && it.isPickable },
            eye.distanceToSqr(end),
        )?.entity
    }

    /** The parsed nametag belonging to [mob], or null. */
    private fun tagOf(level: ClientLevel, mob: Entity): MobHealth? {
        parse(mob.customName)?.let { return it }
        val next = level.getEntity(mob.id + 1)
        if (next is ArmorStand && onColumn(mob, next)) parse(next.customName)?.let { return it }
        var best: MobHealth? = null
        var bestDist = Double.MAX_VALUE
        for (stand in level.getEntitiesOfClass(ArmorStand::class.java, mob.boundingBox.inflate(1.0, 3.5, 1.0))) {
            if (!onColumn(mob, stand)) continue
            val tag = parse(stand.customName) ?: continue
            val dx = stand.x - mob.x
            val dz = stand.z - mob.z
            val d = dx * dx + dz * dz
            if (d < bestDist) {
                bestDist = d
                best = tag
            }
        }
        return best
    }

    private fun onColumn(mob: Entity, stand: Entity): Boolean {
        val dx = stand.x - mob.x
        val dz = stand.z - mob.z
        return dx * dx + dz * dz <= 2.25 && stand.y >= mob.y && stand.y <= mob.y + mob.bbHeight + 3.0
    }

    private fun parse(name: Component?): MobHealth? {
        if (name == null) return null
        val sb = StringBuilder()
        name.visit(
            FormattedText.StyledContentConsumer<Unit> { style, text ->
                if (!style.isObfuscated) sb.append(text)
                Optional.empty()
            },
            Style.EMPTY,
        )
        if (!sb.contains('❤')) return null
        return MobNametag.parse(MobNametag.plain(sb.toString()))
    }
}
