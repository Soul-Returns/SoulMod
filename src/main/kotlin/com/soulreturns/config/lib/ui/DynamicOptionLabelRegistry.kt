package com.soulreturns.config.lib.ui

import com.soulreturns.config.lib.model.OptionData

/**
 * Registry for dynamic option labels. Options can opt-in via
 * ConfigOption(dynamicNameKey = "some_key") and host code can register a
 * formatter for that key at runtime.
 */
object DynamicOptionLabelRegistry {
    private val formatters: MutableMap<String, (OptionData) -> String> = mutableMapOf()

    /**
     * Register (or replace) a formatter for the given key.
     */
    @JvmStatic
    fun register(key: String, formatter: (OptionData) -> String) {
        formatters[key] = formatter
    }

    /**
     * Format the display name for an option, falling back to its static name
     * when no formatter or key is present.
     */
    @JvmStatic
    fun format(option: OptionData): String {
        val key = option.dynamicNameKey
        if (key.isEmpty()) return option.name
        val formatter = formatters[key] ?: return option.name
        return try {
            formatter(option)
        } catch (_: Throwable) {
            option.name
        }
    }
}
