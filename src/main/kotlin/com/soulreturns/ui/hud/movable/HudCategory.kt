package com.soulreturns.ui.hud.movable

/**
 * A Move GUI tab. The editor shows one category at a time on purpose: HUDs of different categories
 * may share a spot because they never show together (fishing tracker and dragon profit both sit
 * top-left), so they are positioned in separate views.
 *
 * [inventoryBackdrop] is for UI that only shows in inventory screens: the editor then draws the
 * player inventory ([com.soulreturns.features.inventory.InventoryPreview], player model included)
 * exactly where the real inventory opens, and the frame's edges and center become snap targets. A
 * HUD joins such a category through its `categoryId` like any other.
 */
class HudCategory(val id: String, val title: String, val inventoryBackdrop: Boolean = false)

object HudCategories {
    /**
     * Known categories in tab order. Not a closed set: a HUD declaring an unlisted `categoryId` still
     * gets a tab (titled from its id) rather than becoming unreachable in the editor.
     */
    private val known =
        listOf(
            HudCategory("general", "General"),
            HudCategory("dungeons", "Dungeons"),
            HudCategory("mining", "Mining"),
            HudCategory("fishing", "Fishing"),
            HudCategory("combat", "Combat"),
            HudCategory("inventory", "Inventory", inventoryBackdrop = true),
        )

    fun of(id: String): HudCategory =
        known.firstOrNull { it.id == id } ?: HudCategory(id, id.replaceFirstChar { it.uppercaseChar() })

    /** [ids] as categories: known ones in tab order, then the rest alphabetically. */
    fun ordered(ids: Set<String>): List<HudCategory> =
        known.filter { it.id in ids } + ids.filter { id -> known.none { it.id == id } }.sorted().map(::of)
}
