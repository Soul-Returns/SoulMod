package com.soulreturns.gui.lib.tracker

import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime registry binding a [com.soulreturns.gui.lib.TrackerOverlayElement]'s id to its
 * [TrackerOverlay] descriptor.
 *
 * The element in `gui_layout.json` persists only position/scale/enabled — the overlay's tabs,
 * sort options, and data providers are runtime state, owned by feature code. Features register
 * their overlay here (typically from a `register()` function called from `Soul.registerFeatures`),
 * and the renderer/input handler look up by id.
 */
object TrackerOverlayRegistry {
    private val overlays: ConcurrentHashMap<String, TrackerOverlay> = ConcurrentHashMap()

    fun register(overlay: TrackerOverlay) {
        overlays[overlay.id] = overlay
    }

    fun get(id: String): TrackerOverlay? = overlays[id]

    fun unregister(id: String) {
        overlays.remove(id)
    }
}
