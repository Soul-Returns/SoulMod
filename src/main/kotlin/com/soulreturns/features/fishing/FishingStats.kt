package com.soulreturns.features.fishing

import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import kotlinx.serialization.Serializable

/**
 * One scope's fishing counters — the same shape for all three of them (session, festival, all-time),
 * which is the whole point.
 *
 * The legacy mod stored thirteen flat fields with a `festival` prefix on six of them, so the HUD
 * needed a branch per scope and a new counter meant touching every one. Here a scope is a value, the
 * HUD renders whichever it is handed, and adding a counter is one field.
 *
 * Counters are `Long` because a grinder's all-time catches genuinely outlive an `Int`'s comfortable
 * range once you count double hooks, and mixing widths across scopes would be worse than the extra
 * bytes.
 *
 * @property fishingXp the XP Hypixel actually printed in the action bar, summed — see
 *   [FishingXpTracker]. Deliberately **not** derived from [catchesByCreature] and the catalog's base
 *   values: Wisdom, the pet, the festival and event buffs all multiply it, so a catalog-derived
 *   total is wrong for every real account and wrong by a different factor for each. `Double` because
 *   Hypixel pays out fractional XP (`+12.5 Fishing`).
 */
@Serializable
data class FishingCounts(
    val catches: Long = 0L,
    val doubleHooks: Long = 0L,
    val cocoons: Long = 0L,
    val fishingXp: Double = 0.0,
    val catchesByCreature: Map<String, Long> = emptyMap(),
    val doubleHooksByCreature: Map<String, Long> = emptyMap(),
    val cocoonsByCreature: Map<String, Long> = emptyMap(),
) {
    /** A catch — with the double hook it consumed, if any. */
    fun withCatch(
        creature: String,
        doubleHook: Boolean,
    ): FishingCounts = copy(
        catches = catches + 1,
        catchesByCreature = catchesByCreature.plusOne(creature),
        doubleHooksByCreature = if (doubleHook) doubleHooksByCreature.plusOne(creature) else doubleHooksByCreature,
    )

    /** Fishing XP as the action bar reported it. Not tied to a catch — see [FishingXpTracker]. */
    fun withXp(gained: Double): FishingCounts = copy(fishingXp = fishingXp + gained)

    /**
     * A `CAUGHT! You cocooned a X!` line. A separate outcome, never a catch: the catch line for that
     * creature already counted, so incrementing [catches] here would double it.
     */
    fun withCocoon(creature: String): FishingCounts = copy(
        cocoons = cocoons + 1,
        cocoonsByCreature = cocoonsByCreature.plusOne(creature),
    )

    /** [count] rather than one, because a chat compactor coalesces repeats into `(N)`. */
    fun withDoubleHooks(count: Int): FishingCounts = copy(doubleHooks = doubleHooks + count)

    /** Every creature that appears in any of the three breakdowns. */
    fun creatureNames(): Set<String> =
        catchesByCreature.keys + doubleHooksByCreature.keys + cocoonsByCreature.keys

    fun isEmpty(): Boolean = catches == 0L && doubleHooks == 0L && cocoons == 0L && fishingXp == 0.0

    private fun Map<String, Long>.plusOne(key: String): Map<String, Long> = this + (key to ((this[key] ?: 0L) + 1L))
}

/**
 * What fishing writes to `config/soul/stats.json`, under the `fishing` namespace, keyed by SkyBlock
 * profile.
 *
 * The session scope is persisted too and follows the shared tracker-session lifetime
 * ([com.soulreturns.stats.TrackerSessions]): it survives a quick restart and resets when the client
 * launches more than 30 minutes after it last ran, or on the panel's Reset Session.
 *
 * @property festivalStartAt epoch ms of the first underway message seen for the currently-active
 *   festival, or 0 when none is. Persisted so a mid-festival relaunch resumes the same bucket rather
 *   than starting a second one — see [com.soulreturns.data.skyblock.FishingFestivalState].
 * @property sessionMs active-fishing time this session ([FishingTimer]), without the span still running.
 */
@Serializable
data class FishingStats(
    val allTime: FishingCounts = FishingCounts(),
    val festival: FishingCounts = FishingCounts(),
    val festivalStartAt: Long = 0L,
    val session: FishingCounts = FishingCounts(),
    val sessionMs: Long = 0L,
) {
    /** What Reset Session (and a fresh start) leaves: session counters and stopwatch at zero. */
    fun withoutSession(): FishingStats = copy(session = FishingCounts(), sessionMs = 0L)
}

/**
 * The `fishing` namespace handle.
 *
 * ### The key is the profile, and `_legacy` is not a fallback
 * The tab list is empty for the first seconds of a session, so [SkyblockProfileState] has no answer
 * yet and writes go to [PersistentStats.LEGACY_KEY]. The first `ProfileChanged` naming a profile
 * promotes that bucket into it. Without that, everything caught between joining and the tab list
 * populating would be filed under a key nothing ever reads again.
 */
object FishingStatsStore {
    const val NAMESPACE: String = "fishing"

    val handle: StatsHandle<FishingStats> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = FishingStats.serializer(),
            default = { FishingStats() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    fun read(): FishingStats = handle.read()

    fun update(block: FishingStats.() -> FishingStats) = handle.update(block)
}
