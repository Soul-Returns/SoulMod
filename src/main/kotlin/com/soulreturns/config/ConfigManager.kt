package com.soulreturns.config

import com.soulreturns.config.lib.manager.SoulConfigManager
import java.io.File

class ConfigManager {
    var config: SoulConfigManager<MainConfig>

    init {
        val configFile = File("config/soul/config.json")

        config = SoulConfigManager(
            configFile,
            MainConfig::class.java
        ) { MainConfig() }
    }

    fun save() {
        config.save()
    }
}
