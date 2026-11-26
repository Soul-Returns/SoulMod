package com.soulreturns.features.inventory

import com.soulreturns.features.inventorybuttons.InventoryButtons
import me.shedaniel.math.Rectangle
import me.shedaniel.rei.api.client.plugins.REIClientPlugin
import me.shedaniel.rei.api.client.registry.screen.ClickArea
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones
import me.shedaniel.rei.api.client.registry.screen.ExclusionZonesProvider
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen

/**
 * Keeps REI from acting through what Soul draws on container screens. Loaded only through the
 * `rei_client` entrypoint, i.e. only when REI is installed; nothing else references REI.
 *
 * - REI's crafting-arrow click area (137, 29 in the player inventory: "View Recipes for Crafting", a
 *   click opens the crafting recipes) lies under the hidden crafting grid and the SkyBlock info display.
 *   REI's API cannot veto another plugin's click area and click areas ignore exclusion zones, so this adds
 *   one more for [InventoryScreen]: where Soul covers the spot it succeeds with an executor that takes the
 *   click and does nothing. REI runs the executors before opening any category, so the recipes never
 *   open. Its tooltip is dropped by `GuiGraphicsExtractorMixin` like any other tooltip over a covered
 *   spot. Uncovered (feature off, hides off, any other screen) it fails and REI works as usual.
 * - Inventory buttons and the equipment display sit beside the frame, where REI lays out its item list,
 *   which would draw over them and take their hovers and clicks: they are exclusion zones.
 */
class InventoryReiPlugin : REIClientPlugin {
    private var buttons: List<IntArray>? = null
    private var equipment: IntArray? = null
    private var zones: List<Rectangle> = emptyList()

    override fun registerScreens(registry: ScreenRegistry) {
        registry.registerClickArea(InventoryScreen::class.java, ClickArea<InventoryScreen> { context ->
            val mouse = context.mousePosition
            if (InventoryCleanup.blocksForeignClick(mouse.x.toDouble(), mouse.y.toDouble())) {
                ClickArea.Result.success().executor { true }
            } else {
                ClickArea.Result.fail()
            }
        })
    }

    override fun registerExclusionZones(zones: ExclusionZones) {
        zones.register(AbstractContainerScreen::class.java, ExclusionZonesProvider<AbstractContainerScreen<*>> { screen -> zonesOf(screen) })
    }

    /** REI asks several times a frame; both sources hand out new rects only when one moves. */
    private fun zonesOf(screen: AbstractContainerScreen<*>): List<Rectangle> {
        val b = InventoryButtons.drawnBoxes(screen)
        val e = (screen as? InventoryScreen)?.let(EquipmentDisplay::drawnRect)
        if (b !== buttons || e !== equipment) {
            buttons = b
            equipment = e
            zones = b.map { Rectangle(it[0], it[1], it[2], it[3]) } + listOfNotNull(e?.let { Rectangle(it[0], it[1], it[2] - it[0], it[3] - it[1]) })
        }
        return zones
    }
}
