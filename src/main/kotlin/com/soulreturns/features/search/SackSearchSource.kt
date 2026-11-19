package com.soulreturns.features.search

import com.soulreturns.config.cfg
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.items.LegacyMaterials
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dungeons.DungeonCommandGuard
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.islandchests.IslandChestLayout
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.search.forge.ForgeModels
import com.soulreturns.features.sacks.SackCount
import com.soulreturns.features.sacks.SackSource
import com.soulreturns.features.sacks.SackState
import net.minecraft.client.Minecraft

/** Item appearance resolved outside the pure snapshot adapter. */
internal class SackItemInfo(val name: String?, val material: String?, val look: InventoryButtonIcons.ItemLook?)

/** Unknown counts and known zero counts never produce results. */
internal object SackSearchEntries {
    class Place(private val counts: Map<String, SackCount>) : IdKeyedPlace {
        override val key: String = "sacks"
        override fun count(item: StoredChestItem): Long = counts[item.skyblockId]?.count ?: 0L
    }

    fun build(counts: Map<String, SackCount>, info: (String) -> SackItemInfo): List<SearchEntry> {
        val items = counts.mapNotNull { (id, count) ->
            if (count.count <= 0L) return@mapNotNull null
            val about = info(id)
            StoredChestItem(about.material ?: "minecraft:paper", id, about.name, emptyList(), 1, about.look)
        }
        return if (items.isEmpty()) emptyList() else listOf(SearchEntry(Place(counts), items))
    }
}

/** Reads the tracker's existing bucket; it stores item ids, not which sack holds them. */
internal object SackSearchSource : SearchSource {
    const val ID = "sacks"
    override val id = ID
    override val label = "Sacks"
    override val color = 0xFF7CC48A.toInt()
    override val badgeText = 0xFFA5DDAF.toInt()
    override val badgeBack = 0xFF263B2B.toInt()
    override val emptyTitle = "No sack counts yet"
    override val emptyText = "Open /sacks and look through your sacks once.\nCounts are remembered and kept current from the [Sacks] messages."

    private var source: Map<String, SackCount>? = null
    private var sourceProfile: String? = null
    private var entries: List<SearchEntry> = emptyList()
    private var catalog: Any? = null
    private var models: Any? = null
    private var tracked: Any? = null
    private var names: Map<String, String>? = null

    override fun enabled(): Boolean = cfg.sacks.searchSacks

    private fun counts(): Map<String, SackCount> =
        if (!SkyblockState.onSkyblock || SkyblockProfileState.current == null) emptyMap() else SackState.allCounts()

    override fun entries(): List<SearchEntry> {
        val counts = counts()
        val profile = SackState.profileKey()
        val nextCatalog = ItemCatalogClient.snapshot
        val nextModels = ForgeModels.index
        val nextTracked = cfg.sacks.tracked
        val nextNames = SackState.learnedNames()
        if (counts !== source || profile != sourceProfile || catalog !== nextCatalog ||
            models !== nextModels || tracked !== nextTracked || names !== nextNames) {
            source = counts
            sourceProfile = profile
            catalog = nextCatalog
            models = nextModels
            tracked = nextTracked
            names = nextNames
            entries = SackSearchEntries.build(counts, ::info)
        }
        return entries
    }

    private fun info(id: String): SackItemInfo {
        val tracked = cfg.sacks.tracked.firstOrNull { it.id == id }
        val entry = ItemCatalogClient.byId(id)
        val name = entry?.displayName?.takeIf { it.isNotEmpty() } ?: tracked?.name?.takeIf { it.isNotEmpty() } ?: learnedName(id)
        val material = LegacyMaterials.itemId(entry?.material, id.substringAfter(':', "").toIntOrNull() ?: 0) ?: "minecraft:paper"
        val look = tracked?.let { InventoryButtonIcons.ItemLook(it.item, it.skin, it.model, it.glint) }
            ?: InventoryButtonIcons.ItemLook(material, model = ForgeModels.index.resolve(id, name).orEmpty(), glint = name?.startsWith("Enchanted ") == true)
        return SackItemInfo(name, look.item, look)
    }

    private var learned: Map<String, String>? = null
    private var learnedById: Map<String, String> = emptyMap()

    /** Names learned by the tracker are already normalized. */
    private fun learnedName(id: String): String? {
        val names = SackState.learnedNames()
        if (names !== learned) {
            learned = names
            learnedById = names.entries.associate { it.value to it.key }
        }
        return learnedById[id]
    }

    override fun coverage(): SourceCoverage {
        if (!SkyblockState.onSkyblock || SkyblockProfileState.current == null) return SourceCoverage("sacks locked", "Waiting for your profile · remembered counts stay hidden until it is known", true)
        val total = counts().size
        if (total == 0) return SourceCoverage("sacks not seen yet", "Not counted yet · open /sacks and look through your sacks once", true)
        return SourceCoverage(plural(total, "sack item"), "${plural(total, "item")} counted · from sack menus, [Sacks] messages and the SkyBlock API", false)
    }

    override fun searched(): String = "Searched ${plural(counts().size, "counted sack item")}"

    override fun places(count: Int): String = "in your sacks"

    override fun where(hits: List<SearchHit>): String = "in your sacks"

    override fun section(item: StoredChestItem, itemKey: String, hits: List<SearchHit>): SearchSection {
        val open = SectionAction("Open sacks", accent = true) { openSacks() }
        val count = item.skyblockId?.let(SackState::count)
        val api = count?.source == SackSource.API
        val detail = if (count?.source == SackSource.MENU) "read in a sack menu"
            else if (count?.source == SackSource.CHAT) "from [Sacks] messages"
            else "from the SkyBlock API"
        val card = HitCard(
            "S", "Sacks", hits.sumOf { it.count }, detail,
            warn = if (api && count?.exactAt == 0L) "· not read in a sack yet" else "",
            trailing = count?.updatedAt?.takeIf { it > 0 }?.let { IslandChestLayout.age(it, System.currentTimeMillis()) }.orEmpty(),
            action = open,
        )
        return SearchSection("your sacks", open, "Last known counts · Open sacks sends /sacks to view and refresh them.", false, listOf(card), false)
    }

    internal fun openSacks() {
        val mc = Minecraft.getInstance()
        val connection = mc.connection ?: return
        if (mc.player?.containerMenu !== mc.player?.inventoryMenu || DungeonCommandGuard.blocks("sacks")) return
        connection.sendCommand("sacks")
        SearchEverywhere.leave()
    }
}
