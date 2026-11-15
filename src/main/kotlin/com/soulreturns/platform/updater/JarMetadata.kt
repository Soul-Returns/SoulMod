package com.soulreturns.platform.updater

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayInputStream
import java.nio.file.Path
import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

/**
 * What the updater needs from a jar's `fabric.mod.json`.
 *
 * @property depends id → version predicates; any one predicate satisfies the entry (Fabric's rule
 *   for an array value).
 * @property breaks same shape; a match against an installed mod makes the jar unloadable.
 * @property nested ids (plus their `provides`) of the jars nested via `jars`, recursively, with
 *   their versions. Those dependencies ship inside the jar itself.
 */
data class JarMetadata(
    val id: String,
    val version: String,
    val depends: Map<String, List<String>>,
    val breaks: Map<String, List<String>>,
    val nested: Map<String, String>,
)

/** Reads [JarMetadata] from a jar on disk without loading anything from it. */
object JarMetadataReader {
    private const val MOD_JSON = "fabric.mod.json"
    private const val MAX_NESTING = 3

    /** Lenient: other mods' hand-written `fabric.mod.json` files are read during the mods-folder scan. */
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Null when [jar] is not a readable Fabric mod jar. */
    fun read(jar: Path): JarMetadata? =
        try {
            ZipFile(jar.toFile()).use { zip ->
                val entry = zip.getEntry(MOD_JSON) ?: return null
                val root = zip.getInputStream(entry).use { parse(it.readBytes()) } ?: return null
                val nested = HashMap<String, String>()
                for (path in nestedJarPaths(root)) {
                    val e = zip.getEntry(path) ?: continue
                    zip.getInputStream(e).use { collectNested(it.readBytes(), nested, 1) }
                }
                toMetadata(root, nested)
            }
        } catch (_: Exception) {
            null
        }

    /** Only the `id` of a jar, for scanning the mods folder; null when it is not a Fabric mod. */
    fun readId(jar: Path): Pair<String, String>? =
        try {
            ZipFile(jar.toFile()).use { zip ->
                val entry = zip.getEntry(MOD_JSON) ?: return null
                val root = zip.getInputStream(entry).use { parse(it.readBytes()) } ?: return null
                val id = root.string("id") ?: return null
                val version = root.string("version") ?: return null
                id to version
            }
        } catch (_: Exception) {
            null
        }

    /** Parses a `fabric.mod.json` text into metadata, nested jars excluded (tests, manifests). */
    fun fromModJson(text: String, nested: Map<String, String> = emptyMap()): JarMetadata? {
        val root = parse(text.toByteArray()) ?: return null
        return toMetadata(root, nested)
    }

    /** `depends`-shaped object (string or string array values) → id → predicates. */
    fun predicates(obj: JsonObject?): Map<String, List<String>> {
        if (obj == null) return emptyMap()
        val out = LinkedHashMap<String, List<String>>()
        for ((id, value) in obj) {
            val list = when (value) {
                is JsonPrimitive -> listOfNotNull(value.contentOrNull)
                is JsonArray -> value.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                else -> emptyList()
            }
            out[id] = list
        }
        return out
    }

    private fun toMetadata(root: JsonObject, nested: Map<String, String>): JarMetadata? {
        val id = root.string("id") ?: return null
        val version = root.string("version") ?: return null
        return JarMetadata(
            id = id,
            version = version,
            depends = predicates(root["depends"] as? JsonObject),
            breaks = predicates(root["breaks"] as? JsonObject),
            nested = nested,
        )
    }

    private fun collectNested(bytes: ByteArray, into: MutableMap<String, String>, depth: Int) {
        val entries = HashMap<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zin ->
            while (true) {
                val e = zin.nextEntry ?: break
                if (e.name == MOD_JSON || e.name.endsWith(".jar")) entries[e.name] = zin.readBytes()
            }
        }
        val root = entries[MOD_JSON]?.let { parse(it) } ?: return
        val id = root.string("id") ?: return
        val version = root.string("version") ?: return
        into[id] = version
        (root["provides"] as? JsonArray)?.forEach { p -> (p as? JsonPrimitive)?.contentOrNull?.let { into[it] = version } }
        if (depth >= MAX_NESTING) return
        for (path in nestedJarPaths(root)) entries[path]?.let { collectNested(it, into, depth + 1) }
    }

    private fun nestedJarPaths(root: JsonObject): List<String> =
        (root["jars"] as? JsonArray)?.mapNotNull { (it as? JsonObject)?.string("file") } ?: emptyList()

    private fun parse(bytes: ByteArray): JsonObject? =
        try {
            json.parseToJsonElement(bytes.toString(Charsets.UTF_8)).jsonObject
        } catch (_: Exception) {
            null
        }

    private fun JsonObject.string(key: String): String? =
        (this[key] as? JsonPrimitive)?.contentOrNull
}
