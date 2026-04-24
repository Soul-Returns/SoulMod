package com.soulreturns.config.gui

import com.soulreturns.render.DrawContextRenderer
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

    const val RADIUS = 8f
    const val SIDEBAR_COLOR = 0xFF1F1B1A.toInt()

    val backgroundSurface: Surface = Surface.flat(BACKGROUND)
    val panelInsetSurface: Surface = Surface.flat(PANEL_INSET)

    // Outer card — full rounded corners.
    val panelSurface: Surface = Surface { context, component ->
        DrawContextRenderer.roundedFill(
            context,
            component.x(), component.y(),
            component.x() + component.width(), component.y() + component.height(),
            PANEL, RADIUS
        )
    }

    // Left panel (sidebar) — left corners rounded, right corners sharp.
    val sidebarSurface: Surface = Surface { context, component ->
        DrawContextRenderer.roundedFillCustomRadii(
            context,
            component.x(), component.y(),
            component.x() + component.width(), component.y() + component.height(),
            SIDEBAR_COLOR,
            topLeftRadius = RADIUS, topRightRadius = 0f,
            bottomRightRadius = 0f, bottomLeftRadius = RADIUS
        )
    }

    // Right content panel — right corners rounded, left corners sharp.
    val contentSurface: Surface = Surface { context, component ->
        DrawContextRenderer.roundedFillCustomRadii(
            context,
            component.x(), component.y(),
            component.x() + component.width(), component.y() + component.height(),
            PANEL,
            topLeftRadius = 0f, topRightRadius = RADIUS,
            bottomRightRadius = RADIUS, bottomLeftRadius = 0f
        )
    }

    fun selectedSurface(): Surface = Surface.flat(PANEL_HOVER).and(Surface.outline(ACCENT_DIM))
    fun rowSurface(): Surface      = Surface.flat(PANEL)

    fun color(rgb: Int): Color = Color.ofArgb(rgb)
}
