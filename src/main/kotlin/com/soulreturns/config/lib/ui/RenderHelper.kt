package com.soulreturns.config.lib.ui

import net.minecraft.client.gui.DrawContext
import kotlin.math.*

/**
 * Helper class for modern UI rendering with rounded corners, gradients, and animations
 */
object RenderHelper {
    
    /**
     * Linear interpolation for smooth animations
     */
    fun lerp(start: Float, end: Float, progress: Float): Float {
        return start + (end - start) * progress
    }
    
    /**
     * Easing function for smooth animations (ease-out cubic)
     */
    fun easeOutCubic(t: Float): Float {
        val f = t - 1f
        return f * f * f + 1f
    }
    
    /**
     * Easing function for smooth animations (ease-in-out cubic)
     */
    fun easeInOutCubic(t: Float): Float {
        return if (t < 0.5f) {
            4f * t * t * t
        } else {
            val f = 2f * t - 2f
            1f + f * f * f / 2f
        }
    }
    
    /**
     * Draws a filled rectangle with optional rounded corners (no anti-aliasing for performance)
     */
    fun drawRoundedRect(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Float,
        color: Int
    ) {
        // Simple rectangle - no rounded corners for performance
        // Anti-aliasing was causing severe FPS drops
        context.fill(x, y, x + width, y + height, color)
    }
    
    
    /**
     * Draws a filled rectangle with a vertical gradient (simplified)
     */
    fun drawGradientRect(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        colorTop: Int,
        colorBottom: Int
    ) {
        // Simplified: use fillGradient from DrawContext
        context.fillGradient(x, y, x + width, y + height, colorTop, colorBottom)
    }
    
    /**
     * Draws a shadow/glow effect around a rectangle
     */
    fun drawShadow(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        blur: Int,
        color: Int
    ) {
        for (i in 0 until blur) {
            val alpha = ((color shr 24 and 0xFF) * (1f - i.toFloat() / blur)).toInt()
            val shadowColor = (alpha shl 24) or (color and 0xFFFFFF)
            context.fill(x - i, y - i, x + width + i, y + height + i, shadowColor)
        }
    }
    
    /**
     * Interpolate between two colors
     */
    fun lerpColor(colorStart: Int, colorEnd: Int, progress: Float): Int {
        val alphaStart = (colorStart shr 24 and 0xFF)
        val redStart = (colorStart shr 16 and 0xFF)
        val greenStart = (colorStart shr 8 and 0xFF)
        val blueStart = (colorStart and 0xFF)
        
        val alphaEnd = (colorEnd shr 24 and 0xFF)
        val redEnd = (colorEnd shr 16 and 0xFF)
        val greenEnd = (colorEnd shr 8 and 0xFF)
        val blueEnd = (colorEnd and 0xFF)
        
        val alpha = (alphaStart + (alphaEnd - alphaStart) * progress).toInt()
        val red = (redStart + (redEnd - redStart) * progress).toInt()
        val green = (greenStart + (greenEnd - greenStart) * progress).toInt()
        val blue = (blueStart + (blueEnd - blueStart) * progress).toInt()
        
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }
    
    /**
     * Draws a rectangle with a border and rounded corners
     */
    fun drawRectWithBorder(
        context: DrawContext,
        x: Int,
        y: Int,
        width: Int,
        height: Int,
        radius: Float,
        fillColor: Int,
        borderColor: Int,
        borderWidth: Int = 1
    ) {
        // Draw border (larger rounded rect)
        if (borderWidth > 0) {
            drawRoundedRect(context, x - borderWidth, y - borderWidth, 
                width + borderWidth * 2, height + borderWidth * 2, radius, borderColor)
        }
        
        // Draw fill on top
        drawRoundedRect(context, x, y, width, height, radius, fillColor)
    }
    
    /**
     * Check if mouse is within bounds
     */
    fun isMouseOver(mouseX: Int, mouseY: Int, x: Int, y: Int, width: Int, height: Int): Boolean {
        return mouseX >= x && mouseX <= x + width && mouseY >= y && mouseY <= y + height
    }
}
