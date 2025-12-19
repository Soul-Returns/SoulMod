package com.soulreturns.config.categories

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class FixesCategory {
    @Expose
    @JvmField
    @ConfigOption(name = "Double Sneak", desc = "Fix the vanilla double sneak lag bug")
    @ConfigEditorBoolean
    var fixDoubleSneak: Boolean = false
}