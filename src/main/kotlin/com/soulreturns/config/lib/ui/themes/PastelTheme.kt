package com.soulreturns.config.lib.ui.themes

/**
 * Pastel theme with soft, modern colors
 */
object PastelTheme : Theme {
    override val name = "Pastel"
    
    // Background colors - soft cream and light beige
    override val backgroundTop = 0xFFF5F0EB.toInt()
    override val backgroundBottom = 0xFFFAF7F5.toInt()
    override val overlayColor = 0x80F0EBE3.toInt()
    
    // Sidebar colors - warm soft tones
    override val sidebarBackground = 0xFFFAF7F5.toInt()
    override val categoryBackground = 0xFFFFFFFF.toInt()
    override val categoryHover = 0xFFF8E8DC.toInt()
    override val categorySelected = 0xFFE8D5C4.toInt()
    override val categoryBorder = 0xFFE8DED6.toInt()
    override val subcategoryBackground = 0xFFFFFBF7.toInt()
    override val subcategoryHover = 0xFFF5EBE0.toInt()
    override val subcategorySelected = 0xFFEBD9C7.toInt()
    
    // Content area colors
    override val contentBackground = 0xFFFFFBF7.toInt()
    
    // Title bar colors
    override val titleBarBackground = 0xFFFFFFFF.toInt()
    override val closeButtonNormal = 0xFFF5EBE0.toInt()
    override val closeButtonHover = 0xFFFFB8B8.toInt()
    
    // Text colors - warm grays
    override val textPrimary = 0xFF4A4A4A.toInt()
    override val textSecondary = 0xFF8B8B8B.toInt()
    override val textDisabled = 0xFFCCCCCC.toInt()
    
    // Widget colors - soft pastels
    override val widgetBackground = 0xFFF0E6DC.toInt()
    override val widgetHover = 0xFFE8DED6.toInt()
    override val widgetActive = 0xFFB8C9E0.toInt() // Soft blue
    override val widgetBorder = 0xFFE8DED6.toInt()
    override val widgetHighlight = 0xFFD4E4F7.toInt()
    
    // Option card colors
    override val optionCardBackground = 0xFFFFFFFF.toInt()
    override val optionCardBorder = 0xFFEBE0D6.toInt()
    override val optionCardShadow = 0x08000000
    
    // Style properties - smooth and modern
    override val categoryCornerRadius = 8f
    override val widgetCornerRadius = 10f
    override val cardCornerRadius = 12f
    override val useBorders = true
    override val useCardStyle = true
}
