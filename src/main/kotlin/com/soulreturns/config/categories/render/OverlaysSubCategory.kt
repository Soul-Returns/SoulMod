package com.soulreturns.config.categories.render

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle

class OverlaysSubCategory {
    @JvmField
    @ConfigOption(
        name = "Enable Legion Counter",
        description = "Show a HUD element with the number of nearby players for Legion."
    )
    @Toggle
    var enableLegionCounter: Boolean = false
}
