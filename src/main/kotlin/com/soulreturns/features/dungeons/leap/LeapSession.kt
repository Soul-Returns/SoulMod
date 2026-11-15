package com.soulreturns.features.dungeons.leap

import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.ResolvableProfile

/**
 * One open Spirit Leap menu: reads its slots into a [LeapGate] and sends the one click a press asks for.
 * The real menu stays the player's open container the whole time; this only reads it.
 */
class LeapSession(val menu: ChestMenu) {
    companion object {
        val logger = SoulLogger("Soul/Leap")

        fun item(stack: ItemStack): LeapMenuReader.Item? {
            if (stack.isEmpty) return null
            val lore = stack.get(DataComponents.LORE)?.lines()?.firstOrNull()?.string
            return LeapMenuReader.Item(
                BuiltInRegistries.ITEM.getKey(stack.item).path,
                MessageDetector.stripColorCodes(stack.hoverName.string).trim(),
                lore?.let { MessageDetector.stripColorCodes(it).trim() },
            )
        }
    }

    /** What a press did. */
    enum class Outcome { SENT, BLOCKED_DEAD, NOT_IN_MENU, NOT_YET, DONE }

    private val created = System.currentTimeMillis()

    /** The zero of every timing: the open-screen packet's arrival (or this session, if that stamp is not this menu's). */
    val openAt = LeapTiming.openPacketAt.let { if (created - it in 0..1000) it else created }

    val gate = LeapGate(openAt)

    val state: Int get() = gate.state

    val heads: List<LeapMenuReader.Head> get() = gate.heads

    /** The skin of each arrived head, by slot. */
    var profiles: Map<Int, ResolvableProfile> = emptyMap()
        private set

    private val seen = arrayOfNulls<ItemStack>(LeapMenuReader.SIZE)
    private var loggedMenu = false

    /** The menu is still the player's open container and recognition has not failed. */
    val live: Boolean get() = gate.state != LeapGate.FAILED && Minecraft.getInstance().player?.containerMenu === menu

    /** Read the menu again (cheap when nothing changed); any slot we do not know hands the menu back to vanilla. */
    fun poll(now: Long = System.currentTimeMillis()) {
        if (gate.state == LeapGate.FAILED || gate.state == LeapGate.SENT) return
        if (Minecraft.getInstance().player?.containerMenu !== menu) return
        var changed = false
        for (i in 0 until LeapMenuReader.SIZE) {
            val cur = menu.getSlot(i).item
            if (cur !== seen[i]) {
                seen[i] = cur
                changed = true
            }
        }
        // Pending still needs the clock (load timeout) without any change.
        if (!changed && gate.state == LeapGate.ACTIVE) return
        val before = gate.heads
        gate.update(LeapMenuReader.read(seen.map { item(it ?: ItemStack.EMPTY) }), now)
        if (gate.state == LeapGate.FAILED) {
            logger.info("Leap menu left to the vanilla screen: {}", gate.why)
            return
        }
        if (gate.heads !== before) {
            profiles = gate.heads.mapNotNull { h -> seen[h.slot]?.get(DataComponents.PROFILE)?.let { h.slot to it } }.toMap()
        }
        if (gate.state == LeapGate.ACTIVE && !loggedMenu) {
            loggedMenu = true
            logger.info("Leap menu: {}", gate.heads.joinToString { "${it.name}@${it.slot}${if (it.dead) " dead" else ""}" })
        }
    }

    /**
     * One physical press that chose [name]: exactly one container click, now, on the slot whose head is [name]
     * as the menu shows it at this moment (vanilla's left click: PICKUP, button 0), or nothing. Never queued,
     * delayed, repeated or retargeted; after one sent leap the menu sends nothing more.
     */
    fun leap(name: String, blockDead: Boolean, how: String): Outcome {
        poll()
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return Outcome.DONE
        val gameMode = mc.gameMode ?: return Outcome.DONE
        if (player.containerMenu !== menu) return Outcome.DONE
        val at = System.currentTimeMillis() - openAt
        return when (val p = gate.press(name, blockDead)) {
            is LeapGate.Press.Send -> {
                gameMode.handleContainerInput(menu.containerId, p.slot, 0, ContainerInput.PICKUP, player)
                gate.sent()
                logger.info("Sent {} → slot {} ({}), +{} ms", how, p.slot, name, at)
                Outcome.SENT
            }
            LeapGate.Press.NotYet -> Outcome.NOT_YET.also { logger.info("Nothing sent ({}): {}'s head has not arrived yet, +{} ms", how, name, at) }
            LeapGate.Press.Dead -> Outcome.BLOCKED_DEAD.also { logger.info("Blocked {} on {}: dead, +{} ms", how, name, at) }
            LeapGate.Press.NotInMenu -> Outcome.NOT_IN_MENU.also { logger.info("Nothing sent ({}): {} is not in the menu", how, name) }
            LeapGate.Press.Done -> Outcome.DONE
        }
    }

    // ---------------------------------------------------------------- timing (debug log)

    /** When [LeapScreen] replaced the vanilla screen, and how many frames vanilla drew before that. */
    var screenAt = -1L
    var vanillaFrames = 0
    private var firstFrameAt = -1L
    private var lastFrameAt = -1L
    private var frames = 0
    private var slowestFrame = 0L
    private var timingLogged = false
    private var tabFaces = ""

    /** One [LeapScreen] frame at [now] with [tiles] drawn, [ready] of them clickable and [tabSkins] with the tab list's skin. */
    fun frame(now: Long, tiles: Int, ready: Int, tabSkins: Int) {
        if (firstFrameAt < 0) firstFrameAt = now
        if (lastFrameAt >= 0) slowestFrame = maxOf(slowestFrame, now - lastFrameAt)
        lastFrameAt = now
        frames++
        tabFaces = "$tabSkins/$tiles"
        if (!timingLogged && tiles > 0 && ready == tiles && gate.state == LeapGate.ACTIVE) logTiming("all clickable")
    }

    /** Once per menu: ms from the open packet to the screen, its first frame and each tile becoming clickable. */
    fun logTiming(why: String) {
        if (timingLogged) return
        timingLogged = true
        logger.info(
            "Timing ({}), ms from the open packet: screen +{} (vanilla frames {}), first frame +{}, clickable {}; faces from the tab list {}; {} frames, slowest {} ms",
            why,
            if (screenAt < 0) "-" else screenAt - openAt,
            vanillaFrames,
            if (firstFrameAt < 0) "-" else firstFrameAt - openAt,
            gate.arrivals().joinToString { "${it.first} +${it.second}" }.ifEmpty { "none" },
            tabFaces.ifEmpty { "-" },
            frames,
            slowestFrame,
        )
    }
}
