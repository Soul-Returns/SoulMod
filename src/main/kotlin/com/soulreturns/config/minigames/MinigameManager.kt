package com.soulreturns.config.minigames

import com.soulreturns.config.config
import com.soulreturns.config.lib.ui.ModConfigLayout
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import com.soulreturns.config.minigames.doom.DoomGame
import com.soulreturns.config.minigames.mandelbrot.MandelbrotGame
import com.soulreturns.config.minigames.snake.SnakeGame
import com.soulreturns.config.minigames.tetris.TetrisGame
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.font.TextRenderer

/**
 * Coordinates the different minigames shown in the config GUI's Minigames tab.
 *
 * This keeps ModConfigScreen free from per-minigame logic and rendering code.
 */
class MinigameManager(
    private val layout: ModConfigLayout,
    /** Live view of sidebar width from the host screen. */
    private val sidebarWidth: () -> Int,
    /** Live view of content padding from the host screen. */
    private val contentPadding: () -> Int,
    /** Live view of category spacing from the host screen. */
    private val categorySpacing: () -> Int,
) {
    enum class Minigame { SNAKE, TETRIS, MANDELBROT, DOOM }

    var activeMinigame: Minigame = Minigame.SNAKE
        private set

    private val snakeGame = SnakeGame()
    private val tetrisGame = TetrisGame()
    private val mandelbrotGame = MandelbrotGame()
    private val doomGame = DoomGame()

    private fun isEnabled(minigame: Minigame): Boolean {
        return try {
            val cfg = config.minigamesCategory
            when (minigame) {
                Minigame.SNAKE -> cfg.enableSnake
                Minigame.TETRIS -> cfg.enableTetris
                Minigame.MANDELBROT -> cfg.enableMandelbrot
                Minigame.DOOM -> cfg.enableDoom
            }
        } catch (_: Throwable) {
            // If config is not yet available, treat all as enabled so the UI
            // still works rather than crashing.
            true
        }
    }

    private fun enabledMinigames(): List<Pair<String, Minigame>> {
        val result = mutableListOf<Pair<String, Minigame>>()
        if (isEnabled(Minigame.SNAKE)) result += "Snake" to Minigame.SNAKE
        if (isEnabled(Minigame.TETRIS)) result += "Tetris" to Minigame.TETRIS
        if (isEnabled(Minigame.MANDELBROT)) result += "Mandelbrot" to Minigame.MANDELBROT
        if (isEnabled(Minigame.DOOM)) result += "DOOM" to Minigame.DOOM
        // Fallback: if for some reason everything is disabled according to
        // isEnabled, keep the list empty; the title bar button will already be
        // hidden by ModConfigScreen in that case.
        return result
    }

    fun enterMinigames(now: Long = System.currentTimeMillis()) {
        val enabled = enabledMinigames()
        val first = enabled.firstOrNull()?.second ?: Minigame.SNAKE
        activeMinigame = first
        // Reset all games so whichever becomes active starts fresh.
        snakeGame.reset(now)
        tetrisGame.reset(now)
        mandelbrotGame.reset()
        doomGame.reset(now)
    }

    fun renderSidebar(
        context: DrawContext,
        guiX: Int,
        guiY: Int,
        guiHeight: Int,
        theme: Theme,
        mouseX: Int,
        mouseY: Int,
        textRenderer: TextRenderer,
    ) {
        val sidebarX = guiX
        val sidebarY = guiY + layout.contentTopOffset
        val sidebarH = guiHeight - layout.contentTopOffset - layout.bottomMargin

        // Sidebar background
        context.fill(sidebarX, sidebarY, sidebarX + sidebarWidth(), sidebarY + sidebarH, theme.sidebarBackground)

        val buttonHeight = 40
        val buttonSpacing = categorySpacing()
        var currentY = sidebarY + layout.sidebarCategoryTopPadding

        val enabled = enabledMinigames()

        fun drawButton(label: String, minigame: Minigame) {
            val x = sidebarX + 10
            val y = currentY
            val w = sidebarWidth() - 20
            val h = buttonHeight

            val isHovered = mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h
            val isSelected = activeMinigame == minigame
            val bgColor = when {
                isSelected -> theme.categorySelected
                isHovered -> theme.categoryHover
                else -> theme.categoryBackground
            }

            if (theme.useBorders && bgColor != theme.sidebarBackground) {
                RenderHelper.drawRect(context, x - 1, y - 1, w + 2, h + 2, theme.categoryBorder)
            }
            RenderHelper.drawRect(context, x, y, w, h, bgColor)
            context.drawText(textRenderer, label, x + 10, y + 13, theme.textPrimary, false)

            currentY += h + buttonSpacing
        }

        for ((label, game) in enabled) {
            drawButton(label, game)
        }
    }

    fun renderContent(
        context: DrawContext,
        guiX: Int,
        guiY: Int,
        guiWidth: Int,
        guiHeight: Int,
        theme: Theme,
        textRenderer: TextRenderer,
    ) {
        val contentX = guiX + sidebarWidth() + layout.outerMargin
        val contentY = guiY + layout.contentTopOffset
        val contentWidth = guiWidth - sidebarWidth() - layout.outerMargin * 2
        val contentHeight = guiHeight - layout.contentTopOffset - layout.bottomMargin

        // Background for minigames area
        RenderHelper.drawRect(context, contentX, contentY, contentWidth, contentHeight, theme.contentBackground)

        val enabled = enabledMinigames()
        val active = if (enabled.any { it.second == activeMinigame }) {
            activeMinigame
        } else {
            enabled.firstOrNull()?.second
        }

        if (active == null) {
            // No minigames enabled; nothing to render.
            return
        }
        if (active != activeMinigame) {
            activeMinigame = active
        }

        val padding = contentPadding()
        when (active) {
            Minigame.SNAKE -> snakeGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
            Minigame.TETRIS -> tetrisGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
            Minigame.MANDELBROT -> mandelbrotGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
            Minigame.DOOM -> doomGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
        }
    }

    /** Handle clicks inside the minigame sidebar to switch games. */
    fun handleMouseClick(mouseX: Int, mouseY: Int, guiX: Int, guiY: Int, guiHeight: Int): Boolean {
        val sidebarX = guiX
        val sidebarY = guiY + layout.contentTopOffset
        val buttonHeight = 40
        val buttonSpacing = categorySpacing()

        val x = sidebarX + 10
        val w = sidebarWidth() - 20
        val h = buttonHeight

        if (mouseX < x || mouseX > x + w) {
            return false
        }

        val enabled = enabledMinigames()
        var currentY = sidebarY + layout.sidebarCategoryTopPadding
        val now = System.currentTimeMillis()

        for ((_, game) in enabled) {
            val buttonTop = currentY
            val buttonBottom = currentY + h
            if (mouseY in buttonTop..buttonBottom) {
                activeMinigame = game
                when (game) {
                    Minigame.SNAKE -> snakeGame.reset(now)
                    Minigame.TETRIS -> tetrisGame.reset(now)
                    Minigame.MANDELBROT -> mandelbrotGame.reset()
                    Minigame.DOOM -> doomGame.reset(now)
                }
                return true
            }
            currentY += h + buttonSpacing
        }

        return false
    }

    /**
     * Mouse drag events from the host screen. Currently only Mandelbrot uses
     * this for panning.
     */
    fun handleMouseDragged(mouseX: Int, mouseY: Int, deltaX: Double, deltaY: Double): Boolean {
        if (!isEnabled(activeMinigame)) return false
        return when (activeMinigame) {
            Minigame.MANDELBROT -> mandelbrotGame.handleMouseDragged(mouseX, mouseY, deltaX, deltaY)
            else -> false
        }
    }

    /** Mouse wheel scroll for zooming Mandelbrot. */
    fun handleMouseScrolled(mouseX: Int, mouseY: Int, scrollAmount: Double): Boolean {
        if (!isEnabled(activeMinigame)) return false
        return when (activeMinigame) {
            Minigame.MANDELBROT -> mandelbrotGame.handleScroll(mouseX, mouseY, scrollAmount)
            else -> false
        }
    }

    fun handleKeyPressed(keyCode: Int): Boolean {
        if (!isEnabled(activeMinigame)) return false
        return when (activeMinigame) {
            Minigame.SNAKE -> snakeGame.handleKeyPressed(keyCode)
            Minigame.TETRIS -> tetrisGame.handleKeyPressed(keyCode)
            Minigame.MANDELBROT -> mandelbrotGame.handleKeyPressed(keyCode)
            Minigame.DOOM -> doomGame.handleKeyPressed(keyCode)
        }
    }

    fun handleKeyReleased(keyCode: Int): Boolean {
        if (!isEnabled(activeMinigame)) return false
        return when (activeMinigame) {
            Minigame.SNAKE -> snakeGame.handleKeyReleased(keyCode)
            Minigame.TETRIS -> tetrisGame.handleKeyReleased(keyCode)
            Minigame.MANDELBROT -> mandelbrotGame.handleKeyReleased(keyCode)
            Minigame.DOOM -> doomGame.handleKeyReleased(keyCode)
        }
    }
}
