package com.soulreturns.features.inventorybuttons

import com.soulreturns.config.InventoryButton
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.SoulPanel
import net.minecraft.ChatFormatting
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component

/** Draws one button. The live overlay and the editor preview both go through here, so they match. */
object InventoryButtonPainter {
    private const val SIZE = InventoryButtonSpots.SIZE

    /** Draw [button] with its top-left at ([x], [y]) in the extractor's current pose; [outline] overrides the border. */
    fun draw(g: GuiGraphicsExtractor, button: InventoryButton, x: Int, y: Int, hovered: Boolean, selected: Boolean = false, outline: Int? = null) {
        val border = outline ?: if (selected) Theme.ACCENT else if (hovered) Theme.TEXT_DIM else Theme.FIELD_BORDER
        SoulPanel.draw(g, x, y, x + SIZE, y + SIZE, SoulPanel.SMALL, if (hovered) Theme.CONTROL_HOVER else Theme.SECTION, border)
        g.fakeItem(InventoryButtonIcons.stack(button), x + 1, y + 1)
    }

    /** A free spot at ([x], [y]): [fill] inside a 1 px [edge]. */
    fun spot(g: GuiGraphicsExtractor, x: Int, y: Int, fill: Int, edge: Int) {
        g.fill(x, y, x + SIZE, y + SIZE, fill)
        g.fill(x, y, x + SIZE, y + 1, edge)
        g.fill(x, y + SIZE - 1, x + SIZE, y + SIZE, edge)
        g.fill(x, y + 1, x + 1, y + SIZE - 1, edge)
        g.fill(x + SIZE - 1, y + 1, x + SIZE, y + SIZE - 1, edge)
    }

    /** Hover text: the label if there is one, then the command that a click sends. */
    fun tooltip(button: InventoryButton): List<Component> {
        val command = button.command.trim().removePrefix("/").trimStart()
        val commandLine =
            if (command.isEmpty()) Component.literal("No command").withStyle(ChatFormatting.DARK_GRAY)
            else Component.literal("/$command").withStyle(ChatFormatting.GRAY)
        return if (button.label.isBlank()) listOf(commandLine) else listOf(Component.literal(button.label), commandLine)
    }
}
