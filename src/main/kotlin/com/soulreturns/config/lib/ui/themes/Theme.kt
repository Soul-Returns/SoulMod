package com.soulreturns.config.lib.ui.themes

/**
 * Theme interface defining colors and styles for the config GUI
 */
interface Theme {
    val name: String
    
    // Background colors
    val backgroundTop: Int
    val backgroundBottom: Int
    val overlayColor: Int
    
    // Sidebar colors
    val sidebarBackground: Int
    val categoryBackground: Int
    val categoryHover: Int
    val categorySelected: Int
    val categoryBorder: Int
    val subcategoryBackground: Int
    val subcategoryHover: Int
    val subcategorySelected: Int
    
    // Content area colors
    val contentBackground: Int
    
    // Title bar colors
    val titleBarBackground: Int
    val closeButtonNormal: Int
    val closeButtonHover: Int
    
    // Text colors
    val textPrimary: Int
    val textSecondary: Int
    val textDisabled: Int
    
    // Widget colors
    val widgetBackground: Int
    val widgetHover: Int
    val widgetActive: Int
    val widgetBorder: Int
    val widgetHighlight: Int
    
    // Option card colors (for light theme)
    val optionCardBackground: Int
    val optionCardBorder: Int
    val optionCardShadow: Int
    
    // Style properties
    val useBorders: Boolean
    val useCardStyle: Boolean
}
