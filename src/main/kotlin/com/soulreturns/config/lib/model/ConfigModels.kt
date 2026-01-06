package com.soulreturns.config.lib.model

import java.lang.reflect.Field

/**
 * Represents the entire configuration structure
 */
data class ConfigStructure(
    val categories: List<CategoryData>
)

/**
 * Represents a top-level category
 */
data class CategoryData(
    val name: String,
    val description: String,
    val field: Field,
    val subcategories: List<SubcategoryData>,
    val options: List<OptionData>
)

/**
 * Represents a subcategory within a category
 */
data class SubcategoryData(
    val name: String,
    val description: String,
    val field: Field,
    val options: List<OptionData>
)

/**
 * Represents a single config option
 */
data class OptionData(
    val name: String,
    val description: String,
    val field: Field,
    val type: OptionType
)

/**
 * Types of config options
 */
sealed class OptionType {
    data object Toggle : OptionType()
    data class Slider(val min: Double, val max: Double, val step: Double) : OptionType()
    data class TextInput(val placeholder: String, val maxLength: Int) : OptionType()
    data class NumberInput(val min: Double, val max: Double, val decimals: Int) : OptionType()
    data class SliderNumberInput(
        val min: Double,
        val max: Double,
        val step: Double,
        val decimals: Int
    ) : OptionType()
    data class ColorPicker(val allowAlpha: Boolean) : OptionType()
    data class Dropdown(val values: Array<String>) : OptionType()
    data class Button(val text: String) : OptionType()
    data class Divider(val label: String) : OptionType()
}
