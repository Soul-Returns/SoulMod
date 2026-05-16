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
     * Effective "show panel background" for [id]. Per-HUD override wins unconditionally
     * when set: `perHud ?: global`. `null` falls back to the global master toggle; an
     * explicit `true` / `false` always wins regardless of the global value. Used by HUD
     * `Content()` to pick the Surface color, and surfaced to the user via the right-click
     * context menu (which shows a marker when the per-HUD value isn't `null`).
     *
     * To clear all per-HUD overrides at once (e.g. after toggling the global off and
     * wanting it to actually take effect everywhere), use the
     * `Reset per HUD settings (Background)` button under `General → UI` — that path
     * walks every `SoulHudElement` and writes `showBackground = null`.
     */
    fun shouldDrawBackground(id: String): Boolean {
        val element =
            GuiLayoutManager.getLayout().elements.firstOrNull {
                it is SoulHudElement && it.id == id
            } as? SoulHudElement
        element?.showBackground?.let { return it }
        return cfg.general.ui.hudBackground()
    }

    /**
     * Effective "use Minecraft font for this HUD's text" for [id]. Per-HUD wins
     * unconditionally when set — same `perHud ?: global` rule as [shouldDrawBackground].
     * Reset via `General → UI → Reset per HUD settings (Font)`.
     */
    fun shouldUseMinecraftFont(id: String): Boolean {
        val element =
            GuiLayoutManager.getLayout().elements.firstOrNull {
                it is SoulHudElement && it.id == id
            } as? SoulHudElement
        element?.useMinecraftFont?.let { return it }
        return cfg.general.ui.useMinecraftFont()
    }

    /** Effective "draw text shadow" for [id]. Global-wins rule, see [shouldDrawBackground]. */
    fun shouldDrawTextShadow(id: String): Boolean {
        if (!cfg.general.ui.hudTextShadow()) return false
        val element =
            GuiLayoutManager.getLayout().elements.firstOrNull {
                it is SoulHudElement && it.id == id
            } as? SoulHudElement ?: return true
        return element.useTextShadow ?: true
    }

    /**
     * Shadow-thickness multiplier (1.0–4.0) read from the global slider. Global only —
     * no per-HUD override. Only consulted when [shouldDrawTextShadow] is already true.
     */
    fun hudTextShadowSize(): Float = cfg.general.ui.hudTextShadowSize().coerceIn(1f, 4f)

    /** Effective "bump font to heavier weight" for [id]. Global-wins rule, see [shouldDrawBackground]. */
    fun shouldUseBoldFont(id: String): Boolean {
        if (!cfg.general.ui.hudBoldFont()) return false
        val element =
            GuiLayoutManager.getLayout().elements.firstOrNull {
                it is SoulHudElement && it.id == id
            } as? SoulHudElement ?: return true
        return element.useBoldFont ?: true
    }

    /**
     * Base multiplier applied to the user's `cfg.ui.globalScale` slider value before it
     * factors into a HUD's effective scale. The slider's stored range stays 0.5–2.0 (and
     * its default stays 1.0) so the config UX is unchanged, but the *on-screen* range
     * shifts up to ~0.83–3.3 with the default rendering at 1.65×. The earlier 1.0 baseline
     * read too small at common GUI Scale + display-DPI combinations; users were having to
     * hand-bump globalScale or per-HUD scales every install to get readable HUDs.
     *
     * 1.65 = 1.5 × 1.1, i.e. one extra `/soul gui` scroll-wheel click above the previous
     * default. Bumped here because the previous 1.5 still felt slightly small at common
     * setups, and the scroll step (`adjustScale`'s `× 1.1` per click) makes 1.65 the next
     * natural notch. Per-HUD scale = 1.0 (the registered default) plus globalScale = 1.0
     * (the slider default) now produces the same on-screen size that previously required
     * one extra scroll click on each HUD.
     */
    private const val BASE_GLOBAL_SCALE = 1.65f

    /**
     * Effective on-screen scale for a SoulHud element with the given per-element [scale].
     *
     * Effective scale = element scale × [BASE_GLOBAL_SCALE] × `cfg.ui.globalScale` ×
     * optional Minecraft-GUI-scale inverter. When `cfg.ui.respectMinecraftGuiScale` is
     * false, dividing by Minecraft's `window.guiScale` cancels out the implicit scaling
     * Mojang applies to all GUI-coordinate rendering — the panel ends up the same physical
     * pixel size regardless of the user's GUI Scale slider.
     *
     * Public so `/soul gui` (and any future tooling) can compute matching selection bounds.
     */
    fun effectiveScaleFor(scale: Float): Float {
        val uiCfg = cfg.general.ui
        val mcGuiScale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(0.5f)
        val guiScaleCompensation = if (uiCfg.respectMinecraftGuiScale()) 1f else (1f / mcGuiScale)
        return (scale.coerceAtLeast(0.25f) * BASE_GLOBAL_SCALE * uiCfg.globalScale() * guiScaleCompensation)
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
        val useMcFont = shouldUseMinecraftFont(id)

        // When the HUD wants Mojang's font, we need each text glyph drawn via
        // `GuiGraphics.drawString` so resource-pack font overrides apply. The NVG block
        // runs DEFERRED inside Mojang's PIP render pass — we can't queue more
        // `GuiRenderState` commands from there. So we pre-compose the tree synchronously
        // here, walk it to collect each TextNode's screen-space position, then reuse the
        // same tree inside the NVG block (no double compose). The Mojang text dispatch
        // runs AFTER the `NvgFrame.submit` call below — that places the text commands
        // AFTER the PIP command in the GuiRenderState, so Mojang processes them in order:
        // PIP composites the panel + shapes, then text renders on top.
        //
        // `SoulInput.currentHudId` is set during the pre-pass too so `Text.measure` picks
        // up Mojang width / `Text.drawSelf` skips the NVG glyph path — matching exactly
        // what the in-block draw will see.
        val preBuiltTree: com.soulreturns.ui.composer.SoulNode?
        val mojangTexts: List<MojangTextEntry>
        val occlusionsScreen: List<com.soulreturns.ui.composer.ClipRect>
        if (useMcFont) {
            val collected = mutableListOf<MojangTextEntry>()
            val occlusions = mutableListOf<com.soulreturns.ui.composer.ClipRect>()
            // Save / restore the previous values — multiple HUDs render per frame, and other
            // panels' state must not leak into this HUD's pre-pass. `panelWidth/Height` are
            // needed so `MultiSelectDropdownNode.emitOcclusions` → `computePopupY` picks the
            // same open-above-vs-below side the in-block popup draw will use.
            val prevHudId = com.soulreturns.ui.input.SoulInput.currentHudId
            val prevScale = com.soulreturns.ui.input.SoulInput.panelScale
            val prevPanelW = com.soulreturns.ui.input.SoulInput.panelWidth
            val prevPanelH = com.soulreturns.ui.input.SoulInput.panelHeight
            com.soulreturns.ui.input.SoulInput.currentHudId = id
            com.soulreturns.ui.input.SoulInput.panelScale = effectiveScale
            com.soulreturns.ui.input.SoulInput.panelWidth = entry.width.toFloat()
            com.soulreturns.ui.input.SoulInput.panelHeight = entry.height.toFloat()
            val tree =
                try {
                    val built =
                        SoulComposer.create().build {
                            entry.content()
                        }
                    built.measure(
                        com.soulreturns.ui.composer.SoulConstraints(
                            maxWidth = entry.width.toFloat(),
                            maxHeight = entry.height.toFloat(),
                        ),
                    )
                    built.walk(0f, 0f) { node, nx, ny, clip ->
                        // Any node implementing `MojangTextEmitter` can emit one-or-more
                        // pieces of text — `TextNode` emits its single string, `TabsNode`
                        // emits one per tab label, `DropdownNode` emits its trigger label.
                        // Popup option labels in Dropdown go through `SoulInput.queueOverlay`
                        // and aren't reachable from this walker; they remain NVG-rendered.
                        val emitter = node as? com.soulreturns.ui.composer.MojangTextEmitter
                            ?: return@walk
                        // Convert panel-local clip to screen-space — GuiGraphics scissor
                        // coords are in GUI-logical pixels (same coord system GuiGraphics.fill
                        // etc. use).
                        val screenClip =
                            clip?.let {
                                com.soulreturns.ui.composer.ClipRect(
                                    x = x.toFloat() + it.x * effectiveScale,
                                    y = y.toFloat() + it.y * effectiveScale,
                                    width = it.width * effectiveScale,
                                    height = it.height * effectiveScale,
                                )
                            }
                        emitter.emitMojangTexts(nx, ny, clip) { spec ->
                            collected.add(
                                MojangTextEntry(
                                    text = spec.text,
                                    screenX = x.toFloat() + spec.x * effectiveScale,
                                    screenY = y.toFloat() + spec.y * effectiveScale,
                                    size = spec.size,
                                    color = spec.color,
                                    scale = effectiveScale,
                                    clip = screenClip,
                                ),
                            )
                        }
                        // Occlusion bounds — e.g. the open dropdown popup. Convert from
                        // panel-local to screen-space so the dispatcher can filter by
                        // simple containment without re-running the math.
                        emitter.emitOcclusions(nx, ny) { rect ->
                            occlusions.add(
                                com.soulreturns.ui.composer.ClipRect(
                                    x = x.toFloat() + rect.x * effectiveScale,
                                    y = y.toFloat() + rect.y * effectiveScale,
                                    width = rect.width * effectiveScale,
                                    height = rect.height * effectiveScale,
                                ),
                            )
                        }
                    }
                    built
                } finally {
                    com.soulreturns.ui.input.SoulInput.currentHudId = prevHudId
                    com.soulreturns.ui.input.SoulInput.panelScale = prevScale
                    com.soulreturns.ui.input.SoulInput.panelWidth = prevPanelW
                    com.soulreturns.ui.input.SoulInput.panelHeight = prevPanelH
                }
            preBuiltTree = tree
            mojangTexts = collected
            occlusionsScreen = occlusions
        } else {
            preBuiltTree = null
            mojangTexts = emptyList()
            occlusionsScreen = emptyList()
        }

        // NvgFrame.submit handles the PIP-size and nvgScale plumbing; we just pass intrinsic
        // (entry.width, entry.height) and the scale. Composables draw at intrinsic bounds;
        // the transform makes them appear at `effectiveScale` × on screen.
        //
        // The submit lambda is deferred — Mojang's PIP pipeline runs it later, NOT
        // synchronously here — so we capture `id` by closure rather than reading it from a
        // shared mutable. Without that, the measured-size record below would write under a
        // stale id (whichever HUD is "current" at PIP-flush time, which is generally NOT this
        // HUD anymore).
        NvgFrame.submit(context, x, y, entry.width, entry.height, scale = effectiveScale, hudId = id) {
            val root =
                preBuiltTree
                    ?: SoulComposer.create().build {
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

        // Dispatch Mojang text AFTER the PIP submission so text composites on top of the
        // panel's NVG content. `Minecraft.font` is the live font instance — resource-pack
        // overrides are picked up automatically (no caching here).
        if (useMcFont && mojangTexts.isNotEmpty()) {
            val font = Minecraft.getInstance().font
            val withShadow = shouldDrawTextShadow(id)
            val pose = context.pose()
            for (mt in mojangTexts) {
                // Skip any text whose bounding box intersects an occluding region —
                // currently the open dropdown popup. Bbox intersection (not just
                // origin-inside-popup) is required because text isn't always pinned to
                // a corner: a `centerLabel` Button like Reset Session has its label
                // origin to the LEFT of its center, so the origin can sit outside the
                // popup while the text glyphs extend INTO the popup. Origin-only check
                // missed those and the half-overlapped text bled across the popup.
                //
                // Width = `font.width × mcScale` (matches the eventual drawString output);
                // height = `size × panelScale` (Mojang's native 9-pixel cap height × the
                // post-`pose.scale` factor that follows below).
                val mcTextW = font.width(mt.text).toFloat() * (mt.size * mt.scale / MC_NATIVE_LINE_HEIGHT)
                val mcTextH = mt.size * mt.scale
                val textRight = mt.screenX + mcTextW
                val textBottom = mt.screenY + mcTextH
                val occluded =
                    occlusionsScreen.any { r ->
                        textRight > r.x && mt.screenX < r.x + r.width &&
                            textBottom > r.y && mt.screenY < r.y + r.height
                    }
                if (occluded) continue
                // Mojang's text natively renders at ~9-pixel line height. To make a Text
                // composable with `size = N` and panel scale = S occupy roughly the same
                // screen-pixel height as the equivalent NVG draw, scale Mojang's output by
                // `(N × S) / 9` — applied via the GuiGraphics matrix stack around the draw.
                val mcScale = mt.size * mt.scale / MC_NATIVE_LINE_HEIGHT
                // ScrollableList (and any future clipping container) records the panel-
                // local viewport during the walk, which the renderOne pre-pass converted
                // to screen space. Apply it via GuiGraphics' scissor stack — Mojang's
                // text renderer ignores NVG's scissor, so without this rows scrolled past
                // the viewport leak past the panel's bottom edge.
                val clip = mt.clip
                if (clip != null) {
                    context.enableScissor(
                        clip.x.toInt(),
                        clip.y.toInt(),
                        (clip.x + clip.width).toInt(),
                        (clip.y + clip.height).toInt(),
                    )
                }
                pose.pushMatrix()
                pose.translate(mt.screenX, mt.screenY)
                pose.scale(mcScale, mcScale)
                context.drawString(font, mt.text, 0, 0, mt.color, withShadow)
                pose.popMatrix()
                if (clip != null) context.disableScissor()
            }
        }
    }

    /**
     * Snapshot of a `TextNode` collected during `SoulHud.renderOne`'s synchronous pre-pass,
     * carrying everything the post-PIP dispatch needs to call `GuiGraphics.drawString` at
     * the right place + scale. [clip] is the screen-space viewport this text must be
     * clipped to (e.g. inherited from a `ScrollableList` ancestor); null when no clip is
     * active.
     */
    private data class MojangTextEntry(
        val text: String,
        val screenX: Float,
        val screenY: Float,
        val size: Float,
        val color: Int,
        val scale: Float,
        val clip: com.soulreturns.ui.composer.ClipRect?,
    )

    private const val MC_NATIVE_LINE_HEIGHT = 9f

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
