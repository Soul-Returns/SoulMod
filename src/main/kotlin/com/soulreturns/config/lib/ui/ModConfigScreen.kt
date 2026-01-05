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

/**
 * Modern configuration screen (80% size, centered, with blur)
 */
class ModConfigScreen<T : Any>(
    private val configManager: SoulConfigManager<T>,
    private val title: String,
    private val version: String
) : Screen(Text.literal(title)) {
    
    private val sidebarWidth = 220
    private val contentPadding = 20
    private val categorySpacing = 8
    private val widgetSpacing = 15
    
    // GUI dimensions (80% of screen, centered)
    private var guiX = 0
    private var guiY = 0
    private var guiWidth = 0
    private var guiHeight = 0
    
    private var selectedCategoryIndex = 0
    private var selectedSubcategoryIndex = -1 // -1 means no subcategory selected
    
    private var sidebarScroll = 0.0
    private var contentScroll = 0.0
    private val scrollSpeed = 20.0
    
    private val widgets = mutableListOf<ConfigWidget>()
    
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
        // Apply blur effect if supported
        if (client != null && client!!.world != null) {
            context.fillGradient(0, 0, width, height, 0xC0101010.toInt(), 0xD0101010.toInt())
        } else {
            renderInGameBackground(context)
        }
        
        // Background for GUI area
        RenderHelper.drawGradientRect(context, guiX, guiY, guiWidth, guiHeight, 0xFF0F0F0F.toInt(), 0xFF1A1A1A.toInt())
        
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
        RenderHelper.drawRoundedRect(context, titleBarX, titleBarY, titleBarWidth, 50, 12f, 0xDD1C1C1C.toInt())
        
        // Title text
        val titleText = "$title v$version"
        val titleX = titleBarX + 20
        val titleY = titleBarY + 15
        context.drawText(textRenderer, titleText, titleX, titleY, 0xFFFFFFFF.toInt(), false)
        
        // Close button
        val closeButtonSize = 30
        val closeButtonX = titleBarX + titleBarWidth - closeButtonSize - 10
        val closeButtonY = titleBarY + 10
        val isCloseHovered = RenderHelper.isMouseOver(
            client?.mouse?.x?.toInt() ?: 0,
            client?.mouse?.y?.toInt() ?: 0,
            closeButtonX, closeButtonY, closeButtonSize, closeButtonSize
        )
        
        val closeButtonColor = if (isCloseHovered) 0xFFFF4444.toInt() else 0xFF3C3C3C.toInt()
        RenderHelper.drawRoundedRect(context, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize, 8f, closeButtonColor)
        
        // X icon
        val xSize = 12
        val xX = closeButtonX + (closeButtonSize - xSize) / 2
        val xY = closeButtonY + (closeButtonSize - xSize) / 2
        context.drawText(textRenderer, "✕", xX, xY, 0xFFFFFFFF.toInt(), false)
    }
    
    private fun renderSidebar(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Sidebar background
        val sidebarX = guiX
        val sidebarY = guiY
        val sidebarH = guiHeight
        RenderHelper.drawRoundedRect(context, sidebarX, sidebarY, sidebarWidth, sidebarH, 0f, 0xFF151515.toInt())
        
        // Enable scissor for sidebar
        context.enableScissor(sidebarX, sidebarY + 70, sidebarX + sidebarWidth, sidebarY + sidebarH)
        
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
                isSelected -> 0xFF2C5AA0.toInt()
                isHovered -> 0xFF2C2C2C.toInt()
                else -> 0xFF1F1F1F.toInt()
            }
            
            RenderHelper.drawRoundedRect(context, sidebarX + 10, categoryY, sidebarWidth - 20, categoryHeight, 8f, bgColor)
            
            // Category text
            context.drawText(textRenderer, category.name, sidebarX + 20, categoryY + 13, 0xFFFFFFFF.toInt(), false)
            
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
                            isSubSelected -> 0xFF4C9AFF.toInt()
                            isSubHovered -> 0xFF2A2A2A.toInt()
                            else -> 0xFF242424.toInt()
                        }
                        
                        RenderHelper.drawRoundedRect(context, sidebarX + 20, subY, sidebarWidth - 30, subHeight, 6f, subBgColor)
                        context.drawText(textRenderer, subcategory.name, sidebarX + 30, subY + 10, 0xFFCCCCCC.toInt(), false)
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
        
        RenderHelper.drawRoundedRect(context, contentX, contentY, contentWidth, contentHeight, 12f, 0xFF1A1A1A.toInt())
        
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
                context.drawText(textRenderer, hintText, hintX, hintY, 0xFF888888.toInt(), false)
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
                
                widget.render(context, mouseX, mouseY, delta, instance)
                
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
                rebuildWidgets()
                contentScroll = 0.0
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
                        rebuildWidgets()
                        contentScroll = 0.0
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
            sidebarScroll = (sidebarScroll - verticalAmount * scrollSpeed).coerceAtLeast(0.0)
        } else {
            contentScroll = (contentScroll - verticalAmount * scrollSpeed).coerceAtLeast(0.0)
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
