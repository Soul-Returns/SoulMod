package com.soulreturns.features.dungeons.devices

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.dungeons.terminals.TerminalColors
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.ServerTraffic
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.state.properties.BlockStateProperties
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Lights in the F7/M7 boss room: reads the 5x4 lever grid (x 58..62, y 133..136, z 142) and the lamps
 * behind it (z 143) from the client's world every tick while its chunk is loaded (from anywhere in the boss
 * room, so a grid lit in Storm's phase is told from a completed one by the completion line, see
 * [LightsModel]); [LightsModel] suggests the levers to flip next and decides the player's own lever clicks.
 * Draws the suggestion as vanilla gizmos when the player is near.
 *
 * Anti-cheat: nothing here ever flips a lever. A click goes out exactly as vanilla sends it, at press time,
 * on the block the player aimed at, or, when [LightsModel] says it is wrong, not at all: Fabric's
 * `UseBlockCallback` returning FAIL before `useItemOn` sends its packet, and `Minecraft.startUseItem`
 * then returns without a swing or a use-item packet (same as [SimonSays]).
 */
object Lights {
    private val CENTER = Vec3(60.5, 134.5, 142.5)

    /** Draw only this close. */
    private const val NEAR = 24.0

    private const val LEVER_Z = 142
    private const val LAMP_Z = 143

    /** A blocked click's red cue, as the terminal solver's. */
    private const val FLASH_MS = 180L

    private val logger = SoulLogger("Soul/Devices")
    val model = LightsModel { logger.info("[Lights] $it") }

    private var watching = false
    private var flashTarget = -1
    private var flashAt = 0L

    private val NEXT_EDGE = TerminalColors.TARGET_EDGE
    private const val NEXT_FILL = 0x7015803D

    fun register() {
        com.soulreturns.features.dev.DevState.register("lights") { mapOf("known" to model.isKnown, "allLit" to model.allLit, "done" to model.done, "preFinished" to model.preFinished, "suggested" to model.remaining().map(LightsModel::name)) }
        Events.subscribe<ChatMessage> { if (watching && "completed a device!" in MessageDetector.stripColorCodes(it.raw)) model.completion(System.currentTimeMillis()) }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> stop("joined a server") }
        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, hit -> onUse(player, level, hand, hit) })
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
    }

    private fun on(): Boolean = cfg.dungeons.devices.let { it.enabled && it.lights } && DeviceSolvers.inF7Boss()

    private fun stop(reason: String) {
        if (watching) model.reset(reason)
        watching = false
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level == null || player == null || !on()) return stop("off or not in the F7/M7 boss")
        if (!level.hasChunkAt(BlockPos(60, 134, LEVER_Z))) return stop("device chunk not loaded")
        var levers = 0
        var lamps = 0
        val pos = BlockPos.MutableBlockPos()
        for (i in 0 until LightsModel.SIZE) {
            val x = LightsModel.x(i)
            val y = LightsModel.y(i)
            val lever = level.getBlockState(pos.set(x, y, LEVER_Z))
            val lamp = level.getBlockState(pos.set(x, y, LAMP_Z))
            // Anything but the device's own blocks (a half-loaded wall, another floor's room): not a grid we know.
            if (!lever.`is`(Blocks.LEVER) || !lamp.`is`(Blocks.REDSTONE_LAMP)) return stop("not the Lights grid at r${i / 5 + 1}c${i % 5 + 1}")
            if (lever.getValue(BlockStateProperties.POWERED)) levers = levers or (1 shl i)
            if (lamp.getValue(BlockStateProperties.LIT)) lamps = lamps or (1 shl i)
        }
        model.check(ServerTraffic.lastInboundMs)
        model.observe(levers, lamps, System.currentTimeMillis())
        watching = true
    }

    private fun onUse(player: Player, level: Level, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        // Main hand only: a click the main hand let through may run useItemOn again for the off hand.
        if (!level.isClientSide || hand != InteractionHand.MAIN_HAND || !watching || !on()) return InteractionResult.PASS
        val pos = hit.blockPos
        if (pos.z != LEVER_Z || pos.x !in 58..62 || pos.y !in 133..136 || !level.getBlockState(pos).`is`(Blocks.LEVER)) return InteractionResult.PASS
        // A sneaking click on a block does nothing on Hypixel (measured: no lever of the recordings was clicked while sneaking), so it is no flip and nothing to block.
        if (player.isShiftKeyDown) return InteractionResult.PASS
        val i = LightsModel.index(pos.x, pos.y)
        val now = System.currentTimeMillis()
        model.check(ServerTraffic.lastInboundMs)
        val d = model.leverClick(now, i, DeviceSolvers.blocking(Device.LIGHTS))
        val what = LightsModel.name(i)
        if (d.blocked) {
            logger.info("[Lights] Blocked $what click: ${d.reason}")
            flashTarget = i
            flashAt = now
            return InteractionResult.FAIL
        }
        logger.info("[Lights] Sent $what click: ${d.reason}")
        return InteractionResult.PASS
    }

    private fun render() {
        if (!watching) return
        val player = Minecraft.getInstance().player ?: return
        if (player.position().distanceTo(CENTER) > NEAR) return
        val remaining = model.remaining()
        val any = model.preFinished
        val flash = flashTarget.takeIf { it >= 0 && System.currentTimeMillis() - flashAt < FLASH_MS }
        if (remaining.isEmpty() && !any && flash == null) return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (i in remaining) {
                Gizmos.cuboid(box(level, i), GizmoStyle.strokeAndFill(NEXT_EDGE, 3f, NEXT_FILL)).setAlwaysOnTop()
            }
            if (remaining.isNotEmpty() || any) {
                // Lit in Storm's phase: a touch completes it only from Goldor's phase start (T), so until then it waits.
                Gizmos.billboardText(
                    if (!any) "${remaining.size} left" else if (GoldorPhase.before()) GoldorPhase.WAIT else "flip any lever",
                    Vec3(60.5, 137.3, 141.9),
                    TextGizmo.Style.forColorAndCentered(0xFFFFFFFF.toInt()).withScale(0.6f),
                ).setAlwaysOnTop()
            }
            if (flash != null) {
                val f = 1f - (System.currentTimeMillis() - flashAt).toFloat() / FLASH_MS
                Gizmos.cuboid(box(level, flash), GizmoStyle.strokeAndFill(TerminalColors.flashEdge(f), 2f, TerminalColors.flashFill(f))).setAlwaysOnTop()
            }
        }
    }

    /** The lever's own hit box (a wall lever is a small block against the lamp), a little larger so it reads. */
    private fun box(level: Level, i: Int): AABB {
        val pos = BlockPos(LightsModel.x(i), LightsModel.y(i), LEVER_Z)
        return level.getBlockState(pos).getShape(level, pos).bounds().move(pos).inflate(0.03)
    }
}
