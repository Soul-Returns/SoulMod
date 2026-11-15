package com.soulreturns.features.mining.nucleus

import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.Minecraft

/**
 * Which Goblin Egg King Yolkar took. The chat line (`I'm covering you in my foul stench`) says "any
 * type", so the kind comes from the inventory: the egg count that dropped between a snapshot from
 * before the hand-in and now. Without an answer after [WAIT_MS] it is booked as the plain `Goblin Egg`.
 */
object GoblinEggs {
    /** SkyBlock id -> kit name (the name is the row label and what [NucleusItems] prices). */
    val KINDS: Map<String, String> =
        linkedMapOf(
            "GOBLIN_EGG" to "Goblin Egg",
            "GOBLIN_EGG_GREEN" to "Green Goblin Egg",
            "GOBLIN_EGG_YELLOW" to "Yellow Goblin Egg",
            "GOBLIN_EGG_RED" to "Red Goblin Egg",
            "GOBLIN_EGG_BLUE" to "Blue Goblin Egg",
        )

    /** Snapshots older than this are the baseline: the egg left the inventory just before the chat line. */
    private const val BASELINE_AGE_MS = 1_500L
    private const val KEEP_MS = 6_000L
    private const val WAIT_MS = 2_500L
    private const val SNAPSHOT_EVERY_MS = 250L

    private class Snapshot(val atMs: Long, val counts: Map<String, Int>)

    private val snapshots = ArrayDeque<Snapshot>()
    private var lastSnapshotMs = 0L
    private var pendingSinceMs = 0L
    private var baseline: Map<String, Int>? = null

    /** Pure: the kit name of the kind whose count dropped the most from [before] to [after], or null. */
    fun kindSpent(before: Map<String, Int>, after: Map<String, Int>): String? =
        KINDS.keys.maxByOrNull { (before[it] ?: 0) - (after[it] ?: 0) }
            ?.takeIf { (before[it] ?: 0) > (after[it] ?: 0) }
            ?.let(KINDS::get)

    /** The egg line arrived: resolve the kind from the inventory in the next ticks. */
    fun onEggLine(now: Long = System.currentTimeMillis()) {
        baseline = snapshots.lastOrNull { now - it.atMs >= BASELINE_AGE_MS }?.counts ?: snapshots.firstOrNull()?.counts
        pendingSinceMs = now
        // Nothing to compare against (first seconds after a restart): book the plain egg at once.
        if (baseline == null) finish(NucleusLines.GOBLIN_EGG_ANY)
    }

    fun tick(now: Long = System.currentTimeMillis()) {
        if (now - lastSnapshotMs >= SNAPSHOT_EVERY_MS) {
            lastSnapshotMs = now
            val counts = readInventory() ?: return
            snapshots.addLast(Snapshot(now, counts))
            while (snapshots.isNotEmpty() && now - snapshots.first().atMs > KEEP_MS) snapshots.removeFirst()
            val before = baseline ?: return
            // The line may arrive before the inventory update: keep looking until the wait runs out.
            kindSpent(before, counts)?.let { finish(it); return }
        }
        if (baseline != null && now - pendingSinceMs >= WAIT_MS) finish(NucleusLines.GOBLIN_EGG_ANY)
    }

    private fun finish(name: String) {
        baseline = null
        NucleusRuns.addKit(name)
    }

    private fun readInventory(): Map<String, Int>? {
        val player = Minecraft.getInstance().player ?: return null
        val counts = HashMap<String, Int>()
        for (stack in player.inventory.getNonEquipmentItems()) {
            val id = SkyblockItemUtils.getSkyblockId(stack) ?: continue
            if (id in KINDS) counts[id] = (counts[id] ?: 0) + stack.count
        }
        return counts
    }
}
