package com.soulreturns.platform.mixinbridge

import com.soulreturns.gui.MinecraftGuiRenderContext
import com.soulreturns.gui.lib.GuiInteractionHandler
import com.soulreturns.gui.lib.GuiInteractionSnapshot
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.GuiRenderer
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * HUD render entrypoint called from `GuiMixin`. Bridges Minecraft's [GuiGraphics] into the
 * mod's `gui/lib/` rendering pipeline.
 *
 * Lives under `platform/mixinbridge/` because it exists solely to be called from a Mixin —
 * not part of any feature, not meant for general consumption.
 */
object SoulGuiHudAdapter {
    /** Last interaction snapshot from the previous render — used by click handling. */
    @Volatile
    var lastSnapshot: GuiInteractionSnapshot? = null
        private set

    fun renderHud(context: GuiGraphics) {
        val client = Minecraft.getInstance()
        val layout = GuiLayoutManager.getLayout()
        val guiCtx = MinecraftGuiRenderContext(context, client)
        lastSnapshot = GuiRenderer.renderHud(layout, guiCtx)
    }

    /** Handle a mouse click routed from client code or a mixin. */
    fun handleClick(screenX: Int, screenY: Int): Boolean {
        val snapshot = lastSnapshot ?: return false
        return GuiInteractionHandler.handleClick(screenX, screenY, snapshot)
    }
}
