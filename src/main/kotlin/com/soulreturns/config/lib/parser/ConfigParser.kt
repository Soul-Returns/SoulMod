package com.soulreturns.config.lib.parser

import com.soulreturns.config.lib.annotations.*
import com.soulreturns.config.lib.model.*
import java.lang.reflect.Field

/**
 * Parses config classes using reflection to build the config structure
 */
object ConfigParser {
    
    fun <T : Any> parse(configClass: Class<T>): ConfigStructure {
        val categories = mutableListOf<CategoryData>()
        
        for (field in configClass.declaredFields) {
            field.isAccessible = true
            val categoryAnnotation = field.getAnnotation(ConfigCategory::class.java)
            
            if (categoryAnnotation != null) {
                val categoryObject = field.type
                val subcategories = mutableListOf<SubcategoryData>()
                val options = mutableListOf<OptionData>()
                
                // Parse fields in the category object
                for (categoryField in categoryObject.declaredFields) {
                    categoryField.isAccessible = true
                    
                    val subcategoryAnnotation = categoryField.getAnnotation(ConfigSubcategory::class.java)
                    if (subcategoryAnnotation != null) {
                        // Parse subcategory
                        val subcategoryOptions = parseOptions(categoryField.type)
                        subcategories.add(
                            SubcategoryData(
                                name = subcategoryAnnotation.name,
                                description = subcategoryAnnotation.description,
                                field = categoryField,
                                options = subcategoryOptions
                            )
                        )
                    } else {
                        // Try to parse as an option
                        val optionData = parseOption(categoryField)
                        if (optionData != null) {
                            options.add(optionData)
                        }
                    }
                }
                
                categories.add(
                    CategoryData(
                        name = categoryAnnotation.name,
                        description = categoryAnnotation.description,
                        field = field,
                        subcategories = subcategories,
                        options = options
                    )
                )
            }
        }
        
        return ConfigStructure(categories)
    }
    
    private fun parseOptions(clazz: Class<*>): List<OptionData> {
        val options = mutableListOf<OptionData>()
        
        for (field in clazz.declaredFields) {
            field.isAccessible = true
            val optionData = parseOption(field)
            if (optionData != null) {
                options.add(optionData)
            }
        }
        
        return options
    }
    
    private fun parseOption(field: Field): OptionData? {
        val configOption = field.getAnnotation(ConfigOption::class.java) ?: return null
        
        val optionType = when {
            field.isAnnotationPresent(Toggle::class.java) -> OptionType.Toggle
            
            field.isAnnotationPresent(Slider::class.java) -> {
                val slider = field.getAnnotation(Slider::class.java)
                OptionType.Slider(slider.min, slider.max, slider.step)
            }

            field.isAnnotationPresent(SliderNumberInput::class.java) -> {
                val combo = field.getAnnotation(SliderNumberInput::class.java)
                OptionType.SliderNumberInput(combo.min, combo.max, combo.step, combo.decimals)
            }
            
            field.isAnnotationPresent(TextInput::class.java) -> {
                val textInput = field.getAnnotation(TextInput::class.java)
                OptionType.TextInput(textInput.placeholder, textInput.maxLength)
            }
            
            field.isAnnotationPresent(NumberInput::class.java) -> {
                val numberInput = field.getAnnotation(NumberInput::class.java)
                OptionType.NumberInput(numberInput.min, numberInput.max, numberInput.decimals)
            }
            
            field.isAnnotationPresent(ColorPicker::class.java) -> {
                val colorPicker = field.getAnnotation(ColorPicker::class.java)
                OptionType.ColorPicker(colorPicker.allowAlpha)
            }
            
            field.isAnnotationPresent(Dropdown::class.java) -> {
                val dropdown = field.getAnnotation(Dropdown::class.java)
                OptionType.Dropdown(dropdown.values)
            }
            
            field.isAnnotationPresent(Button::class.java) -> {
                val button = field.getAnnotation(Button::class.java)
                OptionType.Button(button.text)
            }
            
            field.isAnnotationPresent(Divider::class.java) -> {
                val divider = field.getAnnotation(Divider::class.java)
                OptionType.Divider(divider.label)
            }
            
            else -> return null // No type annotation found
        }
        
        return OptionData(
            name = configOption.name,
            description = configOption.description,
            field = field,
            type = optionType
        )
    }
}
