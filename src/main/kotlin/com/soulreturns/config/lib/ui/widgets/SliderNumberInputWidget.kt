package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.model.OptionType
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import com.soulreturns.util.DebugLogger
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.DrawContext
import org.lwjgl.glfw.GLFW
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Combo widget: slider + numeric input field.
 *
 * - Slider is used for quick adjustments within [min, max] using [step].
 * - Text input allows precise numeric entry.
 * - Binds directly to the backing field type (Int, Long, Float, Double).
 */
class SliderNumberInputWidget(
    option: OptionData,
    x: Int,
    y: Int,
    private val type: OptionType.SliderNumberInput
) : ConfigWidget(option, x, y, 300, 60) {

    private var isDragging = false
    private val sliderHeight = 16
    private val inputHeight = 20

    private var isInputFocused = false
    private var inputText: String = ""
    private var cursorPos = 0
    private var cursorBlink = 0f

    override fun render(
        context: DrawContext,
        mouseX: Int,
        mouseY: Int,
        delta: Float,
        configInstance: Any,
        theme: Theme
    ) {
        val mc = MinecraftClient.getInstance()
        val textRenderer = mc.textRenderer

        val numericValue = (getValue(configInstance) as? Number)?.toDouble()
            ?: type.min.coerceAtMost(type.max)

        // Keep input text in sync when not focused
        if (!isInputFocused) {
            inputText = formatValue(numericValue)
            cursorPos = inputText.length
        }

        // Label
        context.drawText(textRenderer, option.name, x, y, theme.textPrimary, false)

        // Slider area
        val sliderY = y + 18
        val sliderWidth = width - 80 // leave space for input on the right
        val sliderX = x

        val clamped = numericValue.coerceIn(type.min, type.max)
        val percentage = if (type.max > type.min) {
            ((clamped - type.min) / (type.max - type.min)).toFloat()
        } else 0f

        // Track
        val trackColor = if (isHovered || isDragging) theme.widgetHover else theme.widgetBackground
        RenderHelper.drawRect(context, sliderX, sliderY, sliderWidth, sliderHeight, trackColor)

        // Filled portion
        val filledWidth = (sliderWidth * percentage).toInt().coerceIn(0, sliderWidth)
        if (filledWidth > 0) {
            RenderHelper.drawRect(context, sliderX, sliderY, filledWidth, sliderHeight, theme.widgetActive)
        }

        // Handle
        val handleSize = sliderHeight + 4
        val handleX = sliderX + filledWidth - handleSize / 2
        val handleY = sliderY - 2
        val handleColor = when {
            isDragging -> theme.widgetActive
            isHovered -> theme.widgetHover
            else -> theme.widgetBackground
        }
        RenderHelper.drawRect(context, handleX, handleY, handleSize, handleSize, handleColor)

        // Value text next to label
        val valueText = formatValue(numericValue)
        val valueTextX = x + width - textRenderer.getWidth(valueText) - 8
        context.drawText(textRenderer, valueText, valueTextX, y, theme.textSecondary, false)

        // Numeric input box on the right
        val inputX = x + sliderWidth + 8
        val inputY = sliderY
        val inputWidth = width - (inputX - x)
        val bgColor = if (isInputFocused) theme.widgetHover else theme.widgetBackground
        val borderColor = if (isInputFocused) theme.widgetActive else theme.categoryBorder

        RenderHelper.drawRect(context, inputX, inputY, inputWidth, inputHeight, bgColor)
        RenderHelper.drawRect(context, inputX - 1, inputY - 1, inputWidth + 2, inputHeight + 2, borderColor)

        val textX = inputX + 6
        val textY = inputY + (inputHeight - textRenderer.fontHeight) / 2
        val display = if (inputText.isEmpty() && !isInputFocused) "" else inputText

        context.enableScissor(inputX, inputY, inputX + inputWidth, inputY + inputHeight)
        context.drawText(textRenderer, display, textX, textY, theme.textPrimary, false)

        if (isInputFocused) {
            cursorBlink += delta * 2f
            if (cursorBlink % 1f < 0.5f) {
                val cursorOffset = textRenderer.getWidth(display.substring(0, cursorPos.coerceIn(0, display.length)))
                val cx = textX + cursorOffset
                context.fill(cx, textY, cx + 1, textY + textRenderer.fontHeight, theme.textPrimary)
            }
        }
        context.disableScissor()
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        val sliderY = y + 18
        val sliderWidth = width - 80
        val sliderX = x

        val inputX = x + sliderWidth + 8
        val inputY = sliderY
        val inputWidth = width - (inputX - x)

        val overSlider = mouseX >= sliderX && mouseX <= sliderX + sliderWidth &&
                mouseY >= sliderY && mouseY <= sliderY + sliderHeight

        val overInput = mouseX >= inputX && mouseX <= inputX + inputWidth &&
                mouseY >= inputY && mouseY <= inputY + inputHeight

        if (button == 0) {
            if (overSlider) {
                // Start dragging via slider; text input loses focus
                isDragging = true
                isInputFocused = false
                isFocused = false
                updateValueFromSlider(mouseX, configInstance)
                return true
            }

            isDragging = false

            if (overInput) {
                // Focus the numeric input; enable keyboard handling via isFocused
                isInputFocused = true
                isFocused = true
                // Position cursor at end
                val current = (getValue(configInstance) as? Number)?.toDouble()
                inputText = if (current != null) formatValue(current) else ""
                cursorPos = inputText.length
                return true
            } else {
                // Clicked somewhere else: clear focus from input
                isInputFocused = false
                isFocused = false
            }
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
        if (isDragging && button == 0) {
            updateValueFromSlider(mouseX, configInstance)
            return true
        }
        return false
    }

    override fun charTyped(chr: Char, modifiers: Int, configInstance: Any): Boolean {
        if (!isInputFocused) return false
        if (chr.code < 32) return false

        val allowedChars = if (type.decimals > 0) "0123456789.-" else "0123456789-"
        if (!allowedChars.contains(chr)) return false

        val before = inputText
        val newText = StringBuilder(inputText).insert(cursorPos, chr).toString()

        if (parseInput(newText) != null) {
            inputText = newText
            cursorPos++
            applyInputToConfig(configInstance)
            DebugLogger.logWidgetInteraction("Slider+Input '${option.name}': '$before' -> '$inputText'")
            return true
        }

        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int, configInstance: Any): Boolean {
        if (!isInputFocused) return false

        when (keyCode) {
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursorPos > 0 && inputText.isNotEmpty()) {
                    val before = inputText
                    inputText = inputText.removeRange(cursorPos - 1, cursorPos)
                    cursorPos--
                    applyInputToConfig(configInstance)
                    DebugLogger.logWidgetInteraction("Slider+Input '${option.name}': '$before' -> '$inputText'")
                    return true
                }
            }
            GLFW.GLFW_KEY_DELETE -> {
                if (cursorPos < inputText.length) {
                    val before = inputText
                    inputText = inputText.removeRange(cursorPos, cursorPos + 1)
                    applyInputToConfig(configInstance)
                    DebugLogger.logWidgetInteraction("Slider+Input '${option.name}': '$before' -> '$inputText'")
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
                if (cursorPos < inputText.length) {
                    cursorPos++
                    return true
                }
            }
            GLFW.GLFW_KEY_HOME -> {
                cursorPos = 0
                return true
            }
            GLFW.GLFW_KEY_END -> {
                cursorPos = inputText.length
                return true
            }
        }

        return false
    }

    private fun updateValueFromSlider(mouseX: Int, configInstance: Any) {
        val sliderWidth = width - 80
        val sliderX = x
        val raw = ((mouseX - sliderX).toFloat() / sliderWidth).coerceIn(0f, 1f)
        var newValue = type.min + (type.max - type.min) * raw

        if (type.step > 0.0) {
            newValue = (newValue / type.step).roundToInt() * type.step
        }

        newValue = newValue.coerceIn(type.min, type.max)
        setNumericValue(configInstance, newValue)
        inputText = formatValue(newValue)
        cursorPos = inputText.length
    }

    private fun setNumericValue(configInstance: Any, value: Double) {
        val clamped = value.coerceIn(type.min, type.max)
        val fieldType = option.field.type
        val finalValue: Any = when (fieldType) {
            Int::class.javaPrimitiveType, Int::class.javaObjectType -> clamped.roundToInt()
            Long::class.javaPrimitiveType, Long::class.javaObjectType -> clamped.roundToInt().toLong()
            Float::class.javaPrimitiveType, Float::class.javaObjectType -> clamped.toFloat()
            Double::class.javaPrimitiveType, Double::class.javaObjectType -> clamped
            else -> clamped.roundToInt()
        }
        DebugLogger.logWidgetInteraction("Slider+Input '${option.name}' set to $finalValue")
        setValue(configInstance, finalValue)
    }

    private fun parseInput(text: String): Double? {
        if (text.isBlank() || text == "-" || text == "." || text == "-.") return null
        return text.toDoubleOrNull()?.coerceIn(type.min, type.max)
    }

    private fun applyInputToConfig(configInstance: Any) {
        val parsed = parseInput(inputText) ?: return
        setNumericValue(configInstance, parsed)
    }

    private fun formatValue(value: Double): String {
        val decimals = type.decimals.coerceAtLeast(0)
        return if (decimals == 0) {
            value.roundToInt().toString()
        } else {
            val factor = 10.0.pow(decimals.toDouble())
            val rounded = (value * factor).roundToInt() / factor
            "% .${decimals}f".format(rounded).trim()
        }
    }
}
