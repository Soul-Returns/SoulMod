package com.soulreturns.features.qol

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.CameraType

/**
 * Skips the front-facing third-person view when cycling the camera perspective (F5).
 *
 * F5 is handled inside `Minecraft.handleKeybinds()` during the tick, so correcting the camera at
 * END_CLIENT_TICK (same tick, before the next frame renders) means the front view never appears
 * on screen — no mixin needed. `cycle()` continues the vanilla order, landing on first-person.
 */
object NoFrontCamera {
    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc ->
            if (cfg.general.noFrontCamera && mc.options.cameraType == CameraType.THIRD_PERSON_FRONT) {
                mc.options.cameraType = mc.options.cameraType.cycle()
            }
        }
    }
}
