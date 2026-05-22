package com.soulreturns.features.profit.dragon

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soulreturns.data.drops.DropResolver
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
 * **Drop dimension is the item id (`String`).** Pre-backend-migration this was a
 * `DragonDrop` enum; the dragon drop list now lives in the backend's `DropSource` /
 * `Drop` tables fronted by [com.soulreturns.data.drops.DropCatalogClient]. The tracker
 * stores item ids verbatim; resolution of display name / rarity / price-lookup id happens
 * at HUD render time via [DropResolver].
 *
 * **Why this doesn't extend the generic `ProfitTracker<B, D>` base.** Two-axis bucketing
 * (DragonType × KillSource) doesn't fit the single-axis base. Forcing it into the generic
 * shape via `B = (DragonType, KillSource)` was tempting but every read path then needs to
 * collapse one axis, which was uglier than just owning the storage here. Future profit
 * trackers that don't need a partition can still use the generic base.
 *
 * **Eyes are only tracked under SUMMONED.** By definition: a player only places eyes when
 * they're contributing to a summoning. So `eyesPlaced[bucket]` lives on the summoned
 * partition; the lootshare partition has no eyes (zero ancillary cost in [profitFor]).
 *
 * **Persistence:** `config/soul/dragon_profit.json`, schema v3.
 * ```jsonc
 * {
 *   "schemaVersion": 3,
 *   "summoned": { "kills": {...}, "drops": {...}, "eyes": {...} },
 *   "lootshare": { "kills": {...}, "drops": {...} }
 * }
 * ```
 *  - **v1 → v3**: pre-2-axis schema. All prior data → summoned partition + enum-name → item-id rename.
 *  - **v2 → v3**: two-axis schema with `DragonDrop` enum-name drop keys. Translate to item ids.
 *
 * The v2→v3 rename map ([ENUM_NAME_TO_ITEM_ID]) is the only place enum-name knowledge
 * survives. Most entries are 1:1; the exceptions are the historic divergences
 * (`DRACONIC_SHARD` → `SHARD_DRACONIC`, the two pet entries → `ENDER_DRAGON;<tier>`).
 */
object DragonProfitTracker {
    private val logger = SoulLogger("Soul/Profit/DragonProfitTracker")

    private const val SUMMONING_EYE_ID = "SUMMONING_EYE"
    private const val SAVE_FILE_NAME = "dragon_profit.json"
    private const val CURRENT_SCHEMA_VERSION = 3

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

    /**
     * Pre-backend-migration `DragonDrop` enum constant names → item ids. Only consulted
     * during v1/v2 file migration. Most entries are identity (the enum name matched the
     * item id by convention); the divergent ones are the historic edge cases. After the
     * first save in v3 format this map is no longer consulted.
     */
    private val ENUM_NAME_TO_ITEM_ID: Map<String, String> =
        mapOf(
            "DRACONIC_SHARD" to "SHARD_DRACONIC",
            "ENDER_DRAGON_PET_EPIC" to "ENDER_DRAGON;3",
            "ENDER_DRAGON_PET_LEGENDARY" to "ENDER_DRAGON;4",
            // Hypixel's items catalog ships this scroll under the `<LOCATION>_TRAVEL_SCROLL`
            // family — the NEU / SkyHanni `TRAVEL_SCROLL_TO_<LOCATION>` convention doesn't
            // exist in Hypixel's response. Rename so the persisted data lands on the id
            // PriceCache + the drop catalog actually carry.
            "TRAVEL_SCROLL_TO_DRAGONS_NEST" to "DRAGON_NEST_TRAVEL_SCROLL",
            // Everything else uses the enum-name-as-id convention — fall-through in [renameEnumToItemId].
        )

    private fun renameEnumToItemId(enumName: String): String = ENUM_NAME_TO_ITEM_ID[enumName] ?: enumName

    private class PartitionedDrops {
        // Key is (bucket, source); value maps item id → counts.
        private val byKey: ConcurrentHashMap<Key, ConcurrentHashMap<String, Counts>> = ConcurrentHashMap()

        fun add(
            bucket: DragonType,
            source: KillSource,
            itemId: String,
            amount: Long,
        ) {
            byKey.getOrPut(Key(bucket, source)) { ConcurrentHashMap() }
                .compute(itemId) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        }

        fun get(
            bucket: DragonType,
            source: KillSource,
        ): Map<String, Counts> = byKey[Key(bucket, source)]?.toMap() ?: emptyMap()

        fun clear() = byKey.clear()

        fun snapshot(): Map<KillSource, Map<DragonType, Map<String, Counts>>> {
            val out = KillSource.entries.associateWith { mutableMapOf<DragonType, Map<String, Counts>>() }
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
     * Record [amount] of [itemId] for [bucket]. Source defaults to [currentKillSource]
     * which is set by [EyePlacementTracker] on each dragon spawn; passing an explicit
     * [source] is used by `/soul dev grantDragonDropAs` for partition testing.
     */
    fun grantDrop(
        bucket: DragonType,
        itemId: String,
        amount: Long = 1L,
        source: KillSource = currentKillSource,
    ) {
        if (amount <= 0L || itemId.isEmpty()) return
        sessionDrops.add(bucket, source, itemId, amount)
        totalDrops.add(bucket, source, itemId, amount)
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

    fun resetSession() {
        sessionDrops.clear()
        sessionKills.clear()
        sessionEyes.clear()
    }

    fun resetAll() {
        resetSession()
        totalDrops.clear()
        totalKills.clear()
        totalEyes.clear()
        dirty = true
    }

    /**
     * Per-(bucket, itemId) counts for [tab] filtered by [sourceFilter]. `null` = sum across
     * both partitions ("All" view). Defensive copy; iteration is safe without write locks.
     */
    fun countsFor(
        tab: TrackerTab,
        sourceFilter: KillSource? = null,
    ): Map<DragonType, Map<String, Counts>> {
        val partition = if (tab == TrackerTab.Session) sessionDrops else totalDrops
        val out = HashMap<DragonType, HashMap<String, Counts>>()
        for (bucket in DragonType.entries) {
            for (source in KillSource.entries) {
                if (sourceFilter != null && sourceFilter != source) continue
                val inner = partition.get(bucket, source)
                if (inner.isEmpty()) continue
                val bucketMap = out.getOrPut(bucket) { HashMap() }
                for ((itemId, counts) in inner) {
                    val prev = bucketMap[itemId]
                    if (prev == null) {
                        bucketMap[itemId] = Counts(amount = counts.amount)
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

    fun eyesPlacedFor(
        bucket: DragonType,
        tab: TrackerTab,
    ): Long {
        val source = if (tab == TrackerTab.Session) sessionEyes else totalEyes
        return source[bucket] ?: 0L
    }

    /**
     * Sum of `amount × PriceCache.price(priceLookupId, priceSource)` across (bucket, itemId)
     * pairs in [tab] matching [bucketFilter] (empty = all) and [sourceFilter] (null = all).
     * Eye cost is subtracted ONLY when [sourceFilter] is null or SUMMONED — lootshare-only
     * views don't charge eyes since the player didn't place any. Resolved price-lookup id
     * comes from [DropResolver.priceLookupId] so attribute-shard-style `bazaarId` overrides
     * are honored.
     */
    fun profitFor(
        tab: TrackerTab,
        bucketFilter: Set<DragonType> = emptySet(),
        sourceFilter: KillSource? = null,
        priceSource: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
        useNpcFloor: Boolean = false,
    ): Long {
        val data = countsFor(tab, sourceFilter)
        var sum = 0L
        for ((bucket, drops) in data) {
            if (bucketFilter.isNotEmpty() && bucket !in bucketFilter) continue
            for ((itemId, counts) in drops) {
                sum += PriceCache.priceWithNpcFloor(
                    DropResolver.priceLookupId(itemId),
                    priceSource,
                    useNpcFloor,
                ) * counts.amount
            }
            if (sourceFilter == null || sourceFilter == KillSource.SUMMONED) {
                val eyes = eyesPlacedFor(bucket, tab)
                if (eyes > 0L) {
                    // Eye cost uses the raw price — the NPC floor is for items the player
                    // SELLS (loot), not items they BUY (eyes). Floor would only ever
                    // increase the subtraction, making the floor user-hostile here.
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
            when {
                schema == 1 -> {
                    loadV1(root)
                    dirty = true
                    logger.info("Migrated v1 dragon_profit.json → v$CURRENT_SCHEMA_VERSION")
                }
                schema == 2 -> {
                    loadV2OrV3(root, renameKeys = true)
                    dirty = true
                    logger.info("Migrated v2 dragon_profit.json → v$CURRENT_SCHEMA_VERSION (enum names → item ids)")
                }
                else -> loadV2OrV3(root, renameKeys = false)
            }
            logger.info("Loaded $SAVE_FILE_NAME (schema v$schema)")
        } catch (e: Exception) {
            logger.warn("Failed to read $SAVE_FILE_NAME — using defaults", e)
        }
    }

    private fun loadV1(root: JsonObject) {
        root.getAsJsonObject("kills")?.entrySet()?.forEach { (bucketName, e) ->
            val bucket = DragonType.byName(bucketName) ?: return@forEach
            totalKills.add(bucket, KillSource.SUMMONED, e.asLong)
        }
        root.getAsJsonObject("drops")?.entrySet()?.forEach { (bucketName, dropsElement) ->
            val bucket = DragonType.byName(bucketName) ?: return@forEach
            dropsElement.asJsonObject.entrySet().forEach { (dropIdStr, amountElement) ->
                totalDrops.add(bucket, KillSource.SUMMONED, renameEnumToItemId(dropIdStr), amountElement.asLong)
            }
        }
    }

    private fun loadV2OrV3(
        root: JsonObject,
        renameKeys: Boolean,
    ) {
        for (source in KillSource.entries) {
            val block = root.getAsJsonObject(source.name.lowercase()) ?: continue
            block.getAsJsonObject("kills")?.entrySet()?.forEach { (bucketName, e) ->
                val bucket = DragonType.byName(bucketName) ?: return@forEach
                totalKills.add(bucket, source, e.asLong)
            }
            block.getAsJsonObject("drops")?.entrySet()?.forEach { (bucketName, dropsElement) ->
                val bucket = DragonType.byName(bucketName) ?: return@forEach
                dropsElement.asJsonObject.entrySet().forEach { (rawKey, amountElement) ->
                    val itemId = if (renameKeys) renameEnumToItemId(rawKey) else rawKey
                    totalDrops.add(bucket, source, itemId, amountElement.asLong)
                }
            }
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
                        drops.forEach { (itemId, counts) ->
                            bucketObj.addProperty(itemId, counts.amount)
                        }
                        dropsBlock.add(bucket.name, bucketObj)
                    }
                    block.add("drops", dropsBlock)
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
