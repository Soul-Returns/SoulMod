package com.soulreturns.config.categories

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Dropdown

class ConfigCategory {
    @JvmField
    @ConfigOption(
        name = "Theme",
        description = "Select the visual theme for the config GUI"
    )
    @Dropdown(values = ["Dark", "Light", "Pastel"])
    var theme: String = "Dark"
}
