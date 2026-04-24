package com.soulreturns.config.gui

import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Surface

object Theme {
    // Warm dark palette inspired by Modern UI's default theme.
    const val BACKGROUND  = 0xF01A1618.toInt()
    const val PANEL       = 0xFF24201F.toInt()
    const val PANEL_HOVER = 0xFF302A29.toInt()
    const val PANEL_INSET = 0xFF1B1716.toInt()
    const val ACCENT      = 0xFFE8C9C1.toInt()
    const val ACCENT_DIM  = 0xFF7A5F5A.toInt()
    const val SEPARATOR   = 0xFF3A3231.toInt()
    const val TEXT        = 0xFFEDE6E2.toInt()
    const val TEXT_DIM    = 0xFFA09793.toInt()
    const val TEXT_FAINT  = 0xFF6E6663.toInt()

    val backgroundSurface: Surface = Surface.flat(BACKGROUND)
    val panelSurface: Surface      = Surface.flat(PANEL)
    val panelInsetSurface: Surface = Surface.flat(PANEL_INSET)
    val sidebarSurface: Surface    = Surface.flat(0xFF1F1B1A.toInt())

    fun selectedSurface(): Surface = Surface.flat(PANEL_HOVER).and(Surface.outline(ACCENT_DIM))
    fun rowSurface(): Surface      = Surface.flat(PANEL)

    fun color(rgb: Int): Color = Color.ofArgb(rgb)
}
