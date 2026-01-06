package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.model.OptionType
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import com.soulreturns.util.DebugLogger
import net.minecraft.client.gui.DrawContext
import org.lwjgl.glfw.GLFW

/**
 * Text input field widget
 */
class TextInputWidget(
    option: OptionData,
    x: Int,
    y: Int,
    private val textInputType: OptionType.TextInput
) : ConfigWidget(option, x, y, 300, 50) {
    
    private var cursorPos = 0
    private var cursorBlink = 0f
    private val inputHeight = 30
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any, theme: Theme) {
        val value = (getValue(configInstance) as? String) ?: ""
        val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
        
        // Draw label
        context.drawText(textRenderer, option.name, x, y, theme.textPrimary, false)
        
        // Draw input box
        val inputY = y + 18
        val bgColor = if (isFocused) theme.widgetHover else theme.widgetBackground
        val borderColor = if (isFocused) theme.widgetActive else theme.categoryBorder
        
        RenderHelper.drawRect(context, x, inputY, width, inputHeight, bgColor)
        
        // Draw border
        RenderHelper.drawRect(context, x - 1, inputY - 1, width + 2, inputHeight + 2, borderColor)
        RenderHelper.drawRect(context, x, inputY, width, inputHeight, bgColor)
        
        // Draw text
        val textX = x + 8
        val textY = inputY + (inputHeight - textRenderer.fontHeight) / 2
        val displayText = if (value.isEmpty() && !isFocused) textInputType.placeholder else value
        val textColor = if (value.isEmpty() && !isFocused) theme.textDisabled else theme.textPrimary
        
        // Clip text rendering to input box
        context.enableScissor(x, inputY, x + width, inputY + inputHeight)
        context.drawText(textRenderer, displayText, textX, textY, textColor, false)
        
        // Draw cursor if focused
        if (isFocused) {
            cursorBlink += delta * 2f
            if (cursorBlink % 1f < 0.5f) {
                val cursorX = textX + textRenderer.getWidth(value.substring(0, cursorPos.coerceIn(0, value.length)))
                context.fill(cursorX, textY, cursorX + 1, textY + textRenderer.fontHeight, theme.textPrimary)
            }
        }
        
        context.disableScissor()
    }
    
    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        val inputY = y + 18
        val wasClicked = mouseX >= x && mouseX <= x + width && mouseY >= inputY && mouseY <= inputY + inputHeight
        
        if (button == 0) {
            isFocused = wasClicked
            if (wasClicked) {
                // Set cursor position based on click location
                val value = (getValue(configInstance) as? String) ?: ""
                val textRenderer = net.minecraft.client.MinecraftClient.getInstance().textRenderer
                val textX = x + 8
                val relativeX = mouseX - textX
                
                cursorPos = value.length
                for (i in 0 until value.length) {
                    val width = textRenderer.getWidth(value.substring(0, i + 1))
                    if (width > relativeX) {
                        cursorPos = i
                        break
                    }
                }
            }
            return wasClicked
        }
        return false
    }
    
    override fun charTyped(chr: Char, modifiers: Int, configInstance: Any): Boolean {
        if (isFocused && chr.code >= 32) {
            val value = (getValue(configInstance) as? String) ?: ""
            if (value.length < textInputType.maxLength) {
                val newValue = value.substring(0, cursorPos) + chr + value.substring(cursorPos)
                DebugLogger.logWidgetInteraction("Text input '${option.name}': '$value' -> '$newValue'")
                setValue(configInstance, newValue)
                cursorPos++
                return true
            }
        }
        return false
    }
    
    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int, configInstance: Any): Boolean {
        if (!isFocused) return false
        
        val value = (getValue(configInstance) as? String) ?: ""
        
        when (keyCode) {
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursorPos > 0) {
                    val newValue = value.substring(0, cursorPos - 1) + value.substring(cursorPos)
                    setValue(configInstance, newValue)
                    cursorPos--
                    return true
                }
            }
            GLFW.GLFW_KEY_DELETE -> {
                if (cursorPos < value.length) {
                    val newValue = value.substring(0, cursorPos) + value.substring(cursorPos + 1)
                    setValue(configInstance, newValue)
                    return true
                }
            }
            GLFW.GLFW_KEY_LEFT -> {
                if (cursorPos > 0) {
                    cursorPos--
                    return true
                }
            }
            GLFW.GLFW_KEY_RIGHT -> {
                if (cursorPos < value.length) {
                    cursorPos++
                    return true
                }
            }
            GLFW.GLFW_KEY_HOME -> {
                cursorPos = 0
                return true
            }
            GLFW.GLFW_KEY_END -> {
                cursorPos = value.length
                return true
            }
        }
        
        return false
    }
}
