package com.soulreturns.features.dungeons.devices

import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.terminals.TerminalColors
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
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.player.Player
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.BlockHitResult
import net.minecraft.world.phys.Vec3

/**
 * Simon Says in the F7/M7 boss room: reads the lamp wall (x 111) and the button grid (x 110) from
 * the client's world every tick, the pling / error sounds near it, and the player's own clicks on
 * the start button and the grid ([SimonSaysModel] decides). Draws the next button (and the one after
 * it) as vanilla gizmos.
 *
 * Anti-cheat: nothing here ever clicks. A click goes out exactly as vanilla sends it, at press time,
 * on the block the player aimed at, or, when [SimonSaysModel] says it is wrong, not at all: Fabric's
 * `UseBlockCallback` returning FAIL before `useItemOn` sends its packet, and `Minecraft.startUseItem`
 * then returns without a swing or a use-item packet.
 */
object SimonSays {
    /** Plings and error sounds farther than this from the grid are someone else's (the closest terminal is 14 blocks away). */
    const val SOUND_RANGE = 6.0

    /** Watch the device only this close (the wall is in render distance far beyond). */
    private const val NEAR = 24.0

    private val START = BlockPos(110, 121, 91)
    private val CENTER = Vec3(110.5, 121.5, 93.5)

    /** A blocked click's red cue, as the terminal solver's. */
    private const val FLASH_MS = 180L

    private val logger = SoulLogger("Soul/Devices")
    val model = SimonSaysModel { logger.info("[SS] $it") }

    private var watching = false
    private var flashTarget: BlockPos? = null
    private var flashAt = 0L

    private val NEXT_EDGE = TerminalColors.TARGET_EDGE
    private const val NEXT_FILL = 0x7015803D
    private const val AFTER_EDGE = 0x8C4ADE80.toInt()
    private const val AFTER_FILL = 0x1C4ADE80
    private const val CANDIDATE_EDGE = 0xB0FFFFFF.toInt()
    private const val CANDIDATE_FILL = 0x18FFFFFF

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> stop("joined a server") }
        UseBlockCallback.EVENT.register(UseBlockCallback { player, level, hand, hit -> onUse(player, level, hand, hit) })
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
    }

    private fun on(): Boolean = cfg.dungeons.devices.let { it.enabled && it.simonSays } && DeviceSolvers.inF7Boss()

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
        if (!level.hasChunkAt(START)) return stop("device chunk not loaded")
        var lamps = 0
        var buttons = 0
        val pos = BlockPos.MutableBlockPos()
        for (i in 0 until SimonSaysModel.SIZE) {
            val y = SimonSaysModel.y(i)
            val z = SimonSaysModel.z(i)
            if (level.getBlockState(pos.set(111, y, z)).`is`(Blocks.SEA_LANTERN)) lamps = lamps or (1 shl i)
            if (level.getBlockState(pos.set(110, y, z)).`is`(Blocks.STONE_BUTTON)) buttons = buttons or (1 shl i)
        }
        val now = System.currentTimeMillis()
        model.check(now, ServerTraffic.lastInboundMs)
        model.observe(now, lamps, buttons)
        watching = true
    }

    /** `ClientPacketListenerMixin.handleSoundEvent` HEAD, both passes; only the client-thread pass counts. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!watching || !Minecraft.getInstance().isSameThread) return
        if (packet.volume != 8f) return
        val path = packet.sound.value().location().path
        val pling = path == "block.note_block.pling"
        val error = path == "entity.enderman.teleport" && packet.pitch == 0f
        if (!pling && !error) return
        if (Vec3(packet.x, packet.y, packet.z).distanceTo(CENTER) > SOUND_RANGE) return
        val now = System.currentTimeMillis()
        if (pling) model.pling(now) else model.error(now)
    }

    private fun onUse(player: Player, level: Level, hand: InteractionHand, hit: BlockHitResult): InteractionResult {
        // Main hand only: a click the main hand let through may run useItemOn again for the off hand.
        if (!level.isClientSide || hand != InteractionHand.MAIN_HAND || !watching || !on()) return InteractionResult.PASS
        val pos = hit.blockPos
        val start = pos == START
        val grid = pos.x == 110 && pos.y in 120..123 && pos.z in 92..95 && level.getBlockState(pos).`is`(Blocks.STONE_BUTTON)
        if (!start && !grid) return InteractionResult.PASS
        val now = System.currentTimeMillis()
        model.check(now, ServerTraffic.lastInboundMs)
        val blockOn = DeviceSolvers.blocking(Device.SIMON_SAYS)
        val d = if (start) model.startPress(now, blockOn) else model.buttonPress(now, SimonSaysModel.index(pos.y, pos.z), blockOn)
        val what = if (start) "start" else SimonSaysModel.name(SimonSaysModel.index(pos.y, pos.z))
        if (d.blocked) {
            logger.info("[SS] Blocked $what press: ${d.reason}")
            flashTarget = pos.immutable()
            flashAt = now
            return InteractionResult.FAIL
        }
        logger.info("[SS] Sent $what press: ${d.reason}")
        return InteractionResult.PASS
    }

    private fun render() {
        if (!watching) return
        val now = System.currentTimeMillis()
        val showAfter = cfg.dungeons.devices.showAfterNext
        // The lamp wall behind the buttons, from the moment the lamp lit (before any button), through the buttons' phase.
        val wall = model.wall(now, showAfter)
        // Buttons only where one exists in the world now (the skip's last button comes after the solution is known).
        val next = model.next()?.takeIf { model.buttonUp(it) }
        val after = if (showAfter) model.after()?.takeIf { model.buttonUp(it) } else null
        val flash = flashTarget?.takeIf { now - flashAt < FLASH_MS }
        if (wall.isEmpty() && next == null && after == null && flash == null) return
        val mc = Minecraft.getInstance()
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (c in wall) {
                val (edge, fill) = when {
                    !c.certain -> CANDIDATE_EDGE to CANDIDATE_FILL
                    c.next -> NEXT_EDGE to NEXT_FILL
                    else -> AFTER_EDGE to AFTER_FILL
                }
                Gizmos.cuboid(wallBox(c.index), GizmoStyle.strokeAndFill(edge, if (c.next) 3f else 1.5f, fill)).setAlwaysOnTop()
                Gizmos.billboardText(
                    c.order.toString(),
                    Vec3(110.9, SimonSaysModel.y(c.index) + 0.82, SimonSaysModel.z(c.index) + 0.5),
                    TextGizmo.Style.forColorAndCentered(if (c.next) 0xFFFFFFFF.toInt() else 0xCCFFFFFF.toInt()).withScale(if (c.next) 0.5f else 0.4f),
                ).setAlwaysOnTop()
            }
            if (after != null) Gizmos.cuboid(box(after), GizmoStyle.strokeAndFill(AFTER_EDGE, 1.5f, AFTER_FILL)).setAlwaysOnTop()
            if (next != null) {
                Gizmos.cuboid(box(next), GizmoStyle.strokeAndFill(NEXT_EDGE, 3f, NEXT_FILL)).setAlwaysOnTop()
                val left = model.remaining()
                Gizmos.billboardText(
                    left.toString(),
                    // On the button's face, over its box.
                    Vec3(110.8, SimonSaysModel.y(next) + 0.5, SimonSaysModel.z(next) + 0.5),
                    TextGizmo.Style.forColorAndCentered(0xFFFFFFFF.toInt()).withScale(0.6f),
                ).setAlwaysOnTop()
            }
            if (flash != null) {
                val f = 1f - (now - flashAt).toFloat() / FLASH_MS
                val b = if (flash == START) buttonBox(START.y, START.z) else box(SimonSaysModel.index(flash.y, flash.z))
                Gizmos.cuboid(b, GizmoStyle.strokeAndFill(TerminalColors.flashEdge(f), 2f, TerminalColors.flashFill(f))).setAlwaysOnTop()
            }
        }
    }

    /** The lamp cell on the x 111 wall: a thin plate just in front of its face. */
    private fun wallBox(i: Int): AABB {
        val y = SimonSaysModel.y(i)
        val z = SimonSaysModel.z(i)
        return AABB(110.95, y + 0.05, z + 0.05, 111.0, y + 0.95, z + 0.95)
    }

    private fun box(i: Int): AABB = buttonBox(SimonSaysModel.y(i), SimonSaysModel.z(i))

    /** A west-facing stone button on the x 111 wall: 2/16 deep, 6/16 wide, 4/16 high, a little larger so it reads. */
    private fun buttonBox(y: Int, z: Int): AABB = AABB(110.0 + 13.0 / 16, y + 5.0 / 16, z + 4.0 / 16, 111.0, y + 11.0 / 16, z + 12.0 / 16)
}
