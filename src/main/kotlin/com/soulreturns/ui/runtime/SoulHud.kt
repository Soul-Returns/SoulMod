package com.soulreturns.ui.runtime

import com.soulreturns.config.cfg
import com.soulreturns.gui.lib.GuiElement
import com.soulreturns.gui.lib.GuiLayout
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.gui.lib.HudVerticalAnchor
import com.soulreturns.gui.lib.SoulHudElement
import com.soulreturns.platform.render.nvg.NvgFrame
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics

/**
 * Top-level HUD primitive of the Soul UI framework — the bridge between feature code and
 * the per-frame NanoVG render cycle.
 *
 * **Lifecycle.** Features call [register] **once** during `Soul.registerFeatures()`. The
 * registration:
 *  1. Stores the composable + declared dimensions in [SoulHudRegistry].
 *  2. Inserts a [SoulHudElement] into `gui_layout.json` so the HUD becomes positionable via
 *     `/soul gui`. If the user has already moved the HUD in a previous session, that
 *     position is preserved; only the `enabled` flag is overwritten.
 *
 * **Per-frame render.** Once registered, [dispatchAll] (called from `SoulGuiHudAdapter`) walks
 * every registered SoulHud, looks up its current position in the layout, and submits a
 * NanoVG PIP at that position with the composable as content.
 *
 * **Sizing.** The `width` / `height` arguments are the **maximum** the HUD can take. Mojang
 * allocates a PIP texture of exactly that size, so over-sizing wastes GPU memory. Under-
 * sizing clips. Aim for a generous-but-tight bounds — the composable's content can be
 * smaller (the panel hugs content via the framework's measure pass).
 *
 * **Re-composition.** The composable lambda runs every frame. State changes in feature
 * singletons (e.g. `FishingTracker.sessionCatches`) show up automatically on the next
 * frame.
 */
object SoulHud {
    /**
     * Register a composable HUD.
     *
     * @param id Stable per-session id. Used as the [SoulHudElement] id (so layout persists
     *   across launches) and the [SoulHudRegistry] key.
     * @param width Max width of the HUD in logical (GUI-scaled) pixels.
     * @param height Max height of the HUD in logical pixels.
     * @param defaultAnchorX Initial X anchor (`0..1`, fraction of screen width) — only used
     *   on first registration. Subsequent launches preserve the user's `/soul gui` position.
     * @param defaultAnchorY See [defaultAnchorX].
     * @param defaultOffsetX Initial pixel offset from the anchor point.
     * @param defaultOffsetY See [defaultOffsetX].
     * @param defaultScale Initial scale factor. Clamped to `[0.25, 4]` by the layout manager.
     * @param content Composable lambda re-invoked every frame. Must produce exactly one root
     *   composable (typically [com.soulreturns.ui.foundation.Surface] or
     *   [com.soulreturns.ui.foundation.Column]).
     */
    fun register(
        id: String,
        width: Int,
        height: Int,
        defaultAnchorX: Double = 0.02,
        defaultAnchorY: Double = 0.02,
        defaultOffsetX: Int = 0,
        defaultOffsetY: Int = 0,
        defaultScale: Float = 1.0f,
        defaultHorizontalAnchor: HudHorizontalAnchor = HudHorizontalAnchor.Start,
        defaultVerticalAnchor: HudVerticalAnchor = HudVerticalAnchor.Top,
        settingsCategory: String? = null,
        settingsSubcategory: String? = null,
        content: @SoulComposable () -> Unit,
    ) {
        SoulHudRegistry.register(
            id,
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
        GuiLayoutManager.registerElementId(id)
        // Note: we do NOT call ensureLayoutElement here. The layout file may not have been
        // loaded yet (it's loaded later in `Soul.onInitializeClient` via `loadOrInitialize`),
        // and an early ensure would be overwritten by the file load. Instead, [dispatchAll]
        // self-heals the element each frame — same pattern as `FishingTrackerOverlay` /
        // `SeasoningHud` which call their `updateTextBlock` API per tick.
    }

    /**
     * Render every registered SoulHud. Called from `SoulGuiHudAdapter.renderHud` at the HUD
     * pass + Screen-overlay pass.
     *
     * Also self-heals: ensures every registered HUD has a [SoulHudElement] in the layout. If
     * `gui_layout.json` doesn't contain one (first run, file deleted, sync pull, etc.), one
     * is created using the registration defaults. Existing elements' positions / scales are
     * preserved.
     */
    fun dispatchAll(context: GuiGraphics) {
        val window = Minecraft.getInstance().window
        val screenW = window.guiScaledWidth
        val screenH = window.guiScaledHeight

        // Ensure all registered HUDs have a corresponding layout element. Cheap when no-op
        // (element already present); creates a default when missing.
        for ((id, entry) in SoulHudRegistry.all()) {
            ensureLayoutElement(
                id = id,
                defaultAnchorX = entry.defaultAnchorX,
                defaultAnchorY = entry.defaultAnchorY,
                defaultOffsetX = entry.defaultOffsetX,
                defaultOffsetY = entry.defaultOffsetY,
                defaultScale = entry.defaultScale,
                defaultHorizontalAnchor = entry.defaultHorizontalAnchor,
                defaultVerticalAnchor = entry.defaultVerticalAnchor,
            )
        }

        val layout = GuiLayoutManager.getLayout()
        for (element in layout.elements) {
            if (element !is SoulHudElement) continue
            if (!element.enabled) continue
            val entry = SoulHudRegistry.get(element.id) ?: continue
            val baseX = resolveBaseX(element, entry, screenW)
            val baseY = resolveBaseY(element, entry, screenH)
            renderOne(context, element.id, baseX, baseY, entry, element.scale)
        }
    }

    /**
     * Compute the screen-space top-left X for a HUD given its anchor + alignment + offset.
     *
     * `anchorX * screenW` is the pixel the user wants to "pin" along the horizontal axis.
     * `horizontalAnchor` then says which edge of the HUD that pin grabs:
     *  - `Start`: the HUD's left edge.
     *  - `Center`: the HUD's horizontal center — pin is at `(left + width/2)`.
     *  - `End`: the HUD's right edge — pin is at `(left + width)`.
     *
     * The shift is computed from the **on-screen** size (measured intrinsic × effective scale)
     * so a Center-anchored HUD sits dead-center regardless of how the content composes or
     * how the user's GUI scale is configured. Falls back to the registered max bound when no
     * frame has rendered yet (e.g. first frame after a fresh launch with a gated HUD).
     */
    fun resolveBaseX(
        element: SoulHudElement,
        entry: SoulHudRegistry.Entry,
        screenW: Int,
    ): Int {
        val anchorPx = element.anchorX * screenW
        val measured = SoulHudRegistry.lastMeasured(element.id)
        val intrinsicW = measured?.width ?: entry.width.toFloat()
        val effectiveScale = effectiveScaleFor(element.scale)
        val onScreenW = intrinsicW * effectiveScale
        val shift =
            when (element.horizontalAnchor) {
                HudHorizontalAnchor.Start -> 0f
                HudHorizontalAnchor.Center -> onScreenW / 2f
                HudHorizontalAnchor.End -> onScreenW
            }
        return (anchorPx - shift).toInt() + element.offsetX
    }

    /** Vertical analog of [resolveBaseX]. */
    fun resolveBaseY(
        element: SoulHudElement,
        entry: SoulHudRegistry.Entry,
        screenH: Int,
    ): Int {
        val anchorPy = element.anchorY * screenH
        val measured = SoulHudRegistry.lastMeasured(element.id)
        val intrinsicH = measured?.height ?: entry.height.toFloat()
        val effectiveScale = effectiveScaleFor(element.scale)
        val onScreenH = intrinsicH * effectiveScale
        val shift =
            when (element.verticalAnchor) {
                HudVerticalAnchor.Top -> 0f
                HudVerticalAnchor.Center -> onScreenH / 2f
                HudVerticalAnchor.Bottom -> onScreenH
            }
        return (anchorPy - shift).toInt() + element.offsetY
    }

    /**
     * Effective on-screen scale for a SoulHud element with the given per-element [scale].
     *
     * Effective scale = element scale × `cfg.ui.globalScale` × optional Minecraft-GUI-scale
     * inverter. When `cfg.ui.respectMinecraftGuiScale` is false, dividing by Minecraft's
     * `window.guiScale` cancels out the implicit scaling Mojang applies to all
     * GUI-coordinate rendering — the panel ends up the same physical pixel size regardless
     * of the user's GUI Scale slider.
     *
     * Public so `/soul gui` (and any future tooling) can compute matching selection bounds.
     */
    fun effectiveScaleFor(scale: Float): Float {
        val uiCfg = cfg.general.ui
        val mcGuiScale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(0.5f)
        val guiScaleCompensation = if (uiCfg.respectMinecraftGuiScale()) 1f else (1f / mcGuiScale)
        return (scale.coerceAtLeast(0.25f) * uiCfg.globalScale() * guiScaleCompensation)
            .coerceAtLeast(0.05f)
    }

    private fun renderOne(
        context: GuiGraphics,
        id: String,
        x: Int,
        y: Int,
        entry: SoulHudRegistry.Entry,
        scale: Float,
    ) {
        val effectiveScale = effectiveScaleFor(scale)

        // NvgFrame.submit handles the PIP-size and nvgScale plumbing; we just pass intrinsic
        // (entry.width, entry.height) and the scale. Composables draw at intrinsic bounds;
        // the transform makes them appear at `effectiveScale` × on screen.
        //
        // The submit lambda is deferred — Mojang's PIP pipeline runs it later, NOT
        // synchronously here — so we capture `id` by closure rather than reading it from a
        // shared mutable. Without that, the measured-size record below would write under a
        // stale id (whichever HUD is "current" at PIP-flush time, which is generally NOT this
        // HUD anymore).
        NvgFrame.submit(context, x, y, entry.width, entry.height, scale = effectiveScale) {
            val root =
                SoulComposer.create().build {
                    entry.content()
                }
            root.draw(
                0f,
                0f,
                SoulConstraints(maxWidth = entry.width.toFloat(), maxHeight = entry.height.toFloat()),
            )
            // Run any draw lambdas widgets queued via `SoulInput.queueOverlay` (e.g. an
            // expanded dropdown popup) — they paint after every sibling so they always
            // appear on top.
            com.soulreturns.ui.input.SoulInput.flushOverlays()
            // Capture the actual rendered size for /soul gui's selection-box geometry —
            // the registered (width, height) is a max bound; most HUDs render smaller.
            root.measured?.let { m -> SoulHudRegistry.recordMeasured(id, m.width, m.height) }
        }
    }

    private fun ensureLayoutElement(
        id: String,
        defaultAnchorX: Double,
        defaultAnchorY: Double,
        defaultOffsetX: Int,
        defaultOffsetY: Int,
        defaultScale: Float,
        defaultHorizontalAnchor: HudHorizontalAnchor,
        defaultVerticalAnchor: HudVerticalAnchor,
    ) {
        val current = GuiLayoutManager.getLayout()
        // Fast path: already present + correct type → nothing to do, preserve user edits.
        if (current.elements.any { it is SoulHudElement && it.id == id }) return

        var existing: SoulHudElement? = null
        val others = mutableListOf<GuiElement>()
        for (element in current.elements) {
            if (element.id == id) {
                if (element is SoulHudElement) existing = element
                // Otherwise drop — element id is identity; stale type with same id evicted.
            } else {
                others += element
            }
        }
        val updated =
            existing
                ?: SoulHudElement(
                    id = id,
                    enabled = true,
                    anchorX = defaultAnchorX,
                    anchorY = defaultAnchorY,
                    offsetX = defaultOffsetX,
                    offsetY = defaultOffsetY,
                    scale = defaultScale,
                    horizontalAnchor = defaultHorizontalAnchor,
                    verticalAnchor = defaultVerticalAnchor,
                )
        others += updated
        GuiLayoutManager.setLayout(GuiLayout(others))
    }
}
