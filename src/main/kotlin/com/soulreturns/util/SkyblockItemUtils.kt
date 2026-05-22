package com.soulreturns.util

import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

object SkyblockItemUtils {
    /**
     * Extracts the Skyblock item ID from an ItemStack's custom data
     *
     * In Minecraft 1.21+, Skyblock data is stored in:
     * DataComponentTypes.CUSTOM_DATA -> NbtComponent -> "id" key
     *
     * @param stack The ItemStack to check
     * @return The Skyblock ID if present, null otherwise
     */
    fun getSkyblockId(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty) return null

        try {
            // Get the CUSTOM_DATA component
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null

            // Extract NBT from the component
            val nbt: CompoundTag = customData.copyTag()

            // Check if the "id" key exists
            if (!nbt.contains("id")) return null

            // Return the Skyblock ID as a string
            // In Minecraft 1.21.5+, getString with default value returns String directly
            val idValue = nbt.getStringOr("id", "")
            return if (idValue.isEmpty()) null else idValue
        } catch (e: Exception) {
            // Silently handle any errors (corrupted data, etc.)
            return null
        }
    }

    /**
     * Returns the bazaar-compatible price-lookup id for [stack]. For most items this is
     * identical to [getSkyblockId]. For enchanted books with a single enchant in NBT, it
     * returns the bazaar's `ENCHANTMENT_<NAME>_<LEVEL>` form so price lookups + inventory
     * deltas + drop attribution treat every (enchant, level) pair as its own item.
     *
     * **Multi-enchant books** (rare — usually only from Anvil combines, not from drops)
     * return null: the bazaar has no combined-enchantment product id, and treating a
     * multi-enchant book as one of its enchants would over-credit. The caller's choice
     * what to do with null — `DianaLootWatcher` skips, `PriceCache` returns 0.
     *
     * **No-enchant Enchanted Book stacks** (broken / placeholder NBT) return the bare
     * `"ENCHANTED_BOOK"` so the row isn't lost entirely — the price won't resolve from
     * bazaar but it shows up in inventory counts.
     */
    fun getPriceLookupId(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty) return null
        val baseId = getSkyblockId(stack) ?: return null
        if (baseId != "ENCHANTED_BOOK") return baseId
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return baseId
            val nbt = customData.copyTag()
            val enchantments = nbt.getCompoundOrEmpty("enchantments")
            val keys = enchantments.keySet()
            // Bazaar has no combined-enchant product — multi-enchant books don't price.
            if (keys.size != 1) return if (keys.isEmpty()) baseId else null
            val name = keys.first()
            val level = enchantments.getIntOr(name, 0)
            if (level <= 0) return baseId
            "ENCHANTMENT_${name.uppercase()}_$level"
        } catch (e: Exception) {
            baseId
        }
    }

    /**
     * Look up a Skyblock-style enchant level (`components.custom_data.enchantments.<id>`) on
     * [stack]. Returns `0` when the enchant is absent, the NBT is malformed, or the stack is
     * empty — callers can treat the result as "level (or 0 = not present)".
     *
     * The Skyblock enchant table is a flat map of `enchantName: Int` under the
     * `enchantments` compound. See `legion.json` / `bobbin.json` test fixtures for the wire
     * shape.
     */
    fun getSkyblockEnchantLevel(
        stack: ItemStack?,
        enchantId: String,
    ): Int {
        if (stack == null || stack.isEmpty) return 0
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            val nbt = customData.copyTag()
            val enchantments = nbt.getCompoundOrEmpty("enchantments")
            enchantments.getIntOr(enchantId, 0)
        } catch (e: Exception) {
            0
        }
    }

    /** Equipment slots that count as "armor" for enchant gating. */
    private val ARMOR_SLOTS: List<EquipmentSlot> =
        listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /**
     * Returns true if any of the player's equipped armor pieces carries the named Skyblock
     * enchant (level > 0). Used by HUD visibility gates that depend on the player wearing
     * the enchant — e.g. Legion (`ultimate_legion`) or Bobbin Time (`ultimate_bobbin_time`).
     *
     * Cheap: 4 slot lookups + 4 NBT reads per call, fine to invoke per frame from a HUD
     * composable.
     */
    fun hasArmorEnchant(
        player: Player?,
        enchantId: String,
    ): Boolean = highestArmorEnchantLevel(player, enchantId) > 0

    /**
     * Highest level of [enchantId] found across the player's equipped armor pieces. Returns
     * `0` when no piece carries the enchant. Use this for gating logic ("is the enchant
     * present at all?") rather than formula inputs — most multi-piece-armor boosts stack
     * across pieces, see [summedArmorEnchantLevel].
     */
    fun highestArmorEnchantLevel(
        player: Player?,
        enchantId: String,
    ): Int {
        if (player == null) return 0
        var best = 0
        for (slot in ARMOR_SLOTS) {
            val stack = player.getItemBySlot(slot)
            val level = getSkyblockEnchantLevel(stack, enchantId)
            if (level > best) best = level
        }
        return best
    }

    /**
     * Sum of [enchantId] levels across all four armor slots. Returns `0` when no piece
     * carries the enchant. Hypixel's ultimate-tier armor enchants (Legion, Bobbin Time, …)
     * stack additively across the full armor set — e.g. 4× Legion 5 contributes a combined
     * level of 20 to the boost formula, not just 5. Use this for boost-percentage maths;
     * use [highestArmorEnchantLevel] for "is the enchant present" gating.
     */
    fun summedArmorEnchantLevel(
        player: Player?,
        enchantId: String,
    ): Int {
        if (player == null) return 0
        var total = 0
        for (slot in ARMOR_SLOTS) {
            total += getSkyblockEnchantLevel(player.getItemBySlot(slot), enchantId)
        }
        return total
    }
}
