package com.soulreturns.config

import com.google.gson.annotations.Expose
import com.soulreturns.Soul
import com.soulreturns.config.categories.RenderCategory
import com.soulreturns.config.categories.FishingCategory
import com.soulreturns.config.categories.FixesCategory
import com.soulreturns.config.categories.MiningCategory
import io.github.notenoughupdates.moulconfig.Config
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.common.text.StructuredText

class MainConfig : Config() {
    override fun getTitle(): StructuredText {
        return StructuredText.of("Soul Mod - v${Soul.version}").green()
    }

    @JvmField
    @Expose
    @Category(name = "Render", desc = "Render features")
    var renderCategory = RenderCategory()

    @JvmField
    @Expose
    @Category(name = "Fishing", desc = "Fishing features")
    var fishingCategory = FishingCategory()

    @JvmField
    @Expose
    @Category(name = "Mining", desc = "Mining features")
    var miningCategory = MiningCategory()

    @JvmField
    @Expose
    @Category(name = "Fixes", desc = "Fixes")
    var fixesCategory = FixesCategory()
}