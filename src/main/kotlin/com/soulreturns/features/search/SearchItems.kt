package com.soulreturns.features.search

import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.features.islandchests.StoredChestItem
import com.soulreturns.features.islandchests.searchColor
import com.soulreturns.features.islandchests.searchName
import com.soulreturns.util.SkyblockItemUtils
import com.soulreturns.util.toLegacyText
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.core.component.DataComponents
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.item.ItemStack
import java.text.NumberFormat
import java.util.Locale

/** Item identity, labels and icons shared by every search source, the screen and the locators. */
object SearchItems {
    private val numbers = NumberFormat.getIntegerInstance(Locale.ROOT)
    private val colors = HashMap<StoredChestItem, Int>()
    private val names = HashMap<StoredChestItem, String>()

    fun em(size: Number) = size.toFloat() * MsdfFont.LEGACY_EM
    fun number(count: Long): String = numbers.format(count)

    /** The searchable identity of a real stack (registry id, SkyBlock id, name, lore, look). */
    fun of(stack: ItemStack): StoredChestItem? {
        if (stack.isEmpty || stack.count <= 0) return null
        return StoredChestItem(BuiltInRegistries.ITEM.getKey(stack.item).toString(), SkyblockItemUtils.getSkyblockId(stack),
            stack.hoverName.toLegacyText(), stack.get(DataComponents.LORE)?.lines()?.map { it.toLegacyText() }.orEmpty(),
            stack.count, InventoryButtonIcons.look(stack))
    }

    fun name(item: StoredChestItem): String {
        if (names.size > 512) names.clear()
        return names.getOrPut(item) { item.searchName() }
    }

    fun color(item: StoredChestItem): Int {
        if (colors.size > 512) colors.clear()
        return colors.getOrPut(item) { item.searchColor() }
    }

    fun icon(g: GuiGraphicsExtractor, item: StoredChestItem, x: Int, y: Int, size: Int) {
        val stack = InventoryButtonIcons.stack(item.look ?: InventoryButtonIcons.ItemLook(item.material))
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), y.toFloat())
        g.pose().scale(size / 16f, size / 16f)
        g.fakeItem(stack, 0, 0)
        g.pose().popMatrix()
    }
}
