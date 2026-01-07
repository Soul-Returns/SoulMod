package com.soulreturns.config.lib.annotations

/**
 * Marks a field as a configuration option.
 * Must be combined with a type annotation (Toggle, Slider, etc.)
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigOption(
    val name: String,
    val description: String = "",
    /**
     * Optional key for dynamically formatting the option name at render time.
     * When non-empty, the UI can look up a formatter by this key and replace
     * tokens (e.g. to show live values in the label).
     */
    val dynamicNameKey: String = "",
)
