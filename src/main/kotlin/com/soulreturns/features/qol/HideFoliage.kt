package com.soulreturns.features.qol

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.LocationApi
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EntitySelector
import net.minecraft.world.entity.projectile.ProjectileUtil
import net.minecraft.world.level.ClipContext
import net.minecraft.world.level.block.Block
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.BushBlock
import net.minecraft.world.level.block.DoublePlantBlock
import net.minecraft.world.level.block.DryVegetationBlock
import net.minecraft.world.level.block.FlowerBedBlock
import net.minecraft.world.level.block.FlowerBlock
import net.minecraft.world.level.block.LeafLitterBlock
import net.minecraft.world.level.block.PitcherCropBlock
import net.minecraft.world.level.block.TallGrassBlock
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.HitResult
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Hide-foliage: renders decorative plants invisible and silent, without touching any block
 * interaction (anticheat-safe — you can never target, break, or place through hidden foliage;
 * the block raycast stays 100% vanilla). Interception points:
 * - `BlockStateBaseMixin.getRenderShape` → `INVISIBLE`, so the chunk mesher skips them;
 * - `LevelEventHandlerMixin` cancels level event 2001 (block destroy) for them, muting the break
 *   sound and skipping the break particles (the client doesn't predict these locally — they all
 *   arrive as the server's 2001 echo);
 * - `LocalPlayerMixin` extends the crosshair's ENTITY pick through hidden foliage (vanilla caps
 *   the entity sweep at the block hit, so mobs inside grass are normally untargetable). Only the
 *   entity result changes — if no entity is behind, the vanilla foliage block hit stands;
 * - `LevelExtractorMixin` drops the hover outline (selection lines) on hidden foliage — pure
 *   visual, the block stays targetable/breakable.
 *
 * Disabled on the private island ("dynamic") and the Garden ("garden"), where foliage is a
 * resource the player farms — verify modes via `/soul dev location`.
 *
 * Chunk geometry is only re-meshed on demand, so flipping the toggle (or crossing a gated-location
 * boundary) must trigger a full rebuild — the same `levelExtractor.allChanged()` that F3+A runs
 * (it no-ops when no level is loaded).
 */
object HideFoliage {
    /** Max hidden-foliage blocks the entity-pick raycast steps past (interaction range is ~5). */
    private const val MAX_PICK_STEPS = 16

    private var lastGated = false

    fun register() {
        // Re-mesh when crossing into/out of a gated location while the toggle is on. Reads the
        // event's own fields (not LocationApi) so it doesn't depend on handler order.
        Events.subscribe<HypixelModApi.LocationChangeEvent> { event ->
            val gated = event.serverType == "SKYBLOCK" && (event.mode == "dynamic" || event.mode == "garden")
            if (gated != lastGated) {
                lastGated = gated
                if (cfg.general.hideFoliage) Minecraft.getInstance().levelExtractor.allChanged()
            }
        }
    }

    /** The toggle is on AND we're not somewhere foliage matters (Garden / private island). */
    @JvmStatic
    fun isActive(): Boolean {
        if (!cfg.general.hideFoliage) return false
        if (LocationApi.inSkyblock && (LocationApi.mode == "dynamic" || LocationApi.mode == "garden")) return false
        return true
    }

    /**
     * Foliage = decorative vegetation only. Everything below extends `VegetationBlock`, but so do
     * crops/saplings/berry bushes — those must stay visible, hence an explicit class list instead
     * of a blanket `VegetationBlock` check.
     */
    @JvmStatic
    fun shouldHide(block: Block): Boolean {
        if (!isActive()) return false
        if (block is PitcherCropBlock) return false // a crop, despite extending DoublePlantBlock
        return block is TallGrassBlock || // short grass, fern
            block is DoublePlantBlock || // tall grass, large fern, sunflower/lilac/rose bush/peony
            block is FlowerBlock || // all small flowers (azure bluet, poppy, …)
            block is FlowerBedBlock || // pink petals, wildflowers
            block is DryVegetationBlock || // dead bush, short/tall dry grass
            block is BushBlock ||
            block is LeafLitterBlock ||
            // Water plants: exactly kelp + seagrass (each spans two block ids: tip/stalk and
            // short/tall) — deliberately NOT lily pads, sea pickles, or corals.
            block === Blocks.KELP ||
            block === Blocks.KELP_PLANT ||
            block === Blocks.SEAGRASS ||
            block === Blocks.TALL_SEAGRASS
    }

    fun setEnabled(v: Boolean) {
        if (cfg.general.hideFoliage == v) return
        ConfigManager.update { copy(general = general.copy(hideFoliage = v)) }
        Minecraft.getInstance().levelExtractor.allChanged()
    }

    /**
     * Entity sweep for `LocalPlayer.pick` when the vanilla crosshair landed on hidden foliage:
     * re-runs the block clip but steps past hidden-foliage hits to find the true cap (the first
     * visible block), then sweeps entities up to that cap exactly like vanilla does. Returns null
     * when no entity is in reach — the caller then keeps the vanilla (foliage) hit, so block
     * interactions are never redirected.
     */
    @JvmStatic
    fun pickEntityThroughFoliage(camera: Entity, blockRange: Double, entityRange: Double, partialTicks: Float): EntityHitResult? {
        val level = camera.level()
        val from = camera.getEyePosition(partialTicks)
        val dir = camera.getViewVector(partialTicks)
        val maxDistance = max(blockRange, entityRange)

        var capSq = maxDistance * maxDistance
        var start = from
        for (i in 0 until MAX_PICK_STEPS) {
            val remaining = maxDistance - start.distanceTo(from)
            if (remaining <= 0.0) break
            val hit = level.clip(
                ClipContext(start, start.add(dir.scale(remaining)), ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, camera),
            )
            if (hit.type != HitResult.Type.BLOCK) break
            if (!shouldHide(level.getBlockState(hit.blockPos).block)) {
                capSq = hit.location.distanceToSqr(from)
                break
            }
            // Nudge just inside the foliage's shape so the next clip continues behind it.
            start = hit.location.add(dir.scale(0.05))
        }

        val sweepDistance = sqrt(capSq)
        val to = from.add(dir.scale(sweepDistance))
        val box = camera.boundingBox.expandTowards(dir.scale(sweepDistance)).inflate(1.0, 1.0, 1.0)
        val entityHit =
            ProjectileUtil.getEntityHitResult(camera, from, to, box, EntitySelector.CAN_BE_PICKED, capSq)
                ?: return null
        // Vanilla's filterHitResult: entity hits are only valid within the entity range.
        return if (entityHit.location.closerThan(from, entityRange)) entityHit else null
    }
}
