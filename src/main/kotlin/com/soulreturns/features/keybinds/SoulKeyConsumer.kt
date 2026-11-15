package com.soulreturns.features.keybinds

import com.mojang.blaze3d.platform.InputConstants
import com.soulreturns.config.cfg
import com.soulreturns.platform.devaccess.DevAccess
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.util.KeyCombo
import net.minecraft.client.Minecraft
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * Keeps Soul's keybinds from also doing their vanilla job: a press that completes a Soul binding is
 * swallowed before Minecraft sees it (`KeyboardHandlerMixin`), so `Right Ctrl + 1` copies a dump
 * instead of also selecting hotbar slot 1 or swapping the hovered item into it.
 *
 * Soul's pollers read the physical key state from GLFW, so they still fire. Releases always pass,
 * which can only ever un-press a vanilla mapping.
 *
 * - **Dev keybinds** are swallowed wherever they fire: in the world and over any non-Soul screen.
 *   Without developer access they are inert, so they swallow nothing.
 * - **Command keybinds and toggle sprint** fire only with no screen open, and only their combos
 *   (with modifiers) are swallowed: a plain key keeps sharing with vanilla, which the editor's
 *   conflict warning already reports.
 * - **Soul screen presets** (Soul GUI) also fire over inventories and menus
 *   ([CommandKeybinds.opensOver]); there their key is swallowed even without modifiers, since the
 *   press replaces the screen (a plain `Q` must not also drop the hovered item).
 * - Soul screens get every key, or a combo could never be captured.
 */
object SoulKeyConsumer {
    @JvmStatic
    fun consumes(action: Int, event: KeyEvent): Boolean {
        if (action == GLFW.GLFW_RELEASE) return false
        val mc = Minecraft.getInstance()
        val screen = mc.gui.screen()
        if (screen is SoulScreenBase) return false
        val key = InputConstants.getKey(event)
        val dev = cfg.dev.keybinds
        if (
            DevAccess.unlocked && (
                completes(dev.copyOpenedGui, key, mc, false) || completes(dev.copyItemUnderCursor, key, mc, false) ||
                    completes(dev.copyHeldItem, key, mc, false) || completes(dev.copyScoreboard, key, mc, false) ||
                    completes(dev.copyTablist, key, mc, false) || completes(dev.copyNearbyEntities, key, mc, false)
                )
        ) return true
        if (screen != null) {
            if (!CommandKeybinds.opensOver(screen)) return false
            return CommandKeybinds.active().any { it.screen != null && completes(it.key, key, mc, false) }
        }
        if (completes(cfg.general.toggleSprintKey, key, mc, true)) return true
        for (binding in CommandKeybinds.active()) {
            if (completes(binding.key, key, mc, true)) return true
        }
        return false
    }

    private fun completes(stored: String, key: InputConstants.Key, mc: Minecraft, combosOnly: Boolean): Boolean {
        val combo = KeyCombo.cached(stored) ?: return false
        if (combosOnly && combo.modifiers.isEmpty()) return false
        return combo.key == key && combo.isDown(mc.window)
    }
}
