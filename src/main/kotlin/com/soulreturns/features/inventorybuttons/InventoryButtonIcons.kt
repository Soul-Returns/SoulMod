package com.soulreturns.features.inventorybuttons

import com.google.gson.JsonParser
import com.mojang.serialization.JsonOps
import com.soulreturns.config.InventoryButton
import com.soulreturns.util.SoulLogger
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.resources.Identifier
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ResolvableProfile

/**
 * Builds the [ItemStack] a button draws, and captures one from an item the player clicked.
 *
 * Only three things survive the capture: the item, a player-head profile and an `item_model`
 * override. That is what makes a SkyBlock icon look like itself — nearly every one is a textured
 * head — while lore, enchantments and custom data, which never show on a 16 px icon, stay behind.
 * Stacks are cached by those fields, because decoding a profile every frame per button is waste.
 */
object InventoryButtonIcons {
    private val logger = SoulLogger("Soul/InventoryButtons")

    /** What an icon looks like: the item, a head profile (JSON), an `item_model` override and glint. */
    data class ItemLook(val item: String, val skin: String = "", val model: String = "", val glint: Boolean = false)

    private val cache = HashMap<ItemLook, ItemStack>()

    /** Called on the render thread only (overlay and editor), so the cache needs no lock. */
    fun stack(button: InventoryButton): ItemStack = stack(ItemLook(button.item, button.skin, button.model, button.glint))

    /** The stack for [look], cached; render thread only. Other features with stored icons use it too. */
    fun stack(look: ItemLook): ItemStack {
        cache[look]?.let { return it }
        if (cache.size > 256) cache.clear()
        val built = build(look)
        cache[look] = built
        return built
    }

    /** The registered item for [id] (`ender_chest` or `minecraft:ender_chest`), or null if unknown. */
    fun item(id: String): net.minecraft.world.item.Item? {
        val parsed = Identifier.tryParse(id.trim().lowercase()) ?: return null
        return BuiltInRegistries.ITEM.getOptional(parsed).orElse(null)?.takeIf { it != Items.AIR }
    }

    /** Canonical `namespace:path` for [id], or null if no such item exists. */
    fun canonicalId(id: String): String? = item(id)?.let { BuiltInRegistries.ITEM.getKey(it).toString() }

    /** Up to [limit] item ids whose path contains [query], exact and prefix matches first. */
    fun suggestions(query: String, limit: Int): List<String> {
        val q = query.trim().lowercase().removePrefix("minecraft:")
        if (q.isEmpty()) return emptyList()
        return BuiltInRegistries.ITEM.keySet()
            .asSequence()
            .filter { it.path.contains(q) && it.path != "air" }
            .sortedWith(compareBy({ if (it.path == q) 0 else if (it.path.startsWith(q)) 1 else 2 }, { it.path.length }, { it.path }))
            .take(limit)
            .map { it.toString() }
            .toList()
    }

    /** [button] with its icon replaced by what [stack] looks like. */
    fun capture(button: InventoryButton, stack: ItemStack): InventoryButton {
        val look = look(stack)
        return button.copy(item = look.item, skin = look.skin, model = look.model, glint = look.glint)
    }

    /** What [stack] looks like on a 16 px icon; lore, enchantments and custom data stay behind. */
    fun look(stack: ItemStack): ItemLook {
        val skin = stack.get(DataComponents.PROFILE)?.let { profile ->
            ResolvableProfile.CODEC.encodeStart(JsonOps.INSTANCE, profile).result().map { it.toString() }.orElse("")
        }.orEmpty()
        val model = stack.get(DataComponents.ITEM_MODEL)
        val defaultModel = stack.item.defaultInstance.get(DataComponents.ITEM_MODEL)
        return ItemLook(
            item = BuiltInRegistries.ITEM.getKey(stack.item).toString(),
            skin = skin,
            model = if (model != null && model != defaultModel) model.toString() else "",
            glint = stack.hasFoil(),
        )
    }

    /** A fresh, uncached stack for [key]; safe off the render thread. */
    fun build(key: ItemLook): ItemStack {
        val stack = (item(key.item) ?: Items.BARRIER).defaultInstance.copy()
        if (key.skin.isNotEmpty()) {
            try {
                ResolvableProfile.CODEC.parse(JsonOps.INSTANCE, JsonParser.parseString(key.skin)).result()
                    .ifPresent { stack.set(DataComponents.PROFILE, it) }
            } catch (e: RuntimeException) {
                logger.warn("Ignoring an unreadable head profile on an inventory button: ${e.message}")
            }
        }
        if (key.model.isNotEmpty()) Identifier.tryParse(key.model)?.let { stack.set(DataComponents.ITEM_MODEL, it) }
        if (key.glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }
}
