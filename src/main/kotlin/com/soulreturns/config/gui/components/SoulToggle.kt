package com.soulreturns.config.gui.components

import com.soulreturns.config.gui.Theme
import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.base.BaseUIComponent
import io.wispforest.owo.ui.core.CursorStyle
import io.wispforest.owo.ui.core.OwoUIGraphics
import io.wispforest.owo.ui.core.Sizing
import io.wispforest.owo.ui.core.UIComponent
import net.minecraft.client.gui.Click
import org.lwjgl.glfw.GLFW

/** A modern pill-style toggle switch replacing owo's CheckboxComponent. */
class SoulToggle(
    checked: Boolean,
    private val onToggle: (Boolean) -> Unit,
) : BaseUIComponent() {

    var checked: Boolean = checked
        private set

    init {
        cursorStyle(CursorStyle.HAND)
        horizontalSizing(Sizing.fixed(WIDTH))
        verticalSizing(Sizing.fixed(HEIGHT))
    }

    override fun draw(context: OwoUIGraphics, mouseX: Int, mouseY: Int, partialTicks: Float, delta: Float) {
        val trackColor = if (checked) Theme.ACCENT else Theme.PANEL_HOVER
        DrawContextRenderer.roundedFill(context, x, y, x + width, y + height, trackColor, HEIGHT / 2f)

        val knobPad = 3
        val knobSize = HEIGHT - knobPad * 2
        val knobX = if (checked) x + width - knobPad - knobSize else x + knobPad
        DrawContextRenderer.roundedFill(
            context,
            knobX, y + knobPad,
            knobX + knobSize, y + knobPad + knobSize,
            Theme.TEXT, knobSize / 2f,
        )
    }

    override fun onMouseDown(click: Click, doubled: Boolean): Boolean {
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) {
            checked = !checked
            onToggle(checked)
            return true
        }
        return super.onMouseDown(click, doubled)
    }

    fun setChecked(v: Boolean) { checked = v }

    override fun determineHorizontalContentSize(sizing: Sizing): Int = WIDTH
    override fun determineVerticalContentSize(sizing: Sizing): Int = HEIGHT
    override fun canFocus(source: UIComponent.FocusSource): Boolean = false

    companion object {
        const val WIDTH = 36
        const val HEIGHT = 20
    }
}
