package com.soulreturns.config.categories.fishing

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.SliderNumberInput
import com.soulreturns.config.lib.annotations.Toggle

class BobbinTimeSubCategory {
    @JvmField
    @ConfigOption(
        name = "Enable Bobbin Time Counter",
        description = "Show nearby fishing bobbers and optional alert for the Bobbin Time enchant."
    )
    @Toggle
    var enableBobbinTimeCounter: Boolean = false

    @JvmField
    @ConfigOption(
        name = "Enable Bobbin Time Alert",
        description = "Show an on-screen alert with sound when enough bobbers are nearby."
    )
    @Toggle
    var enableBobbinTimeAlert: Boolean = true

    @JvmField
    @ConfigOption(
        name = "Required Bobbers for Alert",
        description = "Minimum fishing bobbers within 30 blocks to trigger the alert (1-5)."
    )
    @SliderNumberInput(min = 1.0, max = 5.0, step = 1.0, decimals = 0)
    var alertBobberCount: Int = 5
}
