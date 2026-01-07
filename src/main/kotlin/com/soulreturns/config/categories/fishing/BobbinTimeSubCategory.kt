package com.soulreturns.config.categories.fishing

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.SliderNumberInput
import com.soulreturns.config.lib.annotations.TextInput
import com.soulreturns.config.lib.annotations.Toggle

class BobbinTimeSubCategory {
    @JvmField
    @ConfigOption(
        name = "Enable Bobbin Time Counter",
        description = "Show a HUD element with the number of nearby fishing bobbers."
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

    @JvmField
    @ConfigOption(
        name = "Sync with party",
        description = "If enabled, use total party size minus one as the alert threshold (capped at 5).",
        dynamicNameKey = "bobbin_sync_party",
    )
    @Toggle
    var syncBobbinAlertWithParty: Boolean = false

    @JvmField
    @ConfigOption(
        name = "Alert Item Name Filter",
        description = "Only send Bobbin Time alerts when you have an inventory item whose name contains this text (case-insensitive). Leave empty to always alert."
    )
    @TextInput(placeholder = "e.g. Spade", maxLength = 64)
    var alertItemNameFilter: String = "Spade"
}
