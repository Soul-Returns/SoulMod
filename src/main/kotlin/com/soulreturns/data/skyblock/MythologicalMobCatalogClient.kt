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
 * Backend mirror of `GET /mobs/mythological`. Lifecycle and threading match
 * [com.soulreturns.data.items.ItemCatalogClient] / [com.soulreturns.data.drops.DropCatalogClient]:
 * synchronous disk load, async refresh, Mercure invalidate with `kind = "mobs_mythological"`.
 *
 * The chat-regex + flavour-prefix + cocoon-prefix parsing stays in
 * [MythologicalMobCatalog] — backend data is the *mob identity*, parser logic is the
 * *mod's interpretation* of chat lines and doesn't belong in admin-curatable storage.
 */
object MythologicalMobCatalogClient {
    const val INVALIDATE_KIND: String = "mobs_mythological"
    const val ENDPOINT: String = "/mobs/mythological"
    private const val CACHE_FILE: String = "soul/mob_catalog_mythological.json"

    private val logger = SoulLogger("Soul/Mobs")
    private val gson: Gson = GsonBuilder().setPrettyPrinting().serializeNulls().create()
    private val initialized = AtomicBoolean(false)

    @Volatile var snapshot: MythologicalMobSnapshot = MythologicalMobSnapshot.EMPTY
        private set

    private val cacheFile: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), CACHE_FILE)
    }

    fun init() {
        if (!initialized.compareAndSet(false, true)) return
        loadFromDisk()
        Events.subscribe<SyncInvalidate> { event ->
            if (event.kind == INVALIDATE_KIND) {
                logger.info("Realtime invalidate received; refetching mob catalog")
                refreshAsync()
            }
        }
        refreshAsync()
    }

    fun refreshAsync() {
        BackendClient.get(ENDPOINT, intent = "mob-catalog-refresh", bypassCache = true).whenComplete { result, throwable ->
            if (throwable != null) {
                logger.warn("Mob catalog fetch threw: ${throwable.message}", throwable)
                return@whenComplete
            }
            if (result is BackendClient.Result.Ok) {
                val parsed =
                    try {
                        gson.fromJson(result.json, MythologicalMobSnapshot::class.java)
                            ?: MythologicalMobSnapshot.EMPTY
                    } catch (e: Exception) {
                        logger.warn("Mob catalog response failed to deserialize: ${e.message}", e)
                        return@whenComplete
                    }
                snapshot = parsed
                persistToDisk(parsed)
                logger.info("Mob catalog refreshed: ${parsed.mobs.size} mobs, updatedAt=${parsed.updatedAt}")
            } else if (result is BackendClient.Result.Error) {
                logger.info("Mob catalog fetch failed: HTTP ${result.statusCode} ${result.message}")
            }
        }
    }

    private fun loadFromDisk() {
        val file = cacheFile
        if (!file.isFile) {
            logger.info("No mob catalog cache on disk; starting empty")
            return
        }
        try {
            val text = file.readText()
            val parsed = gson.fromJson(text, MythologicalMobSnapshot::class.java) ?: MythologicalMobSnapshot.EMPTY
            snapshot = parsed
            logger.info("Mob catalog loaded from disk: ${parsed.mobs.size} mobs, updatedAt=${parsed.updatedAt}")
        } catch (e: Exception) {
            logger.warn("Failed to read mob catalog cache; ignoring: ${e.message}", e)
        }
    }

    private fun persistToDisk(snapshot: MythologicalMobSnapshot) {
        SoulExecutor.executor.execute {
            try {
                val file = cacheFile
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, file.name + ".tmp")
                tmp.writeText(gson.toJson(snapshot))
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } catch (e: Exception) {
                logger.warn("Failed to persist mob catalog cache; in-memory snapshot still good: ${e.message}", e)
            }
        }
    }
}

/** One row in the backend `/mobs/mythological` response. */
data class MythologicalMobEntry(
    val id: String,
    val displayName: String,
    val rarity: String,
    val sortOrder: Int = 0,
)

data class MythologicalMobSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val mobs: List<MythologicalMobEntry> = emptyList(),
) {
    companion object {
        val EMPTY: MythologicalMobSnapshot = MythologicalMobSnapshot()
    }
}
