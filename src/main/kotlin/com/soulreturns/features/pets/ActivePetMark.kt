package com.soulreturns.features.pets

import com.soulreturns.config.cfg
import com.soulreturns.ui.SlotMark
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.item.ItemStack

/** Pure lore test for [ActivePetMark]. */
object ActivePetLore {
    /** The one action line only the summoned pet's lore carries; the others read `Left-click to summon!`. */
    private const val DESPAWN = "Click to despawn!"

    fun isActive(lore: List<String>): Boolean = lore.any { MessageDetector.stripColorCodes(it).trim() == DESPAWN }
}

/**
 * "Highlight active pet": marks the pet whose lore has the `Click to despawn!` line in the Pets menu with
 * [SlotMark] (drawn behind the item at `afterBackground`, like completed commissions). Lore is read from
 * the menu only; a summoned pet on another page leaves this page unmarked. Lore is re-read per slot only
 * when the slot's stack instance changes. Client thread only.
 */
object ActivePetMark {
    private var registered = false
    private var screen: AbstractContainerScreen<*>? = null
    private val read = HashMap<Int, ItemStack>()
    private var activeSlots: Set<Int> = emptySet()

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register { _, s, _, _ ->
            if (s is AbstractContainerScreen<*>) {
                ScreenEvents.afterBackground(s).register { _, g, _, _, _ -> draw(s, g) }
            }
        }
    }

    private fun draw(s: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        val c = cfg.petHud
        if (!c.highlightActive) return
        if (s !== screen) {
            screen = s
            read.clear()
            activeSlots = emptySet()
        }
        if (!PetState.isPetsMenu(MessageDetector.stripColorCodes(s.title.string).trim())) return
        var changed = false
        for ((index, slot) in s.menu.slots.withIndex()) {
            if (slot.container is Inventory) continue
            if (read[index] !== slot.item) {
                read[index] = slot.item
                changed = true
            }
        }
        if (changed) {
            val active = HashSet<Int>(2)
            for ((index, slot) in s.menu.slots.withIndex()) {
                if (slot.container is Inventory) continue
                val lore = slot.item.takeUnless { it.isEmpty }?.get(DataComponents.LORE)?.lines() ?: continue
                if (ActivePetLore.isActive(lore.map { it.toLegacyText() })) active += index
            }
            activeSlots = active
        }
        val color = c.highlightColor.effective()
        for (index in activeSlots) {
            val slot = s.menu.slots[index]
            SlotMark.draw(g, s.leftPos + slot.x, s.topPos + slot.y, color)
        }
    }
}
