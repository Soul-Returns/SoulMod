package com.soulreturns.data.drops

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.soulreturns.core.events.Events
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.realtime.SyncInvalidate
import com.soulreturns.util.SoulLogger
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory mirror of the backend drop graph (`GET /drops`). Follows the same lifecycle
 * shape as [com.soulreturns.data.items.ItemCatalogClient]:
 *
 * 1. [init] runs once during `Soul.registerFeatures()`, loads the disk cache synchronously
 *    (so lookups return useful data even before the network round-trip), subscribes to
 *    `SyncInvalidate(kind = "drops")`, and kicks off an async refresh.
 * 2. [refreshAsync] fetches `GET /drops` via [BackendClient]. On success the new snapshot
 *    is built, the volatile references are swapped, and the JSON is persisted to disk.
 *    On any failure the existing snapshot is preserved untouched.
 * 3. Lookups are lock-free reads of volatile maps — safe from any thread.
 *
 * **What the mod uses this for:**
 *  - Dragon profit tracker resolves `DragonType.PROTECTOR` → source `"dragon.protector"`
 *    → list of [DropEntry]s for that dragon, joining `itemId` against
 *    [com.soulreturns.data.items.ItemCatalogClient] for display name + rarity + price.
 *  - Mythological profit tracker (forthcoming) does the same with `mythological.<mob>`
 *    sources and the `mythological.treasure_burrow` source.
 *  - Loot-stand reverse-lookup (DragonLootScanner) walks every active drop's
 *    [DropEntry.displayNameOverride] / catalog display name to find a match.
 *
 * **No `/drops` toggle.** Same reasoning as `ItemCatalogClient` — foundational data; the
 * empty case (no cache, offline) is handled at the lookup layer.
 */
object DropCatalogClient {
    const val INVALIDATE_KIND: String = "drops"
    const val ENDPOINT: String = "/drops"
    private const val CACHE_FILE: String = "soul/drop_catalog.json"
    private const val SCHEMA_VERSION: Int = 1

    private val logger = SoulLogger("Soul/Drops")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val initialized = AtomicBoolean(false)

    @Volatile private var current: DropCatalogSnapshot = DropCatalogSnapshot.EMPTY

    /** Source id → entry. Rebuilt atomically on every snapshot swap. */
    @Volatile private var sourcesById: Map<String, DropSourceEntry> = emptyMap()

    /**
     * Source id → drops dropping from that source. The list is the read-side surface for
     * the profit trackers; building it once at snapshot time saves O(n) scans per HUD frame.
     */
    @Volatile private var dropsBySource: Map<String, List<DropEntry>> = emptyMap()

    /**
     * (Source id, item id) → drop. Lets the loot-scanner reverse-lookup a per-source drop
     * quickly when the display-name match isn't unique across sources.
     */
    @Volatile private var dropsByCompositeKey: Map<Pair<String, String>, DropEntry> = emptyMap()

    private val cacheFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), CACHE_FILE)
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { event ->
            if (event.kind == INVALIDATE_KIND) {
                logger.info("Realtime invalidate received; refetching drop catalog")
                refreshAsync()
            }
        }
        refreshAsync()
    }

    fun sourceById(id: String): DropSourceEntry? = sourcesById[id]

    /**
     * Drops dropping from [sourceId]. Empty when the source has no drops yet (mythological
     * mob nodes start empty until an admin fills them in). Order is the backend's natural
     * iteration order — don't depend on it for sorting; HUDs apply their own sorts.
     */
    fun dropsFrom(sourceId: String): List<DropEntry> = dropsBySource[sourceId] ?: emptyList()

    fun drop(
        sourceId: String,
        itemId: String,
    ): DropEntry? = dropsByCompositeKey[sourceId to itemId]

    /** Every source whose `kind` matches [kind]. Used by trackers iterating a domain (`"dragon"`, `"mythological"`). */
    fun sourcesByKind(kind: String): List<DropSourceEntry> = sourcesById.values.filter { it.kind == kind }

    /** Children of [parentId]. */
    fun childrenOf(parentId: String): List<DropSourceEntry> = sourcesById.values.filter { it.parentId == parentId }

    fun snapshot(): DropCatalogSnapshot = current

    /** Diagnostic line for `/soul dev` commands / startup logging. */
    fun status(): String {
        val s = current
        return "DropCatalog: ${s.sources.size} sources, ${s.drops.size} drops, updatedAt=${s.updatedAt}"
    }

    fun refreshAsync() {
        // bypassCache = true — see ItemCatalogClient for the rationale.
        BackendClient.get(ENDPOINT, intent = "drop-catalog-refresh", bypassCache = true).whenComplete { result, throwable ->
            if (throwable != null) {
                logger.warn("Drop catalog fetch threw: ${throwable.message}", throwable)
                return@whenComplete
            }
            // `if (result is …)` chain rather than `when (result)` — BackendClient.Result is
            // a sealed class, and CLAUDE.md flags `when (sealedClass)` as the synthetic
            // `$WhenMappings` trap that Fabric's KnotClassLoader can fail to resolve.
            if (result is BackendClient.Result.Ok) {
                val parsed =
                    try {
                        gson.fromJson(result.json, DropCatalogSnapshot::class.java)
                            ?: DropCatalogSnapshot.EMPTY
                    } catch (e: Exception) {
                        logger.warn("Drop catalog response failed to deserialize: ${e.message}", e)
                        return@whenComplete
                    }
                apply(parsed)
                persistToDisk(parsed)
                logger.info(
                    "Drop catalog refreshed: ${parsed.sources.size} sources, " +
                        "${parsed.drops.size} drops, updatedAt=${parsed.updatedAt}",
                )
            } else if (result is BackendClient.Result.Error) {
                logger.info("Drop catalog fetch failed: HTTP ${result.statusCode} ${result.message}")
            }
        }
    }

    private fun apply(parsed: DropCatalogSnapshot) {
        val sourceMap = HashMap<String, DropSourceEntry>(parsed.sources.size)
        for (s in parsed.sources) {
            if (s.id.isEmpty()) continue
            sourceMap[s.id] = s
        }
        val bySource = HashMap<String, MutableList<DropEntry>>()
        val byComposite = HashMap<Pair<String, String>, DropEntry>(parsed.drops.size)
        for (d in parsed.drops) {
            if (d.sourceId.isEmpty() || d.itemId.isEmpty()) continue
            bySource.getOrPut(d.sourceId) { ArrayList() }.add(d)
            byComposite[d.sourceId to d.itemId] = d
        }
        sourcesById = sourceMap
        dropsBySource = bySource.mapValues { (_, list) -> list.toList() }
        dropsByCompositeKey = byComposite
        current = parsed
    }

    private fun loadFromDisk() {
        val file = cacheFile
        if (!file.isFile) {
            logger.info("No drop catalog cache on disk; starting empty")
            return
        }
        try {
            val text = file.readText()
            val parsed = gson.fromJson(text, DropCatalogSnapshot::class.java) ?: DropCatalogSnapshot.EMPTY
            apply(parsed)
            logger.info(
                "Drop catalog loaded from disk: ${parsed.sources.size} sources, " +
                    "${parsed.drops.size} drops, updatedAt=${parsed.updatedAt}",
            )
        } catch (e: Exception) {
            logger.warn("Failed to read drop catalog cache; ignoring: ${e.message}", e)
        }
    }

    private fun persistToDisk(snapshot: DropCatalogSnapshot) {
        SoulExecutor.executor.execute {
            try {
                val file = cacheFile
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                logger.warn("Failed to persist drop catalog cache; in-memory snapshot still good: ${e.message}", e)
            }
        }
    }

    internal fun resetForTests() {
        initialized.set(false)
        current = DropCatalogSnapshot.EMPTY
        sourcesById = emptyMap()
        dropsBySource = emptyMap()
        dropsByCompositeKey = emptyMap()
    }

    fun schemaVersion(): Int = SCHEMA_VERSION
}
