package com.soulreturns.config.lib.annotations

/**
 * Marks a field as a configuration option.
 * Must be combined with a type annotation (Toggle, Slider, etc.)
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigOption(
    val name: String,
    val description: String = ""
)
