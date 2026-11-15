package com.soulreturns.ui

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * The container slot mark of the island chest locator (blue `0xFF72BBFF`), Search everywhere's storage
 * pages and completed commissions: a 16×16 tint (the edge color at 25 %) with a 1 px inset edge.
 * Draw it behind the item.
 */
object SlotMark {
    /** Done / completed, Hypixel's `§a`. */
    val DONE: Int = 0xFF55FF55.toInt()

    fun draw(g: GuiGraphicsExtractor, x: Int, y: Int, edge: Int) {
        g.fill(x, y, x + 16, y + 16, (edge and 0xFFFFFF) or 0x40000000)
        g.fill(x, y, x + 16, y + 1, edge)
        g.fill(x, y + 15, x + 16, y + 16, edge)
        g.fill(x, y + 1, x + 1, y + 15, edge)
        g.fill(x + 15, y + 1, x + 16, y + 15, edge)
    }
}
