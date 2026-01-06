package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.model.OptionType
import com.soulreturns.config.lib.ui.themes.Theme
import net.minecraft.client.gui.DrawContext

/**
 * Divider widget for visual separation between config sections
 */
class DividerWidget(
    option: OptionData,
    x: Int,
    y: Int,
    private val dividerType: OptionType.Divider
) : ConfigWidget(option, x, y, 400, if (dividerType.label.isNotEmpty()) 32 else 12) {
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any, theme: Theme) {
        val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
        
        if (dividerType.label.isNotEmpty()) {
            // Draw label
            val labelX = x
            val labelY = y + (height - textRenderer.fontHeight) / 2
            
            context.drawText(textRenderer, dividerType.label, labelX, labelY, theme.textSecondary, false)
        } else {
            // Draw full-width line for unlabeled divider
            val lineY = y + height / 2
            val lineHeight = 1
            
            context.fill(x, lineY, x + width, lineY + lineHeight, theme.categoryBorder)
        }
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        // Dividers don't handle clicks
        return false
    }
}
