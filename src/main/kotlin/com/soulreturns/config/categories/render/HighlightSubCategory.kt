package com.soulreturns.config.categories.render

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class HighlightSubCategory {
    @Expose
    @JvmField
    @ConfigOption(name = "Item Highlighting", desc = "Highlight specific Skyblock items in inventories (configured via JSON files in config/soul/highlight)")
    @ConfigEditorBoolean
    var itemHighlightingEnabled: Boolean = false;

    @Expose
    @JvmField
    @ConfigOption(name = "Highlight Pest Equipment", desc = "Highlight Pest Hunter's equipment in inventories")
    @ConfigEditorBoolean
    var highlightPestEquipment: Boolean = false;

    @Expose
    @JvmField
    @ConfigOption(name = "Use Pest Vest Instead", desc = "Replace PESTHUNTERS_CLOAK with PEST_VEST in highlights")
    @ConfigEditorBoolean
    var usePestVest: Boolean = false;

    @Expose
    @JvmField
    @ConfigOption(name = "Highlight Farming Equipment", desc = "Highlight farming equipment (Lotus, Zorros, Blossom) in inventories")
    @ConfigEditorBoolean
    var highlightFarmingEquipment: Boolean = false;

    @Expose
    @JvmField
    @ConfigOption(name = "Highlight Custom Items", desc = "Highlight custom items from config/soul/highlight/custom")
    @ConfigEditorBoolean
    var highlightCustomItems: Boolean = false;
}