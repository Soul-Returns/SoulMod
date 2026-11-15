package com.soulreturns.features.islandchests

import kotlin.math.min

internal object IslandChestLayout {
    data class Panel(val x: Float, val y: Float, val width: Float, val height: Float) {
        val compact: Boolean get() = width < 660f
    }

    fun panel(width: Float, height: Float): Panel {
        val w = min(900f, (width - 48f).coerceAtLeast(1f))
        val h = min(610f, (height - 48f).coerceAtLeast(1f))
        return Panel((width - w) / 2f, (height - h) / 2f, w, h)
    }

    fun contentHeight(panelHeight: Int): Int = maxOf(212, panelHeight)

    fun hasVisibleArea(x: Int, y: Int, right: Int, bottom: Int, clip: IntArray): Boolean =
        maxOf(x, clip[0]) < minOf(right, clip[2]) && maxOf(y, clip[1]) < minOf(bottom, clip[3])

    fun canLocate(enabled: Boolean, ownIsland: Boolean, profile: String?, locatingProfile: String?): Boolean =
        enabled && ownIsland && profile != null && profile == locatingProfile

    fun age(observedAt: Long, now: Long): String {
        val seconds = ((now - observedAt).coerceAtLeast(0L) / 1000L)
        return when {
            seconds < 60 -> "just now"
            seconds < 3600 -> "${seconds / 60} min ago"
            seconds < 86400 -> "${seconds / 3600} hr ago"
            else -> "${seconds / 86400} days ago"
        }
    }
}
