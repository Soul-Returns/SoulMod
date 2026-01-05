package com.soulreturns.config.categories.debug

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle

class LoggingSubCategory {
    @JvmField
    @ConfigOption(name = "Log Config Changes", description = "Log when config values are changed")
    @Toggle
    var logConfigChanges: Boolean = false

    @JvmField
    @ConfigOption(name = "Log Widget Interactions", description = "Log widget clicks and interactions")
    @Toggle
    var logWidgetInteractions: Boolean = false

    @JvmField
    @ConfigOption(name = "Log Message Handler", description = "Log server and player messages")
    @Toggle
    var logMessageHandler: Boolean = false

    @JvmField
    @ConfigOption(name = "Log Feature Events", description = "Log feature-specific events")
    @Toggle
    var logFeatureEvents: Boolean = false
}
