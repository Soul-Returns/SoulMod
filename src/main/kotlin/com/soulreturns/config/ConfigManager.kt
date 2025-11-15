package com.soulreturns.config

import io.github.notenoughupdates.moulconfig.managed.ManagedConfig
import java.io.File

class ConfigManager {
    var config: ManagedConfig<MainConfig>

    init {
        val configFile = File("config/soul/config.json")
        configFile.parentFile.mkdirs()

        config = ManagedConfig.create(
            configFile,
            MainConfig::class.java
        ) { }

        config.reloadFromFile()
    }

    fun save() {
        config.saveToFile()
    }
}