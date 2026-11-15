package com.soulreturns.features.dungeons.devices

import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.terminals.TerminalColors
import com.soulreturns.util.ServerTraffic
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.fabricmc.fabric.api.event.player.UseEntityCallback
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ItemFrame
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.Items
import net.minecraft.world.level.Level
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.EntityHitResult
import net.minecraft.world.phys.Vec3

/**
 * Arrow Align in the F7/M7 boss room: reads the 5x5 item-frame wall (frames in the blocks x -2, y 120..124,
 * z 75..79; the block behind each, x -3, is a sea lantern exactly while its arrow is at the target) from the
 * client's world every tick; [ArrowAlignModel] knows the layout's targets and decides the player's own clicks
 * on the frames. Draws the clicks each arrow still needs as vanilla gizmos.
 *
 * Anti-cheat: nothing here ever rotates a frame. A click goes out exactly as vanilla sends it, at press time,
 * on the frame the player aimed at, or, when [ArrowAlignModel] says it is an over-rotation, not at all: Fabric's
 * `UseEntityCallback` (the client hook sits in `Minecraft.startUseItem` before `MultiPlayerGameMode.interact`)
 * returning FAIL sends no interact packet (only a result that consumes the action does), no swing, and the
 * hook cancels the rest of `startUseItem`, so the held item is not used either.
 */
object ArrowAlign {
    private val CENTER = Vec3(-1.5, 122.5, 77.5)

    /** Watch the device only this close. */
    private const val NEAR = 24.0

    /** The frame wall (the frames sit in blocks x -2; the walls behind them at x -3). */
    private val AREA = AABB(-3.0, 120.0, 75.0, 0.0, 125.0, 80.0)

    /** A blocked click's red cue, as the terminal solver's. */
    private const val FLASH_MS = 180L

    private const val COMPLETE_WITHIN_MS = 2500L
    private val logger = SoulLogger("Soul/Devices")
    val model = ArrowAlignModel { logger.info("[Arrows] $it") }

    private var watching = false

    /** When the last own click went out: a completion line right after it is this device's. */
    private var lastSentAt = 0L
    private var flashCell = -1
    private var flashAt = 0L

    /** Where each arrow frame's box is, read in the tick (the render pass only draws). */
    private val boxes = HashMap<Int, AABB>()

    fun register() {
        com.soulreturns.features.dev.DevState.register("arrows") { model.snapshot() }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        com.soulreturns.core.events.Events.subscribe<com.soulreturns.data.model.ChatMessage> {
            if (watching && System.currentTimeMillis() - lastSentAt < COMPLETE_WITHIN_MS && com.soulreturns.util.MessageDetector.stripColorCodes(it.raw).contains("completed a device!")) model.complete()
        }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> stop("joined a server") }
        UseEntityCallback.EVENT.register(UseEntityCallback { player, level, hand, entity, hit -> onUse(player, level, hand, entity, hit) })
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
    }

    private fun on(): Boolean = cfg.dungeons.devices.let { it.enabled && it.arrowAlign } && DeviceSolvers.inF7Boss()

    private fun stop(reason: String) {
        if (watching) model.reset(reason)
        watching = false
        boxes.clear()
    }

    private fun cellOf(frame: ItemFrame): Int? {
        val p = frame.blockPosition()
        if (p.x != ArrowAlignModel.FRAME_X || p.y !in 120..124 || p.z !in 75..79) return null
        return ArrowAlignModel.index(p.y, p.z)
    }

    private fun tick() {
        val mc = Minecraft.getInstance()
        val level = mc.level
        val player = mc.player
        if (level == null || player == null || !on()) return stop("off or not in the F7/M7 boss")
        if (player.position().distanceTo(CENTER) > NEAR) return stop("left the device")
        if (!level.hasChunkAt(BlockPos(-2, 122, 77))) return stop("device chunk not loaded")
        val seen = ArrayList<ArrowAlignModel.Frame>()
        boxes.clear()
        val pos = BlockPos.MutableBlockPos()
        for (frame in level.getEntitiesOfClass(ItemFrame::class.java, AREA)) {
            val cell = cellOf(frame) ?: continue
            val item = frame.item
            val kind = when {
                item.`is`(Items.ARROW) -> ArrowAlignModel.ARROW
                item.`is`(Items.WOOL.lime()) -> ArrowAlignModel.START
                item.`is`(Items.WOOL.red()) -> ArrowAlignModel.END
                else -> ArrowAlignModel.OTHER
            }
            val back = level.getBlockState(pos.set(ArrowAlignModel.BACK_X, ArrowAlignModel.y(cell), ArrowAlignModel.z(cell)))
            val lantern = if (back.`is`(Blocks.SEA_LANTERN)) true else if (back.`is`(Blocks.DYED_TERRACOTTA.blue())) false else null
            seen += ArrowAlignModel.Frame(cell, kind, frame.rotation, lantern)
            boxes[cell] = frame.boundingBox
        }
        val now = System.currentTimeMillis()
        model.check(ServerTraffic.lastInboundMs)
        model.hold = GoldorPhase.holding()
        model.observe(now, seen)
        watching = model.isKnown
    }

    private fun onUse(player: Player, level: Level, hand: InteractionHand, entity: Entity, hit: EntityHitResult?): InteractionResult {
        // Main hand only: a click the main hand let through may run interact again for the off hand.
        if (!level.isClientSide || hand != InteractionHand.MAIN_HAND || !watching || entity !is ItemFrame || !on()) return InteractionResult.PASS
        val cell = cellOf(entity) ?: return InteractionResult.PASS
        val now = System.currentTimeMillis()
        model.check(ServerTraffic.lastInboundMs)
        val d = model.click(now, cell, DeviceSolvers.blocking(Device.ARROW_ALIGN), GoldorPhase.holding(), GoldorPhase.model.active)
        val what = ArrowAlignModel.name(cell)
        // So a live log says why a click went through (the override mode and the hold are settings the user flips).
        val ctx = " [hold=${cfg.dungeons.devices.arrowHoldLast} phase=${if (GoldorPhase.model.active) "goldor" else if (GoldorPhase.model.before) "before" else "unknown"} override=${DeviceSolvers.blockOverridden(Device.ARROW_ALIGN)}]"
        if (d.blocked) {
            logger.info("[Arrows] Blocked $what click: ${d.reason}$ctx")
            flashCell = cell
            flashAt = now
            return InteractionResult.FAIL
        }
        lastSentAt = now
        logger.info("[Arrows] Sent $what click: ${d.reason}$ctx")
        return InteractionResult.PASS
    }

    private fun render() {
        if (!watching) return
        val now = System.currentTimeMillis()
        val flash = flashCell.takeIf { it >= 0 && now - flashAt < FLASH_MS }
        val left = model.remaining()
        val full = if (GoldorPhase.model.active) model.fullTurnArrow() else -1
        if (left.isEmpty() && flash == null && full < 0) return
        val mc = Minecraft.getInstance()
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            val last = if (cfg.dungeons.devices.arrowHoldLast || GoldorPhase.showNow()) model.lastArrow() else -1
            val total = if (last >= 0) model.totalClicksLeft() else null
            for (cell in left) {
                var n = model.clicksLeft(cell) ?: continue
                val box = boxes[cell] ?: continue
                val c = box.center
                var text: String? = null
                var tint = color(n)
                if (cell == last) {
                    // The spot left for after Goldor starts: kept one click short, then the label.
                    if (GoldorPhase.showNow()) {
                        if (total == 1) { text = GoldorPhase.NOW; tint = 0xFF4ADE80.toInt() }
                    } else if (model.hold) {
                        if (n > 1) n -= 1
                        else { text = if (total == 1) GoldorPhase.WAIT else "last, after Goldor starts"; tint = 0xFFFACC15.toInt() }
                    }
                }
                Gizmos.billboardText(
                    text ?: n.toString(),
                    // In front of the frame (the wall is on the -x side, the player on the +x side).
                    Vec3(c.x + 0.25, c.y, c.z),
                    TextGizmo.Style.forColorAndCentered(if (text != null) tint else color(n)).withScale(if (text != null) 0.6f else 0.9f),
                ).setAlwaysOnTop()
            }
            // Aligned in Goldor's phase and not complete: the bottom-left-most arrow takes a full turn (unverified that it completes).
            boxes[full]?.let { b ->
                val c = b.center
                Gizmos.billboardText("full turn", Vec3(c.x + 0.25, c.y, c.z), TextGizmo.Style.forColorAndCentered(0xFF4ADE80.toInt()).withScale(0.6f)).setAlwaysOnTop()
            }
            if (flash != null) {
                val box = boxes[flash]
                if (box != null) {
                    val f = 1f - (now - flashAt).toFloat() / FLASH_MS
                    Gizmos.cuboid(box.inflate(0.03), GizmoStyle.strokeAndFill(TerminalColors.flashEdge(f), 2f, TerminalColors.flashFill(f))).setAlwaysOnTop()
                }
            }
        }
    }

    /** One click left reads green, seven red. */
    private fun color(clicks: Int): Int {
        val t = (clicks - 1) / 6f
        val r = (0x4A + (0xEF - 0x4A) * t).toInt()
        val g = (0xDE + (0x44 - 0xDE) * t).toInt()
        val b = (0x80 + (0x44 - 0x80) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }
}
