package com.soulreturns.data.recipes

import com.soulreturns.platform.catalog.BackendCatalog
import com.soulreturns.platform.catalog.CatalogContents
import com.soulreturns.platform.catalog.CatalogRegistry
import com.soulreturns.platform.catalog.CatalogStatus
import com.soulreturns.platform.concurrent.SoulScope
import kotlinx.coroutines.launch

/**
 * The client mirror of `GET /recipes`: forge and crafting recipes from NotEnoughUpdates-REPO, served by
 * the backend (the mod never contacts GitHub). Kind `recipes`, disk cache `recipe_catalog.json`, default
 * staleness (the backend rebuilds at most every 6 h and publishes `sync/catalogs` + `recipes`).
 * No bundled fallback (rule://catalogs-prices): without a first download the Forge tab says so.
 */
object RecipeCatalogClient {
    const val KIND: String = "recipes"

    private val catalog = BackendCatalog(
        kind = KIND,
        endpoint = "/recipes",
        cacheFile = "recipe_catalog.json",
        serializer = RecipeCatalogSnapshot.serializer(),
        empty = RecipeCatalogSnapshot(),
        describe = { CatalogContents(it.forge.size + it.crafting.size, it.updatedAt) },
    )

    /** Rebuilt on every snapshot swap and published by one volatile assignment. */
    @Volatile var index: RecipeIndex = RecipeIndex.of(catalog.snapshot)
        private set

    @Volatile private var refreshing = false

    fun init() {
        index = RecipeIndex.of(catalog.snapshot)
        catalog.addListener { index = RecipeIndex.of(it) }
        CatalogRegistry.register(catalog)
    }

    suspend fun refresh(): String = catalog.refresh()

    fun status(): CatalogStatus = catalog.status()

    /** The Forge tab's Try again. */
    fun retry() {
        if (refreshing) return
        refreshing = true
        SoulScope.io.launch {
            try { catalog.refresh() } finally { refreshing = false }
        }
    }

    /** `loaded` | `stale` (disk copy, last refresh failed) | `loading` (nothing yet, no failure) | `offline` (nothing, failed). */
    fun state(): String {
        val status = catalog.status()
        val empty = index.forge.isEmpty()
        if (empty) return if (status.lastError != null && !refreshing) "offline" else "loading"
        return if (status.source != "network" && status.lastError != null) "stale" else "loaded"
    }
}

/**
 * Lookups over one snapshot. Crafting alternatives are chosen as the mock (`forge-data.py`) does: the
 * output's FIRST crafting recipe in served order (NEU's own item file first, `recipe` before `recipes[]`);
 * when that first one yields more than one item it is no recipe here and the item stays a base material.
 */
class RecipeIndex private constructor(
    val forge: Map<String, ForgeRecipeEntry>,
    val craft: Map<String, CraftingRecipeEntry>,
    /** How many other crafting recipes each output has (mock `alt`). */
    val alternatives: Map<String, Int>,
    val updatedAt: Long?,
    val source: RecipeSource?,
    /** [RecipeCatalogSnapshot.petHeads] as served. */
    val petHeads: Map<String, String>,
    /** [RecipeCatalogSnapshot.heads] as served. */
    val heads: Map<String, String>,
) {
    /** Every output and input id of the forge recipes and the crafting recipes they use (icons are learned for these). */
    val ids: Set<String> by lazy {
        val out = HashSet<String>()
        for (r in forge.values) { out += r.output; out += r.inputs.keys }
        for (r in craft.values) { out += r.output; out += r.inputs.keys }
        out
    }

    companion object {
        fun of(snapshot: RecipeCatalogSnapshot): RecipeIndex {
            val forge = LinkedHashMap<String, ForgeRecipeEntry>()
            for (r in snapshot.forge) if (r.output.isNotEmpty() && r.inputs.isNotEmpty()) forge.putIfAbsent(r.output, r)
            val craft = LinkedHashMap<String, CraftingRecipeEntry>()
            val alternatives = HashMap<String, Int>()
            for (r in snapshot.crafting) {
                if (r.output.isEmpty() || r.inputs.isEmpty()) continue
                if (craft.putIfAbsent(r.output, r) != null) alternatives.merge(r.output, 1, Int::plus)
            }
            return RecipeIndex(forge, craft, alternatives, snapshot.updatedAt, snapshot.source, snapshot.petHeads, snapshot.heads)
        }
    }
}
