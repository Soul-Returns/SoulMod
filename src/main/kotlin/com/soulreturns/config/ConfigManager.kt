package com.soulreturns.config

import com.soulreturns.config.lib.manager.SoulConfigManager
import java.io.File

class ConfigManager {
    var config: SoulConfigManager<MainConfig>

    init {
        val configFile = File("config/soul/config.json")

        // Run migrations (if any) on the raw JSON file before the typed
        // config instance is created. This lets us upgrade legacy layouts
        // that did not have a configVersion field.
        ConfigMigration.migrateIfNeeded(configFile)

        config = SoulConfigManager(
            configFile,
            MainConfig::class.java
        ) { MainConfig() }
    }

    fun save() {
        config.save()
    }
}
