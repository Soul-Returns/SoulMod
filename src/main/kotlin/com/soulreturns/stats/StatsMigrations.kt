package com.soulreturns.stats

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Load-time migrations of `stats.json`, applied once per file read below [PersistentStats]'s current
 * schema version. Pure: raw JSON in, raw JSON out, so a feature's payload type never has to keep
 * obsolete fields.
 */
internal object StatsMigrations {
    /** `nucleus` kit rows of the four Scavenged tools (v1, removed in v2: they cost nothing). */
    private val NUCLEUS_TOOLS = setOf("Scavenged Lapis Sword", "Scavenged Golden Hammer", "Scavenged Diamond Axe", "Scavenged Emerald Hammer")

    /** Applies every migration newer than [fromVersion] to the namespace map, returning the migrated map. */
    fun migrate(
        fromVersion: Int,
        namespaces: Map<String, Map<String, JsonElement>>,
    ): Map<String, Map<String, JsonElement>> {
        if (fromVersion >= 2) return namespaces
        val nucleus = namespaces["nucleus"] ?: return namespaces
        return namespaces + ("nucleus" to nucleus.mapValues { (_, bucket) -> dropNucleusTools(bucket) })
    }

    /** v1 -> v2: drops the Scavenged tool rows from the session and total kit of one nucleus bucket. */
    fun dropNucleusTools(bucket: JsonElement): JsonElement {
        val root = bucket as? JsonObject ?: return bucket
        val fixed = root.mapValues { (scope, counts) ->
            if (scope != "session" && scope != "total") return@mapValues counts
            val obj = counts as? JsonObject ?: return@mapValues counts
            val kit = obj["kit"] as? JsonObject ?: return@mapValues counts
            JsonObject(obj + ("kit" to JsonObject(kit.filterKeys { it !in NUCLEUS_TOOLS })))
        }
        return JsonObject(fixed)
    }
}
