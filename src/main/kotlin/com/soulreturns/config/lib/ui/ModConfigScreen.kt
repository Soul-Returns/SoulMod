package com.soulreturns.config.lib.ui

import com.soulreturns.config.lib.manager.SoulConfigManager
import com.soulreturns.config.lib.model.CategoryData
import com.soulreturns.config.lib.model.SubcategoryData
import com.soulreturns.config.lib.ui.minigames.SnakeGame
import com.soulreturns.config.lib.ui.minigames.SnakeGame.Direction
import com.soulreturns.config.lib.ui.minigames.TetrisGame
import com.soulreturns.config.lib.ui.widgets.ConfigWidget
import com.soulreturns.config.lib.ui.widgets.WidgetFactory
import com.soulreturns.util.DebugLogger
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW
import com.soulreturns.config.config
import com.soulreturns.config.lib.ui.themes.ThemeManager

/**
 * Modern configuration screen (80% size, centered, with blur)
 */

data class ModConfigLayout(
    val guiWidthFraction: Double = 0.8,
    val guiHeightFraction: Double = 0.8,
    val sidebarWidth: Int = 240,
    val contentPadding: Int = 24,
    val categorySpacing: Int = 6,
    val widgetSpacing: Int = 6,
    val outerMargin: Int = 10,
    val titleBarHeight: Int = 50,
    /**
     * Vertical offset from the top of the GUI to the content/sidebar area
     * (i.e., the area below the title bar).
     */
    val contentTopOffset: Int = 70,
    val bottomMargin: Int = 10,
    /**
     * Extra padding between the top of the sidebar content area and the first category button.
     */
    val sidebarCategoryTopPadding: Int = 10,
    /**
     * Vertical padding between the top of the content area and the first option widget.
     * Chosen so that the gap above the first card roughly matches the gap between cards.
     */
    val contentListTopPadding: Int = 10,
)

class ModConfigScreen<T : Any>(
    private val configManager: SoulConfigManager<T>,
    private val screenTitle: String,
    private val version: String,
    /**
     * Layout configuration for margins, paddings and sizing. Implementations can override this
     * to customise the overall positioning without touching rendering logic.
     */
    private val layout: ModConfigLayout = ModConfigLayout()
) : Screen(Text.literal(screenTitle)) {

    private enum class ViewMode { CONFIG, MINIGAMES }
    private enum class Minigame { SNAKE, TETRIS }

    private var viewMode: ViewMode = ViewMode.CONFIG
    private var activeMinigame: Minigame = Minigame.SNAKE
    private val snakeGame = SnakeGame()
    private val tetrisGame = TetrisGame()
    
    private val sidebarWidth get() = layout.sidebarWidth
    private val contentPadding get() = layout.contentPadding
    private val categorySpacing get() = layout.categorySpacing
    private val widgetSpacing get() = layout.widgetSpacing
    
    // GUI dimensions (80% of screen, centered)
    private var guiX = 0
    private var guiY = 0
    private var guiWidth = 0
    private var guiHeight = 0
    
    private var selectedCategoryIndex = 0
    private var selectedSubcategoryIndex = -1 // -1 means no subcategory selected
    
    private var sidebarScroll = 0.0
    private var contentScroll = 0.0
    private var targetSidebarScroll = 0.0
    private var targetContentScroll = 0.0
    private val scrollSpeed = 20.0
    private val scrollSmoothing = 0.3f // Smooth scroll lerp factor
    
    // Animation state
    private var hoverAnimations = mutableMapOf<String, Float>()
    private var selectionAnimProgress = 0f
    
    private val widgets = mutableListOf<ConfigWidget>()
    
    // Get current theme
    private val theme get() = ThemeManager.getCurrentTheme()
    
    init {
        rebuildWidgets()
    }
    
    override fun init() {
        super.init()
        updateDimensions()
    }
    
    override fun resize(client: net.minecraft.client.MinecraftClient, width: Int, height: Int) {
        super.resize(client, width, height)
        updateDimensions()
    }
    
    private fun updateDimensions() {
        // Calculate size based on layout fractions
        guiWidth = (width * layout.guiWidthFraction).toInt()
        guiHeight = (height * layout.guiHeightFraction).toInt()
        
        // Center the GUI
        guiX = (width - guiWidth) / 2
        guiY = (height - guiHeight) / 2
    }
    
    private fun rebuildWidgets() {
        widgets.clear()
        
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex) ?: return
        val categoryInstance = getCategoryInstance(category)
        
        // Start a bit below the top of the content area so the first card
        // has a similar gap to the gaps between cards.
        var currentY = layout.contentListTopPadding
        // Align widget X with the content area (contentX + contentPadding)
        val widgetBaseX = sidebarWidth + layout.outerMargin + contentPadding
        
        // If a subcategory is selected, show its options
        if (selectedSubcategoryIndex >= 0) {
            val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex) ?: return
            val subcategoryInstance = getSubcategoryInstance(categoryInstance, subcategory)
            
            for (option in subcategory.options) {
                val widget = WidgetFactory.createWidget(
                    option,
                    widgetBaseX,
                    currentY
                )
                widgets.add(widget)
                currentY += widget.height + widgetSpacing
            }
        } else {
            // Show category-level options
            for (option in category.options) {
                val widget = WidgetFactory.createWidget(
                    option,
                    widgetBaseX,
                    currentY
                )
                widgets.add(widget)
                currentY += widget.height + widgetSpacing
            }
        }
    }
    
    override fun render(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Smooth scroll interpolation
        sidebarScroll = RenderHelper.lerp(sidebarScroll.toFloat(), targetSidebarScroll.toFloat(), scrollSmoothing).toDouble()
        contentScroll = RenderHelper.lerp(contentScroll.toFloat(), targetContentScroll.toFloat(), scrollSmoothing).toDouble()
        
        // Update selection animation
        selectionAnimProgress = (selectionAnimProgress + delta * 8f).coerceAtMost(1f)
        
        // Apply blur effect if supported
        if (client != null && client!!.world != null) {
            context.fillGradient(0, 0, width, height, theme.overlayColor, theme.overlayColor)
        } else {
            renderInGameBackground(context)
        }
        
        // Background for GUI area
        RenderHelper.drawGradientRect(context, guiX, guiY, guiWidth, guiHeight, theme.backgroundTop, theme.backgroundBottom)
        
        if (viewMode == ViewMode.CONFIG) {
            // Render sidebar and content area for config
            renderSidebar(context, mouseX, mouseY, delta)
            renderContent(context, mouseX, mouseY, delta)
        } else {
            // Render minigames sidebar and content instead of config widgets
            renderMinigameSidebar(context, mouseX, mouseY)
            renderMinigames(context, mouseX, mouseY, delta)
        }
        
        // Render title bar (including Minigames button)
        renderTitleBar(context)
        
        // Render tooltips last (on top of everything) in config mode only
        if (viewMode == ViewMode.CONFIG) {
            renderTooltips(context, mouseX, mouseY)
        }
        
        // super.render(context, mouseX, mouseY, delta)
    }
    
    private fun renderTooltips(context: DrawContext, mouseX: Int, mouseY: Int) {
        // Find hovered widget and show its description as tooltip
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex) ?: return
        
        val contentX = guiX + sidebarWidth + layout.outerMargin
        val contentY = guiY + layout.contentTopOffset
        val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
        val widgetDisplayX = contentX + contentPadding
        val widgetDisplayWidth = contentWidth - contentPadding * 2
        
        for (widget in widgets) {
            val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider
            val displayX = if (isDivider) widget.x + guiX else widgetDisplayX
            val displayY = widget.y - contentScroll.toInt() + contentY
            val hitWidth = if (isDivider) widget.width else widgetDisplayWidth
            val hitHeight = widget.height
            
            // Check if widget is hovered (entire card area for non-dividers)
            if (mouseX >= displayX && mouseX <= displayX + hitWidth &&
                mouseY >= displayY && mouseY <= displayY + hitHeight &&
                widget.option.description.isNotEmpty()) {
                
                // Calculate tooltip position near cursor
                val tooltipX = mouseX + 12
                val tooltipY = mouseY + 12
                
                // Measure tooltip size
                val lines = widget.option.description.split("\n")
                val maxWidth = lines.maxOfOrNull { textRenderer.getWidth(it) } ?: 0
                val tooltipWidth = maxWidth + 16
                val tooltipHeight = lines.size * (textRenderer.fontHeight + 2) + 12
                
                // Adjust if tooltip goes off screen
                val finalX = if (tooltipX + tooltipWidth > width) tooltipX - tooltipWidth - 24 else tooltipX
                val finalY = if (tooltipY + tooltipHeight > height) tooltipY - tooltipHeight - 24 else tooltipY
                
                // Draw tooltip background
                RenderHelper.drawRect(context, finalX, finalY, tooltipWidth, tooltipHeight, 0xE0202020.toInt())
                RenderHelper.drawRect(context, finalX - 1, finalY - 1, tooltipWidth + 2, tooltipHeight + 2, theme.categoryBorder)
                
                // Draw tooltip text
                var textY = finalY + 6
                for (line in lines) {
                    context.drawText(textRenderer, line, finalX + 8, textY, theme.textSecondary, false)
                    textY += textRenderer.fontHeight + 2
                }
                
                break // Only show one tooltip
            }
        }
    }
    
    private fun renderTitleBar(context: DrawContext) {
        // Title bar background
        val titleBarX = guiX + layout.outerMargin
        val titleBarY = guiY + layout.outerMargin
        val titleBarWidth = guiWidth - layout.outerMargin * 2
        val titleBarHeight = layout.titleBarHeight
        RenderHelper.drawRect(context, titleBarX, titleBarY, titleBarWidth, titleBarHeight, theme.titleBarBackground)
        
        // Title text
        val titleText = "$screenTitle v$version"
        val titleX = titleBarX + 20
        val titleY = titleBarY + 15
        context.drawText(textRenderer, titleText, titleX, titleY, theme.textPrimary, false)
        
        // Close and Minigames buttons
        val closeButtonSize = 30
        val closeButtonX = titleBarX + titleBarWidth - closeButtonSize - layout.outerMargin
        val closeButtonY = titleBarY + 10

        val minigamesButtonSize = closeButtonSize
        val minigamesButtonX = closeButtonX - minigamesButtonSize - 6
        val minigamesButtonY = closeButtonY

        val mouseX = client?.mouse?.x?.toInt() ?: 0
        val mouseY = client?.mouse?.y?.toInt() ?: 0

        val isCloseHovered = RenderHelper.isMouseOver(
            mouseX,
            mouseY,
            closeButtonX, closeButtonY, closeButtonSize, closeButtonSize
        )
        val isMinigamesHovered = RenderHelper.isMouseOver(
            mouseX,
            mouseY,
            minigamesButtonX, minigamesButtonY, minigamesButtonSize, minigamesButtonSize
        )
        
        val closeButtonColor = if (isCloseHovered) theme.closeButtonHover else theme.closeButtonNormal
        RenderHelper.drawRect(context, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, closeButtonColor)

        // Minigames button uses widget colors and highlights when active
        val baseGamesColor = when {
            viewMode == ViewMode.MINIGAMES -> theme.widgetActive
            isMinigamesHovered -> theme.widgetHover
            else -> theme.widgetBackground
        }
        RenderHelper.drawRect(context, minigamesButtonX, minigamesButtonY, minigamesButtonSize, minigamesButtonSize, baseGamesColor)
        
        // X icon
        val xSize = 5
        val xX = closeButtonX + (closeButtonSize - xSize) / 2
        val xY = closeButtonY + (closeButtonSize - xSize) / 2
        context.drawText(textRenderer, "✕", xX, xY, theme.textPrimary, false)

        // Minigames icon (simple controller glyph)
        val gSize = 5
        val gX = minigamesButtonX + (minigamesButtonSize - gSize) / 2
        val gY = minigamesButtonY + (minigamesButtonSize - gSize) / 2
        context.drawText(textRenderer, "🎮", gX, gY, theme.textPrimary, false)
    }
    
    private fun renderSidebar(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Sidebar background (aligned with content area)
        val sidebarX = guiX
        val sidebarY = guiY + layout.contentTopOffset
        val sidebarH = guiHeight - layout.contentTopOffset - layout.bottomMargin
        context.fill(sidebarX, sidebarY, sidebarX + sidebarWidth, sidebarY + sidebarH, theme.sidebarBackground)
        
        // Enable scissor for sidebar
        context.enableScissor(sidebarX, sidebarY, sidebarX + sidebarWidth, sidebarY + sidebarH)
        
        // Categories
        var currentY = sidebarY + layout.sidebarCategoryTopPadding - sidebarScroll.toInt()
        
        for ((index, category) in configManager.structure.categories.withIndex()) {
            val categoryHeight = 40
            val categoryY = currentY
            
            // Skip if off-screen
            if (categoryY + categoryHeight < sidebarY || categoryY > sidebarY + sidebarH) {
                currentY += categoryHeight + categorySpacing
                if (index == selectedCategoryIndex && category.subcategories.isNotEmpty()) {
                    currentY += (category.subcategories.size * 35)
                }
                continue
            }
            
            // Check if hovered
            val isHovered = mouseX >= sidebarX + 10 && mouseX <= sidebarX + sidebarWidth - 10 &&
                            mouseY >= categoryY && mouseY <= categoryY + categoryHeight
            val isSelected = index == selectedCategoryIndex
            
            // Category button background
            val bgColor = when {
                isSelected -> theme.categorySelected
                isHovered -> theme.categoryHover
                else -> theme.categoryBackground
            }
            
            // Draw rect, optionally with border
            if (theme.useBorders && bgColor != theme.sidebarBackground) {
                // Draw border first
                RenderHelper.drawRect(context, sidebarX + 9, categoryY - 1, sidebarWidth - 18, categoryHeight + 2, theme.categoryBorder)
            }
            // Draw button on top
            RenderHelper.drawRect(context, sidebarX + 10, categoryY, sidebarWidth - 20, categoryHeight, bgColor)
            
            // Category text
            context.drawText(textRenderer, category.name, sidebarX + 20, categoryY + 13, theme.textPrimary, false)
            
            currentY += categoryHeight + categorySpacing
            
            // Show subcategories if this category is selected
            if (isSelected && category.subcategories.isNotEmpty()) {
                for ((subIndex, subcategory) in category.subcategories.withIndex()) {
                    val subHeight = 32
                    val subY = currentY
                    
                    if (subY + subHeight >= sidebarY && subY <= sidebarY + sidebarH) {
                        val isSubHovered = mouseX >= sidebarX + 20 && mouseX <= sidebarX + sidebarWidth - 10 &&
                                          mouseY >= subY && mouseY <= subY + subHeight
                        val isSubSelected = subIndex == selectedSubcategoryIndex
                        
                        val subBgColor = when {
                            isSubSelected -> theme.subcategorySelected
                            isSubHovered -> theme.subcategoryHover
                            else -> theme.subcategoryBackground
                        }
                        
                        // Draw rect, optionally with border
                        if (theme.useBorders && subBgColor != theme.sidebarBackground) {
                            // Draw border first
                            RenderHelper.drawRect(context, sidebarX + 19, subY - 1, sidebarWidth - 28, subHeight + 2, theme.categoryBorder)
                        }
                        // Draw button on top
                        RenderHelper.drawRect(context, sidebarX + 20, subY, sidebarWidth - 30, subHeight, subBgColor)
                        context.drawText(textRenderer, subcategory.name, sidebarX + 30, subY + 10, theme.textPrimary, false)
                    }
                    
                    currentY += subHeight + 4
                }
            }
        }
        
        context.disableScissor()
    }
    
    private fun renderContent(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Content area background
        val contentX = guiX + sidebarWidth + layout.outerMargin
        val contentY = guiY + layout.contentTopOffset
        val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
        val contentHeight = guiHeight - layout.contentTopOffset - layout.bottomMargin
        
        RenderHelper.drawRect(context, contentX, contentY, contentWidth, contentHeight, theme.contentBackground)
        
        // Enable scissor for scrolling
        context.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight)
        
        // Render widgets or hint message
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            
            // Show hint if no options at category level and subcategories exist
            if (widgets.isEmpty() && selectedSubcategoryIndex < 0 && category.subcategories.isNotEmpty()) {
                val hintText = "\uD83E\uDC1C Select a subcategory to view options"
                val hintX = contentX + contentPadding
                val hintY = contentY + contentPadding
                context.drawText(textRenderer, hintText, hintX, hintY, theme.textSecondary, false)
            }
            
            // All non-divider widgets should share the same visible X and width so that
            // toggles, sliders, etc. line up perfectly regardless of dividers or position
            val widgetDisplayX = guiX + sidebarWidth + layout.outerMargin + contentPadding
            val widgetDisplayWidth = contentWidth - contentPadding * 2
            
            for (widget in widgets) {
                val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider
                
                // Calculate display position based on scroll without modifying the logical Y
                val displayX = if (isDivider) widget.x + guiX else widgetDisplayX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                val originalWidth = widget.width
                
                // Temporarily set position (and width for non-dividers) for rendering
                widget.x = displayX
                widget.y = displayY
                if (!isDivider) {
                    widget.width = widgetDisplayWidth
                }
                widget.updateHover(mouseX, mouseY)
                
                // Get the correct instance (subcategory or category)
                val instance = if (selectedSubcategoryIndex >= 0) {
                    val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                    if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
                } else {
                    categoryInstance
                }
                
                // Render card background if theme uses card style (but not for dividers)
                if (theme.useCardStyle && !isDivider) {
                    val cardPaddingH = 16 // Horizontal padding
                    val cardPaddingV = 6   // Vertical padding
                    val cardX = displayX - cardPaddingH
                    val cardY = displayY - cardPaddingV / 2
                    val cardWidth = contentWidth - contentPadding * 2 + cardPaddingH * 2
                    val cardHeight = widget.height + cardPaddingV
                    
                    // Draw subtle shadow
                    val shadowOffset = 1
                    val shadowColor = 0x06000000
                    RenderHelper.drawRect(context, cardX + shadowOffset, cardY + shadowOffset, 
                        cardWidth, cardHeight, shadowColor)
                    
                    if (theme.useBorders) {
                        // Draw border first
                        RenderHelper.drawRect(context, cardX - 1, cardY - 1, cardWidth + 2, cardHeight + 2, theme.optionCardBorder)
                    }
                    // Draw card on top
                    RenderHelper.drawRect(context, cardX, cardY, cardWidth, cardHeight, theme.optionCardBackground)
                }
                
                widget.render(context, mouseX, mouseY, delta, instance, theme)
                
                // Restore original geometry
                widget.x = originalX
                widget.y = originalY
                widget.width = originalWidth
            }
        }
        
        context.disableScissor()
    }
    
    private fun renderMinigameSidebar(context: DrawContext, mouseX: Int, mouseY: Int) {
        val sidebarX = guiX
        val sidebarY = guiY + layout.contentTopOffset
        val sidebarH = guiHeight - layout.contentTopOffset - layout.bottomMargin

        // Sidebar background
        context.fill(sidebarX, sidebarY, sidebarX + sidebarWidth, sidebarY + sidebarH, theme.sidebarBackground)

        val buttonHeight = 40
        val buttonSpacing = categorySpacing
        var currentY = sidebarY + layout.sidebarCategoryTopPadding

        fun drawButton(label: String, minigame: Minigame) {
            val x = sidebarX + 10
            val y = currentY
            val w = sidebarWidth - 20
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
    }

    private fun renderMinigames(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        val contentX = guiX + sidebarWidth + layout.outerMargin
        val contentY = guiY + layout.contentTopOffset
        val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
        val contentHeight = guiHeight - layout.contentTopOffset - layout.bottomMargin

        // Background for minigames area
        RenderHelper.drawRect(context, contentX, contentY, contentWidth, contentHeight, theme.contentBackground)

        when (activeMinigame) {
            Minigame.SNAKE -> renderSnakeGame(context, contentX, contentY, contentWidth, contentHeight)
            Minigame.TETRIS -> renderTetrisGame(context, contentX, contentY, contentWidth, contentHeight)
        }
    }

    private fun renderSnakeGame(
        context: DrawContext,
        contentX: Int,
        contentY: Int,
        contentWidth: Int,
        contentHeight: Int,
    ) {
        val now = System.currentTimeMillis()
        snakeGame.update(now)

        val cols = snakeGame.columns
        val rows = snakeGame.rows
        if (cols <= 0 || rows <= 0) return

        val cellSize = (minOf(contentWidth / cols, contentHeight / rows)).coerceAtLeast(4)
        if (cellSize <= 0) return

        val boardWidth = cols * cellSize
        val boardHeight = rows * cellSize
        val boardX = contentX + (contentWidth - boardWidth) / 2
        val boardY = contentY + (contentHeight - boardHeight) / 2

        // Board background card
        if (theme.useCardStyle) {
            RenderHelper.drawRect(context, boardX - 4, boardY - 4, boardWidth + 8, boardHeight + 8, theme.optionCardBackground)
        }

        // Draw grid contents
        val head = snakeGame.segments.lastOrNull()
        for (segment in snakeGame.segments) {
            val segX = boardX + segment.x * cellSize
            val segY = boardY + segment.y * cellSize
            val isHead = head != null && segment == head
            val color = if (isHead) theme.widgetActive else theme.widgetBackground
            RenderHelper.drawRect(context, segX, segY, cellSize - 1, cellSize - 1, color)
        }

        val food = snakeGame.food
        val foodX = boardX + food.x * cellSize
        val foodY = boardY + food.y * cellSize
        val foodColor = 0xFFFF5555.toInt()
        RenderHelper.drawRect(context, foodX, foodY, cellSize - 1, cellSize - 1, foodColor)

        // HUD text (score and instructions)
        val hudX = contentX + contentPadding
        val hudY = contentY + contentPadding
        val scoreText = "Score: ${snakeGame.score}"
        val infoText = if (snakeGame.isAlive) {
            "Use WASD or arrow keys to move. Press R to restart."
        } else {
            "Game over! Press R to play again."
        }
        context.drawText(textRenderer, scoreText, hudX, hudY, theme.textPrimary, false)
        context.drawText(textRenderer, infoText, hudX, hudY + textRenderer.fontHeight + 4, theme.textSecondary, false)
    }

    private fun renderTetrisGame(
        context: DrawContext,
        contentX: Int,
        contentY: Int,
        contentWidth: Int,
        contentHeight: Int,
    ) {
        val now = System.currentTimeMillis()
        tetrisGame.update(now)

        val cols = tetrisGame.columns
        val rows = tetrisGame.rows
        if (cols <= 0 || rows <= 0) return

        val cellSize = (minOf(contentWidth * 2 / (cols * 3), contentHeight / rows)).coerceAtLeast(4)
        if (cellSize <= 0) return

        val boardWidth = cols * cellSize
        val boardHeight = rows * cellSize
        val boardX = contentX + contentPadding
        val boardY = contentY + contentPadding

        // Board background card
        if (theme.useCardStyle) {
            RenderHelper.drawRect(context, boardX - 4, boardY - 4, boardWidth + 8, boardHeight + 8, theme.optionCardBackground)
        }

        val snapshot = tetrisGame.getBoardSnapshot()
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val id = snapshot[y][x]
                if (id != 0) {
                    val cellX = boardX + x * cellSize
                    val cellY = boardY + y * cellSize
                    val color = when (id) {
                        1 -> 0xFF00FFFF.toInt() // I - cyan
                        2 -> 0xFFFFFF00.toInt() // O - yellow
                        3 -> 0xFFAA00FF.toInt() // T - purple
                        4 -> 0xFF00FF00.toInt() // S - green
                        5 -> 0xFFFF0000.toInt() // Z - red
                        6 -> 0xFF0000FF.toInt() // J - blue
                        7 -> 0xFFFFA500.toInt() // L - orange
                        else -> theme.widgetBackground
                    }
                    RenderHelper.drawRect(context, cellX, cellY, cellSize - 1, cellSize - 1, color)
                }
            }
        }

        // Active piece
        for (cell in tetrisGame.getActiveCells()) {
            val cellX = boardX + cell.x * cellSize
            val cellY = boardY + cell.y * cellSize
            RenderHelper.drawRect(context, cellX, cellY, cellSize - 1, cellSize - 1, theme.widgetActive)
        }

        // HUD (score, level, next piece)
        val hudX = boardX + boardWidth + contentPadding
        val hudY = boardY
        val lineH = textRenderer.fontHeight + 4
        val scoreText = "Score: ${tetrisGame.score}"
        val levelText = "Level: ${tetrisGame.level}"
        val linesText = "Lines: ${tetrisGame.linesCleared}"
        val statusText = if (tetrisGame.isGameOver) "Game Over (R to restart)" else "Arrow/WASD move, Space hard drop, R restart"
        context.drawText(textRenderer, scoreText, hudX, hudY, theme.textPrimary, false)
        context.drawText(textRenderer, levelText, hudX, hudY + lineH, theme.textPrimary, false)
        context.drawText(textRenderer, linesText, hudX, hudY + lineH * 2, theme.textPrimary, false)
        context.drawText(textRenderer, statusText, hudX, hudY + lineH * 4, theme.textSecondary, false)

        // Next piece preview
        val previewLabelY = hudY + lineH * 6
        context.drawText(textRenderer, "Next:", hudX, previewLabelY, theme.textPrimary, false)

        val previewCellSize = (cellSize * 3 / 4).coerceAtLeast(4)
        val previewBoxSize = previewCellSize * 4
        val previewX = hudX
        val previewY = previewLabelY + lineH

        // Preview background box
        RenderHelper.drawRect(context, previewX - 2, previewY - 2, previewBoxSize + 4, previewBoxSize + 4, theme.optionCardBackground)

        val nextCells = tetrisGame.getNextPieceCells()
        if (nextCells.isNotEmpty()) {
            val minX = nextCells.minOf { it.x }
            val maxX = nextCells.maxOf { it.x }
            val minY = nextCells.minOf { it.y }
            val maxY = nextCells.maxOf { it.y }
            val spanX = maxX - minX + 1
            val spanY = maxY - minY + 1
            val offsetCellsX = ((4 - spanX) / 2).coerceAtLeast(0)
            val offsetCellsY = ((4 - spanY) / 2).coerceAtLeast(0)

            val color = when (tetrisGame.getNextPiece()) {
                TetrisGame.Tetromino.I -> 0xFF00FFFF.toInt()
                TetrisGame.Tetromino.O -> 0xFFFFFF00.toInt()
                TetrisGame.Tetromino.T -> 0xFFAA00FF.toInt()
                TetrisGame.Tetromino.S -> 0xFF00FF00.toInt()
                TetrisGame.Tetromino.Z -> 0xFFFF0000.toInt()
                TetrisGame.Tetromino.J -> 0xFF0000FF.toInt()
                TetrisGame.Tetromino.L -> 0xFFFFA500.toInt()
            }

            for (cell in nextCells) {
                val px = previewX + (cell.x - minX + offsetCellsX) * previewCellSize
                val py = previewY + (cell.y - minY + offsetCellsY) * previewCellSize
                RenderHelper.drawRect(context, px, py, previewCellSize - 1, previewCellSize - 1, color)
            }
        }
    }

        //? if >=1.21.10 {
    override fun mouseClicked(click: net.minecraft.client.gui.Click, doubled: Boolean): Boolean {
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()
        // Mouse buttons are always 0=left, 1=right, 2=middle
        val buttonStr = click.button().toString()
        val button = when {
            buttonStr.contains("LEFT", ignoreCase = true) -> 0
            buttonStr.contains("RIGHT", ignoreCase = true) -> 1
            buttonStr.contains("MIDDLE", ignoreCase = true) -> 2
            else -> 0
        }
    //?} else {
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mouseXInt = mouseX.toInt()
        val mouseYInt = mouseY.toInt()
    *///?}
        
        // Check if click is outside GUI bounds
        if (mouseXInt < guiX || mouseXInt > guiX + guiWidth || mouseYInt < guiY || mouseYInt > guiY + guiHeight) {
            return true // Consume clicks outside GUI
        }
        
        // Title bar geometry used for Minigames and close buttons
        val closeButtonSize = 30
        val titleBarX = guiX + 10
        val titleBarY = guiY + 10
        val titleBarWidth = guiWidth - 20
        val closeButtonX = titleBarX + titleBarWidth - closeButtonSize - 10
        val closeButtonY = titleBarY + 10
        val minigamesButtonSize = closeButtonSize
        val minigamesButtonX = closeButtonX - minigamesButtonSize - 6
        val minigamesButtonY = closeButtonY

        // Minigames button click toggles view mode
        if (RenderHelper.isMouseOver(mouseXInt, mouseYInt, minigamesButtonX, minigamesButtonY, minigamesButtonSize, minigamesButtonSize)) {
            if (viewMode == ViewMode.CONFIG) {
                viewMode = ViewMode.MINIGAMES
                activeMinigame = Minigame.SNAKE
                snakeGame.reset()
                tetrisGame.reset()
            } else {
                viewMode = ViewMode.CONFIG
                rebuildWidgets()
                targetSidebarScroll = 0.0
                targetContentScroll = 0.0
            }
            return true
        }

        // Close button
        if (RenderHelper.isMouseOver(mouseXInt, mouseYInt, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize)) {
            close()
            return true
        }
        
        if (viewMode == ViewMode.MINIGAMES) {
            // Handle clicks on Snake/Tetris buttons in the minigame sidebar.
            val sidebarX = guiX
            val sidebarY = guiY + layout.contentTopOffset
            val buttonHeight = 40
            val buttonSpacing = categorySpacing
            var currentY = sidebarY + layout.sidebarCategoryTopPadding

            fun hitButton(): Minigame? {
                val x = sidebarX + 10
                val w = sidebarWidth - 20
                val h = buttonHeight

                val snakeY = currentY
                val tetrisY = currentY + h + buttonSpacing

                if (mouseXInt >= x && mouseXInt <= x + w) {
                    if (mouseYInt >= snakeY && mouseYInt <= snakeY + h) return Minigame.SNAKE
                    if (mouseYInt >= tetrisY && mouseYInt <= tetrisY + h) return Minigame.TETRIS
                }
                return null
            }

            when (hitButton()) {
                Minigame.SNAKE -> {
                    activeMinigame = Minigame.SNAKE
                    snakeGame.reset()
                    return true
                }
                Minigame.TETRIS -> {
                    activeMinigame = Minigame.TETRIS
                    tetrisGame.reset()
                    return true
                }
                null -> return true // ignore other clicks while in minigames mode
            }
        }
        
        // Check sidebar clicks
        if (mouseXInt >= guiX && mouseXInt < guiX + sidebarWidth) {
            handleSidebarClick(mouseXInt, mouseYInt)
            return true
        }
        
        // Check widget clicks
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            val instance = if (selectedSubcategoryIndex >= 0) {
                val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
            } else {
                categoryInstance
            }
            
            val contentY = guiY + layout.contentTopOffset
            // Match the same visible X/width we use during rendering so hitboxes
            // line up with the drawn cards and toggles.
            val contentX = guiX + sidebarWidth + layout.outerMargin
            val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
            val widgetDisplayX = contentX + contentPadding
            val widgetDisplayWidth = contentWidth - contentPadding * 2
            
            for (widget in widgets) {
                val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider
                
                // Calculate display position with scroll offset
                val displayX = if (isDivider) widget.x + guiX else widgetDisplayX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                val originalWidth = widget.width
                
                // Temporarily set display geometry for click detection
                widget.x = displayX
                widget.y = displayY
                if (!isDivider) {
                    widget.width = widgetDisplayWidth
                }
                
                // Update hover state with current position
                widget.updateHover(mouseXInt, mouseYInt)
                
                val clicked = widget.mouseClicked(mouseXInt, mouseYInt, button, instance)
                
                // Restore original geometry
                widget.x = originalX
                widget.y = originalY
                widget.width = originalWidth
                
                if (clicked) {
                    return true
                }
            }
        }
        
        //? if >=1.21.10 {
        return super.mouseClicked(click, doubled)
        //?} else {
        /*return super.mouseClicked(mouseX, mouseY, button)
        *///?}
    }
    
    //? if >=1.21.10 {
    override fun mouseDragged(click: net.minecraft.client.gui.Click, offsetX: Double, offsetY: Double): Boolean {
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()

        // Only handle dragging if mouse is within GUI bounds
        if (mouseXInt < guiX || mouseXInt > guiX + guiWidth || mouseYInt < guiY || mouseYInt > guiY + guiHeight) {
            return super.mouseDragged(click, offsetX, offsetY)
        }

        if (viewMode == ViewMode.MINIGAMES) {
            // No draggable widgets in minigames mode.
            return super.mouseDragged(click, offsetX, offsetY)
        }

        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            val instance = if (selectedSubcategoryIndex >= 0) {
                val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
            } else {
                categoryInstance
            }

            val contentY = guiY + layout.contentTopOffset
            val contentX = guiX + sidebarWidth + layout.outerMargin
            val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
            val widgetDisplayX = contentX + contentPadding
            val widgetDisplayWidth = contentWidth - contentPadding * 2

            for (widget in widgets) {
                val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider

                val displayX = if (isDivider) widget.x + guiX else widgetDisplayX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                val originalWidth = widget.width

                widget.x = displayX
                widget.y = displayY
                if (!isDivider) {
                    widget.width = widgetDisplayWidth
                }

                widget.updateHover(mouseXInt, mouseYInt)
                // Use left button (0) for drag handling; widgets like SliderWidget
                // only care that a drag is in progress, not which button.
                val dragged = widget.mouseDragged(mouseXInt, mouseYInt, 0, offsetX, offsetY, instance)

                widget.x = originalX
                widget.y = originalY
                widget.width = originalWidth

                if (dragged) {
                    return true
                }
            }
        }

        return super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseReleased(click: net.minecraft.client.gui.Click): Boolean {
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()

        // Mouse buttons are always 0=left, 1=right, 2=middle in our widgets;
        // derive a simple int from the Click button enum string.
        val buttonStr = click.button().toString()
        val button = when {
            buttonStr.contains("LEFT", ignoreCase = true) -> 0
            buttonStr.contains("RIGHT", ignoreCase = true) -> 1
            buttonStr.contains("MIDDLE", ignoreCase = true) -> 2
            else -> 0
        }

        if (viewMode == ViewMode.MINIGAMES) {
            // No widget release handling in minigames mode.
            return super.mouseReleased(click)
        }

        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            val instance = if (selectedSubcategoryIndex >= 0) {
                val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
            } else {
                categoryInstance
            }

            val contentY = guiY + layout.contentTopOffset
            val contentX = guiX + sidebarWidth + layout.outerMargin
            val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
            val widgetDisplayX = contentX + contentPadding
            val widgetDisplayWidth = contentWidth - contentPadding * 2

            for (widget in widgets) {
                val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider

                val displayX = if (isDivider) widget.x + guiX else widgetDisplayX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                val originalWidth = widget.width

                widget.x = displayX
                widget.y = displayY
                if (!isDivider) {
                    widget.width = widgetDisplayWidth
                }

                widget.updateHover(mouseXInt, mouseYInt)
                val released = widget.mouseReleased(mouseXInt, mouseYInt, button, instance)

                widget.x = originalX
                widget.y = originalY
                widget.width = originalWidth

                if (released) {
                    return true
                }
            }
        }

        return super.mouseReleased(click)
    }
    //?} else {
    /*override fun mouseDragged(mouseX: Double, mouseY: Double, button: Int, deltaX: Double, deltaY: Double): Boolean {
        val mouseXInt = mouseX.toInt()
        val mouseYInt = mouseY.toInt()

        if (mouseXInt < guiX || mouseXInt > guiX + guiWidth || mouseYInt < guiY || mouseYInt > guiY + guiHeight) {
            return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
        }

        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            val instance = if (selectedSubcategoryIndex >= 0) {
                val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
            } else {
                categoryInstance
            }

            val contentY = guiY + layout.contentTopOffset
            val contentX = guiX + sidebarWidth + layout.outerMargin
            val contentWidth = guiWidth - sidebarWidth - layout.outerMargin * 2
            val widgetDisplayX = contentX + contentPadding
            val widgetDisplayWidth = contentWidth - contentPadding * 2

            for (widget in widgets) {
                val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider

                val displayX = if (isDivider) widget.x + guiX else widgetDisplayX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                val originalWidth = widget.width

                widget.x = displayX
                widget.y = displayY
                if (!isDivider) {
                    widget.width = widgetDisplayWidth
                }

                widget.updateHover(mouseXInt, mouseYInt)
                val dragged = widget.mouseDragged(mouseXInt, mouseYInt, button, deltaX, deltaY, instance)

                widget.x = originalX
                widget.y = originalY
                widget.width = originalWidth

                if (dragged) {
                    return true
                }
            }
        }

        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY)
    }*/
    //?}
    
    private fun handleSidebarClick(mouseX: Int, mouseY: Int) {
        val sidebarX = guiX
        val sidebarY = guiY + layout.contentTopOffset
        val sidebarH = guiHeight - layout.contentTopOffset - layout.bottomMargin
        var currentY = sidebarY + layout.sidebarCategoryTopPadding - sidebarScroll.toInt()
        
        for ((index, category) in configManager.structure.categories.withIndex()) {
            val categoryHeight = 40
            val categoryY = currentY
            
            // Check category click
            if (mouseX >= sidebarX + 10 && mouseX <= sidebarX + sidebarWidth - 10 &&
                mouseY >= categoryY && mouseY <= categoryY + categoryHeight) {
                DebugLogger.logWidgetInteraction("Selected category: ${category.name}")
                selectedCategoryIndex = index
                selectedSubcategoryIndex = -1
                selectionAnimProgress = 0f // Reset animation
                rebuildWidgets()
                targetContentScroll = 0.0
                return
            }
            
            currentY += categoryHeight + categorySpacing
            
            // Check subcategory clicks
            if (index == selectedCategoryIndex && category.subcategories.isNotEmpty()) {
                for ((subIndex, subcategory) in category.subcategories.withIndex()) {
                    val subHeight = 32
                    val subY = currentY
                    
                    if (mouseX >= sidebarX + 20 && mouseX <= sidebarX + sidebarWidth - 10 &&
                        mouseY >= subY && mouseY <= subY + subHeight) {
                        DebugLogger.logWidgetInteraction("Selected subcategory: ${subcategory.name}")
                        selectedSubcategoryIndex = subIndex
                        selectionAnimProgress = 0f // Reset animation
                        rebuildWidgets()
                        targetContentScroll = 0.0
                        return
                    }
                    
                    currentY += subHeight + 4
                }
            }
        }
    }
    
    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val mouseXInt = mouseX.toInt()
        val mouseYInt = mouseY.toInt()
        
        // Only handle scrolling if mouse is within GUI bounds
        if (mouseXInt < guiX || mouseXInt > guiX + guiWidth || mouseYInt < guiY || mouseYInt > guiY + guiHeight) {
            return false
        }

        if (viewMode == ViewMode.MINIGAMES) {
            // Ignore scroll input in minigames mode.
            return false
        }
        
        if (mouseXInt >= guiX && mouseXInt < guiX + sidebarWidth) {
            targetSidebarScroll = (targetSidebarScroll - verticalAmount * scrollSpeed).coerceAtLeast(0.0)
        } else {
            targetContentScroll = (targetContentScroll - verticalAmount * scrollSpeed).coerceAtLeast(0.0)
        }
        
        return true
    }
    
    //? if >=1.21.10 {
    override fun keyPressed(input: net.minecraft.client.input.KeyInput): Boolean {
        val keyCode = input.key()
    //?} else {
    /*override fun keyPressed(keyCode: Int, scanCode: Int, modifiers: Int): Boolean {
    *///?}
        if (keyCode == GLFW.GLFW_KEY_ESCAPE) {
            close()
            return true
        }

        if (viewMode == ViewMode.MINIGAMES) {
            when (activeMinigame) {
                Minigame.SNAKE -> {
                    when (keyCode) {
                        GLFW.GLFW_KEY_W, GLFW.GLFW_KEY_UP -> snakeGame.changeDirection(Direction.UP)
                        GLFW.GLFW_KEY_S, GLFW.GLFW_KEY_DOWN -> snakeGame.changeDirection(Direction.DOWN)
                        GLFW.GLFW_KEY_A, GLFW.GLFW_KEY_LEFT -> snakeGame.changeDirection(Direction.LEFT)
                        GLFW.GLFW_KEY_D, GLFW.GLFW_KEY_RIGHT -> snakeGame.changeDirection(Direction.RIGHT)
                        GLFW.GLFW_KEY_R -> snakeGame.reset()
                    }
                }
                Minigame.TETRIS -> {
                    when (keyCode) {
                        GLFW.GLFW_KEY_LEFT, GLFW.GLFW_KEY_A -> tetrisGame.moveLeft()
                        GLFW.GLFW_KEY_RIGHT, GLFW.GLFW_KEY_D -> tetrisGame.moveRight()
                        GLFW.GLFW_KEY_DOWN, GLFW.GLFW_KEY_S -> tetrisGame.softDrop()
                        GLFW.GLFW_KEY_UP, GLFW.GLFW_KEY_W -> tetrisGame.rotateCW()
                        GLFW.GLFW_KEY_Q -> tetrisGame.rotateCCW()
                        GLFW.GLFW_KEY_SPACE -> tetrisGame.hardDrop()
                        GLFW.GLFW_KEY_R -> tetrisGame.reset()
                    }
                }
            }
            return true
        }
        
        // Pass to focused widget
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            val instance = if (selectedSubcategoryIndex >= 0) {
                val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
            } else {
                categoryInstance
            }
            
            for (widget in widgets) {
                if (widget.isFocused && widget.keyPressed(keyCode, 0, 0, instance)) {
                    return true
                }
            }
        }
        
        //? if >=1.21.10 {
        return super.keyPressed(input)
        //?} else {
        /*return super.keyPressed(keyCode, 0, 0)
        *///?}
    }
    
    //? if >=1.21.10 {
    override fun charTyped(input: net.minecraft.client.input.CharInput): Boolean {
        val chr = input.codepoint().toChar()
    //?} else {
    /*override fun charTyped(chr: Char, modifiers: Int): Boolean {
    *///?}
        if (viewMode == ViewMode.MINIGAMES) {
            // Minigames view does not use text input.
            return false
        }

        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            val instance = if (selectedSubcategoryIndex >= 0) {
                val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
            } else {
                categoryInstance
            }
            
            for (widget in widgets) {
                if (widget.isFocused && widget.charTyped(chr, 0, instance)) {
                    return true
                }
            }
        }
        
        //? if >=1.21.10 {
        return super.charTyped(input)
        //?} else {
        /*return super.charTyped(chr, 0)
        *///?}
    }
    
    override fun close() {
        DebugLogger.logConfigChange("Config GUI closed, saving config")
        configManager.save()
        super.close()
    }
    
    private fun getCategoryInstance(category: CategoryData): Any {
        category.field.isAccessible = true
        return category.field.get(configManager.instance)
    }
    
    private fun getSubcategoryInstance(categoryInstance: Any, subcategory: SubcategoryData): Any {
        subcategory.field.isAccessible = true
        return subcategory.field.get(categoryInstance)
    }
}
