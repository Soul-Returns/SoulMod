package com.soulreturns.config.categories

import com.google.gson.annotations.Expose
import com.soulreturns.config.categories.fishing.ChatSubCategory
import io.github.notenoughupdates.moulconfig.annotations.Category

class FishingCategory {
    @Expose
    @JvmField
    @Category(name = "Chat", desc = "Chat features")
    var ChatSubCategory = ChatSubCategory()
}