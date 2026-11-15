package com.soulreturns.features.inventory

import com.soulreturns.config.cfg
import com.soulreturns.features.darkui.DarkUi
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.InventoryScreen

/**
 * Paints over the parts of the player inventory that the "hide" toggles remove. The slots
 * themselves are switched off by `SlotMixin` (an inactive slot is neither drawn nor clickable), and
 * the "Crafting" label by `InventoryScreenMixin`; what is left is their frames, which are baked into
 * the inventory texture, so they are covered with the texture's own background color.
 *
 * Registered before the inventory buttons so a button placed there still draws on top.
 */
object InventoryCleanup {
    private val BACKGROUND = 0xFFC6C6C6.toInt()

    /**
     * The space right of the player model and above the main rows, in inventory-frame pixels
     * (x0, y0, x1, y1). Empty only with the crafting grid, the recipe book (its button sits at
     * 104, 61) and the off-hand slot all hidden; [InventoryInfo] draws here.
     */
    val FREE_AREA = intArrayOf(77, 4, 172, 81)

    /** Frame rects (x0, y0, x1, y1, exclusive) in inventory-frame pixels that the hide toggles paint over. */
    private val OFFHAND_FRAME = intArrayOf(76, 61, 94, 79)
    private val CRAFTING_FRAME = intArrayOf(97, 17, 171, 53)

    /** The recipe book button's spot (x0, y0, x1, y1) in inventory-frame pixels. */
    private val RECIPE_BUTTON = intArrayOf(104, 61, 124, 79)

    /** True while Soul itself sets a tooltip, so [hidesVanillaTooltip] lets it through (render thread). */
    private var drawingOwnTooltip = false

    /** Runs [block], which sets one of Soul's own tooltips (inside the hidden areas too). */
    inline fun ownTooltip(block: () -> Unit) {
        beginOwnTooltip()
        try {
            block()
        } finally {
            endOwnTooltip()
        }
    }

    @PublishedApi internal fun beginOwnTooltip() { drawingOwnTooltip = true }

    @PublishedApi internal fun endOwnTooltip() { drawingOwnTooltip = false }

    /**
     * Whether a tooltip someone other than Soul asks for at ([mouseX], [mouseY]) must be dropped: the
     * player inventory is open and the pointer is over the frame of an element hidden by its toggle
     * (crafting grid + result, off-hand slot, recipe book button), whatever the tooltip's source
     * (a hidden slot's item, the button, another mod). Called by `GuiGraphicsExtractorMixin`.
     */
    fun hidesVanillaTooltip(mouseX: Int, mouseY: Int): Boolean {
        if (drawingOwnTooltip) return false
        val screen = Minecraft.getInstance().gui.screen() as? InventoryScreen ?: return false
        val x = mouseX - screen.leftPos
        val y = mouseY - screen.topPos
        fun inside(r: IntArray) = x >= r[0] && x < r[2] && y >= r[1] && y < r[3]
        val general = cfg.general
        return (general.craftingGridHidden && inside(CRAFTING_FRAME)) ||
            (general.offhandSlotHidden && inside(OFFHAND_FRAME)) ||
            (general.recipeBookHidden && inside(RECIPE_BUTTON))
    }

    fun register() {
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is InventoryScreen) return@AfterInit
                InventoryInfo.onInventoryOpened()
                ScreenEvents.afterBackground(screen).register(
                    ScreenEvents.AfterBackground { _, g, mouseX, mouseY, _ -> draw(g, screen.leftPos, screen.topPos, preview = false, mouseX = mouseX, mouseY = mouseY) },
                )
                // A left click on the info display's "Profile" link opens /spv and is consumed.
                ScreenMouseEvents.allowMouseClick(screen).register(
                    ScreenMouseEvents.AllowMouseClick { _, click ->
                        val carrying = Minecraft.getInstance().player?.containerMenu?.carried?.isEmpty == false
                        !(click.button() == 0 && !carrying && InventoryInfo.active() && InventoryInfo.click(click.x(), click.y()))
                    },
                )
            },
        )
    }

    /**
     * Cover the frames of whatever is hidden, then the info display, for an inventory frame at
     * ([left], [top]). [preview] lets the info display fall back to sample data; [forceInfo] draws
     * the info display (and the three hides it needs) whatever the config says, for its own editor.
     * The mouse position, in the same coordinates, drives the info display's hover tooltip.
     */
    fun draw(g: GuiGraphicsExtractor, left: Int, top: Int, preview: Boolean, forceInfo: Boolean = false, mouseX: Int = -1, mouseY: Int = -1) {
        val general = cfg.general
        val background = if (DarkUi.active) DarkUi.BACKGROUND else BACKGROUND
        // The off-hand slot frame.
        if (forceInfo || general.offhandSlotHidden) g.fill(left + OFFHAND_FRAME[0], top + OFFHAND_FRAME[1], left + OFFHAND_FRAME[2], top + OFFHAND_FRAME[3], background)
        // Grid frames, arrow and the larger result frame: measured from the texture as x 97..170, y 17..52.
        if (forceInfo || general.craftingGridHidden) g.fill(left + CRAFTING_FRAME[0], top + CRAFTING_FRAME[1], left + CRAFTING_FRAME[2], top + CRAFTING_FRAME[3], background)
        if (forceInfo || InventoryInfo.active()) InventoryInfo.draw(g, left, top, preview, mouseX, mouseY)
    }
}
