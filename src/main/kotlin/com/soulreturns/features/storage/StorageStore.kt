package com.soulreturns.features.storage

import com.soulreturns.config.DataBackups
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class StorageStore(
    private val file: Path,
    private val backup: (Path, String) -> Unit = { path, reason -> DataBackups.backup(path, reason); Unit },
) {
    private val logger = SoulLogger("Soul/Storage")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private var root = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "buckets" to JsonObject(emptyMap())))
    private val decoded = HashMap<String, StorageBucket>()
    private val unreadable = HashSet<String>()
    private val dirty = HashSet<String>()
    private var writable = true
    private var version = 0L
    private var savedVersion = 0L
    private val writeLock = Any()

    @Synchronized
    fun load() {
        if (!Files.isRegularFile(file)) return
        try {
            val parsed = json.parseToJsonElement(Files.readString(file)) as? JsonObject
            val schema = (parsed?.get("schemaVersion") as? JsonPrimitive)?.intOrNull
            if (parsed == null || schema != 1 || parsed["buckets"] !is JsonObject) {
                writable = false
                backup(file, if ((schema ?: 0) > 1) "newer-v$schema" else "unreadable")
                return
            }
            root = parsed
        } catch (e: Exception) {
            writable = false
            backup(file, "unreadable")
            logger.warn("Cannot read storage cache; preserving the original file", e)
        }
    }

    @Synchronized
    fun read(scope: String): StorageBucket = decoded.getOrPut(scope) {
        val raw = (root["buckets"] as JsonObject)[scope] ?: return@getOrPut StorageBucket()
        try {
            json.decodeFromJsonElement(StorageBucket.serializer(), raw).also { bucket ->
                require(bucket.pages.map { it.id }.distinct().size == bucket.pages.size)
                require(bucket.selectors.map { it.id }.distinct().size == bucket.selectors.size)
                require(bucket.pages.all { it.rows in 1..5 && (it.items == null || it.items.size == it.rows * 9) && validId(it.id) })
                require(bucket.selectors.all { validId(it.id) && it.rows in 1..5 && it.state in setOf("installed", "empty", "locked", "unknown") })
            }
        } catch (e: Exception) {
            unreadable += scope
            backup(file, "unreadable-bucket")
            logger.warn("Cannot read a storage profile; preserving its original data", e)
            StorageBucket()
        }
    }

    @Synchronized
    fun update(scope: String, change: (StorageBucket) -> StorageBucket): StorageBucket {
        val old = read(scope)
        val next = change(old)
        if (next == old) return old
        decoded[scope] = next
        if (writable && scope !in unreadable) { dirty += scope; version++ }
        return next
    }

    @Synchronized
    fun unreadableItems(scope: String) {
        if (!unreadable.add(scope)) return
        dirty.remove(scope)
        backup(file, "unreadable-items")
        logger.warn("Cannot decode storage components; preserving the original profile bucket")
    }

    @Synchronized
    fun hasChanges(): Boolean = writable && version != savedVersion

    fun flush() {
        synchronized(writeLock) {
            val snapshot = synchronized(this) {
                if (!writable || version == savedVersion) return
                Triple(version, root, dirty.associateWith { decoded.getValue(it) })
            }
            try {
                val buckets = (snapshot.second["buckets"] as JsonObject).toMutableMap()
                for ((scope, bucket) in snapshot.third) buckets[scope] = json.encodeToJsonElement(StorageBucket.serializer(), bucket)
                val output = JsonObject(snapshot.second.toMutableMap().also { it["buckets"] = JsonObject(buckets) })
                Files.createDirectories(file.parent)
                val temp = file.resolveSibling("${file.fileName}.tmp")
                Files.writeString(temp, json.encodeToString(JsonObject.serializer(), output))
                try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
                catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
                synchronized(this) {
                    root = output
                    savedVersion = snapshot.first
                    for ((scope, bucket) in snapshot.third) if (decoded[scope] === bucket) dirty.remove(scope)
                }
            } catch (e: Exception) { logger.warn("Could not save storage cache", e) }
        }
    }

    private fun validId(id: StoragePageId): Boolean = (id.kind == "ender" && id.index in 1..9) || (id.kind == "backpack" && id.index in 1..18)
}
