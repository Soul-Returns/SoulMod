package com.soulreturns.features.pets

import com.soulreturns.data.items.HeadSkins
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.items.PackModels
import com.soulreturns.data.recipes.RecipeCatalogClient
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import net.minecraft.world.item.ItemStack
import java.util.Locale

/**
 * Real pet heads for pets the player is not holding: the head seen in a Pets menu (your own pets, exact) > the
 * skin's head (`/items` `PET_SKIN_<skin>` head texture) > the base pet's head for its tier, then its type
 * (`/recipes` `petHeads`, NotEnoughUpdates-REPO) > null, and the caller draws its rarity tile. A pet nothing
 * knows never becomes a bare (Steve) head.
 */
object PetHeads {
    private const val PLAYER_HEAD = "minecraft:player_head"
    private const val MAX_CACHED = 256
    private val TIERS = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "MYTHIC")

    /** The head texture hash of a pet; [skinTexture] reads a catalog row's skin, [heads] is `petHeads` (pets and `PET_SKIN_*`). Pure. */
    internal fun texture(type: String, tier: String?, skin: String?, skinTexture: (String) -> String?, heads: Map<String, String>): String? {
        if (!skin.isNullOrEmpty()) {
            val id = "PET_SKIN_" + skin.removePrefix("PET_SKIN_")
            (valid(skinTexture(id)) ?: valid(heads[id]))?.let { return it }
        }
        val index = TIERS.indexOf(tier?.uppercase(Locale.ROOT))
        if (index >= 0) valid(heads["$type;$index"])?.let { return it }
        return valid(heads[type])
    }

    private fun valid(hash: String?): String? = hash?.lowercase(Locale.ROOT)?.takeIf(HeadSkins::valid)

    /** A menu-captured icon counts only when it is a textured head (or a model the loaded pack has). */
    internal fun usable(seen: PetIconLook?, packHas: (String) -> Boolean): Boolean {
        if (seen == null) return false
        if (seen.model.isNotEmpty()) return !seen.model.startsWith("${PackModels.NAMESPACE}:") || packHas(seen.model)
        return seen.item != PLAYER_HEAD || seen.skin.isNotEmpty()
    }

    private val cache = object : LinkedHashMap<String, Entry>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Entry>?): Boolean = size > MAX_CACHED
    }
    private class Entry(val stack: ItemStack?)
    private var cacheItems: Any? = null
    private var cacheRecipes: Any? = null
    private var cacheModels: Any? = null

    /** The head stack for a pet ([seen] = its icon from a Pets menu, own profile only), cached; null = draw the tile. */
    fun icon(type: String, tier: String?, skin: String?, seen: PetIconLook?): ItemStack? {
        val items = ItemCatalogClient.snapshot
        val recipes = RecipeCatalogClient.index
        val models = PackModels.index
        val key = "$type|$tier|$skin|$seen"
        synchronized(cache) {
            if (items !== cacheItems || recipes !== cacheRecipes || models !== cacheModels) {
                cache.clear()
                cacheItems = items
                cacheRecipes = recipes
                cacheModels = models
            }
            cache[key]?.let { return it.stack }
        }
        val look = if (seen != null && usable(seen, models::contains)) {
            InventoryButtonIcons.ItemLook(seen.item, seen.skin, seen.model, seen.glint)
        } else {
            texture(type, tier, skin, { ItemCatalogClient.byId(it)?.skin }, recipes.petHeads)
                ?.let(HeadSkins::profileJson)?.let { InventoryButtonIcons.ItemLook(PLAYER_HEAD, skin = it) }
        }
        val stack = look?.let(InventoryButtonIcons::build)
        synchronized(cache) { cache[key] = Entry(stack) }
        return stack
    }
}
