package com.soulreturns.config.categories

import com.google.gson.annotations.Expose
import io.github.notenoughupdates.moulconfig.annotations.ConfigEditorBoolean
import io.github.notenoughupdates.moulconfig.annotations.ConfigOption

class RenderCategory {
    @Expose
    @JvmField
    @ConfigOption(name = "Hide Held Tooltip", desc = "Hides the annoying tooltip above the hotbar when swapping items")
    @ConfigEditorBoolean
    var hideHeldItemTooltip: Boolean = true;

    @Expose
    @JvmField
    @ConfigOption(name = "Fuck Diorite", desc = "Replace Diorite with Glass")
    @ConfigEditorBoolean
    var fuckDiorite: Boolean = false;

    @Expose
    @JvmField
    @ConfigOption(name = "Replace Lava", desc = "Replace Lava with Water")
    @ConfigEditorBoolean
    var replaceLava: Boolean = false;
}