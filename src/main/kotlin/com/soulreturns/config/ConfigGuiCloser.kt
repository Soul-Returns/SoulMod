package com.soulreturns.config

import com.soulreturns.Soul
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text

object ConfigGuiCloser {
    private var watched: Screen? = null
    private var manager: ConfigManager? = null

    init {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val target = watched ?: return@register
            if (client.currentScreen !== target) {
                try {
                    MinecraftClient.getInstance().player?.sendMessage(
                        Text.of("Closed config GUI, saving config..."),
                        false
                    )
                    manager?.save()
                    Soul.loadFeatures()
//                    MinecraftClient.getInstance().worldRenderer.reload()
                } finally {
                    watched = null
                    manager = null
                }
            }
        }
    }

    fun watch(configManager: ConfigManager, screen: Screen?) {
        if (screen == null) return
        manager = configManager
        watched = screen
    }
}