package com.soulreturns.util

import net.minecraft.component.DataComponentTypes
import net.minecraft.item.ItemStack
import net.minecraft.nbt.NbtCompound

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
            val customData = stack.get(DataComponentTypes.CUSTOM_DATA) ?: return null

            // Extract NBT from the component
            val nbt: NbtCompound = customData.copyNbt()

            // Check if the "id" key exists
            if (!nbt.contains("id")) return null

            // Return the Skyblock ID as a string
            // In Minecraft 1.21.5+, getString with default value returns String directly
            val idValue = nbt.getString("id", "")
            return if (idValue.isEmpty()) null else idValue
        } catch (e: Exception) {
            // Silently handle any errors (corrupted data, etc.)
            return null
        }
    }
}
