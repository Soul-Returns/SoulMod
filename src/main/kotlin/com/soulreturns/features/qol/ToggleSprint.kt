package com.soulreturns.features.qol

import com.mojang.blaze3d.platform.InputConstants
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.Minecraft
import com.soulreturns.util.KeyCombo

/**
 * Toggle sprint: holds the sprint key down every tick so sprinting never drops (vanilla sprint
 * activation still requires forward movement, so idly holding the key is harmless).
 *
 * Underwater sprint (= fast swimming / diving) is gated behind its own toggle because forced
 * sprint underwater constantly re-enters swim mode, which some players don't want.
 *
 * The feature can be flipped on/off in-game with a configurable keybind ([pollToggleKey]); the
 * current state is surfaced by [ToggleSprintHud] ("Sprint toggled" or nothing).
 */
object ToggleSprint {
    /** Whether WE are the reason the key currently reads as down (vs the user's physical key). */
    private var forcing = false

    /** Last tick's in-water state, to detect the moment the player enters the water. */
    private var wasInWater = false

    /** Whether the toggle keybind was down last tick — so we fire on the press *edge*, not while held. */
    private var toggleKeyHeld = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    private fun tick(mc: Minecraft) {
        pollToggleKey(mc)

        val player = mc.player
        if (player == null) {
            wasInWater = false
            return
        }

        // Gate on isInWater (body touching water), NOT isUnderWater (eyes submerged): the eye-level
        // flag flickers rapidly as the player bobs at the surface, which made force+cancel fight
        // each other. Body-in-water is stable for the whole time the player is in a pool.
        val inWater = player.isInWater
        val waterBlocksForce = inWater && !cfg.general.toggleSprintUnderwater
        val shouldForce =
            cfg.general.toggleSprint &&
                !player.isSpectator &&
                !waterBlocksForce

        if (shouldForce) {
            mc.options.keySprint.setDown(true)
            forcing = true
        } else if (forcing) {
            // Stop forcing: restore the real key state, otherwise the binding stays stuck "down"
            // until the user physically presses and releases sprint once.
            mc.options.keySprint.setDown(isPhysicallyDown(mc))
            forcing = false
        }

        // When underwater sprint is off, entering the water while sprinting should NOT carry over
        // into an instant fast-swim: releasing the key alone doesn't stop the already-active sprint
        // state, so cancel it explicitly on the water-entry tick. Only on the transition, and only
        // when the user isn't physically holding sprint — that way they can still press the key to
        // start swimming fast on purpose. Because we stop forcing for the whole time the player is
        // in the water, the sprint stays cancelled (no re-force → no flip-flop).
        if (
            cfg.general.toggleSprint &&
            !cfg.general.toggleSprintUnderwater &&
            inWater &&
            !wasInWater &&
            !isPhysicallyDown(mc)
        ) {
            player.isSprinting = false
        }

        wasInWater = inWater
    }

    /**
     * Poll the user's toggle-sprint keybind ([GeneralConfig.toggleSprintKey]) and flip
     * [GeneralConfig.toggleSprint] on the press edge. Bypasses Minecraft's controls menu (the key is
     * stored as an `InputConstants` id string, like the dev keybinds) but is world-only: it's ignored
     * while any screen is open, so it never fires while typing in chat or capturing the bind itself.
     */
    private fun pollToggleKey(mc: Minecraft) {
        val combo = if (mc.gui.screen() == null) KeyCombo.cached(cfg.general.toggleSprintKey) else null
        if (combo == null) {
            toggleKeyHeld = false
            return
        }
        val down = combo.isDown(mc.window)
        if (down && !toggleKeyHeld) {
            toggleKeyHeld = true
            ConfigManager.update { copy(general = general.copy(toggleSprint = !general.toggleSprint)) }
        } else if (!down) {
            toggleKeyHeld = false
        }
    }

    private fun isPhysicallyDown(mc: Minecraft): Boolean {
        val key = KeyMappingHelper.getBoundKeyOf(mc.options.keySprint)
        // Only keyboard bindings are queryable this way; a mouse-bound sprint just reads released.
        if (key.type != InputConstants.Type.KEYSYM) return false
        return InputConstants.isKeyDown(mc.window, key.value)
    }
}
