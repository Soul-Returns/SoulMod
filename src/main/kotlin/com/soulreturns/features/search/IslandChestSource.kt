package com.soulreturns.features.search

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.features.islandchests.IslandChestDisplay
import com.soulreturns.features.islandchests.IslandChestLayout
import com.soulreturns.features.islandchests.IslandChests
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.islandchests.StoredIslandChest
import java.time.Instant
import kotlin.math.roundToInt

/** The private island's remembered chests (per account and exact profile), searchable from anywhere. */
internal object IslandChestSource : SearchSource {
    override val id = "island"
    override val label = "Island chests"
    override val color = 0xFFD9A95F.toInt()
    override val badgeText = 0xFF9DB4D1.toInt()
    override val badgeBack = 0xFF293443.toInt()
    override val emptyTitle = "No island chests remembered yet"
    override val emptyText = "Open chests on your private island once.\nAfter that they are searchable from anywhere."

    class Place(val chest: StoredIslandChest) : SearchPlace {
        override val key: String get() = chest.key
    }

    private var source: List<StoredIslandChest>? = null
    private var entries: List<SearchEntry> = emptyList()

    override fun enabled(): Boolean = cfg.islandChests.enabled

    override fun entries(): List<SearchEntry> {
        val chests = IslandChests.view().chests
        if (chests !== source) {
            source = chests
            entries = chests.map { SearchEntry(Place(it), it.slots) }
        }
        return entries
    }

    override fun coverage(): SourceCoverage {
        val view = IslandChests.view()
        if (view.profileId == null) return SourceCoverage("island chests locked", "Waiting for your profile · remembered chests stay hidden until it is known", true)
        val count = view.chests.size
        if (count == 0) return SourceCoverage("no island chests yet", "No chests remembered yet · open chests on your private island", true)
        return SourceCoverage(plural(count, "island chest"), "${plural(count, "chest")} remembered · ${coopText(view.sharingEnabled, view.syncState)}", false)
    }

    /** Co-op sync runs anywhere on SkyBlock, so its last outcome is the truth on and off the island. */
    internal fun coopText(sharing: Boolean, state: String): String = when {
        !sharing -> "co-op sharing off"
        state == "offline" -> "co-op offline"
        state == "rate-limited" -> "co-op sync paused"
        state == "forbidden" || state == "update-required" || state == "error" -> "co-op sync unavailable"
        else -> "co-op sharing on"
    }

    override fun searched(): String = "Searched ${plural(IslandChests.view().chests.size, "remembered island chest")}"

    override fun places(count: Int): String = plural(count, "island chest")

    override fun where(hits: List<SearchHit>): String {
        val nearest = hits.mapNotNull { IslandChestDisplay.distance((it.place as Place).chest) }.minOrNull()
        return places(hits.size) + if (nearest != null) " · nearest ${nearest.roundToInt()}m" else ""
    }

    override fun section(item: StoredChestItem, itemKey: String, hits: List<SearchHit>): SearchSection {
        val home = IslandChests.view().ownIsland
        val chests = hits.map { (it.place as Place).chest }
        val newest = chests.maxOfOrNull { it.observedAt } ?: 0L
        val action: SectionAction
        val note: String?
        if (home) {
            action = SectionAction(if (hits.size == 1) "Locate chest" else "Locate all ${hits.size}", accent = true) {
                if (IslandChestDisplay.locate(itemKey)) SearchEverywhere.leave()
            }
            note = null
        } else if (LocationApi.inDungeon) {
            action = SectionAction("Warp home & locate", enabled = false)
            note = "Not during a dungeon run."
        } else {
            action = SectionAction("Warp home & locate", accent = true) {
                if (IslandChestDisplay.warpHomeAndLocate(itemKey)) SearchEverywhere.leave()
            }
            note = "Sends /warp home, then outlines these chests."
        }
        val now = System.currentTimeMillis()
        val cards = hits.mapIndexed { index, hit ->
            val chest = chests[index]
            val older = chest.observedAt < newest
            val first = chest.blocks.first()
            val other = chest.blocks.getOrNull(1)
            val distance = IslandChestDisplay.distance(chest)?.roundToInt()
            val who = chest.observerName ?: chest.observerUuid.take(8)
            val dim = listOfNotNull(other?.let { "/ ${it.x}, ${it.y}, ${it.z}" }, distance?.let { "· ${it}m" }).joinToString(" ")
            HitCard(
                IslandChestLayout.marker(index), if (chest.blocks.size == 2) "Double chest" else "Chest", hit.count,
                "${first.x}, ${first.y}, ${first.z}", dim,
                (if (older) "older check · " else "") + "${IslandChestLayout.age(chest.observedAt, now)} · $who", stale = older,
                tooltip = listOf(chest.blocks.joinToString(" / ") { "${it.x}, ${it.y}, ${it.z}" },
                    "Last checked: ${Instant.ofEpochMilli(chest.observedAt)}", "Contributor: ${chest.observerName ?: chest.observerUuid}"),
            )
        }
        return SearchSection(plural(hits.size, "chest"), action, note, !home, cards, cards.any { it.stale })
    }
}

internal fun plural(count: Int, one: String, many: String = "${one}s") = "$count ${if (count == 1) one else many}"
