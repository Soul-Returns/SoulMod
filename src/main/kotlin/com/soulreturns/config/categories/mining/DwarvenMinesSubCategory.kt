package com.soulreturns.config.categories.mining

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle

class DwarvenMinesSubCategory {
    @JvmField
    @ConfigOption(name = "Don Expresso", description = "Show an alert when Don Expresso is about to leave (currently only works when completed early)")
    @Toggle
    var donExpressoAlert: Boolean = true
}
