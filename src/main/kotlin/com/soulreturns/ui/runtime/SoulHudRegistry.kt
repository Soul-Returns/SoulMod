package com.soulreturns.ui.runtime

import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
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

    /**
     * Most-recent measured size of each HUD's composed root, in unscaled content pixels.
     * Updated each frame from `SoulHud.renderOne` after layout completes. Used by
     * `/soul gui`'s selection-box geometry so the click-target tracks the actual rendered
     * panel rather than the maximum registered bounds (the panel typically renders smaller
     * because Surface hugs its content).
     */
    private val measured: ConcurrentHashMap<String, MeasuredSize> = ConcurrentHashMap()

    data class MeasuredSize(val width: Float, val height: Float)

    data class Entry(
        val width: Int,
        val height: Int,
        val defaultAnchorX: Double,
        val defaultAnchorY: Double,
        val defaultOffsetX: Int,
        val defaultOffsetY: Int,
        val defaultScale: Float,
        val defaultHorizontalAnchor: HudHorizontalAnchor,
        val defaultVerticalAnchor: HudVerticalAnchor,
        /** Optional deep-link target for the "Settings" entry in `/soul gui`'s right-click menu. */
        val settingsCategory: String?,
        val settingsSubcategory: String?,
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
        defaultHorizontalAnchor: HudHorizontalAnchor,
        defaultVerticalAnchor: HudVerticalAnchor,
        settingsCategory: String?,
        settingsSubcategory: String?,
        content: @SoulComposable () -> Unit,
    ) {
        entries[id] =
            Entry(
                width,
                height,
                defaultAnchorX,
                defaultAnchorY,
                defaultOffsetX,
                defaultOffsetY,
                defaultScale,
                defaultHorizontalAnchor,
                defaultVerticalAnchor,
                settingsCategory,
                settingsSubcategory,
                content,
            )
    }

    fun all(): Collection<Map.Entry<String, Entry>> = entries.entries

    fun get(id: String): Entry? = entries[id]

    fun ids(): Set<String> = entries.keys.toSet()

    fun unregister(id: String) {
        entries.remove(id)
        measured.remove(id)
    }

    /** Called from `SoulHud.renderOne` after each frame's composition + layout. */
    fun recordMeasured(
        id: String,
        width: Float,
        height: Float,
    ) {
        measured[id] = MeasuredSize(width, height)
    }

    /** Latest measured size if the HUD has rendered at least once this session; null otherwise. */
    fun lastMeasured(id: String): MeasuredSize? = measured[id]
}

/**
 * Destructuring helper so [GuiEdit] etc. can do `val (w, h) = entry`.
 */
operator fun SoulHudRegistry.Entry.component1(): Int = width

operator fun SoulHudRegistry.Entry.component2(): Int = height
