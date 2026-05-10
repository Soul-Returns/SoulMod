package com.soulreturns.ui.config.model

import com.soulreturns.ui.config.registry.ConfigSections
import io.wispforest.owo.config.ConfigWrapper
import io.wispforest.owo.config.Option
import net.minecraft.network.chat.Component

/**
 * Walks the owo-config wrapper once and produces a normalized [CategoryEntry] list:
 *  - Top-level path → category id; depth-≥3 paths get path[1] as subcategory id, depth-≤2
 *    fall into a synthetic `"misc"` sub.
 *  - Virtual subs from [ConfigSections.virtualSubs] are merged in with empty option lists.
 *  - Categories are sorted per [ConfigSections.categoryOrder]; unlisted ones fall to the end.
 *  - Names are resolved to translatable [Component]s using `text.config.soul/config.{category,group}.<id>` keys.
 */
internal object CategoriesCollector {
    fun collect(wrapper: ConfigWrapper<*>): List<CategoryEntry> {
        val byCat = LinkedHashMap<String, LinkedHashMap<String, MutableList<Option<*>>>>()
        wrapper.forEachOption { opt ->
            val path = opt.key().path()
            if (path.isEmpty()) return@forEachOption
            val cat = path[0]
            val sub = if (path.size >= 3) path[1] else "misc"
            val groups = byCat.getOrPut(cat) { LinkedHashMap() }
            groups.getOrPut(sub) { mutableListOf() }.add(opt)
        }
        // Inject virtual subs (no backing fields).
        for ((catId, subIds) in ConfigSections.virtualSubs) {
            val groups = byCat.getOrPut(catId) { LinkedHashMap() }
            for (subId in subIds) groups.getOrPut(subId) { mutableListOf() }
        }
        val maxOrderIdx = ConfigSections.categoryOrder.size
        return byCat.entries
            .sortedBy { entry ->
                val idx = ConfigSections.categoryOrder.indexOf(entry.key)
                if (idx < 0) maxOrderIdx else idx
            }
            .map { (catId, groups) ->
                val subs =
                    groups.entries.map { (subId, options) ->
                        SubcategoryEntry(
                            catId = catId,
                            subId = subId,
                            displayName = Component.translatable("text.config.soul/config.group.$catId.$subId"),
                            options = options,
                        )
                    }
                CategoryEntry(
                    id = catId,
                    displayName = Component.translatable("text.config.soul/config.category.$catId"),
                    subcategories = subs,
                )
            }
    }
}
