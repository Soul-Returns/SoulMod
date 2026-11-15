package com.soulreturns.features.search

import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.islandchests.searchKey
import com.soulreturns.features.islandchests.searchName
import com.soulreturns.features.islandchests.searchText
import java.util.Locale

/** One item across every source: [hits] by source id, places in each source's own order. */
class SearchRow(val key: String, val item: StoredChestItem, val name: String, internal val text: String, val hits: Map<String, List<SearchHit>>) {
    internal val sortName = name.lowercase(Locale.ROOT)
    fun has(source: String): Boolean = hits[source]?.isNotEmpty() == true
    fun hitsIn(source: String): List<SearchHit> = hits[source].orEmpty()
    fun total(sources: Iterable<String>): Long = sources.sumOf { source -> hits[source]?.sumOf { it.count } ?: 0L }
}

/**
 * The merged list of the mock's `resultsIn`: items grouped by their exact search identity (name,
 * id, material, lore), rebuilt only when a source hands out a new entry list.
 */
class SearchIndex {
    private var sourceIds: List<String> = emptyList()
    private var sourceLists: List<List<SearchEntry>> = emptyList()
    private val cache = HashMap<String, List<SearchRow>>()
    var rows: List<SearchRow> = emptyList()
        private set

    fun update(sources: List<Pair<String, List<SearchEntry>>>): List<SearchRow> {
        val ids = sources.map { it.first }
        val lists = sources.map { it.second }
        if (ids == sourceIds && lists.size == sourceLists.size && lists.indices.all { lists[it] === sourceLists[it] }) return rows
        sourceIds = ids
        sourceLists = lists
        cache.clear()
        rows = build(sources)
        return rows
    }

    /** Rows matching [query] with a hit in any of [sources]; the mock's order (prefix matches first). */
    fun search(query: String, sources: List<String>): List<SearchRow> {
        val normalized = query.trim().lowercase(Locale.ROOT)
        val id = normalized + "\u0000" + sources.joinToString(",")
        cache[id]?.let { return it }
        if (cache.size > 32) cache.clear()
        return filter(rows, normalized, sources).also { cache[id] = it }
    }

    companion object {
        private class Counted(val place: SearchPlace, var count: Long = 0, var stacks: Int = 0)
        private class Group(val item: StoredChestItem) {
            val bySource = LinkedHashMap<String, LinkedHashMap<String, Counted>>()
        }

        fun build(sources: List<Pair<String, List<SearchEntry>>>): List<SearchRow> {
            val groups = LinkedHashMap<String, Group>()
            for ((source, entries) in sources) for (entry in entries) for (item in entry.items) {
                if (item == null || item.count <= 0) continue
                val group = groups.getOrPut(item.searchKey()) { Group(item) }
                val counted = group.bySource.getOrPut(source) { LinkedHashMap() }.getOrPut(entry.place.key) { Counted(entry.place) }
                counted.count += item.count.toLong()
                counted.stacks++
            }
            return groups.map { (key, group) ->
                val item = group.item
                SearchRow(key, item, item.searchName(), item.searchText(),
                    group.bySource.mapValues { (_, places) -> places.values.map { SearchHit(it.place, it.count, it.stacks) } })
            }.sortedWith(compareBy({ it.sortName }, { it.key }))
        }

        /** Every query word must appear; blank lists everything alphabetically. */
        fun filter(rows: List<SearchRow>, query: String, sources: List<String>): List<SearchRow> {
            val normalized = query.trim().lowercase(Locale.ROOT)
            val words = normalized.split(' ').filter(String::isNotEmpty)
            val found = rows.filter { row -> sources.any(row::has) && words.all { it in row.text } }
            return if (normalized.isEmpty()) found else found.sortedBy { !it.sortName.startsWith(normalized) }
        }
    }
}
