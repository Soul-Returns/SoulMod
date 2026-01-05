package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.util.DebugLogger
import net.minecraft.client.gui.DrawContext

/**
 * iOS-style toggle switch widget
 */
class ToggleWidget(
    option: OptionData,
    x: Int,
    y: Int
) : ConfigWidget(option, x, y, 200, 30) {
    
    private val toggleWidth = 44
    private val toggleHeight = 24
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any) {
        val value = getValue(configInstance) as? Boolean ?: false
        
        // Animate toggle state
        val targetProgress = if (value) 1f else 0f
        animationProgress += (targetProgress - animationProgress) * delta * 10f
        animationProgress = animationProgress.coerceIn(0f, 1f)
        
        // Draw option name
        val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
        context.drawText(textRenderer, option.name, x, y + 7, 0xFFFFFFFF.toInt(), false)
        
        // Draw toggle background
        val toggleX = x + width - toggleWidth
        val toggleY = y + (height - toggleHeight) / 2
        
        val bgColorOff = 0xFF3C3C3C.toInt()
        val bgColorOn = 0xFF4C9AFF.toInt()
        val bgColor = RenderHelper.lerpColor(bgColorOff, bgColorOn, animationProgress)
        
        val bgColorHover = if (isHovered) {
            RenderHelper.lerpColor(bgColor, 0xFFFFFFFF.toInt(), 0.1f)
        } else {
            bgColor
        }
        
        RenderHelper.drawRoundedRect(context, toggleX, toggleY, toggleWidth, toggleHeight, toggleHeight / 2f, bgColorHover)
        
        // Draw toggle knob
        val knobSize = toggleHeight - 4
        val knobX = toggleX + 2 + ((toggleWidth - knobSize - 4) * animationProgress).toInt()
        val knobY = toggleY + 2
        val knobColor = 0xFFFFFFFF.toInt()
        
        RenderHelper.drawRoundedRect(context, knobX, knobY, knobSize, knobSize, knobSize / 2f, knobColor)
        
        // Draw description if hovered
        if (isHovered && option.description.isNotEmpty()) {
            val descY = y + height + 2
            context.drawText(textRenderer, option.description, x, descY, 0xFF999999.toInt(), false)
        }
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        if (button == 0 && isHovered) {
            val currentValue = getValue(configInstance) as? Boolean ?: false
            val newValue = !currentValue
            DebugLogger.logWidgetInteraction("Toggle '${option.name}': $currentValue -> $newValue")
            setValue(configInstance, newValue)
            return true
        }
        return false
    }
}
