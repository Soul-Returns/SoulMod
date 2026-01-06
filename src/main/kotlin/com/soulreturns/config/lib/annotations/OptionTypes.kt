package com.soulreturns.config.lib.annotations

/**
 * Boolean toggle switch option
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Toggle

/**
 * Slider for numeric values
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Slider(
    val min: Double = 0.0,
    val max: Double = 100.0,
    val step: Double = 1.0
)

/**
 * Text input field
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class TextInput(
    val placeholder: String = "",
    val maxLength: Int = 256
)

/**
 * Number input field
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class NumberInput(
    val min: Double = Double.NEGATIVE_INFINITY,
    val max: Double = Double.POSITIVE_INFINITY,
    val decimals: Int = 0
)

/**
 * Color picker
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class ColorPicker(
    val allowAlpha: Boolean = true
)

/**
 * Dropdown selection
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Dropdown(
    val values: Array<String>
)

/**
 * Clickable button
 */
@Target(AnnotationTarget.FIELD)
@Retention(AnnotationRetention.RUNTIME)
annotation class Button(
    val text: String = "Click"
)
