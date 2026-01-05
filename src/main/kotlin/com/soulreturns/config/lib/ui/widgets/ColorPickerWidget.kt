package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.model.OptionType
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.util.DebugLogger
import net.minecraft.client.gui.DrawContext

/**
 * Color picker widget
 */
class ColorPickerWidget(
    option: OptionData,
    x: Int,
    y: Int,
    private val colorPickerType: OptionType.ColorPicker
) : ConfigWidget(option, x, y, 200, 40) {
    
    private var isPickerOpen = false
    private val previewSize = 30
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any) {
        val value = (getValue(configInstance) as? Int) ?: 0xFFFFFFFF.toInt()
        val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
        
        // Draw label
        context.drawText(textRenderer, option.name, x, y, 0xFFFFFFFF.toInt(), false)
        
        // Draw color preview box
        val previewX = x + width - previewSize
        val previewY = y
        
        // Draw checkerboard background for alpha preview
        if (colorPickerType.allowAlpha) {
            val checkSize = previewSize / 2
            context.fill(previewX, previewY, previewX + checkSize, previewY + checkSize, 0xFFCCCCCC.toInt())
            context.fill(previewX + checkSize, previewY, previewX + previewSize, previewY + checkSize, 0xFF999999.toInt())
            context.fill(previewX, previewY + checkSize, previewX + checkSize, previewY + previewSize, 0xFF999999.toInt())
            context.fill(previewX + checkSize, previewY + checkSize, previewX + previewSize, previewY + previewSize, 0xFFCCCCCC.toInt())
        }
        
        // Draw color preview
        RenderHelper.drawRoundedRect(context, previewX, previewY, previewSize, previewSize, 6f, value)
        
        // Draw border
        val borderColor = if (isHovered) 0xFFFFFFFF.toInt() else 0xFF666666.toInt()
        RenderHelper.drawRoundedRect(context, previewX - 1, previewY - 1, previewSize + 2, previewSize + 2, 6f, borderColor)
        RenderHelper.drawRoundedRect(context, previewX, previewY, previewSize, previewSize, 6f, value)
        
        // Draw hex value
        val hex = String.format("#%08X", value)
        val hexX = previewX - textRenderer.getWidth(hex) - 10
        context.drawText(textRenderer, hex, hexX, y + (previewSize - textRenderer.fontHeight) / 2, 0xFFCCCCCC.toInt(), false)
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        val previewX = x + width - previewSize
        val previewY = y
        
        if (button == 0 && mouseX >= previewX && mouseX <= previewX + previewSize &&
            mouseY >= previewY && mouseY <= previewY + previewSize) {
            // For now, cycle through some preset colors
            val current = (getValue(configInstance) as? Int) ?: 0xFFFFFFFF.toInt()
            val presets = listOf(
                0xFFFF0000.toInt(), // Red
                0xFF00FF00.toInt(), // Green
                0xFF0000FF.toInt(), // Blue
                0xFFFFFF00.toInt(), // Yellow
                0xFFFF00FF.toInt(), // Magenta
                0xFF00FFFF.toInt(), // Cyan
                0xFFFFFFFF.toInt(), // White
                0xFF000000.toInt()  // Black
            )
            
            val currentIndex = presets.indexOf(current)
            val nextIndex = (currentIndex + 1) % presets.size
            val newColor = presets[nextIndex]
            DebugLogger.logWidgetInteraction("Color picker '${option.name}': ${String.format("#%08X", current)} -> ${String.format("#%08X", newColor)}")
            setValue(configInstance, newColor)
            return true
        }
        return false
    }
}
