package com.soulreturns.features.profit.dragon

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Dragon-island profit tracker. Buckets drops by [DragonType] × [KillSource] (SUMMONED vs
 * LOOTSHARE) so the HUD can show "what did I make from kills I summoned" separately from
 * "what did I make from kills I lootshared into someone else's nest."
 *
 * **Why this doesn't extend the generic `ProfitTracker<B, D>` base.** The base's one-axis
 * bucketing fits slayer / mineshaft style trackers (one boss type per kill, no co-op
 * attribution); dragons have a real second axis (kill source) that all per-kill state needs
 * to be partitioned along — drops, kill counts, eye costs. Forcing it into the generic
 * shape via `B = (DragonType, KillSource)` was tempting but every read path then needs to
 * collapse one axis, which was uglier than just owning the storage here. Future profit
 * trackers that don't need a partition can still use the generic base.
 *
 * **Eyes are only tracked under SUMMONED.** By definition: a player only places eyes when
 * they're contributing to a summoning, and the only kills counted as SUMMONED are ones
 * where they placed ≥ 1 eye. So `eyesPlaced[bucket]` lives on the summoned partition; the
 * lootshare partition has no eyes (and consequently zero ancillary cost in [profitFor]).
 *
 * **Persistence:** `config/soul/dragon_profit.json`, schema v2.
 * ```jsonc
 * {
 *   "schemaVersion": 2,
 *   "summoned": { "kills": {...}, "drops": {...}, "eyes": {...} },
 *   "lootshare": { "kills": {...}, "drops": {...} }
 * }
 * ```
 * v1 files (single combined `kills`/`drops` block) migrate forward by treating all prior
 * data as summoned — the assumption that most past kills were summoner-attributed. Not
 * strictly accurate, but acceptable because (a) the tracker is brand-new with little or no
 * real data yet, and (b) future kills get properly attributed.
 */
object DragonProfitTracker {
    private val logger = SoulLogger("Soul/Profit/DragonProfitTracker")

    private const val SUMMONING_EYE_ID = "SUMMONING_EYE"
    private const val SAVE_FILE_NAME = "dragon_profit.json"
    private const val CURRENT_SCHEMA_VERSION = 2

    data class Counts(var amount: Long = 0L)

    /**
     * The source the next `grantDrop` / `grantKill` will attribute to. Set by
     * [EyePlacementTracker] on every dragon spawn. Defaults to LOOTSHARE — if the user
     * misses a spawn message (lag, late join) or relogs mid-kill, attributing as lootshare
     * is the safe default (it only declines to charge eye cost, doesn't fabricate it).
     */
    @Volatile var currentKillSource: KillSource = KillSource.LOOTSHARE

    // ─── Per-source partitions: session (in-memory) + total (persisted) ───
    private val sessionDrops: PartitionedDrops = PartitionedDrops()
    private val totalDrops: PartitionedDrops = PartitionedDrops()
    private val sessionKills: PartitionedKills = PartitionedKills()
    private val totalKills: PartitionedKills = PartitionedKills()

    /** Eyes placed (always summoned by definition). */
    private val sessionEyes: ConcurrentHashMap<DragonType, Long> = ConcurrentHashMap()
    private val totalEyes: ConcurrentHashMap<DragonType, Long> = ConcurrentHashMap()

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/$SAVE_FILE_NAME")
    }

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile private var initialized: Boolean = false

    private class PartitionedDrops {
        private val byKey: ConcurrentHashMap<Key, ConcurrentHashMap<DragonDrop, Counts>> = ConcurrentHashMap()

        fun add(
            bucket: DragonType,
            source: KillSource,
            drop: DragonDrop,
            amount: Long,
        ) {
            byKey.getOrPut(Key(bucket, source)) { ConcurrentHashMap() }
                .compute(drop) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        }

        fun get(
            bucket: DragonType,
            source: KillSource,
        ): Map<DragonDrop, Counts> = byKey[Key(bucket, source)]?.toMap() ?: emptyMap()

        fun clear() = byKey.clear()

        fun snapshot(): Map<KillSource, Map<DragonType, Map<DragonDrop, Counts>>> {
            val out = KillSource.entries.associateWith { mutableMapOf<DragonType, Map<DragonDrop, Counts>>() }
            for ((key, drops) in byKey) {
                out[key.source]!![key.bucket] = drops.toMap()
            }
            return out.mapValues { it.value.toMap() }
        }

        data class Key(val bucket: DragonType, val source: KillSource)
    }

    private class PartitionedKills {
        private val byKey: ConcurrentHashMap<PartitionedDrops.Key, Long> = ConcurrentHashMap()

        fun add(
            bucket: DragonType,
            source: KillSource,
            kills: Long,
        ) {
            byKey.merge(PartitionedDrops.Key(bucket, source), kills, Long::plus)
        }

        fun get(
            bucket: DragonType,
            source: KillSource,
        ): Long = byKey[PartitionedDrops.Key(bucket, source)] ?: 0L

        fun clear() = byKey.clear()

        fun snapshot(): Map<KillSource, Map<DragonType, Long>> {
            val out = KillSource.entries.associateWith { mutableMapOf<DragonType, Long>() }
            for ((key, count) in byKey) {
                out[key.source]!![key.bucket] = count
            }
            return out.mapValues { it.value.toMap() }
        }
    }

    fun init() {
        if (initialized) return
        initialized = true
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
     * Record [amount] of [drop] for [bucket]. Source defaults to [currentKillSource] which
     * is set by [EyePlacementTracker] on each dragon spawn; passing an explicit [source]
     * is used by `/soul dev grantDragonDropAs` for partition testing without real kills.
     */
    fun grantDrop(
        bucket: DragonType,
        drop: DragonDrop,
        amount: Long = 1L,
        source: KillSource = currentKillSource,
    ) {
        if (amount <= 0L) return
        sessionDrops.add(bucket, source, drop, amount)
        totalDrops.add(bucket, source, drop, amount)
        dirty = true
    }

    fun grantKill(
        bucket: DragonType,
        kills: Long = 1L,
        source: KillSource = currentKillSource,
    ) {
        if (kills <= 0L) return
        sessionKills.add(bucket, source, kills)
        totalKills.add(bucket, source, kills)
        dirty = true
    }

    /**
     * Record [count] Summoning Eyes the local player placed for [bucket]. Always SUMMONED
     * by definition — eyes are the act of summoning.
     */
    fun grantEye(
        bucket: DragonType,
        count: Long = 1L,
    ) {
        if (count <= 0L) return
        sessionEyes.merge(bucket, count, Long::plus)
        totalEyes.merge(bucket, count, Long::plus)
        dirty = true
    }

    /** Reset every session counter to 0. Persisted totals are untouched. */
    fun resetSession() {
        sessionDrops.clear()
        sessionKills.clear()
        sessionEyes.clear()
    }

    /** Wipe both session AND total data. Used by `/soul dev resetDragonProfit`. */
    fun resetAll() {
        resetSession()
        totalDrops.clear()
        totalKills.clear()
        totalEyes.clear()
        dirty = true
    }

    /**
     * Per-(bucket, drop) counts for [tab] filtered by [sourceFilter]. `null` = sum across
     * both partitions ("All" view). Defensive-copy; iteration is safe without write locks.
     */
    fun countsFor(
        tab: TrackerTab,
        sourceFilter: KillSource? = null,
    ): Map<DragonType, Map<DragonDrop, Counts>> {
        val partition = if (tab == TrackerTab.Session) sessionDrops else totalDrops
        val out = HashMap<DragonType, HashMap<DragonDrop, Counts>>()
        for (bucket in DragonType.entries) {
            for (source in KillSource.entries) {
                if (sourceFilter != null && sourceFilter != source) continue
                val inner = partition.get(bucket, source)
                if (inner.isEmpty()) continue
                val bucketMap = out.getOrPut(bucket) { HashMap() }
                for ((drop, counts) in inner) {
                    val prev = bucketMap[drop]
                    if (prev == null) {
                        bucketMap[drop] = Counts(amount = counts.amount)
                    } else {
                        prev.amount += counts.amount
                    }
                }
            }
        }
        return out
    }

    fun killsFor(
        bucket: DragonType,
        tab: TrackerTab,
        sourceFilter: KillSource? = null,
    ): Long {
        val partition = if (tab == TrackerTab.Session) sessionKills else totalKills
        return if (sourceFilter != null) {
            partition.get(bucket, sourceFilter)
        } else {
            KillSource.entries.sumOf { partition.get(bucket, it) }
        }
    }

    /** Eyes placed for [bucket] in [tab]. Always summoned by definition — no source filter. */
    fun eyesPlacedFor(
        bucket: DragonType,
        tab: TrackerTab,
    ): Long {
        val source = if (tab == TrackerTab.Session) sessionEyes else totalEyes
        return source[bucket] ?: 0L
    }

    /**
     * Sum of `amount × PriceCache.price(itemId, priceSource)` across (bucket, drop) pairs
     * in [tab] matching [bucketFilter] (empty = all buckets) and [sourceFilter] (null =
     * all sources). Eye cost is subtracted ONLY when [sourceFilter] is null or SUMMONED —
     * lootshare-only views don't charge eyes since the player didn't place any.
     */
    fun profitFor(
        tab: TrackerTab,
        bucketFilter: Set<DragonType> = emptySet(),
        sourceFilter: KillSource? = null,
        priceSource: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
    ): Long {
        val data = countsFor(tab, sourceFilter)
        var sum = 0L
        for ((bucket, drops) in data) {
            if (bucketFilter.isNotEmpty() && bucket !in bucketFilter) continue
            for ((drop, counts) in drops) {
                sum += PriceCache.price(drop.itemId, priceSource) * counts.amount
            }
            // Charge eye cost only for views that include SUMMONED kills.
            if (sourceFilter == null || sourceFilter == KillSource.SUMMONED) {
                val eyes = eyesPlacedFor(bucket, tab)
                if (eyes > 0L) {
                    sum -= eyes * PriceCache.price(SUMMONING_EYE_ID, priceSource)
                }
            }
        }
        return sum
    }

    // ───────────────────── persistence ─────────────────────

    private fun load() {
        if (!saveFile.exists()) {
            logger.info("No $SAVE_FILE_NAME — starting from defaults")
            return
        }
        try {
            val json = JsonParser.parseReader(saveFile.reader())
            if (!json.isJsonObject) {
                logger.warn("$SAVE_FILE_NAME not a JSON object — using defaults")
                return
            }
            val root = json.asJsonObject
            val schema = root.get("schemaVersion")?.asInt ?: 1
            if (schema == 1) {
                loadV1(root)
                // Re-save in v2 format on next save tick.
                dirty = true
                logger.info("Migrated v1 dragon_profit.json → v2 (all prior data → summoned partition)")
            } else {
                loadV2(root)
            }
            logger.info("Loaded $SAVE_FILE_NAME (schema v$schema)")
        } catch (e: Exception) {
            logger.warn("Failed to read $SAVE_FILE_NAME — using defaults", e)
        }
    }

    private fun loadV1(root: JsonObject) {
        // v1 had `kills: {bucket: long}` + `drops: {bucket: {drop: long}}` at the top level
        // and no source distinction. Treat all prior data as SUMMONED (heuristic: most
        // dragon kills people remember are ones they summoned themselves).
        root.getAsJsonObject("kills")?.entrySet()?.forEach { (bucketName, e) ->
            val bucket = DragonType.byName(bucketName) ?: return@forEach
            totalKills.add(bucket, KillSource.SUMMONED, e.asLong)
        }
        root.getAsJsonObject("drops")?.entrySet()?.forEach { (bucketName, dropsElement) ->
            val bucket = DragonType.byName(bucketName) ?: return@forEach
            dropsElement.asJsonObject.entrySet().forEach { (dropIdStr, amountElement) ->
                val drop = DragonDrop.byId(dropIdStr) ?: return@forEach
                totalDrops.add(bucket, KillSource.SUMMONED, drop, amountElement.asLong)
            }
        }
    }

    private fun loadV2(root: JsonObject) {
        for (source in KillSource.entries) {
            val block = root.getAsJsonObject(source.name.lowercase()) ?: continue
            block.getAsJsonObject("kills")?.entrySet()?.forEach { (bucketName, e) ->
                val bucket = DragonType.byName(bucketName) ?: return@forEach
                totalKills.add(bucket, source, e.asLong)
            }
            block.getAsJsonObject("drops")?.entrySet()?.forEach { (bucketName, dropsElement) ->
                val bucket = DragonType.byName(bucketName) ?: return@forEach
                dropsElement.asJsonObject.entrySet().forEach { (dropIdStr, amountElement) ->
                    val drop = DragonDrop.byId(dropIdStr) ?: return@forEach
                    totalDrops.add(bucket, source, drop, amountElement.asLong)
                }
            }
            // Only SUMMONED carries eyes — read from the same block for forward compat.
            block.getAsJsonObject("eyes")?.entrySet()?.forEach { (bucketName, e) ->
                val bucket = DragonType.byName(bucketName) ?: return@forEach
                totalEyes.merge(bucket, e.asLong, Long::plus)
            }
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val killsSnapshot = totalKills.snapshot()
        val dropsSnapshot = totalDrops.snapshot()
        val eyesSnapshot = totalEyes.toMap()
        val json =
            JsonObject().apply {
                addProperty("schemaVersion", CURRENT_SCHEMA_VERSION)
                for (source in KillSource.entries) {
                    val block = JsonObject()
                    val killsBlock = JsonObject()
                    killsSnapshot[source]?.forEach { (bucket, count) ->
                        killsBlock.addProperty(bucket.name, count)
                    }
                    block.add("kills", killsBlock)
                    val dropsBlock = JsonObject()
                    dropsSnapshot[source]?.forEach { (bucket, drops) ->
                        val bucketObj = JsonObject()
                        drops.forEach { (drop, counts) ->
                            bucketObj.addProperty(drop.name, counts.amount)
                        }
                        dropsBlock.add(bucket.name, bucketObj)
                    }
                    block.add("drops", dropsBlock)
                    // Eyes only attach to the SUMMONED block — lootshare has no eyes by
                    // definition.
                    if (source == KillSource.SUMMONED) {
                        val eyesBlock = JsonObject()
                        eyesSnapshot.forEach { (bucket, count) ->
                            if (count > 0L) eyesBlock.addProperty(bucket.name, count)
                        }
                        block.add("eyes", eyesBlock)
                    }
                    add(source.name.lowercase(), block)
                }
            }
        SoulExecutor.executor.submit {
            try {
                saveFile.parentFile?.mkdirs()
                val tmp = File(saveFile.parentFile, "${saveFile.name}.tmp")
                tmp.writeText(gson.toJson(json))
                if (saveFile.exists()) saveFile.delete()
                if (!tmp.renameTo(saveFile)) {
                    logger.warn("Could not rename ${tmp.name} → ${saveFile.name}; falling back to direct write")
                    saveFile.writeText(gson.toJson(json))
                    tmp.delete()
                }
            } catch (e: Exception) {
                logger.warn("Failed to persist $SAVE_FILE_NAME", e)
                dirty = true
            } finally {
                saving = false
            }
        }
    }
}
