package com.soulreturns.config

import com.soulreturns.Soul
import com.soulreturns.config.lib.manager.SoulConfigManager

class ConfigInstance {
    companion object {
        @JvmStatic
        fun get(): SoulConfigManager<MainConfig> {
            return Soul.configManager.config
        }

        @JvmStatic
        fun getInstance(): MainConfig {
            return Soul.configManager.config.instance
        }
    }
}

// Alias for shorter access
typealias Config = MainConfig

// Top-level property for easy access
val config: MainConfig
    get() = ConfigInstance.getInstance()
