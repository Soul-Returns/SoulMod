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
 * Numeric-only input widget backed by OptionType.NumberInput.
 *
 * Uses a text field UI but parses/validates against numeric bounds and
 * decimals, then writes back as Int/Long/Float/Double depending on field type.
 */
class NumberInputWidget(
    option: OptionData,
    x: Int,
    y: Int,
    private val type: OptionType.NumberInput
) : ConfigWidget(option, x, y, 300, 50) {

    private var inputText: String = ""
    private var cursorPos: Int = 0
    private var cursorBlink = 0f
    private val inputHeight = 30

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

        // Sync from config when not focused
        if (!isFocused) {
            inputText = numericValue?.let { formatValue(it) } ?: ""
            cursorPos = inputText.length
        }

        // Label
        context.drawText(textRenderer, option.name, x, y, theme.textPrimary, false)

        // Input box
        val inputY = y + 18
        val bgColor = if (isFocused) theme.widgetHover else theme.widgetBackground
        val borderColor = if (isFocused) theme.widgetActive else theme.categoryBorder

        RenderHelper.drawRect(context, x, inputY, width, inputHeight, bgColor)
        RenderHelper.drawRect(context, x - 1, inputY - 1, width + 2, inputHeight + 2, borderColor)

        val textX = x + 8
        val textY = inputY + (inputHeight - textRenderer.fontHeight) / 2
        val display = inputText

        context.enableScissor(x, inputY, x + width, inputY + inputHeight)
        context.drawText(textRenderer, display, textX, textY, theme.textPrimary, false)

        if (isFocused) {
            cursorBlink += delta * 2f
            if (cursorBlink % 1f < 0.5f) {
                val cursorOffset = textRenderer.getWidth(
                    display.substring(0, cursorPos.coerceIn(0, display.length))
                )
                val cx = textX + cursorOffset
                context.fill(cx, textY, cx + 1, textY + textRenderer.fontHeight, theme.textPrimary)
            }
        }
        context.disableScissor()
    }

    override fun mouseClicked(mouseX: Int, mouseY: Int, button: Int, configInstance: Any): Boolean {
        if (button != 0) return false

        val inputY = y + 18
        val inside = mouseX >= x && mouseX <= x + width &&
                mouseY >= inputY && mouseY <= inputY + inputHeight

        isFocused = inside
        if (inside) {
            // Position cursor based on click
            val mc = MinecraftClient.getInstance()
            val textRenderer = mc.textRenderer
            val textX = x + 8
            val relativeX = mouseX - textX
            val value = inputText

            cursorPos = value.length
            for (i in 0 until value.length) {
                val w = textRenderer.getWidth(value.substring(0, i + 1))
                if (w > relativeX) {
                    cursorPos = i
                    break
                }
            }
        }
        return inside
    }

    override fun charTyped(chr: Char, modifiers: Int, configInstance: Any): Boolean {
        if (!isFocused) return false
        if (chr.code < 32) return false

        val allowedChars = if (type.decimals > 0) "0123456789.-" else "0123456789-"
        if (!allowedChars.contains(chr)) return false

        val before = inputText
        val newText = StringBuilder(inputText).insert(cursorPos, chr).toString()

        if (parseInput(newText) != null) {
            inputText = newText
            cursorPos++
            applyInputToConfig(configInstance)
            DebugLogger.logWidgetInteraction("NumberInput '${option.name}': '$before' -> '$inputText'")
            return true
        }

        return false
    }

    override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int, configInstance: Any): Boolean {
        if (!isFocused) return false

        when (keyCode) {
            GLFW.GLFW_KEY_BACKSPACE -> {
                if (cursorPos > 0 && inputText.isNotEmpty()) {
                    val before = inputText
                    inputText = inputText.removeRange(cursorPos - 1, cursorPos)
                    cursorPos--
                    applyInputToConfig(configInstance)
                    DebugLogger.logWidgetInteraction("NumberInput '${option.name}': '$before' -> '$inputText'")
                    return true
                }
            }
            GLFW.GLFW_KEY_DELETE -> {
                if (cursorPos < inputText.length) {
                    val before = inputText
                    inputText = inputText.removeRange(cursorPos, cursorPos + 1)
                    applyInputToConfig(configInstance)
                    DebugLogger.logWidgetInteraction("NumberInput '${option.name}': '$before' -> '$inputText'")
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

    private fun parseInput(text: String): Double? {
        if (text.isBlank() || text == "-" || text == "." || text == "-.") return null
        return text.toDoubleOrNull()?.coerceIn(type.min, type.max)
    }

    private fun applyInputToConfig(configInstance: Any) {
        val parsed = parseInput(inputText) ?: return
        setNumericValue(configInstance, parsed)
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
        DebugLogger.logWidgetInteraction("NumberInput '${option.name}' set to $finalValue")
        setValue(configInstance, finalValue)
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
