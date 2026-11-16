package com.soulreturns.features.search.forge

import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.items.LegacyMaterials
import com.soulreturns.data.prices.MarketItemKey
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.search.SearchItems
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.world.item.ItemStack
import java.util.Locale

/** Names, colors and icons of recipe ids, and the recipe id of a real stack. */
object ForgeItems {
    class Info(val name: String, val color: Int, val look: InventoryButtonIcons.ItemLook)

    private val COIN_LOOK = InventoryButtonIcons.ItemLook("minecraft:gold_nugget")
    private val PUA = Regex("^[\\uE000-\\uF8FF]\\s*")
    private val cache = HashMap<String, Info>()
    private var cacheBook: ForgeBookData? = null
    private var cacheCatalog: Any? = null
    private var cacheModels: ModelIndex? = null
    /** Looks / names of items the player owns (Search everywhere rows), by recipe id. */
    @Volatile private var owned: Map<String, StoredChestItem> = emptyMap()

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
        val models = ForgeModels.index
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
        val name = entry?.name ?: catalog?.displayName?.let { MessageDetector.stripColorCodes(it).replace(PUA, "").trim() }?.takeIf { it.isNotEmpty() }
            ?: stored?.let(SearchItems::name) ?: petName(id) ?: id
        val color = SkyblockRarity.forName(catalog?.rarity)?.color ?: stored?.let(SearchItems::color) ?: petColor(id) ?: 0xFFFFFFFF.toInt()
        // Exact stack seen anywhere > the loaded Hypixel pack's model for the id > catalog material > paper.
        val look = entry?.look?.look() ?: book.looks[id]?.look() ?: stored?.look ?: catalogLook(id, catalog?.material, name)
        return Info(name, color, look)
    }

    /**
     * The `/items` material as a modern item (`LegacyMaterials`; legacy data value from ids like `INK_SACK:4`),
     * with the `item_model` of Hypixel's pack when it is loaded and names this id ([ForgeModels]), and the glint
     * Hypixel gives `Enchanted …` items (the mock's rule). Paper only for ids the catalog lacks.
     */
    private fun catalogLook(id: String, material: String?, name: String): InventoryButtonIcons.ItemLook {
        val damage = id.substringAfter(':', "").toIntOrNull() ?: 0
        val item = LegacyMaterials.itemId(material, damage) ?: "minecraft:paper"
        val model = ForgeModels.index.resolve(id, name).orEmpty()
        return InventoryButtonIcons.ItemLook(item, model = model, glint = name.startsWith("Enchanted "))
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
