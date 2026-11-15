package com.soulreturns.features.search

import com.soulreturns.features.islandchests.StoredChestItem

/**
 * One place a source remembers items in (an island chest, a storage page). Sources keep their own
 * data in subclasses; the search only needs a stable [key] within the source.
 */
interface SearchPlace {
    val key: String
}

/** A place with the items last seen in it, in its own slot order (null = empty slot). */
class SearchEntry(val place: SearchPlace, val items: List<StoredChestItem?>)

/** How many of one item a place holds, and in how many stacks. */
class SearchHit(val place: SearchPlace, val count: Long, val stacks: Int)

class SourceCoverage(val short: String, val long: String, val none: Boolean)

class SectionAction(val label: String, val enabled: Boolean = true, val accent: Boolean = false, val run: () -> Unit = {})

/** One row in a source's details section (mock `.card`). */
class HitCard(
    val badge: String,
    val title: String,
    val count: Long,
    /** Left part of the second line; [dim] follows it in the darker color. */
    val detail: String,
    val dim: String = "",
    /** Right-aligned end of the second line. */
    val trailing: String = "",
    /** Amber text after [detail] (API previews). */
    val warn: String = "",
    /** Older observation: amber edge and trailing text. */
    val stale: Boolean = false,
    val action: SectionAction? = null,
    val tooltip: List<String> = emptyList(),
)

/** A source's collapsible block in the details pane (mock `islandSection` / `storageSection`). */
class SearchSection(
    val places: String,
    val action: SectionAction?,
    val note: String?,
    val noteWarn: Boolean,
    val cards: List<HitCard>,
    val stale: Boolean,
)

/**
 * An item source of Search everywhere (mock `SOURCES`). The screen (filter chips, row tags, detail
 * sections, empty and no-result coverage) is built only from [SearchEverywhere.sources]; a new
 * source is one more implementation registered there.
 */
interface SearchSource {
    val id: String
    val label: String
    /** Dot color of the source's chip, tags and section. */
    val color: Int
    /** Card badge text/background (island #9db4d1 on #293443, storage #c8b4ff on #342b4a). */
    val badgeText: Int
    val badgeBack: Int
    val emptyTitle: String
    val emptyText: String

    /** The source's own feature is on; only then does it take part in the search at all. */
    fun enabled(): Boolean

    /** Same list instance until the remembered contents change; empty = nothing seen yet. */
    fun entries(): List<SearchEntry>

    fun coverage(): SourceCoverage

    /** "Searched …" line for the no-results state. */
    fun searched(): String

    /** "3 island chests", "1 storage page": the details summary's per-source count. */
    fun places(count: Int): String

    /** Row tag text for an item's hits in this source. */
    fun where(hits: List<SearchHit>): String

    fun section(item: StoredChestItem, itemKey: String, hits: List<SearchHit>): SearchSection
}
