package com.soulreturns.features.inventory

import com.soulreturns.config.cfg
import com.soulreturns.features.darkui.DarkUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot

/**
 * The player inventory as an editor preview draws it: texture, the active hides, the info display
 * and the player's items. Shared by every screen that shows "your inventory with this setting",
 * so a preview can never disagree with the real one.
 */
object InventoryPreview {
    const val WIDTH = 176
    const val HEIGHT = 166

    private val LABEL = 0xFF404040.toInt()
    private val SLOT_HIGHLIGHT = 0x80FFFFFF.toInt()

    /**
     * Draw the frame with its top-left at the current pose's origin. [highlight] picks slots to
     * light up under their item (the buttons editor marks the one an icon would be copied from);
     * [forceInfo] shows the info display as if it were enabled, for its own settings screen.
     */
    fun drawFrame(g: GuiGraphicsExtractor, forceInfo: Boolean = false, highlight: (Slot) -> Boolean = { false }) {
        val mc = Minecraft.getInstance()
        g.blit(RenderPipelines.GUI_TEXTURED, InventoryScreen.INVENTORY_LOCATION, 0, 0, 0f, 0f, WIDTH, HEIGHT, 256, 256)
        InventoryCleanup.draw(g, 0, 0, preview = true, forceInfo = forceInfo)
        if (!forceInfo && !cfg.general.craftingGridHidden) g.text(mc.font, Component.translatable("container.crafting"), 97, 6, DarkUi.label(LABEL), false)
        val player = mc.player ?: return
        for (slot in player.inventoryMenu.slots) {
            val stack = slot.item
            if (stack.isEmpty || !slot.isActive) continue
            if (forceInfo && (slot.index < 5 || slot.index == 45)) continue
            if (highlight(slot)) g.fill(slot.x, slot.y, slot.x + 16, slot.y + 16, SLOT_HIGHLIGHT)
            g.item(stack, slot.x, slot.y)
            g.itemDecorations(mc.font, stack, slot.x, slot.y)
        }
    }

    /**
     * The player model for a frame at ([frameX], [frameY]) in unscaled screen pixels, drawn at
     * [zoom]× size. It renders through its own picture-in-picture pass, which does not follow a
     * scaled pose, so a zoomed preview passes the zoom here instead of scaling the pose.
     */
    fun drawPlayer(g: GuiGraphicsExtractor, frameX: Int, frameY: Int, mouseX: Int, mouseY: Int, zoom: Float = 1f) {
        val player = Minecraft.getInstance().player ?: return
        fun at(v: Int): Int = Math.round(v * zoom)
        InventoryScreen.extractEntityInInventoryFollowsMouse(
            g,
            frameX + at(26), frameY + at(8), frameX + at(75), frameY + at(78),
            Math.round(30 * zoom), 0.0625f, mouseX.toFloat(), mouseY.toFloat(), player,
        )
    }
}
