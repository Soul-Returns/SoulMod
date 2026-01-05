package com.soulreturns.config.lib.annotations

/**
 * Marks a field as a configuration category.
 * Categories appear in the left sidebar of the config GUI.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigCategory(
    val name: String,
    val description: String = ""
)
