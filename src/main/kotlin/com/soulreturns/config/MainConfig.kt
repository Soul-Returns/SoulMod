package com.soulreturns.config

import com.soulreturns.config.categories.RenderCategory
import com.soulreturns.config.categories.FishingCategory
import com.soulreturns.config.categories.FixesCategory
import com.soulreturns.config.categories.MiningCategory
import com.soulreturns.config.categories.DebugCategory
import com.soulreturns.config.lib.annotations.ConfigCategory

class MainConfig {
    @JvmField
    @ConfigCategory(name = "Render", description = "Render features")
    var renderCategory = RenderCategory()

    @JvmField
    @ConfigCategory(name = "Fishing", description = "Fishing features")
    var fishingCategory = FishingCategory()

    @JvmField
    @ConfigCategory(name = "Mining", description = "Mining features")
    var miningCategory = MiningCategory()

    @JvmField
    @ConfigCategory(name = "Fixes", description = "Fixes")
    var fixesCategory = FixesCategory()

    @JvmField
    @ConfigCategory(name = "Debug", description = "Debug settings")
    var debugCategory = DebugCategory()
}
