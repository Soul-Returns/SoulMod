package com.soulreturns.config.lib.ui.themes

/**
 * Light theme (clean modern look)
 */
object LightTheme : Theme {
    override val name = "Light"
    
    // Background colors
    override val backgroundTop = 0xFFFAFAFA.toInt()
    override val backgroundBottom = 0xFFFFFFFF.toInt()
    override val overlayColor = 0x80F5F5F5.toInt()
    
    // Sidebar colors
    override val sidebarBackground = 0xFFFAFAFA.toInt()
    override val categoryBackground = 0xFFFFFFFF.toInt()
    override val categoryHover = 0xFFF5F5F5.toInt()
    override val categorySelected = 0xFFF0F0F0.toInt()
    override val categoryBorder = 0xFFE0E0E0.toInt()
    override val subcategoryBackground = 0xFFFAFAFA.toInt()
    override val subcategoryHover = 0xFFF5F5F5.toInt()
    override val subcategorySelected = 0xFFEAEAEA.toInt()
    
    // Content area colors
    override val contentBackground = 0xFFFAFAFA.toInt()
    
    // Title bar colors
    override val titleBarBackground = 0xFFFFFFFF.toInt()
    override val closeButtonNormal = 0xFFF0F0F0.toInt()
    override val closeButtonHover = 0xFFFF6B6B.toInt()
    
    // Text colors
    override val textPrimary = 0xFF2C2C2C.toInt()
    override val textSecondary = 0xFF757575.toInt()
    override val textDisabled = 0xFFBBBBBB.toInt()
    
    // Widget colors
    override val widgetBackground = 0xFFE0E0E0.toInt()
    override val widgetHover = 0xFFD0D0D0.toInt()
    override val widgetActive = 0xFF4C9AFF.toInt()
    override val widgetBorder = 0xFFE0E0E0.toInt()
    override val widgetHighlight = 0xFFE8F4FD.toInt()
    
    // Option card colors
    override val optionCardBackground = 0xFFFFFFFF.toInt()
    override val optionCardBorder = 0xFFE0E0E0.toInt()
    override val optionCardShadow = 0x08000000
    
    // Style properties
    override val useBorders = true
    override val useCardStyle = true
}
