package com.soulreturns.data.items

import net.fabricmc.fabric.api.resource.v1.ResourceLoader
import net.minecraft.resources.Identifier
import net.minecraft.server.packs.PackType
import net.minecraft.server.packs.resources.ResourceManagerReloadListener
import java.util.Locale

/**
 * Item models of Hypixel's server resource pack, for icons of items the player never had in view. Hypixel
 * draws most SkyBlock items as `minecraft:paper` with an `item_model` like
 * `hypixel_skyblock:item/uncategorized/refined_diamond`; that model exists only while its pack is loaded.
 * On every resource reload (the pack arriving or leaving) the item definitions under namespace
 * [NAMESPACE] are listed (ids only, in memory; nothing is read, copied or written). No pack = empty index =
 * icons exactly as without it.
 */
object PackModels {
    const val NAMESPACE = "hypixel_skyblock"

    @Volatile var index: ModelIndex = ModelIndex.EMPTY
        private set

    fun register() {
        ResourceLoader.get(PackType.CLIENT_RESOURCES).registerReloadListener(
            Identifier.fromNamespaceAndPath("soul", "forge_models"),
            ResourceManagerReloadListener { manager ->
                val ids = manager.listResources("items") { it.namespace == NAMESPACE && it.path.endsWith(".json") }.keys
                    .map { "${it.namespace}:${it.path.removePrefix("items/").removeSuffix(".json")}" }
                index = ModelIndex.of(ids)
            },
        )
    }
}

/**
 * Item model ids by their last path segment. Survey of the 2026-11-16 capture (125 SkyBlock ids with a
 * model): 91 end in the lowercased id (`REFINED_DIAMOND` → `…/refined_diamond`), 32 more in the display name
 * as a path (`DRILL_ENGINE` "Drill Motor" → `drill_motor`, `TITANIUM_DRILL_4` → `titanium_drill_dr_x655`,
 * `DIVAN_DRILL` "Divan's Drill" → `divans_drill`) or, for `ENCHANTED_X`, in the base item's model
 * (`ENCHANTED_TITANIUM` → `titanium`, drawn with glint). 2 match nothing (`MAGMA_FISH_DIAMOND` →
 * `magmafish_diamond`, `NEX_TITANUM` → `prime_huntaxe`) and keep the catalog material. Pets, heads, dyed
 * armor and vanilla-material items carry no model.
 */
class ModelIndex private constructor(private val bySegment: Map<String, List<String>>, private val all: Set<String>) {
    val size: Int get() = all.size

    /** Whether the loaded pack defines [model] (a full id, `hypixel_skyblock:item/…`). */
    operator fun contains(model: String): Boolean = model in all

    /**
     * The model for [id] (display name [name]), trying the lowercased id, then the display name as a path,
     * then an `ENCHANTED_` id's base. Several models with one name: the one with the fewest path segments
     * (`item/uncategorized/x` over `item/a/b/x`); a tie there is ambiguous and resolves nothing.
     * Pet keys (`TYPE;tier`) never match.
     */
    fun resolve(id: String, name: String?): String? {
        if (bySegment.isEmpty() || ';' in id) return null
        val lower = id.lowercase(Locale.ROOT)
        val candidates = listOfNotNull(lower, name?.let(::pathName), if (lower.startsWith("enchanted_")) lower.removePrefix("enchanted_") else null)
        for (candidate in candidates) {
            val models = bySegment[candidate] ?: continue
            return pick(models)
        }
        return null
    }

    private fun pick(models: List<String>): String? {
        if (models.size == 1) return models[0]
        val depth = models.minOf { it.count { c -> c == '/' } }
        val shallow = models.filter { it.count { c -> c == '/' } == depth }
        return shallow.singleOrNull()
    }

    companion object {
        val EMPTY = ModelIndex(emptyMap(), emptySet())
        private val CODE = Regex("§.")
        private val NON_WORD = Regex("[^a-z0-9]+")

        /** [ids] are model ids (`hypixel_skyblock:item/…`); only those under `item/` take part. */
        fun of(ids: Collection<String>): ModelIndex {
            val map = HashMap<String, MutableList<String>>()
            val all = HashSet<String>()
            for (id in ids.toSet()) {
                val path = id.substringAfter(':')
                if (!path.startsWith("item/")) continue
                map.getOrPut(path.substringAfterLast('/')) { ArrayList() } += id
                all += id
            }
            for (list in map.values) list.sort()
            return ModelIndex(map, all)
        }

        /** "Divan's Drill" → `divans_drill`, "Titanium Drill DR-X655" → `titanium_drill_dr_x655`. */
        fun pathName(name: String): String =
            NON_WORD.replace(name.replace(CODE, "").lowercase(Locale.ROOT).replace("'", "").replace("\u2019", ""), "_").trim('_')
    }
}
