package com.soulreturns.features.storage

import me.shedaniel.math.Rectangle
import me.shedaniel.rei.api.client.plugins.REIClientPlugin
import me.shedaniel.rei.api.client.registry.screen.DisplayBoundsProvider
import me.shedaniel.rei.api.client.registry.screen.ExclusionZones
import me.shedaniel.rei.api.client.registry.screen.ExclusionZonesProvider
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry
import net.minecraft.client.gui.screens.Screen

/**
 * Keeps REI off the storage overlay. Loaded only through the `rei_client` entrypoint, i.e. only when
 * REI is installed; nothing else references REI.
 *
 * Exclusion zones alone are not enough: REI sizes its side panels from the screen bounds, which for a
 * container screen are the vanilla chest frame hidden under the wall, and its "recipes not full" notice
 * box at the top of the left panel takes every click inside it on 26.2 (even after Okay, and drawn
 * nowhere here). So the panel is also REI's screen bounds: the side panels, that box and the search bar
 * sit around the overlay. The zones still cover the title pill, which can be wider than the panel.
 */
class StorageReiPlugin : REIClientPlugin {
    private var panel: StorageOverlayLayout.Rect? = null
    private var bounds = Rectangle()
    private var boxes: List<StorageOverlayLayout.Rect>? = null
    private var zones: List<Rectangle> = emptyList()

    override fun registerScreens(registry: ScreenRegistry) {
        registry.registerDecider(object : DisplayBoundsProvider<StorageOverlayScreen> {
            override fun <R : Screen> isHandingScreen(screen: Class<R>) = StorageOverlayScreen::class.java.isAssignableFrom(screen)
            // Ahead of REI's own container-screen bounds.
            override fun getPriority() = 10.0
            override fun getScreenBounds(screen: StorageOverlayScreen): Rectangle? = boundsOf(screen)
        })
    }

    override fun registerExclusionZones(zones: ExclusionZones) {
        zones.register(StorageOverlayScreen::class.java, ExclusionZonesProvider<StorageOverlayScreen> { screen -> zonesOf(screen) })
    }

    /** REI asks several times a frame; the screen hands out new boxes only when one moves. */
    private fun boundsOf(screen: StorageOverlayScreen): Rectangle? {
        val p = screen.guiPanel
        if (p.width <= 0 || p.height <= 0) return null
        if (p !== panel) {
            panel = p
            bounds = Rectangle(p.x, p.y, p.width, p.height)
        }
        return bounds
    }

    private fun zonesOf(screen: StorageOverlayScreen): List<Rectangle> {
        val b = screen.occupiedBoxes
        if (b !== boxes) {
            boxes = b
            zones = b.map { Rectangle(it.x, it.y, it.width, it.height) }
        }
        return zones
    }
}
