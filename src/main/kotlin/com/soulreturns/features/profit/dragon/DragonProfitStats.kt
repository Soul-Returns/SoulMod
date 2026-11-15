package com.soulreturns.features.profit.dragon

import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import kotlinx.serialization.Serializable

/**
 * One kill-source partition's counters: kills and eyes per dragon, and drops per dragon per item id.
 *
 * Keys are enum **names** and item ids, not ordinals — a key that survives a reordering of
 * [DragonType] and reads sensibly in the stats file when someone opens it.
 *
 * Eyes only ever appear on the summoned partition; a lootshare kill is by definition one the player
 * placed no eyes toward. The field exists on both because one shape for both partitions is what lets
 * every read path below be written once.
 */
@Serializable
data class DragonPartition(
    /** `DragonType.name` → kills credited. */
    val kills: Map<String, Long> = emptyMap(),
    /** `DragonType.name` → item id → amount. */
    val drops: Map<String, Map<String, Long>> = emptyMap(),
    /** `DragonType.name` → Summoning Eyes the local player placed. */
    val eyes: Map<String, Long> = emptyMap(),
) {
    fun withKill(
        type: DragonType,
        count: Long,
    ): DragonPartition = copy(kills = kills.plus(type.name, count))

    fun withDrop(
        type: DragonType,
        itemId: String,
        amount: Long,
    ): DragonPartition {
        val bucket = drops[type.name].orEmpty()
        return copy(drops = drops + (type.name to bucket.plus(itemId, amount)))
    }

    fun withEye(
        type: DragonType,
        count: Long,
    ): DragonPartition = copy(eyes = eyes.plus(type.name, count))

    fun killsOf(type: DragonType): Long = kills[type.name] ?: 0L

    fun eyesOf(type: DragonType): Long = eyes[type.name] ?: 0L

    fun dropsOf(type: DragonType): Map<String, Long> = drops[type.name].orEmpty()

    fun isEmpty(): Boolean = kills.isEmpty() && drops.isEmpty() && eyes.isEmpty()

    private fun Map<String, Long>.plus(
        key: String,
        amount: Long,
    ): Map<String, Long> = this + (key to ((this[key] ?: 0L) + amount))
}

/**
 * The whole two-axis model: one partition per [KillSource].
 *
 * ### One shape for both scopes
 * The session counters and the all-time counters are the *same type*, so the panel reads whichever it
 * is handed and every query below is written once. The legacy version had two bespoke partition
 * classes with parallel `add` / `get` / `snapshot` methods and a separate eye map beside them, which
 * meant three places to touch to add a counter.
 *
 * ### No migration path, deliberately
 * The legacy mod carried v1→v3 and v2→v3 file migrations plus an enum-name→item-id rename table,
 * purely to upgrade files written by older builds of itself. **No such file has ever existed on a
 * 26.2 client** — this is a fresh baseline — so the whole path is gone and this starts at item-id
 * keys. The three divergent ids (`SHARD_DRACONIC`, `ENDER_DRAGON;3`, `ENDER_DRAGON;4`) still matter,
 * but as the ids [DragonLootScanner] *produces*, not as a rename table.
 */
@Serializable
data class DragonProfitStats(
    val summoned: DragonPartition = DragonPartition(),
    val lootshare: DragonPartition = DragonPartition(),
) {
    /** `if/else` rather than `when (source)` — see [KillSource.displayName] for why. */
    fun partition(source: KillSource): DragonPartition = if (source == KillSource.SUMMONED) summoned else lootshare

    fun withPartition(
        source: KillSource,
        block: DragonPartition.() -> DragonPartition,
    ): DragonProfitStats = if (source == KillSource.SUMMONED) {
        copy(summoned = summoned.block())
    } else {
        copy(lootshare = lootshare.block())
    }

    fun isEmpty(): Boolean = summoned.isEmpty() && lootshare.isEmpty()
}

/**
 * The `dragon_profit` namespace in `config/soul/stats.json`, keyed by SkyBlock profile.
 *
 * The **session** scope is its own namespace, [DragonProfitSessionStore], with the same payload type.
 *
 * The bucket key is the profile, with `_legacy` as the not-yet-known placeholder that
 * [PersistentStats] promotes on the first `ProfileChanged` — the tab list is empty for the first
 * seconds of a session, which is exactly when someone relogging at the nest lands their first kill.
 *
 * This file is local and stays local. There is no upload path for it and none may be added; if these
 * numbers should ever be visible to another player, that is an opt-in `soul-stats` namespace the
 * backend aggregates, not a mirror of this file.
 */
object DragonProfitStatsStore {
    const val NAMESPACE: String = "dragon_profit"

    val handle: StatsHandle<DragonProfitStats> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = DragonProfitStats.serializer(),
            default = { DragonProfitStats() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    fun read(): DragonProfitStats = handle.read()

    fun update(block: DragonProfitStats.() -> DragonProfitStats) = handle.update(block)
}

/**
 * The `dragon_profit_session` namespace: the Session tab's counters, same shape and key as
 * [DragonProfitStatsStore]. Survives a quick restart and resets on a launch more than 30 minutes after
 * the client last ran ([com.soulreturns.stats.TrackerSessions]) or on Reset Session.
 */
object DragonProfitSessionStore {
    const val NAMESPACE: String = "dragon_profit_session"

    val handle: StatsHandle<DragonProfitStats> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = DragonProfitStats.serializer(),
            default = { DragonProfitStats() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    fun read(): DragonProfitStats = handle.read()

    fun update(block: DragonProfitStats.() -> DragonProfitStats) = handle.update(block)
}
