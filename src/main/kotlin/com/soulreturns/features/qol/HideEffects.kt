package com.soulreturns.features.qol

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements

/**
 * Hides vanilla potion-effect displays.
 *
 * HUD half: wraps the vanilla MOB_EFFECTS HUD element via Fabric's registry — the wrapper reads
 * the config live, so the toggle applies immediately. Inventory half lives in
 * `com.soulreturns.mixin.EffectsInInventoryMixin` (no API hook exists for that one).
 */
object HideEffects {
    fun register() {
        HudElementRegistry.replaceElement(VanillaHudElements.MOB_EFFECTS) { vanilla ->
            HudElement { g, tickCounter ->
                if (!cfg.general.effectsHudHidden) vanilla.extractRenderState(g, tickCounter)
            }
        }
    }
}
