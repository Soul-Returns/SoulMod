package com.soulreturns.config.categories

import com.soulreturns.config.categories.mining.DwarvenMinesSubCategory
import com.soulreturns.config.lib.annotations.ConfigSubcategory

class MiningCategory {
    @JvmField
    @ConfigSubcategory(name = "Dwarven Mines", description = "Dwarven Mines related features")
    var dwarvenMinesSubCategory = DwarvenMinesSubCategory()
}
