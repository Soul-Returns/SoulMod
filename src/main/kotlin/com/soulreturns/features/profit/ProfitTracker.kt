package com.soulreturns.features.profit

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Generic profit-tracker base. Buckets drops by some enum [B] (dragon type, slayer boss,
 * mineshaft type, …) and tracks count + per-item details for each. Stores the all-time
 * data to a per-tracker JSON file at `config/soul/<savePath>` with tick-debounced atomic
 * writes; session counters live in memory only and reset via [resetSession] or client
 * restart.
 *
 * **Why not [com.soulreturns.stats.PersistentStats]?** Profit data is per-tracker (each
 * tracker has its own reset semantics and its own bucket axis), can grow large per item ×
 * bucket, and shouldn't bloat `stats.json` for every user across every profile. Each profit
 * tracker owns one JSON file in `config/soul/`. Cloud-sync wiring is a separate
 * `SyncKind.PROFIT_*` addition, layered on top later — out of scope for this initial cut.
 *
 * **Where drops come from:** [grantDrop] is the only entry point. The dragon detection
 * scheme (scanning item-display armor stands the moment a kill completes) is deliberately
 * out of scope; for now the `/soul dev grantDragonDrop` command calls into this method
 * directly, so HUD layout and reset / sort / filter behaviour can be validated before drop
 * detection lands.
 *
 * @param B bucket axis — typically an enum of bosses / dragon types / location variants.
 * @param D drop type — typically an enum of items dropped by this domain. The drop's coin
 *   value is looked up via [PriceCache] keyed on [drop.itemId][itemId].
 */
abstract class ProfitTracker<B : Enum<B>, D : Any>(
    /** File name under `config/soul/`, e.g. `"dragon_profit.json"`. */
    private val saveFileName: String,
    /** All possible bucket values — used to seed the per-bucket maps deterministically. */
    private val bucketValues: List<B>,
) {
    /**
     * Per-(bucket, drop) totals. Counts only — [PriceCache] turns counts into coin value
     * at display time, so prices can update without us rewriting stored data.
     */
    data class Counts(var amount: Long = 0L)

    /** Number of kills tracked per bucket — useful for "drops per kill" displays later. */
    private val sessionKills: ConcurrentHashMap<B, Long> = ConcurrentHashMap()

    private val totalKills: ConcurrentHashMap<B, Long> = ConcurrentHashMap()

    /** Bucket → drop → counts. Separate maps for in-memory session vs persisted total. */
    private val sessionData: ConcurrentHashMap<B, ConcurrentHashMap<D, Counts>> = ConcurrentHashMap()

    private val totalData: ConcurrentHashMap<B, ConcurrentHashMap<D, Counts>> = ConcurrentHashMap()

    private val logger = SoulLogger("Soul/Profit/${javaClass.simpleName}")

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/$saveFileName")
    }

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile private var initialized: Boolean = false

    /** Convert a drop into the price-cache key (Hypixel/NEU item id, e.g. `"SUMMONING_EYE"`). */
    protected abstract fun itemId(drop: D): String

    /** Resolve a string id back to a drop. Used by the JSON loader and `/soul dev`. */
    protected abstract fun dropById(id: String): D?

    /** String key of a drop — used to keep the JSON stable across enum renumberings. */
    protected abstract fun dropId(drop: D): String

    /** Resolve a bucket name back to its enum. Used by JSON load + `/soul dev`. */
    protected abstract fun bucketByName(name: String): B?

    /**
     * Cost subtracted from a bucket's profit total. Default = 0; override for bucket-keyed
     * ancillary costs like Summoning Eyes for dragons. Receives the **persisted** kill /
     * eye counts (whichever the tracker stores) so it can multiply by live price-cache
     * values at display time.
     */
    protected open fun ancillaryCost(
        bucket: B,
        tab: com.soulreturns.ui.hud.tracker.TrackerTab,
    ): Long = 0L

    /**
     * Initialise the tracker — load persisted data + register the tick-driven save loop.
     * Idempotent. Call from `Soul.registerFeatures()`.
     */
    fun init() {
        if (initialized) return
        initialized = true
        bucketValues.forEach {
            sessionData[it] = ConcurrentHashMap()
            totalData[it] = ConcurrentHashMap()
            sessionKills[it] = 0L
            totalKills[it] = 0L
        }
        load()
        var ticksSinceSave = 0
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                ticksSinceSave++
                if (dirty && !saving && ticksSinceSave >= 20) {
                    ticksSinceSave = 0
                    saveAsync()
                }
            },
        )
    }

    /**
     * Add [amount] of [drop] to [bucket]'s counters. Updates both session (in-memory) and
     * total (persisted) — there's no separate "session only" or "total only" mode; a real
     * drop counts toward both. Marks the tracker dirty so the next tick persists.
     */
    fun grantDrop(
        bucket: B,
        drop: D,
        amount: Long = 1L,
    ) {
        if (amount <= 0L) return
        sessionData.getOrPut(bucket) { ConcurrentHashMap() }
            .compute(drop) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        totalData.getOrPut(bucket) { ConcurrentHashMap() }
            .compute(drop) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        dirty = true
    }

    /**
     * Bump the kill count for [bucket]. Tracks how many of this bucket's encounters the
     * player has completed — used for "drops per kill" displays and for the ancillary cost
     * formula (e.g. dragons subtract eyes-placed × eye price, where eyes per kill is
     * roughly proportional to kills). Defaults to one kill per call.
     */
    fun grantKill(
        bucket: B,
        kills: Long = 1L,
    ) {
        if (kills <= 0L) return
        sessionKills.merge(bucket, kills, Long::plus)
        totalKills.merge(bucket, kills, Long::plus)
        dirty = true
    }

    /** Reset every session counter back to 0. Persisted totals are untouched. */
    open fun resetSession() {
        sessionData.values.forEach { it.clear() }
        sessionKills.replaceAll { _, _ -> 0L }
    }

    /** Wipe both session AND total data. Used by `/soul dev resetDragonProfit`. */
    fun resetAll() {
        resetSession()
        totalData.values.forEach { it.clear() }
        totalKills.replaceAll { _, _ -> 0L }
        dirty = true
    }

    /**
     * Snapshot of per-(bucket, drop) counts for [tab]. Returns a defensive copy — callers
     * can iterate freely without holding the maps' write locks.
     */
    fun countsFor(tab: com.soulreturns.ui.hud.tracker.TrackerTab): Map<B, Map<D, Counts>> {
        val source = if (tab == com.soulreturns.ui.hud.tracker.TrackerTab.Session) sessionData else totalData
        return source.mapValues { (_, inner) -> inner.toMap() }
    }

    /** Kill count for [bucket] in [tab]. */
    fun killsFor(
        bucket: B,
        tab: com.soulreturns.ui.hud.tracker.TrackerTab,
    ): Long {
        val source = if (tab == com.soulreturns.ui.hud.tracker.TrackerTab.Session) sessionKills else totalKills
        return source[bucket] ?: 0L
    }

    /**
     * Sum of `amount × PriceCache.price(itemId(drop), source)` across the (bucket, drop)
     * pairs in [tab] matching [bucketFilter] (empty filter = all buckets), then minus
     * [ancillaryCost] per matching bucket. Returns 0 when prices haven't loaded yet.
     */
    fun profitFor(
        tab: com.soulreturns.ui.hud.tracker.TrackerTab,
        bucketFilter: Set<B> = emptySet(),
        source: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
    ): Long {
        val data = if (tab == com.soulreturns.ui.hud.tracker.TrackerTab.Session) sessionData else totalData
        var sum = 0L
        for ((bucket, drops) in data) {
            if (bucketFilter.isNotEmpty() && bucket !in bucketFilter) continue
            for ((drop, counts) in drops) {
                val price = PriceCache.price(itemId(drop), source)
                sum += price * counts.amount
            }
            sum -= ancillaryCost(bucket, tab)
        }
        return sum
    }

    // ───────────────────── persistence ─────────────────────

    /**
     * JSON schema (v1):
     * ```jsonc
     * {
     *   "schemaVersion": 1,
     *   "kills": { "<bucket>": <long>, ... },
     *   "drops": {
     *     "<bucket>": { "<dropId>": { "amount": <long> }, ... },
     *     ...
     *   }
     * }
     * ```
     */
    private data class Snapshot(
        var schemaVersion: Int = 1,
        var kills: MutableMap<String, Long> = mutableMapOf(),
        var drops: MutableMap<String, MutableMap<String, Long>> = mutableMapOf(),
    )

    private fun load() {
        if (!saveFile.exists()) {
            logger.info("No $saveFileName — starting from defaults")
            return
        }
        try {
            val json = JsonParser.parseReader(saveFile.reader())
            if (!json.isJsonObject) {
                logger.warn("$saveFileName not a JSON object — using defaults")
                return
            }
            val snap = gson.fromJson(json, Snapshot::class.java) ?: return
            for ((bucketName, count) in snap.kills) {
                val bucket = bucketByName(bucketName) ?: continue
                totalKills[bucket] = count
            }
            for ((bucketName, drops) in snap.drops) {
                val bucket = bucketByName(bucketName) ?: continue
                val inner = totalData.getOrPut(bucket) { ConcurrentHashMap() }
                for ((dropIdStr, amount) in drops) {
                    val drop = dropById(dropIdStr) ?: continue
                    inner[drop] = Counts(amount = amount)
                }
            }
            logger.info("Loaded $saveFileName: ${snap.drops.size} buckets, ${snap.kills.values.sum()} total kills")
        } catch (e: Exception) {
            logger.warn("Failed to read $saveFileName — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot =
            Snapshot(
                schemaVersion = 1,
                kills =
                    totalKills.entries.associate { (k, v) -> k.name to v }
                        .toMutableMap(),
                drops =
                    totalData.entries.associate { (k, v) ->
                        k.name to v.entries.associate { (d, c) -> dropId(d) to c.amount }.toMutableMap()
                    }.toMutableMap(),
            )
        SoulExecutor.executor.submit {
            try {
                saveFile.parentFile?.mkdirs()
                val tmp = File(saveFile.parentFile, "${saveFile.name}.tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (saveFile.exists()) saveFile.delete()
                if (!tmp.renameTo(saveFile)) {
                    logger.warn("Could not rename ${tmp.name} → ${saveFile.name}; falling back to direct write")
                    saveFile.writeText(gson.toJson(snapshot))
                    tmp.delete()
                }
            } catch (e: Exception) {
                logger.warn("Failed to persist $saveFileName", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
