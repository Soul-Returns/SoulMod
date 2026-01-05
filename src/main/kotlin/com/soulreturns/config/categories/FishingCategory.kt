package com.soulreturns.config.categories

import com.soulreturns.config.categories.fishing.ChatSubCategory
import com.soulreturns.config.lib.annotations.ConfigSubcategory

class FishingCategory {
    @JvmField
    @ConfigSubcategory(name = "Chat", description = "Chat features")
    var chatSubCategory = ChatSubCategory()
}
