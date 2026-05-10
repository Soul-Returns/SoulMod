package com.soulreturns.gui

import net.minecraft.world.item.ItemStack

/**
 * Maps icon keys (used by GUI elements) to concrete [ItemStack]s. Hosts register mappings
 * at mod init; resolution falls back to [ItemStack.EMPTY] when the key is unknown.
 */
object GuiIconRegistry {
    private val icons: MutableMap<String, ItemStack> = mutableMapOf()

    fun registerIcon(
        key: String,
        stack: ItemStack
    ) {
        icons[key] = stack
    }

    fun resolve(key: String): ItemStack = icons[key] ?: ItemStack.EMPTY
}
