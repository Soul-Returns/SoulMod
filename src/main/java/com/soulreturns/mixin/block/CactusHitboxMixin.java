package com.soulreturns.mixin.block;

import com.llamalad7.mixinextras.injector.ModifyReturnValue;
import net.minecraft.block.BlockState;
import net.minecraft.block.CactusBlock;
import net.minecraft.block.ShapeContext;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.shape.VoxelShape;
import net.minecraft.util.shape.VoxelShapes;
import net.minecraft.world.BlockView;
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
        method = "getCollisionShape(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;",
        at = @At("RETURN")
    )
    private VoxelShape removeCollision(VoxelShape original, BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (!SoulConfigHolder.isConfigReady()) return original;
        if (!getCfg().fixes.oldCactusHitbox()) return original;
        return VoxelShapes.empty();
    }

    @ModifyReturnValue(
        method = "getOutlineShape(Lnet/minecraft/block/BlockState;Lnet/minecraft/world/BlockView;Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/block/ShapeContext;)Lnet/minecraft/util/shape/VoxelShape;",
        at = @At("RETURN")
    )
    private VoxelShape expandOutline(VoxelShape original, BlockState state, BlockView world, BlockPos pos, ShapeContext context) {
        if (!SoulConfigHolder.isConfigReady()) return original;
        if (!getCfg().fixes.oldCactusHitbox()) return original;
        return VoxelShapes.fullCube();
    }
}
