package com.soulreturns.features.keybinds

import com.soulreturns.config.CommandBinding
import com.soulreturns.config.CommandKeybindsConfig
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.PresetBinding
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.keybinds.CommandKeybinds.Ref
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import com.soulreturns.util.KeyCombo
import com.soulreturns.util.KeyComboCapture
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * `/soul keybinds`: Hypixel menu presets as a foldable tile grid, the player's own commands as
 * compact rows below. Everything is edited in place: a key chip captures a new combo, ✎ turns the
 * command into a field, toggles switch single bindings or all of them off.
 */
class CommandKeybindScreen(private val parent: Screen? = null) : SoulScreenBase(Component.literal("Command Keybinds")) {
    companion object {
        private val WARNING = 0xFFFFD744.toInt()
        private val ERROR = 0xFFFF7777.toInt()
        private val PAUSED_BG = 0xFF2B2414.toInt()
        private val BADGE_BG = 0xFF3A2F17.toInt()
        private val BADGE_FG = 0xFFFFCF6B.toInt()
        private val CHIP_BG = 0xFF2E2E2E.toInt()
        private val CHIP_BG_DIM = 0xFF232323.toInt()
        private val CHIP_BORDER = 0xFF3C3C3C.toInt()
        private val CHIP_TEXT = 0xFFDDDDDD.toInt()
        private val CAPTURE_BG = 0xFF1D2A40.toInt()
        private val CAPTURE_TEXT = 0xFF9CC2FF.toInt()
        private val TILE_HOVER = 0xFF262626.toInt()
        private const val ROW_STRIPE = 0x08FFFFFF
        private const val ROW_HOVER = 0x12FFFFFF
        private val ICON_DIM = 0xB01E1E1E.toInt()
        private const val ICON_UNBOUND = 0x601E1E1E
        private const val TOP_H = 32
        private const val TILE_H = 28
        private const val TILE_MIN_W = 200
        private const val GAP = 6
        private const val ROW_H = 28
        private const val CHIP_H = 15
        private const val TOGGLE_W = 20
        private const val TOGGLE_H = 11
        private val COMMAND_ICON: ItemStack by lazy { ItemStack(Items.COMMAND_BLOCK) }
        private val CHEST_ICON: ItemStack by lazy { ItemStack(Items.CHEST) }

        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(CommandKeybindScreen(parent)) }
        }
    }

    /** The command being edited in its row; [index] null = a new entry not saved yet. */
    private class InlineEdit(var index: Int?, command: String) {
        val field = SoulTextField(command).apply { focused = true }
        var key = ""
        var error: String? = null

        init {
            field.onChange = { error = null }
            field.onRejected = { error = "Commands must be a single line; the pasted text was not inserted." }
        }
    }

    private val search = SoulTextField().apply { onChange = { scroll = 0f } }
    private var edit: InlineEdit? = null
    private var revealEdit = false

    // A Ref (saved entry) or the new InlineEdit; equality picks the chip that shows the capture.
    private var captureTarget: Any? = null
    private var capture = KeyComboCapture()

    private var importReview: CommandKeybindShare? = null
    private var reviewScroll = 0f
    private val listFooter = listOf(
        FooterAction("+ Add command", true) { addCommand() },
        FooterAction("Export") { exportBindings() },
        FooterAction("Import") { importClipboard() },
        FooterAction("Done") { if (commitEdit()) close() },
    )
    private val reviewFooter = listOf(
        FooterAction("Cancel") { cancelImport() },
        FooterAction("Replace", true) { confirmImport() },
    )
    private val clearFooter = listOf(
        FooterAction("Cancel") { cancelImport() },
        FooterAction("Clear all", true) { confirmImport() },
    )
    private var scroll = 0f
    private var contentHeight = 0
    private var bodyRect = intArrayOf(0, 0, 0, 0)

    // Per frame: the cursor in Soul units, whether the list takes input, and the hover tooltip.
    private var mx = 0
    private var my = 0
    private var interactive = true
    private var tooltip: List<Component>? = null

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        tooltip = null
        val scale = SoulUi.factor()
        val uiW = (width / scale).toInt()
        val uiH = (height / scale).toInt()
        mx = (mouseX / scale).roundToInt()
        my = (mouseY / scale).roundToInt()
        val incoming = importReview
        interactive = incoming == null && !alertModalOpen
        val cardW = (uiW * 0.94f).toInt().coerceAtMost(700)
        val cardH = (uiH * 0.94f).toInt().coerceAtMost(600)
        val x0 = (uiW - cardW) / 2
        val y0 = (uiH - cardH) / 2
        val x1 = x0 + cardW
        val y1 = y0 + cardH
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL)
        val footerButtons = if (incoming == null) listFooter else if (isEmpty(incoming)) clearFooter else reviewFooter
        val footerTop = y1 - footerHeight(cardW, footerButtons.size)
        var bodyTop = y0 + TOP_H
        if (incoming == null && !cfg.commandKeybinds.enabled) bodyTop = drawPausedBanner(g, x0 + 10, bodyTop + 6, x1 - 10)
        bodyRect = intArrayOf(x0 + 6, bodyTop, x1 - 6, max(bodyTop, footerTop))
        if (incoming == null) {
            drawTopBar(g, x0, y0, x1)
            drawList(g)
        } else {
            drawTextV(g, "Review clipboard import", x0 + 10, y0, y0 + TOP_H, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
            drawReview(g, incoming)
        }
        g.fill(x0 + 1, y0 + TOP_H - 1, x1 - 1, y0 + TOP_H, Theme.SEPARATOR)
        drawFooter(g, x0, footerTop, x1, footerButtons)
        drawAlertModal(g, uiW, uiH, mx, my)
        g.pose().popMatrix()
        val tip = tooltip
        if (tip != null && !alertModalOpen) g.setComponentTooltipForNextFrame(Minecraft.getInstance().font, tip, mouseX, mouseY)
    }

    // ---------------------------------------------------------------- chrome

    private class FooterAction(val label: String, val accent: Boolean = false, val action: () -> Unit)

    private fun footerColumns(width: Int, count: Int): Int = if (count > 2 && width < 320) 2 else count

    private fun footerHeight(width: Int, count: Int): Int {
        val columns = footerColumns(width, count)
        return ((count + columns - 1) / columns) * 27 + 7
    }

    private fun drawFooter(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, buttons: List<FooterAction>) {
        g.fill(x0 + 1, y0, x1 - 1, y0 + 1, Theme.SEPARATOR)
        val columns = footerColumns(x1 - x0, buttons.size)
        val cellWidth = ((x1 - x0 - 16 - (columns - 1) * 6) / columns).coerceAtLeast(1)
        for ((index, button) in buttons.withIndex()) {
            val bx = x0 + 8 + (index % columns) * (cellWidth + 6)
            val by = y0 + 6 + (index / columns) * 27
            drawButton(g, mx, my, bx, by, bx + cellWidth, by + 21, button.label, button.accent, button.action)
        }
    }

    private fun drawTopBar(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int) {
        val enabled = cfg.commandKeybinds.enabled
        val searchW = ((x1 - x0) * 0.3f).toInt().coerceIn(60, 170)
        val sx1 = x1 - 8
        val sx0 = sx1 - searchW
        drawTextField(g, sx0, y0 + 7, sx1, y0 + TOP_H - 7, search, "Search…") { edit?.field?.focused = false }

        val label = if (enabled) "Enabled" else "Paused"
        val tx1 = sx0 - 12
        val tx0 = tx1 - TOGGLE_W
        val lx = tx0 - 5 - textWidth(label, fCaption)
        val cy = y0 + TOP_H / 2
        drawTextV(g, label, lx, y0, y0 + TOP_H, if (enabled) Theme.TEXT_DIM else WARNING, fCaption)
        drawMiniToggle(g, tx0, cy, enabled, inBody = false, tip = masterTooltip(enabled)) { toggleMaster() }
        if (hot(lx - 2, cy - 8, tx0, cy + 8, inBody = false)) tooltip = masterTooltip(enabled)
        if (interactive) region(lx - 2, cy - 8, tx0, cy + 8) { toggleMaster() }

        val title = ellipsize("Command Keybinds", lx - 14 - (x0 + 10), fHeading, MsdfFonts.menuSemibold)
        drawTextV(g, title, x0 + 10, y0, y0 + TOP_H, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
    }

    private fun masterTooltip(enabled: Boolean): List<Component> = listOf(
        line("Command keybinds enabled", ChatFormatting.WHITE),
        line(if (enabled) "On: every enabled command binding fires." else "Off: command bindings pause; Soul feature bindings stay independent.", ChatFormatting.GRAY),
        line("Keys stay bound either way.", ChatFormatting.DARK_GRAY),
    )

    // ---------------------------------------------------------------- list

    /** Fixed under the top bar while command keybinds are paused; returns where the list starts. */
    private fun drawPausedBanner(g: GuiGraphicsExtractor, left: Int, top: Int, right: Int): Int {
        val lines = wrapText("Paused: no command keybind fires until you switch them back on. Keys stay bound.", right - left - 16, fCaption)
        val h = 8 + lines.size * 12
        roundRect(g, left, top, right, top + h, PAUSED_BG, SoulRenderPipelines.ROUND_SMALL)
        for ((i, text) in lines.withIndex()) drawText(g, text, left + 8, top + 5 + i * 12, WARNING, fCaption)
        return top + h + 2
    }

    private fun drawList(g: GuiGraphicsExtractor) {
        val (bx0, by0, bx1, by1) = bodyRect
        val settings = cfg.commandKeybinds
        val bindings = cfg.commandBindings
        val query = search.text.trim().lowercase().removePrefix("/")
        val left = bx0 + 4
        val right = bx1 - 4
        beginClip(g, bx0, by0, bx1, by1)
        var y = by0 + 6 - scroll.roundToInt()

        val presets = CommandPresets.all.filter { query.isEmpty() || it.name.lowercase().contains(query) || it.command.contains(query) }
        if (query.isEmpty() || presets.isNotEmpty()) {
            y = drawPresetHeader(g, left, right, y, settings, query.isNotEmpty())
            y = if (settings.presetsOpen || query.isNotEmpty()) drawTiles(g, left, right, y, presets, settings) else drawStrip(g, left, right, y, settings)
            y += 12
        }

        if (query.isEmpty() || "island chest search soul chests".contains(query)) {
            drawTextV(g, "SOUL FEATURES", left + 4, y, y + 18, Theme.TEXT_DIM, fCaption, MsdfFonts.menuSemibold)
            y += 20
            val columns = ((right - left + GAP) / (TILE_MIN_W + GAP)).coerceIn(1, 3)
            val tileW = (right - left - GAP * (columns - 1)) / columns
            drawBindingTile(
                g, "Island chest search", CHEST_ICON, Ref.IslandChests, cfg.islandChests.searchKey, cfg.islandChests.enabled,
                left, y, left + tileW, y + TILE_H,
                listOf(
                    line("Island chest search", ChatFormatting.WHITE),
                    line("Open search, or return to your preserved search while locating.", ChatFormatting.GRAY),
                    line("Same binding as Island chests settings. Requires that feature enabled; command pause and share codes do not change it.", ChatFormatting.DARK_GRAY),
                ),
            )
            y += TILE_H + 12
        }

        val shown = bindings.indices.filter { query.isEmpty() || CommandKeybinds.normalize(bindings[it].command).lowercase().contains(query) }
        val title = "YOUR COMMANDS"
        drawTextV(g, title, left + 4, y, y + 18, Theme.TEXT_DIM, fCaption, MsdfFonts.menuSemibold)
        drawTextV(g, bindings.size.toString(), left + 12 + textWidth(title, fCaption, MsdfFonts.menuSemibold), y, y + 18, Theme.TEXT_FAINT, fCaption)
        y += 20
        val draft = edit?.takeIf { it.index == null }
        if (shown.isEmpty() && draft == null) {
            val empty = if (bindings.isEmpty()) "No custom commands yet. + Add command adds one." else "No command matches \"${search.text.trim()}\"."
            for (text in wrapText(empty, right - left - 8, fCaption)) {
                drawText(g, text, left + 4, y + 2, Theme.TEXT_DIM, fCaption)
                y += 13
            }
        }
        for ((row, index) in shown.withIndex()) y = drawCommandRow(g, left, right, y, index, bindings[index], row % 2 == 1)
        if (draft != null) y = drawCommandRow(g, left, right, y, null, null, shown.size % 2 == 1)

        endClip(g)
        contentHeight = y + scroll.roundToInt() - by0 + 6
        scroll = clampScroll(if (revealEdit) Float.MAX_VALUE else scroll, contentHeight, by1 - by0)
        revealEdit = false
    }

    private fun drawPresetHeader(g: GuiGraphicsExtractor, left: Int, right: Int, y: Int, settings: CommandKeybindsConfig, searching: Boolean): Int {
        val h = 18
        val cy = y + h / 2
        val title = "HYPIXEL MENUS"
        val hover = !searching && hot(left, y, right, y + h)
        drawTextV(g, title, left + 4, y, y + h, if (hover) Theme.TEXT else Theme.TEXT_DIM, fCaption, MsdfFonts.menuSemibold)
        if (!searching) {
            val bound = CommandPresets.all.count { settings.presets[it.id]?.key?.isNotBlank() == true }
            val summary = "$bound of ${CommandPresets.all.size} bound"
            val sx = left + 12 + textWidth(title, fCaption, MsdfFonts.menuSemibold)
            drawTextV(g, summary, sx, y, y + h, Theme.TEXT_FAINT, fCaption)
            val ax = sx + textWidth(summary, fCaption) + 7
            if (settings.presetsOpen) {
                caret(g, ax + 2, cy - 1)
            } else {
                fillPolygon(g, floatArrayOf(ax.toFloat(), cy - 3.5f, ax + 3.5f, cy.toFloat(), ax.toFloat(), cy + 3.5f), Theme.TEXT_DIM)
            }
            if (hover) {
                tooltip = listOf(line(if (settings.presetsOpen) "Fold: show only bound presets" else "Unfold: show every preset", ChatFormatting.GRAY))
            }
            hit(left, y, right, y + h) { togglePresetsOpen() }
        }
        return y + h + 2
    }

    private fun drawTiles(g: GuiGraphicsExtractor, left: Int, right: Int, top: Int, presets: List<CommandPreset>, settings: CommandKeybindsConfig): Int {
        val columns = ((right - left + GAP) / (TILE_MIN_W + GAP)).coerceIn(1, 3)
        val tileW = (right - left - GAP * (columns - 1)) / columns
        for ((i, preset) in presets.withIndex()) {
            val tx = left + (i % columns) * (tileW + GAP)
            val ty = top + (i / columns) * (TILE_H + GAP)
            drawBindingTile(
                g, preset.name, preset.icon, Ref.Preset(preset.id), settings.presets[preset.id]?.key.orEmpty(),
                settings.presets[preset.id]?.enabled != false, tx, ty, tx + tileW, ty + TILE_H,
                presetTooltip(preset, settings.presets[preset.id]),
            ) { togglePreset(preset.id) }
        }
        val rows = (presets.size + columns - 1) / columns
        return top + max(0, rows * (TILE_H + GAP) - GAP)
    }

    private fun drawBindingTile(
        g: GuiGraphicsExtractor,
        name: String,
        stack: ItemStack,
        ref: Ref,
        key: String,
        on: Boolean,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        tip: List<Component>,
        toggle: (() -> Unit)? = null,
    ) {
        val bound = key.isNotBlank()
        val dim = bound && !on
        val cy = (y0 + y1) / 2
        val hover = hot(x0, y0, x1, y1)
        if (hover) tooltip = tip
        roundRect(g, x0, y0, x1, y1, if (hover) TILE_HOVER else Theme.SECTION, SoulRenderPipelines.ROUND_SMALL)
        icon(g, stack, x0 + 6, cy - 8, dim, faint = !bound)
        var rx = x1 - 6
        val chipMax = ((x1 - x0) * 0.5f).toInt()
        rx -= drawChip(g, rx, cy, ref, key, dim, chipMax, alignRight = true) + 5
        if (bound) {
            if (toggle != null) {
                drawMiniToggle(g, rx - TOGGLE_W, cy, on, tip = bindingToggleTooltip(on), action = toggle)
                rx -= TOGGLE_W + 5
            }
            val conflicts = if (on) CommandKeybinds.conflicts(key, ref) else emptyList()
            if (conflicts.isNotEmpty()) {
                drawBadge(g, rx - 12, cy, conflicts)
                rx -= 12 + 5
            }
        }
        val nameColor = if (!bound) Theme.TEXT_DIM else if (dim) Theme.TEXT_FAINT else Theme.TEXT
        val weight = if (bound) MsdfFonts.menuSemibold else MsdfFonts.menuRegular
        drawTextV(g, ellipsize(name, rx - (x0 + 28) - 2, fBody, weight), x0 + 28, y0, y1, nameColor, fBody, weight)
    }

    private fun drawStrip(g: GuiGraphicsExtractor, left: Int, right: Int, top: Int, settings: CommandKeybindsConfig): Int {
        val bound = CommandPresets.all.filter { settings.presets[it.id]?.key?.isNotBlank() == true }
        if (bound.isEmpty()) {
            drawText(g, "No Hypixel menu bound yet. Unfold the group to bind one.", left + 4, top + 2, Theme.TEXT_DIM, fCaption)
            return top + 14
        }
        val h = 24
        var x = left
        var y = top
        for (preset in bound) {
            val binding = settings.presets.getValue(preset.id)
            val ref = Ref.Preset(preset.id)
            val w = 4 + 16 + 5 + chipWidth(chipParts(ref, binding.key)) + 5
            if (x + w > right && x > left) {
                x = left
                y += h + 5
            }
            val hover = hot(x, y, x + w, y + h)
            if (hover) tooltip = presetTooltip(preset, binding)
            roundRect(g, x, y, x + w, y + h, if (hover) TILE_HOVER else Theme.SECTION, SoulRenderPipelines.ROUND_SMALL)
            icon(g, preset.icon, x + 4, y + 4, !binding.enabled)
            drawChip(g, x + 25, y + h / 2, ref, binding.key, !binding.enabled, w)
            x += w + 6
        }
        return y + h
    }

    private fun drawCommandRow(g: GuiGraphicsExtractor, left: Int, right: Int, y: Int, index: Int?, binding: CommandBinding?, stripe: Boolean): Int {
        val e = edit?.takeIf { it.index == index }
        val key = if (index == null) e?.key.orEmpty() else binding?.key.orEmpty()
        val on = binding?.enabled ?: true
        val bound = key.isNotBlank()
        val dim = bound && !on
        val y1 = y + ROW_H
        val cy = y + ROW_H / 2
        val hover = hot(left, y, right, y1)
        if (hover) {
            roundRect(g, left, y, right, y1, ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
        } else if (stripe) {
            roundRect(g, left, y, right, y1, ROW_STRIPE, SoulRenderPipelines.ROUND_SMALL)
        }
        icon(g, COMMAND_ICON, left + 6, cy - 8, dim)

        // Right to left: remove, edit/save, then the key column (toggle, chip, conflict badge).
        glyphButton(g, right - 20, cy, if (index == null) "Discard" else "Remove", { g2, cx, c -> drawCross(g2, cx, cy, c) }) {
            if (index == null) cancelEdit() else remove(index)
        }
        if (e != null) {
            glyphButton(g, right - 40, cy, "Save (Enter)", { g2, cx, _ -> drawTextV(g2, "✓", cx - textWidth("✓") / 2, cy - 8, cy + 8, Theme.ACCENT) }) {
                commitEdit()
            }
        } else if (index != null) {
            glyphButton(g, right - 40, cy, "Edit command", { g2, cx, c -> drawPencil(g2, cx, cy, c) }) { beginEdit(index) }
        }
        val keyColW = ((right - left) * 0.34f).toInt().coerceIn(96, 200)
        val kx = right - 46 - keyColW
        val target: Any = if (index == null) e!! else Ref.Custom(index)
        if (bound && index != null) {
            drawMiniToggle(g, kx, cy, on, tip = bindingToggleTooltip(on)) { toggleCustom(index) }
        }
        val chipX = kx + TOGGLE_W + 6
        val chipW = drawChip(g, chipX, cy, target, key, dim, keyColW - TOGGLE_W - 6 - 18)
        if (bound && on) {
            val conflicts = CommandKeybinds.conflicts(key, if (index == null) null else Ref.Custom(index))
            if (conflicts.isNotEmpty()) drawBadge(g, chipX + chipW + 5, cy, conflicts)
        }

        val textX0 = left + 28
        val textX1 = kx - 8
        if (e != null) {
            drawTextField(g, textX0 - 4, cy - 10, textX1, cy + 10, e.field, "/command", bodyRect) { search.focused = false }
        } else if (binding != null) {
            val command = "/" + CommandKeybinds.normalize(binding.command)
            val shownText = ellipsize(command, textX1 - textX0)
            drawTextV(g, shownText, textX0, y, y1, if (dim) Theme.TEXT_FAINT else Theme.TEXT, fBody)
            if (shownText != command && hot(textX0, y, textX1, y1)) tooltip = listOf(line(command, ChatFormatting.WHITE))
        }
        var bottom = y1
        e?.error?.let { error ->
            for (text in wrapText(error, right - textX0 - 8, fCaption)) {
                drawText(g, text, textX0, bottom + 1, ERROR, fCaption)
                bottom += 12
            }
            bottom += 3
        }
        return bottom + 2
    }

    // ---------------------------------------------------------------- widgets

    /** Hover test for the current frame; body elements only count inside the visible body. */
    private fun hot(x0: Int, y0: Int, x1: Int, y1: Int, inBody: Boolean = true): Boolean =
        interactive && mx in x0..x1 && my in y0..y1 && (!inBody || my in bodyRect[1]..bodyRect[3])

    private fun hit(x0: Int, y0: Int, x1: Int, y1: Int, inBody: Boolean = true, action: () -> Unit) {
        if (!interactive) return
        if (inBody) clippedRegion(x0, y0, x1, y1, bodyRect, action) else region(x0, y0, x1, y1, action)
    }

    /** [dim] = a switched-off binding; [faint] = an unbound preset, lighter. */
    private fun icon(g: GuiGraphicsExtractor, stack: ItemStack, x: Int, y: Int, dim: Boolean, faint: Boolean = false) {
        g.fakeItem(stack, x, y)
        if (dim || faint) g.fill(x, y, x + 16, y + 16, if (dim) ICON_DIM else ICON_UNBOUND)
    }

    private fun chipParts(target: Any, key: String): List<String> {
        if (captureTarget == target) {
            val held = capture.previewParts()
            return if (held.isEmpty()) listOf("Press keys…") else held + "…"
        }
        if (key.isBlank()) return listOf("Bind")
        return KeyCombo.cached(key)?.shortParts() ?: listOf("Invalid key")
    }

    private fun partWidth(part: String): Int = textWidth(part, fCaption) + 10

    private val plusWidth: Int get() = textWidth("+", fCaption) + 6

    private fun chipWidth(parts: List<String>): Int = parts.sumOf { partWidth(it) } + (parts.size - 1) * plusWidth

    /**
     * A key chip: one keycap per key, a dashed "Bind" when unbound, accent while capturing. Click
     * starts capturing for [target]. Wider than [maxW], the keys merge into one ellipsized cap.
     * Returns the drawn width.
     */
    private fun drawChip(
        g: GuiGraphicsExtractor,
        x: Int,
        cy: Int,
        target: Any,
        key: String,
        dimmed: Boolean,
        maxW: Int,
        alignRight: Boolean = false,
    ): Int {
        val capturing = captureTarget == target
        var parts = chipParts(target, key)
        if (chipWidth(parts) > maxW) parts = listOf(ellipsize(parts.joinToString("+"), max(10, maxW - 10), fCaption))
        val width = chipWidth(parts)
        val x0 = if (alignRight) x - width else x
        val top = cy - CHIP_H / 2
        val bottom = top + CHIP_H
        val unbound = key.isBlank() && !capturing
        val hover = hot(x0, top, x0 + width, bottom)
        var cx = x0
        for ((i, part) in parts.withIndex()) {
            if (i > 0) {
                drawTextV(g, "+", cx + 3, top, bottom, Theme.TEXT_FAINT, fCaption)
                cx += plusWidth
            }
            val w = partWidth(part)
            val textColor = if (capturing) {
                roundRect(g, cx, top, cx + w, bottom, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
                roundRect(g, cx + 1, top + 1, cx + w - 1, bottom - 1, CAPTURE_BG, SoulRenderPipelines.ROUND_SMALL)
                CAPTURE_TEXT
            } else if (unbound) {
                dashedRect(g, cx, top, cx + w, bottom, if (hover) Theme.TEXT_DIM else Theme.TEXT_FAINT)
                if (hover) Theme.TEXT_DIM else Theme.TEXT_FAINT
            } else {
                roundRect(g, cx, top, cx + w, bottom, if (hover) Theme.TEXT_DIM else CHIP_BORDER, SoulRenderPipelines.ROUND_SMALL)
                roundRect(g, cx + 1, top + 1, cx + w - 1, bottom - 1, if (dimmed) CHIP_BG_DIM else CHIP_BG, SoulRenderPipelines.ROUND_SMALL)
                if (dimmed) Theme.TEXT_FAINT else CHIP_TEXT
            }
            drawTextV(g, part, cx + 5, top, bottom, textColor, fCaption)
            cx += w
        }
        if (hover) {
            tooltip = if (capturing) {
                listOf(line("Press a key, a combo or a mouse button", ChatFormatting.WHITE), line("Set when you let go. Esc unbinds.", ChatFormatting.GRAY))
            } else {
                listOfNotNull(
                    if (unbound) null else line("Key: ${KeyCombo.label(key)}", ChatFormatting.WHITE),
                    line(if (unbound) "Click to bind a key" else "Click to change it; Esc while binding unbinds", ChatFormatting.GRAY),
                )
            }
        }
        hit(x0, top, x0 + width, bottom) { startCapture(target) }
        return width
    }

    private fun dashedRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        var x = x0 + 2
        while (x < x1 - 2) {
            val end = minOf(x + 3, x1 - 2)
            g.fill(x, y0, end, y0 + 1, color)
            g.fill(x, y1 - 1, end, y1, color)
            x += 5
        }
        var y = y0 + 2
        while (y < y1 - 2) {
            val end = minOf(y + 3, y1 - 2)
            g.fill(x0, y, x0 + 1, end, color)
            g.fill(x1 - 1, y, x1, end, color)
            y += 5
        }
    }

    private fun drawMiniToggle(
        g: GuiGraphicsExtractor,
        x: Int,
        cy: Int,
        on: Boolean,
        inBody: Boolean = true,
        tip: List<Component>,
        action: () -> Unit,
    ) {
        val y = cy - TOGGLE_H / 2
        val hover = hot(x, y, x + TOGGLE_W, y + TOGGLE_H, inBody)
        roundRect(g, x, y, x + TOGGLE_W, y + TOGGLE_H, if (on) Theme.ACCENT else if (hover) Theme.TEXT_FAINT else Theme.CONTROL_HOVER, SoulRenderPipelines.ROUND_PILL)
        val knob = TOGGLE_H - 4
        val knobX = if (on) x + TOGGLE_W - 2 - knob else x + 2
        roundRect(g, knobX, y + 2, knobX + knob, y + 2 + knob, if (on) Theme.TEXT else Theme.TEXT_DIM, SoulRenderPipelines.ROUND_PILL)
        if (hover) tooltip = tip
        hit(x - 1, y - 2, x + TOGGLE_W + 1, y + TOGGLE_H + 2, inBody, action)
    }

    private fun bindingToggleTooltip(on: Boolean): List<Component> = listOf(
        line(if (on) "On" else "Off", ChatFormatting.WHITE),
        line(if (on) "Click to switch this binding off; it keeps its key." else "Keeps its key but never fires. Click to switch it on.", ChatFormatting.GRAY),
    )

    private fun drawBadge(g: GuiGraphicsExtractor, x: Int, cy: Int, conflicts: List<String>) {
        val y = cy - 6
        roundRect(g, x, y, x + 12, y + 12, BADGE_BG, SoulRenderPipelines.ROUND_PILL)
        drawTextV(g, "!", x + 6 - textWidth("!", fCaption, MsdfFonts.menuSemibold) / 2, y, y + 12, BADGE_FG, fCaption, MsdfFonts.menuSemibold)
        if (hot(x - 1, y - 1, x + 13, y + 13)) {
            tooltip = listOf(line("Also bound to:", ChatFormatting.YELLOW)) +
                conflicts.map { line("• $it", ChatFormatting.GRAY) } +
                line("Conflicting actions remain enabled.", ChatFormatting.DARK_GRAY)
        }
    }

    private fun glyphButton(
        g: GuiGraphicsExtractor,
        x: Int,
        cy: Int,
        tip: String,
        glyph: (GuiGraphicsExtractor, Int, Int) -> Unit,
        action: () -> Unit,
    ) {
        val hover = hot(x, cy - 8, x + 16, cy + 8)
        if (hover) {
            roundRect(g, x, cy - 8, x + 16, cy + 8, Theme.CONTROL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            tooltip = listOf(line(tip, ChatFormatting.GRAY))
        }
        glyph(g, x + 8, if (hover) Theme.TEXT else Theme.TEXT_DIM)
        hit(x, cy - 8, x + 16, cy + 8, action = action)
    }

    private fun drawCross(g: GuiGraphicsExtractor, cx: Int, cy: Int, color: Int) {
        val r = 3.2f
        val t = 0.75f
        val x = cx.toFloat()
        val y = cy.toFloat()
        fillPolygon(g, floatArrayOf(x - r - t, y - r + t, x - r + t, y - r - t, x + r + t, y + r - t, x + r - t, y + r + t), color)
        fillPolygon(g, floatArrayOf(x + r - t, y - r - t, x + r + t, y - r + t, x - r + t, y + r + t, x - r - t, y + r - t), color)
    }

    /** ✎ is in no MSDF family: a slanted pencil body plus its tip. */
    private fun drawPencil(g: GuiGraphicsExtractor, cx: Int, cy: Int, color: Int) {
        val x = cx.toFloat()
        val y = cy.toFloat()
        fillPolygon(g, floatArrayOf(x - 2.6f, y + 1.6f, x + 2.4f, y - 3.4f, x + 3.9f, y - 1.9f, x - 1.1f, y + 3.1f), color)
        fillPolygon(g, floatArrayOf(x - 2.9f, y + 1.9f, x - 1.4f, y + 3.4f, x - 4.0f, y + 4.5f), color)
    }

    private fun presetTooltip(preset: CommandPreset, binding: PresetBinding?): List<Component> {
        val lines = mutableListOf(
            line(preset.name, ChatFormatting.WHITE),
            line(preset.purpose, ChatFormatting.GRAY),
            line("/${preset.command}", ChatFormatting.DARK_GRAY),
        )
        if (binding != null && binding.key.isNotBlank()) {
            lines.add(line("Key: ${KeyCombo.label(binding.key)}" + if (binding.enabled) "" else " (off)", ChatFormatting.GRAY))
        }
        return lines
    }

    private fun line(text: String, style: ChatFormatting): Component = Component.literal(text).withStyle(style)

    // ---------------------------------------------------------------- actions

    private fun startCapture(target: Any) {
        capture = KeyComboCapture()
        captureTarget = target
        search.focused = false
        edit?.field?.focused = false
    }

    private fun applyCapture(result: String) {
        val target = captureTarget ?: return
        captureTarget = null
        when (target) {
            is Ref -> ConfigManager.update { CommandKeybinds.rebind(this, target, result) }
            is InlineEdit -> {
                target.key = result
                target.field.focused = true
            }
        }
    }

    private fun toggleMaster() = ConfigManager.update { copy(commandKeybinds = commandKeybinds.copy(enabled = !commandKeybinds.enabled)) }

    private fun togglePresetsOpen() = ConfigManager.update { copy(commandKeybinds = commandKeybinds.copy(presetsOpen = !commandKeybinds.presetsOpen)) }

    private fun togglePreset(id: String) = ConfigManager.update {
        val binding = commandKeybinds.presets[id] ?: return@update this
        copy(commandKeybinds = commandKeybinds.copy(presets = commandKeybinds.presets + (id to binding.copy(enabled = !binding.enabled))))
    }

    private fun toggleCustom(index: Int) = ConfigManager.update {
        copy(commandBindings = commandBindings.mapIndexed { i, b -> if (i == index) b.copy(enabled = !b.enabled) else b })
    }

    private fun addCommand() {
        if (!commitEdit()) return
        edit = InlineEdit(null, "/")
        search.focused = false
        revealEdit = true
    }

    private fun beginEdit(index: Int) {
        if (!commitEdit()) return
        val binding = cfg.commandBindings.getOrNull(index) ?: return
        edit = InlineEdit(index, "/" + CommandKeybinds.normalize(binding.command))
        search.focused = false
    }

    /** Saves the open inline edit, if any. False (and the error shown) when its command is empty. */
    private fun commitEdit(): Boolean {
        val e = edit ?: return true
        val command = CommandKeybinds.normalize(e.field.text)
        if (command.isEmpty()) {
            e.error = "Enter a command, or press Esc to cancel."
            e.field.focused = true
            return false
        }
        val index = e.index
        ConfigManager.update {
            if (index == null) {
                copy(commandBindings = commandBindings + CommandBinding(command, e.key))
            } else {
                copy(commandBindings = commandBindings.mapIndexed { i, b -> if (i == index) b.copy(command = command) else b })
            }
        }
        cancelEdit()
        return true
    }

    private fun cancelEdit() {
        if (captureTarget === edit) captureTarget = null
        edit = null
    }

    private fun remove(index: Int) {
        captureTarget = null
        edit?.let { e ->
            val editing = e.index
            if (editing == index) edit = null else if (editing != null && editing > index) e.index = editing - 1
        }
        ConfigManager.update { copy(commandBindings = commandBindings.filterIndexed { i, _ -> i != index }) }
    }

    private fun close() {
        if (parent != null) minecraft?.setScreenAndShow(parent) else super.onClose()
    }

    // ---------------------------------------------------------------- share codes

    private fun exportBindings() {
        try {
            val saved = cfg.commandBindings
            val presets = cfg.commandKeybinds.presets
            val code = CommandBindingShareCode.encode(saved, presets)
            Minecraft.getInstance().keyboardHandler.setClipboard(code)
            val boundPresets = CommandPresets.all.count { presets[it.id]?.key?.isNotBlank() == true }
            showAlert("Export copied", "${saved.size} commands and $boundPresets bound presets were copied as a share code.", success = true)
        } catch (error: IllegalArgumentException) {
            showAlert("Export failed", error.message ?: "Could not export the saved command keybinds.")
        }
    }

    private fun importClipboard() {
        if (!commitEdit()) return
        captureTarget = null
        try {
            importReview = CommandBindingShareCode.decode(Minecraft.getInstance().keyboardHandler.clipboard)
            reviewScroll = 0f
        } catch (error: IllegalArgumentException) {
            showAlert("Import failed", error.message ?: "The clipboard does not contain a valid share code.")
        }
    }

    private fun isEmpty(share: CommandKeybindShare): Boolean = share.bindings.isEmpty() && share.presets.isNullOrEmpty()

    private fun confirmImport() {
        val incoming = importReview ?: return
        ConfigManager.update {
            val presets = incoming.presets
            copy(
                commandBindings = incoming.bindings,
                commandKeybinds = if (presets == null) commandKeybinds else commandKeybinds.copy(presets = presets),
            )
        }
        cancelImport()
        scroll = 0f
    }

    private fun cancelImport() {
        importReview = null
        reviewScroll = 0f
    }

    private fun drawReview(g: GuiGraphicsExtractor, incoming: CommandKeybindShare) {
        val (x0, y0, x1, y1) = bodyRect
        val left = x0 + 4
        val right = x1 - 4
        val savedCommands = cfg.commandBindings.size
        val savedPresets = CommandPresets.all.count { cfg.commandKeybinds.presets[it.id]?.key?.isNotBlank() == true }
        val presets = incoming.presets
        val warning = if (isEmpty(incoming)) {
            if (presets == null) "This import is empty. Clear all removes all $savedCommands saved commands; presets stay as they are."
            else "This import is empty. Clear all removes all $savedCommands saved commands and unbinds $savedPresets presets."
        } else if (presets == null) {
            "This older share code only holds commands. Replace your $savedCommands commands with these ${incoming.bindings.size}? Presets stay as they are."
        } else {
            "Replace your $savedCommands commands and $savedPresets bound presets with these ${incoming.bindings.size} commands and ${presets.size} presets?"
        }
        beginClip(g, x0, y0, x1, y1)
        var y = y0 + 6 - reviewScroll.roundToInt()
        for (text in wrapText("$warning Nothing runs during import. Cancel or Esc keeps everything unchanged.", right - left - 8, fCaption)) {
            drawText(g, text, left + 4, y, WARNING, fCaption)
            y += 13
        }
        y += 8
        if (!presets.isNullOrEmpty()) {
            drawTextV(g, "HYPIXEL MENUS", left + 4, y, y + 18, Theme.TEXT_DIM, fCaption, MsdfFonts.menuSemibold)
            y += 20
            for (preset in CommandPresets.all) {
                val binding = presets[preset.id] ?: continue
                y = drawReviewRow(g, left, right, y, preset.icon, preset.name, binding.key, binding.enabled)
            }
            y += 8
        }
        if (incoming.bindings.isNotEmpty()) {
            drawTextV(g, "YOUR COMMANDS", left + 4, y, y + 18, Theme.TEXT_DIM, fCaption, MsdfFonts.menuSemibold)
            y += 20
            for (binding in incoming.bindings) {
                y = drawReviewRow(g, left, right, y, COMMAND_ICON, "/" + CommandKeybinds.normalize(binding.command), binding.key, binding.enabled)
            }
        }
        endClip(g)
        contentHeight = y + reviewScroll.roundToInt() - y0 + 6
        reviewScroll = clampScroll(reviewScroll, contentHeight, y1 - y0)
    }

    private fun drawReviewRow(g: GuiGraphicsExtractor, left: Int, right: Int, y: Int, icon: ItemStack, text: String, key: String, on: Boolean): Int {
        val cy = y + 11
        val dim = key.isNotBlank() && !on
        icon(g, icon, left + 6, cy - 8, dim)
        val chipW = chipWidth(chipParts(Unit, key))
        val label = if (dim) "off" else ""
        val chipX = right - 6 - chipW - if (dim) textWidth(label, fCaption) + 6 else 0
        drawTextV(g, ellipsize(text, chipX - left - 36), left + 28, y, y + 22, if (dim) Theme.TEXT_FAINT else Theme.TEXT, fBody)
        drawChip(g, chipX, cy, Unit, key, dim, right - chipX)
        if (dim) drawTextV(g, label, right - 6 - textWidth(label, fCaption), y, y + 22, Theme.TEXT_FAINT, fCaption)
        return y + 24
    }

    // ---------------------------------------------------------------- input

    override fun interceptClick(click: MouseButtonEvent): Boolean {
        if (alertModalOpen || captureTarget == null) return false
        applyCapture(capture.mouseClicked(click.button()))
        return true
    }

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        val modal = importReview != null || alertModalOpen
        val handled = super.mouseClicked(click, doubleClick)
        return modal || handled
    }

    override fun onEmptyClick() {
        super.onEmptyClick()
        search.focused = false
        edit?.field?.focused = false
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) return super.keyPressed(event)
        if (importReview != null) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) cancelImport()
            return true
        }
        if (captureTarget != null) {
            capture.keyPressed(event)?.let { applyCapture(it) }
            return true
        }
        val key = event.key()
        val enter = key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER
        val e = edit
        if (e != null && e.field.focused) {
            if (enter) commitEdit() else if (key == GLFW.GLFW_KEY_ESCAPE) cancelEdit() else e.field.keyPressed(event, minecraft?.keyboardHandler)
            return true
        }
        if (search.focused) {
            if (enter || key == GLFW.GLFW_KEY_ESCAPE) search.focused = false else search.keyPressed(event, minecraft?.keyboardHandler)
            return true
        }
        if (e != null && key == GLFW.GLFW_KEY_ESCAPE) {
            cancelEdit()
            return true
        }
        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        if (captureTarget != null && !alertModalOpen && importReview == null) {
            capture.keyReleased(event)?.let { applyCapture(it) }
            return true
        }
        return super.keyReleased(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        if (importReview != null || captureTarget != null) return true
        val e = edit
        if (e != null && e.field.focused) e.field.charTyped(event) else if (search.focused) search.charTyped(event)
        return true
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (alertModalOpen) return true
        val scale = SoulUi.factor()
        val x = (mouseX / scale).roundToInt()
        val y = (mouseY / scale).roundToInt()
        if (within(bodyRect, x, y)) {
            val viewport = bodyRect[3] - bodyRect[1]
            if (importReview != null) {
                reviewScroll = clampScroll(reviewScroll - scrollY.toFloat() * 18, contentHeight, viewport)
            } else {
                scroll = clampScroll(scroll - scrollY.toFloat() * 18, contentHeight, viewport)
            }
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            return
        }
        if (importReview != null) {
            cancelImport()
            return
        }
        if (captureTarget != null) {
            captureTarget = null
            return
        }
        if (edit != null) {
            cancelEdit()
            return
        }
        close()
    }
}
