package com.soulreturns.config.categories

import com.google.gson.annotations.Expose
import com.soulreturns.config.categories.mining.DwarvenMinesSubCategory
import com.soulreturns.config.categories.render.HighlightSubCategory
import io.github.notenoughupdates.moulconfig.annotations.Category
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorColour
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorText
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class RenderCategory {
    @Expose
    @JvmField
    @Category(name = "Highlights", desc = "Item highlights")
    var highlightSubCategory = HighlightSubCategory()

    @Expose
    @JvmField
    @ConfigOption(name = "Hide Held Tooltip", desc = "Hides the annoying tooltip above the hotbar when swapping items")
    @ConfigEditorBoolean
    var hideHeldItemTooltip: Boolean = false;

    @Expose
    @JvmField
    @ConfigOption(name = "Old Sneak Height", desc = "Reverts sneak height")
    @ConfigEditorBoolean
    var oldSneakHeight: Boolean = false

    @Expose
    @JvmField
    @ConfigOption(name = "Show Skyblock ID in Tooltip", desc = "Shows the Skyblock item ID in the item tooltip")
    @ConfigEditorBoolean
    var showSkyblockIdInTooltip: Boolean = false;
}