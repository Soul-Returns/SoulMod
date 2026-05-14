package com.soulreturns.platform.mixinbridge

import com.soulreturns.gui.MinecraftGuiRenderContext
import com.soulreturns.gui.lib.GuiInteractionHandler
import com.soulreturns.gui.lib.GuiInteractionSnapshot
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.GuiRenderer
import com.soulreturns.gui.lib.tracker.TrackerInputHandler
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen

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

    /**
     * Re-render the HUD on top of inventory / container screens.
     *
     * The `GuiMixin` injection point at `Gui.render(...)` TAIL runs *before* `Screen.render`
     * for the same frame, so when a container is open the screen's dim/blur background
     * paints over our HUD. To get z-index-like behavior we register a [ScreenEvents.afterRender]
     * callback on the screen instance: that runs at `Screen.render` TAIL, after the screen's
     * background, items, and tooltips, so our HUD ends up on top.
     *
     * **Scope:** only screens that extend [AbstractContainerScreen] — i.e. the bare player
     * inventory and any chest-style menu (dispenser, anvil, Hypixel-custom menus, etc.).
     * Soul's own config / SPV / update modal screens, the title screen, options menus, and
     * other mods' arbitrary screens are excluded so the HUD doesn't paint over their UI.
     *
     * Call once from [com.soulreturns.Soul.onInitializeClient]. The Fabric
     * `ScreenEvents.afterRender(screen)` event is bound per-screen-instance and disposed when
     * the screen closes, so re-opening an inventory gets a fresh registration via `AFTER_INIT`.
     */
    fun registerScreenOverlay() {
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is AbstractContainerScreen<*>) return@AfterInit
                ScreenEvents.afterRender(screen).register(
                    ScreenEvents.AfterRender { _, ctx, _, _, _ -> renderHud(ctx) }
                )
                ScreenMouseEvents.allowMouseClick(screen).register(
                    ScreenMouseEvents.AllowMouseClick { _, click ->
                        // Consume + cancel propagation when the click lands on a Soul HUD region
                        // so the click doesn't double-fire as an inventory slot interaction.
                        !handleClick(click.x().toInt(), click.y().toInt())
                    }
                )
                ScreenMouseEvents.allowMouseScroll(screen).register(
                    ScreenMouseEvents.AllowMouseScroll { _, mx, my, _, vsd ->
                        val snapshot = lastSnapshot ?: return@AllowMouseScroll true
                        // Return false to cancel default scroll behavior when we consume the event.
                        !TrackerInputHandler.handleScroll(snapshot, mx.toInt(), my.toInt(), vsd)
                    }
                )
            }
        )
    }

    /** Handle a mouse click routed from client code or a mixin. */
    fun handleClick(
        screenX: Int,
        screenY: Int
    ): Boolean {
        val snapshot = lastSnapshot ?: return false
        return GuiInteractionHandler.handleClick(screenX, screenY, snapshot)
    }
}
