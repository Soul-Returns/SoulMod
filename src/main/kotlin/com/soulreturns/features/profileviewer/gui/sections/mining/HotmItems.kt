package com.soulreturns.features.profileviewer.gui.sections.mining

import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import net.minecraft.world.item.component.ItemLore

/**
 * Vanilla stacks for [HotmLore.Spec]s, so a node draws the menu's own icon and shows its lore as the
 * game's tooltip. The tree asks every frame; equal specs reuse one stack (bounded LRU: a tree is 46
 * nodes, a few players' trees fit).
 */
object HotmItems {
    private const val MAX = 256

    private val cache = object : LinkedHashMap<HotmLore.Spec, ItemStack>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<HotmLore.Spec, ItemStack>?): Boolean = size > MAX
    }

    fun stack(spec: HotmLore.Spec): ItemStack = synchronized(cache) { cache.getOrPut(spec) { build(spec) } }

    private fun build(spec: HotmLore.Spec): ItemStack {
        val item = when (spec.icon) {
            HotmLore.DIAMOND -> Items.DIAMOND
            HotmLore.EMERALD -> Items.EMERALD
            HotmLore.EMERALD_BLOCK -> Items.EMERALD_BLOCK
            HotmLore.COAL_BLOCK -> Items.COAL_BLOCK
            HotmLore.CORE -> Items.REDSTONE_BLOCK
            else -> Items.COAL
        }
        val stack = ItemStack(item)
        stack.set(DataComponents.CUSTOM_NAME, line(spec.name))
        stack.set(DataComponents.LORE, ItemLore(spec.lore.map(::line)))
        if (spec.glint) stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
        return stack
    }

    /** `§` text as Hypixel's lore renders it: not the italic purple vanilla gives custom names and lore. */
    private fun line(text: String): Component = Component.literal(text).withStyle { it.withItalic(false) }
}
