package com.soulreturns.config.lib.annotations

/**
 * Marks a field as a configuration subcategory.
 * Subcategories are nested within categories.
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ConfigSubcategory(
    val name: String,
    val description: String = ""
)
