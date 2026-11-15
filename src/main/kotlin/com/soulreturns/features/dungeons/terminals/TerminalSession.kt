package com.soulreturns.features.dungeons.terminals

import com.soulreturns.config.cfg
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.ServerTraffic
import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.ContainerInput
import net.minecraft.world.item.ItemStack

/**
 * One open terminal menu: the server's view of its slots, the recognition state and the click gate.
 *
 * The real menu stays open the whole time; this only reads it. [view] is what the server last sent:
 * a click's local prediction (vanilla moves the clicked item onto the cursor until the server
 * resyncs) is recorded by stack identity and ignored until the server replaces that stack.
 */
class TerminalSession(val menu: ChestMenu, val spec: TerminalSpec) {
    companion object {
        const val PENDING = 0
        const val ACTIVE = 1
        const val FAILED = 2

        /** Hypixel's full contents arrive ~0.2 s after the open; anything slower is not a terminal we know. */
        private const val LOAD_TIMEOUT_MS = 2000L

        /** How long a blocked click's cell shows its red cue, fading out. */
        const val FLASH_MS = 180L

        private val logger = SoulLogger("Soul/Terminals")

        /** Click-to-echo latency, from every terminal's accepted clicks this game session. */
        val latency = LatencyEstimate()

        /** The latency estimate; the player list's ping only as a fallback (Hypixel reports 1 ms there). */
        fun latencyMs(): Long {
            val mc = Minecraft.getInstance()
            val ping = mc.player?.let { mc.connection?.getPlayerInfo(it.uuid)?.latency } ?: 0
            return latency.estimate(System.currentTimeMillis(), ping.toLong())
        }

        fun termItem(stack: ItemStack): TermItem? {
            if (stack.isEmpty) return null
            val patch = stack.componentsPatch.entrySet().any { it.key === DataComponents.ENCHANTMENT_GLINT_OVERRIDE && it.value.orElse(null) == true }
            val intrinsic = stack.item.components().has(DataComponents.ENCHANTMENT_GLINT_OVERRIDE) || (stack.hasFoil() && !patch)
            return TermItem(
                BuiltInRegistries.ITEM.getKey(stack.item).path,
                MessageDetector.stripColorCodes(stack.hoverName.string),
                stack.count,
                patch,
                intrinsic,
            )
        }
    }

    private val size = spec.type.size
    private val opened = System.currentTimeMillis()
    val tracker = TerminalTracker(spec, latency = ::latencyMs, lastInbound = { ServerTraffic.lastInboundMs })

    /** Clicks sent and not yet answered: slot to send time, for the latency estimate. */
    private val sentAt = HashMap<Int, Long>()

    var state = PENDING
        private set

    /** The stack the server last sent per container slot (what the GUI draws). */
    val view: Array<ItemStack> = Array(size) { ItemStack.EMPTY }

    private val localRefs = HashMap<Int, ItemStack>()
    private val cachedStacks = arrayOfNulls<ItemStack>(size)
    private val cachedItems = arrayOfNulls<TermItem>(size)

    var solution: TerminalTracker.Solution? = null
        private set

    /** Slot and time of the last blocked click, for the red cue. */
    var flashSlot = -1
        private set
    var flashAt = 0L
        private set

    /** Strength of the blocked-click cue on [slot]: 1 right after the click, fading to 0 over [FLASH_MS]. */
    fun flash(slot: Int, now: Long = System.currentTimeMillis()): Float {
        if (slot != flashSlot) return 0f
        val age = now - flashAt
        return if (age in 0 until FLASH_MS) 1f - age.toFloat() / FLASH_MS else 0f
    }

    private var snapshot: Array<ItemStack>? = null

    /** The menu is still the player's open container and recognition has not failed. */
    val live: Boolean get() = state != FAILED && Minecraft.getInstance().player?.containerMenu === menu

    /** Read the menu, advance recognition, re-solve. Cheap when nothing changed. */
    fun poll(now: Long = System.currentTimeMillis()) {
        if (state == FAILED) return
        if (Minecraft.getInstance().player?.containerMenu !== menu) return
        for (i in 0 until size) {
            val cur = menu.getSlot(i).item
            val local = localRefs[i]
            if (local != null) {
                if (local === cur) continue
                localRefs.remove(i)
            }
            if (cur !== view[i]) sentAt.remove(i)?.let { latency.add(now - it, now) }
            view[i] = cur
        }
        val board = board()
        if (state == PENDING) {
            if (!spec.slotsOk(board)) return fail("unexpected item while loading")
            if (spec.initialOk(board)) {
                state = ACTIVE
                logger.info("Recognized {} terminal \"{}\"", spec.type.key, spec.title)
            } else if (board.all { it != null }) {
                return fail("layout does not match")
            } else if (now - opened > LOAD_TIMEOUT_MS) {
                return fail("contents did not load")
            } else {
                return
            }
        }
        if (!spec.slotsOk(board)) return fail("unexpected item")
        tracker.update(board, now)
        solution = tracker.solution(now)
    }

    private fun fail(why: String) {
        state = FAILED
        logger.info("Terminal \"{}\" left to the vanilla menu: {}", spec.title, why)
    }

    private fun board(): Board = List(size) { i ->
        val stack = view[i]
        if (cachedStacks[i] !== stack) {
            cachedStacks[i] = stack
            cachedItems[i] = termItem(stack)
        }
        cachedItems[i]
    }

    /**
     * One physical click on [slot]: sends exactly one container click right now, or nothing when it
     * would be wrong and wrong clicks are blocked. A right click is vanilla's right PICKUP. A left click
     * is a middle click when `leftAsMiddle` is on (no ping-long pick-up onto the cursor), else a left
     * PICKUP. Always through `handleContainerInput`, so the packet is the one vanilla builds for that
     * click type: a middle click must be CLONE with button 2; any other button/type pair (Skytils'
     * button 2 with PICKUP, 2022) gets players kicked. Returns whether a click was sent.
     */
    fun click(slot: Int, button: Int): Boolean {
        val now = System.currentTimeMillis()
        poll(now)
        if (state != ACTIVE || (button != LEFT && button != RIGHT) || slot !in spec.type.board) return false
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return false
        val gameMode = mc.gameMode ?: return false
        if (player.containerMenu !== menu) return false
        val correct = tracker.isCorrect(slot, button, now)
        if (!correct && cfg.dungeons.terminals.blockWrongClicks) {
            flashSlot = slot
            flashAt = now
            logger.info("Blocked {} click on slot {} ({}, +{} ms: {})", if (button == LEFT) "left" else "right", slot, spec.type.key, now - opened, tracker.explain(slot, button, now))
            return false
        }
        val detail = if (!correct) "wrong: " + tracker.explain(slot, button, now) else if (spec.type === TerminalType.Melody) "correct: " + tracker.explain(slot, button, now) else "correct"
        val middle = button == LEFT && cfg.dungeons.terminals.leftAsMiddle
        beginVanillaClick()
        if (middle) {
            gameMode.handleContainerInput(menu.containerId, slot, MIDDLE, ContainerInput.CLONE, player)
        } else {
            gameMode.handleContainerInput(menu.containerId, slot, button, ContainerInput.PICKUP, player)
        }
        endVanillaClick()
        if (correct) tracker.clicked(slot, button, now)
        // The melody button answers on the lane's step loop, not after one round trip.
        if (spec.type !== TerminalType.Melody) sentAt[slot] = now
        solution = tracker.solution(now)
        val how = if (middle) "middle" else if (button == LEFT) "left" else "right"
        logger.info("Sent {} click on slot {} ({}, +{} ms, {})", how, slot, spec.type.key, now - opened, detail)
        return true
    }

    /**
     * Highlight-only mode: the vanilla screen is about to handle a click on [slot]. Returns false
     * when it must be blocked (wrong or done, with blocking on); otherwise records the prediction.
     */
    fun allowVanillaClick(slot: Int, button: Int): Boolean {
        val now = System.currentTimeMillis()
        poll(now)
        if (state != ACTIVE || slot !in spec.type.board) return true
        val correct = tracker.isCorrect(slot, button, now)
        if (!correct && cfg.dungeons.terminals.blockWrongClicks) {
            flashSlot = slot
            flashAt = now
            logger.info("Blocked {} click on slot {} ({}, vanilla screen, +{} ms: {})", button, slot, spec.type.key, now - opened, tracker.explain(slot, button, now))
            return false
        }
        if (correct) tracker.clicked(slot, button, now)
        if (spec.type !== TerminalType.Melody) sentAt[slot] = now
        return true
    }

    /** A sound while this menu is open: the melody lock's pling or error sound answers our last lock. */
    fun onSound(path: String, now: Long = System.currentTimeMillis()) {
        if (spec.type !== TerminalType.Melody || state != ACTIVE) return
        val accepted = when (path) {
            "block.note_block.pling" -> true
            "entity.enderman.teleport" -> false
            else -> return
        }
        if (tracker.melodySound(accepted, now)) logger.info("Melody {} heard (+{} ms)", if (accepted) "pling" else "error sound", now - opened)
    }

    /** A completion or refusal chat line while this menu is open (a teammate's pling or error rides on it). */
    fun onCompletionChat(now: Long = System.currentTimeMillis()) {
        if (spec.type !== TerminalType.Melody || state != ACTIVE) return
        if (tracker.melodyChat(now)) logger.info("Melody sound was a completion line's, not our lock's (+{} ms)", now - opened)
    }

    /** Remember every slot's stack before a click so the local prediction it makes can be told apart. */
    fun beginVanillaClick() {
        snapshot = Array(size) { menu.getSlot(it).item }
    }

    /** Slots the click changed locally keep showing the server's stack until the server answers. */
    fun endVanillaClick() {
        val before = snapshot ?: return
        snapshot = null
        for (i in 0 until size) {
            val cur = menu.getSlot(i).item
            if (cur === before[i]) continue
            if (localRefs[i] == null) view[i] = before[i]
            localRefs[i] = cur
        }
    }
}
