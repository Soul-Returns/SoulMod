package com.soulreturns.features.islandchests

import com.soulreturns.config.DataBackups
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

internal class IslandChestStore(
    private val file: Path,
    private val backup: (Path, String) -> Unit = { path, reason -> DataBackups.backup(path, reason); Unit },
) {
    private val logger = SoulLogger("Soul/IslandChests")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }
    private var root = JsonObject(mapOf("schemaVersion" to JsonPrimitive(1), "buckets" to JsonObject(emptyMap())))
    private val decoded = HashMap<String, IslandChestBucket>()
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
            val schema = parsed?.get("schemaVersion")?.jsonPrimitive?.intOrNull
            if (parsed == null || schema != 1 || parsed["buckets"] !is JsonObject) {
                writable = false
                backup(file, if ((schema ?: 0) > 1) "newer-v$schema" else "unreadable")
                logger.warn("Cannot read island-chests.json; preserving it without overwriting")
                return
            }
            root = parsed
        } catch (e: Exception) {
            writable = false
            backup(file, "unreadable")
            logger.warn("Cannot read island-chests.json; preserving it without overwriting", e)
        }
    }

    @Synchronized
    fun read(account: String?, profile: String?): IslandChestBucket {
        val key = chestBucketKey(account, profile) ?: return IslandChestBucket()
        return decoded.getOrPut(key) {
            val raw = (root["buckets"] as JsonObject)[key] ?: return@getOrPut IslandChestBucket()
            try {
                json.decodeFromJsonElement(IslandChestBucket.serializer(), raw)
            } catch (e: Exception) {
                unreadable += key
                backup(file, "unreadable-bucket")
                logger.warn("Cannot read an island chest profile bucket; preserving its original data", e)
                IslandChestBucket()
            }
        }
    }

    @Synchronized
    fun update(account: String?, profile: String?, change: (IslandChestBucket) -> IslandChestBucket): IslandChestBucket? {
        val key = chestBucketKey(account, profile) ?: return null
        val previous = read(account, profile)
        val next = change(previous)
        if (previous == next) return previous
        decoded[key] = next
        if (writable && key !in unreadable) {
            dirty += key
            version++
        }
        return next
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
                for ((key, bucket) in snapshot.third) buckets[key] = json.encodeToJsonElement(IslandChestBucket.serializer(), bucket)
                val output = JsonObject(snapshot.second.toMutableMap().also { it["buckets"] = JsonObject(buckets) })
                Files.createDirectories(file.parent)
                val temp = file.resolveSibling("${file.fileName}.tmp")
                Files.writeString(temp, json.encodeToString(JsonObject.serializer(), output))
                try {
                    Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } catch (_: AtomicMoveNotSupportedException) {
                    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING)
                }
                synchronized(this) {
                    root = output
                    savedVersion = snapshot.first
                    for ((key, bucket) in snapshot.third) if (decoded[key] === bucket) dirty.remove(key)
                }
            } catch (e: Exception) {
                logger.warn("Could not save island chest cache", e)
            }
        }
    }
}
