package com.soulreturns.ui.config.search

import com.soulreturns.ui.config.model.CategoryEntry
import com.soulreturns.ui.config.model.SubcategoryEntry
import com.soulreturns.ui.config.registry.ConfigSections
import io.wispforest.owo.config.Option
import net.minecraft.network.chat.Component

/**
 * Pure filter pass for the config-screen search box.
 *
 * Match rules (case-insensitive):
 *  - **Category name match** → keep the entire category and all its subs intact.
 *  - **Subcategory name match** → keep the whole sub intact.
 *  - **Otherwise** → keep only options whose label or tooltip text contains the query.
 *
 * Hidden options (per [ConfigSections.isOptionVisible]) are never surfaced — searching for
 * them is only useful once their gating parent toggle is on.
 */
internal object ConfigSearchFilter {

    fun filter(all: List<CategoryEntry>, query: String): List<CategoryEntry> {
        val q = query.trim()
        if (q.isEmpty()) return all
        return all.mapNotNull { cat ->
            val catMatches = cat.displayName.string.contains(q, ignoreCase = true)
            val filteredSubs = cat.subcategories.mapNotNull { sub ->
                val subMatches = sub.displayName.string.contains(q, ignoreCase = true)
                if (catMatches || subMatches) {
                    sub
                } else {
                    val matching = sub.options.filter { matchesOption(it, q) }
                    if (matching.isEmpty()) null
                    else SubcategoryEntry(sub.catId, sub.subId, sub.displayName, matching)
                }
            }
            if (filteredSubs.isEmpty()) null
            else CategoryEntry(cat.id, cat.displayName, filteredSubs)
        }
    }

    private fun matchesOption(opt: Option<*>, q: String): Boolean {
        if (!ConfigSections.isOptionVisible(opt)) return false
        val labelText = Component.translatable(opt.translationKey()).string
        if (labelText.contains(q, ignoreCase = true)) return true
        val tooltipKey = opt.translationKey() + ".tooltip"
        val tooltipText = Component.translatable(tooltipKey).string
        return tooltipText != tooltipKey && tooltipText.contains(q, ignoreCase = true)
    }
}
