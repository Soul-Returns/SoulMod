package com.soulreturns.gui.lib

import kotlin.jvm.Transient

/**
 * Logical identifier for a GUI element. Kept as a typealias so hosts can
 * easily interop with plain Strings.
 */
typealias GuiElementId = String

/**
 * Base type for all GUI elements managed by the GUI layout library.
 *
 * This type is intentionally free of any Minecraft/Fabric dependencies so the
 * library can be reused across mods or even non-Minecraft contexts if needed.
 */
sealed class GuiElement(
    @Transient open val id: GuiElementId,
    @Transient open val enabled: Boolean = true,
    /**
     * Normalized anchor coordinates in screen space (0.0–1.0).
     * These determine the general area where the element lives, independent of
     * concrete resolution.
     */
    @Transient open val anchorX: Double = 0.5,
    @Transient open val anchorY: Double = 0.5,
    /**
     * Pixel offsets relative to the anchor point.
     */
    @Transient open val offsetX: Int = 0,
    @Transient open val offsetY: Int = 0,
    /**
     * Visual scale factor applied during rendering.
     */
    @Transient open val scale: Float = 1.0f,
    /**
     * Whether to render the element's text with a shadow.
     */
    @Transient open val textShadow: Boolean = true,
)

/**
 * Simple text block rendered on screen.
 */
data class TextBlockElement(
    override val id: GuiElementId,
    override val enabled: Boolean = true,
    override val anchorX: Double = 0.5,
    override val anchorY: Double = 0.5,
    override val offsetX: Int = 0,
    override val offsetY: Int = 0,
    override val scale: Float = 1.0f,
    override val textShadow: Boolean = true,
    val title: String? = null,
    val lines: List<String> = emptyList(),
    val color: Int = 0xFFFFFFFF.toInt(),
) : GuiElement(id, enabled, anchorX, anchorY, offsetX, offsetY, scale, textShadow)

/**
 * Entry for a single tracked item inside an ItemTrackerElement.
 *
 * The concrete mapping from iconKey to an ItemStack (or other renderable
 * representation) is provided by the host via adapter code.
 */
data class TrackedItemEntry(
    val entryId: String,
    val displayName: String,
    val iconKey: String,
    val currentCount: Int = 0,
    val targetCount: Int = 0,
)

/**
 * Item tracker element with a list of tracked items and simple layout
 * parameters.
 */
data class ItemTrackerElement(
    override val id: GuiElementId,
    override val enabled: Boolean = true,
    override val anchorX: Double = 0.5,
    override val anchorY: Double = 0.5,
    override val offsetX: Int = 0,
    override val offsetY: Int = 0,
    override val scale: Float = 1.0f,
    override val textShadow: Boolean = true,
    val title: String? = null,
    val entries: List<TrackedItemEntry> = emptyList(),
    val textColor: Int = 0xFFFFFFFF.toInt(),
    val rowSpacing: Int = 2,
    val columns: Int = 1,
) : GuiElement(id, enabled, anchorX, anchorY, offsetX, offsetY, scale, textShadow)

/**
 * Position/scale shell for a [com.soulreturns.gui.lib.tracker.TrackerOverlay] HUD.
 *
 * Only the layout fields are serialized to `gui_layout.json`; the overlay's tabs/data are
 * provided at runtime by the owning feature via
 * [com.soulreturns.gui.lib.GuiLayoutApi.updateTrackerOverlay]. UI state (active tab, sort key,
 * row limit) lives in `tracker_settings.json` via
 * [com.soulreturns.gui.lib.tracker.TrackerSettingsStore].
 */
data class TrackerOverlayElement(
    override val id: GuiElementId,
    override val enabled: Boolean = true,
    override val anchorX: Double = 0.02,
    override val anchorY: Double = 0.5,
    override val offsetX: Int = 0,
    override val offsetY: Int = 0,
    override val scale: Float = 1.0f,
    override val textShadow: Boolean = true,
) : GuiElement(id, enabled, anchorX, anchorY, offsetX, offsetY, scale, textShadow)
