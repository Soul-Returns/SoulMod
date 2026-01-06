package com.soulreturns.config.categories

import com.soulreturns.config.categories.render.HighlightSubCategory
import com.soulreturns.config.lib.annotations.ConfigSubcategory
import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle
import com.soulreturns.config.lib.annotations.Divider

class RenderCategory {
    @JvmField
    @ConfigSubcategory(name = "Highlights", description = "Item highlights")
    var highlightSubCategory = HighlightSubCategory()

    @JvmField
    @ConfigOption(name = "Tooltips")
    @Divider(label = "Tooltips")
    var divider1: Boolean = false
    
    @JvmField
    @ConfigOption(name = "Hide Held Tooltip", description = "Hides the annoying tooltip above the hotbar when swapping items")
    @Toggle
    var hideHeldItemTooltip: Boolean = false
    
    @JvmField
    @ConfigOption(name = "Show Skyblock ID in Tooltip", description = "Shows the Skyblock item ID in the item tooltip")
    @Toggle
    var showSkyblockIdInTooltip: Boolean = false
    
    @JvmField
    @ConfigOption(name = "Player Rendering")
    @Divider(label = "Player Rendering")
    var divider2: Boolean = false

    @JvmField
    @ConfigOption(name = "Old Sneak Height", description = "Reverts sneak height")
    @Toggle
    var oldSneakHeight: Boolean = false
}
