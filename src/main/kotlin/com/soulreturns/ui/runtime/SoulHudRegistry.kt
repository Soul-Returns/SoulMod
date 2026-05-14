package com.soulreturns.ui.runtime

import com.soulreturns.ui.composer.SoulComposable
import java.util.concurrent.ConcurrentHashMap

/**
 * Runtime registry binding a [com.soulreturns.gui.lib.SoulHudElement]'s id to its declared
 * dimensions + composable content.
 *
 * The composable lambda lives in memory only — it can't be serialized to `gui_layout.json`
 * — so features must re-register their HUDs on each launch via [SoulHud.register]. Position
 * and scale persist in the layout file; everything else is rebuilt.
 *
 * Mirrors `TrackerOverlayRegistry` — same pattern, different element type.
 */
object SoulHudRegistry {
    /** Pairs each registered HUD id with `(width, height)` declared at registration time. */
    private val entries: ConcurrentHashMap<String, Entry> = ConcurrentHashMap()

    data class Entry(
        val width: Int,
        val height: Int,
        val defaultAnchorX: Double,
        val defaultAnchorY: Double,
        val defaultOffsetX: Int,
        val defaultOffsetY: Int,
        val defaultScale: Float,
        val content: @SoulComposable () -> Unit,
    )

    fun register(
        id: String,
        width: Int,
        height: Int,
        defaultAnchorX: Double,
        defaultAnchorY: Double,
        defaultOffsetX: Int,
        defaultOffsetY: Int,
        defaultScale: Float,
        content: @SoulComposable () -> Unit,
    ) {
        entries[id] =
            Entry(width, height, defaultAnchorX, defaultAnchorY, defaultOffsetX, defaultOffsetY, defaultScale, content)
    }

    fun all(): Collection<Map.Entry<String, Entry>> = entries.entries

    fun get(id: String): Entry? = entries[id]

    fun ids(): Set<String> = entries.keys.toSet()

    fun unregister(id: String) {
        entries.remove(id)
    }
}

/**
 * Destructuring helper so [GuiEdit] etc. can do `val (w, h) = entry`.
 */
operator fun SoulHudRegistry.Entry.component1(): Int = width

operator fun SoulHudRegistry.Entry.component2(): Int = height
