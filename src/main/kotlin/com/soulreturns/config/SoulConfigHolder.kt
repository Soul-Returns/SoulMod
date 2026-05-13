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
     * Re-read `config.json5` into the existing [INSTANCE]. Used by cloud sync after a remote
     * pull writes a new file. Consumers reading `cfg.*` at point of use will pick up the new
     * values on their next read.
     *
     * **Why `load()` (in-place) is fine:** the admin web UI always pushes complete JSON
     * containing every option — either the current value or the declared default (the mod
     * uploads its defaults alongside content, so the backend has them to render "reset"
     * buttons). There's no scenario where a key is missing, so we never need to recover
     * defaults from the model. If we ever start producing partial JSON, this needs to switch
     * to a wrapper-rebuild — and owo-config's `ConfigWrapper` guards against double-registration,
     * so the implementation must reset the existing wrapper's internal model rather than
     * call `createAndLoad()` (which crashes with "Config name 'soul/config' is already taken").
     */
    fun reload() {
        if (::INSTANCE.isInitialized) {
            INSTANCE.load()
        }
    }

    /**
     * Build a JSON tree of every option's declared default value, matching the layout of
     * `config.json5`. Used by cloud sync — the admin UI uses this to render per-row "reset
     * to default" buttons and a "reset all" button, without the backend needing to know
     * what the defaults are. Re-sent on every config push so the backend always has a
     * fresh copy (defaults change when the mod version updates).
     */
    fun defaultsJson(): com.google.gson.JsonObject? {
        if (!::INSTANCE.isInitialized) return null
        val gson = com.google.gson.Gson()
        val root = com.google.gson.JsonObject()
        INSTANCE.forEachOption { opt ->
            val path = opt.key().path()
            if (path.isEmpty()) return@forEachOption
            var node = root
            for (i in 0 until path.size - 1) {
                val seg = path[i]
                val existing = if (node.has(seg) && node.get(seg).isJsonObject) node.getAsJsonObject(seg) else null
                node =
                    existing ?: com.google.gson.JsonObject().also { fresh ->
                        node.add(seg, fresh)
                    }
            }
            node.add(path.last(), gson.toJsonTree(opt.defaultValue()))
        }
        return root
    }
}

/** Convenience accessor; mirrors the previous `config` top-level property. */
val cfg: SoulConfig get() = SoulConfigHolder.INSTANCE
