package com.soulreturns.gui

import com.soulreturns.gui.lib.GuiRenderContext
import com.soulreturns.render.DrawContextRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/** Minecraft/Fabric-specific implementation of [GuiRenderContext]. */
class MinecraftGuiRenderContext(
    private val context: GuiGraphics,
    private val client: Minecraft,
) : GuiRenderContext {
    override val screenWidth: Int
        get() = client.window.guiScaledWidth

    override val screenHeight: Int
        get() = client.window.guiScaledHeight

    override fun drawText(
        text: String,
        x: Int,
        y: Int,
        color: Int,
        shadow: Boolean
    ) {
        val renderer = client.font
        if (shadow) {
            context.drawString(renderer, text, x, y, color)
        } else {
            context.drawString(renderer, text, x, y, color, false)
        }
    }

    override fun drawScaledText(
        text: String,
        x: Int,
        y: Int,
        color: Int,
        shadow: Boolean,
        scale: Float,
    ) {
        if (scale == 1.0f) {
            drawText(text, x, y, color, shadow)
            return
        }

        val renderer = client.font
        val matrices = context.pose()

        // Convert target top-left coordinates into scaled space.
        val invScale = 1.0f / scale
        val sx = x * invScale
        val sy = y * invScale

        matrices.pushMatrix()
        matrices.scale(scale, scale)
        if (shadow) {
            context.drawString(renderer, text, sx.toInt(), sy.toInt(), color)
        } else {
            context.drawString(renderer, text, sx.toInt(), sy.toInt(), color, false)
        }
        matrices.popMatrix()
    }

    override fun fillRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int
    ) {
        context.fill(x, y, x + width, y + height, color)
    }

    override fun fillRoundedRect(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        color: Int,
        radius: Float,
    ) {
        DrawContextRenderer.roundedFill(context, x, y, x + width, y + height, color, radius)
    }

    override fun textWidth(text: String): Int = client.font.width(text)

    override val textLineHeight: Int
        get() = client.font.lineHeight

    override fun pushScissor(
        x: Int,
        y: Int,
        width: Int,
        height: Int,
    ) {
        context.enableScissor(x, y, x + width, y + height)
    }

    override fun popScissor() {
        context.disableScissor()
    }

    override fun drawItemIcon(
        iconKey: String,
        x: Int,
        y: Int
    ) {
        // Falls back to an empty stack when the key isn't registered — keeps the adapter self-contained.
        val stack: ItemStack = GuiIconRegistry.resolve(iconKey)
        context.renderItem(stack, x, y)
    }
}
