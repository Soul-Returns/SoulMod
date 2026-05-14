package com.soulreturns.gui.lib.tracker

import com.soulreturns.gui.lib.GuiHitRegion
import com.soulreturns.gui.lib.GuiInteractionSnapshot

/**
 * Routes clicks + scroll events for tracker overlays.
 *
 * Inputs are dispatched here from [com.soulreturns.platform.mixinbridge.SoulGuiHudAdapter] which
 * wires Fabric's `ScreenMouseEvents` callbacks. Hit regions are produced by
 * [TrackerOverlayRenderer] and live on the latest [GuiInteractionSnapshot].
 *
 * All mutations route through [TrackerSettingsStore.markDirty] so the periodic save catches
 * up — no synchronous file I/O on the click thread.
 */
object TrackerInputHandler {
    /**
     * Try to consume a click at ([x], [y]). Returns true if a tracker hit-region matched.
     * Called from [com.soulreturns.gui.lib.GuiInteractionHandler.handleClick] when the region's
     * kind is one of the `TRACKER_OVERLAY_*` variants.
     */
    fun handleClick(region: GuiHitRegion): Boolean {
        val overlay = TrackerOverlayRegistry.get(region.elementId) ?: return false
        val settings = TrackerSettingsStore.getOrCreate(overlay.id, overlay.defaults)
        return when (region.kind) {
            GuiHitRegion.Kind.TRACKER_OVERLAY_TAB -> {
                val tabName = region.payload ?: return false
                if (settings.activeTab != tabName) {
                    settings.activeTab = tabName
                    settings.scrollOffset = 0
                    TrackerSettingsStore.markDirty()
                }
                true
            }
            GuiHitRegion.Kind.TRACKER_OVERLAY_CYCLE_SORT -> {
                val keys = overlay.sortOptions.map { it.key }
                val nextIdx = (keys.indexOf(settings.sortKey).coerceAtLeast(0) + 1) % keys.size
                settings.sortKey = keys[nextIdx]
                settings.scrollOffset = 0
                TrackerSettingsStore.markDirty()
                true
            }
            GuiHitRegion.Kind.TRACKER_OVERLAY_CYCLE_LIMIT -> {
                val opts = overlay.limitOptions
                val nextIdx = (opts.indexOf(settings.limit).coerceAtLeast(0) + 1) % opts.size
                settings.limit = opts[nextIdx]
                settings.scrollOffset = 0
                TrackerSettingsStore.markDirty()
                true
            }
            GuiHitRegion.Kind.TRACKER_OVERLAY_RESET -> {
                overlay.onReset?.invoke()
                settings.scrollOffset = 0
                TrackerSettingsStore.markDirty()
                true
            }
            GuiHitRegion.Kind.TRACKER_OVERLAY_SCROLL_REGION -> false // not a click handler
            else -> false
        }
    }

    /**
     * Try to consume a scroll event at cursor ([mouseX], [mouseY]).
     *
     * [verticalDelta] is the wheel delta (+1 per notch up, -1 per notch down) from Fabric's
     * `ScreenMouseEvents.allowMouseScroll`. Returns true if a tracker's scroll region was hit
     * and the scroll consumed.
     */
    fun handleScroll(
        snapshot: GuiInteractionSnapshot,
        mouseX: Int,
        mouseY: Int,
        verticalDelta: Double,
    ): Boolean {
        if (verticalDelta == 0.0) return false
        val region =
            snapshot.hitRegions.firstOrNull {
                it.kind == GuiHitRegion.Kind.TRACKER_OVERLAY_SCROLL_REGION && it.contains(mouseX, mouseY)
            } ?: return false
        val overlay = TrackerOverlayRegistry.get(region.elementId) ?: return false
        val settings = TrackerSettingsStore.getOrCreate(overlay.id, overlay.defaults)
        // Wheel-up (positive delta) scrolls toward older entries → reduce scrollOffset.
        // Wheel-down (negative delta) reveals lower entries → increase scrollOffset.
        val step = if (verticalDelta > 0) -1 else 1
        val newOffset = (settings.scrollOffset + step).coerceAtLeast(0)
        if (newOffset != settings.scrollOffset) {
            settings.scrollOffset = newOffset
            TrackerSettingsStore.markDirty()
        }
        return true
    }
}
