package com.soulreturns.util

import com.soulreturns.data.location.LocationApi

/**
 * Backwards-compatible façade. Forwards to [LocationApi].
 *
 * New code should depend on `com.soulreturns.data.location.LocationApi` directly, or subscribe
 * to `AreaChanged` / `SublocationChanged` events from `data.model`.
 */
@Deprecated(
    "Use LocationApi (or subscribe to AreaChanged/SublocationChanged events) instead.",
    ReplaceWith("LocationApi", "com.soulreturns.data.location.LocationApi")
)
object SkyblockLocation {
    val area: String? get() = LocationApi.currentArea
    val sublocation: String? get() = LocationApi.currentSublocation
    fun isInArea(name: String): Boolean = LocationApi.isInArea(name)
    fun isInSublocation(name: String): Boolean = LocationApi.isInSublocation(name)
}
