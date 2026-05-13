package com.soulreturns.platform.sync

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.io.File

/**
 * Sidecar metadata for [SyncEngine]. Persisted at `config/soul/sync_meta.json`.
 *
 * Records, per [SyncKind]:
 *   - `pushedHash`: SHA-256 hex of the file contents last successfully PUT to the backend.
 *     If the current on-disk hash differs from this, we have unpushed local changes
 *     (i.e. the previous session pushed and crashed, or the backend was unreachable last time).
 *   - `syncedAt`: server-reported `updatedAt` (epoch ms) at the last reconcile. Used to detect
 *     "remote is fresher" — i.e. another session on a different machine pushed an update.
 */
internal object SyncMetadata {
    private const val SCHEMA_VERSION = 1

    private val gson = GsonBuilder().setPrettyPrinting().create()

    data class KindMeta(
        var pushedHash: String = "",
        var syncedAt: Long = 0L,
    )

    private data class Storage(
        var version: Int = SCHEMA_VERSION,
        var kinds: MutableMap<String, KindMeta> = mutableMapOf(),
    )

    @Volatile private var storage: Storage = Storage()

    private val file: File by lazy {
        File(FabricLoader.getInstance().configDir.toFile(), "soul/sync_meta.json")
    }

    fun load() {
        if (!file.exists()) return
        try {
            val json = JsonParser.parseString(file.readText())
            if (!json.isJsonObject) return
            val loaded = gson.fromJson(json, Storage::class.java) ?: return
            storage = loaded
        } catch (_: Exception) {
            // Bad metadata file: treat as empty. Re-sync will rebuild it.
            storage = Storage()
        }
    }

    @Synchronized
    fun save() {
        try {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(gson.toJson(storage))
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                file.writeText(gson.toJson(storage))
                tmp.delete()
            }
        } catch (_: Exception) {
            // Non-fatal: missing metadata just makes the next session redo a push.
        }
    }

    @Synchronized
    fun get(kind: SyncKind): KindMeta = storage.kinds.getOrPut(kind.key) { KindMeta() }

    @Synchronized
    fun update(
        kind: SyncKind,
        block: KindMeta.() -> Unit,
    ) {
        get(kind).block()
    }

    @Synchronized
    fun snapshotJson(): JsonObject {
        val obj = JsonObject()
        obj.addProperty("version", storage.version)
        val kinds = JsonObject()
        storage.kinds.forEach { (k, v) ->
            val km = JsonObject()
            km.addProperty("pushedHash", v.pushedHash)
            km.addProperty("syncedAt", v.syncedAt)
            kinds.add(k, km)
        }
        obj.add("kinds", kinds)
        return obj
    }
}
