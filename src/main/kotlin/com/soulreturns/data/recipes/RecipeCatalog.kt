package com.soulreturns.data.recipes

import kotlinx.serialization.Serializable

/** Where the backend built `/recipes` from (NotEnoughUpdates-REPO, MIT, Copyright (c) 2020 Moulberry). */
@Serializable
data class RecipeSource(
    val name: String? = null,
    val url: String? = null,
    val commit: String? = null,
    val committedAt: Long? = null,
    val license: String? = null,
    val copyright: String? = null,
)

/** One forge recipe; [inputs] keep the served order (kotlinx decodes objects into a LinkedHashMap). */
@Serializable
data class ForgeRecipeEntry(
    val output: String = "",
    val count: Long = 1,
    val inputs: Map<String, Long> = emptyMap(),
    val coins: Long = 0,
    /** Before Quick Forge; the game shows `baseSeconds × (1 − percent/100)`. */
    val baseSeconds: Long = 0,
    /** The forge menu's category title (`Tools`; not a closed set); null or absent in older payloads. */
    val category: String? = null,
    /** Page × 100 + slot within [category], lower first; null or absent in older payloads. */
    val order: Int? = null,
)

@Serializable
data class CraftingRecipeEntry(
    val output: String = "",
    val count: Long = 1,
    val inputs: Map<String, Long> = emptyMap(),
)

/**
 * `GET /recipes` (SkyBackend "Recipe catalog"): lists sorted by output, alternatives of one output adjacent
 * in NEU's order. Before the backend's first refresh both lists are empty and [updatedAt] is null.
 */
@Serializable
data class RecipeCatalogSnapshot(
    val schemaVersion: Int = 1,
    val updatedAt: Long? = null,
    val source: RecipeSource? = null,
    val overridesVersion: Int? = null,
    val forge: List<ForgeRecipeEntry> = emptyList(),
    val crafting: List<CraftingRecipeEntry> = emptyList(),
)
