package com.soulreturns.features.search

import com.soulreturns.config.cfg
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.storage.StorageOverlay
import com.soulreturns.features.storage.StoragePageView

/**
 * Ender Chest pages and backpacks from the storage overlay's store, including pages known only from
 * API previews. Pages never seen (no contents) are not searchable and are listed as never opened.
 */
internal object StorageSource : SearchSource {
    override val id = "storage"
    override val label = "Storage"
    override val color = 0xFFB490FF.toInt()
    override val badgeText = 0xFFC8B4FF.toInt()
    override val badgeBack = 0xFF342B4A.toInt()
    override val emptyTitle = "Storage not seen yet"
    override val emptyText = "Open /storage and look through your Ender Chest\npages and backpacks once. Each page you open is remembered."

    class Place(val page: StoragePageView) : SearchPlace {
        override val key: String = "${page.id.kind}:${page.id.index}"
    }

    private var source: List<StoragePageView>? = null
    private var entries: List<SearchEntry> = emptyList()

    override fun enabled(): Boolean = cfg.storageOverlay.enabled

    override fun entries(): List<SearchEntry> {
        val pages = StorageOverlay.pages()
        if (pages !== source) {
            source = pages
            entries = pages.mapNotNull { page -> page.items?.let { items -> SearchEntry(Place(page), items.map(SearchItems::of)) } }
        }
        return entries
    }

    private fun seen(pages: List<StoragePageView>) = pages.count { it.items != null }

    private fun neverOpened(pages: List<StoragePageView>): String {
        val unseen = pages.filter { it.items == null }
        return if (unseen.isEmpty()) "" else " · never opened: " + unseen.joinToString(", ") { it.label }
    }

    override fun coverage(): SourceCoverage {
        val pages = StorageOverlay.pages()
        val seen = seen(pages)
        if (seen == 0) return SourceCoverage("storage not seen yet", "Not seen yet · open /storage and look through your pages once", true)
        return SourceCoverage("$seen/${pages.size} storage pages", "$seen of ${pages.size} pages seen" + neverOpened(pages), false)
    }

    override fun searched(): String {
        val pages = StorageOverlay.pages()
        return "Searched ${seen(pages)} of ${pages.size} storage pages" + neverOpened(pages)
    }

    override fun places(count: Int): String = plural(count, "storage page")

    override fun where(hits: List<SearchHit>): String {
        val names = hits.map { (it.place as Place).page.label }
        return if (names.size > 2) "${names.take(2).joinToString(", ")} +${names.size - 2}" else names.joinToString(", ")
    }

    override fun section(item: StoredChestItem, itemKey: String, hits: List<SearchHit>): SearchSection {
        fun open(hit: SearchHit) = SectionAction("Open") {
            StorageOverlay.openFromSearch((hit.place as Place).page.id, itemKey, item, hit.count)
        }
        val cards = hits.map { hit ->
            val page = (hit.place as Place).page
            HitCard(
                (if (page.id.kind == "ender") "E" else "B") + page.id.index, page.label, hit.count,
                plural(hit.stacks, "stack"), warn = if (page.preview) "· from the SkyBlock API, not opened yet" else "",
                trailing = if (page.id.kind == "ender") "/enderchest ${page.id.index}" else "/backpack ${page.id.index}",
                action = open(hit),
            )
        }
        return SearchSection(plural(hits.size, "page"), if (hits.size == 1) open(hits.single()) else null, null, false, cards, false)
    }
}
