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
        // Migrator must run *before* createAndLoad() so it can write the
        // legacy values into the new file before owo reads it.
        LegacyConfigMigrator.runIfPresent()
        INSTANCE = SoulConfig.createAndLoad()
        return INSTANCE
    }
}

/** Convenience accessor; mirrors the previous `config` top-level property. */
val cfg: SoulConfig get() = SoulConfigHolder.INSTANCE
