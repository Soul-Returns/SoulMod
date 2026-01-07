package com.soulreturns.config.categories

import com.soulreturns.config.lib.annotations.ConfigOption
import com.soulreturns.config.lib.annotations.Toggle

/**
 * Config category for enabling / disabling individual config GUI minigames.
 */
class MinigamesCategory {
    @JvmField
    @ConfigOption(
        name = "Enable Snake",
        description = "Show the Snake minigame in the config GUI"
    )
    @Toggle
    var enableSnake: Boolean = true

    @JvmField
    @ConfigOption(
        name = "Enable Tetris",
        description = "Show the Tetris minigame in the config GUI"
    )
    @Toggle
    var enableTetris: Boolean = true

    @JvmField
    @ConfigOption(
        name = "Enable Mandelbrot Viewer",
        description = "Show the Mandelbrot set viewer in the config GUI"
    )
    @Toggle
    var enableMandelbrot: Boolean = true

    @JvmField
    @ConfigOption(
        name = "Enable DOOM",
        description = "Show the DOOM minigame in the config GUI"
    )
    @Toggle
    var enableDoom: Boolean = true
}
