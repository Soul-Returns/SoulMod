package com.soulreturns.config.lib.ui.themes

import com.soulreturns.config.config

/**
 * Manages theme selection and provides the current theme
 */
object ThemeManager {
    private val themes = mapOf(
        "Dark" to DarkTheme,
        "Light" to LightTheme,
        "Pastel" to PastelTheme
    )
    
    val availableThemes: Array<String> = themes.keys.toTypedArray()
    
    fun getCurrentTheme(): Theme {
        return try {
            val themeName = config.configCategory.theme
            themes[themeName] ?: DarkTheme
        } catch (e: Exception) {
            DarkTheme // Default to dark theme if config not available
        }
    }
}
