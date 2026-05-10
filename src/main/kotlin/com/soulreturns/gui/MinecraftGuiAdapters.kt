package com.soulreturns.gui

import com.soulreturns.gui.lib.GuiInteractionHandler
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.GuiRenderContext
import com.soulreturns.gui.lib.GuiRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.world.item.ItemStack

/**
 * Minecraft/Fabric-specific implementation of GuiRenderContext.
 */
class MinecraftGuiRenderContext(
    private val context: GuiGraphics,
    private val client: Minecraft,
) : GuiRenderContext {

    override val screenWidth: Int
        get() = client.window.guiScaledWidth

    override val screenHeight: Int
        get() = client.window.guiScaledHeight

    override fun drawText(text: String, x: Int, y: Int, color: Int, shadow: Boolean) {
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

    override fun fillRect(x: Int, y: Int, width: Int, height: Int, color: Int) {
        context.fill(x, y, x + width, y + height, color)
    }

    override fun drawItemIcon(iconKey: String, x: Int, y: Int) {
        // For now, rely on the host to supply a mapping from iconKey to
        // ItemStack via a simple registry. To keep this adapter self-contained,
        // fall back to an empty stack when not found.
        val stack: ItemStack = GuiIconRegistry.resolve(iconKey)
        context.renderItem(stack, x, y)
    }
}

/**
 * Simple registry mapping icon keys to ItemStacks. Hosts can register mappings
 * during mod initialization.
 */
object GuiIconRegistry {
    private val icons: MutableMap<String, ItemStack> = mutableMapOf()

    fun registerIcon(key: String, stack: ItemStack) {
        icons[key] = stack
    }

    fun resolve(key: String): ItemStack {
        return icons[key] ?: ItemStack.EMPTY
    }
}

/**
 * HUD adapter entrypoint called from the InGameHud mixin.
 */
object SoulGuiHudAdapter {
    // Last interaction snapshot from the previous render. This is used by
    // click handling when the user interacts with tracker +/- buttons.
    @Volatile
    var lastSnapshot: com.soulreturns.gui.lib.GuiInteractionSnapshot? = null
        private set

    fun renderHud(context: GuiGraphics) {
        val client = Minecraft.getInstance()
        val layout = GuiLayoutManager.getLayout()
        val guiCtx = MinecraftGuiRenderContext(context, client)
        lastSnapshot = GuiRenderer.renderHud(layout, guiCtx)
    }

    /**
     * Handle a mouse click routed from client code or a mixin.
     */
    fun handleClick(screenX: Int, screenY: Int): Boolean {
        val snapshot = lastSnapshot ?: return false
        return GuiInteractionHandler.handleClick(screenX, screenY, snapshot)
    }
}
