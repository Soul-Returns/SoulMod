package com.soulreturns.config.categories

import com.soulreturns.config.categories.debug.LoggingSubCategory
import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.ConfigSubcategory
import com.soulreturns.config.lib.annotations.Toggle

class DebugCategory {
    @JvmField
    @ConfigOption(name = "Enable Debug Mode", description = "Enable debug logging and features globally")
    @Toggle
    var debugMode: Boolean = false

    @JvmField
    @ConfigSubcategory(name = "Logging", description = "Configure debug logging options")
    var loggingSubCategory = LoggingSubCategory()
}
