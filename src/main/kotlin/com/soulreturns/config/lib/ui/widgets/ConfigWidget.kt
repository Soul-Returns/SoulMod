package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.ui.themes.Theme
import net.minecraft.client.gui.DrawContext

/**
 * Base interface for all config widgets
 */
abstract class ConfigWidget(
    val option: OptionData,
    var x: Int,
    var y: Int,
    var width: Int,
    var height: Int
) {
    var isHovered = false
    var isFocused = false
    protected var animationProgress = 0f
    
    /**
     * Renders the widget
     */
    abstract fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float, configInstance: Any, theme: Theme)
    
    /**
     * Handles mouse click events
     * @return true if the event was consumed
     */
    abstract fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean
    
    /**
     * Handles mouse release events
     * @return true if the event was consumed
     */
    open fun mouseReleased(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        return false
    }
    
    /**
     * Handles mouse drag events
     * @return true if the event was consumed
     */
    open fun mouseDragged(mouseX: Int, mouseY: Int, button: Int, deltaX: Double, deltaY: Double, configInstance: Any): Boolean {
        return false
    }
    
    /**
     * Handles key press events
     * @return true if the event was consumed
     */
    open fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int, configInstance: Any): Boolean {
        return false
    }
    
    /**
     * Handles character typed events
     * @return true if the event was consumed
     */
    open fun charTyped(chr: Char, modifiers: Int, configInstance: Any): Boolean {
        return false
    }
    
    /**
     * Updates hover state
     */
    fun updateHover(mouseX: Int, mouseY: Int) {
        isHovered = mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }
    
    /**
     * Gets the current value from the config instance
     */
    protected fun getValue(configInstance: Any): Any? {
        return try {
            option.field.isAccessible = true
            option.field.get(configInstance)
        } catch (e: Exception) {
            null
        }
    }
    
    /**
     * Sets the value in the config instance
     */
    protected fun setValue(configInstance: Any, value: Any) {
        try {
            option.field.isAccessible = true
            option.field.set(configInstance, value)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
