package com.soulreturns.features.search.forge

import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.sacks.SackState
import com.soulreturns.features.search.SackSearchSource
import com.soulreturns.features.search.SearchEntry
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.search.SearchHit
import com.soulreturns.features.search.SearchSource
import com.soulreturns.util.MessageDetector
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/** One source's part of an owned count: [where] is the tooltip's place text (`2 island chests`, `Ender Chest 2`). */
class OwnedPart(val source: String, val count: Long, val where: String, val hits: List<SearchHit> = emptyList())

/** Breakdown order and colors of the mock's `FSRC`; island / storage keep the search screen's colors. */
class OwnedSourceInfo(val id: String, val label: String, val color: Int)

/**
 * What the player owns, per profile, by recipe id: every Search everywhere source (island chests, storage
 * and any future one, via [SearchSource.entries]) plus sacks, the live inventory and outputs running in the
 * forge. A source without a cache is unknown, never zero: [known] says which ones have one.
 */
class OwnedSnapshot(
    private val parts: Map<String, List<OwnedPart>>,
    /** Source id → has a cache for this profile (mock `cached`). */
    val known: Map<String, Boolean>,
    /** Search source id → the entry list this was built from (identity), for change detection. */
    internal val searchLists: List<List<SearchEntry>>,
    internal val sackData: Any?,
    internal val forgeSlots: Any?,
    val purse: Long?,
    val bank: Long?,
    /** One owned stored item per id, for names and icons of things the catalog does not know. */
    val items: Map<String, StoredChestItem>,
) {
    fun bySource(id: String): List<OwnedPart> {
        if (id == ForgeEngine.COINS) return listOfNotNull(purse?.let { OwnedPart("purse", it, "purse") }, bank?.let { OwnedPart("bank", it, "bank") })
        return parts[id].orEmpty()
    }

    fun have(id: String): Long = bySource(id).sumOf { it.count }

    fun ids(): Set<String> = parts.keys
}

object ForgeOwned {
    val SOURCES = listOf(
        OwnedSourceInfo("island", "Island chests", 0xFFD9A95F.toInt()),
        OwnedSourceInfo("storage", "Storage", 0xFFB490FF.toInt()),
        OwnedSourceInfo("sacks", "Sacks", 0xFF7CC48A.toInt()),
        OwnedSourceInfo("inventory", "Inventory", 0xFF8FB2E7.toInt()),
        OwnedSourceInfo("forge", "In the forge", 0xFFE8875A.toInt()),
    )
    val COIN_COLOR = 0xFFE0C25A.toInt()

    fun source(id: String): OwnedSourceInfo? = SOURCES.firstOrNull { it.id == id }

    private var last: OwnedSnapshot? = null
    private var lastInventory: List<Pair<String, Int>> = emptyList()
    private var lastProfile: String? = null

    /** Rebuilt only when a source's data changed (search entry lists, sack bucket, inventory, forge slots). */
    fun snapshot(): OwnedSnapshot {
        val search = SearchEverywhere.sources.filter { it.enabled() && it.id != SackSearchSource.ID } // sacks are counted below, also with the search toggle off
        val lists = search.map { it.entries() }
        val sackData = if (SackState.enabled()) SackState.allCounts() else null
        val slots = ForgeStore.read().slots
        val inventory = inventory()
        val profile = SkyblockProfileState.current
        val prev = last
        if (prev != null && profile == lastProfile && inventory == lastInventory && prev.sackData === sackData && prev.forgeSlots === slots &&
            prev.searchLists.size == lists.size && lists.indices.all { prev.searchLists[it] === lists[it] }) return prev
        lastInventory = inventory
        lastProfile = profile
        return build(search, lists, sackData, slots, inventory).also {
            last = it
            ForgeItems.ownedItems(it.items)
        }
    }

    private fun inventory(): List<Pair<String, Int>> {
        val player = Minecraft.getInstance().player ?: return emptyList()
        val inv = player.inventory
        val out = ArrayList<Pair<String, Int>>()
        for (i in 0 until inv.containerSize) {
            val stack = inv.getItem(i)
            if (stack.isEmpty) continue
            ForgeItems.keyOf(stack)?.let { out += it to stack.count }
        }
        return out
    }

    private fun build(
        search: List<SearchSource>,
        lists: List<List<SearchEntry>>,
        sacks: Map<String, com.soulreturns.features.sacks.SackCount>?,
        slots: List<ForgeSlotRead>,
        inventory: List<Pair<String, Int>>,
    ): OwnedSnapshot {
        val parts = HashMap<String, MutableList<OwnedPart>>()
        val items = HashMap<String, StoredChestItem>()
        val known = LinkedHashMap<String, Boolean>()
        for ((i, source) in search.withIndex()) {
            known[source.id] = lists[i].isNotEmpty()
            // Per id: count and stacks per place, in the source's own place order.
            val byId = LinkedHashMap<String, LinkedHashMap<String, Triple<SearchEntry, Long, Int>>>()
            for (entry in lists[i]) for (item in entry.items) {
                val id = item?.skyblockId ?: continue
                if (item.count <= 0) continue
                items.putIfAbsent(id, item)
                val places = byId.getOrPut(id) { LinkedHashMap() }
                val prev = places[entry.place.key]
                places[entry.place.key] = Triple(entry, (prev?.second ?: 0L) + item.count, (prev?.third ?: 0) + 1)
            }
            for ((id, places) in byId) {
                val hits = places.values.map { SearchHit(it.first.place, it.second, it.third) }
                parts.getOrPut(id) { ArrayList() } += OwnedPart(source.id, hits.sumOf { it.count }, source.where(hits), hits)
            }
        }
        known["sacks"] = sacks != null && sacks.isNotEmpty() && SkyblockProfileState.current != null
        if (sacks != null) for ((id, c) in sacks) if (c.count > 0) parts.getOrPut(id) { ArrayList() } += OwnedPart("sacks", c.count, "sacks")
        known["inventory"] = Minecraft.getInstance().player != null
        val carried = LinkedHashMap<String, Long>()
        for ((id, n) in inventory) carried.merge(id, n.toLong(), Long::plus)
        for ((id, n) in carried) parts.getOrPut(id) { ArrayList() } += OwnedPart("inventory", n, "carried")
        known["forge"] = slots.isNotEmpty()
        val running = LinkedHashMap<String, Long>()
        for (s in slots) if (s.state == "busy" && s.outputId != null) running.merge(s.outputId, 1L, Long::plus)
        for ((id, n) in running) parts.getOrPut(id) { ArrayList() } += OwnedPart("forge", n, "running")
        // Breakdown order = SOURCES order (search sources not listed there follow them).
        val order = SOURCES.map { it.id }
        val sorted = parts.mapValues { (_, list) -> list.sortedBy { order.indexOf(it.source).let { i -> if (i < 0) order.size else i } } }
        return OwnedSnapshot(sorted, known, lists, sacks, slots, purse(), bank(), items)
    }

    private val PURSE = Regex("""^(?:Purse|Piggy): ([\d,]+)""")

    /** Purse from the scoreboard sidebar, else the profile API's `currencies.coin_purse`. */
    private fun purse(): Long? {
        val connection = Minecraft.getInstance().player?.connection
        val scoreboard = connection?.scoreboard()
        val objective = scoreboard?.getDisplayObjective(DisplaySlot.SIDEBAR)
        if (scoreboard != null && objective != null) {
            for (entry in scoreboard.listPlayerScores(objective)) {
                val team = scoreboard.getPlayersTeam(entry.owner())
                val text = if (team == null) entry.ownerName().string else PlayerTeam.formatNameForTeam(team, entry.ownerName()).string
                PURSE.find(MessageDetector.stripColorCodes(text).trim())?.let { return it.groupValues[1].replace(",", "").toLongOrNull() }
            }
        }
        val member = selfMember() ?: return null
        return ((member["currencies"] as? JsonObject)?.get("coin_purse"))?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
    }

    /** The member's own bank account from the profile API, when Hypixel reports one. */
    private fun bank(): Long? {
        val member = selfMember() ?: return null
        return ((member["profile"] as? JsonObject)?.get("bank_account"))?.jsonPrimitive?.longOrNull
            ?: ((member["profile"] as? JsonObject)?.get("bank_account"))?.jsonPrimitive?.content?.toDoubleOrNull()?.toLong()
    }

    private fun selfMember(): JsonObject? {
        val snapshot = SkyblockSelfProfile.snapshot ?: return null
        if (!snapshot.profileName.equals(SkyblockProfileState.current, ignoreCase = true)) return null
        return snapshot.member.raw
    }
}
