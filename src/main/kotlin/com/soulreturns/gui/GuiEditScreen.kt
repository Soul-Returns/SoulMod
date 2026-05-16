package com.soulreturns.gui

import com.soulreturns.gui.lib.EditState
import com.soulreturns.gui.lib.GuiEditSession
import com.soulreturns.gui.lib.GuiLayoutManager
import com.soulreturns.gui.lib.GuiRenderer
import com.soulreturns.platform.render.nvg.NvgFrame
import com.soulreturns.ui.composer.Arrangement
import com.soulreturns.ui.composer.HorizontalAlignment
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.composer.SoulComposer
import com.soulreturns.ui.composer.SoulConstraints
import com.soulreturns.ui.composer.SoulModifier
import com.soulreturns.ui.composer.VerticalAlignment
import com.soulreturns.ui.composer.background
import com.soulreturns.ui.composer.clickable
import com.soulreturns.ui.composer.fillMaxSize
import com.soulreturns.ui.composer.fillMaxWidth
import com.soulreturns.ui.composer.padding
import com.soulreturns.ui.composer.width
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Button
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Row
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.input.SoulInput
import com.soulreturns.ui.runtime.SoulScreen
import com.soulreturns.ui.theme.SoulTheme
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphics
import net.minecraft.client.gui.screens.Screen
import net.minecraft.network.chat.Component

/**
 * Edit GUI screen opened via "/soul gui". Allows moving and scaling GUI
 * elements defined by the GUI library.
 */
class GuiEditScreen : Screen(Component.literal("Edit GUI")) {
    private companion object {
        // Top-right action button strip — rendered via a small NanoVG submit so the buttons
        // share Soul UI's visual language (rounded, hover-aware) instead of the previous
        // vanilla-rectangle Reset button. Sized to fit a `?` icon button + the `Reset HUD`
        // label button at body-text size with the default Button padding.
        const val BTN_PANEL_W = 140
        const val BTN_PANEL_H = 32
        const val BTN_PANEL_MARGIN = 6

        // Right-click context-menu geometry. The anchor-grid section sits above the per-HUD
        // toggle list; its height is fixed regardless of element so the menu doesn't reflow
        // between targets. 16:9-ish screen rect = `(menuWidth − 2·GRID_MARGIN_H) × (gridH − 2·GRID_MARGIN_V)`.
        const val CTX_MENU_WIDTH = 160
        const val CTX_ITEM_HEIGHT = 14
        const val CTX_GRID_HEIGHT = 88
        const val GRID_MARGIN_H = 16
        const val GRID_MARGIN_V = 8
        const val DOT_INSET = 5
    }

    private var editState: EditState = EditState()

    private data class ElementBounds(
        val id: String,
        val x: Int,
        val y: Int,
        val width: Int,
        val height: Int,
    )

    private var elementBounds: List<ElementBounds> = emptyList()

    /**
     * When true, render a modal help dialog overlay explaining how to use the editor and
     * how anchors work. Toggled by the `?` button at the top-right; dismissed via the
     * dialog's `Got it` button, by clicking the dimmed scrim, or by pressing Esc.
     */
    @Volatile private var showHelpDialog: Boolean = false

    /**
     * Open context menu state. Non-null while the user has right-clicked a SoulHudElement
     * to bring up the anchor picker + per-HUD toggles. Cleared on selection, outside-click,
     * or escape.
     *
     * [gridHeight] is the height of the top "anchor grid" section (a 3×3 grid of dots on a
     * screen-shaped rectangle). Action items (per-HUD toggles + Settings) are stacked below
     * it at [itemHeight] each.
     */
    private data class ContextMenu(
        val elementId: String,
        val originX: Int,
        val originY: Int,
        val itemHeight: Int,
        val width: Int,
        val gridHeight: Int,
    )

    private var contextMenu: ContextMenu? = null

    /** Per-HUD toggle / link row in the right-click context menu (no anchor presets). */
    private data class ContextMenuItem(
        val label: String,
        val action: (elementId: String) -> Unit,
    )

    private data class AnchorPreset(
        val anchorX: Double,
        val anchorY: Double,
        val horizontalAnchor: com.soulreturns.gui.lib.HudHorizontalAnchor,
        val verticalAnchor: com.soulreturns.gui.lib.HudVerticalAnchor,
    )

    /**
     * Compute the 9 anchor presets row-major (top→bottom, left→right). The list always has
     * the same shape; the visual grid maps `index = row × 3 + col` and the same indexing is
     * used by hit-testing.
     */
    private fun anchorGridPresets(): List<AnchorPreset> {
        val hAnchors =
            listOf(
                com.soulreturns.gui.lib.HudHorizontalAnchor.Start,
                com.soulreturns.gui.lib.HudHorizontalAnchor.Center,
                com.soulreturns.gui.lib.HudHorizontalAnchor.End,
            )
        val vAnchors =
            listOf(
                com.soulreturns.gui.lib.HudVerticalAnchor.Top,
                com.soulreturns.gui.lib.HudVerticalAnchor.Center,
                com.soulreturns.gui.lib.HudVerticalAnchor.Bottom,
            )
        val fracs = listOf(0.0, 0.5, 1.0)
        return buildList {
            for (row in 0..2) {
                for (col in 0..2) {
                    add(AnchorPreset(fracs[col], fracs[row], hAnchors[col], vAnchors[row]))
                }
            }
        }
    }

    /**
     * Build the per-HUD toggle / link rows for the right-click context menu. Re-evaluated on
     * every draw / hit-test so the toggle labels ("HUD Background: ON/OFF" etc.) reflect the
     * element's current effective state (global gate AND per-HUD override).
     */
    private fun buildContextMenuItems(elementId: String): List<ContextMenuItem> {
        val bgEffective = com.soulreturns.ui.runtime.SoulHud.shouldDrawBackground(elementId)
        val mcFontEffective = com.soulreturns.ui.runtime.SoulHud.shouldUseMinecraftFont(elementId)
        val shadowEffective = com.soulreturns.ui.runtime.SoulHud.shouldDrawTextShadow(elementId)
        val boldEffective = com.soulreturns.ui.runtime.SoulHud.shouldUseBoldFont(elementId)

        return listOf(
            ContextMenuItem(
                label = "HUD Background: ${if (bgEffective) "ON" else "OFF"}",
                action = { id -> toggleHudBackground(id) },
            ),
            ContextMenuItem(
                label = "Use Minecraft Font: ${if (mcFontEffective) "ON" else "OFF"}",
                action = { id -> toggleUseMinecraftFont(id) },
            ),
            ContextMenuItem(
                label = "Text Shadow: ${if (shadowEffective) "ON" else "OFF"}",
                action = { id -> toggleUseTextShadow(id) },
            ),
            ContextMenuItem(
                label = "Bold Font: ${if (boldEffective) "ON" else "OFF"}",
                action = { id -> toggleUseBoldFont(id) },
            ),
            ContextMenuItem(label = "Settings", action = ::openSettingsFor),
        )
    }

    /**
     * Flip the per-HUD `showBackground` override. Reads the current per-HUD value
     * (defaulting `null` → `true`), inverts it, and writes back the explicit `Boolean`.
     * The global `cfg.general.ui.hudBackground` still has to be on for any value here
     * to actually show a backdrop — when global is off, this just records the preference
     * for when global flips back on.
     */
    private fun toggleHudBackground(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.showBackground ?: true
        GuiLayoutManager.updateSoulHudShowBackground(elementId, !current)
    }

    /** Per-HUD analog of [toggleHudBackground] for the Minecraft font override. */
    private fun toggleUseMinecraftFont(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.useMinecraftFont ?: true
        GuiLayoutManager.updateSoulHudUseMinecraftFont(elementId, !current)
    }

    /** Per-HUD analog of [toggleHudBackground] for the text-shadow override. */
    private fun toggleUseTextShadow(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.useTextShadow ?: true
        GuiLayoutManager.updateSoulHudUseTextShadow(elementId, !current)
    }

    /** Per-HUD analog of [toggleHudBackground] for the bold-font override. */
    private fun toggleUseBoldFont(elementId: String) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement ?: return
        val current = element.useBoldFont ?: true
        GuiLayoutManager.updateSoulHudUseBoldFont(elementId, !current)
    }

    /**
     * Open the Soul config screen jumped to the deep-link target the HUD registered. If
     * the HUD didn't supply a target (or its category isn't in the live config), the
     * screen falls back to its first visible category — so the user still lands somewhere
     * useful instead of an empty body.
     */
    private fun openSettingsFor(elementId: String) {
        val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(elementId)
        val screen =
            com.soulreturns.config.gui.SoulConfigScreen(
                initialCategory = entry?.settingsCategory,
                initialSubcategory = entry?.settingsSubcategory,
            )
        Minecraft.getInstance().setScreen(screen)
    }

    /**
     * Lightweight context used for drag updates, where we only
     * need access to the screen dimensions and not actual rendering.
     */
    private class EditHitTestContext(private val client: Minecraft) : com.soulreturns.gui.lib.GuiRenderContext {
        override val screenWidth: Int
            get() = client.window.guiScaledWidth

        override val screenHeight: Int
            get() = client.window.guiScaledHeight

        override fun drawText(
            text: String,
            x: Int,
            y: Int,
            color: Int,
            shadow: Boolean
        ) {
            // no-op for hit testing
        }

        override fun drawScaledText(
            text: String,
            x: Int,
            y: Int,
            color: Int,
            shadow: Boolean,
            scale: Float,
        ) {
            // no-op for hit testing
        }

        override fun fillRect(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            color: Int
        ) {
            // no-op for hit testing
        }

        override fun fillRoundedRect(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
            color: Int,
            radius: Float,
        ) {
            // no-op for hit testing
        }

        override fun textWidth(text: String): Int = client.font.width(text)

        override val textLineHeight: Int
            get() = client.font.lineHeight

        override fun pushScissor(
            x: Int,
            y: Int,
            width: Int,
            height: Int,
        ) {
            // no-op for hit testing
        }

        override fun popScissor() {
            // no-op for hit testing
        }

        override fun drawItemIcon(
            iconKey: String,
            x: Int,
            y: Int
        ) {
            // no-op for hit testing
        }
    }

    override fun render(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
        delta: Float
    ) {
        // Draw the in-game background behind our editor UI so the world remains
        // visible with Minecraft's standard slight blur, but without the
        // previous semi-transparent blue overlay.
        renderTransparentBackground(context)

        // Re-render Soul UI HUDs on top of the blur. The `Gui.render` TAIL hook already
        // painted them, but Minecraft 1.21's screen-open blur pass runs over the main
        // framebuffer including any previously-composited NanoVG PIPs — so they end up
        // blurred. Re-dispatching here puts them back on top, crisp, so the user can see
        // what they're positioning.
        com.soulreturns.ui.runtime.SoulHud.dispatchAll(context)

        val client = Minecraft.getInstance()
        val layout = GuiLayoutManager.getLayout()
        val guiCtx = MinecraftGuiRenderContext(context, client)

        // Precompute rough bounds for each element so we can draw edit boxes
        // and support hit testing.
        val textRenderer = client.font
        val bounds = mutableListOf<ElementBounds>()
        for (element in layout.elements) {
            if (!element.enabled) continue
            // SoulHudElements compute their on-screen origin via `SoulHud.resolveBaseX/Y` so
            // anchor alignment (Center/End for top-center/right HUDs) is honored. Other
            // element types still use the raw anchor + offset math — they don't have
            // alignment metadata yet.
            val baseX: Int
            val baseY: Int
            if (element is com.soulreturns.gui.lib.SoulHudElement) {
                val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
                if (entry != null) {
                    baseX = com.soulreturns.ui.runtime.SoulHud.resolveBaseX(element, entry, width)
                    baseY = com.soulreturns.ui.runtime.SoulHud.resolveBaseY(element, entry, height)
                } else {
                    baseX = (element.anchorX * width).toInt() + element.offsetX
                    baseY = (element.anchorY * height).toInt() + element.offsetY
                }
            } else {
                baseX = (element.anchorX * width).toInt() + element.offsetX
                baseY = (element.anchorY * height).toInt() + element.offsetY
            }

            when (element) {
                is com.soulreturns.gui.lib.TextBlockElement -> {
                    val lines =
                        buildList {
                            element.title?.let { add(it) }
                            addAll(element.lines)
                        }
                    if (lines.isEmpty()) continue
                    val scale = element.scale.coerceAtLeast(0.25f)
                    val lineHeight = ((textRenderer.lineHeight + 2) * scale).toInt().coerceAtLeast(4)
                    var maxWidth = 0
                    for (line in lines) {
                        val w = (textRenderer.width(line) * scale).toInt()
                        if (w > maxWidth) maxWidth = w
                    }
                    val totalHeight = lines.size * lineHeight
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = maxWidth + 8,
                            height = totalHeight + 8,
                        )
                }
                is com.soulreturns.gui.lib.ItemTrackerElement -> {
                    val rows = element.entries.size + if (element.title != null) 1 else 0
                    if (rows == 0) continue
                    val scale = element.scale.coerceAtLeast(0.25f)
                    val lineHeight = ((textRenderer.lineHeight + 4) * scale).toInt().coerceAtLeast(4)
                    val approxWidth = (160 * scale).toInt()
                    val totalHeight = rows * lineHeight
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = approxWidth,
                            height = totalHeight + 8,
                        )
                }
                is com.soulreturns.gui.lib.SoulHudElement -> {
                    // Selection-box geometry tracks the **actual rendered size** of the
                    // composed root from the previous frame — the framework records that
                    // post-draw on `SoulHudRegistry`. Falls back to the registration `(width,
                    // height)` max bounds if the HUD hasn't rendered yet this session (e.g.
                    // first edit before the HUD's gate flips visible).
                    val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id)
                    val measured = com.soulreturns.ui.runtime.SoulHudRegistry.lastMeasured(element.id)
                    val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
                    val intrinsicW = measured?.width ?: (entry?.width?.toFloat() ?: 200f)
                    val intrinsicH = measured?.height ?: (entry?.height?.toFloat() ?: 100f)
                    val approxWidth = (intrinsicW * effectiveScale).toInt()
                    val approxHeight = (intrinsicH * effectiveScale).toInt()
                    bounds +=
                        ElementBounds(
                            id = element.id,
                            x = baseX - 4,
                            y = baseY - 4,
                            width = approxWidth + 8,
                            height = approxHeight + 8,
                        )
                }
            }
        }
        elementBounds = bounds

        // Highlight hovered & selected elements.
        val hoveredId = findHitElement(mouseX, mouseY)
        for (b in elementBounds) {
            val color =
                when {
                    b.id == editState.selectedElementId -> 0x40FFFFFF.toInt() // selected
                    b.id == hoveredId -> 0x2000FFFF.toInt() // hovered
                    else -> 0x20000000.toInt()
                }
            context.fill(b.x, b.y, b.x + b.width, b.y + b.height, color)
        }

        // Render HUD content on top of the edit boxes.
        GuiRenderer.renderHud(layout, guiCtx)

        // Anchor corner indicators — small dots showing each SoulHud's pivot pixel so the
        // user can see at a glance which edge the element is pinned to. Drawn after HUD
        // content so the dot reads on top.
        drawAnchorIndicators(context, layout)

        // Small label showing which element is selected.
        editState.selectedElementId?.let { selectedId ->
            guiCtx.drawText("Selected: $selectedId", 4, 4, 0xFFFFFF00.toInt(), shadow = true)
        }

        // Context menu rendered last so it overlays everything else (HUDs, indicators).
        drawContextMenu(context, mouseX, mouseY)

        // Soul UI overlay: top-right action buttons OR the help dialog (mutually exclusive
        // so a click inside the dimmed scrim can't reach a button accidentally rendered
        // behind it). Submitted last so the buttons / dialog composite on top of HUDs +
        // selection boxes + context menu. Both panels render against
        // [SoulScreen.TARGET_GUI_SCALE] so they look the same physical size regardless of
        // the user's Minecraft GUI Scale — same convention every Soul UI screen uses.
        val actualGuiScale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(0.5f)
        val pipScale = SoulScreen.TARGET_GUI_SCALE / actualGuiScale
        if (showHelpDialog) {
            // Full-viewport composable space sized so `composable × pipScale = screen`.
            val composableW = (width * actualGuiScale / SoulScreen.TARGET_GUI_SCALE).toInt().coerceAtLeast(1)
            val composableH = (height * actualGuiScale / SoulScreen.TARGET_GUI_SCALE).toInt().coerceAtLeast(1)
            NvgFrame.submit(context, x = 0, y = 0, w = composableW, h = composableH, scale = pipScale) {
                val root =
                    SoulComposer.create().build {
                        HelpDialog()
                    }
                root.draw(
                    0f,
                    0f,
                    SoulConstraints(
                        maxWidth = composableW.toFloat(),
                        maxHeight = composableH.toFloat(),
                    ),
                )
            }
        } else {
            // Strip's on-screen footprint = composable × pipScale; pin to top-right with a
            // screen-space margin so it stays a uniform distance from the corner across
            // GUI Scales.
            val screenW = (BTN_PANEL_W * pipScale).toInt().coerceAtLeast(1)
            val screenH = (BTN_PANEL_H * pipScale).toInt().coerceAtLeast(1)
            val bx = width - screenW - BTN_PANEL_MARGIN
            val by = BTN_PANEL_MARGIN
            NvgFrame.submit(context, x = bx, y = by, w = BTN_PANEL_W, h = BTN_PANEL_H, scale = pipScale) {
                val root =
                    SoulComposer.create().build {
                        EditorButtons()
                    }
                root.draw(
                    0f,
                    0f,
                    SoulConstraints(
                        maxWidth = BTN_PANEL_W.toFloat(),
                        maxHeight = BTN_PANEL_H.toFloat(),
                    ),
                )
            }
        }

        super.render(context, mouseX, mouseY, delta)
    }

    // ─────────────────────────────── editor overlay composables ──────────────────────────

    @SoulComposable
    private fun EditorButtons() {
        Row(
            modifier = SoulModifier.Empty.fillMaxSize().padding(all = 4f),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = VerticalAlignment.Center,
            gap = 6f,
        ) {
            Button(
                label = "?",
                onClick = { showHelpDialog = true },
                key = "editor.help",
            )
            Button(
                label = "Reset HUD",
                onClick = { resetLayoutToDefaults() },
                key = "editor.reset",
            )
        }
    }

    @SoulComposable
    private fun HelpDialog() {
        // Full-screen dimmed backdrop. Clicking the scrim (anywhere outside the card)
        // closes the dialog — same affordance as Esc / the Got it button.
        Box(
            modifier =
                SoulModifier.Empty
                    .fillMaxSize()
                    .background(color = 0xA0000000.toInt(), radius = 0f)
                    .clickable("editor.help.scrim") { showHelpDialog = false },
        ) {
            Column(
                modifier = SoulModifier.Empty.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = HorizontalAlignment.Center,
            ) {
                // Inner card: absorb clicks (no-op clickable at a deeper region depth than
                // the scrim) so clicking the card itself doesn't bubble up and dismiss.
                Surface(
                    modifier =
                        SoulModifier.Empty
                            .width(480f)
                            .clickable("editor.help.card") { /* swallow */ },
                    color = SoulTheme.colors.panel,
                    radius = SoulTheme.dimens.radiusMedium,
                    padding = 16f,
                ) {
                    Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 10f) {
                        Text(
                            text = "GUI Editor Help",
                            size = SoulTheme.typography.title.size,
                            color = SoulTheme.colors.accent,
                            font = SoulTheme.typography.title.font,
                        )

                        HelpSection("Controls") {
                            HelpRow("Left-click + drag", "Move a HUD.")
                            HelpRow("Scroll wheel", "Scale the hovered HUD.")
                            HelpRow("Right-click", "Open the per-HUD menu.")
                            HelpRow("Reset HUD", "Restore every HUD to its default.")
                        }

                        HelpSection("Anchors") {
                            HelpRow("Pivot dot", "Yellow dot = the HUD's pinned corner.")
                            HelpRow("Snap", "Right-click → pick a cell on the 3×3 grid.")
                        }

                        Row(
                            modifier = SoulModifier.Empty.fillMaxWidth(),
                            horizontalArrangement = Arrangement.End,
                        ) {
                            Button(
                                label = "Got it",
                                onClick = { showHelpDialog = false },
                                accent = true,
                                key = "editor.help.close",
                            )
                        }
                    }
                }
            }
        }
    }

    @SoulComposable
    private fun HelpSection(
        title: String,
        content: @SoulComposable () -> Unit,
    ) {
        Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f) {
            Text(
                text = title,
                size = SoulTheme.typography.heading.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.heading.font,
            )
            Surface(
                modifier = SoulModifier.Empty.fillMaxWidth(),
                color = SoulTheme.colors.panelInset,
                radius = SoulTheme.dimens.radiusSmall,
                padding = 10f,
            ) {
                Column(modifier = SoulModifier.Empty.fillMaxWidth(), gap = 6f, content = content)
            }
        }
    }

    @SoulComposable
    private fun HelpRow(
        key: String,
        description: String,
    ) {
        // Two-column row mirroring the Home page's HomeFeature pattern: fixed-width key
        // label on the left (accent color, heading font) so descriptions line up cleanly.
        Row(
            modifier = SoulModifier.Empty.fillMaxWidth(),
            verticalAlignment = VerticalAlignment.Top,
            gap = 10f,
        ) {
            Box(modifier = SoulModifier.Empty.width(130f)) {
                Text(
                    text = key,
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.accent,
                    font = SoulTheme.typography.heading.font,
                )
            }
            Text(
                text = description,
                size = SoulTheme.typography.body.size,
                color = SoulTheme.colors.text,
                font = SoulTheme.typography.body.font,
            )
        }
    }

    /**
     * Draw a 4×4 dot at each enabled SoulHudElement's anchor pixel, plus a 1-px ring to
     * make it pop against bright HUD backgrounds. The anchor pixel is the point where the
     * element's `horizontalAnchor` / `verticalAnchor` edge meets `(anchorX × screenW,
     * anchorY × screenH)` — for `Start/Top` it's the top-left corner, for `Center/Center`
     * the visual center, etc. Helps the user understand why a HUD doesn't move the way
     * they expect when they drag.
     */
    private fun drawAnchorIndicators(
        context: GuiGraphics,
        layout: com.soulreturns.gui.lib.GuiLayout,
    ) {
        for (element in layout.elements) {
            if (!element.enabled) continue
            if (element !is com.soulreturns.gui.lib.SoulHudElement) continue
            val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(element.id) ?: continue
            val baseX = com.soulreturns.ui.runtime.SoulHud.resolveBaseX(element, entry, width)
            val baseY = com.soulreturns.ui.runtime.SoulHud.resolveBaseY(element, entry, height)
            val measured = com.soulreturns.ui.runtime.SoulHudRegistry.lastMeasured(element.id)
            val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
            val w = ((measured?.width ?: entry.width.toFloat()) * effectiveScale).toInt()
            val h = ((measured?.height ?: entry.height.toFloat()) * effectiveScale).toInt()
            val pivotX =
                baseX +
                    when (element.horizontalAnchor) {
                        com.soulreturns.gui.lib.HudHorizontalAnchor.Start -> 0
                        com.soulreturns.gui.lib.HudHorizontalAnchor.Center -> w / 2
                        com.soulreturns.gui.lib.HudHorizontalAnchor.End -> w
                    }
            val pivotY =
                baseY +
                    when (element.verticalAnchor) {
                        com.soulreturns.gui.lib.HudVerticalAnchor.Top -> 0
                        com.soulreturns.gui.lib.HudVerticalAnchor.Center -> h / 2
                        com.soulreturns.gui.lib.HudVerticalAnchor.Bottom -> h
                    }
            // White-bordered yellow dot (4×4 inner, 6×6 outer).
            context.fill(pivotX - 3, pivotY - 3, pivotX + 3, pivotY + 3, 0xFF000000.toInt())
            context.fill(pivotX - 2, pivotY - 2, pivotX + 2, pivotY + 2, 0xFFFFFF00.toInt())
        }
    }

    private fun drawContextMenu(
        context: GuiGraphics,
        mouseX: Int,
        mouseY: Int,
    ) {
        val menu = contextMenu ?: return
        val items = buildContextMenuItems(menu.elementId)
        val client = Minecraft.getInstance()
        val textRenderer = client.font
        val padding = 4
        val ih = menu.itemHeight
        val left = menu.originX
        val top = menu.originY
        val right = menu.originX + menu.width
        val bottom = menu.originY + menu.gridHeight + ih * items.size
        val bg = 0xE0202020.toInt()
        val border = 0xFF555555.toInt()
        val hover = 0xFF353535.toInt()
        context.fill(left, top, right, bottom, bg)
        // 1-px border on each side.
        context.fill(left, top, right, top + 1, border)
        context.fill(left, bottom - 1, right, bottom, border)
        context.fill(left, top, left + 1, bottom, border)
        context.fill(right - 1, top, right, bottom, border)

        // Anchor grid section at the top — replaces the 5 text preset rows. Exposes all 9
        // anchor combinations (including the 4 edge midpoints) on a single visual widget.
        drawAnchorGrid(context, menu, mouseX, mouseY)

        // Divider between grid and action items.
        val itemsTop = top + menu.gridHeight
        context.fill(left + 4, itemsTop, right - 4, itemsTop + 1, border)

        for ((idx, item) in items.withIndex()) {
            val itemY = itemsTop + idx * ih
            val hovered = mouseX in left..(right - 1) && mouseY in itemY..(itemY + ih - 1)
            if (hovered) {
                context.fill(left + 1, itemY, right - 1, itemY + ih, hover)
            }
            context.drawString(
                textRenderer,
                item.label,
                left + padding,
                itemY + (ih - 8) / 2,
                0xFFEEEEEE.toInt(),
                true,
            )
        }
    }

    /**
     * Geometry of the anchor grid inside a [ContextMenu]. The visible "screen rectangle" is
     * inset from the menu's left / right by [GRID_MARGIN_H] and from its top / bottom of the
     * grid section by [GRID_MARGIN_V]. The 9 cells are equal thirds of that rectangle; each
     * cell's dot is centered on the corresponding anchor pixel (corner / edge midpoint /
     * exact center) with a small inward inset so it stays visually inside the rect frame.
     */
    private data class GridGeometry(
        val rectLeft: Int,
        val rectTop: Int,
        val rectRight: Int,
        val rectBottom: Int,
    ) {
        val cellW: Int get() = (rectRight - rectLeft) / 3
        val cellH: Int get() = (rectBottom - rectTop) / 3

        /** Cell index `0..8` at (mouseX, mouseY); -1 if outside the rectangle. */
        fun cellIndexAt(mouseX: Int, mouseY: Int): Int {
            if (mouseX < rectLeft || mouseX >= rectRight) return -1
            if (mouseY < rectTop || mouseY >= rectBottom) return -1
            val col = ((mouseX - rectLeft) / cellW).coerceIn(0, 2)
            val row = ((mouseY - rectTop) / cellH).coerceIn(0, 2)
            return row * 3 + col
        }

        /** Center coords of the visible dot for cell `index` (row-major, 0..8). */
        fun dotCenter(index: Int): IntArray {
            val col = index % 3
            val row = index / 3
            val hFrac = listOf(0.0, 0.5, 1.0)[col]
            val vFrac = listOf(0.0, 0.5, 1.0)[row]
            val cx = rectLeft + DOT_INSET + ((rectRight - rectLeft - 2 * DOT_INSET) * hFrac).toInt()
            val cy = rectTop + DOT_INSET + ((rectBottom - rectTop - 2 * DOT_INSET) * vFrac).toInt()
            return intArrayOf(cx, cy)
        }
    }

    /**
     * Snap [elementId] to [preset]'s anchor configuration while keeping the HUD visually
     * fixed on screen. The renderer formula is
     * `baseX = anchorX × screenW − shift(horizontalAnchor, onScreenW) + offsetX` (and same
     * for Y), so to preserve `baseX` we solve for the new offset:
     * `newOffsetX = baseX_current − newAnchorX × screenW + shift(newHA, onScreenW)`.
     * Without this the HUD would teleport to the new corner (`offset = 0`).
     *
     * `onScreenW/H` come from the last measured intrinsic size × the element's effective
     * scale — the **same** values the renderer uses, so the pivot shift cancels out exactly
     * and the visible position doesn't drift even by one pixel.
     */
    private fun applyAnchorPreservingPosition(
        elementId: String,
        preset: AnchorPreset,
    ) {
        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == elementId } as?
                com.soulreturns.gui.lib.SoulHudElement
        val entry = com.soulreturns.ui.runtime.SoulHudRegistry.get(elementId)
        if (element == null || entry == null) {
            // No registration / wrong element type — fall back to the corner-snap behavior.
            GuiLayoutManager.updateSoulHudAnchor(
                id = elementId,
                anchorX = preset.anchorX,
                anchorY = preset.anchorY,
                horizontalAnchor = preset.horizontalAnchor,
                verticalAnchor = preset.verticalAnchor,
            )
            return
        }
        val baseX = com.soulreturns.ui.runtime.SoulHud.resolveBaseX(element, entry, width)
        val baseY = com.soulreturns.ui.runtime.SoulHud.resolveBaseY(element, entry, height)
        val measured = com.soulreturns.ui.runtime.SoulHudRegistry.lastMeasured(elementId)
        val effectiveScale = com.soulreturns.ui.runtime.SoulHud.effectiveScaleFor(element.scale)
        val onScreenW = (measured?.width ?: entry.width.toFloat()) * effectiveScale
        val onScreenH = (measured?.height ?: entry.height.toFloat()) * effectiveScale
        val newShiftX =
            when (preset.horizontalAnchor) {
                com.soulreturns.gui.lib.HudHorizontalAnchor.Start -> 0f
                com.soulreturns.gui.lib.HudHorizontalAnchor.Center -> onScreenW / 2f
                com.soulreturns.gui.lib.HudHorizontalAnchor.End -> onScreenW
            }
        val newShiftY =
            when (preset.verticalAnchor) {
                com.soulreturns.gui.lib.HudVerticalAnchor.Top -> 0f
                com.soulreturns.gui.lib.HudVerticalAnchor.Center -> onScreenH / 2f
                com.soulreturns.gui.lib.HudVerticalAnchor.Bottom -> onScreenH
            }
        val newOffsetX = (baseX - preset.anchorX * width + newShiftX).toInt()
        val newOffsetY = (baseY - preset.anchorY * height + newShiftY).toInt()
        GuiLayoutManager.updateSoulHudAnchor(
            id = elementId,
            anchorX = preset.anchorX,
            anchorY = preset.anchorY,
            horizontalAnchor = preset.horizontalAnchor,
            verticalAnchor = preset.verticalAnchor,
            offsetX = newOffsetX,
            offsetY = newOffsetY,
        )
    }

    private fun computeGridGeometry(menu: ContextMenu): GridGeometry {
        val rectLeft = menu.originX + GRID_MARGIN_H
        val rectTop = menu.originY + GRID_MARGIN_V
        val rectRight = menu.originX + menu.width - GRID_MARGIN_H
        val rectBottom = menu.originY + menu.gridHeight - GRID_MARGIN_V
        return GridGeometry(rectLeft, rectTop, rectRight, rectBottom)
    }

    private fun drawAnchorGrid(
        context: GuiGraphics,
        menu: ContextMenu,
        mouseX: Int,
        mouseY: Int,
    ) {
        val geom = computeGridGeometry(menu)
        val frameColor = 0xFF555555.toInt()
        val accentColor = 0xFF3B82F6.toInt()
        val dimDot = 0xFF777777.toInt()
        val brightDot = 0xFFEEEEEE.toInt()
        val cellHover = 0x33FFFFFF.toInt()

        // Screen-shaped frame (1-px outline).
        context.fill(geom.rectLeft, geom.rectTop, geom.rectRight, geom.rectTop + 1, frameColor)
        context.fill(geom.rectLeft, geom.rectBottom - 1, geom.rectRight, geom.rectBottom, frameColor)
        context.fill(geom.rectLeft, geom.rectTop, geom.rectLeft + 1, geom.rectBottom, frameColor)
        context.fill(geom.rectRight - 1, geom.rectTop, geom.rectRight, geom.rectBottom, frameColor)

        val element =
            GuiLayoutManager.getElements().firstOrNull { it.id == menu.elementId } as?
                com.soulreturns.gui.lib.SoulHudElement
        val presets = anchorGridPresets()
        val hoveredIdx = geom.cellIndexAt(mouseX, mouseY)
        val currentIdx =
            if (element != null) {
                presets.indexOfFirst {
                    it.horizontalAnchor == element.horizontalAnchor &&
                        it.verticalAnchor == element.verticalAnchor
                }
            } else {
                -1
            }

        for (i in 0..8) {
            val col = i % 3
            val row = i / 3
            val cellLeft = geom.rectLeft + col * geom.cellW
            val cellTop = geom.rectTop + row * geom.cellH
            val cellRight = if (col == 2) geom.rectRight else cellLeft + geom.cellW
            val cellBottom = if (row == 2) geom.rectBottom else cellTop + geom.cellH
            val isHovered = i == hoveredIdx
            val isCurrent = i == currentIdx
            if (isHovered) {
                context.fill(cellLeft + 1, cellTop + 1, cellRight - 1, cellBottom - 1, cellHover)
            }
            val (dotCx, dotCy) = geom.dotCenter(i)
            val dotSize = if (isCurrent || isHovered) 8 else 6
            val dotColor =
                when {
                    isCurrent -> accentColor
                    isHovered -> brightDot
                    else -> dimDot
                }
            val half = dotSize / 2
            context.fill(dotCx - half, dotCy - half, dotCx + half, dotCy + half, dotColor)
        }
    }

    override fun mouseClicked(
        click: net.minecraft.client.input.MouseButtonEvent,
        doubled: Boolean
    ): Boolean {
        val client = Minecraft.getInstance()
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()
        val isRightClick = click.button() == 1

        // Help dialog is modal: route ALL clicks to SoulInput so the close button + scrim
        // dismiss work. Legacy edit logic is fully blocked until the dialog is closed.
        if (showHelpDialog) {
            SoulInput.queueClick(click.x.toFloat(), click.y.toFloat())
            return true
        }

        // Soul UI top-right button strip: any click in its small region goes through
        // SoulInput rather than the legacy editor flow (no selection/drag side-effects).
        // Bounds match the scaled PIP rect computed in render() — `BTN_PANEL_*` are in
        // composable units, multiplied by `pipScale` to land in screen-space.
        val actualGuiScale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(0.5f)
        val pipScale = SoulScreen.TARGET_GUI_SCALE / actualGuiScale
        val screenW = (BTN_PANEL_W * pipScale).toInt().coerceAtLeast(1)
        val screenH = (BTN_PANEL_H * pipScale).toInt().coerceAtLeast(1)
        val bx = width - screenW - BTN_PANEL_MARGIN
        val by = BTN_PANEL_MARGIN
        if (mouseXInt in bx..(bx + screenW - 1) && mouseYInt in by..(by + screenH - 1)) {
            SoulInput.queueClick(click.x.toFloat(), click.y.toFloat())
            return true
        }

        // Context menu is modal: while open, all clicks land here first.
        contextMenu?.let { menu ->
            // Anchor-grid hit-test first — covers the top `menu.gridHeight` strip.
            val geom = computeGridGeometry(menu)
            val gridIdx = geom.cellIndexAt(mouseXInt, mouseYInt)
            if (gridIdx in 0..8) {
                val preset = anchorGridPresets()[gridIdx]
                applyAnchorPreservingPosition(menu.elementId, preset)
                contextMenu = null
                return true
            }
            // Per-HUD toggle / Settings rows live below the grid section.
            val items = buildContextMenuItems(menu.elementId)
            val itemsTop = menu.originY + menu.gridHeight
            val ih = menu.itemHeight
            val withinX = mouseXInt >= menu.originX && mouseXInt < menu.originX + menu.width
            val withinY = mouseYInt >= itemsTop && mouseYInt < itemsTop + ih * items.size
            if (withinX && withinY) {
                val idx = (mouseYInt - itemsTop) / ih
                items.getOrNull(idx)?.action?.invoke(menu.elementId)
            }
            contextMenu = null
            return true
        }

        // Right-click on a SoulHudElement opens the anchor-preset menu instead of dragging.
        // Other element types don't have alignment metadata yet, so right-click on them
        // falls through to the normal selection path.
        if (isRightClick) {
            val hitId = findHitElement(mouseXInt, mouseYInt)
            val hit = hitId?.let { id -> GuiLayoutManager.getElements().firstOrNull { it.id == id } }
            if (hit is com.soulreturns.gui.lib.SoulHudElement) {
                editState = EditState(selectedElementId = hit.id)
                // Clamp the menu to stay on-screen. Total height = grid section + per-HUD
                // toggle rows; item count is freshly built because toggle labels can change.
                val itemCount = buildContextMenuItems(hit.id).size
                val totalHeight = CTX_GRID_HEIGHT + CTX_ITEM_HEIGHT * itemCount
                val ox = mouseXInt.coerceAtMost(width - CTX_MENU_WIDTH - 2).coerceAtLeast(2)
                val oy = mouseYInt.coerceAtMost(height - totalHeight - 2).coerceAtLeast(2)
                contextMenu =
                    ContextMenu(
                        elementId = hit.id,
                        originX = ox,
                        originY = oy,
                        itemHeight = CTX_ITEM_HEIGHT,
                        width = CTX_MENU_WIDTH,
                        gridHeight = CTX_GRID_HEIGHT,
                    )
                return true
            }
        }

        // Treat any mouse button as a selection trigger in edit mode. This
        // avoids having to depend on the exact Click.button() enum string.
        val hitId = findHitElement(mouseXInt, mouseYInt)
        if (hitId != null) {
            val guiCtx = EditHitTestContext(client)
            editState = GuiEditSession.beginDrag(hitId, guiCtx, mouseXInt, mouseYInt)
        } else {
            editState = EditState()
        }
        return true
    }

    override fun mouseDragged(
        click: net.minecraft.client.input.MouseButtonEvent,
        offsetX: Double,
        offsetY: Double
    ): Boolean {
        if (showHelpDialog) return false
        // Right-button drags + drags while the context menu is up shouldn't move elements.
        if (click.button() != 0) return super.mouseDragged(click, offsetX, offsetY)
        if (contextMenu != null) return super.mouseDragged(click, offsetX, offsetY)

        val client = Minecraft.getInstance()
        val guiCtx = EditHitTestContext(client)
        val mouseXInt = click.x.toInt()
        val mouseYInt = click.y.toInt()

        // If a drag starts without a prior click being registered here, we
        // still want to begin dragging the element under the cursor.
        if (!editState.isDragging) {
            val hitId = findHitElement(mouseXInt, mouseYInt)
            if (hitId != null) {
                editState = GuiEditSession.beginDrag(hitId, guiCtx, mouseXInt, mouseYInt)
            }
        }

        if (editState.isDragging) {
            editState = GuiEditSession.updateDrag(editState, guiCtx, mouseXInt, mouseYInt)
            return true
        }
        return super.mouseDragged(click, offsetX, offsetY)
    }

    override fun mouseReleased(click: net.minecraft.client.input.MouseButtonEvent): Boolean {
        if (showHelpDialog) {
            SoulInput.queueRelease()
            return true
        }
        if (editState.isDragging) {
            editState = GuiEditSession.endDrag(editState)
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        horizontalAmount: Double,
        verticalAmount: Double
    ): Boolean {
        // Block legacy HUD-scaling scroll while the help dialog is open.
        if (showHelpDialog) return true
        if (verticalAmount == 0.0) return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        val client = Minecraft.getInstance()
        val hitId =
            findHitElement(mouseX.toInt(), mouseY.toInt())
                ?: editState.selectedElementId
                ?: return super.mouseScrolled(mouseX, mouseY, horizontalAmount, verticalAmount)

        // Treat the hovered element as selected for scaling purposes.
        editState = editState.copy(selectedElementId = hitId)
        GuiEditSession.adjustScale(editState, verticalAmount)
        return true
    }

    override fun keyPressed(keyEvent: net.minecraft.client.input.KeyEvent): Boolean {
        if (showHelpDialog && keyEvent.key() == org.lwjgl.glfw.GLFW.GLFW_KEY_ESCAPE) {
            showHelpDialog = false
            return true
        }
        return super.keyPressed(keyEvent)
    }

    override fun onClose() {
        // Persist layout changes when leaving edit mode
        GuiLayoutManager.save()
        super.onClose()
    }

    private fun resetLayoutToDefaults() {
        // Clear any current selection/dragging state and reset layout.
        editState = EditState()
        elementBounds = emptyList()
        GuiLayoutManager.resetToDefaults()
    }

    private fun findHitElement(
        mouseX: Int,
        mouseY: Int
    ): String? {
        return elementBounds.lastOrNull { b ->
            mouseX >= b.x && mouseX <= b.x + b.width &&
                mouseY >= b.y && mouseY <= b.y + b.height
        }?.id
    }
}
