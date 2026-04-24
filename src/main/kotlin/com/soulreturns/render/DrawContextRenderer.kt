// Adapted from Odin (github.com/odtheking/Odin) — BSD 3-Clause License, Copyright (c) odtheking
package com.soulreturns.render

import net.minecraft.client.gui.DrawContext

object DrawContextRenderer {

    fun roundedFill(
        context: DrawContext,
        x0: Int, y0: Int, x1: Int, y1: Int,
        color: Int,
        radius: Float,
        outlineColor: Int = 0,
        outlineWidth: Float = 0f
    ) {
        RoundRectRenderer.submit(
            context, x0, y0, x1, y1,
            color, color, color, color,
            radius, radius, radius, radius,
            outlineColor, outlineWidth
        )
    }

    fun roundedFillGradient(
        context: DrawContext,
        x0: Int, y0: Int, x1: Int, y1: Int,
        topLeftColor: Int, topRightColor: Int,
        bottomRightColor: Int, bottomLeftColor: Int,
        radius: Float,
        outlineColor: Int = 0,
        outlineWidth: Float = 0f
    ) {
        RoundRectRenderer.submit(
            context, x0, y0, x1, y1,
            topLeftColor, topRightColor, bottomRightColor, bottomLeftColor,
            radius, radius, radius, radius,
            outlineColor, outlineWidth
        )
    }

    fun roundedFillCustomRadii(
        context: DrawContext,
        x0: Int, y0: Int, x1: Int, y1: Int,
        color: Int,
        topLeftRadius: Float,
        topRightRadius: Float,
        bottomRightRadius: Float,
        bottomLeftRadius: Float,
        outlineColor: Int = 0,
        outlineWidth: Float = 0f
    ) {
        RoundRectRenderer.submit(
            context, x0, y0, x1, y1,
            color, color, color, color,
            topLeftRadius, topRightRadius, bottomRightRadius, bottomLeftRadius,
            outlineColor, outlineWidth
        )
    }
}
