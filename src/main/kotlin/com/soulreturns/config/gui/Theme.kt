package com.soulreturns.config.gui

import com.soulreturns.render.DrawContextRenderer
import io.wispforest.owo.ui.core.Color
import io.wispforest.owo.ui.core.Surface

object Theme {
    // Dark monochromatic palette with blue accent — OneConfig-inspired.
    const val BACKGROUND  = 0xFF0D0D0D.toInt()
    const val PANEL       = 0xFF1A1A1A.toInt()
    const val PANEL_HOVER = 0xFF242424.toInt()
    const val PANEL_INSET = 0xFF1E1E1E.toInt()
    const val ACCENT      = 0xFF3B82F6.toInt()
    const val ACCENT_DIM  = 0xFF2563EB.toInt()
    const val SEPARATOR   = 0xFF2A2A2A.toInt()
    const val TEXT        = 0xFFEEEEEE.toInt()
    const val TEXT_DIM    = 0xFF888888.toInt()
    const val TEXT_FAINT  = 0xFF555555.toInt()

    const val RADIUS = 8f
    const val ITEM_RADIUS = 6f
    const val SIDEBAR_COLOR = 0xFF141414.toInt()

    // Blank — lets Minecraft's in-game background dimming show through; card panels are opaque.
    val backgroundSurface: Surface = Surface.BLANK

    val panelInsetSurface: Surface = Surface { context, component ->
        DrawContextRenderer.roundedFill(
            context,
            component.x(), component.y(),
            component.x() + component.width(), component.y() + component.height(),
            PANEL_INSET, ITEM_RADIUS
        )
    }

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

    fun rowSurface(): Surface = Surface.BLANK

    fun color(rgb: Int): Color = Color.ofArgb(rgb)
}
