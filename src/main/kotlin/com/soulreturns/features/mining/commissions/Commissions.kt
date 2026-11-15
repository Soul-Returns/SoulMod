package com.soulreturns.features.mining.commissions

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.ProfileChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.ui.SlotMark
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.player.Inventory

/**
 * Commission state for "Highlight completed commissions" and the Commissions HUD.
 *
 * Sources: the tab list's `Commissions:` widget (read through [TabWidgets.lastLines]), else the last
 * read of the `Commissions` menu. The menu fallback shows only in an area where commissions were seen
 * this session (widget or menu), so no closed area list is needed. Commission items are found by name
 * (`Commission #N`), never by slot: players have 2-4 commission slots. Client thread only.
 */
object Commissions {
    private var registered = false

    /** `menu.slots` indices of COMPLETED commissions in [menuScreen]. */
    private var completedSlots: Set<Int> = emptySet()
    private var menuScreen: AbstractContainerScreen<*>? = null

    /** Last commissions read from the menu, in menu order. */
    private var menuRows: List<Commission>? = null

    private val seenAreas = HashSet<String>()

    private var tabLinesRead: List<String>? = null
    private var tabRows: List<Commission>? = null

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register { scanMenu() }
        ScreenEvents.AFTER_INIT.register { _, screen, _, _ ->
            if (screen is AbstractContainerScreen<*>) {
                ScreenEvents.afterBackground(screen).register { _, g, _, _, _ -> highlight(screen, g) }
            }
        }
        Events.subscribe<ProfileChanged> {
            if (it.previous != null) {
                menuRows = null
                seenAreas.clear()
            }
        }
    }

    /** What the HUD lists right now: the tab widget, else the menu read where commissions exist; null = nothing. */
    fun current(): List<Commission>? {
        if (!SkyblockState.onSkyblock) return null
        val tab = tabRows()
        val area = SkyblockLocation.area
        if (!tab.isNullOrEmpty()) {
            if (area != null) seenAreas += area
            return tab
        }
        val menu = menuRows ?: return null
        return if (area != null && area in seenAreas) menu else null
    }

    private fun tabRows(): List<Commission>? {
        val lines = TabWidgets.lastLines
        if (lines !== tabLinesRead) {
            tabLinesRead = lines
            tabRows = CommissionParsing.parseTab(lines)
        }
        return tabRows
    }

    private fun scanMenu() {
        val screen = Minecraft.getInstance().gui.screen() as? AbstractContainerScreen<*>
        val c = cfg.mining.commissions
        if (screen == null || !(c.hud || c.highlightCompleted) || !CommissionParsing.isMenuTitle(screen.title.string)) {
            menuScreen = null
            completedSlots = emptySet()
            return
        }
        val rows = ArrayList<Commission>(4)
        val done = HashSet<Int>(4)
        for ((index, slot) in screen.menu.slots.withIndex()) {
            if (slot.container is Inventory) continue
            val stack = slot.item
            if (stack.isEmpty) continue
            val lore = stack.get(DataComponents.LORE)?.lines()?.map { it.toLegacyText() } ?: continue
            val commission = CommissionParsing.parseMenuItem(stack.hoverName.toLegacyText(), lore) ?: continue
            rows += commission
            if (commission.done) done += index
        }
        menuScreen = screen
        completedSlots = done
        if (rows.isNotEmpty()) {
            menuRows = rows
            SkyblockLocation.area?.let { seenAreas += it }
        }
    }

    private fun highlight(screen: AbstractContainerScreen<*>, g: GuiGraphicsExtractor) {
        if (!cfg.mining.commissions.highlightCompleted || screen !== menuScreen) return
        for (index in completedSlots) {
            val slot = screen.menu.slots.getOrNull(index) ?: continue
            SlotMark.draw(g, screen.leftPos + slot.x, screen.topPos + slot.y, SlotMark.DONE)
        }
    }
}
