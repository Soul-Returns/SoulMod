package com.soulreturns.config.lib.ui.themes

/**
 * Dark theme (default)
 */
object DarkTheme : Theme {
    override val name = "Dark"
    
    // Background colors
    override val backgroundTop = 0xFF0F0F0F.toInt()
    override val backgroundBottom = 0xFF1A1A1A.toInt()
    override val overlayColor = 0xC0101010.toInt()
    
    // Sidebar colors
    override val sidebarBackground = 0xFF151515.toInt()
    override val categoryBackground = 0xFF1F1F1F.toInt()
    override val categoryHover = 0xFF2C2C2C.toInt()
    override val categorySelected = 0xFF2C5AA0.toInt()
    override val categoryBorder = 0xFF000000.toInt()
    override val subcategoryBackground = 0xFF242424.toInt()
    override val subcategoryHover = 0xFF2A2A2A.toInt()
    override val subcategorySelected = 0xFF4C9AFF.toInt()
    
    // Content area colors
    override val contentBackground = 0xFF1A1A1A.toInt()
    
    // Title bar colors
    override val titleBarBackground = 0xDD1C1C1C.toInt()
    override val closeButtonNormal = 0xFF3C3C3C.toInt()
    override val closeButtonHover = 0xFFFF4444.toInt()
    
    // Text colors
    override val textPrimary = 0xFFFFFFFF.toInt()
    override val textSecondary = 0xFFCCCCCC.toInt()
    override val textDisabled = 0xFF666666.toInt()
    
    // Widget colors
    override val widgetBackground = 0xFF3C3C3C.toInt()
    override val widgetHover = 0xFF4C4C4C.toInt()
    override val widgetActive = 0xFF4C9AFF.toInt()
    override val widgetBorder = 0xFF000000.toInt()
    override val widgetHighlight = 0xFF4C9AFF.toInt()
    
    // Option card colors - subtle separators
    override val optionCardBackground = 0xFF222222.toInt()
    override val optionCardBorder = 0xFF2A2A2A.toInt()
    override val optionCardShadow = 0x00000000
    
    // Style properties
    override val categoryCornerRadius = 6f
    override val widgetCornerRadius = 12f
    override val cardCornerRadius = 4f
    override val useBorders = true
    override val useCardStyle = true
}
