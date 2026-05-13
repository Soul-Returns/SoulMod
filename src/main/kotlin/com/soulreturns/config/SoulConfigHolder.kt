package com.soulreturns.config

/**
 * Top-level holder for the generated owo-config wrapper. Initialised exactly
 * once by [com.soulreturns.Soul.onInitializeClient]. Use [cfg] from any
 * client code instead of touching this directly.
 */
object SoulConfigHolder {
    lateinit var INSTANCE: SoulConfig
        private set

    fun init(): SoulConfig {
        // Migrators must run *before* createAndLoad() so they can write the
        // legacy values into the new file before owo reads it.
        LegacyConfigMigrator.runIfPresent()
        LegacyConfigMigrator.migrateOwoConfigPaths()
        INSTANCE = SoulConfig.createAndLoad()
        return INSTANCE
    }

    @JvmStatic
    fun isConfigReady(): Boolean = ::INSTANCE.isInitialized

    /**
     * Re-read the on-disk config.json5 into the existing wrapper. Used by cloud sync
     * after a remote pull writes a new config file. Consumers reading `cfg.*` at point
     * of use will pick up the new values on their next read.
     */
    fun reload() {
        if (::INSTANCE.isInitialized) {
            INSTANCE.load()
        }
    }
}

/** Convenience accessor; mirrors the previous `config` top-level property. */
val cfg: SoulConfig get() = SoulConfigHolder.INSTANCE
