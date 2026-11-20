package com.soulreturns.features.search.forge

import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.items.ItemIcons
import com.soulreturns.data.items.ModelIndex
import com.soulreturns.data.items.PackModels
import com.soulreturns.data.prices.MarketItemKey
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.search.SearchItems
import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import java.util.Locale

/** Names, colors and icons of recipe ids, and the recipe id of a real stack. */
object ForgeItems {
    class Info(val name: String, val color: Int, val look: InventoryButtonIcons.ItemLook)

    private val COIN_LOOK = InventoryButtonIcons.ItemLook("minecraft:gold_nugget")
    private const val PAPER = "minecraft:paper"
    private val cache = HashMap<String, Info>()
    private var cacheBook: ForgeBookData? = null
    private var cacheCatalog: Any? = null
    private var cacheModels: ModelIndex? = null
    /** Looks / names of items the player owns (Search everywhere rows), by recipe id. */
    @Volatile private var owned: Map<String, StoredChestItem> = emptyMap()

    /** The exact looks Forge has seen (menu entries, learned looks, owned items), for every other icon ([ItemIcons]). */
    internal object Seen : ItemIcons.SeenLooks {
        private var book: ForgeBookData? = null
        private var ownedSeen: Map<String, StoredChestItem>? = null
        private var current = Any()

        override val stamp: Any
            @Synchronized get() {
                val b = ForgeStore.book()
                val o = owned
                if (b !== book || o !== ownedSeen) {
                    book = b
                    ownedSeen = o
                    current = Any()
                }
                return current
            }

        override fun look(id: String): InventoryButtonIcons.ItemLook? {
            val b = ForgeStore.book()
            return b.recipes[id]?.look?.look() ?: b.looks[id]?.look() ?: owned[id]?.look
        }
    }

    /**
     * The recipe catalog's id for a stack (`/recipes` ids): `custom_data.id` as is (`INK_SACK:4`), pets as
     * `TYPE;tier` and single-enchant books as `ENCHANTMENT_NAME_n` (the market key's rules, rule://catalogs-prices).
     */
    fun keyOf(stack: ItemStack): String? {
        val id = SkyblockItemUtils.getSkyblockId(stack) ?: return null
        if (id != "PET" && id != "ENCHANTED_BOOK") return id
        return MarketItemKey.of(stack) ?: id
    }

    internal fun ownedItems(items: Map<String, StoredChestItem>) {
        if (items != owned) { owned = items; cache.clear() }
    }

    fun info(id: String): Info {
        val book = ForgeStore.book()
        val catalog = ItemCatalogClient.snapshot
        val models = PackModels.index
        if (book !== cacheBook || catalog !== cacheCatalog || models !== cacheModels || cache.size > 1024) {
            cache.clear()
            cacheBook = book
            cacheCatalog = catalog
            cacheModels = models
        }
        return cache.getOrPut(id) { build(id, book) }
    }

    fun name(id: String) = info(id).name
    fun color(id: String) = info(id).color

    fun icon(g: GuiGraphicsExtractor, id: String, x: Int, y: Int, size: Int) {
        val stack = InventoryButtonIcons.stack(info(id).look)
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(size / 16f, size / 16f)
        g.fakeItem(stack, 0, 0)
        g.pose().popMatrix()
    }

    private fun build(id: String, book: ForgeBookData): Info {
        if (id == ForgeEngine.COINS) return Info("Coins", 0xFFFFAA00.toInt(), COIN_LOOK)
        val entry = book.recipes[id]
        val catalog = ItemCatalogClient.byId(id)
        val stored = owned[id]
        val name = entry?.name ?: ItemIcons.cleanName(catalog?.displayName)
            ?: stored?.let(SearchItems::name) ?: petName(id) ?: id
        val color = SkyblockRarity.forName(catalog?.rarity)?.color ?: stored?.let(SearchItems::color) ?: petColor(id) ?: 0xFFFFFFFF.toInt()
        // Exact stack seen anywhere > the loaded Hypixel pack's model for the id > catalog material > paper (ItemIcons.choose).
        val seen = entry?.look?.look() ?: book.looks[id]?.look() ?: stored?.look
        val look = ItemIcons.lookWith(seen, id, name) ?: InventoryButtonIcons.ItemLook(PAPER, glint = name.startsWith("Enchanted "))
        return Info(name, color, look)
    }

    /** `TYRANNOSAURUS;4` → `Tyrannosaurus` when no catalog row names the pet. */
    private fun petName(id: String): String? {
        val type = id.substringBefore(';', "").ifEmpty { return null }
        return type.split('_').joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
    }

    private val PET_RARITY = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    private fun petColor(id: String): Int? {
        val tier = id.substringAfter(';', "").takeWhile { it.isDigit() }.toIntOrNull() ?: return null
        return SkyblockRarity.forName(PET_RARITY.getOrNull(tier))?.color
    }
}
