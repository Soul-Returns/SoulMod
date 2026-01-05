package com.soulreturns.config.lib.ui

import com.soulreturns.config.lib.manager.SoulConfigManager
import com.soulreturns.config.lib.model.CategoryData
import com.soulreturns.config.lib.model.SubcategoryData
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
class ModConfigScreen<T : Any>(
    private val configManager: SoulConfigManager<T>,
    private val title: String,
    private val version: String
) : Screen(Text.literal(title)) {
    
    private val sidebarWidth = 240
    private val contentPadding = 24
    private val categorySpacing = 6
    private val widgetSpacing = 6
    
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
        // Calculate 80% of screen size
        guiWidth = (width * 0.8).toInt()
        guiHeight = (height * 0.8).toInt()
        
        // Center the GUI
        guiX = (width - guiWidth) / 2
        guiY = (height - guiHeight) / 2
    }
    
    private fun rebuildWidgets() {
        widgets.clear()
        
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex) ?: return
        val categoryInstance = getCategoryInstance(category)
        
        var currentY = contentPadding
        
        // If a subcategory is selected, show its options
        if (selectedSubcategoryIndex >= 0) {
            val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex) ?: return
            val subcategoryInstance = getSubcategoryInstance(categoryInstance, subcategory)
            
            for (option in subcategory.options) {
                val widget = WidgetFactory.createWidget(
                    option,
                    sidebarWidth + contentPadding,
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
                    sidebarWidth + contentPadding,
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
        
        // Render sidebar
        renderSidebar(context, mouseX, mouseY, delta)
        
        // Render content area
        renderContent(context, mouseX, mouseY, delta)
        
        // Render title bar
        renderTitleBar(context)
        
        super.render(context, mouseX, mouseY, delta)
    }
    
    private fun renderTitleBar(context: DrawContext) {
        // Title bar background
        val titleBarX = guiX + 10
        val titleBarY = guiY + 10
        val titleBarWidth = guiWidth - 20
        RenderHelper.drawRoundedRect(context, titleBarX, titleBarY, titleBarWidth, 50, 12f, theme.titleBarBackground)
        
        // Title text
        val titleText = "$title v$version"
        val titleX = titleBarX + 20
        val titleY = titleBarY + 15
        context.drawText(textRenderer, titleText, titleX, titleY, theme.textPrimary, false)
        
        // Close button
        val closeButtonSize = 30
        val closeButtonX = titleBarX + titleBarWidth - closeButtonSize - 10
        val closeButtonY = titleBarY + 10
        val isCloseHovered = RenderHelper.isMouseOver(
            client?.mouse?.x?.toInt() ?: 0,
            client?.mouse?.y?.toInt() ?: 0,
            closeButtonX, closeButtonY, closeButtonSize, closeButtonSize
        )
        
        val closeButtonColor = if (isCloseHovered) theme.closeButtonHover else theme.closeButtonNormal
        RenderHelper.drawRoundedRect(context, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, 8f, closeButtonColor)
        
        // X icon
        val xSize = 12
        val xX = closeButtonX + (closeButtonSize - xSize) / 2
        val xY = closeButtonY + (closeButtonSize - xSize) / 2
        context.drawText(textRenderer, "✕", xX, xY, theme.textPrimary, false)
    }
    
    private fun renderSidebar(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Sidebar background
        val sidebarX = guiX
        val sidebarY = guiY
        val sidebarH = guiHeight
        context.fill(sidebarX, sidebarY, sidebarX + sidebarWidth, sidebarY + sidebarH, theme.sidebarBackground)
        
        // Enable scissor for sidebar (but leave some padding for rounded corners)
        context.enableScissor(sidebarX, sidebarY + 70, sidebarX + sidebarWidth, sidebarY + sidebarH - 10)
        
        // Categories
        var currentY = sidebarY + 80 - sidebarScroll.toInt()
        
        for ((index, category) in configManager.structure.categories.withIndex()) {
            val categoryHeight = 40
            val categoryY = currentY
            
            // Skip if off-screen
            if (categoryY + categoryHeight < sidebarY + 70 || categoryY > sidebarY + sidebarH) {
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
            
            // Always draw rounded rect, optionally with border
            if (theme.useBorders && bgColor != theme.sidebarBackground) {
                // Draw border first
                RenderHelper.drawRoundedRect(context, sidebarX + 9, categoryY - 1, sidebarWidth - 18, categoryHeight + 2, theme.categoryCornerRadius, theme.categoryBorder)
            }
            // Draw button on top
            RenderHelper.drawRoundedRect(context, sidebarX + 10, categoryY, sidebarWidth - 20, categoryHeight, theme.categoryCornerRadius, bgColor)
            
            // Category text
            context.drawText(textRenderer, category.name, sidebarX + 20, categoryY + 13, theme.textPrimary, false)
            
            currentY += categoryHeight + categorySpacing
            
            // Show subcategories if this category is selected
            if (isSelected && category.subcategories.isNotEmpty()) {
                for ((subIndex, subcategory) in category.subcategories.withIndex()) {
                    val subHeight = 32
                    val subY = currentY
                    
                    if (subY + subHeight >= sidebarY + 70 && subY <= sidebarY + sidebarH) {
                        val isSubHovered = mouseX >= sidebarX + 20 && mouseX <= sidebarX + sidebarWidth - 10 &&
                                          mouseY >= subY && mouseY <= subY + subHeight
                        val isSubSelected = subIndex == selectedSubcategoryIndex
                        
                        val subBgColor = when {
                            isSubSelected -> theme.subcategorySelected
                            isSubHovered -> theme.subcategoryHover
                            else -> theme.subcategoryBackground
                        }
                        
                        // Always draw rounded rect, optionally with border
                        if (theme.useBorders && subBgColor != theme.sidebarBackground) {
                            // Draw border first
                            RenderHelper.drawRoundedRect(context, sidebarX + 19, subY - 1, sidebarWidth - 28, subHeight + 2, theme.categoryCornerRadius, theme.categoryBorder)
                        }
                        // Draw button on top
                        RenderHelper.drawRoundedRect(context, sidebarX + 20, subY, sidebarWidth - 30, subHeight, theme.categoryCornerRadius, subBgColor)
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
        val contentX = guiX + sidebarWidth + 10
        val contentY = guiY + 70
        val contentWidth = guiWidth - sidebarWidth - 20
        val contentHeight = guiHeight - 80
        
        RenderHelper.drawRoundedRect(context, contentX, contentY, contentWidth, contentHeight, 12f, theme.contentBackground)
        
        // Enable scissor for scrolling
        context.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight)
        
        // Render widgets or hint message
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            
            // Show hint if no options at category level and subcategories exist
            if (widgets.isEmpty() && selectedSubcategoryIndex < 0 && category.subcategories.isNotEmpty()) {
                val hintText = "← Select a subcategory to view options"
                val hintX = contentX + contentPadding
                val hintY = contentY + contentPadding
                context.drawText(textRenderer, hintText, hintX, hintY, theme.textSecondary, false)
            }
            
            for (widget in widgets) {
                // Calculate display position based on scroll without modifying widget.y
                val displayX = widget.x + guiX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                
                // Temporarily set position for rendering
                widget.x = displayX
                widget.y = displayY
                widget.updateHover(mouseX, mouseY)
                
                // Get the correct instance (subcategory or category)
                val instance = if (selectedSubcategoryIndex >= 0) {
                    val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                    if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
                } else {
                    categoryInstance
                }
                
                // Render card background if theme uses card style (but not for dividers)
                val isDivider = widget.option.type is com.soulreturns.config.lib.model.OptionType.Divider
                if (theme.useCardStyle && !isDivider) {
                    val cardPaddingH = 16 // Horizontal padding
                    val cardPaddingV = 8   // Vertical padding (reduced)
                    val cardX = displayX - cardPaddingH
                    val cardY = displayY - cardPaddingV / 2
                    val cardWidth = contentWidth - contentPadding * 2 + cardPaddingH * 2
                    val cardHeight = widget.height + cardPaddingV
                    
                    // Draw subtle shadow
                    val shadowOffset = 1
                    val shadowColor = 0x06000000
                    RenderHelper.drawRoundedRect(context, cardX + shadowOffset, cardY + shadowOffset, 
                        cardWidth, cardHeight, theme.cardCornerRadius, shadowColor)
                    
                    if (theme.useBorders) {
                        // Draw border first
                        RenderHelper.drawRoundedRect(context, cardX - 1, cardY - 1, cardWidth + 2, cardHeight + 2, theme.cardCornerRadius, theme.optionCardBorder)
                    }
                    // Draw card on top
                    RenderHelper.drawRoundedRect(context, cardX, cardY, cardWidth, cardHeight, theme.cardCornerRadius, theme.optionCardBackground)
                }
                
                widget.render(context, mouseX, mouseY, delta, instance, theme)
                
                // Restore original position
                widget.x = originalX
                widget.y = originalY
            }
        }
        
        context.disableScissor()
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
        
        // Check close button
        val closeButtonSize = 30
        val titleBarX = guiX + 10
        val titleBarY = guiY + 10
        val titleBarWidth = guiWidth - 20
        val closeButtonX = titleBarX + titleBarWidth - closeButtonSize - 10
        val closeButtonY = titleBarY + 10
        if (RenderHelper.isMouseOver(mouseXInt, mouseYInt, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize)) {
            close()
            return true
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
            
            val contentY = guiY + 70
            for (widget in widgets) {
                // Calculate display position with scroll offset
                val displayX = widget.x + guiX
                val displayY = widget.y - contentScroll.toInt() + contentY
                val originalX = widget.x
                val originalY = widget.y
                
                // Temporarily set display position for click detection
                widget.x = displayX
                widget.y = displayY
                
                // Update hover state with current position
                widget.updateHover(mouseXInt, mouseYInt)
                
                val clicked = widget.mouseClicked(mouseXInt, mouseYInt, button, instance)
                
                // Restore original position
                widget.x = originalX
                widget.y = originalY
                
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
    
    private fun handleSidebarClick(mouseX: Int, mouseY: Int) {
        val sidebarX = guiX
        val sidebarY = guiY
        val sidebarH = guiHeight
        var currentY = sidebarY + 80 - sidebarScroll.toInt()
        
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
