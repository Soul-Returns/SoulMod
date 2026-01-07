package com.soulreturns.config.minigames.mandelbrot

import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import net.minecraft.client.font.TextRenderer
import net.minecraft.client.gui.DrawContext
import org.lwjgl.glfw.GLFW
import kotlin.math.ln

/**
 * Simple Mandelbrot set viewer used by the config GUI's Minigames tab.
 *
 * This is intentionally lightweight: we render a low-resolution grid and
 * only recompute the fractal when the camera changes, so normal GUI
 * performance is preserved.
 */
class MandelbrotGame(
    // Slightly reduced resolution for better performance while keeping a
    // recognizable shape. The image is scaled up when rendered.
    private val gridWidth: Int = 128,
    private val gridHeight: Int = 96,
    private val baseMaxIterations: Int = 80,
) {
    // Center of the view in complex plane coordinates (Re, Im).
    private var centerX = -0.5
    private var centerY = 0.0

    /** Horizontal span of the view in complex plane units. */
    private var spanX = 3.5

    /** Vertical span is derived from aspect ratio to avoid stretching. */
    private val spanY: Double
        get() = spanX * gridHeight.toDouble() / gridWidth.toDouble()

    private var maxIterations: Int = baseMaxIterations

    // Cached ARGB colors for each sample cell in the grid.
    private var pixels: IntArray = IntArray(gridWidth * gridHeight)

    // Last viewer rect (in screen coordinates) used for rendering. Mouse
    // interaction (drag/scroll) is limited to this area.
    private var lastViewerX: Int = 0
    private var lastViewerY: Int = 0
    private var lastViewerWidth: Int = 0
    private var lastViewerHeight: Int = 0

    // Marked dirty whenever camera/zoom or iteration budget changes.
    private var dirty: Boolean = true

    fun reset() {
        centerX = -0.5
        centerY = 0.0
        spanX = 3.5
        maxIterations = baseMaxIterations
        dirty = true
    }

    private fun markDirty() {
        dirty = true
    }

    private fun zoom(factor: Double) {
        // Zoom towards or away from the current center.
        spanX = (spanX * factor).coerceIn(1e-15, 4.0)

        // As we zoom in, increase iterations a bit for more detail, but keep
        // an upper bound so performance stays reasonable.
        val zoomLevel = 3.5 / spanX
        val targetIterations = (baseMaxIterations * zoomLevel)
            .toInt()
            .coerceIn(baseMaxIterations, baseMaxIterations * 4)
        maxIterations = targetIterations
        markDirty()
    }

    private fun pan(deltaX: Double, deltaY: Double) {
        // Pan proportional to current span so movement feels consistent.
        val panScale = 0.15
        centerX += deltaX * spanX * panScale
        centerY += deltaY * spanY * panScale
        markDirty()
    }

    private fun recomputePixelsIfNeeded() {
        if (!dirty) return
        dirty = false

        val w = gridWidth
        val h = gridHeight
        val maxIter = maxIterations

        val minX = centerX - spanX / 2.0
        val minY = centerY - spanY / 2.0

        for (yy in 0 until h) {
            val cy = minY + spanY * (yy.toDouble() / (h - 1).coerceAtLeast(1))
            for (xx in 0 until w) {
                val cx = minX + spanX * (xx.toDouble() / (w - 1).coerceAtLeast(1))

                var x = 0.0
                var y = 0.0
                var iter = 0

                while (x * x + y * y <= 4.0 && iter < maxIter) {
                    val xNew = x * x - y * y + cx
                    y = 2.0 * x * y + cy
                    x = xNew
                    iter++
                }

                val color = iterationToColor(iter, maxIter)
                pixels[yy * w + xx] = color
            }
        }
    }

    /**
     * Simple smooth coloring based on iteration count.
     * Inside-set pixels (maxIter) are rendered nearly black.
     */
    private fun iterationToColor(iter: Int, maxIter: Int): Int {
        if (iter >= maxIter) {
            // Deep interior: almost black.
            return 0xFF000000.toInt()
        }

        // Normalized iteration count in [0, 1].
        val t = (iter.toDouble() / maxIter.toDouble()).coerceIn(0.0, 1.0)

        // Polynomial palette (classic smooth rainbow approximation).
        val r = (9.0 * (1 - t) * t * t * t * 255.0).toInt().coerceIn(0, 255)
        val g = (15.0 * (1 - t) * (1 - t) * t * t * 255.0).toInt().coerceIn(0, 255)
        val b = (8.5 * (1 - t) * (1 - t) * (1 - t) * t * 255.0).toInt().coerceIn(0, 255)

        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    fun render(
        context: DrawContext,
        contentX: Int,
        contentY: Int,
        contentWidth: Int,
        contentHeight: Int,
        theme: Theme,
        contentPadding: Int,
        textRenderer: TextRenderer,
    ) {
        recomputePixelsIfNeeded()

        val infoLines = 4
        val lineHeight = textRenderer.fontHeight + 4
        val infoHeight = infoLines * lineHeight + 6

        val viewerX = contentX + contentPadding
        val viewerY = contentY + contentPadding + infoHeight
        val viewerWidth = contentWidth - contentPadding * 2
        val viewerHeight = contentHeight - contentPadding * 2 - infoHeight

        if (viewerWidth <= 0 || viewerHeight <= 0) return

        // Remember the viewer area for mouse interaction.
        lastViewerX = viewerX
        lastViewerY = viewerY
        lastViewerWidth = viewerWidth
        lastViewerHeight = viewerHeight

        // Background card for the viewer.
        if (theme.useCardStyle) {
            RenderHelper.drawRect(
                context,
                viewerX - 4,
                viewerY - 4,
                viewerWidth + 8,
                viewerHeight + 8,
                theme.optionCardBackground,
            )
        }

        val cellSize = (minOf(viewerWidth / gridWidth, viewerHeight / gridHeight)).coerceAtLeast(1)
        if (cellSize <= 0) return

        val drawWidth = gridWidth * cellSize
        val drawHeight = gridHeight * cellSize
        val offsetX = viewerX + (viewerWidth - drawWidth) / 2
        val offsetY = viewerY + (viewerHeight - drawHeight) / 2

        // Draw the cached pixels as small rectangles.
        var idx = 0
        for (yy in 0 until gridHeight) {
            val rowY = offsetY + yy * cellSize
            for (xx in 0 until gridWidth) {
                val colX = offsetX + xx * cellSize
                val color = pixels[idx++]
                RenderHelper.drawRect(context, colX, rowY, cellSize, cellSize, color)
            }
        }

        // HUD text with simple controls and zoom info.
        val hudX = contentX + contentPadding
        val hudY = contentY + contentPadding

        val zoomLevel = 3.5 / spanX
        val infoText1 = "Mandelbrot Viewer"
        val infoText2 = "Zoom: %.2fx".format(zoomLevel)
        val infoText3 = "Center: (%.3f, %.3f)".format(centerX, centerY)
        val infoText4 = "Scroll/drag to explore, R reset"

        context.drawText(textRenderer, infoText1, hudX, hudY, theme.textPrimary, false)
        context.drawText(textRenderer, infoText2, hudX, hudY + lineHeight, theme.textSecondary, false)
        context.drawText(textRenderer, infoText3, hudX, hudY + lineHeight * 2, theme.textSecondary, false)
        context.drawText(textRenderer, infoText4, hudX, hudY + lineHeight * 3, theme.textSecondary, false)
    }

    fun handleKeyPressed(keyCode: Int): Boolean {
        when (keyCode) {
            GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> pan(deltaX = -1.0, deltaY = 0.0)
            GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> pan(deltaX = 1.0, deltaY = 0.0)
            GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> pan(deltaX = 0.0, deltaY = -1.0)
            GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> pan(deltaX = 0.0, deltaY = 1.0)

            GLFW.GLFW_KEY_EQUAL, GLFW.GLFW_KEY_KP_ADD -> zoom(0.7) // zoom in
            GLFW.GLFW_KEY_MINUS, GLFW.GLFW_KEY_KP_SUBTRACT -> zoom(1.3) // zoom out

            GLFW.GLFW_KEY_R -> reset()
            else -> return false
        }
        return true
    }

    fun handleKeyReleased(@Suppress("UNUSED_PARAMETER") keyCode: Int): Boolean {
        // No key-up behaviour needed for the viewer.
        return false
    }

    // ---------- Mouse interaction ----------

    /**
     * Handle a mouse drag in screen coordinates. Any drag over the viewer
     * pans the camera.
     */
    fun handleMouseDragged(mouseX: Int, mouseY: Int, deltaX: Double, deltaY: Double): Boolean {
        if (!isInsideViewer(mouseX, mouseY)) return false
        if (lastViewerWidth <= 0 || lastViewerHeight <= 0) return false

        // Drag direction is inverted so dragging right moves the view right.
        val normDX = (-deltaX / lastViewerWidth).coerceIn(-1.0, 1.0)
        val normDY = (-deltaY / lastViewerHeight).coerceIn(-1.0, 1.0)
        if (normDX == 0.0 && normDY == 0.0) return false

        pan(normDX, normDY)
        return true
    }

    /**
     * Handle mouse wheel scrolling: zoom towards/away from the cursor
     * position inside the viewer.
     */
    fun handleScroll(mouseX: Int, mouseY: Int, scrollAmount: Double): Boolean {
        if (!isInsideViewer(mouseX, mouseY)) return false
        if (scrollAmount == 0.0) return false

        val factor = if (scrollAmount > 0.0) 0.8 else 1.25
        zoomAroundCursor(mouseX, mouseY, factor)
        return true
    }

    private fun isInsideViewer(mouseX: Int, mouseY: Int): Boolean {
        return mouseX >= lastViewerX && mouseX < lastViewerX + lastViewerWidth &&
            mouseY >= lastViewerY && mouseY < lastViewerY + lastViewerHeight
    }

    private fun zoomAroundCursor(mouseX: Int, mouseY: Int, factor: Double) {
        if (lastViewerWidth <= 0 || lastViewerHeight <= 0) {
            zoom(factor)
            return
        }

        // Normalized coordinates of the cursor within the viewer [0,1].
        val nx = ((mouseX - lastViewerX).toDouble() / lastViewerWidth.toDouble()).coerceIn(0.0, 1.0)
        val ny = ((mouseY - lastViewerY).toDouble() / lastViewerHeight.toDouble()).coerceIn(0.0, 1.0)

        val currentSpanX = spanX
        val currentSpanY = spanY

        val focusX = centerX + (nx - 0.5) * currentSpanX
        val focusY = centerY + (ny - 0.5) * currentSpanY

        // Apply zoom with clamped span and updated iteration budget.
        val newSpanX = (currentSpanX * factor).coerceIn(1e-15, 4.0)
        val zoomLevel = 3.5 / newSpanX
        val targetIterations = (baseMaxIterations * zoomLevel)
            .toInt()
            .coerceIn(baseMaxIterations, baseMaxIterations * 4)

        val newSpanY = newSpanX * gridHeight.toDouble() / gridWidth.toDouble()

        centerX = focusX - (nx - 0.5) * newSpanX
        centerY = focusY - (ny - 0.5) * newSpanY
        spanX = newSpanX
        maxIterations = targetIterations
        markDirty()
    }
}
