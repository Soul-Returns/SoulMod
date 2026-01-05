package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.model.OptionType
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import com.soulreturns.util.DebugLogger
import net.minecraft.client.gui.DrawContext
import kotlin.math.roundToInt

/**
 * Slider widget for numeric range values
 */
class SliderWidget(
    option: OptionData,
    x: Int,
    y: Int,
    private val sliderType: OptionType.Slider
) : ConfigWidget(option, x, y, 200, 40) {
    
    private var isDragging = false
    private val sliderHeight = 20
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any, theme: Theme) {
        val value = (getValue(configInstance) as? Number)?.toDouble() ?: sliderType.min
        val percentage = ((value - sliderType.min) / (sliderType.max - sliderType.min)).toFloat()
        
        val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
        
        // Draw option name and value
        val valueText = if (sliderType.step >= 1.0) {
            value.toInt().toString()
        } else {
            String.format("%.2f", value)
        }
        context.drawText(textRenderer, "${option.name}: $valueText", x, y, theme.textPrimary, false)
        
        // Draw slider track
        val sliderY = y + 20
        val trackColor = if (isHovered || isDragging) theme.widgetHover else theme.widgetBackground
        RenderHelper.drawRect(context, x, sliderY, width, sliderHeight, trackColor)
        
        // Draw filled portion
        val filledWidth = (width * percentage).toInt()
        if (filledWidth > 0) {
            val fillColor = theme.widgetActive
            RenderHelper.drawRect(context, x, sliderY, filledWidth, sliderHeight, fillColor)
        }
        
        // Draw handle
        val handleSize = sliderHeight + 4
        val handleX = x + (width * percentage).toInt() - handleSize / 2
        val handleY = sliderY - 2
        val handleColor = if (isDragging) theme.widgetHover else theme.widgetBackground
        
        RenderHelper.drawRect(context, handleX, handleY, handleSize, handleSize, handleColor)
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        if (button == 0 && isHovered) {
            isDragging = true
            updateValue(mouseX, configInstance)
            return true
        }
        return false
    }
    
    override fun mouseReleased(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        if (button == 0 && isDragging) {
            isDragging = false
            return true
        }
        return false
    }
    
    override fun mouseDragged(
        mouseX: Int,
        mouseY: Int,
        button: Int,
        deltaX: Double,
        deltaY: Double,
        configInstance: Any
    ): Boolean {
        if (isDragging) {
            updateValue(mouseX, configInstance)
            return true
        }
        return false
    }
    
    private fun updateValue(mouseX: Int, configInstance: Any) {
        val percentage = ((mouseX - x).toFloat() / width).coerceIn(0f, 1f)
        var newValue = sliderType.min + (sliderType.max - sliderType.min) * percentage
        
        // Apply step
        if (sliderType.step > 0) {
            newValue = (newValue / sliderType.step).roundToInt() * sliderType.step
        }
        
        newValue = newValue.coerceIn(sliderType.min, sliderType.max)
        
        // Set the appropriate type
        val finalValue: Any = when (option.field.type) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> newValue.toInt()
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> newValue.toFloat()
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> newValue.toLong()
            else -> newValue
        }
        
        val oldValue = getValue(configInstance)
        DebugLogger.logWidgetInteraction("Slider '${option.name}': $oldValue -> $finalValue")
        setValue(configInstance, finalValue)
    }
}
