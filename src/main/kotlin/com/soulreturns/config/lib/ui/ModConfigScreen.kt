package com.soulreturns.config.lib.ui

import com.soulreturns.config.lib.manager.SoulConfigManager
import com.soulreturns.config.lib.model.CategoryData
import com.soulreturns.config.lib.model.SubcategoryData
import com.soulreturns.config.lib.ui.widgets.ConfigWidget
import com.soulreturns.config.lib.ui.widgets.WidgetFactory
import net.minecraft.client.gui.DrawContext
import net.minecraft.client.gui.screen.Screen
import net.minecraft.text.Text
import org.lwjgl.glfw.GLFW

/**
 * Modern fullscreen configuration screen
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
    
    private var selectedCategoryIndex = 0
    private var selectedSubcategoryIndex = -1 // -1 means no subcategory selected
    
    private var sidebarScroll = 0.0
    private var contentScroll = 0.0
    private val scrollSpeed = 20.0
    
    private val widgets = mutableListOf<ConfigWidget>()
    
    init {
        rebuildWidgets()
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
        // Background
        RenderHelper.drawGradientRect(context, 0, 0, width, height, 0xFF0F0F0F.toInt(), 0xFF1A1A1A.toInt())
        
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
        RenderHelper.drawRoundedRect(context, 10, 10, width - 20, 50, 12f, 0xDD1C1C1C.toInt())
        
        // Title text
        val titleText = "$title v$version"
        val titleX = 30
        val titleY = 25
        context.drawText(textRenderer, titleText, titleX, titleY, 0xFFFFFFFF.toInt(), false)
        
        // Close button
        val closeButtonSize = 30
        val closeButtonX = width - closeButtonSize - 20
        val closeButtonY = 20
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
        RenderHelper.drawRoundedRect(context, 0, 0, sidebarWidth, height, 0f, 0xFF151515.toInt())
        
        // Categories
        var currentY = 80 - sidebarScroll.toInt()
        
        for ((index, category) in configManager.structure.categories.withIndex()) {
            val categoryHeight = 40
            val categoryY = currentY
            
            // Skip if off-screen
            if (categoryY + categoryHeight < 70 || categoryY > height) {
                currentY += categoryHeight + categorySpacing
                if (index == selectedCategoryIndex && category.subcategories.isNotEmpty()) {
                    currentY += (category.subcategories.size * 35)
                }
                continue
            }
            
            // Check if hovered
            val isHovered = mouseX >= 10 && mouseX <= sidebarWidth - 10 &&
                            mouseY >= categoryY && mouseY <= categoryY + categoryHeight
            val isSelected = index == selectedCategoryIndex
            
            // Category button background
            val bgColor = when {
                isSelected -> 0xFF2C5AA0.toInt()
                isHovered -> 0xFF2C2C2C.toInt()
                else -> 0xFF1F1F1F.toInt()
            }
            
            RenderHelper.drawRoundedRect(context, 10, categoryY, sidebarWidth - 20, categoryHeight, 8f, bgColor)
            
            // Category text
            context.drawText(textRenderer, category.name, 20, categoryY + 13, 0xFFFFFFFF.toInt(), false)
            
            currentY += categoryHeight + categorySpacing
            
            // Show subcategories if this category is selected
            if (isSelected && category.subcategories.isNotEmpty()) {
                for ((subIndex, subcategory) in category.subcategories.withIndex()) {
                    val subHeight = 32
                    val subY = currentY
                    
                    if (subY + subHeight >= 70 && subY <= height) {
                        val isSubHovered = mouseX >= 20 && mouseX <= sidebarWidth - 10 &&
                                          mouseY >= subY && mouseY <= subY + subHeight
                        val isSubSelected = subIndex == selectedSubcategoryIndex
                        
                        val subBgColor = when {
                            isSubSelected -> 0xFF4C9AFF.toInt()
                            isSubHovered -> 0xFF2A2A2A.toInt()
                            else -> 0xFF242424.toInt()
                        }
                        
                        RenderHelper.drawRoundedRect(context, 20, subY, sidebarWidth - 30, subHeight, 6f, subBgColor)
                        context.drawText(textRenderer, subcategory.name, 30, subY + 10, 0xFFCCCCCC.toInt(), false)
                    }
                    
                    currentY += subHeight + 4
                }
            }
        }
    }
    
    private fun renderContent(context: DrawContext, mouseX: Int, mouseY: Int, delta: Float) {
        // Content area background
        val contentX = sidebarWidth + 10
        val contentY = 70
        val contentWidth = width - sidebarWidth - 20
        val contentHeight = height - 80
        
        RenderHelper.drawRoundedRect(context, contentX, contentY, contentWidth, contentHeight, 12f, 0xFF1A1A1A.toInt())
        
        // Enable scissor for scrolling
        context.enableScissor(contentX, contentY, contentX + contentWidth, contentY + contentHeight)
        
        // Render widgets
        val category = configManager.structure.categories.getOrNull(selectedCategoryIndex)
        if (category != null) {
            val categoryInstance = getCategoryInstance(category)
            
            for (widget in widgets) {
                widget.y = widget.y - contentScroll.toInt() + contentY
                widget.updateHover(mouseX, mouseY)
                
                // Get the correct instance (subcategory or category)
                val instance = if (selectedSubcategoryIndex >= 0) {
                    val subcategory = category.subcategories.getOrNull(selectedSubcategoryIndex)
                    if (subcategory != null) getSubcategoryInstance(categoryInstance, subcategory) else categoryInstance
                } else {
                    categoryInstance
                }
                
                widget.render(context, mouseX, mouseY, delta, instance)
            }
        }
        
        context.disableScissor()
    }
    
    //? if >=1.21.10 {
    override fun mouseClicked(click: net.minecraft.client.gui.Click, doubled: Boolean): Boolean {
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()
        // Mouse buttons are always 0=left, 1=right, 2=middle
        val button = if (click.button().toString().contains("LEFT")) 0 else if (click.button().toString().contains("RIGHT")) 1 else 2
    //?} else {
    /*override fun mouseClicked(mouseX: Double, mouseY: Double, button: Int): Boolean {
        val mouseXInt = mouseX.toInt()
        val mouseYInt = mouseY.toInt()
    *///?}
        
        // Check close button
        val closeButtonSize = 30
        val closeButtonX = width - closeButtonSize - 20
        val closeButtonY = 20
        if (RenderHelper.isMouseOver(mouseXInt, mouseYInt, closeButtonX, closeButtonY, closeButtonSize, closeButtonSize)) {
            close()
            return true
        }
        
        // Check sidebar clicks
        if (mouseXInt < sidebarWidth) {
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
            
            for (widget in widgets) {
                if (widget.mouseClicked(mouseXInt, mouseYInt, button, instance)) {
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
        var currentY = 80 - sidebarScroll.toInt()
        
        for ((index, category) in configManager.structure.categories.withIndex()) {
            val categoryHeight = 40
            val categoryY = currentY
            
            // Check category click
            if (mouseX >= 10 && mouseX <= sidebarWidth - 10 &&
                mouseY >= categoryY && mouseY <= categoryY + categoryHeight) {
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
                    
                    if (mouseX >= 20 && mouseX <= sidebarWidth - 10 &&
                        mouseY >= subY && mouseY <= subY + subHeight) {
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
        
        if (mouseXInt < sidebarWidth) {
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
