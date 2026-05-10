package com.soulreturns.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.CactusBlock;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.shapes.VoxelShape;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.level.BlockGetter;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

import com.soulreturns.config.SoulConfigHolder;

import static com.soulreturns.config.SoulConfigHolderKt.getCfg;

/**
 * Restores predictable cactus targeting/movement for Hypixel SkyBlock Garden farming.
 *
 * <p>Two behaviour changes when toggled on:
 * <ul>
 *   <li><b>Collision shape</b> → empty. Removes the inset hitbox that stalls movement at
 *       ≥20 BPS lateral speeds, since the 1.8.9 server doesn't enforce cactus collision
 *       the way the modern client predicts it.</li>
 *   <li><b>Outline shape</b> → full cube. Vanilla cactus has a 1/16 inset on each side;
 *       at fixed yaw/pitch with high lateral movement, the crosshair raycast slips through
 *       the gap between offset rows, calling {@code cancelBlockBreaking} every miss.
 *       Accumulated misses cause the server to throttle break packets (the visible ~3s
 *       pause where the attack animation stops). A full-cube outline closes the gap so
 *       the raycast always hits a cactus.</li>
 * </ul>
 *
 * <p>Guard: these methods are invoked during Bootstrap (Blocks static init), before the
 * config is loaded — isConfigReady() prevents an UninitializedPropertyAccessException.
 */
@Mixin(CactusBlock.class)
public abstract class CactusHitboxMixin {

    @ModifyReturnValue(
        method = "getCollisionShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN")
    )
    private VoxelShape removeCollision(VoxelShape original, BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        if (!SoulConfigHolder.isConfigReady()) return original;
        if (!getCfg().general.fixes.oldCactusHitbox()) return original;
        return Shapes.empty();
    }

    @ModifyReturnValue(
        method = "getShape(Lnet/minecraft/world/level/block/state/BlockState;Lnet/minecraft/world/level/BlockGetter;Lnet/minecraft/core/BlockPos;Lnet/minecraft/world/phys/shapes/CollisionContext;)Lnet/minecraft/world/phys/shapes/VoxelShape;",
        at = @At("RETURN")
    )
    private VoxelShape expandOutline(VoxelShape original, BlockState state, BlockGetter world, BlockPos pos, CollisionContext context) {
        if (!SoulConfigHolder.isConfigReady()) return original;
        if (!getCfg().general.fixes.oldCactusHitbox()) return original;
        return Shapes.block();
    }
}
