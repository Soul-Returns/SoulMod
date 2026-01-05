package com.soulreturns.config.categories.render

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle

class HighlightSubCategory {
    @JvmField
    @ConfigOption(name = "Item Highlighting", description = "Highlight specific Skyblock items in inventories (configured via JSON files in config/soul/highlight)")
    @Toggle
    var itemHighlightingEnabled: Boolean = false

    @JvmField
    @ConfigOption(name = "Highlight Pest Equipment", description = "Highlight Pest Hunter's equipment in inventories")
    @Toggle
    var highlightPestEquipment: Boolean = false

    @JvmField
    @ConfigOption(name = "Use Pest Vest Instead", description = "Replace PESTHUNTERS_CLOAK with PEST_VEST in highlights")
    @Toggle
    var usePestVest: Boolean = false

    @JvmField
    @ConfigOption(name = "Highlight Farming Equipment", description = "Highlight farming equipment (Lotus, Zorros, Blossom) in inventories")
    @Toggle
    var highlightFarmingEquipment: Boolean = false

    @JvmField
    @ConfigOption(name = "Highlight Custom Items", description = "Highlight custom items from config/soul/highlight/custom")
    @Toggle
    var highlightCustomItems: Boolean = false
}
