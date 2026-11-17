package com.soulreturns.features.storage

import com.soulreturns.config.DataBackups
import com.soulreturns.util.SoulLogger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
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
    private var root = JsonObject(mapOf("schemaVersion" to JsonPrimitive(SCHEMA_VERSION), "buckets" to JsonObject(emptyMap())))
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
            if (parsed == null || schema == null || schema !in 1..SCHEMA_VERSION || parsed["buckets"] !is JsonObject) {
                writable = false
                backup(file, if ((schema ?: 0) > SCHEMA_VERSION) "newer-v$schema" else "unreadable")
                return
            }
            val migrated = StorageMigrations.migrate(schema, parsed)
            if (migrated["buckets"] != parsed["buckets"]) {
                backup(file, "migrate-v$schema")
                version++
            }
            root = migrated
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

private const val SCHEMA_VERSION = 2

/** Load-time steps of `storage.json` below [SCHEMA_VERSION]; raw JSON in and out, so malformed or unknown data stays verbatim. */
internal object StorageMigrations {
    fun migrate(fromVersion: Int, root: JsonObject): JsonObject {
        if (fromVersion >= SCHEMA_VERSION) return root
        val buckets = (root["buckets"] as JsonObject).mapValues { (_, bucket) -> labelBackpackPages(bucket) }
        return JsonObject(root + mapOf("schemaVersion" to JsonPrimitive(SCHEMA_VERSION), "buckets" to JsonObject(buckets)))
    }

    /**
     * v1 -> v2: v1 saved a backpack page opened without a Storage overview visit in the same world with no UUID,
     * so it never showed again and the next overview cleared it. Such a page took its contents after the last
     * overview (which would have cleared it), so it gets that overview's UUID for the slot, as
     * [StorageBucket.observeOpen] records it now.
     */
    fun labelBackpackPages(bucket: JsonElement): JsonElement {
        val obj = bucket as? JsonObject ?: return bucket
        val pages = obj["pages"] as? JsonArray ?: return bucket
        val installed = (obj["selectors"] as? JsonArray).orEmpty().mapNotNull { raw ->
            val selector = raw as? JsonObject ?: return@mapNotNull null
            val index = backpackIndex(selector["id"]) ?: return@mapNotNull null
            val identity = (selector["identity"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
            if ((selector["state"] as? JsonPrimitive)?.contentOrNull != "installed" || identity == null) null else index to identity
        }.toMap()
        var changed = false
        val labelled = pages.map { raw ->
            val page = raw as? JsonObject ?: return@map raw
            val identity = backpackIndex(page["id"])?.let(installed::get) ?: return@map raw
            if ((page["identity"] ?: JsonNull) != JsonNull || (page["items"] ?: JsonNull) == JsonNull) return@map raw
            changed = true
            JsonObject(page + ("identity" to JsonPrimitive(identity)))
        }
        return if (changed) JsonObject(obj + ("pages" to JsonArray(labelled))) else bucket
    }

    private fun backpackIndex(id: JsonElement?): Int? {
        val obj = id as? JsonObject ?: return null
        if ((obj["kind"] as? JsonPrimitive)?.contentOrNull != "backpack") return null
        return (obj["index"] as? JsonPrimitive)?.intOrNull
    }
}
