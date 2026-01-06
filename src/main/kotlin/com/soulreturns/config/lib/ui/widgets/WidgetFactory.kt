package com.soulreturns.config.lib.ui.widgets

import com.soulreturns.config.lib.model.OptionData
import com.soulreturns.config.lib.model.OptionType

/**
 * Factory for creating config widgets based on option type
 */
object WidgetFactory {
    
    fun createWidget(option: OptionData, x: Int, y: Int): ConfigWidget {
        return when (option.type) {
            is OptionType.Toggle -> ToggleWidget(option, x, y)
            is OptionType.Slider -> SliderWidget(option, x, y, option.type)
            is OptionType.TextInput -> TextInputWidget(option, x, y, option.type)
            is OptionType.ColorPicker -> ColorPickerWidget(option, x, y, option.type)
            is OptionType.NumberInput -> TextInputWidget(
                option, x, y,
                OptionType.TextInput("0", 64)
            ) // Simplified for now
            is OptionType.Dropdown -> DropdownWidget(option, x, y, option.type.values)
            is OptionType.Button -> ToggleWidget(option, x, y) // Simplified for now
            is OptionType.Divider -> DividerWidget(option, x, y, option.type)
        }
    }
}
