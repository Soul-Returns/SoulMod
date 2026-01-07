package com.soulreturns.config.minigames

import com.soulreturns.config.lib.ui.ModConfigLayout
import com.soulreturns.config.lib.ui.RenderHelper
import com.soulreturns.config.lib.ui.themes.Theme
import com.soulreturns.config.minigames.doom.DoomGame
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
    enum class Minigame { SNAKE, TETRIS, DOOM }

    var activeMinigame: Minigame = Minigame.SNAKE
        private set

    private val snakeGame = SnakeGame()
    private val tetrisGame = TetrisGame()
    private val doomGame = DoomGame()

    fun enterMinigames(now: Long = System.currentTimeMillis()) {
        activeMinigame = Minigame.SNAKE
        snakeGame.reset(now)
        tetrisGame.reset(now)
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

        drawButton("Snake", Minigame.SNAKE)
        drawButton("Tetris", Minigame.TETRIS)
        drawButton("DOOM", Minigame.DOOM)
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

        val padding = contentPadding()
        when (activeMinigame) {
            Minigame.SNAKE -> snakeGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
            Minigame.TETRIS -> tetrisGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
            Minigame.DOOM -> doomGame.render(context, contentX, contentY, contentWidth, contentHeight, theme, padding, textRenderer)
        }
    }

    /** Handle clicks inside the minigame sidebar to switch games. */
    fun handleMouseClick(mouseX: Int, mouseY: Int, guiX: Int, guiY: Int, guiHeight: Int): Boolean {
        val sidebarX = guiX
        val sidebarY = guiY + layout.contentTopOffset
        val buttonHeight = 40
        val buttonSpacing = categorySpacing()
        val firstButtonY = sidebarY + layout.sidebarCategoryTopPadding

        val x = sidebarX + 10
        val w = sidebarWidth() - 20
        val h = buttonHeight

        val snakeY = firstButtonY
        val tetrisY = snakeY + h + buttonSpacing
        val doomY = tetrisY + h + buttonSpacing

        if (mouseX < x || mouseX > x + w) {
            return false
        }

        val now = System.currentTimeMillis()
        when {
            mouseY in snakeY..(snakeY + h) -> {
                activeMinigame = Minigame.SNAKE
                snakeGame.reset(now)
                return true
            }
            mouseY in tetrisY..(tetrisY + h) -> {
                activeMinigame = Minigame.TETRIS
                tetrisGame.reset(now)
                return true
            }
            mouseY in doomY..(doomY + h) -> {
                activeMinigame = Minigame.DOOM
                doomGame.reset(now)
                return true
            }
        }

        return false
    }

    fun handleKeyPressed(keyCode: Int): Boolean {
        return when (activeMinigame) {
            Minigame.SNAKE -> snakeGame.handleKeyPressed(keyCode)
            Minigame.TETRIS -> tetrisGame.handleKeyPressed(keyCode)
            Minigame.DOOM -> doomGame.handleKeyPressed(keyCode)
        }
    }

    fun handleKeyReleased(keyCode: Int): Boolean {
        return when (activeMinigame) {
            Minigame.SNAKE -> snakeGame.handleKeyReleased(keyCode)
            Minigame.TETRIS -> tetrisGame.handleKeyReleased(keyCode)
            Minigame.DOOM -> doomGame.handleKeyReleased(keyCode)
        }
    }
}
