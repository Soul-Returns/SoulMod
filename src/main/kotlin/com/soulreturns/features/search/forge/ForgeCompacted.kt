package com.soulreturns.features.search.forge

/**
 * Compacted forms of base materials, for the nested rows of "Still to gather". A form is an item the goal's
 * tree makes by a crafting step whose only ingredient type is a base material or another form of it
 * (Enchanted Mithril from Mithril, Enchanted Diamond Block from Enchanted Diamond). Forge processes are never
 * forms. Derived from the recipe book alone, so no item list or name pattern is involved.
 */
object ForgeCompacted {
    /** The base material at the end of [id]'s single-ingredient crafting chain, with its distance; null when none. */
    private fun rawOf(id: String, book: ForgeRecipeBook): Pair<String, Int>? {
        var cur = id
        var level = 0
        val seen = HashSet<String>()
        while (true) {
            val recipe = book.recipeOf(cur)
            if (recipe == null) return if (level > 0 && cur != ForgeEngine.COINS) cur to level else null
            if (recipe.kind != StepKind.CRAFT || !seen.add(cur)) return null
            val ingredient = recipe.inputs.map { it.first }.distinct().singleOrNull() ?: return null
            cur = ingredient
            level++
        }
    }

    /** Base material id → its forms in the goal's aggregated [agg], closest to raw first (ties by id). */
    fun formsByBase(agg: List<ForgeStep>, book: ForgeRecipeBook): Map<String, List<ForgeStep>> {
        val bases = agg.filter { it.kind == StepKind.BASE }.mapTo(HashSet()) { it.id }
        val found = HashMap<String, MutableList<Pair<Int, ForgeStep>>>()
        for (step in agg) {
            if (step.kind != StepKind.CRAFT) continue
            val (base, level) = rawOf(step.id, book) ?: continue
            if (base in bases) found.getOrPut(base) { ArrayList() } += level to step
        }
        return found.mapValues { (_, list) ->
            list.sortedWith { a, b -> if (a.first != b.first) a.first - b.first else IdOrder.compare(a.second.id, b.second.id) }.map { it.second }
        }
    }

    /** Default fold state: open when the player holds any unit of any of the [forms]. */
    fun defaultOpen(forms: List<ForgeStep>, have: (String) -> Long): Boolean = forms.any { have(it.id) > 0 }
}
