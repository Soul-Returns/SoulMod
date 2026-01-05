package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import com.soulreturns.util.DebugLogger
import net.minecraft.client.gui.DrawContext

/**
 * iOS-style toggle switch widget
 */
class ToggleWidget(
    option: OptionData,
    x: Int,
    y: Int
) : ConfigWidget(option, x, y, 200, 20) {
    
    private val toggleWidth = 36
    private val toggleHeight = 18
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any, theme: Theme) {
        val value = getValue(configInstance) as? Boolean ?: false
        
        // Animate toggle state
        val targetProgress = if (value) 1f else 0f
        animationProgress += (targetProgress - animationProgress) * delta * 10f
        animationProgress = animationProgress.coerceIn(0f, 1f)
        
        // Draw option name
        val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
        val textHeight = textRenderer.fontHeight
        val textY = y + (height - textHeight) / 2
        context.drawText(textRenderer, option.name, x, textY, theme.textPrimary, false)
        
        // Draw toggle background (aligned with text center)
        val toggleX = x + width - toggleWidth
        val toggleY = y + (height - toggleHeight) / 2
        
        val bgColorOff = theme.widgetBackground
        val bgColorOn = theme.widgetActive
        val bgColor = RenderHelper.lerpColor(bgColorOff, bgColorOn, animationProgress)
        
        val bgColorFinal = if (isHovered) {
            RenderHelper.lerpColor(bgColor, 0xFFFFFFFF.toInt(), 0.1f)
        } else {
            bgColor
        }
        
        RenderHelper.drawRect(context, toggleX, toggleY, toggleWidth, toggleHeight, bgColorFinal)
        
        // Draw toggle knob (circle)
        val knobSize = toggleHeight - 6
        val knobTravel = toggleWidth - knobSize - 6
        val knobX = toggleX + 3 + (knobTravel * animationProgress).toInt()
        val knobY = toggleY + 3
        val knobColor = 0xFFFFFFFF.toInt()
        
        RenderHelper.drawRect(context, knobX, knobY, knobSize, knobSize, knobColor)
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
