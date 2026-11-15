package com.soulreturns.features.profit.dragon

import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceMode
import com.soulreturns.stats.TrackerSessions
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.util.SoulLogger

/**
 * The dragon profit model: drops, kills and eyes bucketed by [DragonType] × [KillSource].
 *
 * ### Why two axes
 * "What did I make from kills I summoned" and "what did I make lootsharing into someone else's nest"
 * are different questions with different economics — only the first has an eye cost — and a player
 * who does both wants them apart. So a bucket is a *pair*, and the panel's Source picker chooses
 * whether to read one partition or the sum.
 *
 * This is also why the legacy `ProfitTracker<B, D>` base was not ported: it is single-axis, forcing a
 * pair into it meant every read path collapsing an axis back out, and with Diana gone it had no
 * second user to justify the shape.
 *
 * ### Session and total are the same type
 * Both are a [DragonProfitStats], both in `config/soul/stats.json` keyed by SkyBlock profile: total in
 * `dragon_profit`, session in `dragon_profit_session` ([DragonProfitSessionStore]), which follows the
 * shared tracker-session lifetime ([TrackerSessions]). Every read below therefore has one
 * implementation that takes whichever it was handed.
 *
 * ### Values are never stored
 * Only counts. A coin value is computed at read time from [PriceCache], so changing the price source
 * — or the market moving — is reflected immediately and retroactively rather than baked into
 * whatever the price happened to be at the moment of the drop.
 */
object DragonProfitTracker {
    private val logger = SoulLogger("Soul/DragonProfit")

    const val SUMMONING_EYE_ID: String = "SUMMONING_EYE"

    /**
     * The source the next grant is attributed to, set by [EyePlacementTracker] on each dragon spawn.
     *
     * [DragonLootScanner] **freezes** this at scan-begin rather than reading it per grant: finishing
     * kill A and immediately summoning kill B would otherwise flip it while A's 30 s window is still
     * open, and A's drops would land in B's partition.
     */
    @Volatile
    var currentKillSource: KillSource = KillSource.LOOTSHARE

    /** Persisted per profile; see [DragonProfitSessionStore]. */
    val session: DragonProfitStats get() = DragonProfitSessionStore.read()

    fun register() {
        TrackerSessions.resetOnFreshStart(DragonProfitSessionStore.handle) { DragonProfitStats() }
    }

    fun total(): DragonProfitStats = DragonProfitStatsStore.read()

    // ------------------------------------------------------------------ grants

    fun grantDrop(
        type: DragonType,
        itemId: String,
        amount: Long = 1L,
        source: KillSource = currentKillSource,
    ) {
        if (amount <= 0L || itemId.isEmpty()) return
        DragonProfitSessionStore.update { withPartition(source) { withDrop(type, itemId, amount) } }
        DragonProfitStatsStore.update { withPartition(source) { withDrop(type, itemId, amount) } }
    }

    fun grantKill(
        type: DragonType,
        kills: Long = 1L,
        source: KillSource = currentKillSource,
    ) {
        if (kills <= 0L) return
        DragonProfitSessionStore.update { withPartition(source) { withKill(type, kills) } }
        DragonProfitStatsStore.update { withPartition(source) { withKill(type, kills) } }
    }

    /** Eyes the local player placed. Always [KillSource.SUMMONED] — placing one *is* summoning. */
    fun grantEye(
        type: DragonType,
        count: Long = 1L,
    ) {
        if (count <= 0L) return
        DragonProfitSessionStore.update { withPartition(KillSource.SUMMONED) { withEye(type, count) } }
        DragonProfitStatsStore.update { withPartition(KillSource.SUMMONED) { withEye(type, count) } }
    }

    fun resetSession() {
        DragonProfitSessionStore.update { DragonProfitStats() }
        logger.info("Session counters cleared")
    }

    /** Clears the all-time counters for the **current profile** as well as the session. */
    fun resetAll() {
        resetSession()
        DragonProfitStatsStore.update { DragonProfitStats() }
        logger.info("All-time counters cleared for this profile")
    }

    // ------------------------------------------------------------------ reads

    /**
     * Which scope a tab reads. `if/else` rather than `when (tab)` — see [KillSource.displayName].
     * [TrackerTab.Event] is not in this tracker's tab list, so anything that is not Session is Total.
     */
    fun scope(tab: TrackerTab): DragonProfitStats = if (tab == TrackerTab.Session) session else total()

    /**
     * Per-dragon, per-item counts for [tab], narrowed to [sourceFilter] (null = both partitions
     * summed).
     *
     * Pure and allocation-only: the profit panel lays out twice per frame and calls this on both
     * passes, so it must be safe to run repeatedly and must not touch Minecraft state.
     */
    fun countsFor(
        tab: TrackerTab,
        sourceFilter: KillSource? = null,
    ): Map<DragonType, Map<String, Long>> {
        val stats = scope(tab)
        val out = HashMap<DragonType, HashMap<String, Long>>()
        for (source in KillSource.entries) {
            if (sourceFilter != null && sourceFilter != source) continue
            val partition = stats.partition(source)
            for (type in DragonType.entries) {
                val drops = partition.dropsOf(type)
                if (drops.isEmpty()) continue
                val bucket = out.getOrPut(type) { HashMap() }
                for ((itemId, amount) in drops) {
                    bucket[itemId] = (bucket[itemId] ?: 0L) + amount
                }
            }
        }
        return out
    }

    fun killsFor(
        type: DragonType,
        tab: TrackerTab,
        sourceFilter: KillSource? = null,
    ): Long {
        val stats = scope(tab)
        if (sourceFilter != null) return stats.partition(sourceFilter).killsOf(type)
        return KillSource.entries.sumOf { stats.partition(it).killsOf(type) }
    }

    /** Eyes placed for [type]. Summoned-only by definition, so the source filter does not apply. */
    fun eyesPlacedFor(
        type: DragonType,
        tab: TrackerTab,
    ): Long = scope(tab).partition(KillSource.SUMMONED).eyesOf(type)

    /**
     * Gross coin value of the drops in [tab] under [bucketFilter] (empty = all dragons) and
     * [sourceFilter] (null = both partitions). No eye cost — that is [eyeCostFor], kept separate
     * because the panel shows the two as distinct numbers and the Lootshare view charges neither.
     */
    fun grossValueFor(
        tab: TrackerTab,
        bucketFilter: Set<DragonType> = emptySet(),
        sourceFilter: KillSource? = null,
        mode: PriceMode = PriceMode.INSTANT,
        useNpcFloor: Boolean = false,
    ): Long {
        var sum = 0L
        for ((type, drops) in countsFor(tab, sourceFilter)) {
            if (bucketFilter.isNotEmpty() && type !in bucketFilter) continue
            for ((itemId, amount) in drops) {
                sum += PriceCache.sellValue(DropResolver.priceLookupId(itemId), mode, useNpcFloor) * amount
            }
        }
        return sum
    }

    /**
     * What the player spent on Summoning Eyes for the kills in view.
     *
     * Priced through [PriceCache.buyCost] — the **buy** side of [mode], since an eye is something the
     * player acquired rather than something they sell. Under [PriceMode.NPC] that still yields a
     * market price, because no NPC sells eyes and a zero eye cost would overstate every summoned kill
     * by the whole cost of the eyes; `buyCost` documents why.
     *
     * **The NPC floor is deliberately not applied here.** That toggle exists to value items the player
     * *sells*; an eye is something they *bought*, so flooring it could only ever inflate the
     * subtraction and make the tracker report less profit than the player made.
     */
    fun eyeCostFor(
        tab: TrackerTab,
        bucketFilter: Set<DragonType> = emptySet(),
        mode: PriceMode = PriceMode.INSTANT,
    ): Long {
        val unit = PriceCache.buyCost(SUMMONING_EYE_ID, mode)
        if (unit <= 0L) return 0L
        var eyes = 0L
        for (type in DragonType.entries) {
            if (bucketFilter.isNotEmpty() && type !in bucketFilter) continue
            eyes += eyesPlacedFor(type, tab)
        }
        return eyes * unit
    }

    /**
     * Net profit: gross minus eye cost, where the eye cost applies only when the view includes
     * summoned kills. A Lootshare-only view charges nothing, because the player placed nothing.
     */
    fun profitFor(
        tab: TrackerTab,
        bucketFilter: Set<DragonType> = emptySet(),
        sourceFilter: KillSource? = null,
        mode: PriceMode = PriceMode.INSTANT,
        useNpcFloor: Boolean = false,
    ): Long {
        val gross = grossValueFor(tab, bucketFilter, sourceFilter, mode, useNpcFloor)
        if (sourceFilter == KillSource.LOOTSHARE) return gross
        return gross - eyeCostFor(tab, bucketFilter, mode)
    }
}
