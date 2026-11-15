package com.soulreturns.features.qol

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements

/**
 * Hides the floating item name shown above the hotbar when you select/switch an item.
 *
 * Wraps the vanilla HELD_ITEM_TOOLTIP HUD element via Fabric's registry — the wrapper reads the
 * config live, so the toggle applies immediately. No mixin needed (Fabric exposes this surface).
 */
object HideHeldItemTooltip {
    fun register() {
        HudElementRegistry.replaceElement(VanillaHudElements.HELD_ITEM_TOOLTIP) { vanilla ->
            HudElement { g, tickCounter ->
                if (!cfg.general.hideHeldItemTooltip) vanilla.extractRenderState(g, tickCounter)
            }
        }
    }
}
