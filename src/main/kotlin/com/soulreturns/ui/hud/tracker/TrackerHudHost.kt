package com.soulreturns.ui.hud.tracker

import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.movable.HudLayouts
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.client.gui.screens.inventory.InventoryScreen
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What makes a tracker panel usable **inside a container screen** — the half of the legacy HUD
 * runtime that a plain Fabric HUD layer cannot do.
 *
 * ### Why a second render pass, and why at `afterBackground`
 * A HUD layer draws as part of the in-game GUI, which the screen then paints over: its dim
 * background lands on top and the panel goes murky and unreadable exactly when you opened the
 * inventory to read it. So every registered tracker is drawn **again** at `afterBackground`: after
 * the dim, blur and inventory frame, in the stratum before the screen's content. Everything the
 * screen draws after that (slots, carried item, tooltips, overlays like REI's item list, which
 * draws inside `AbstractContainerScreen.extractContents`) paints over the panel where they overlap.
 *
 * ### Why only the player inventory
 * Trackers show on the in-game HUD and over [InventoryScreen], nowhere else. Chests, Hypixel's
 * menus and storage overlays from other mods fill the screen, and a panel painted over them only
 * hides what the player opened the menu for. Soul's own screens, the title screen and the options
 * menus are excluded for the same reason.
 *
 * ### Why clicks are hit-tested against the previous frame
 * The panel records its click regions while it draws. A screen renders every frame and the mouse
 * callbacks fire between frames, so the regions a click tests against are at most one frame old —
 * which is what the legacy runtime did as well, and is imperceptible at any frame rate a person
 * would play at.
 *
 * ### Consuming a click
 * A click that lands on a panel returns false from `allowMouseClick`, which cancels Minecraft's own
 * handling — otherwise clicking a dropdown would also throw the inventory slot underneath it. Scroll
 * and release always return true so hotbar scrolling and drag-release keep working everywhere the
 * panel is not.
 */
object TrackerHudHost {
    private val logger = SoulLogger("Soul/Tracker")

    private val huds = CopyOnWriteArrayList<TrackerHud<*>>()

    /** Cursor in Soul-logical coordinates, or NaN when no screen is feeding us one. */
    @Volatile
    var mouseX: Float = Float.NaN
        private set

    @Volatile
    var mouseY: Float = Float.NaN
        private set

    /** True while the player's own inventory is open — the gate for the tab strip and the footer. */
    @Volatile
    var inventoryOpen: Boolean = false
        private set

    /**
     * True only while [draw] is running. [TrackerHud.isEnabled] reads it to suppress the in-game HUD
     * layer's copy of the panel while this pass owns the frame — see its doc for why drawing both
     * would be worse than useless.
     */
    @Volatile
    var drawingOverlay: Boolean = false
        private set

    private var registered = false

    fun add(hud: TrackerHud<*>) {
        if (huds.none { it.id == hud.id }) huds.add(hud)
    }

    fun register() {
        if (registered) return
        registered = true
        ScreenEvents.AFTER_INIT.register(
            ScreenEvents.AfterInit { _, screen, _, _ ->
                if (screen !is InventoryScreen) return@AfterInit

                ScreenEvents.afterBackground(screen).register(
                    ScreenEvents.AfterBackground { s, g, mouseX, mouseY, _ -> draw(s, g, mouseX, mouseY) },
                )
                ScreenEvents.remove(screen).register(
                    ScreenEvents.Remove { _ ->
                        // The screen is going away: forget the cursor and close any popup, or the
                        // next inventory opens with a stale hover and a popup nobody left open.
                        inventoryOpen = false
                        mouseX = Float.NaN
                        mouseY = Float.NaN
                        huds.forEach {
                            it.closePopups()
                            it.collapse()
                        }
                    },
                )
                ScreenMouseEvents.allowMouseClick(screen).register(
                    ScreenMouseEvents.AllowMouseClick { _, click ->
                        val x = click.x().toFloat() / SoulUi.factor()
                        val y = click.y().toFloat() / SoulUi.factor()
                        // `!consumed`: returning false cancels Minecraft's handling, which is exactly
                        // what a click on the panel must do — otherwise it also hits the slot behind it.
                        !dispatch { it.handleClick(x, y) }
                    },
                )
                ScreenMouseEvents.allowMouseScroll(screen).register(
                    ScreenMouseEvents.AllowMouseScroll { _, mx, my, _, vertical ->
                        val x = mx.toFloat() / SoulUi.factor()
                        val y = my.toFloat() / SoulUi.factor()
                        dispatch { it.handleScroll(x, y, vertical) }
                        // Always true: the wheel still has to reach Minecraft for hotbar scrolling
                        // everywhere the panel is not.
                        true
                    },
                )
            },
        )
    }

    /**
     * Draw every enabled tracker over the screen's background. [mouseX]/[mouseY] arrive in MC-scaled
     * screen pixels; the panels lay out in Soul-logical ones, hence the divide by the global factor.
     */
    private fun draw(
        screen: net.minecraft.client.gui.screens.Screen,
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
    ) {
        inventoryOpen = screen is InventoryScreen
        val factor = SoulUi.factor()
        mouseX = mx / factor
        mouseY = my / factor
        if (Minecraft.getInstance().player == null) return

        drawingOverlay = true
        g.pose().pushMatrix()
        g.pose().scale(factor, factor)
        try {
            HudLayouts.frame {
                for (hud in huds) {
                    try {
                        if (hud.isEnabled()) {
                            if (inventoryOpen) hud.updateHover(mouseX, mouseY)
                            hud.extract(g)
                        }
                    } catch (t: Throwable) {
                        logger.error("Tracker '${hud.id}' threw while drawing over a screen", t)
                    }
                }
            }
        } finally {
            g.pose().popMatrix()
            drawingOverlay = false
        }
    }

    /** Offer the event to each panel, newest registration last, and stop at the first that takes it. */
    private fun dispatch(action: (TrackerHud<*>) -> Boolean): Boolean {
        for (hud in huds.asReversed()) {
            if (!hud.acceptsInput()) continue
            try {
                if (action(hud)) return true
            } catch (t: Throwable) {
                logger.warn("Tracker '${hud.id}' threw while handling input", t)
            }
        }
        return false
    }
}
