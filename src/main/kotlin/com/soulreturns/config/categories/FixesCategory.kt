package com.soulreturns.config.categories

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle

class FixesCategory {
    @JvmField
    @ConfigOption(name = "Double Sneak", description = "Fix the vanilla double sneak lag bug")
    @Toggle
    var fixDoubleSneak: Boolean = false
}
