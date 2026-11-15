package com.soulreturns.features.dungeons.devices

import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.terminals.TerminalColors
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * Sharpshooter in the F7/M7 boss room: reads the nine target blocks (x 64/66/68, y 126/128/130, z 50) and the
 * pressure plate (63, 127, 35) from the client's world every tick; [SharpshooterModel] follows which cell is the
 * target and which have been hit. Draws the target as a green box and the cells already hit as dim red ones.
 *
 * Highlight only: this never shoots, aims, releases or blocks anything, and gives no "release now" cue; the
 * player's input is not touched at all.
 */
object Sharpshooter {
    private val CENTER = Vec3(66.5, 128.5, 50.5)
    private val PLATE = BlockPos(63, 127, 35)

    /** Watch the device only this close (the plate is 16 blocks from the grid). */
    private const val NEAR = 48.0

    private val logger = SoulLogger("Soul/Devices")
    val model = SharpshooterModel { logger.info("[Sharpshooter] $it") }

    private var watching = false

    private val HIT_EDGE = 0xCCEF4444.toInt()
    private const val HIT_FILL = 0x30EF4444
    private const val TARGET_FILL = 0x7015803D

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> stop("joined a server") }
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
    }

    private fun on(): Boolean = cfg.dungeons.devices.let { it.enabled && it.sharpshooter } && DeviceSolvers.inF7Boss()

    private fun stop(reason: String) {
        if (watching) model.reset(reason)
        watching = false
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level == null || player == null || !on()) return stop("off or not in the F7/M7 boss")
        if (player.position().distanceTo(CENTER) > NEAR) return stop("left the device")
        if (!level.hasChunkAt(BlockPos(SharpshooterModel.X0, SharpshooterModel.Y_TOP, SharpshooterModel.Z)) || !level.hasChunkAt(PLATE)) {
            return stop("device chunk not loaded")
        }
        var emerald = 0
        val pos = BlockPos.MutableBlockPos()
        for (i in 0 until SharpshooterModel.SIZE) {
            val s = level.getBlockState(pos.set(SharpshooterModel.x(i), SharpshooterModel.y(i), SharpshooterModel.Z))
            when {
                s.`is`(Blocks.EMERALD_BLOCK) -> emerald = emerald or (1 shl i)
                // Anything but the device's own blocks (a half-loaded wall, another floor's room): not a grid we know.
                !s.`is`(Blocks.DYED_TERRACOTTA.blue()) -> return stop("not the Sharpshooter grid at ${SharpshooterModel.name(i)}")
            }
        }
        val plate = level.getBlockState(PLATE)
        model.observe(emerald, if (plate.`is`(Blocks.LIGHT_WEIGHTED_PRESSURE_PLATE)) plate.getValue(BlockStateProperties.POWER) else -1)
        watching = true
    }

    private fun render() {
        if (!watching || (model.current == 0 && model.hit == 0)) return
        val mc = Minecraft.getInstance()
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (i in 0 until SharpshooterModel.SIZE) {
                val bit = 1 shl i
                val style = when {
                    model.current and bit != 0 -> GizmoStyle.strokeAndFill(TerminalColors.TARGET_EDGE, 3f, TARGET_FILL)
                    model.hit and bit != 0 -> GizmoStyle.strokeAndFill(HIT_EDGE, 1.5f, HIT_FILL)
                    else -> continue
                }
                val x = SharpshooterModel.x(i)
                val y = SharpshooterModel.y(i)
                Gizmos.cuboid(AABB(x.toDouble(), y.toDouble(), SharpshooterModel.Z.toDouble(), x + 1.0, y + 1.0, SharpshooterModel.Z + 1.0).inflate(0.03), style).setAlwaysOnTop()
            }
        }
    }
}
