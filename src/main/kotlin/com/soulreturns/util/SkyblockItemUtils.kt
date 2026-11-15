package com.soulreturns.util

import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.player.Player
import net.minecraft.world.item.ItemStack

object SkyblockItemUtils {
    /**
     * Extracts the Skyblock item ID from an ItemStack's custom data. Hypixel stores it at
     * `DataComponents.CUSTOM_DATA` → NBT `"id"` (e.g. `"HYPERION"`, `"ENCHANTED_BOOK"`).
     *
     * @return The Skyblock ID if present, null otherwise (vanilla items, empty stacks).
     */
    fun getSkyblockId(stack: ItemStack?): String? {
        if (stack == null || stack.isEmpty) return null
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return null
            val id = customData.copyTag().getStringOr("id", "")
            id.ifEmpty { null }
        } catch (_: Exception) {
            // Corrupted / unexpected NBT — treat as "no Skyblock id".
            null
        }
    }

    /** The four slots that count as armour for a SkyBlock set bonus. */
    private val ARMOR_SLOTS: List<EquipmentSlot> =
        listOf(EquipmentSlot.HEAD, EquipmentSlot.CHEST, EquipmentSlot.LEGS, EquipmentSlot.FEET)

    /**
     * A SkyBlock enchant's level on [stack], read from `CUSTOM_DATA.enchantments.<id>` — Hypixel's own
     * flat `enchantName: Int` map, which has nothing to do with vanilla enchantments.
     *
     * 0 when absent, malformed or empty, so a caller can treat the result as "level, or 0 for not
     * present" without a null branch.
     */
    fun getSkyblockEnchantLevel(
        stack: ItemStack?,
        enchantId: String,
    ): Int {
        if (stack == null || stack.isEmpty) return 0
        return try {
            val customData = stack.get(DataComponents.CUSTOM_DATA) ?: return 0
            customData.copyTag().getCompoundOrEmpty("enchantments").getIntOr(enchantId, 0)
        } catch (_: Exception) {
            0
        }
    }

    /**
     * Sum of [enchantId] across all four armour slots.
     *
     * **Sum, not maximum**: Hypixel's ultimate armour enchants stack additively across the set, so a
     * full set of Legion 5 contributes 20 to the boost formula rather than 5. Using the highest level
     * instead is a quarter of the real number and reads as a plausible one.
     *
     * Four slot lookups and four NBT reads — cheap enough to call on a chat line, which is the only
     * thing that calls it.
     */
    fun summedArmorEnchantLevel(
        player: Player?,
        enchantId: String,
    ): Int {
        if (player == null) return 0
        var total = 0
        for (slot in ARMOR_SLOTS) total += getSkyblockEnchantLevel(player.getItemBySlot(slot), enchantId)
        return total
    }
}
