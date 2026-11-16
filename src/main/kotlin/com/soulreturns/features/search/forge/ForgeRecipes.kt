package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeIndex

/**
 * Mock `recipeOf` over the backend catalog: forge first (coins appended as [ForgeEngine.COINS]), then the
 * output's chosen crafting recipe when it yields exactly one item. [locks] are this profile's `Requires …`
 * texts from its last Forge visit.
 */
class ForgeRecipes(private val index: RecipeIndex, private val locks: Map<String, String>) : ForgeRecipeBook {
    private val cache = HashMap<String, ForgeRecipe?>()

    override fun recipeOf(id: String): ForgeRecipe? = cache.getOrPut(id) {
        val f = index.forge[id]
        if (f != null) {
            val inputs = f.inputs.map { it.key to it.value }
            ForgeRecipe(StepKind.FORGE, if (f.coins > 0) inputs + (ForgeEngine.COINS to f.coins) else inputs, f.count.coerceAtLeast(1), f.baseSeconds, locks[id])
        } else {
            val c = index.craft[id]
            if (c != null && c.count == 1L) ForgeRecipe(StepKind.CRAFT, c.inputs.map { it.key to it.value }, 1) else null
        }
    }
}
