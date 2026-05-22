package com.soulreturns.data.fishing

import com.google.gson.Gson
import com.google.gson.GsonBuilder
import com.soulreturns.core.events.Events
import com.soulreturns.platform.concurrent.SoulExecutor
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.realtime.SyncInvalidate
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Backend mirror of `GET /sea-creatures`. Lifecycle mirrors the other catalog clients —
 * disk cache → async refresh → Mercure invalidate `kind = "sea_creatures"`.
 *
 * Replaces the old `assets/soul/sea_creatures.json` bundled snapshot; the consumer-facing
 * [SeaCreatureCatalog] now reads its in-memory maps from this client's snapshot. Chat-line
 * parsing logic stays in [SeaCreatureCatalog] — backend data is just creature identity.
 */
object SeaCreatureCatalogClient {
    const val INVALIDATE_KIND: String = "sea_creatures"
    const val ENDPOINT: String = "/sea-creatures"
    private const val CACHE_FILE: String = "soul/sea_creature_catalog.json"

    private val logger = SoulLogger("Soul/Fishing")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val initialized = AtomicBoolean(false)

    @Volatile var snapshot: SeaCreatureCatalogSnapshot = SeaCreatureCatalogSnapshot.EMPTY
        private set

    /**
     * Listeners to fire whenever the snapshot is replaced. [SeaCreatureCatalog] hooks here
     * to rebuild its `byCleanMessage` + `byName` maps; this avoids each lookup re-walking
     * the entries list (which is a hot path during fishing chat parsing).
     */
    private val onSnapshotChange: MutableList<() -> Unit> = java.util.concurrent.CopyOnWriteArrayList()

    fun addListener(listener: () -> Unit) {
        onSnapshotChange.add(listener)
    }

    private val cacheFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), CACHE_FILE)
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { event ->
            if (event.kind == INVALIDATE_KIND) {
                logger.info("Realtime invalidate received; refetching sea-creature catalog")
                refreshAsync()
            }
        }
        refreshAsync()
    }

    fun refreshAsync() {
        BackendClient.get(ENDPOINT, intent = "sea-creature-catalog-refresh", bypassCache = true).whenComplete { result, throwable ->
            if (throwable != null) {
                logger.warn("Sea-creature catalog fetch threw: ${throwable.message}", throwable)
                return@whenComplete
            }
            if (result is BackendClient.Result.Ok) {
                val parsed =
                    try {
                        gson.fromJson(result.json, SeaCreatureCatalogSnapshot::class.java)
                            ?: SeaCreatureCatalogSnapshot.EMPTY
                    } catch (e: Exception) {
                        logger.warn("Sea-creature catalog response failed to deserialize: ${e.message}", e)
                        return@whenComplete
                    }
                applyAndNotify(parsed)
                persistToDisk(parsed)
                logger.info("Sea-creature catalog refreshed: ${parsed.creatures.size} creatures, updatedAt=${parsed.updatedAt}")
            } else if (result is BackendClient.Result.Error) {
                logger.info("Sea-creature catalog fetch failed: HTTP ${result.statusCode} ${result.message}")
            }
        }
    }

    private fun applyAndNotify(parsed: SeaCreatureCatalogSnapshot) {
        snapshot = parsed
        onSnapshotChange.forEach { listener ->
            try {
                listener()
            } catch (e: Exception) {
                logger.warn("Sea-creature snapshot listener threw: ${e.message}", e)
            }
        }
    }

    private fun loadFromDisk() {
        val file = cacheFile
        if (!file.isFile) {
            logger.info("No sea-creature catalog cache on disk; starting empty")
            return
        }
        try {
            val text = file.readText()
            val parsed = gson.fromJson(text, SeaCreatureCatalogSnapshot::class.java) ?: SeaCreatureCatalogSnapshot.EMPTY
            applyAndNotify(parsed)
            logger.info(
                "Sea-creature catalog loaded from disk: ${parsed.creatures.size} creatures, updatedAt=${parsed.updatedAt}",
            )
        } catch (e: Exception) {
            logger.warn("Failed to read sea-creature catalog cache; ignoring: ${e.message}", e)
        }
    }

    private fun persistToDisk(snapshot: SeaCreatureCatalogSnapshot) {
        SoulExecutor.executor.execute {
            try {
                val file = cacheFile
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                logger.warn("Failed to persist sea-creature catalog cache: ${e.message}", e)
            }
        }
    }

    /**
     * Internal helper: strip color codes + trim, matching what
     * [SeaCreatureCatalog.match] does on incoming chat lines. Exposed here for the
     * catalog's index-build pass.
     */
    fun cleanMessage(raw: String): String = MessageDetector.stripColorCodes(raw).trim()
}

/** One row in the backend `/sea-creatures` response. */
data class SeaCreatureEntry(
    val id: String,
    val variant: String,
    val displayName: String,
    val chatMessage: String,
    val alternateMessages: List<String> = emptyList(),
    val rarity: String,
    val rare: Boolean = false,
    val fishingExperience: Int? = null,
    val sortOrder: Int = 0,
)

data class SeaCreatureCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val creatures: List<SeaCreatureEntry> = emptyList(),
) {
    companion object {
        val EMPTY: SeaCreatureCatalogSnapshot = SeaCreatureCatalogSnapshot()
    }
}
