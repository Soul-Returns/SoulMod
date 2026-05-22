package com.soulreturns.features.diana

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.model.SackDeltasApplied
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Detects Mythological-Ritual loot via two channels and credits matches to the
 * [MythologicalProfitTracker.MOB_LOOT_BUCKET] / [MythologicalProfitTracker.LOOTSHARE_BUCKET]:
 *
 *  1. **Inventory delta** — once per second, snapshot the player's inventory and credit
 *     positive count changes for items on the backend's `mythological.mob_loot` /
 *     `mythological.lootshare` sources. Bucket selection reads
 *     [DianaLootshareDetector.isInLootShareLootWindow] — when a `LOOT SHARE You received
 *     loot for assisting <player>!` chat fired in the last few seconds, the drop goes to
 *     LOOTSHARE; otherwise MOB_LOOT.
 *  2. **Sack chat** — subscribes to [SackDeltasApplied] published by
 *     [com.soulreturns.features.sacks.SackChatReader]. Hypixel can batch sack chat up to
 *     60 s late; the attribution window stretches [SACK_GRACE_MS] past the active-timer
 *     pause to absorb that delay. Per user design: **Diana lootshare drops never land in
 *     sacks — only direct-to-inventory**, so the sack handler always routes to mob_loot.
 *
 * **Attribution window** rules:
 *  - While [MythologicalActivityTimer] is ACTIVE: sack window is open.
 *  - For [SACK_GRACE_MS] after the timer pauses: sack window stays open.
 *  - Inventory window additionally accepts a recent LOOT SHARE chat so pure-follow
 *    sessions (no own digs) still credit.
 *  - Outside both: deltas are ignored.
 *
 * **Deduplication** against [MythologicalProfitChatListener]'s `RARE DROP! You dug out X!`
 * path: when chat credits a treasure-burrow drop, it records `(itemId, count, timestamp)`
 * in [pendingChatCredits]. The inventory watcher consumes from this buffer first — same
 * item ids matching a recent chat credit don't double-count. Buffer entries TTL out after
 * [CHAT_DEDUP_WINDOW_MS] so a long-delayed inventory re-pickup (rare but possible) doesn't
 * stay suppressed forever.
 */
object DianaLootWatcher {
    private val logger = SoulLogger("Soul/Diana")

    /** Tick interval between inventory snapshots. 20 ticks = 1 second. */
    private const val SNAPSHOT_INTERVAL_TICKS = 20

    /** Milliseconds the attribution window stays open after `MythologicalActivityTimer` pauses. */
    private const val SACK_GRACE_MS = 90_000L

    /**
     * How long a chat-credited drop stays in [pendingChatCredits] for inventory-dedup
     * matching. Inventory pickup of the same item should happen within a few seconds of
     * the chat line; 10 s is generous slack for client-side hiccups.
     */
    private const val CHAT_DEDUP_WINDOW_MS = 10_000L

    /** Last tick the activity timer was ACTIVE, used to compute the grace window. */
    @Volatile private var lastActiveAt: Long = 0L

    /**
     * Per-item-id total counts from the previous inventory snapshot. Used to compute deltas.
     * Tracked continuously across pauses (no transition-on-window-open reset) so a
     * lootshare event landing after the activity timer's been paused for hours catches
     * the next delta against a fresh baseline instead of against an empty map.
     */
    private var lastSnapshot: Map<String, Long> = emptyMap()

    private var tickCounter = 0

    /**
     * Pending "I credited this via chat" entries. Each entry consumes one inventory-delta
     * match for the same item id, decrementing or removing itself. Append-only within a
     * window, TTL-pruned each scan.
     */
    private data class ChatCredit(val itemId: String, var remaining: Long, val recordedAt: Long)

    private val pendingChatCredits: MutableList<ChatCredit> = mutableListOf()

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ -> tick() })
    }

    /**
     * Called from [MythologicalProfitChatListener] when a `RARE DROP! You dug out X!` line
     * credits a treasure-burrow drop. Suppresses a matching inventory delta arriving in the
     * next [CHAT_DEDUP_WINDOW_MS] so we don't double-count.
     */
    fun recordChatCredit(
        itemId: String,
        count: Long,
    ) {
        if (count <= 0L) return
        pendingChatCredits.add(ChatCredit(itemId, count, System.currentTimeMillis()))
    }

    @HandleEvent
    fun onSackDeltas(event: SackDeltasApplied) {
        if (!cfg.dev.trackers.mythologicalTracker()) return
        if (!sackWindowOpen()) return
        val watched = watchedItemIds()
        if (watched.isEmpty()) return
        for ((itemId, delta) in event.deltas) {
            if (delta <= 0L) continue
            if (itemId !in watched) continue
            // Sack credits don't share the chat-dedup buffer — treasure burrow chat-line
            // drops go straight to inventory pickup, not to a sack, so by the time
            // anything gets sucked into a sack the chat dedup is already consumed (or
            // about to expire). Crediting both would only happen on a Hypixel bug.
            MythologicalProfitTracker.grantDrop(MythologicalProfitTracker.MOB_LOOT_BUCKET, itemId, delta)
        }
    }

    /**
     * Sack credit gate — active timer OR within the 90 s post-pause grace. Lootshare does
     * NOT extend this window (sack items are explicitly not credited as lootshare).
     */
    private fun sackWindowOpen(): Boolean {
        if (!MythologicalActivityTimer.isPaused) return true
        if (lastActiveAt == 0L) return false
        return System.currentTimeMillis() - lastActiveAt < SACK_GRACE_MS
    }

    /**
     * Inventory credit gate — sack window OR an active lootshare window. Lootshare credits
     * fire even when the activity timer's been paused beyond grace, because the player
     * may be standing around waiting for a party member's spawn to die without digging
     * themselves.
     */
    private fun inventoryWindowOpen(): Boolean =
        sackWindowOpen() || DianaLootshareDetector.isInLootShareLootWindow()

    /**
     * Items the watcher is listening for — union of every drop curated under
     * `mythological.mob_loot` AND `mythological.lootshare`. Admins can put the
     * comprehensive Diana drop list in either bucket (or split between them) — the
     * watcher cares about presence, not which bucket owns it. The bucket the credit
     * lands in is decided at credit time by the lootshare detector's window, not by
     * which catalog source the item happens to live under.
     */
    private fun watchedItemIds(): Set<String> {
        val out = HashSet<String>()
        DropCatalogClient.dropsFrom(MythologicalProfitTracker.MOB_LOOT_BUCKET).forEach { out.add(it.itemId) }
        DropCatalogClient.dropsFrom(MythologicalProfitTracker.LOOTSHARE_BUCKET).forEach { out.add(it.itemId) }
        return out
    }

    private fun tick() {
        if (!cfg.dev.trackers.mythologicalTracker()) return
        if (!MythologicalActivityTimer.isPaused) {
            lastActiveAt = System.currentTimeMillis()
        }

        tickCounter++
        if (tickCounter < SNAPSHOT_INTERVAL_TICKS) return
        tickCounter = 0

        // Don't snapshot while a container screen is open — moving items between inventory
        // and a chest reads as bogus deltas. Reset baseline so the next post-container
        // snapshot picks up where things actually land.
        if (Minecraft.getInstance().screen != null) {
            lastSnapshot = emptyMap()
            return
        }

        val current = currentInventoryCounts() ?: return
        if (lastSnapshot.isEmpty()) {
            lastSnapshot = current
            return
        }

        creditDeltas(lastSnapshot, current)
        lastSnapshot = current
    }

    private fun creditDeltas(
        previous: Map<String, Long>,
        current: Map<String, Long>,
    ) {
        val now = System.currentTimeMillis()
        pendingChatCredits.removeAll { now - it.recordedAt > CHAT_DEDUP_WINDOW_MS }
        if (!inventoryWindowOpen()) return
        val watched = watchedItemIds()
        if (watched.isEmpty()) return
        // Bucket decision: route to LOOTSHARE when the authoritative `LOOT SHARE You
        // received loot...` chat fired in the last few seconds, otherwise this is an
        // own-kill drop (or a sack-grace drop) and routes to MOB_LOOT.
        val bucket =
            if (DianaLootshareDetector.isInLootShareLootWindow()) {
                MythologicalProfitTracker.LOOTSHARE_BUCKET
            } else {
                MythologicalProfitTracker.MOB_LOOT_BUCKET
            }
        for ((itemId, currentCount) in current) {
            if (itemId !in watched) continue
            val prev = previous[itemId] ?: 0L
            val delta = currentCount - prev
            if (delta <= 0L) continue
            val crediting = consumeChatCredit(itemId, delta)
            if (crediting > 0L) {
                MythologicalProfitTracker.grantDrop(bucket, itemId, crediting)
                val bucketTag = if (bucket == MythologicalProfitTracker.LOOTSHARE_BUCKET) "LOOTSHARE" else "MOB_LOOT"
                logger.info("Diana inv credit: $itemId x$crediting -> $bucketTag")
            }
        }
    }

    /**
     * Reduce the unattributed [delta] by any matching chat-credit entries (oldest first).
     * Returns the amount left to credit via inventory. Buffer entries with zero remaining
     * are removed.
     */
    private fun consumeChatCredit(
        itemId: String,
        delta: Long,
    ): Long {
        var remaining = delta
        val iter = pendingChatCredits.iterator()
        while (iter.hasNext() && remaining > 0L) {
            val entry = iter.next()
            if (entry.itemId != itemId) continue
            val consume = minOf(entry.remaining, remaining)
            entry.remaining -= consume
            remaining -= consume
            if (entry.remaining <= 0L) iter.remove()
        }
        return remaining
    }

    /**
     * Snapshot the player's current inventory keyed by skyblock id. Returns null when
     * there's no player to read from (mid-disconnect / title screen).
     */
    private fun currentInventoryCounts(): Map<String, Long>? {
        val player = Minecraft.getInstance().player ?: return null
        val inventory = player.inventory
        val counts = HashMap<String, Long>()
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            // Use the price-lookup id (NOT the bare skyblock id) so enchanted books are
            // counted per (enchant, level) pair — bazaar's `ENCHANTMENT_<NAME>_<LEVEL>`
            // shape — instead of collapsing every book under one `ENCHANTED_BOOK` bucket.
            val id = SkyblockItemUtils.getPriceLookupId(stack) ?: continue
            counts.merge(id, stack.count.toLong(), Long::plus)
        }
        return counts
    }
}
