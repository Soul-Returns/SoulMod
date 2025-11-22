package com.soulreturns.config

import com.soulreturns.Soul
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.gui.screen.Screen

object ConfigGuiCloser {
    private var watched: Screen? = null
    private var manager: ConfigManager? = null

    init {
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            val target = watched ?: return@register
            if (client.currentScreen !== target) {
                try {
                    manager?.save()
                    Soul.reloadFeatures()
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