package com.soulreturns.data.items

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
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * In-memory mirror of the backend SkyBlock item catalog (`GET /items`). Loaded from a
 * local cache file at startup, refreshed from the backend asynchronously, and re-fetched
 * whenever the backend publishes a `sync-invalidate` event with `kind = "items"`.
 *
 * **Lifecycle:**
 * 1. [init] runs once during `Soul.registerFeatures()`. It loads the on-disk cache
 *    synchronously so [byId] / [byDisplayName] return useful data even before the
 *    network round-trip completes, subscribes to [SyncInvalidate] for the `"items"`
 *    kind, and kicks off an async refresh.
 * 2. [refreshAsync] fetches `GET /items` via [BackendClient]. On success, the new
 *    snapshot is built, the volatile reference is swapped, and the JSON is persisted
 *    to disk for the next session. On any failure (network, auth, malformed JSON) the
 *    existing snapshot is preserved untouched.
 * 3. Mercure invalidate → same refresh path. The mod's `RealtimeClient` publishes
 *    [SyncInvalidate] events for every backend kind; we filter on `event.kind ==
 *    "items"` and ignore the rest (the wider `SyncEngine` does the same for its
 *    `SyncKind` enum).
 *
 * **Threading model.** Everything that mutates state runs on `SoulExecutor` (the same
 * 2-thread pool `BackendClient` dispatches its callbacks through). Lookups ([byId] /
 * [byDisplayName]) read volatile snapshots from any thread without locks — readers
 * always see a consistent set of maps because the new instances are populated fully
 * before being assigned.
 *
 * **No `/items` toggle.** This is foundational data, not a sync-paired-with-PUT artifact
 * like the entries in [com.soulreturns.platform.sync.SyncKind]. There's no master toggle
 * to gate it behind — when the user is offline or unauthenticated the catalog is simply
 * empty (or cached from a prior session) and callers handle null lookups gracefully.
 */
object ItemCatalogClient {
    const val INVALIDATE_KIND: String = "items"
    const val ENDPOINT: String = "/items"
    private const val CACHE_FILE: String = "soul/item_catalog.json"
    private const val SCHEMA_VERSION: Int = 1

    private val logger = SoulLogger("Soul/Items")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val initialized = AtomicBoolean(false)

    @Volatile private var current: ItemCatalogSnapshot = ItemCatalogSnapshot.EMPTY

    /** id → entry. Rebuilt atomically on every snapshot swap. */
    @Volatile private var byId: Map<String, ItemCatalogEntry> = emptyMap()

    /**
     * Lowercased display-name → entry. Lowercased because chat tooltip parsers see the
     * exact display-text and we want a stable lookup regardless of any client-side case
     * normalization. Hypixel's catalog has ~450 display-name collisions (cosmetic
     * variants, builder duplicates, tiered accessories) — those are resolved at
     * snapshot-build time via [pickCanonical] so this map holds the canonical entry
     * for each name. Total collision count is summarized in one log line per refresh.
     */
    @Volatile private var byDisplayName: Map<String, ItemCatalogEntry> = emptyMap()

    private val cacheFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), CACHE_FILE)
    }

    /**
     * Wire up the catalog. Safe to call multiple times — the first call performs the
     * disk load + subscribes to invalidates + starts the initial fetch; later calls
     * are no-ops.
     */
    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { event ->
            if (event.kind == INVALIDATE_KIND) {
                logger.info("Realtime invalidate received; refetching catalog")
                refreshAsync()
            }
        }
        refreshAsync()
    }

    fun byId(id: String): ItemCatalogEntry? = byId[id]

    fun byDisplayName(name: String): ItemCatalogEntry? = byDisplayName[name.lowercase(Locale.ROOT)]

    fun all(): List<ItemCatalogEntry> = current.items

    fun snapshot(): ItemCatalogSnapshot = current

    /** Diagnostic line for future dev commands / startup logging. */
    fun status(): String {
        val s = current
        return "ItemCatalog: ${s.items.size} items, updatedAt=${s.updatedAt}"
    }

    /**
     * Fetch the catalog from the backend on `SoulExecutor` and replace the in-memory
     * snapshot + disk cache on success. Errors are logged and swallowed — the existing
     * snapshot stays intact so a transient backend hiccup doesn't wipe the catalog out
     * from under the rest of the mod.
     */
    fun refreshAsync() {
        BackendClient.get(ENDPOINT, intent = "item-catalog-refresh").whenComplete { result, throwable ->
            if (throwable != null) {
                logger.warn("Catalog fetch threw: ${throwable.message}", throwable)
                return@whenComplete
            }
            // `if (result is …)` chain rather than `when (result)` — BackendClient.Result is
            // a sealed class, and CLAUDE.md flags `when (sealedClass)` as the synthetic
            // $WhenMappings trap that Fabric's KnotClassLoader can fail to resolve at
            // runtime.
            if (result is BackendClient.Result.Ok) {
                val parsed =
                    try {
                        gson.fromJson(result.json, ItemCatalogSnapshot::class.java)
                            ?: ItemCatalogSnapshot.EMPTY
                    } catch (e: Exception) {
                        logger.warn("Catalog response failed to deserialize: ${e.message}", e)
                        return@whenComplete
                    }
                apply(parsed)
                persistToDisk(parsed)
                logger.info("Catalog refreshed: ${parsed.items.size} items, updatedAt=${parsed.updatedAt}")
            } else if (result is BackendClient.Result.Error) {
                logger.info("Catalog fetch failed: HTTP ${result.statusCode} ${result.message}")
            }
        }
    }

    private fun apply(parsed: ItemCatalogSnapshot) {
        val items = parsed.items
        val idMap = HashMap<String, ItemCatalogEntry>(items.size)
        val nameMap = HashMap<String, ItemCatalogEntry>(items.size)
        var collisions = 0
        for (entry in items) {
            if (entry.id.isEmpty() || entry.displayName.isEmpty()) continue
            idMap[entry.id] = entry
            val nameKey = entry.displayName.lowercase(Locale.ROOT)
            val existing = nameMap[nameKey]
            if (existing == null) {
                nameMap[nameKey] = entry
            } else if (existing.id != entry.id) {
                collisions++
                nameMap[nameKey] = pickCanonical(existing, entry)
            }
        }
        byId = idMap
        byDisplayName = nameMap
        current = parsed
        if (collisions > 0) {
            logger.info("Indexed ${items.size} items; $collisions display-name collisions resolved")
        } else {
            logger.info("Indexed ${items.size} items; no display-name collisions")
        }
    }

    /**
     * Pick the canonical entry when two items share a display name. Hypixel's catalog
     * has ~450 collisions — mostly cosmetic variants (color-prefixed backpacks, tiered
     * anniversary podiums) that are interchangeable for our purposes, but some pairs
     * matter: `APPLE` vs `BUILDER_APPLE` both render as "Apple", and a naive
     * alphabetical-first pick can keep the wrong one (e.g. `BUILDER_CACTUS` < `CACTUS`).
     *
     * Preference order (first decisive rule wins):
     *  1. **Id matches the canonical-form display name.** "Apple" → `APPLE`,
     *     "Brown Mushroom" → `BROWN_MUSHROOM`. If exactly one candidate's id equals
     *     the uppercased+`_`-joined display name, it wins. This handles the chat-
     *     translation case most reliably — the chat tooltip says "+72 Ender Pearl",
     *     and we want `ENDER_PEARL`, not some variant.
     *  2. **Drop `BUILDER_*` prefixes.** Creative-mode building blocks that share
     *     names with real items but never appear in chat / inventory contexts.
     *  3. **Drop placeholder suffixes** (`_ITEM`, `_NO_CHARGES`, `_UNCRAFTED`):
     *     `CAULDRON_ITEM` is the held-item form of the block; the bare `CAULDRON`
     *     is what we want for lookups.
     *  4. **Drop numeric suffixes** (`_2`, `_3`, …): `ASPECT_OF_THE_LEECH_1` wins
     *     over `_2`/`_3` because shorter id = older form = usually canonical.
     *  5. **Shorter id wins.** Falls through to length comparison for the remaining
     *     cosmetic-variant cases (backpack colors, podium variants) where any pick
     *     is fine.
     *  6. **Alphabetical tie-break.** Stable, deterministic output.
     */
    private fun pickCanonical(
        a: ItemCatalogEntry,
        b: ItemCatalogEntry,
    ): ItemCatalogEntry {
        val nameKey =
            a.displayName.uppercase(Locale.ROOT)
                .replace(Regex("[^A-Z0-9]+"), "_")
                .trim('_')
        val aExact = a.id == nameKey
        val bExact = b.id == nameKey
        if (aExact != bExact) return if (aExact) a else b

        val aBuilder = a.id.startsWith("BUILDER_")
        val bBuilder = b.id.startsWith("BUILDER_")
        if (aBuilder != bBuilder) return if (aBuilder) b else a

        val placeholderSuffix = Regex("_(ITEM|NO_CHARGES|UNCRAFTED)$")
        val aPlaceholder = placeholderSuffix.containsMatchIn(a.id)
        val bPlaceholder = placeholderSuffix.containsMatchIn(b.id)
        if (aPlaceholder != bPlaceholder) return if (aPlaceholder) b else a

        val numericSuffix = Regex("_\\d+$")
        val aNum = numericSuffix.containsMatchIn(a.id)
        val bNum = numericSuffix.containsMatchIn(b.id)
        if (aNum != bNum) return if (aNum) b else a

        if (a.id.length != b.id.length) return if (a.id.length < b.id.length) a else b
        return if (a.id <= b.id) a else b
    }

    private fun loadFromDisk() {
        val file = cacheFile
        if (!file.isFile) {
            logger.info("No catalog cache on disk; starting empty")
            return
        }
        try {
            val text = file.readText()
            val parsed = gson.fromJson(text, ItemCatalogSnapshot::class.java) ?: ItemCatalogSnapshot.EMPTY
            apply(parsed)
            logger.info("Catalog loaded from disk: ${parsed.items.size} items, updatedAt=${parsed.updatedAt}")
        } catch (e: Exception) {
            logger.warn("Failed to read catalog cache; ignoring: ${e.message}", e)
        }
    }

    private fun persistToDisk(snapshot: ItemCatalogSnapshot) {
        SoulExecutor.executor.execute {
            try {
                val file = cacheFile
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                logger.warn("Failed to persist catalog cache; in-memory snapshot still good: ${e.message}", e)
            }
        }
    }

    /** Reset for tests. */
    internal fun resetForTests() {
        initialized.set(false)
        current = ItemCatalogSnapshot.EMPTY
        byId = emptyMap()
        byDisplayName = emptyMap()
    }

    /** Schema version constant exposed for tests / future migration checks. */
    fun schemaVersion(): Int = SCHEMA_VERSION
}
