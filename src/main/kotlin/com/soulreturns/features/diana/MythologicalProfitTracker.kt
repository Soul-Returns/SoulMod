package com.soulreturns.features.diana

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.soulreturns.core.events.Events
import com.soulreturns.data.drops.DropCatalogClient
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.prices.PriceCache
import com.soulreturns.data.prices.PriceSource
import com.soulreturns.data.skyblock.MayorState
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.ui.hud.tracker.TrackerTab
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Mythological Ritual profit tracker — counts mob kills + treasure-burrow drops + per-bucket
 * item drops during Mayor Diana's term. The data layer mirrors [com.soulreturns.features.profit.dragon.DragonProfitTracker]'s
 * shape but with a String bucket axis (backend `DropSource.id` values) and no second
 * partition axis — there's no SUMMONED/LOOTSHARE equivalent for digs.
 *
 * **Bucket ids** correspond to backend source ids:
 *  - `mythological.minos_hunter`, `mythological.siamese_lynxes`, … (one per mob)
 *  - `mythological.treasure_burrow` (~25 % of burrows that drop items instead of mobs)
 *
 * **Why not extend `ProfitTracker<B, D>`?** The base requires `B : Enum<B>`. Bucket ids
 * are admin-curatable backend strings; bolting them onto a Kotlin enum would defeat the
 * point of moving the data to the backend. Standalone storage is the cleaner path.
 *
 * **Persistence:** `config/soul/mythological_profit.json`, schema v1.
 * ```jsonc
 * {
 *   "schemaVersion": 1,
 *   "kills": { "mythological.minos_hunter": <long>, ... },
 *   "drops": {
 *     "mythological.minos_hunter": { "<itemId>": <long>, ... },
 *     "mythological.treasure_burrow": { "GRIFFIN_FEATHER": <long>, "COINS": <long>, ... }
 *   },
 *   "burrowsTotal": <long>
 * }
 * ```
 *
 * `burrowsTotal` is a separate counter from per-bucket kills because every Griffin Burrow
 * dug fires `"You dug out a Griffin Burrow! (N/M)"` regardless of what's inside (mob vs
 * treasure). Counting burrows independently feeds the `burrows/hour` rate display.
 */
object MythologicalProfitTracker {
    private val logger = SoulLogger("Soul/Profit/MythologicalProfitTracker")

    private const val SAVE_FILE_NAME = "mythological_profit.json"
    private const val CURRENT_SCHEMA_VERSION = 2

    /** Bucket id for the treasure-burrow source — kept as a constant so chat-listeners agree. */
    const val TREASURE_BURROW_BUCKET = "mythological.treasure_burrow"

    /**
     * Bucket id for "mob loot" — all per-mob drops attributed without per-mob granularity.
     * The backend admin curates the drop list under this source; `DianaLootWatcher` watches
     * inventory + sack deltas for items in that list and credits matches here.
     */
    const val MOB_LOOT_BUCKET = "mythological.mob_loot"

    /**
     * Bucket id for attribute shards captured during Mythological Ritual. Shards never
     * land in inventory or sacks — they're inserted directly into the player's attribute-
     * shard menu. The detection path is exclusively chat-driven via the `"<NAME> You
     * charmed a <Mob> and captured N Shards from it."` line parsed in
     * [MythologicalProfitChatListener]. Backend admin curates which `SHARD_*` ids land
     * here so the row list + price + display flow through the existing resolver chain.
     */
    const val ATTRIBUTE_SHARD_BUCKET = "mythological.attribute_shard"

    /**
     * Bucket id for items the player received via lootshare — a nearby Mythological mob
     * the player damaged died without the player's own dig credit. Drops landing in
     * inventory inside a short window after the lootshare event are routed here instead
     * of [MOB_LOOT_BUCKET]; sack auto-deposits are NOT credited because they can be up to
     * 60 s delayed and attribution becomes unreliable past the death moment.
     */
    const val LOOTSHARE_BUCKET = "mythological.lootshare"

    data class Counts(var amount: Long = 0L)

    // ─── Session (in-memory) + total (persisted) ───
    private val sessionDrops: ConcurrentHashMap<String, ConcurrentHashMap<String, Counts>> = ConcurrentHashMap()
    private val totalDrops: ConcurrentHashMap<String, ConcurrentHashMap<String, Counts>> = ConcurrentHashMap()
    private val sessionKills: ConcurrentHashMap<String, Long> = ConcurrentHashMap()
    private val totalKills: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    @Volatile var sessionBurrows: Long = 0L
        private set

    @Volatile private var totalBurrows: Long = 0L

    // ─── Per-mayor-term event partitions (persisted) ───────────────────────────
    // Each map keyed by `MayorState.currentMayorKey()` (`"Diana_490"` etc.). Drops/kills/
    // burrows accumulated under whichever mayor was active when the event arrived;
    // historic mayor terms stay on disk so a future `/soul dev` command can inspect them.
    // The HUD's Event tab reads only the current mayor's bucket.
    private val eventDropsByMayor: ConcurrentHashMap<String, ConcurrentHashMap<String, ConcurrentHashMap<String, Counts>>> =
        ConcurrentHashMap()
    private val eventKillsByMayor: ConcurrentHashMap<String, ConcurrentHashMap<String, Long>> = ConcurrentHashMap()
    private val eventBurrowsByMayor: ConcurrentHashMap<String, Long> = ConcurrentHashMap()

    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val saveFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/$SAVE_FILE_NAME")
    }

    @Volatile private var dirty: Boolean = false

    @Volatile private var saving: Boolean = false

    @Volatile private var initialized: Boolean = false

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

    /** Record a drop. [bucketId] is a backend source id (`mythological.<mob>` or the treasure-burrow bucket). */
    fun grantDrop(
        bucketId: String,
        itemId: String,
        amount: Long = 1L,
    ) {
        if (amount <= 0L || bucketId.isEmpty() || itemId.isEmpty()) return
        sessionDrops.getOrPut(bucketId) { ConcurrentHashMap() }
            .compute(itemId) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        totalDrops.getOrPut(bucketId) { ConcurrentHashMap() }
            .compute(itemId) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        MayorState.currentMayorKey()?.let { mayor ->
            eventDropsByMayor.getOrPut(mayor) { ConcurrentHashMap() }
                .getOrPut(bucketId) { ConcurrentHashMap() }
                .compute(itemId) { _, prev -> (prev ?: Counts()).also { it.amount += amount } }
        }
        dirty = true
        // Stats-tracker hook: only the lootshare bucket counts as a LOOTSHARE source for
        // stat-reset purposes — everything else (mob_loot, treasure_burrow, attribute_shard)
        // represents drops from the player's own activity.
        val source = if (bucketId == LOOTSHARE_BUCKET) DianaEventSource.LOOTSHARE else DianaEventSource.OWN
        Events.publish(MythologicalDropCredited(bucketId, itemId, amount, source))
    }

    fun grantKill(
        bucketId: String,
        kills: Long = 1L,
    ) {
        if (kills <= 0L || bucketId.isEmpty()) return
        sessionKills.merge(bucketId, kills, Long::plus)
        totalKills.merge(bucketId, kills, Long::plus)
        MayorState.currentMayorKey()?.let { mayor ->
            eventKillsByMayor.getOrPut(mayor) { ConcurrentHashMap() }.merge(bucketId, kills, Long::plus)
        }
        dirty = true
    }

    /** Bump the "Griffin Burrow dug" counter. Fires once per `"You dug out a Griffin Burrow!"` line. */
    fun grantBurrow(count: Long = 1L) {
        if (count <= 0L) return
        sessionBurrows += count
        totalBurrows += count
        MayorState.currentMayorKey()?.let { mayor ->
            eventBurrowsByMayor.merge(mayor, count, Long::plus)
        }
        dirty = true
    }

    fun resetSession() {
        sessionDrops.clear()
        sessionKills.clear()
        sessionBurrows = 0L
        // The activity timer is shared with the mob HUD — resetting it here is the
        // session's "clean slate" signal so the burrows/hr (+ mobs/hr on the sibling HUD)
        // start fresh. Mirrors what [MythologicalMobTracker.resetSession] already does.
        MythologicalActivityTimer.resetSession()
    }

    fun resetAll() {
        resetSession()
        totalDrops.clear()
        totalKills.clear()
        totalBurrows = 0L
        eventDropsByMayor.clear()
        eventKillsByMayor.clear()
        eventBurrowsByMayor.clear()
        dirty = true
    }

    /**
     * Bulk-import from an external mod's export. OVERWRITES the persisted totals —
     * any prior recorded data is wiped and replaced. Session counters are also cleared.
     * Per-mayor event partitions are NOT touched (Sbo's mayor-bucket schema doesn't map
     * 1:1; the user's current mayor bucket will start fresh going forward).
     *
     * Caller is expected to gate behind a confirmation step.
     */
    fun importFromSbo(
        dropsByBucket: Map<String, Map<String, Long>>,
        totalBurrowsCount: Long,
    ) {
        sessionDrops.clear()
        sessionKills.clear()
        sessionBurrows = 0L
        totalDrops.clear()
        totalKills.clear()
        for ((bucketId, drops) in dropsByBucket) {
            if (bucketId.isEmpty()) continue
            val bucketMap = ConcurrentHashMap<String, Counts>()
            for ((itemId, amount) in drops) {
                if (amount <= 0L || itemId.isEmpty()) continue
                bucketMap[itemId] = Counts(amount = amount)
            }
            if (bucketMap.isNotEmpty()) totalDrops[bucketId] = bucketMap
        }
        totalBurrows = totalBurrowsCount.coerceAtLeast(0L)
        dirty = true
        val dropsAcrossBuckets = totalDrops.values.sumOf { it.size }
        logger.info(
            "Imported from Sbo (overwrite): $dropsAcrossBuckets distinct drops across ${totalDrops.size} buckets, totalBurrows=$totalBurrows",
        )
    }

    /**
     * Burrows for [tab]. Event reads the current mayor term's bucket (0 when no mayor
     * known); Session reads in-memory; Total reads the persisted all-time counter.
     */
    fun totalBurrowsValue(tab: TrackerTab): Long =
        if (tab == TrackerTab.Session) {
            sessionBurrows
        } else if (tab == TrackerTab.Event) {
            MayorState.currentMayorKey()?.let { eventBurrowsByMayor[it] } ?: 0L
        } else {
            totalBurrows
        }

    /**
     * Per-(bucket, itemId) counts for [tab] filtered by [bucketFilter] (empty = all buckets).
     * Defensive copy.
     */
    fun countsFor(
        tab: TrackerTab,
        bucketFilter: Set<String> = emptySet(),
    ): Map<String, Map<String, Counts>> {
        val partition: Map<String, Map<String, Counts>> =
            if (tab == TrackerTab.Session) {
                sessionDrops
            } else if (tab == TrackerTab.Event) {
                MayorState.currentMayorKey()?.let { eventDropsByMayor[it] } ?: emptyMap()
            } else {
                totalDrops
            }
        val out = HashMap<String, Map<String, Counts>>()
        for ((bucket, drops) in partition) {
            if (bucketFilter.isNotEmpty() && bucket !in bucketFilter) continue
            if (drops.isEmpty()) continue
            out[bucket] =
                drops.entries.associate { (id, counts) -> id to Counts(amount = counts.amount) }
        }
        return out
    }

    fun killsFor(
        bucketId: String,
        tab: TrackerTab,
    ): Long {
        val partition: Map<String, Long> =
            if (tab == TrackerTab.Session) {
                sessionKills
            } else if (tab == TrackerTab.Event) {
                MayorState.currentMayorKey()?.let { eventKillsByMayor[it] } ?: emptyMap()
            } else {
                totalKills
            }
        return partition[bucketId] ?: 0L
    }

    /**
     * Sum of `amount × PriceCache.price(priceLookupId, priceSource)` across (bucket, itemId)
     * pairs in [tab] matching [bucketFilter] (empty = all). `COINS` is treated at face value
     * (no PriceCache lookup) since the backend models coins as a synthetic item with no
     * bazaar / AH entry.
     */
    fun profitFor(
        tab: TrackerTab,
        bucketFilter: Set<String> = emptySet(),
        priceSource: PriceSource = PriceSource.BAZAAR_INSTANT_BUY,
        useNpcFloor: Boolean = false,
    ): Long {
        val data = countsFor(tab, bucketFilter)
        var sum = 0L
        for ((_, drops) in data) {
            for ((itemId, counts) in drops) {
                val unit =
                    if (itemId == "COINS") {
                        1L
                    } else {
                        PriceCache.priceWithNpcFloor(
                            DropResolver.priceLookupId(itemId),
                            priceSource,
                            useNpcFloor,
                        )
                    }
                sum += unit * counts.amount
            }
        }
        return sum
    }

    /**
     * Every mythological bucket id surfaced in the HUD's filter dropdown. Union of:
     *  - Sources curated in the backend drop catalog under `mythological` (mob nodes,
     *    treasure burrow, attribute shards, etc.).
     *  - Bucket ids that exist purely client-side because the mod credits them without
     *    backend curation. Today: [LOOTSHARE_BUCKET] — all mobs and all non-sack drops
     *    are lootshare-eligible by definition, so the backend doesn't curate a source
     *    for it.
     *  - Bucket ids that have actual data on disk (covers the offline / pre-first-fetch
     *    case + any tracker-recorded bucket that's not in the catalog).
     */
    fun knownBucketIds(): List<String> {
        val fromCatalog = DropCatalogClient.sourcesByKind("mythological").map { it.id }.filter { it != "mythological" }
        val fromData = (totalDrops.keys + totalKills.keys).distinct()
        // Always include LOOTSHARE_BUCKET so the filter dropdown has it from a fresh
        // install too — the user can filter on lootshare before any lootshare event has
        // actually fired.
        return (fromCatalog + fromData + listOf(LOOTSHARE_BUCKET)).distinct()
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
            root.getAsJsonObject("kills")?.entrySet()?.forEach { (bucket, e) ->
                totalKills[bucket] = e.asLong
            }
            root.getAsJsonObject("drops")?.entrySet()?.forEach { (bucket, dropsElement) ->
                val bucketMap = ConcurrentHashMap<String, Counts>()
                dropsElement.asJsonObject.entrySet().forEach { (itemId, amountElement) ->
                    bucketMap[itemId] = Counts(amount = amountElement.asLong)
                }
                if (bucketMap.isNotEmpty()) totalDrops[bucket] = bucketMap
            }
            totalBurrows = root.get("burrowsTotal")?.asLong ?: 0L
            // v2 fields — absent in v1 files, fall through silently with empty maps.
            root.getAsJsonObject("eventKills")?.entrySet()?.forEach { (mayor, byBucket) ->
                val inner = ConcurrentHashMap<String, Long>()
                byBucket.asJsonObject.entrySet().forEach { (bucket, e) -> inner[bucket] = e.asLong }
                if (inner.isNotEmpty()) eventKillsByMayor[mayor] = inner
            }
            root.getAsJsonObject("eventDrops")?.entrySet()?.forEach { (mayor, byBucket) ->
                val mayorMap = ConcurrentHashMap<String, ConcurrentHashMap<String, Counts>>()
                byBucket.asJsonObject.entrySet().forEach { (bucket, dropsElement) ->
                    val bucketMap = ConcurrentHashMap<String, Counts>()
                    dropsElement.asJsonObject.entrySet().forEach { (itemId, n) ->
                        bucketMap[itemId] = Counts(amount = n.asLong)
                    }
                    if (bucketMap.isNotEmpty()) mayorMap[bucket] = bucketMap
                }
                if (mayorMap.isNotEmpty()) eventDropsByMayor[mayor] = mayorMap
            }
            root.getAsJsonObject("eventBurrows")?.entrySet()?.forEach { (mayor, e) ->
                eventBurrowsByMayor[mayor] = e.asLong
            }
            logger.info(
                "Loaded $SAVE_FILE_NAME: ${totalKills.size} buckets, $totalBurrows burrows, " +
                    "${eventBurrowsByMayor.size} mayor term(s) with event data",
            )
        } catch (e: Exception) {
            logger.warn("Failed to read $SAVE_FILE_NAME — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val killsSnap = totalKills.toMap()
        val dropsSnap =
            totalDrops.entries.associate { (b, drops) -> b to drops.entries.associate { (id, c) -> id to c.amount } }
        val burrowsSnap = totalBurrows
        // Event partitions — snapshot per-mayor maps with defensive copies of all inner maps.
        val eventKillsSnap = eventKillsByMayor.entries.associate { (m, byBucket) -> m to byBucket.toMap() }
        val eventDropsSnap =
            eventDropsByMayor.entries.associate { (m, byBucket) ->
                m to
                    byBucket.entries.associate { (b, drops) ->
                        b to drops.entries.associate { (id, c) -> id to c.amount }
                    }
            }
        val eventBurrowsSnap = eventBurrowsByMayor.toMap()
        val json =
            JsonObject().apply {
                addProperty("schemaVersion", CURRENT_SCHEMA_VERSION)
                val killsBlock = JsonObject()
                killsSnap.forEach { (b, c) -> killsBlock.addProperty(b, c) }
                add("kills", killsBlock)
                val dropsBlock = JsonObject()
                dropsSnap.forEach { (b, drops) ->
                    val inner = JsonObject()
                    drops.forEach { (id, n) -> inner.addProperty(id, n) }
                    dropsBlock.add(b, inner)
                }
                add("drops", dropsBlock)
                addProperty("burrowsTotal", burrowsSnap)
                val evKillsBlock = JsonObject()
                eventKillsSnap.forEach { (mayor, byBucket) ->
                    val mayorObj = JsonObject()
                    byBucket.forEach { (b, c) -> mayorObj.addProperty(b, c) }
                    evKillsBlock.add(mayor, mayorObj)
                }
                add("eventKills", evKillsBlock)
                val evDropsBlock = JsonObject()
                eventDropsSnap.forEach { (mayor, byBucket) ->
                    val mayorObj = JsonObject()
                    byBucket.forEach { (b, drops) ->
                        val inner = JsonObject()
                        drops.forEach { (id, n) -> inner.addProperty(id, n) }
                        mayorObj.add(b, inner)
                    }
                    evDropsBlock.add(mayor, mayorObj)
                }
                add("eventDrops", evDropsBlock)
                val evBurrowsBlock = JsonObject()
                eventBurrowsSnap.forEach { (mayor, c) -> evBurrowsBlock.addProperty(mayor, c) }
                add("eventBurrows", evBurrowsBlock)
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
