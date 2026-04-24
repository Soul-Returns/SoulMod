package com.soulreturns.config.categories.render

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.SliderNumberInput

class HudScaleSubCategory {
    @JvmField
    @ConfigOption(
        name = "Hotbar Scale",
        description = "Scale the hotbar, health/food bars, and experience bar"
    )
    @SliderNumberInput(min = 0.5, max = 2.0, step = 0.05, decimals = 2)
    var hotbarScale: Float = 1.0f

    @JvmField
    @ConfigOption(
        name = "Boss Bar Scale",
        description = "Scale the boss bar at the top of the screen"
    )
    @SliderNumberInput(min = 0.5, max = 2.0, step = 0.05, decimals = 2)
    var bossBarScale: Float = 1.0f

    @JvmField
    @ConfigOption(
        name = "Chat Scale",
        description = "Scale the chat overlay and input field"
    )
    @SliderNumberInput(min = 0.5, max = 2.0, step = 0.05, decimals = 2)
    var chatScale: Float = 1.0f

    @JvmField
    @ConfigOption(
        name = "Action Bar Scale",
        description = "Scale the action bar text above the hotbar"
    )
    @SliderNumberInput(min = 0.5, max = 2.0, step = 0.05, decimals = 2)
    var actionBarScale: Float = 1.0f

    @JvmField
    @ConfigOption(
        name = "Scoreboard Scale",
        description = "Scale the sidebar scoreboard"
    )
    @SliderNumberInput(min = 0.5, max = 2.0, step = 0.05, decimals = 2)
    var scoreboardScale: Float = 1.0f
}
