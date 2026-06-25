package com.soulreturns.data.skyblock

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
 * Backend mirror of `GET /diana/stats`. Lifecycle and threading match
 * [MythologicalMobCatalogClient]: synchronous disk load on init, async refresh, Mercure
 * invalidate with `kind = "diana_stats"`.
 *
 * Stores the **row definitions** (what to count + what resets the counters); the actual
 * counter state lives in `features/diana/MythologicalStatsTracker`. Two variants are
 * encoded in the response by [DianaStatRowEntry.rowType] — `mob_item` or
 * `any_mobs_since_mob` — and the consumer dispatches.
 */
object DianaStatsCatalogClient {
    const val INVALIDATE_KIND: String = "diana_stats"
    const val ENDPOINT: String = "/diana/stats"
    private const val CACHE_FILE: String = "soul/diana_stats_catalog.json"

    private val logger = SoulLogger("Soul/Diana")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val initialized = AtomicBoolean(false)

    @Volatile var snapshot: DianaStatsSnapshot = DianaStatsSnapshot.EMPTY
        private set

    private val cacheFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), CACHE_FILE)
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { event ->
            if (event.kind == INVALIDATE_KIND) {
                logger.info("Realtime invalidate received; refetching diana stats catalog")
                refreshAsync()
            }
        }
        refreshAsync()
    }

    fun refreshAsync() {
        BackendClient.get(ENDPOINT, intent = "diana-stats-catalog-refresh", bypassCache = true)
            .whenComplete { result, throwable ->
                if (throwable != null) {
                    logger.warn("Diana stats catalog fetch threw: ${throwable.message}", throwable)
                    return@whenComplete
                }
                if (result is BackendClient.Result.Ok) {
                    val parsed =
                        try {
                            gson.fromJson(result.json, DianaStatsSnapshot::class.java) ?: DianaStatsSnapshot.EMPTY
                        } catch (e: Exception) {
                            logger.warn("Diana stats catalog response failed to deserialize: ${e.message}", e)
                            return@whenComplete
                        }
                    snapshot = parsed
                    persistToDisk(parsed)
                    logger.info("Diana stats catalog refreshed: ${parsed.rows.size} rows, updatedAt=${parsed.updatedAt}")
                } else if (result is BackendClient.Result.Error) {
                    logger.info("Diana stats catalog fetch failed: HTTP ${result.statusCode} ${result.message}")
                }
            }
    }

    private fun loadFromDisk() {
        val file = cacheFile
        if (!file.isFile) {
            logger.info("No diana stats catalog cache on disk; starting empty")
            return
        }
        try {
            val text = file.readText()
            val parsed = gson.fromJson(text, DianaStatsSnapshot::class.java) ?: DianaStatsSnapshot.EMPTY
            snapshot = parsed
            logger.info("Diana stats catalog loaded from disk: ${parsed.rows.size} rows, updatedAt=${parsed.updatedAt}")
        } catch (e: Exception) {
            logger.warn("Failed to read diana stats catalog cache; ignoring: ${e.message}", e)
        }
    }

    private fun persistToDisk(snapshot: DianaStatsSnapshot) {
        SoulExecutor.executor.execute {
            try {
                val file = cacheFile
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                Files.move(
                    tmp.toPath(),
                    file.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE,
                )
            } catch (e: Exception) {
                logger.warn("Failed to persist diana stats catalog cache; in-memory snapshot still good: ${e.message}", e)
            }
        }
    }
}

/**
 * One row in the backend `/diana/stats` response. Fields not relevant to the row's
 * [rowType] are nullable / ignored — see [DianaStatsCatalogClient]'s class kdoc and
 * `MythologicalStatsTracker.rowsFromCatalog` for dispatch.
 *
 * [labelColor] / [valueColor] are signed 32-bit ARGB. Null means "use the mod's default"
 * — the mod doesn't impose a baseline on what the default is; that's a render-time
 * decision so admins can leave colors unset and get whichever shade the mod ships with.
 */
data class DianaStatRowEntry(
    val id: String,
    val rowType: String,
    val label: String,
    val mobName: String,
    val itemId: String? = null,
    val showLootshareColumn: Boolean = true,
    val sortOrder: Int = 0,
    val labelColor: Int? = null,
    val valueColor: Int? = null,
)

data class DianaStatsSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val rows: List<DianaStatRowEntry> = emptyList(),
) {
    companion object {
        val EMPTY: DianaStatsSnapshot = DianaStatsSnapshot()
    }
}
