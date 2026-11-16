package com.soulreturns.features.search.forge

import com.soulreturns.data.recipes.RecipeIndex

/**
 * Where each forge recipe sits in the picker. What the player's own menus showed ([ForgeBookData]) wins;
 * otherwise the backend's served `category` / `order`; a recipe with neither is "Not sorted yet".
 * Category order: the Select Process order read from the menus, then categories only the backend names,
 * alphabetically (the catalog carries no category order).
 */
class ForgeCategories private constructor(
    private val placed: Map<String, Pair<String, Int>>,
    val categories: List<String>,
) {
    fun categoryOf(id: String): String? = placed[id]?.first
    fun orderOf(id: String): Int = placed[id]?.second ?: Int.MAX_VALUE
    fun unsorted(id: String): Boolean = id !in placed

    companion object {
        private var cached: ForgeCategories? = null
        private var cachedIndex: RecipeIndex? = null
        private var cachedBook: ForgeBookData? = null

        /** [of], rebuilt only when the catalog or the book changed (the picker asks every frame). */
        fun current(index: RecipeIndex, book: ForgeBookData): ForgeCategories {
            cached?.let { if (index === cachedIndex && book === cachedBook) return it }
            return of(index, book).also { cached = it; cachedIndex = index; cachedBook = book }
        }

        fun of(index: RecipeIndex, book: ForgeBookData): ForgeCategories {
            val placed = HashMap<String, Pair<String, Int>>()
            for ((id, r) in index.forge) {
                val read = book.recipes[id]
                if (read != null) placed[id] = read.category to read.order
                else r.category?.takeIf { it.isNotBlank() }?.let { placed[id] = it to (r.order ?: Int.MAX_VALUE) }
            }
            val served = placed.values.map { it.first }.toSortedSet().filter { it !in book.categories }
            return ForgeCategories(placed, book.categories + served)
        }
    }
}
