package com.soulreturns.stats

import com.google.gson.GsonBuilder
import com.soulreturns.api.SoulExecutor
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.loader.api.FabricLoader
import java.io.File
import java.io.FileReader

/**
 * Generic key/value persistence for tracked numeric stats (seasonings collected, kills, etc.).
 *
 * Stored at `config/soul/stats.json`. Mutations go through [update] on whatever thread; the
 * dirty flag is checked once per tick and a write to disk is enqueued on [SoulExecutor]
 * (off the render thread). Add new tracked values as fields on [Data].
 */
object PersistentStats {
    private val logger = SoulLogger("Soul/Stats")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/stats.json")
    }

    /** All persisted stats live here. Adding a `var x: Long = 0L` field is a no-migration extension. */
    data class Data(
        var seasonings: Long = 0L,
        /** Sorted ascending Y values from the Harvest Feast milestones (e.g. `[5, 25, 75, 150, 250]`). */
        var milestoneTargets: List<Long> = emptyList()
    )

    @Volatile private var data: Data = Data()
    @Volatile private var dirty: Boolean = false
    @Volatile private var saving: Boolean = false

    fun init() {
        load()
        // Persist no more than once per second; coalesces bursts of mutations into one write.
        var lastSaveTick = 0
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { _ ->
            lastSaveTick++
            if (dirty && !saving && lastSaveTick >= 20) {
                lastSaveTick = 0
                saveAsync()
            }
        })
    }

    val current: Data get() = data

    /** Mutate the stats. Marks dirty so the next tick triggers a save. */
    inline fun update(block: Data.() -> Unit) {
        synchronized(this) {
            current.block()
            markDirty()
        }
    }

    @PublishedApi internal fun markDirty() { dirty = true }

    private fun load() {
        if (!file.exists()) {
            logger.info("No stats file at ${file.absolutePath}; starting from defaults.")
            return
        }
        try {
            FileReader(file).use { reader ->
                val loaded = gson.fromJson(reader, Data::class.java)
                if (loaded != null) data = loaded
            }
        } catch (e: Exception) {
            logger.warn("Failed to read stats.json — using defaults", e)
        }
    }

    private fun saveAsync() {
        saving = true
        dirty = false
        val snapshot = synchronized(this) { data.copy() }
        SoulExecutor.executor.submit {
            try {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                tmp.writeText(gson.toJson(snapshot))
                if (file.exists()) file.delete()
                if (!tmp.renameTo(file)) {
                    logger.warn("Could not rename ${tmp.name} → ${file.name}; falling back to direct write")
                    file.writeText(gson.toJson(snapshot))
                    tmp.delete()
                }
            } catch (e: Exception) {
                logger.warn("Failed to persist stats.json", e)
                dirty = true // retry next tick
            } finally {
                saving = false
            }
        }
    }
}
