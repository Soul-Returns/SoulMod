package com.soulreturns.features.inventorybuttons

import com.soulreturns.api.LocationApi
import com.soulreturns.config.ButtonProfile
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.InventoryButton
import com.soulreturns.config.InventoryButtonsConfig
import com.soulreturns.config.InventoryButtonsConfig.Companion.DEFAULT_ID
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.inventory.EquipmentDisplay
import com.soulreturns.features.inventory.InventoryPreview
import com.soulreturns.features.inventorybuttons.InventoryButtonSpots.FRAME_HEIGHT
import com.soulreturns.features.inventorybuttons.InventoryButtonSpots.FRAME_WIDTH
import com.soulreturns.features.inventorybuttons.InventoryButtonSpots.SIZE
import com.soulreturns.features.keybinds.CommandPreset
import com.soulreturns.features.keybinds.CommandPresets
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import com.soulreturns.util.soulChat
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.chat.Component
import net.minecraft.world.inventory.Slot
import org.lwjgl.glfw.GLFW
import java.util.UUID
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The inventory button editor (`/soul buttons`), spec `mocks/inventory-buttons-editor/` (Proposed).
 * Left: browser-style profile tabs over a picture of the whole screen at Minecraft's GUI scale
 * (shrunk to fit beside the sidebar): the player inventory as it really looks, every button spot
 * ([InventoryButtonSpots]) that exists at this window size and GUI scale, and the profile's buttons
 * on theirs; under it a key-hint panel that turns into a trash drop zone while a button is dragged.
 * Right: a Soul sidebar of three cards, Button (the selected one), Profile and Editor, plus a footer
 * with Add button / Revert / Done. The use-in picker is a modal over both.
 *
 * Buttons only ever sit on spots: a click selects, dragging (after 3 px) drops onto the spot under
 * the button (swapping with a button already there), double-click adds one on a free spot, Duplicate
 * puts the copy next to the original ([InventoryButtonSpots.nextTo]). Buttons whose spot does not
 * exist at this scale wait in the [HiddenButtonsTray] over the preview.
 *
 * Profiles are plain button sets; one "Everywhere" slot plus per-area slots (and the dungeon run)
 * each hold exactly one profile ([InventoryButtonProfiles]). Opens on the profile live where the
 * player is. Edits apply to a working copy of every profile and of those slots and are saved when
 * the screen goes away (Done, Escape, or anything else that replaces it); Revert puts the copy back
 * to what it was on opening.
 */
class InventoryButtonsScreen(
    private val parent: Screen? = null,
    initialProfile: String = InventoryButtons.liveProfileId(),
    initialSelection: Int = -1,
) : SoulScreenBase(Component.literal("Inventory Buttons")) {
    companion object {
        private val WARN = 0xFFE0A452.toInt()
        private val LIVE = 0xFF4ADE80.toInt()
        private const val LIVE_BG = 0x2922C55E
        private val TAB_IDLE = 0xFF171A20.toInt()
        private val TAB_HOVER = 0xFF21252D.toInt()
        private const val GRID_CELL = 0x18FFFFFF
        private val SCREEN_BG = 0xA0262A31.toInt()
        private val ACTIVE_TAB = 0xFF22252B.toInt()
        private val BAR_BG = 0xFF0C0D10.toInt()
        private val SCREEN_EDGE = 0xFF3A3F49.toInt()
        private val HEAD = 0xFF777777.toInt()
        private val CHIP_HOVER = 0xFF2C2C2C.toInt()
        private const val CHIP_ON = 0x383B82F6
        private val OUTLINE_HOVER = 0xFF555555.toInt()
        private val OUTLINE_OFF = 0xFF2C2C2C.toInt()
        private val DANGER_TEXT = 0xFFFF8A8A.toInt()
        private val DANGER_EDGE = 0xFF7A3434.toInt()
        private val NOTE_BG = 0x99242424.toInt()
        private val ROW_SEP = 0xFF262626.toInt()
        private val ICON_IDLE = 0xFF3A3A3A.toInt()
        private val HINTS_BG = 0xE0262A31.toInt()
        private val HINT_TEXT = 0xFFAAAAAA.toInt()
        private val HINT_SEP = 0xFF666666.toInt()
        private val TRASH_BG = 0xE03C1E20.toInt()
        private const val TRASH_EDGE = 0x73DC5A5A
        private val TRASH_TEXT = 0xFFE8A0A0.toInt()
        private val TRASH_BG_OVER = 0xE6962828.toInt()
        private val TRASH_EDGE_OVER = 0xFFE05555.toInt()
        private val TOAST_BG = 0xFF242424.toInt()
        private val UNDO = 0xFF7FB0FF.toInt()
        private val UNDO_HOVER = 0xFFB9D3FF.toInt()
        private val HERE_EDGE = 0x8C7FB0FF.toInt()
        private val HERE_ON = 0xFFDBE7FF.toInt()

        private val F9 = 9f * MsdfFont.LEGACY_EM
        private val F95 = 9.5f * MsdfFont.LEGACY_EM
        private val F105 = 10.5f * MsdfFont.LEGACY_EM
        private val F12 = 12f * MsdfFont.LEGACY_EM

        private const val MAX_SUGGESTIONS = 18
        private const val TITLE_H = 33
        private const val DRAG_THRESHOLD = 3.0
        private const val GAP = 6f
        private const val ZOOM_MARGIN = 6
        private val TRAFFIC_LIGHTS = intArrayOf(0xFFFF5F57.toInt(), 0xFFFEBC2E.toInt(), 0xFF28C840.toInt())
        private const val TAB_H = 28
        private const val TABS_X = 54
        private const val TAB_MIN_W = 56
        private const val TAB_MAX_W = 160
        private const val TAB_FADE_W = 16
        private const val TAB_GAP = 3
        private const val PLUS_W = 24

        /** The tab's "…" pill (14 wide) + 4 px gap - 2 px of the tab's right padding, kept free on the title line. */
        private const val TAB_MENU_RESERVE = 16

        /** The key-hint panel under the window: two 12 px lines + padding, 6 px below the frame. */
        private const val HINTS_H = 40
        private const val HINTS_GAP = 6
        private const val HINTS_PAD_X = 10
        private const val UNDO_MS = 5000L
        private const val TOAST_MS = 2200L
        private const val PANEL_MARGIN = 6
        private const val PANEL_MAX_W = 250
        private const val FOOTER_H = 40

        // Button variants.
        private const val PLAIN = 0
        private const val OUTLINE = 1
        private const val ACCENT = 2

        private val TAB_MENU = listOf("Use everywhere", "Use in…", "Rename…", "Duplicate", "Delete")
        private val HINTS_FULL = listOf(
            "Drag" to "move (onto a button: swap)",
            "Double-click" to "add",
            "Del" to "remove",
            "Click an item" to "copy its look",
            "Right-click a tab" to "profile menu",
        )
        private val HINTS_SHORT = listOf("Drag" to "move", "Double-click" to "add", "Del" to "remove", "Click an item" to "copy look")

        /** Presets that open a Soul screen are not commands a button could send. */
        private val CHIPS: List<CommandPreset> = CommandPresets.all.filter { it.screen == null }

        /** Open on [profileId] ([DEFAULT_ID] = Default), [selected] button pre-selected. */
        fun open(parent: Screen? = null, profileId: String = InventoryButtons.liveProfileId(), selected: Int = -1) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(InventoryButtonsScreen(parent, profileId, selected)) }
        }
    }

    /** One profile in the working copy; [DEFAULT_ID] is Default. */
    private class Draft(
        val id: String,
        var name: String,
        val buttons: MutableList<InventoryButton>,
    ) {
        val isDefault: Boolean get() = id == DEFAULT_ID

        fun profile(): ButtonProfile = ButtonProfile(id, name, buttons.toList())
    }

    /** The last button dropped on the trash: profile, list position and the button itself, offered until [until]. */
    private class Undo(val profileId: String, val index: Int, val button: InventoryButton, val until: Long)

    private val original = cfg.inventoryButtons
    private val drafts = ArrayList<Draft>()
    private var tab = 0
    private val tray = HiddenButtonsTray()

    /** Hidden buttons in the profile the editor opens on show the tray right away (needs the first frame's size). */
    private var openTrayOnFirstFrame = true
    private var selected = -1

    // Which profile is used where, edited alongside the drafts and saved with them.
    private var everywhere = DEFAULT_ID
    private val byArea = LinkedHashMap<String, String>()
    private var showOuter = original.showOuterButtons

    /** The buttons of the profile being edited. */
    private val buttons: MutableList<InventoryButton> get() = drafts[tab].buttons

    private val commandField = SoulTextField()
    private val labelField = SoulTextField()
    private val itemField = SoulTextField()
    private val fields = listOf(commandField, labelField, itemField)
    private val tabMenuToken = Any()

    // Recomputed at the start of every frame from the drafts.
    private var frame: InventoryButtonsConfig = original
    private var live = DEFAULT_ID
    private var liveTab = 0

    /** Tab rects of the last frame (x0, y0, x1, y1, draft index), for right clicks and the tab menu. */
    private val tabRects = ArrayList<IntArray>()

    // The preview's transform: the screen (GUI px) drawn at viewScale with its top-left at
    // (viewX, viewY). Recomputed every frame; the input handlers read the last frame's values,
    // which is what the user was looking at when they clicked.
    private var previewRight = 0.0
    private var previewTop = 0.0

    /** The profile tab strip's scroll, drawn with the shared Soul scrollbar (draggable). */
    private val tabSb = ScrollState()
    private var tabScroll: Float
        get() = tabSb.pos
        set(v) {
            tabSb.pos = v
        }
    private var tabStripX1 = 0
    private var revealTab = -1
    private var tabMaxScroll = 0f
    private var viewX = 0
    private var viewY = 0
    private var viewScale = 1f

    /** The preview window on screen (raw px): what is clipped to, framed and given the title bar. */
    private var frameX0 = 0
    private var frameY0 = 0
    private var frameX1 = 0
    private var frameY1 = 0
    private var invX = 0
    private var invY = 0
    private var spots: InventoryButtonSpots.Layout = InventoryButtonSpots.layout(1, 1)

    /** Top of the key-hint panel under the window (Soul px). */
    private var hintsTop = 0

    private var dragging = false
    private var armed = false
    private var lifted = false
    private var pressX = 0.0
    private var pressY = 0.0
    private var grabX = 0f
    private var grabY = 0f

    // Sidebar body (Soul px) and its scroll; cards are measured in a dry pass before they are drawn.
    private var bodyRect = intArrayOf(0, 0, 0, 0)
    private val panelSb = ScrollState()
    private var dry = false
    private var mx = 0
    private var my = 0
    private var chipsOpen = false
    private var advancedOpen = false

    private var pickerOpen = false
    private var pickerBody = intArrayOf(0, 0, 0, 0)
    private val pickerSb = ScrollState()

    private var suggestionQuery = ""
    private var suggestionCache: List<String> = emptyList()

    /** The key-hint panel's last size (Soul px): the trash zone takes it over while dragging. */
    private var hintW = 0
    private var hintH = 0
    private var trashRect = IntArray(0)
    private var undo: Undo? = null
    private var undoRect = IntArray(0)
    private var toastText: String? = null
    private var toastUntil = 0L

    init {
        // Opening the editor any other way abandons a pending menu pick.
        InventoryButtons.cancelPick()
        commandField.onChange = { text -> updateSelected { copy(command = text) } }
        labelField.onChange = { text -> updateSelected { copy(label = text) } }
        itemField.onChange = { text ->
            InventoryButtonIcons.canonicalId(text)?.let { id ->
                updateSelected { copy(item = id, skin = "", model = "", glint = false) }
            }
        }
        load()
        tab = drafts.indexOfFirst { it.id == initialProfile }.coerceAtLeast(0)
        select(if (initialSelection in buttons.indices) initialSelection else -1)
    }

    private fun load() {
        drafts.clear()
        drafts.add(Draft(DEFAULT_ID, "Default", original.buttons.toMutableList()))
        for (profile in original.profiles) drafts.add(Draft(profile.id, profile.name, profile.buttons.toMutableList()))
        everywhere = original.activeEverywhere
        byArea.clear()
        byArea.putAll(original.activeByArea)
        showOuter = original.showOuterButtons
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        scrollTargets.clear()
        tabRects.clear()
        val f = SoulUi.factor()
        val uiW = (width / f).toInt()
        val uiH = (height / f).toInt()
        val smx = (mouseX / f).roundToInt()
        val smy = (mouseY / f).roundToInt()
        val panelW = (uiW * 0.4f).toInt().coerceAtMost(PANEL_MAX_W)
        val px1 = uiW - PANEL_MARGIN
        val px0 = px1 - panelW
        previewRight = ((px0 - PANEL_MARGIN) * f).toDouble()
        previewTop = (PANEL_MARGIN * f).toDouble()
        layoutPreview()

        frame = view()
        live = InventoryButtonProfiles.resolve(frame, SkyblockLocation.area, LocationApi.inDungeon)
        liveTab = drafts.indexOfFirst { it.id == live }.coerceAtLeast(0)
        undo?.let { if (System.currentTimeMillis() > it.until) undo = null }

        val modal = pickerOpen || alertModalOpen || dropdownOpen
        val busy = dragging || lifted
        mx = if (modal) -10000 else smx
        my = if (modal) -10000 else smy

        g.fill(0, 0, width, height, Theme.DIM)
        drawPreview(g, mouseX, mouseY)
        drawTitleBar(g, f, smx, smy)
        val hidden = buttons.withIndex().filter { spots.isHidden(it.value.spot) }.map { HiddenButtonsTray.Entry(it.index, it.value) }
        if (openTrayOnFirstFrame) {
            openTrayOnFirstFrame = false
            if (hidden.isNotEmpty()) tray.show()
        }
        g.pose().pushMatrix()
        g.pose().scale(f, f)
        drawPanel(g, px0, PANEL_MARGIN, px1, uiH - PANEL_MARGIN)
        drawHints(g, f)
        // The tray and its notice live inside the window: under the tabs, above the key hints.
        val trayTop = (frameY0 / f).roundToInt() + 2
        tray.draw(g, hidden, guiScale(), 0, px0 - PANEL_MARGIN, trayTop, hintsTop - 4, mx, my, selected) { entry, r ->
            if (!busy) tooltip(r[0], r[1], r[2], r[3], trayTip(entry.button))
        }
        g.pose().popMatrix()
        drawGhost(g, mouseX, mouseY)
        g.pose().pushMatrix()
        g.pose().scale(f, f)
        drawToasts(g, (px0 - PANEL_MARGIN) / 2, min(uiH, hintsTop))
        if (pickerOpen) drawPicker(g, smx, smy, uiW, uiH)
        drawTooltip(g, uiW, uiH)
        drawOpenDropdown(g, uiW, uiH, smx, smy)
        drawAlertModal(g, uiW, uiH, smx, smy)
        g.pose().popMatrix()
    }

    private fun guiScale(): Int = Minecraft.getInstance().window.guiScale

    /**
     * Fit the previewed screen into the area left of the panel, below the tabs, above the key-hint
     * panel, under a title bar. With the outer buttons off only the two rings matter, so the view
     * zooms to them. At 1× and up the scale snaps to half steps, which keeps the pixel art crisp.
     */
    private fun layoutPreview() {
        val f = SoulUi.factor()
        val title = TITLE_H * f
        val hints = HINTS_H * f
        val areaW = previewRight.toFloat()
        val top = previewTop.toFloat()
        invX = (width - FRAME_WIDTH) / 2
        invY = (height - FRAME_HEIGHT) / 2
        spots = InventoryButtonSpots.layout(width, height, showOuter = showOuter, blocked = EquipmentDisplay.occupiedRect(width, invX, invY))
        var x0 = 0
        var y0 = 0
        var x1 = width
        var y1 = height
        if (!showOuter) {
            val padX = InventoryButtonSpots.CLEARANCE + SIZE + (InventoryButtonSpots.RINGS - 1) * InventoryButtonSpots.PITCH_X + ZOOM_MARGIN
            val padY = InventoryButtonSpots.CLEARANCE + SIZE + (InventoryButtonSpots.RINGS - 1) * InventoryButtonSpots.PITCH_Y + ZOOM_MARGIN
            x0 = max(0, invX - padX)
            y0 = max(0, invY - padY)
            x1 = minOf(width, invX + FRAME_WIDTH + padX)
            y1 = minOf(height, invY + FRAME_HEIGHT + padY)
            // Widen or heighten the ring area to the real window's aspect (never past the screen).
            val aspect = width.toFloat() / height
            var w = x1 - x0
            var h = y1 - y0
            if (w.toFloat() / h < aspect) w = minOf(width, (h * aspect).roundToInt()) else h = minOf(height, (w / aspect).roundToInt())
            x0 = (((x0 + x1) - w) / 2).coerceIn(0, width - w)
            y0 = (((y0 + y1) - h) / 2).coerceIn(0, height - h)
            x1 = x0 + w
            y1 = y0 + h
        }
        val fit = minOf((areaW - 2 * GAP) / (x1 - x0), (height - top - title - hints - 2 * GAP) / (y1 - y0))
        viewScale = if (fit >= 1f) floor(fit * 2f) / 2f else fit.coerceAtLeast(0.1f)
        val w = ((x1 - x0) * viewScale).roundToInt()
        val h = ((y1 - y0) * viewScale).roundToInt()
        frameX0 = ((areaW - w) / 2f).roundToInt()
        frameY0 = (top + (height - top - title - hints - h) / 2f + title).roundToInt()
        frameX1 = frameX0 + w
        frameY1 = frameY0 + h
        viewX = frameX0 - (x0 * viewScale).roundToInt()
        viewY = frameY0 - (y0 * viewScale).roundToInt()
        hintsTop = ((frameY0 + h) / f + HINTS_GAP).roundToInt()
    }

    /**
     * A macOS-style title bar over the preview, so it reads as the Minecraft window: traffic lights
     * on the left, then the profile tabs standing on the bar's bottom edge (the active one is the
     * window's own color, so it merges into the content below).
     */
    private fun drawTitleBar(g: GuiGraphicsExtractor, f: Float, smx: Int, smy: Int) {
        val w = ((frameX1 - frameX0 + 2) / f).toInt()
        val ox = (frameX0 - 1) / f
        val oy = frameY0 / f - TITLE_H
        g.pose().pushMatrix()
        g.pose().translate((frameX0 - 1).toFloat(), frameY0 - TITLE_H * f)
        g.pose().scale(f, f)
        roundRect(g, 0, 0, w, TITLE_H, BAR_BG, SoulRenderPipelines.ROUND_TOP)
        val dot = 8
        val dy = TITLE_H - TAB_H + (TAB_H - dot) / 2
        for ((i, color) in TRAFFIC_LIGHTS.withIndex()) {
            val dx = 8 + i * (dot + 5)
            roundRect(g, dx, dy, dx + dot, dy + dot, color, SoulRenderPipelines.ROUND_PILL)
        }
        g.pose().popMatrix()
        g.pose().pushMatrix()
        g.pose().translate((frameX0 - 1).toFloat(), frameY0 - TITLE_H * f)
        g.pose().scale(f, f)
        drawTabs(g, smx - ox, smy - oy, ox.roundToInt(), oy.roundToInt(), w)
        g.pose().popMatrix()
        // The shared bar works in UI space (regions too): the strip's local x plus ox; it sits in the gap above the tabs (local y 1).
        val barY1 = oy.roundToInt() + 1 - 7
        g.pose().pushMatrix()
        g.pose().scale(f, f)
        drawScrollbar(g, smx, smy, tabSb, ox.roundToInt() + TABS_X, barY1 - TAB_H, ox.roundToInt() + tabStripX1, barY1, horizontal = true)
        g.pose().popMatrix()
    }

    private fun drawPreview(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val mc = Minecraft.getInstance()
        val player = mc.player
        val vx = virtualX(mouseX.toDouble())
        val vy = virtualY(mouseY.toDouble())
        val rx = (vx - invX).toFloat()
        val ry = (vy - invY).toFloat()
        val pointing = overPreview(mouseX.toDouble(), mouseY.toDouble()) && !tray.busy && !overToast(mouseX.toDouble(), mouseY.toDouble())
        val moving = if (dragging && selected in buttons.indices) selected else -1
        val placing = moving >= 0 || (tray.dragged >= 0 && lifted)

        g.enableScissor(frameX0, frameY0, frameX1, frameY1)
        g.fill(frameX0, frameY0, frameX1, frameY1, SCREEN_BG)
        g.pose().pushMatrix()
        g.pose().translate(viewX.toFloat(), viewY.toFloat())
        g.pose().scale(viewScale, viewScale)
        for (spot in spots.spots) {
            val owner = buttons.indexOfFirst { it.spot == spot.id }
            if (placing && (owner < 0 || owner == moving)) {
                InventoryButtonPainter.spot(g, spot.x, spot.y, InventoryButtons.DROP_FILL, InventoryButtons.DROP_EDGE)
            } else if (owner < 0) {
                g.fill(spot.x, spot.y, spot.x + SIZE, spot.y + SIZE, GRID_CELL)
            }
        }
        val pickable = pointing && !placing && selected in buttons.indices
        g.pose().pushMatrix()
        g.pose().translate(invX.toFloat(), invY.toFloat())
        InventoryPreview.drawFrame(g) { slot -> pickable && overSlot(slot, rx, ry) }
        if (EquipmentDisplay.occupiedRect(width, invX, invY) != null) EquipmentDisplay.drawBackdrop(g, 0, 0)
        g.pose().popMatrix()
        val hovered = if (pointing && !placing) buttonAt(vx, vy) else -1
        for ((index, button) in buttons.withIndex()) {
            if (index == moving) continue
            val spot = spots.at(button.spot) ?: continue
            InventoryButtonPainter.draw(g, button, spot.x, spot.y, hovered == index, selected == index)
        }
        g.pose().popMatrix()
        if (player != null) InventoryPreview.drawPlayer(g, (viewX + invX * viewScale).roundToInt(), (viewY + invY * viewScale).roundToInt(), mouseX, mouseY, viewScale)
        g.disableScissor()
        g.fill(frameX0 - 1, frameY0 - 1, frameX1 + 1, frameY0, SCREEN_EDGE)
        g.fill(frameX0 - 1, frameY1, frameX1 + 1, frameY1 + 1, SCREEN_EDGE)
        g.fill(frameX0 - 1, frameY0, frameX0, frameY1, SCREEN_EDGE)
        g.fill(frameX1, frameY0, frameX1 + 1, frameY1, SCREEN_EDGE)
        // A button's own hover text is the in-game item tooltip, so it stays vanilla.
        if (hovered >= 0 && !armed) {
            g.setComponentTooltipForNextFrame(mc.font, InventoryButtonPainter.tooltip(buttons[hovered]), mouseX, mouseY)
        }
    }

    /** The button being dragged (out of the tray, or between spots), under the cursor at preview scale. */
    private fun drawGhost(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val fromTray = tray.dragged >= 0 && lifted
        val button = (if (fromTray) buttons.getOrNull(tray.dragged) else if (dragging) buttons.getOrNull(selected) else null) ?: return
        val vx = virtualX(mouseX.toDouble())
        val vy = virtualY(mouseY.toDouble())
        val x = if (fromTray) vx - SIZE / 2.0 else vx - grabX
        val y = if (fromTray) vy - SIZE / 2.0 else vy - grabY
        g.pose().pushMatrix()
        g.pose().translate(viewX.toFloat(), viewY.toFloat())
        g.pose().scale(viewScale, viewScale)
        InventoryButtonPainter.draw(g, button, x.roundToInt(), y.roundToInt(), hovered = false, selected = true, outline = InventoryButtons.DROP_EDGE)
        g.pose().popMatrix()
    }

    // ---------------------------------------------------------------- tabs

    /** What [id] holds, as shown: "Everywhere" first, then the dungeon run, then areas. */
    private fun held(id: String): List<String> =
        listOfNotNull("Everywhere".takeIf { InventoryButtonProfiles.everywhere(frame) == id }) +
            InventoryButtonProfiles.slotsOf(frame, id).map { InventoryButtonProfiles.slotLabel(it) }

    /** Default is the fallback, so its tab says so instead of listing places. */
    private fun tabHint(draft: Draft): String =
        if (draft.isDefault) "everywhere else" else held(draft.id).joinToString(", ").ifEmpty { "not used" }

    /** Tabs in the title bar's local space ([lx]/[ly] the cursor, local too); (ox, oy) is the bar's UI position for rects and regions. */
    private fun drawTabs(g: GuiGraphicsExtractor, lx: Float, ly: Float, ox: Int, oy: Int, barW: Int) {
        val semibold = MsdfFonts.menuSemibold
        val hints = drafts.map { tabHint(it) }
        val nameW = drafts.map { textWidth(it.name, fBody, semibold) }
        val hintW = hints.map { textWidth(it, fCaption) }
        fun reserve(i: Int) = if (drafts[i].isDefault) 0 else TAB_MENU_RESERVE
        fun dot(i: Int) = if (i == liveTab) 8 else 0
        val widths = drafts.indices.map { i -> (max(nameW[i] + dot(i) + reserve(i), hintW[i]) + 18).coerceIn(TAB_MIN_W, TAB_MAX_W) }
        val areaX1 = barW - 8
        val plusX = areaX1 - PLUS_W
        val stripX1 = plusX - TAB_GAP
        val content = widths.sum() + TAB_GAP * (drafts.size - 1)
        tabMaxScroll = max(0, content - (stripX1 - TABS_X)).toFloat()
        tabSb.content = content
        tabSb.view = stripX1 - TABS_X
        tabStripX1 = stripX1
        val reveal = revealTab
        if (reveal in drafts.indices) {
            revealTab = -1
            val start = widths.take(reveal).sum() + TAB_GAP * reveal
            val end = start + widths[reveal]
            val view = stripX1 - TABS_X
            if (start - TAB_FADE_W < tabScroll) tabScroll = (start - TAB_FADE_W).toFloat()
            if (end + TAB_FADE_W > tabScroll + view) tabScroll = (end + TAB_FADE_W - view).toFloat()
        }
        tabScroll = tabScroll.coerceIn(0f, tabMaxScroll)
        val y0 = TITLE_H - TAB_H
        val y1 = TITLE_H
        val interactive = !pickerOpen && !alertModalOpen && !dropdownOpen && !dragging && !lifted
        fun over(x0: Int, yy0: Int, x1: Int, yy1: Int) = interactive && lx >= x0 && lx <= x1 && ly >= yy0 && ly <= yy1
        beginClip(g, TABS_X, 0, stripX1, TITLE_H)
        var x = TABS_X - tabScroll.roundToInt()
        for (i in drafts.indices) {
            val w = widths[i]
            val draft = drafts[i]
            val active = i == tab
            val vx0 = max(x, TABS_X)
            val vx1 = min(x + w, stripX1)
            val hovered = over(vx0, y0, vx1, y1)
            roundRect(g, x, y0, x + w, y1, if (active) ACTIVE_TAB else if (hovered) TAB_HOVER else TAB_IDLE, SoulRenderPipelines.ROUND_TOP)
            var tx = x + 8
            if (i == liveTab) {
                roundRect(g, tx, y0 + 6, tx + 5, y0 + 11, LIVE, SoulRenderPipelines.ROUND_PILL)
                tx += 8
            }
            val nameColor = if (active) Theme.TEXT else Theme.TEXT_DIM
            drawTextV(g, ellipsize(draft.name, x + w - 10 - reserve(i) - tx, fBody, semibold), tx, y0 + 2, y0 + 15, nameColor, fBody, semibold)
            drawTextV(g, ellipsize(hints[i], w - 18, fCaption), x + 8, y0 + 15, y0 + 26, if (active) Theme.TEXT_DIM else Theme.TEXT_FAINT, fCaption)
            if (vx0 < vx1) {
                region(ox + vx0, oy + y0, ox + vx1, oy + y1) { if (tab == i) openTabMenu(i) else switchTab(i) }
                tabRects.add(intArrayOf(ox + vx0, oy + y0, ox + vx1, oy + y1, i))
            }
            // The "…" pill opens the tab menu; Default has none (no place settings, no rename).
            var overPill = false
            if (reserve(i) > 0 && (hovered || active)) {
                val px0 = x + w - 8 - 14
                val py0 = y0 + 4
                val shown = px0 >= TABS_X && px0 + 14 <= stripX1
                overPill = shown && over(px0, py0, px0 + 14, py0 + 10)
                roundRect(g, px0, py0, px0 + 14, py0 + 10, if (overPill) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
                for (d in 0..2) roundRect(g, px0 + 2 + d * 4, py0 + 4, px0 + 4 + d * 4, py0 + 6, if (overPill) Theme.TEXT else 0xFF999999.toInt(), SoulRenderPipelines.ROUND_PILL)
                if (shown) {
                    region(ox + px0 - 1, oy + py0 - 1, ox + px0 + 15, oy + py0 + 11) {
                        if (tab != i) switchTab(i)
                        openTabMenu(i)
                    }
                }
                if (overPill) tooltip(ox + px0, oy + py0, ox + px0 + 14, oy + py0 + 10, "Profile menu")
            }
            if (hovered && !overPill) {
                // A tab only explains what it cuts off: the full name, the full place list.
                val tip = ArrayList<String>()
                if (nameW[i] + dot(i) + reserve(i) > w - 18) tip += draft.name
                if (!draft.isDefault && hintW[i] > w - 18) tip += "§7" + held(draft.id).joinToString(", ")
                if (tip.isNotEmpty()) tooltip(ox + vx0, oy + y0, ox + vx1, oy + y1, tip)
            }
            x += w + TAB_GAP
        }
        endClip(g)
        if (tabScroll > 0f) drawTabFade(g, TABS_X, y0, y1, left = true)
        if (tabScroll < tabMaxScroll) drawTabFade(g, stripX1, y0, y1, left = false)
        val plusPos = min(TABS_X + content + TAB_GAP, plusX)
        val hovered = over(plusPos, y0, plusPos + PLUS_W, y1)
        roundRect(g, plusPos, y0, plusPos + PLUS_W, y1, if (hovered) TAB_HOVER else TAB_IDLE, SoulRenderPipelines.ROUND_TOP)
        drawTextV(g, "+", plusPos + (PLUS_W - textWidth("+", fHeading)) / 2, y0, y1, Theme.TEXT, fHeading)
        val vx0 = max(plusPos, TABS_X)
        val vx1 = min(plusPos + PLUS_W, areaX1)
        if (vx0 < vx1) region(ox + vx0, oy + y0, ox + vx1, oy + y1) { newProfile() }
        if (hovered) tooltip(ox + plusPos, oy + y0, ox + plusPos + PLUS_W, oy + y1, listOf("New profile", "§7Starts empty."))
    }

    /** Fade of the bar color over a clipped strip edge ([x] is the strip's edge) with a chevron hint. */
    private fun drawTabFade(g: GuiGraphicsExtractor, x: Int, y0: Int, y1: Int, left: Boolean) {
        val steps = TAB_FADE_W / 2
        for (s in 0 until steps) {
            val alpha = 0xF0 - s * (0xF0 / steps)
            val x0 = if (left) x + s * 2 else x - (s + 1) * 2
            g.fill(x0, y0, x0 + 2, y1, (alpha shl 24) or (BAR_BG and 0xFFFFFF))
        }
        val chev = if (left) "<" else ">"
        val cx = if (left) x + 2 else x - 2 - textWidth(chev, fBody, MsdfFonts.menuSemibold)
        drawTextV(g, chev, cx, y0, y1, Theme.TEXT_DIM, fBody, MsdfFonts.menuSemibold)
    }

    private fun openTabMenu(index: Int) {
        val draft = drafts.getOrNull(index)?.takeIf { !it.isDefault } ?: return
        val rect = tabRects.firstOrNull { it[4] == index } ?: return
        openDropdownAt(tabMenuToken, rect[0], rect[1], max(rect[2], rect[0] + 100), rect[3], TAB_MENU, -1) { pick ->
            when (TAB_MENU[pick]) {
                "Use everywhere" -> useEverywhere(draft.id)
                "Use in…" -> openPicker()
                "Rename…" -> renameProfile(index)
                "Duplicate" -> duplicateProfile(index)
                "Delete" -> deleteProfile(index)
            }
        }
    }

    // ---------------------------------------------------------------- key hints, trash, toasts

    /**
     * Under the window: the mouse and key hints in a small panel of the window's style, wrapping to
     * two lines (the short set when the full one needs more). While a button is dragged the same
     * place is the trash drop zone.
     */
    private fun drawHints(g: GuiGraphicsExtractor, f: Float) {
        val left = (frameX0 / f).roundToInt()
        val w = ((frameX1 - frameX0) / f).roundToInt()
        val cx = left + w / 2
        val top = hintsTop
        trashRect = IntArray(0)
        if (dragging || lifted) {
            val label = "Drop here to delete"
            val ownW = 13 + 6 + textWidth(label, fCaption) + 2 * HINTS_PAD_X
            val tw = min(w, max(hintW, ownW))
            val th = max(hintH, 19)
            val r = intArrayOf(cx - tw / 2, top, cx - tw / 2 + tw, top + th)
            trashRect = r
            val over = within(r, mx, my)
            roundRect(g, r[0], r[1], r[2], r[3], if (over) TRASH_EDGE_OVER else TRASH_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
            roundRect(g, r[0] + 1, r[1] + 1, r[2] - 1, r[3] - 1, if (over) TRASH_BG_OVER else TRASH_BG, SoulRenderPipelines.ROUND_MEDIUM)
            val color = if (over) 0xFFFFFFFF.toInt() else TRASH_TEXT
            val contentW = 13 + 6 + textWidth(label, fCaption)
            val ix = cx - contentW / 2
            val cy = (r[1] + r[3]) / 2
            drawIcon(g, "trash_body", ix, cy - 7, 13, color)
            drawIcon(g, if (over) "trash_lid_open" else "trash_lid", ix, cy - 7, 13, color)
            drawTextV(g, label, ix + 19, cy - 6, cy + 6, color, fCaption)
            return
        }
        val semibold = MsdfFonts.menuSemibold
        val sep = 6 + textWidth("·", fCaption) + 6
        val inner = w - 2 * HINTS_PAD_X - 2
        fun itemW(k: Pair<String, String>) = textWidth(k.first, fCaption, semibold) + textWidth(" " + k.second, fCaption)
        fun lines(set: List<Pair<String, String>>): List<List<Pair<String, String>>> {
            val out = ArrayList<MutableList<Pair<String, String>>>()
            var lineW = 0
            for (k in set) {
                val iw = itemW(k)
                if (out.isNotEmpty() && lineW + sep + iw <= inner) {
                    out.last() += k
                    lineW += sep + iw
                } else {
                    out += mutableListOf(k)
                    lineW = iw
                }
            }
            return out
        }
        val full = lines(HINTS_FULL)
        val shown = (if (full.size <= 2) full else lines(HINTS_SHORT)).take(2)
        val lineWs = shown.map { line -> line.sumOf { itemW(it) } + sep * (line.size - 1) }
        val pw = min(w, (lineWs.maxOrNull() ?: 0) + 2 * HINTS_PAD_X + 2)
        val ph = shown.size * 12 + 6
        hintW = pw
        hintH = ph
        roundRect(g, cx - pw / 2, top, cx - pw / 2 + pw, top + ph, SCREEN_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, cx - pw / 2 + 1, top + 1, cx - pw / 2 + pw - 1, top + ph - 1, HINTS_BG, SoulRenderPipelines.ROUND_MEDIUM)
        var y = top + 3
        for ((li, line) in shown.withIndex()) {
            var x = cx - lineWs[li] / 2
            for ((ki, k) in line.withIndex()) {
                if (ki > 0) {
                    drawTextV(g, "·", x + 6, y, y + 12, HINT_SEP, fCaption)
                    x += sep
                }
                drawTextV(g, k.first, x, y, y + 12, Theme.TEXT, fCaption, semibold)
                drawTextV(g, " " + k.second, x + textWidth(k.first, fCaption, semibold), y, y + 12, HINT_TEXT, fCaption)
                x += itemW(k)
            }
            y += 12
        }
    }

    /** The "Reverted…" toast and the "Button deleted · Undo" toast, centered over the preview's bottom. */
    private fun drawToasts(g: GuiGraphicsExtractor, cx: Int, base: Int) {
        undoRect = IntArray(0)
        val text = toastText
        if (text != null && System.currentTimeMillis() > toastUntil) toastText = null
        if (text != null && toastText != null) toast(g, cx, base - 40, textWidth(text, F105)) { x -> drawTextV(g, text, x, base - 40, base - 14, Theme.TEXT, F105) }
        if (undo == null) return
        val semibold = MsdfFonts.menuSemibold
        val lead = "Button deleted"
        val leadW = textWidth(lead, F105)
        val dotW = textWidth("·", F105)
        val undoW = textWidth("Undo", F105, semibold)
        val contentW = leadW + 6 + dotW + 6 + undoW
        val y0 = base - 30
        toast(g, cx, y0, contentW) { x ->
            val ux = x + leadW + 6 + dotW + 6
            val r = intArrayOf(ux - 3, y0 + 4, ux + undoW + 3, y0 + 22)
            val hover = within(r, mx, my)
            drawTextV(g, lead, x, y0, y0 + 26, Theme.TEXT, F105)
            drawTextV(g, "·", x + leadW + 6, y0, y0 + 26, HINT_SEP, F105)
            drawTextV(g, "Undo", ux, y0, y0 + 26, if (hover) UNDO_HOVER else UNDO, F105, semibold)
            if (hover) g.fill(ux, y0 + 19, ux + undoW, y0 + 20, UNDO_HOVER)
            undoRect = r
        }
    }

    private inline fun toast(g: GuiGraphicsExtractor, cx: Int, y0: Int, contentW: Int, content: (Int) -> Unit) {
        val w = contentW + 24
        val x0 = cx - w / 2
        roundRect(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + 27, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, x0, y0, x0 + w, y0 + 26, TOAST_BG, SoulRenderPipelines.ROUND_MEDIUM)
        content(x0 + 12)
    }

    private fun showToast(text: String) {
        toastText = text
        toastUntil = System.currentTimeMillis() + TOAST_MS
    }

    /** Whether the raw-px point is on the undo toast, which sits over the preview. */
    private fun overToast(x: Double, y: Double): Boolean {
        if (undo == null) return false
        val f = SoulUi.factor()
        val r = undoRect
        return r.size == 4 && within(intArrayOf(r[0] - 60, r[1] - 4, r[2] + 12, r[3] + 4), (x / f).roundToInt(), (y / f).roundToInt())
    }

    // ---------------------------------------------------------------- sidebar

    private fun drawPanel(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        roundRect(g, x0, y0, x1, y1, Theme.PANEL)
        val footTop = y1 - FOOTER_H
        bodyRect = intArrayOf(x0, y0, x1, footTop)
        panelSb.view = footTop - y0
        panelSb.clamp()
        beginClip(g, x0, y0, x1, footTop)
        val left = x0 + 10
        val right = x1 - 10
        val top = y0 + 10 - panelSb.pos.roundToInt()
        val button = buttons.getOrNull(selected)
        var y = sectionHead(g, "Button", left, right, top, if (button != null) "${selected + 1} of ${buttons.size}" else null)
        y = if (button != null) buttonSection(g, button, left, right, y) else card(g, left, right, y) { emptyCard(g, left, right, it) }
        // Profile + Editor sit flush above the footer when there is room; taller content flows top-down and scrolls.
        val outerDry = dry
        dry = true
        val lowerH = 19 + profileCard(g, left, right, 0) + 14 + 19 + editorCard(g, left, right, 0)
        dry = outerDry
        val natural = y + 14
        val lowTop = max(natural, footTop - 10 - 2 - lowerH)
        y = sectionHead(g, "Profile", left, right, lowTop, null)
        y = card(g, left, right, y) { profileCard(g, left, right, it) }
        y = sectionHead(g, "Editor", left, right, y + 14, null)
        y = card(g, left, right, y) { editorCard(g, left, right, it) }
        endClip(g)
        panelSb.content = y + 12 - top
        drawScrollbar(g, mx, my, panelSb, x0, y0, x1, footTop)
        drawFooter(g, x0, footTop, x1, y1)
    }

    private fun inBody(x0: Int, y0: Int, x1: Int, y1: Int): Boolean = !dry && within(bodyRect, mx, my) && mx in x0..x1 && my in y0..y1

    /** Run [body] dry to measure the card, draw its background (+ a 2 px left [stripe]), then draw it; returns its bottom. */
    private inline fun card(g: GuiGraphicsExtractor, x0: Int, x1: Int, top: Int, stripe: Boolean = false, body: (Int) -> Int): Int {
        val outer = dry
        dry = true
        val bottom = body(top)
        dry = outer
        if (!dry) {
            roundRect(g, x0, top, x1, bottom, Theme.SECTION, SoulRenderPipelines.ROUND_MEDIUM)
            if (stripe) g.fill(x0, top + 3, x0 + 2, bottom - 3, Theme.FIELD_BORDER)
        }
        return body(top)
    }

    /** Small caps section heading with an optional dim note on the right; returns the y below it. */
    private fun sectionHead(g: GuiGraphicsExtractor, text: String, left: Int, right: Int, top: Int, note: String?): Int {
        val caps = text.uppercase()
        val semibold = MsdfFonts.menuSemibold
        val tracking = 9f * 0.12f
        val w = SoulText.widthTracked(caps, F9, semibold, tracking)
        SoulText.drawCenteredTracked(g, caps, (left + 2 + w / 2f).roundToInt(), top + 7, F9, HEAD, semibold, tracking, scissor = currentClip)
        if (note != null) drawTextV(g, note, right - 2 - textWidth(note, fCaption), top, top + 14, Theme.TEXT_FAINT, fCaption)
        return top + 14 + 5
    }

    /** The selected button's card plus its Advanced fold. */
    private fun buttonSection(g: GuiGraphicsExtractor, b: InventoryButton, left: Int, right: Int, top: Int): Int {
        var y = card(g, left, right, top) { buttonCard(g, b, left, right, it) }
        y += 6
        val hover = inBody(left, y, right, y + 24)
        if (!dry) {
            roundRect(g, left, y, right, y + 24, if (hover) CHIP_HOVER else Theme.FIELD_BG, SoulRenderPipelines.ROUND_MEDIUM)
            if (advancedOpen) caret(g, left + 11, y + 11) else caretRight(g, left + 10, y + 12, Theme.CHIP_TEXT)
            drawTextV(g, "Advanced", left + 20, y, y + 24, Theme.TEXT, fBody)
            drawTextV(g, "1 more", right - 8 - textWidth("1 more", fCaption), y, y + 24, Theme.TEXT_DIM, fCaption)
            clippedRegion(left, y, right, y + 24, bodyRect) { advancedOpen = !advancedOpen }
        }
        y += 24
        if (!advancedOpen) return y
        return card(g, left, right, y + 4, stripe = true) {
            var ry = it + 8
            ry = row(g, left + 8, right - 8, ry, "Icon from a menu", "Closes the editor; middle-click an item in any menu (skins, SkyBlock textures).", Theme.TEXT_DIM, buttonW("Pick…", F105, 9)) { cx, cy ->
                val bw = buttonW("Pick…", F105, 9)
                button(g, cx, cy - 10, cx + bw, cy + 10, "Pick…", OUTLINE, Theme.SECTION, F105) { pickFromMenu() }
            }
            ry + 8
        }
    }

    private fun buttonCard(g: GuiGraphicsExtractor, b: InventoryButton, x0: Int, x1: Int, top: Int): Int {
        val button = b
        val l = x0 + 8
        val r = x1 - 8
        var y = top + 8
        val command = command(button)
        val hidden = spots.isHidden(button.spot)
        val off = spots.isSwitchedOff(button.spot)
        // Top: icon, name, where it sits, Duplicate + Delete.
        val delX = r - 22
        val dupX = delX - 8 - 22
        if (!dry) {
            roundRect(g, l, y, l + 26, y + 26, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, l + 1, y + 1, l + 25, y + 25, Theme.ICON_BOX, SoulRenderPipelines.ROUND_SMALL)
            g.fakeItem(InventoryButtonIcons.stack(button), l + 5, y + 5)
            val title = if (button.label.isNotBlank()) button.label else if (command.isNotEmpty()) "/$command" else "New button"
            val where = if (off) "Off: on a screen-edge spot" else if (hidden) "Hidden at GUI scale ${guiScale()}" else InventoryButtonSpots.describe(button.spot)
            val tx = l + 26 + 8
            drawTextV(g, ellipsize(title, dupX - 8 - tx, F12, MsdfFonts.menuSemibold), tx, y, y + 15, Theme.TEXT, F12, MsdfFonts.menuSemibold)
            drawTextV(g, ellipsize(where, dupX - 8 - tx, fCaption), tx, y + 15, y + 27, if (hidden || off) WARN else Theme.TEXT_DIM, fCaption)
            iconButton(g, dupX, y + 2, "copy", false, listOf("Duplicate", "§7Next free spot along its row or side")) { duplicate() }
            iconButton(g, delX, y + 2, "trash", true, listOf("Delete", "§7Del, or drag it onto the trash")) { delete() }
        }
        y += 27
        if (hidden) y = note(g, l, r, y + 8, "Its spot does not fit at this GUI scale or sits under the equipment display.", "Place…") { tray.show() }
        if (off) y = note(g, l, r, y + 8, "Screen-edge buttons are off.", "Turn on") { setOuter(true) }

        // Command: "/" prefix, presets while empty or on request.
        y += 8
        if (!dry) {
            drawTextV(g, "Command", l + 1, y, y + 14, Theme.TEXT, fBody)
            if (command.isNotEmpty()) {
                val link = if (chipsOpen) "Hide presets" else "Presets"
                val lw = textWidth(link, fCaption)
                val hover = inBody(r - lw, y, r, y + 14)
                drawTextV(g, link, r - lw, y, y + 14, if (hover) Theme.ACCENT else Theme.TEXT_DIM, fCaption)
                clippedRegion(r - lw - 2, y, r, y + 14, bodyRect) { chipsOpen = !chipsOpen }
            }
        }
        y += 17
        if (!dry) {
            drawTextField(g, l, y, r, y + 22, commandField, "storage", bodyRect, inset = 13) { focus(commandField) }
            drawTextV(g, "/", l + 6, y, y + 22, Theme.TEXT_FAINT)
        }
        y += 22
        if (command.isEmpty() || chipsOpen) y = drawChips(g, command, l, r, y + 5)

        // Icon: preview + item id, suggestions while typing, the inventory hint.
        y += 8
        if (!dry) drawTextV(g, "Icon", l + 1, y, y + 14, Theme.TEXT, fBody)
        y += 17
        if (!dry) {
            roundRect(g, l, y, l + 22, y + 22, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, l + 1, y + 1, l + 21, y + 21, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
            g.fakeItem(InventoryButtonIcons.stack(button), l + 3, y + 3)
            drawTextField(g, l + 26, y, r, y + 22, itemField, "Item id, e.g. chest", bodyRect) { focus(itemField) }
        }
        y += 22
        if (itemField.focused) y = drawSuggestions(g, l, r, y)
        val unknown = InventoryButtonIcons.item(itemField.text) == null && itemField.text.isNotBlank()
        y = paragraph(g, if (unknown) "Unknown item id; the icon stays as it was." else "Or click any item in the inventory to copy its look.", l, r, y + 3, if (unknown) WARN else Theme.TEXT_FAINT)

        // Name (hover text).
        y += 8
        if (!dry) {
            drawTextV(g, "Name", l + 1, y, y + 14, Theme.TEXT, fBody)
            drawTextV(g, "shown on hover", l + 1 + textWidth("Name", fBody) + 6, y, y + 14, Theme.TEXT_FAINT, fCaption)
        }
        y += 17
        if (!dry) drawTextField(g, l, y, r, y + 22, labelField, "Optional", bodyRect) { focus(labelField) }
        y += 22

        // Where it shows.
        val small = if (button.inventoryOnly) "Now: only in your inventory" else "Now: in every menu"
        y = row(g, l, r, y + 6, "Also in chests and menus", small, Theme.TEXT_DIM, toggleW) { cx, cy ->
            toggle(g, cx, cy - toggleH / 2, !button.inventoryOnly) { updateSelected { copy(inventoryOnly = !inventoryOnly) } }
        }
        return y + 8
    }

    /** Preset command chips; returns the y below them. */
    private fun drawChips(g: GuiGraphicsExtractor, current: String, left: Int, right: Int, top: Int): Int {
        var x = left
        var y = top
        for (preset in CHIPS) {
            val w = textWidth(preset.name, F95) + 14
            if (x + w > right && x > left) {
                x = left
                y += 19
            }
            if (!dry) {
                val on = current.equals(preset.command, ignoreCase = true)
                val hover = inBody(x, y, x + w, y + 15)
                roundRect(g, x, y, x + w, y + 15, if (on) CHIP_ON else if (hover) CHIP_HOVER else Theme.ICON_BOX, SoulRenderPipelines.ROUND_PILL)
                drawTextV(g, preset.name, x + 7, y, y + 15, if (on) Theme.CHIP_HERE_TEXT else if (hover) Theme.TEXT else Theme.CHIP_TEXT, F95)
                clippedRegion(x, y, x + w, y + 15, bodyRect) { applyPreset(preset) }
                if (hover) tooltip(x, y, x + w, y + 15, listOf("${preset.name}  §7/${preset.command}", "§7${preset.purpose}"))
            }
            x += w + 4
        }
        return y + 15
    }

    private fun drawSuggestions(g: GuiGraphicsExtractor, left: Int, right: Int, top: Int): Int {
        val query = itemField.text
        if (query != suggestionQuery) {
            suggestionQuery = query
            suggestionCache = InventoryButtonIcons.suggestions(query, MAX_SUGGESTIONS)
        }
        if (suggestionCache.isEmpty()) return top
        val y0 = top + 4
        val columns = max(1, (right - left + 2) / 24)
        if (!dry) {
            for ((index, id) in suggestionCache.withIndex()) {
                val cx = left + (index % columns) * 24
                val cy = y0 + (index / columns) * 24
                val hover = inBody(cx, cy, cx + 22, cy + 22)
                roundRect(g, cx, cy, cx + 22, cy + 22, if (hover) Theme.CONTROL_HOVER else Theme.ICON_BOX, SoulRenderPipelines.ROUND_SMALL)
                InventoryButtonIcons.item(id)?.let { g.fakeItem(it.defaultInstance, cx + 3, cy + 3) }
                clippedRegion(cx, cy, cx + 22, cy + 22, bodyRect) { pickItem(id) }
                if (hover) tooltip(cx, cy, cx + 22, cy + 22, id.removePrefix("minecraft:"))
            }
        }
        return y0 + ((suggestionCache.size + columns - 1) / columns) * 24 - 2
    }

    /** "Click a button to edit it", or the empty profile's start. */
    private fun emptyCard(g: GuiGraphicsExtractor, x0: Int, x1: Int, top: Int): Int {
        val cx = (x0 + x1) / 2
        val draft = drafts[tab]
        val empty = buttons.isEmpty()
        var y = top + 10
        if (!dry) drawIcon(g, if (empty) "plus" else "cursor", cx - 10, y, 20, ICON_IDLE)
        y += 24
        val title = if (empty) "${draft.name} has no buttons yet" else "Click a button to edit it"
        if (!dry) drawTextCentered(g, ellipsize(title, x1 - x0 - 12, fBody, MsdfFonts.menuSemibold), cx, y + 2, Theme.TEXT_DIM, fBody, MsdfFonts.menuSemibold)
        y += 18
        val text = if (empty) "Double-click a free spot in the preview, or add one below." else "Drag buttons between spots. Double-click a free spot to add one."
        for (line in wrapText(text, x1 - x0 - 12, fCaption)) {
            if (!dry) drawTextCentered(g, line, cx, y + 2, Theme.TEXT_FAINT, fCaption)
            y += 13
        }
        val defaults = drafts[0].buttons.size
        if (empty && !draft.isDefault && defaults > 0) {
            val label = "Copy Default's $defaults buttons"
            val bw = min(x1 - x0 - 12, buttonW(label, fBody, 12))
            y += 8
            button(g, cx - bw / 2, y, cx - bw / 2 + bw, y + 22, label, OUTLINE, Theme.SECTION, fBody) { copyFromDefault() }
            y += 22
        }
        return y + 8
    }

    private fun profileCard(g: GuiGraphicsExtractor, x0: Int, x1: Int, top: Int): Int {
        val l = x0 + 8
        val r = x1 - 8
        val draft = drafts[tab]
        var y = top + 8
        if (!dry) {
            var nameRight = r
            if (tab == liveTab) {
                val text = "Live here"
                val pw = 6 + 5 + 4 + textWidth(text, F95) + 6
                roundRect(g, r - pw, y, r, y + 15, LIVE_BG, SoulRenderPipelines.ROUND_PILL)
                roundRect(g, r - pw + 6, y + 5, r - pw + 11, y + 10, LIVE, SoulRenderPipelines.ROUND_PILL)
                drawTextV(g, text, r - pw + 15, y, y + 15, LIVE, F95)
                nameRight -= pw + 6
            }
            drawTextV(g, ellipsize(draft.name, nameRight - l, F12, MsdfFonts.menuSemibold), l, y, y + 15, Theme.TEXT, F12, MsdfFonts.menuSemibold)
        }
        y += 15
        // Default is the fallback, not a place holder: no place settings, no rename/duplicate/delete.
        if (draft.isDefault) return paragraph(g, "Used wherever no other profile is active.", l, r, y + 2, Theme.TEXT_DIM) + 8

        y += 9
        if (!dry) drawTextV(g, "Used in", l + 1, y, y + 12, Theme.TEXT_DIM, fCaption)
        y += 16
        val here = hereSlot()
        val places = listOfNotNull(("Everywhere" to null as String?).takeIf { InventoryButtonProfiles.everywhere(frame) == draft.id }) +
            InventoryButtonProfiles.slotsOf(frame, draft.id).map { InventoryButtonProfiles.slotLabel(it) to it }
        if (places.isEmpty()) {
            y = paragraph(g, "Not used anywhere yet: Default shows instead.", l, r, y, Theme.TEXT_FAINT)
        } else {
            var x = l
            for ((label, slot) in places) {
                val isHere = slot != null && slot == here
                val w = textWidth(label, F95) + 14 + if (isHere) 9 + 4 else 0
                if (x + w > r && x > l) {
                    x = l
                    y += 19
                }
                if (!dry) {
                    roundRect(g, x, y, x + w, y + 15, Theme.ICON_BOX, SoulRenderPipelines.ROUND_PILL)
                    var tx = x + 7
                    if (isHere) {
                        drawIcon(g, "pin", tx, y + 3, 9, Theme.ACCENT_LIGHT)
                        tx += 13
                    }
                    drawTextV(g, label, tx, y, y + 15, Theme.CHIP_TEXT, F95)
                    if (isHere && inBody(x, y, x + w, y + 15)) tooltip(x, y, x + w, y + 15, "You're here")
                }
                x += w + 4
            }
            y += 15
        }
        y += 8
        if (places.isEmpty()) {
            button(g, l, y, r, y + 22, "Choose where to use it", ACCENT, Theme.SECTION, F105) { openPicker() }
        } else {
            button(g, l, y, r, y + 22, "Choose places…", OUTLINE, Theme.SECTION, F105) { openPicker() }
        }
        y += 30
        val bw = (r - l - 8) / 3
        button(g, l, y, l + bw, y + 22, "Rename…", OUTLINE, Theme.SECTION, F105) { renameProfile(tab) }
        button(g, l + bw + 4, y, l + 2 * bw + 4, y + 22, "Duplicate", OUTLINE, Theme.SECTION, F105) { duplicateProfile(tab) }
        button(g, l + 2 * bw + 8, y, r, y + 22, "Delete…", OUTLINE, Theme.SECTION, F105, danger = true) { deleteProfile(tab) }
        return y + 22 + 8
    }

    private fun editorCard(g: GuiGraphicsExtractor, x0: Int, x1: Int, top: Int): Int {
        val l = x0 + 8
        val r = x1 - 8
        val off = buttons.count { spots.isSwitchedOff(it.spot) }
        val hidden = buttons.count { spots.isHidden(it.spot) }
        var y = top + 8
        val small = if (off > 0) "$off button${if (off == 1) "" else "s"} switched off" else "Spots along the screen edges, outside the rings. All profiles."
        y = row(g, l, r, y, "Screen-edge buttons", small, if (off > 0) WARN else Theme.TEXT_DIM, toggleW) { cx, cy ->
            toggle(g, cx, cy - toggleH / 2, showOuter) { setOuter(!showOuter) }
        }
        if (hidden > 0) {
            if (!dry) g.fill(l - 2, y, r + 2, y + 1, ROW_SEP)
            val label = if (tray.open) "Tray open" else "Place…"
            val bw = buttonW(label, F105, 9)
            y = row(g, l, r, y, "$hidden hidden at GUI scale ${guiScale()}", "No room for their spot here.", Theme.TEXT_DIM, bw) { cx, cy ->
                button(g, cx, cy - 10, cx + bw, cy + 10, label, PLAIN, Theme.SECTION, F105, enabled = !tray.open) { tray.show() }
            }
        }
        return y + 8
    }

    private fun drawFooter(g: GuiGraphicsExtractor, x0: Int, top: Int, x1: Int, y1: Int) {
        roundRect(g, x0, top, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_BOTTOM)
        g.fill(x0, top, x1, top + 1, Theme.SEPARATOR)
        val by0 = top + 7
        val by1 = by0 + 26
        val doneW = buttonW("Done", fBody, 10)
        val revertW = buttonW("Revert", fBody, 10)
        val addFull = 10 + 4 + buttonW("Add button", fBody, 10)
        val room = x1 - x0 - 16 - doneW - revertW - 12
        // A narrow sidebar keeps the add button as its icon alone.
        val addW = if (addFull <= room) addFull else 26
        button(g, x0 + 8, by0, x0 + 8 + addW, by1, if (addW == addFull) "Add button" else "", OUTLINE, Theme.TOP_NAV, fBody, icon = "plus", viewport = null) { add() }
        if (addW != addFull && within(intArrayOf(x0 + 8, by0, x0 + 8 + addW, by1), mx, my)) tooltip(x0 + 8, by0, x0 + 8 + addW, by1, "Add button")
        val doneX = x1 - 8 - doneW
        button(g, doneX, by0, x1 - 8, by1, "Done", ACCENT, Theme.TOP_NAV, fBody, viewport = null) { onClose() }
        val revertX = doneX - 6 - revertW
        val changed = frame != original
        button(g, revertX, by0, doneX - 6, by1, "Revert", OUTLINE, Theme.TOP_NAV, fBody, enabled = changed, viewport = null) { revert() }
        if (changed && within(intArrayOf(revertX, by0, doneX - 6, by1), mx, my)) {
            tooltip(revertX, by0, doneX - 6, by1, listOf("Revert", "§7Undo every change since opening, in all profiles."))
        }
    }

    // ---------------------------------------------------------------- sidebar widgets

    private fun buttonW(label: String, size: Float, pad: Int): Int = textWidth(label, size) + 2 * pad

    /**
     * A sidebar button: [PLAIN], [OUTLINE] (a 1 px ring around [under], the color behind it) or
     * [ACCENT]; optional [icon] before the label; [danger] reddens an outline on hover. Its hit region
     * is cut to [viewport] (the scrolled body; null outside it).
     */
    private fun button(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        label: String,
        variant: Int,
        under: Int,
        size: Float,
        icon: String? = null,
        enabled: Boolean = true,
        danger: Boolean = false,
        viewport: IntArray? = bodyRect,
        onClick: () -> Unit,
    ) {
        if (dry) return
        val hover = enabled && (viewport == null || within(viewport, mx, my)) && mx in x0..x1 && my in y0..y1
        val text: Int
        if (variant == ACCENT) {
            roundRect(g, x0, y0, x1, y1, if (hover) Theme.ACCENT else Theme.ACCENT_DIM, SoulRenderPipelines.ROUND_SMALL)
            text = if (enabled) Theme.TEXT else 0x66FFFFFF
        } else if (variant == OUTLINE) {
            val edge = if (!enabled) OUTLINE_OFF else if (hover && danger) DANGER_EDGE else if (hover) OUTLINE_HOVER else Theme.OUTLINE
            roundRect(g, x0, y0, x1, y1, edge, SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, x0 + 1, y0 + 1, x1 - 1, y1 - 1, under, SoulRenderPipelines.ROUND_SMALL)
            text = if (!enabled) Theme.TEXT_FAINT else if (hover && danger) DANGER_TEXT else if (hover) Theme.TEXT else Theme.CHIP_TEXT
        } else {
            roundRect(g, x0, y0, x1, y1, if (hover) Theme.CONTROL_HOVER else Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
            text = if (enabled) Theme.TEXT else Theme.TEXT_FAINT
        }
        val labelW = if (label.isEmpty()) 0 else textWidth(label, size)
        val contentW = labelW + if (icon != null) 10 + if (label.isEmpty()) 0 else 4 else 0
        var x = (x0 + x1) / 2 - contentW / 2
        val cy = (y0 + y1) / 2
        if (icon != null) {
            drawIcon(g, icon, x, cy - 5, 10, text)
            x += 14
        }
        if (label.isNotEmpty()) drawTextV(g, label, x, y0, y1, text, size)
        if (enabled) {
            if (viewport == null) region(x0, y0, x1, y1, onClick) else clippedRegion(x0, y0, x1, y1, viewport, onClick)
        }
    }

    /** 22 px square outline button with a 12 px icon and a tooltip (Duplicate / Delete). */
    private fun iconButton(g: GuiGraphicsExtractor, x: Int, y: Int, icon: String, danger: Boolean, tip: List<String>, onClick: () -> Unit) {
        val hover = inBody(x, y, x + 22, y + 22)
        roundRect(g, x, y, x + 22, y + 22, if (hover && danger) DANGER_EDGE else if (hover) OUTLINE_HOVER else Theme.OUTLINE, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x + 1, y + 1, x + 21, y + 21, Theme.SECTION, SoulRenderPipelines.ROUND_SMALL)
        drawIcon(g, icon, x + 5, y + 5, 12, if (hover && danger) DANGER_TEXT else if (hover) Theme.TEXT else Theme.TEXT_DIM)
        clippedRegion(x, y, x + 22, y + 22, bodyRect, onClick)
        if (hover) tooltip(x, y, x + 22, y + 22, tip)
    }

    /** A switch whose hit region is cut to the scrolled body. */
    private fun toggle(g: GuiGraphicsExtractor, x: Int, y: Int, on: Boolean, onClick: () -> Unit) {
        if (dry) return
        val suppressed = suppressRegions
        suppressRegions = true
        drawToggle(g, x, y, on) {}
        suppressRegions = suppressed
        clippedRegion(x, y, x + toggleW, y + toggleH, bodyRect, onClick)
    }

    /**
     * A settings row: [label] (wrapping) with [small] under it on the left, a control [controlW] wide
     * on the right, at least 30 px tall; [control] gets the control's left x and the row's center y.
     * Returns the y below.
     */
    private inline fun row(
        g: GuiGraphicsExtractor,
        l: Int,
        r: Int,
        top: Int,
        label: String,
        small: String,
        smallColor: Int,
        controlW: Int,
        control: (Int, Int) -> Unit,
    ): Int {
        val textW = r - l - controlW - 8
        val labelLines = wrapText(label, textW, fBody)
        val smallLines = wrapText(small, textW, fCaption)
        val blockH = labelLines.size * 14 + smallLines.size * 12
        val h = max(30, blockH)
        if (!dry) {
            var y = top + (h - blockH) / 2
            for (line in labelLines) {
                drawTextV(g, line, l, y, y + 14, Theme.TEXT, fBody)
                y += 14
            }
            for (line in smallLines) {
                drawTextV(g, line, l, y, y + 12, smallColor, fCaption)
                y += 12
            }
            control(r - controlW, top + h / 2)
        }
        return top + h
    }

    /** Wrapped 10 px text, 12 px lines; returns the y below. */
    private fun paragraph(g: GuiGraphicsExtractor, text: String, l: Int, r: Int, top: Int, color: Int): Int {
        var y = top
        for (line in wrapText(text, r - l, fCaption)) {
            if (!dry) drawTextV(g, line, l, y, y + 12, color, fCaption)
            y += 12
        }
        return y
    }

    /** A muted note with a small button on its right (hidden or switched-off button). */
    private inline fun note(g: GuiGraphicsExtractor, l: Int, r: Int, top: Int, text: String, label: String, crossinline action: () -> Unit): Int {
        val bw = buttonW(label, fCaption, 8)
        val lines = wrapText(text, r - l - 13 - 6 - bw, fCaption)
        val h = max(lines.size * 12, 18) + 10
        if (!dry) {
            roundRect(g, l, top, r, top + h, NOTE_BG, SoulRenderPipelines.ROUND_SMALL)
            var y = top + (h - lines.size * 12) / 2
            for (line in lines) {
                drawTextV(g, line, l + 8, y, y + 12, Theme.TEXT_DIM, fCaption)
                y += 12
            }
            val cy = top + h / 2
            button(g, r - 5 - bw, cy - 9, r - 5, cy + 9, label, PLAIN, NOTE_BG, fCaption) { action() }
        }
        return top + h
    }

    private fun command(button: InventoryButton): String = button.command.trim().removePrefix("/").trimStart()

    private fun trayTip(button: InventoryButton): List<String> {
        val command = command(button)
        return listOfNotNull(button.label.takeIf { it.isNotBlank() }) +
            (if (command.isEmpty()) "§8No command" else "§7/$command") +
            "§8Was: ${InventoryButtonSpots.describe(button.spot)}"
    }

    // ---------------------------------------------------------------- use-in picker

    private fun openPicker() {
        dismissTransientEditors()
        pickerOpen = true
        pickerSb.pos = 0f
    }

    private fun closePicker() {
        pickerOpen = false
    }

    /** [slot] is an area name or the run key; null is Everywhere. [other] names the profile holding it when that is not this one. */
    private class Chip(val label: String, val slot: String?, val other: String?, val here: Boolean, val x0: Int, val y0: Int, val x1: Int)

    private fun profileName(id: String): String = drafts.firstOrNull { it.id == id }?.name ?: "Default"

    /** The profile holding [slot] (null = Everywhere) in the frame being drawn; null for an unset area. */
    private fun holder(slot: String?): String? =
        if (slot == null) InventoryButtonProfiles.everywhere(frame) else InventoryButtonProfiles.owner(frame, slot)

    private fun hereMarkW(): Int = 9 + 2 + textWidth("You're here", F95, MsdfFonts.menuSemibold)

    /** Chip layout for the picker body: captions with their y, chips with their rects; returns the height. */
    private fun layoutPicker(left: Int, right: Int, id: String): Triple<List<Pair<String, Int>>, List<Chip>, Int> {
        val captions = ArrayList<Pair<String, Int>>()
        val chips = ArrayList<Chip>()
        val here = hereSlot()
        var y = 0

        fun row(labels: List<Pair<String, String?>>) {
            var x = left
            for ((label, slot) in labels) {
                val owner = holder(slot)
                val other = if (owner != null && owner != id) ellipsize(profileName(owner), 80, fCaption) else null
                val isHere = slot != null && slot == here
                val w = textWidth(label, fBody) + 16 + (if (isHere) 6 + hereMarkW() else 0) + (if (other != null) 6 + textWidth(other, fCaption) else 0)
                if (x + w > right && x > left) {
                    x = left
                    y += 21
                }
                chips.add(Chip(label, slot, other, isHere, x, y, x + w))
                x += w + 4
            }
            y += 21 + 6
        }

        captions.add("Default for every place" to y)
        y += 13
        row(listOf("Everywhere" to null))
        val held = byArea.keys + original.seenAreas + cfg.inventoryButtons.seenAreas
        for ((group, areas) in InventoryButtonProfiles.pickerGroups(held)) {
            captions.add(group to y)
            y += 13
            val labels = areas.map { it to (it as String?) }
            row(if (group == "Dungeons") listOf(InventoryButtonProfiles.DUNGEON_RUN to InventoryButtonsConfig.RUN_KEY) + labels else labels)
        }
        return Triple(captions, chips, y)
    }

    private fun drawPicker(g: GuiGraphicsExtractor, pmx: Int, pmy: Int, uiW: Int, uiH: Int) {
        val draft = drafts[tab]
        val id = draft.id
        g.fill(0, 0, uiW, uiH, 0xB0000000.toInt())
        region(0, 0, uiW, uiH) { closePicker() }
        val interactive = !alertModalOpen && !dropdownOpen
        val qx = if (interactive) pmx else -10000
        val qy = if (interactive) pmy else -10000

        val w = minOf(uiW - 24, 400)
        val pad = 14
        val left = pad
        val right = w - pad
        val (captions, chips, bodyH) = layoutPicker(left, right, id)
        val footerLines = wrapText("One profile per place. Ticking a place takes it from whichever profile has it; places nobody holds use Everywhere.", right - left - 74, fCaption)
        val headerH = 46
        val footerH = max(22, footerLines.size * 12) + pad + 8
        val maxBody = uiH - 24 - headerH - footerH
        val viewH = minOf(bodyH, max(40, maxBody))
        val h = headerH + viewH + footerH
        val x0 = (uiW - w) / 2
        val y0 = max(12, (uiH - h) / 2)

        roundRect(g, x0 - 1, y0 - 1, x0 + w + 1, y0 + h + 1, Theme.FIELD_BORDER)
        roundRect(g, x0, y0, x0 + w, y0 + h, Theme.TOP_NAV)
        region(x0, y0, x0 + w, y0 + h) {}

        drawTextV(g, ellipsize("Use ${draft.name} in…", w - 2 * pad, fHeading, MsdfFonts.menuSemibold), x0 + pad, y0 + 6, y0 + 24, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
        val area = SkyblockLocation.area
        val where = if (LocationApi.inDungeon) "You're in a dungeon run." else if (area != null) "You're in: $area" else "Tick every place this profile is for."
        drawText(g, where, x0 + pad, y0 + 25, Theme.TEXT_DIM, fCaption)

        val bodyTop = y0 + headerH
        pickerBody = intArrayOf(x0, bodyTop, x0 + w, bodyTop + viewH)
        pickerSb.content = bodyH
        pickerSb.view = viewH
        pickerSb.clamp()
        val oy = bodyTop - pickerSb.pos.roundToInt()
        beginClip(g, x0, bodyTop, x0 + w, bodyTop + viewH)
        for ((caption, cy) in captions) drawText(g, caption, x0 + left, oy + cy, Theme.TEXT_DIM, fCaption)
        val semibold = MsdfFonts.menuSemibold
        for (chip in chips) {
            val cx0 = x0 + chip.x0
            val cx1 = x0 + chip.x1
            val cy0 = oy + chip.y0
            val cy1 = cy0 + 17
            val owner = holder(chip.slot)
            val ticked = owner == id
            val hovered = within(pickerBody, qx, qy) && qx in cx0..cx1 && qy in cy0..cy1
            val fill = if (ticked) Theme.ACCENT_DIM else if (hovered) Theme.CONTROL_HOVER else Theme.CONTROL
            if (chip.here) {
                // The place you are in carries the config menu's "right now" pin and an accent outline.
                roundRect(g, cx0, cy0, cx1, cy1, HERE_EDGE, SoulRenderPipelines.ROUND_PILL)
                roundRect(g, cx0 + 1, cy0 + 1, cx1 - 1, cy1 - 1, fill, SoulRenderPipelines.ROUND_PILL)
            } else {
                roundRect(g, cx0, cy0, cx1, cy1, fill, SoulRenderPipelines.ROUND_PILL)
            }
            drawTextV(g, chip.label, cx0 + 8, cy0, cy1, if (ticked || hovered) Theme.TEXT else Theme.TEXT_DIM, fBody)
            if (chip.here) {
                val hx = cx0 + 8 + textWidth(chip.label, fBody) + 6
                val color = if (ticked) HERE_ON else Theme.ACCENT_LIGHT
                drawIcon(g, "pin", hx, cy0 + 4, 9, color)
                drawTextV(g, "You're here", hx + 11, cy0, cy1, color, F95, semibold)
            }
            if (chip.other != null) {
                drawTextV(g, chip.other, cx1 - 8 - textWidth(chip.other, fCaption), cy0, cy1, if (ticked) 0xFFBCD0FF.toInt() else Theme.TEXT_FAINT, fCaption)
            }
            clippedRegion(cx0, cy0, cx1, cy1, pickerBody) { toggleSlot(chip.slot, id) }
            if (hovered) tooltip(cx0, cy0, cx1, cy1, chipTip(chip, ticked, owner, draft.name))
        }
        endClip(g)
        drawScrollbar(g, qx, qy, pickerSb, x0, bodyTop, x0 + w, bodyTop + viewH)

        val footerTop = y0 + h - footerH + 8
        g.fill(x0 + 1, footerTop - 4, x0 + w - 1, footerTop - 3, Theme.SEPARATOR)
        var fy = footerTop + 2
        for (line in footerLines) {
            drawText(g, line, x0 + pad, fy, Theme.TEXT_DIM, fCaption)
            fy += 12
        }
        drawButton(g, qx, qy, x0 + w - pad - 64, footerTop, x0 + w - pad, footerTop + 22, "Done", accent = true) { closePicker() }
    }

    private fun chipTip(chip: Chip, ticked: Boolean, owner: String?, name: String): List<String> {
        val lines = ArrayList<String>()
        lines.add(chip.label)
        if (chip.slot == null) {
            lines.add("§7Used wherever a place has no profile of its own.")
            lines.add(if (ticked) "§7Click to hand it back to Default." else "§7Now: ${profileName(owner ?: DEFAULT_ID)}. Click to use $name.")
            return lines
        }
        if (chip.slot == InventoryButtonsConfig.RUN_KEY) lines.add("§7Inside a dungeon run (not the Dungeon Hub).")
        if (chip.here) lines.add("§aYou are here")
        lines.add(
            if (ticked) {
                "§7Used here. Click to release: it follows Everywhere again."
            } else if (owner != null) {
                "§7Used by ${profileName(owner)}. Click to use $name here instead."
            } else {
                "§7Follows Everywhere (${profileName(InventoryButtonProfiles.everywhere(frame))}). Click to use $name here."
            },
        )
        return lines
    }

    // ---------------------------------------------------------------- profiles

    private fun switchTab(index: Int) {
        if (index !in drafts.indices) return
        tab = index
        revealTab = index
        tray.reset()
        select(-1)
    }

    private fun newId(): String {
        while (true) {
            val id = UUID.randomUUID().toString().substring(0, 8)
            if (id != DEFAULT_ID && drafts.none { it.id == id }) return id
        }
    }

    private fun uniqueName(base: String): String {
        var n = drafts.size
        while (drafts.any { it.name.equals("$base $n", ignoreCase = true) }) n++
        return "$base $n"
    }

    private fun newProfile() {
        showPrompt("New profile", "Starts empty; pick where it is used next.", "Create", initial = uniqueName("Profile")) { text ->
            val name = text.trim()
            if (name.isEmpty()) {
                setPromptState(false, "Give it a name.")
            } else {
                closeAlert()
                drafts.add(Draft(newId(), name, mutableListOf()))
                switchTab(drafts.lastIndex)
                openPicker()
            }
        }
    }

    /** Fill the empty selected profile with a copy of Default's buttons (buttons are immutable data, no ids). */
    private fun copyFromDefault() {
        val draft = drafts[tab]
        if (draft.isDefault || draft.buttons.isNotEmpty()) return
        draft.buttons.addAll(drafts[0].buttons)
        select(-1)
    }

    private fun renameProfile(index: Int) {
        val draft = drafts.getOrNull(index)?.takeIf { !it.isDefault } ?: return
        showPrompt("Rename profile", "", "Rename", initial = draft.name) { text ->
            val name = text.trim()
            if (name.isEmpty()) {
                setPromptState(false, "Give it a name.")
            } else {
                draft.name = name
                closeAlert()
            }
        }
    }

    private fun duplicateProfile(index: Int) {
        val source = drafts.getOrNull(index)?.takeIf { !it.isDefault } ?: return
        // A copy holds no places: the original keeps them.
        drafts.add(Draft(newId(), uniqueName(source.name), source.buttons.toMutableList()))
        switchTab(drafts.lastIndex)
        openPicker()
    }

    private fun deleteProfile(index: Int) {
        val draft = drafts.getOrNull(index)?.takeIf { !it.isDefault } ?: return
        val count = draft.buttons.size
        val places = held(draft.id)
        val sentences = ArrayList<String>()
        if (count > 0) sentences.add("Its $count button${if (count == 1) " is" else "s are"} deleted too.")
        if ("Everywhere" in places) {
            sentences.add("Default takes over everywhere.")
        } else if (places.isNotEmpty()) {
            sentences.add("Default takes over where it was active (${places.joinToString(", ")}).")
        }
        if (sentences.isEmpty()) sentences.add("It has no buttons and isn't used anywhere.")
        showConfirm(
            "Delete ${draft.name}?",
            sentences.joinToString(" "),
            "Delete",
        ) {
            apply(InventoryButtonProfiles.withoutProfile(view(), draft.id))
            drafts.remove(draft)
            switchTab(0)
        }
    }

    // ---------------------------------------------------------------- activation

    /** The working copy as a config, for the pure resolution helpers. */
    private fun view(): InventoryButtonsConfig = original.copy(
        buttons = drafts[0].buttons.toList(),
        profiles = drafts.drop(1).map { it.profile() },
        activeEverywhere = everywhere,
        activeByArea = byArea.toMap(),
        showOuterButtons = showOuter,
    )

    private fun apply(next: InventoryButtonsConfig) {
        everywhere = next.activeEverywhere
        byArea.clear()
        byArea.putAll(next.activeByArea)
    }

    /** The slot "here" stands for: the dungeon run while in one, else the current area. Null when unknown. */
    private fun hereSlot(): String? =
        if (LocationApi.inDungeon) InventoryButtonsConfig.RUN_KEY else SkyblockLocation.area?.takeIf { it.isNotBlank() }

    private fun useEverywhere(id: String) = apply(InventoryButtonProfiles.useEverywhere(view(), id))

    /** A picker chip: ticked = this profile holds the place, so unticking hands it back (Everywhere falls to Default). */
    private fun toggleSlot(slot: String?, id: String) {
        val config = view()
        apply(
            if (slot == null) {
                InventoryButtonProfiles.useEverywhere(config, if (InventoryButtonProfiles.everywhere(config) == id) DEFAULT_ID else id)
            } else if (InventoryButtonProfiles.owner(config, slot) == id) {
                InventoryButtonProfiles.release(config, slot)
            } else {
                InventoryButtonProfiles.useIn(config, slot, id)
            },
        )
    }

    // ---------------------------------------------------------------- editing

    private inline fun updateSelected(block: InventoryButton.() -> InventoryButton) {
        if (selected in buttons.indices) buttons[selected] = buttons[selected].block()
    }

    private fun select(index: Int) {
        selected = index
        panelSb.pos = 0f
        dragging = false
        chipsOpen = false
        closeDropdown()
        val button = buttons.getOrNull(index)
        commandField.set(button?.command?.removePrefix("/").orEmpty())
        labelField.set(button?.label.orEmpty())
        itemField.set(button?.item?.removePrefix("minecraft:").orEmpty())
        fields.forEach { it.focused = false }
    }

    private fun focus(field: SoulTextField) {
        for (other in fields) if (other !== field) other.focused = false
        closeDropdown()
    }

    private fun pickItem(id: String) {
        updateSelected { copy(item = id, skin = "", model = "", glint = false) }
        itemField.set(id.removePrefix("minecraft:"))
    }

    private fun presetIcon(preset: CommandPreset): String = BuiltInRegistries.ITEM.getKey(preset.icon.item).toString()

    /**
     * Set [preset]'s command; also its name and icon where the button has none of its own (blank
     * label, the plain default icon, or what an earlier chip put there).
     */
    private fun applyPreset(preset: CommandPreset) {
        val button = buttons.getOrNull(selected) ?: return
        val blank = InventoryButton()
        var next = button.copy(command = preset.command)
        if (button.label.isBlank() || CHIPS.any { it.name == button.label }) next = next.copy(label = preset.name)
        val plainIcon = button.skin.isEmpty() && button.model.isEmpty() &&
            (button.item == blank.item || CHIPS.any { presetIcon(it) == button.item })
        if (plainIcon) next = next.copy(item = presetIcon(preset), glint = false)
        buttons[selected] = next
        commandField.set(next.command)
        labelField.set(next.label)
        itemField.set(next.item.removePrefix("minecraft:"))
    }

    /** Leave the editor (which saves the working copy) and arm the middle-click pick in game. */
    private fun pickFromMenu() {
        if (selected !in buttons.indices) return
        val target = selected
        val profileId = drafts[tab].id
        closeDropdown()
        super.onClose()
        InventoryButtons.startPick(profileId, target)
        soulChat("Middle-click an item in any menu to use its look as the button icon. /soul buttons cancels.")
    }

    private fun noFreeSpot() = showAlert("No free spot", "Every spot at GUI scale ${guiScale()} is taken.")

    /** First free spot in placement order (ring 1 first), or an alert when every spot is taken. */
    private fun firstFreeSpot(): String? {
        val spot = spots.spots.firstOrNull { free(it.id) }?.id
        if (spot == null) noFreeSpot()
        return spot
    }

    private fun add() {
        val spot = firstFreeSpot() ?: return
        insert(InventoryButton(spot = spot))
    }

    /** Double-click in the preview: a new button on the free spot under ([vx], [vy]). */
    private fun addAt(vx: Double, vy: Double) {
        val spot = spots.spotAt(vx, vy)?.takeIf { free(it.id) } ?: return
        insert(InventoryButton(spot = spot.id))
    }

    private fun insert(button: InventoryButton) {
        buttons.add(button)
        select(buttons.lastIndex)
        commandField.focused = true
    }

    /** The copy goes next to the original along its row or side ([InventoryButtonSpots.nextTo]) and gets selected. */
    private fun duplicate() {
        val source = buttons.getOrNull(selected) ?: return
        val spot = InventoryButtonSpots.nextTo(spots, source.spot, ::free) ?: return noFreeSpot()
        buttons.add(source.copy(spot = spot))
        select(buttons.lastIndex)
    }

    private fun delete() {
        if (selected !in buttons.indices) return
        buttons.removeAt(selected)
        select(-1)
    }

    /** Dropped on the trash: deleted without asking (Revert covers it), with Undo offered for [UNDO_MS]. */
    private fun trash(index: Int) {
        val button = buttons.getOrNull(index) ?: return
        undo = Undo(drafts[tab].id, index, button, System.currentTimeMillis() + UNDO_MS)
        buttons.removeAt(index)
        select(-1)
    }

    /** The button returns at its list position and on its spot (next to it when that was taken meanwhile), selected. */
    private fun undoTrash() {
        val u = undo ?: return
        undo = null
        val index = drafts.indexOfFirst { it.id == u.profileId }
        if (index < 0) return
        if (index != tab) switchTab(index)
        var button = u.button
        if (!free(button.spot) && spots.at(button.spot) != null) {
            button = button.copy(spot = InventoryButtonSpots.nextTo(spots, button.spot, ::free) ?: button.spot)
        }
        val at = min(u.index, buttons.size)
        buttons.add(at, button)
        select(at)
    }

    private fun revert() {
        val id = drafts[tab].id
        load()
        tab = drafts.indexOfFirst { it.id == id }.coerceAtLeast(0)
        undo = null
        select(-1)
        showToast("Reverted every profile to how it was when the editor opened")
    }

    private fun setOuter(on: Boolean) {
        showOuter = on
        dragging = false
    }

    private fun free(spot: String): Boolean = buttons.none { it.spot == spot }

    /** Drop the dragged button on the spot under its center; a button already there takes its old spot. */
    private fun dropDragged(vx: Double, vy: Double) {
        val button = buttons.getOrNull(selected) ?: return
        val target = spots.spotAt(vx - grabX + SIZE / 2.0, vy - grabY + SIZE / 2.0) ?: return
        if (target.id == button.spot) return
        val other = buttons.indexOfFirst { it.spot == target.id }
        if (other >= 0) buttons[other] = buttons[other].copy(spot = button.spot)
        buttons[selected] = button.copy(spot = target.id)
    }

    /** Drop a button dragged out of the tray: onto a free spot only; it becomes the selection. */
    private fun dropFromTray(index: Int, vx: Double, vy: Double) {
        val button = buttons.getOrNull(index) ?: return
        val target = spots.spotAt(vx, vy)?.takeIf { free(it.id) } ?: return
        buttons[index] = button.copy(spot = target.id)
        select(index)
    }

    // ---------------------------------------------------------------- preview input

    private fun overPreview(x: Double, y: Double): Boolean =
        x < previewRight && y >= frameY0 && !alertModalOpen && !dropdownOpen && !pickerOpen

    /** Screen x/y (GUI px of this screen) to the previewed screen's GUI px. */
    private fun virtualX(screenX: Double): Double = (screenX - viewX) / viewScale

    private fun virtualY(screenY: Double): Double = (screenY - viewY) / viewScale

    private fun buttonAt(vx: Double, vy: Double): Int {
        for (index in buttons.lastIndex downTo 0) {
            val spot = spots.at(buttons[index].spot) ?: continue
            if (spot.contains(vx, vy)) return index
        }
        return -1
    }

    private fun overSlot(slot: Slot, rx: Float, ry: Float): Boolean =
        rx >= slot.x - 1 && rx < slot.x + 17 && ry >= slot.y - 1 && ry < slot.y + 17

    private fun previewClick(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        fields.forEach { it.focused = false }
        if (click.button() != GLFW.GLFW_MOUSE_BUTTON_LEFT) return true
        val vx = virtualX(click.x())
        val vy = virtualY(click.y())
        val hit = buttonAt(vx, vy)
        if (hit >= 0) {
            // A press only selects; the drag starts once the pointer moved DRAG_THRESHOLD px.
            if (hit != selected) select(hit)
            val spot = spots.at(buttons[hit].spot) ?: return true
            armed = true
            pressX = click.x()
            pressY = click.y()
            grabX = (vx - spot.x).toFloat()
            grabY = (vy - spot.y).toFloat()
            return true
        }
        if (selected in buttons.indices) {
            val rx = (vx - invX).toFloat()
            val ry = (vy - invY).toFloat()
            val slot = Minecraft.getInstance().player?.inventoryMenu?.slots?.firstOrNull { !it.item.isEmpty && overSlot(it, rx, ry) }
            if (slot != null) {
                buttons[selected] = InventoryButtonIcons.capture(buttons[selected], slot.item)
                itemField.set(buttons[selected].item.removePrefix("minecraft:"))
                return true
            }
        }
        if (doubleClick) addAt(vx, vy) else select(-1)
        return true
    }

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        closeTooltip()
        val f = SoulUi.factor()
        val smx = (click.x() / f).roundToInt()
        val smy = (click.y() / f).roundToInt()
        val modal = alertModalOpen || dropdownOpen || pickerOpen
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT && !modal) {
            if (undo != null && undoRect.size == 4 && within(undoRect, smx, smy)) {
                undoTrash()
                return true
            }
            if (tray.click(smx, smy)) {
                armed = tray.dragged >= 0
                lifted = false
                pressX = click.x()
                pressY = click.y()
                fields.forEach { it.focused = false }
                return true
            }
        }
        if (!modal && overToast(click.x(), click.y())) return true
        if (overPreview(click.x(), click.y())) return previewClick(click, doubleClick)
        if (click.button() == GLFW.GLFW_MOUSE_BUTTON_RIGHT && !alertModalOpen && !pickerOpen) {
            val rect = tabRects.firstOrNull { smx in it[0]..it[2] && smy in it[1]..it[3] }
            if (rect != null) {
                closeDropdown()
                switchTab(rect[4])
                openTabMenu(rect[4])
                return true
            }
        }
        return super.mouseClicked(click, doubleClick)
    }

    // Drags follow the cursor from extractRenderState; only the release decides where a button lands.
    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        if (armed && !dragging && !lifted) {
            val dx = click.x() - pressX
            val dy = click.y() - pressY
            if (dx * dx + dy * dy > DRAG_THRESHOLD * DRAG_THRESHOLD) {
                if (tray.dragged >= 0) lifted = true else dragging = true
            }
        }
        return armed || dragging || tray.busy || super.mouseDragged(click, dragX, dragY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        val f = SoulUi.factor()
        val vx = virtualX(click.x())
        val vy = virtualY(click.y())
        val onTrash = trashRect.size == 4 && within(trashRect, (click.x() / f).roundToInt(), (click.y() / f).roundToInt())
        val wasArmed = armed
        armed = false
        if (tray.busy) {
            val index = tray.release()
            val wasLifted = lifted
            lifted = false
            if (index >= 0) {
                if (!wasLifted) select(index) else if (onTrash) trash(index) else dropFromTray(index, vx, vy)
            }
            return true
        }
        if (dragging) {
            dragging = false
            if (onTrash) trash(selected) else dropDragged(vx, vy)
            return true
        }
        if (wasArmed) return true
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (alertModalOpen) return true
        val f = SoulUi.factor()
        val smx = (mouseX / f).roundToInt()
        val smy = (mouseY / f).roundToInt()
        val barTop = frameY0 / f - TITLE_H
        if (!pickerOpen && !dropdownOpen && smx < previewRight / f && smy >= barTop && smy < frameY0 / f && tabMaxScroll > 0f) {
            val delta = if (scrollY != 0.0) scrollY else scrollX
            tabScroll = (tabScroll - delta.toFloat() * 24).coerceIn(0f, tabMaxScroll)
        } else if (pickerOpen) {
            pickerSb.scrollBy(-scrollY.toFloat() * 18)
        } else if (within(bodyRect, smx, smy)) {
            panelSb.scrollBy(-scrollY.toFloat() * 18)
        }
        return true
    }

    // ---------------------------------------------------------------- keys

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) return super.keyPressed(event)
        if (pickerOpen) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE || event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) closePicker()
            return true
        }
        val field = fields.firstOrNull { it.focused }
        if (field != null) {
            val key = event.key()
            if (key == GLFW.GLFW_KEY_ESCAPE || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                field.focused = false
            } else if (key == GLFW.GLFW_KEY_TAB) {
                val next = fields[(fields.indexOf(field) + if (event.hasShiftDown()) fields.size - 1 else 1) % fields.size]
                field.focused = false
                next.focused = true
            } else {
                field.keyPressed(event, minecraft?.keyboardHandler)
            }
            return true
        }
        if (selected in buttons.indices && !dropdownOpen && (event.key() == GLFW.GLFW_KEY_DELETE || event.key() == GLFW.GLFW_KEY_BACKSPACE)) {
            delete()
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        if (pickerOpen) return true
        val field = fields.firstOrNull { it.focused } ?: return super.charTyped(event)
        field.charTyped(event)
        return true
    }

    // ---------------------------------------------------------------- lifecycle

    override fun onEmptyClick() {
        super.onEmptyClick()
        fields.forEach { it.focused = false }
    }

    override fun dismissTransientEditors() {
        fields.forEach { it.focused = false }
        closeDropdown()
    }

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            regions.clear()
            return
        }
        if (dropdownOpen) {
            closeDropdown()
            return
        }
        if (pickerOpen) {
            closePicker()
            return
        }
        if (parent != null) minecraft?.setScreenAndShow(parent) else super.onClose()
    }

    /** Persist on every way out — Done, Escape, or a server opening a menu over this screen. */
    override fun removed() {
        val current = cfg.inventoryButtons
        val next = current.copy(
            buttons = drafts[0].buttons.toList(),
            profiles = drafts.drop(1).map { it.profile() },
            activeEverywhere = everywhere,
            activeByArea = byArea.toMap(),
            showOuterButtons = showOuter,
        )
        if (next != current) ConfigManager.update { copy(inventoryButtons = next) }
        super.removed()
    }
}
