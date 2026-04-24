package com.soulreturns.config.categories

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.TextInput
import com.soulreturns.config.lib.annotations.Toggle

class ProfileViewerCategory {
    @JvmField
    @ConfigOption(
        name = "Enable /spv",
        description = "Enables the /spv (Soul Profile Viewer) command"
    )
    @Toggle
    var enabled: Boolean = true

    @JvmField
    @ConfigOption(
        name = "Backend URL Override",
        description = "Optional override for the SPV backend base URL. Leave empty to use the bundled production URL. The JVM property -Dsoul.spv.backendUrl always wins."
    )
    @TextInput(placeholder = "https://...")
    var backendUrlOverride: String = ""
}
