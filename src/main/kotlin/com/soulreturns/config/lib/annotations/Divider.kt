package com.soulreturns.config.lib.annotations

/**
 * Marks a field as a divider/separator in the config GUI
 * The field value is ignored - it's just used as a visual separator
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Divider(
    val label: String = "" // Optional label for the divider
)
