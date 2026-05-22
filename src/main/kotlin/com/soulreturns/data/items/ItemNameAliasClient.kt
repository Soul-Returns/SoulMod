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
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend mirror of `GET /aliases`. Curated string → `Item.id` mapping for chat parsers
 * that need to translate display names Hypixel uses in chat into canonical item ids.
 *
 * **Lookup order** consumers should follow (see [ItemNameResolver]):
 *  1. Per-source `Drop.displayNameOverride` (for drop-graph contexts; covers loot stands).
 *  2. The alias table (cross-context: sack chat, scoreboard, future profit announcers).
 *  3. `ItemCatalogClient.byDisplayName(...)` 1:1 against the Hypixel catalog name.
 *
 * Lifecycle + threading model mirror the other catalog clients — disk cache, async
 * refresh, Mercure invalidate `kind = "aliases"`.
 */
object ItemNameAliasClient {
    const val INVALIDATE_KIND: String = "aliases"
    const val ENDPOINT: String = "/aliases"
    private const val CACHE_FILE: String = "soul/item_name_aliases.json"

    private val logger = SoulLogger("Soul/Aliases")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val initialized = AtomicBoolean(false)

    @Volatile private var current: ItemNameAliasSnapshot = ItemNameAliasSnapshot.EMPTY

    /** alias string → item id, case-sensitive (matching the backend's storage). */
    @Volatile private var byAlias: Map<String, String> = emptyMap()

    private val cacheFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), CACHE_FILE)
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { event ->
            if (event.kind == INVALIDATE_KIND) {
                logger.info("Realtime invalidate received; refetching alias table")
                refreshAsync()
            }
        }
        refreshAsync()
    }

    /** Resolve [alias] to an item id. Returns null when no admin has mapped it. */
    fun resolve(alias: String): String? = byAlias[alias]

    fun snapshot(): ItemNameAliasSnapshot = current

    fun refreshAsync() {
        BackendClient.get(ENDPOINT, intent = "alias-table-refresh", bypassCache = true).whenComplete { result, throwable ->
            if (throwable != null) {
                logger.warn("Alias table fetch threw: ${throwable.message}", throwable)
                return@whenComplete
            }
            if (result is BackendClient.Result.Ok) {
                val parsed =
                    try {
                        gson.fromJson(result.json, ItemNameAliasSnapshot::class.java) ?: ItemNameAliasSnapshot.EMPTY
                    } catch (e: Exception) {
                        logger.warn("Alias table response failed to deserialize: ${e.message}", e)
                        return@whenComplete
                    }
                apply(parsed)
                persistToDisk(parsed)
                logger.info("Alias table refreshed: ${parsed.aliases.size} aliases, updatedAt=${parsed.updatedAt}")
            } else if (result is BackendClient.Result.Error) {
                logger.info("Alias table fetch failed: HTTP ${result.statusCode} ${result.message}")
            }
        }
    }

    private fun apply(parsed: ItemNameAliasSnapshot) {
        val index = HashMap<String, String>(parsed.aliases.size)
        for (entry in parsed.aliases) {
            if (entry.alias.isEmpty() || entry.itemId.isEmpty()) continue
            index[entry.alias] = entry.itemId
        }
        byAlias = index
        current = parsed
    }

    private fun loadFromDisk() {
        val file = cacheFile
        if (!file.isFile) {
            logger.info("No alias-table cache on disk; starting empty")
            return
        }
        try {
            val text = file.readText()
            val parsed = gson.fromJson(text, ItemNameAliasSnapshot::class.java) ?: ItemNameAliasSnapshot.EMPTY
            apply(parsed)
            logger.info("Alias table loaded from disk: ${parsed.aliases.size} aliases, updatedAt=${parsed.updatedAt}")
        } catch (e: Exception) {
            logger.warn("Failed to read alias-table cache; ignoring: ${e.message}", e)
        }
    }

    private fun persistToDisk(snapshot: ItemNameAliasSnapshot) {
        SoulExecutor.executor.execute {
            try {
                val file = cacheFile
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                logger.warn("Failed to persist alias-table cache: ${e.message}", e)
            }
        }
    }
}

data class ItemNameAliasEntry(
    val alias: String,
    val itemId: String,
    val context: String? = null,
)

data class ItemNameAliasSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val aliases: List<ItemNameAliasEntry> = emptyList(),
) {
    companion object {
        val EMPTY: ItemNameAliasSnapshot = ItemNameAliasSnapshot()
    }
}

/**
 * Two-step name → item id resolver. Used by chat parsers that see arbitrary display
 * strings and need a canonical id for stats / profit / lookup. The drop-graph case has
 * its own per-source override in [com.soulreturns.data.drops.DropResolver].
 */
object ItemNameResolver {
    /**
     * Resolve a display name to an item id. Tries the alias table first (curated, case-
     * sensitive), then falls back to the item catalog's case-insensitive display-name
     * lookup. Returns null when neither has a match — caller logs + skips.
     */
    fun resolve(displayName: String): String? {
        if (displayName.isEmpty()) return null
        ItemNameAliasClient.resolve(displayName)?.let { return it }
        return ItemCatalogClient.byDisplayName(displayName)?.id
    }
}
