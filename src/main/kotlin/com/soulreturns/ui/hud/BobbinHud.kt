package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.features.fishing.BobbinSpotter
import com.soulreturns.gui.lib.GuiLayoutApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * Bobbin Time HUD — pure view. Reads [BobbinSpotter.nearbyBobbers] each tick and
 * pushes a text block into [GuiLayoutApi].
 *
 * The spotter must register *before* this HUD so the count read here is from the
 * current tick, not the previous one. [com.soulreturns.Soul.registerFeatures] enforces
 * the order.
 */
object BobbinHud {
    private const val ELEMENT_ID = "bobbin_time_counter"

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { _ -> render() }
    }

    private fun render() {
        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            title = "Bobbin Time",
            lines = listOf("Nearby bobbers: ${BobbinSpotter.nearbyBobbers}"),
            color = 0xFF00FFFF.toInt(),
            enabled = cfg.fishing.bobbinTime.enableBobbinTimeCounter(),
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.35,
            defaultScale = 1.0f,
        )
    }
}
