package com.soulreturns.config.gui

import com.soulreturns.config.gui.model.ActionOption
import com.soulreturns.config.gui.model.CheckboxGroupOption
import com.soulreturns.config.gui.model.CheckboxItem
import com.soulreturns.config.gui.model.ColorOption
import com.soulreturns.config.gui.model.ConfigOption
import com.soulreturns.config.gui.model.CycleOption
import com.soulreturns.config.gui.model.KeybindOption
import com.soulreturns.config.gui.model.SliderOption
import com.soulreturns.config.gui.model.TextOption
import com.soulreturns.config.gui.model.SoundOption
import com.soulreturns.config.gui.model.ToggleOption
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.sound.SoulSounds
import com.soulreturns.sound.SoundNames
import com.soulreturns.sound.SoundRef
import com.soulreturns.sound.gui.SoundPick
import com.soulreturns.sound.gui.SoundPickerScreen
import com.soulreturns.util.KeyCombo
import com.soulreturns.util.KeyComboCapture
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.util.Locale
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Shared base for Soul's **config-style** screens ([SoulConfigScreen],
 * [com.soulreturns.features.dungeons.map.DungeonMapConfigScreen]): the widgets that render a
 * [ConfigOption] (option row, toggle, text field, slider, checkbox group, color picker, dropdown,
 * keybind picker), their editing state, and the input plumbing around them (slider drag, inline
 * text/number editing, keybind capture, deferred "Apply" values).
 *
 * Everything generic — the click-region hit test, the SDF/MSDF draw helpers, clipping, scroll
 * clamping, the button and the result modal — lives one level up in [SoulScreenBase], which any Soul
 * screen can extend without dragging the config model in with it. Read its KDoc first: the
 * coordinate system and the two rendering traps (overlays last, scissor is not automatic) are
 * documented there and apply here unchanged.
 */
abstract class ConfigScreenBase(title: Component) : SoulScreenBase(title) {
    // Widget dimensions (legacy sizes). The typography sizes are [SoulScreenBase]'s.
    protected val fieldW = 160
    protected val sliderW = 120
    protected val resetW = 20
    protected val resetH = 16

    protected var focusedText: TextOption? = null

    /** The slider whose value is being typed as a number (reuses [editBuffer]); null = none. */
    protected var editingSlider: SliderOption? = null
    protected var editBuffer = ""

    /** Uncommitted values for deferred (applyImmediately = false) sliders, shown until "Apply". */
    protected val pendingSlider = HashMap<SliderOption, Double>()

    protected var draggingSlider: SliderOption? = null
    private var dragTrackX0 = 0
    private var dragTrackX1 = 0

    // Color picker: the option being edited (null = closed) + working HSVA (kept separately so hue
    // survives at zero saturation/value). Bar rects + drag target are set while the picker draws.
    private var openColor: ColorOption? = null
    private var pickerPalette: List<Int> = emptyList()
    private var pickH = 0f
    private var pickS = 0f
    private var pickV = 0f
    private var pickA = 1f
    private var pickChroma = false
    private var pickerDrag = 0 // 0 none, 1 SV square, 2 hue bar, 3 alpha bar
    // The swatch rect the picker was opened from, so the popup anchors beside it (not screen-centered).
    private var pickerAnchor = intArrayOf(0, 0, 0, 0)
    private var svRect = intArrayOf(0, 0, 0, 0)
    private var hueRect = intArrayOf(0, 0, 0, 0)
    private var alphaRect = intArrayOf(0, 0, 0, 0)

    /**
     * The keybind option in capture mode (null = none). While set, keys are collected by
     * [keyCapture] and the combo is committed when the first held key is released (Escape clears
     * it); a mouse click binds that button with the modifiers held — checked at the top of
     * [mouseClicked]/[keyPressed] so it pre-empts regions, inline editors, and Esc-close.
     */
    protected var capturingKeybind: KeybindOption? = null
    private var keyCapture = KeyComboCapture()

    // ---------------------------------------------------------------- input

    /**
     * Keybind capture pre-empts the regions: while armed, any click binds that mouse button (4/5
     * included), which is why re-clicking the picker box does not cancel it — Escape does.
     */
    override fun interceptClick(click: MouseButtonEvent): Boolean {
        capturingKeybind?.let { opt ->
            opt.set(keyCapture.mouseClicked(click.button()))
            capturingKeybind = null
            return true
        }
        return false
    }

    /**
     * A click on empty space commits whichever inline editor was open. A screen that adds focus of
     * its own (the config menu's search field) overrides this and calls `super.onEmptyClick()` first.
     */
    override fun onEmptyClick() {
        commit()
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        draggingSlider = null
        pickerDrag = 0
        return super.mouseReleased(click)
    }

    override fun mouseDragged(
        click: MouseButtonEvent,
        dragX: Double,
        dragY: Double,
    ): Boolean {
        val s = SoulUi.factor()
        lastMouseX = (click.x() / s).roundToInt()
        lastMouseY = (click.y() / s).roundToInt()
        val slider = draggingSlider
        if (slider != null) {
            setSliderFromMouse(slider, dragTrackX0, dragTrackX1, lastMouseX)
            return true
        }
        if (pickerDrag != 0) {
            updatePickerDrag()
            return true
        }
        return super.mouseDragged(click, dragX, dragY)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen || !event.isAllowedChatCharacter()) return super.charTyped(event)
        val s = event.codepointAsString()
        if (handleExtraCharInput(s)) return true
        if (focusedText != null) {
            editBuffer += s
            return true
        }
        if (editingSlider != null) {
            if (s.length == 1 && (s[0].isDigit() || s[0] == '.' || s[0] == '-')) editBuffer += s
            return true
        }
        return super.charTyped(event)
    }

    /** Subclass hook for extra text input (e.g. a search field). Return true if consumed. */
    protected open fun handleExtraCharInput(s: String): Boolean = false

    override fun keyPressed(event: KeyEvent): Boolean {
        // Capture mode pre-empts everything (incl. the screen's Esc-close): keys are collected and
        // the combo lands on release (see keyReleased); Escape clears the binding ("Not bound").
        capturingKeybind?.let { opt ->
            keyCapture.keyPressed(event)?.let { result ->
                opt.set(result)
                capturingKeybind = null
            }
            return true
        }
        // A result modal is up: [SoulScreenBase.keyPressed] swallows every key while it is, so
        // nothing below can leak to the screen behind it.
        if (alertModalOpen) return super.keyPressed(event)
        if (handleExtraKey(event)) return true
        if (focusedText != null || editingSlider != null) {
            when (event.key()) {
                GLFW.GLFW_KEY_BACKSPACE -> {
                    editBuffer = editBuffer.dropLast(1)
                    return true
                }
                GLFW.GLFW_KEY_ENTER, GLFW.GLFW_KEY_KP_ENTER -> {
                    commit()
                    return true
                }
                GLFW.GLFW_KEY_ESCAPE -> {
                    focusedText = null
                    editingSlider = null
                    return true
                }
            }
        }
        return super.keyPressed(event)
    }

    override fun keyReleased(event: KeyEvent): Boolean {
        capturingKeybind?.let { opt ->
            keyCapture.keyReleased(event)?.let { result ->
                opt.set(result)
                capturingKeybind = null
            }
            return true
        }
        return super.keyReleased(event)
    }

    /** Subclass hook for extra key handling (e.g. a search field). Return true if consumed. */
    protected open fun handleExtraKey(event: KeyEvent): Boolean = false

    // ---------------------------------------------------------------- rows

    /**
     * Draw one option row: label left, control (toggle / text field / slider / action button) + a
     * reset chip on the right. Rows fully outside [clipY0]..[clipY1] are skipped (draw + region).
     * An [ActionOption] renders as a button routed through [onAction]; it has no reset chip.
     */
    protected fun drawOptionRow(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        opt: ConfigOption,
        clipY0: Int,
        clipY1: Int,
    ) {
        if (y1 <= clipY0 || y0 >= clipY1) return
        // A disabled row is drawn but greyed out + non-interactive: suppress its regions while it
        // draws, dim the label, and lay a translucent scrim over it at the end.
        val dis = opt.disabled()
        suppressRegions = dis

        // A checkbox group manages its own multi-line, label-less layout — handle it before the
        // standard label/control path (which assumes a single value control on the right).
        if (opt is CheckboxGroupOption) {
            drawCheckboxGroup(g, mx, my, x0, y0, x1, opt, dis)
            if (dis) drawDisabledScrim(g, x0, y0, x1, y1)
            suppressRegions = false
            return
        }

        val cy = (y0 + y1) / 2
        val labelX = x0 + 8
        val rowRight = x1 - 6
        drawLabel(g, opt, labelX, y0, y1, labelMaxW(opt, x0, x1), if (dis) Theme.TEXT_FAINT else Theme.TEXT)

        if (opt is ActionOption) {
            val bw = actionButtonW(opt)
            // With descriptions (the config menu) an action keeps the empty reset-chip slot, so every control lines up.
            val bx1 = if (rowDescriptions) rowRight - resetW - 6 else rowRight
            drawButton(g, mx, my, bx1 - bw, cy - 9, bx1, cy + 9, opt.buttonLabel) {
                val url = opt.url
                if (url != null) net.minecraft.util.Util.getPlatform().openUri(url) else onAction(opt.actionId)
            }
            if (dis) drawDisabledScrim(g, x0, y0, x1, y1)
            suppressRegions = false
            return
        }

        val canReset = opt.isModified && !dis
        val chipX0 = rowRight - resetW
        val chipY0 = cy - resetH / 2
        val chipY1 = cy + resetH / 2
        val rHover = canReset && mx in chipX0..rowRight && my in chipY0..chipY1
        if (rHover) roundRect(g, chipX0, chipY0, rowRight, chipY1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        val rColor = if (!canReset) Theme.TEXT_FAINT else if (rHover) Theme.ACCENT else Theme.TEXT_DIM
        drawTextV(g, "↺", chipX0 + (resetW - textWidth("↺", fBody)) / 2, chipY0, chipY1, rColor, fBody)
        if (canReset) {
            region(chipX0, chipY0, rowRight, chipY1) {
                opt.reset()
                if (opt is SliderOption) pendingSlider.remove(opt)
                if (editingSlider === opt) editingSlider = null
                if (capturingKeybind === opt) capturingKeybind = null
            }
        }

        val controlRight = chipX0 - 6
        when (opt) {
            is ToggleOption -> drawToggle(g, controlRight - toggleW, cy - toggleH / 2, opt.get()) { opt.toggle() }
            is TextOption -> {
                val cx0 = controlRight - fieldW
                val focused = focusedText === opt
                val shown = if (focused) editBuffer else opt.get()
                drawTextBox(g, cx0, cy - 9, controlRight, cy + 9, shown, "", focused) {
                    commit()
                    focusedText = opt
                    editBuffer = opt.get()
                }
            }
            is SliderOption -> drawSlider(g, controlRight - sliderW, cy, controlRight, opt)
            is ColorOption -> {
                val sw = 26
                drawSwatch(g, controlRight - sw, cy - 8, controlRight, cy + 8, opt.get().effective(), opt.hasAlpha)
                region(controlRight - sw, cy - 8, controlRight, cy + 8) {
                    openColorPicker(opt, controlRight - sw, cy - 8, controlRight, cy + 8)
                }
            }
            is CycleOption -> {
                val segW = segmentedW(opt)
                if (segW != null) {
                    drawSegmented(g, mx, my, controlRight - segW, cy - 9, cy + 9, opt)
                } else {
                    val bw = 100
                    val bx0 = controlRight - bw
                    drawDropdownButton(g, bx0, cy - 9, controlRight, cy + 9, opt.display, isDropdownOpen(opt))
                    region(bx0, cy - 9, controlRight, cy + 9) {
                        if (isDropdownOpen(opt)) {
                            closeDropdown()
                        } else {
                            openColor = null
                            openDropdownAt(opt, bx0, cy - 9, controlRight, cy + 9, opt.labels, opt.get()) { opt.set(it) }
                        }
                    }
                }
            }
            is KeybindOption -> drawKeybindButton(g, controlRight - 100, cy - 9, controlRight, cy + 9, opt)
            is SoundOption -> drawSoundField(g, mx, my, controlRight - soundFieldW, cy - 9, controlRight, cy + 9, opt)
            else -> {}
        }

        if (dis) drawDisabledScrim(g, x0, y0, x1, y1)
        suppressRegions = false
    }

    private val labelLineH get() = ceil(MsdfFont.LINE_HEIGHT * fBody).toInt() + 1
    private val descLineH = 12

    /**
     * Config-menu rows (spec `mocks/config-menu/`): the description in caption size under the label,
     * a chip after the label ([rowTag]) and an empty reset slot beside action buttons. Off for the
     * map screen, whose rows stay label-only.
     */
    protected open val rowDescriptions: Boolean = false

    /** A chip after the row's label ("HUD"); null = none. */
    protected open fun rowTag(opt: ConfigOption): String? = null

    private fun actionButtonW(opt: ActionOption): Int = (textWidth(opt.buttonLabel, fBody) + 24).coerceAtLeast(64)

    /** Space the row's right-hand control takes (reset chip included); the label stops short of it. */
    private fun controlW(opt: ConfigOption): Int {
        if (opt is ActionOption) return actionButtonW(opt) + if (rowDescriptions) resetW + 6 else 0
        val chip = resetW + 6
        return chip + when (opt) {
            is ToggleOption -> toggleW
            is TextOption -> fieldW
            is SliderOption -> {
                val shown = formatSlider(opt, pendingSlider[opt] ?: opt.get())
                sliderW + 6 + maxOf(textWidth(shown, fBody), textWidth("0.0", fBody)) + 4
            }
            is ColorOption -> 26
            is CycleOption -> segmentedW(opt) ?: 100
            is KeybindOption -> 100
            is SoundOption -> soundFieldW
            else -> 0
        }
    }

    /**
     * Short choices (2-3 labels that fit [SEG_MAX_W]) draw as segmented buttons, in the Move GUI
     * inspector's style (Weight/Shadow); longer lists stay dropdowns. Null = dropdown.
     */
    protected fun segmentedW(opt: CycleOption): Int? {
        if (opt.labels.size !in 2..3) return null
        val w = opt.labels.sumOf { textWidth(it, fBody) + SEG_PAD * 2 } + (opt.labels.size - 1) * SEG_GAP
        return if (w <= SEG_MAX_W) w else null
    }

    protected fun drawSegmented(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, y1: Int, opt: CycleOption) {
        var x = x0
        val current = opt.get()
        for ((i, label) in opt.labels.withIndex()) {
            val w = textWidth(label, fBody) + SEG_PAD * 2
            val on = i == current
            val over = mx in x..x + w && my in y0..y1
            roundRect(g, x, y0, x + w, y1, if (on) Theme.ACCENT else if (over) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, label, x + SEG_PAD, y0, y1, if (on) 0xFFFFFFFF.toInt() else Theme.TEXT_DIM, fBody)
            region(x, y0, x + w, y1) { if (opt.get() != i) opt.set(i) }
            x += w + SEG_GAP
        }
    }

    private fun labelMaxW(opt: ConfigOption, x0: Int, x1: Int): Int = ((x1 - 6 - controlW(opt)) - 8 - (x0 + 8)).coerceAtLeast(20)

    /** Label lines and description lines (empty without [rowDescriptions]) for a label [maxW] wide. */
    private fun labelBlock(opt: ConfigOption, maxW: Int): Pair<List<String>, List<String>> {
        val tagW = rowTag(opt)?.let { chipWidth(it) + 6 } ?: 0
        val label = wrapText(opt.label, (maxW - tagW).coerceAtLeast(20), fBody)
        val desc = if (rowDescriptions) opt.description?.let { wrapText(it, maxW, fCaption) }.orEmpty() else emptyList()
        return label to desc
    }

    private fun blockHeight(block: Pair<List<String>, List<String>>): Int =
        block.first.size * labelLineH + if (block.second.isEmpty()) 0 else 1 + block.second.size * descLineH

    /**
     * Row height for [opt] laid out between [x0] and [x1]: [minH], or taller when the label (and its
     * description) has to wrap beside its control.
     */
    protected fun optionRowHeight(opt: ConfigOption, x0: Int, x1: Int, minH: Int): Int {
        if (opt is CheckboxGroupOption) return checkboxRowHeight(opt, x0, x1)
        val block = labelBlock(opt, labelMaxW(opt, x0, x1))
        if (block.first.size <= 1 && block.second.isEmpty()) return minH
        return maxOf(minH, blockHeight(block) + if (rowDescriptions) 8 else 12)
    }

    /** The label (+ description), wrapped to [maxW] and centered in the row; lines past the row's height are ellipsized. */
    private fun drawLabel(g: GuiGraphicsExtractor, opt: ConfigOption, x: Int, y0: Int, y1: Int, maxW: Int, color: Int) {
        val tag = rowTag(opt)
        val block = labelBlock(opt, maxW)
        if (rowDescriptions) {
            drawLabelBlock(g, block, tag, x, (y0 + y1) / 2 - blockHeight(block) / 2, color)
            return
        }
        val lines = block.first
        if (lines.size <= 1) {
            drawTextV(g, opt.label, x, y0, y1, color, fBody)
            return
        }
        val fit = ((y1 - y0 - 4) / labelLineH).coerceAtLeast(1)
        val shown = if (lines.size <= fit) lines else lines.take(fit - 1) + ellipsize(lines.drop(fit - 1).joinToString(" "), maxW, fBody)
        val top = (y0 + y1) / 2 - shown.size * labelLineH / 2
        shown.forEachIndexed { i, line ->
            drawTextV(g, line, x, top + i * labelLineH, top + (i + 1) * labelLineH, color, fBody)
        }
    }

    private fun drawLabelBlock(g: GuiGraphicsExtractor, block: Pair<List<String>, List<String>>, tag: String?, x: Int, top: Int, color: Int) {
        block.first.forEachIndexed { i, line ->
            val ly = top + i * labelLineH
            drawTextV(g, line, x, ly, ly + labelLineH, color, fBody)
            if (i == 0 && tag != null) drawChip(g, x + textWidth(line, fBody) + 6, ly + labelLineH / 2, tag, Theme.TEXT_DIM, CHIP_HUD_BG)
        }
        val dy = top + block.first.size * labelLineH + 1
        block.second.forEachIndexed { i, line ->
            drawTextV(g, line, x, dy + i * descLineH, dy + (i + 1) * descLineH, Theme.TEXT_DIM, fCaption)
        }
    }

    // ---------------------------------------------------------------- chips

    protected val fChip = 8.5f * MsdfFont.LEGACY_EM

    /** The chips' letter-spacing: mock `.tag` 0.08em. */
    private val chipTracking = 0.08f * fChip

    protected fun chipWidth(text: String): Int =
        kotlin.math.ceil(com.soulreturns.config.gui.render.SoulText.widthTracked(text, fChip, com.soulreturns.config.gui.render.MsdfFonts.menuSemibold, chipTracking)).toInt() + 8

    /** A tag chip (NEW / HUD / BETA): 12 px tall, centered on [cy]; returns its width. */
    protected fun drawChip(g: GuiGraphicsExtractor, x: Int, cy: Int, text: String, fg: Int, bg: Int): Int {
        val w = chipWidth(text)
        roundRect(g, x, cy - 6, x + w, cy + 6, bg, SoulRenderPipelines.ROUND_SMALL)
        com.soulreturns.config.gui.render.SoulText.drawCenteredTracked(g, text, x + w / 2, cy, fChip, fg, com.soulreturns.config.gui.render.MsdfFonts.menuSemibold, chipTracking, scissor = currentClip)
        return w
    }

    protected companion object {
        const val CHIP_HUD_BG = 0xFF262626.toInt()
        const val SEG_PAD = 7
        const val SEG_GAP = 2
        const val SEG_MAX_W = 170

        // Color picker quick colors (mock kit CP.CHIP / CP.CHIP_GAP).
        const val PAL_COLS = 8
        const val PAL_CHIP = 10
        const val PAL_GAP = 2

        /** Under the picker's Chroma toggle: chroma has no per-color settings. */
        const val CHROMA_NOTE = "Speed and motion: Settings › Interface › Chroma"
    }

    // ---------------------------------------------------------------- checkbox group

    private val chipBox = 12 // checkbox square size
    private val chipBoxGap = 5 // box → label
    private val chipGap = 16 // between chips
    private val chipLineH = 20 // one wrapped line of chips

    private class ChipPos(val item: CheckboxItem, val x0: Int, val lineTop: Int, val boxX: Int, val labelX: Int, val x1: Int)

    /** Flow [items] left→right within [startX]..[endX], wrapping to new [chipLineH] lines. */
    private fun layoutChips(items: List<CheckboxItem>, startX: Int, endX: Int, topY: Int): Pair<List<ChipPos>, Int> {
        val chips = ArrayList<ChipPos>(items.size)
        var cx = startX
        var line = 0
        for (item in items) {
            val w = chipBox + chipBoxGap + textWidth(item.label, fBody)
            if (cx + w > endX && cx > startX) { line++; cx = startX }
            val top = topY + line * chipLineH
            chips.add(ChipPos(item, cx, top, cx, cx + chipBox + chipBoxGap, cx + w))
            cx += w + chipGap
        }
        return chips to (line + 1)
    }

    /** The label + description above a checkbox group's boxes (config menu only); null = none. */
    private fun checkboxHeader(opt: CheckboxGroupOption, x0: Int, x1: Int): Pair<List<String>, List<String>>? =
        if (rowDescriptions) labelBlock(opt, (x1 - 6 - resetW) - 8 - (x0 + 8)) else null

    /** Height a checkbox-group row needs, given the same [x0]/[x1] the section passes to [drawOptionRow]. */
    protected fun checkboxRowHeight(opt: CheckboxGroupOption, x0: Int, x1: Int): Int {
        val endX = (x1 - 6 - resetW) - 8
        val (_, lines) = layoutChips(opt.items, x0 + 8, endX, 0)
        val header = checkboxHeader(opt, x0, x1)?.let { blockHeight(it) + 4 } ?: 0
        return (header + lines * chipLineH + 12).coerceAtLeast(32)
    }

    private fun drawCheckboxGroup(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, y0: Int, x1: Int, opt: CheckboxGroupOption, dis: Boolean) {
        val rowRight = x1 - 6
        // Reset chip (resets every checkbox), top-right like other rows.
        val canReset = opt.isModified && !dis
        val chipX0 = rowRight - resetW
        val rY0 = y0 + 6
        val rY1 = rY0 + resetH
        val rHover = canReset && mx in chipX0..rowRight && my in rY0..rY1
        if (rHover) roundRect(g, chipX0, rY0, rowRight, rY1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
        val rColor = if (!canReset) Theme.TEXT_FAINT else if (rHover) Theme.ACCENT else Theme.TEXT_DIM
        drawTextV(g, "↺", chipX0 + (resetW - textWidth("↺", fBody)) / 2, rY0, rY1, rColor, fBody)
        if (canReset) region(chipX0, rY0, rowRight, rY1) { opt.reset() }

        var top = y0 + 6
        checkboxHeader(opt, x0, x1)?.let { header ->
            drawLabelBlock(g, header, null, x0 + 8, top, if (dis) Theme.TEXT_FAINT else Theme.TEXT)
            top += blockHeight(header) + 4
        }
        val (chips, _) = layoutChips(opt.items, x0 + 8, chipX0 - 8, top)
        for (c in chips) {
            val cy0 = c.lineTop
            val cy1 = c.lineTop + chipLineH
            drawCheckbox(g, c.boxX, cy0 + (chipLineH - chipBox) / 2, c.item.get())
            drawTextV(g, c.item.label, c.labelX, cy0, cy1, if (dis) Theme.TEXT_FAINT else Theme.TEXT, fBody)
            region(c.x0, cy0, c.x1, cy1) { c.item.toggle() }
        }
    }

    private fun drawCheckbox(g: GuiGraphicsExtractor, x: Int, y: Int, on: Boolean) {
        if (on) {
            roundRect(g, x, y, x + chipBox, y + chipBox, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, "✓", x + (chipBox - textWidth("✓", fBody)) / 2, y, y + chipBox, Theme.TEXT, fBody)
        } else {
            panel(g, x, y, x + chipBox, y + chipBox, Theme.FIELD_BG, Theme.FIELD_BORDER, SoulPanel.SMALL)
        }
    }

    /** Translucent overlay that greys out a disabled option row (fades it toward the section color). */
    private fun drawDisabledScrim(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        roundRect(g, x0, y0, x1, y1, (Theme.SECTION and 0xFFFFFF) or 0xB0000000.toInt(), SoulRenderPipelines.ROUND_SMALL)
    }

    /** A color swatch (checkerboard behind if it may be translucent) with a border. */
    protected fun drawSwatch(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, alpha: Boolean) {
        if (alpha) checker(g, x0, y0, x1, y1)
        panel(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, color, Theme.FIELD_BORDER, SoulPanel.SMALL)
    }

    /** A gray checkerboard, so a translucent color over it reads as translucent. */
    private fun checker(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        val s = 4
        var yy = y0
        var row = 0
        while (yy < y1) {
            var xx = x0
            var col = row
            while (xx < x1) {
                val c = if (col % 2 == 0) 0xFFB0B0B0.toInt() else 0xFF707070.toInt()
                g.fill(xx, yy, minOf(xx + s, x1), minOf(yy + s, y1), c)
                xx += s; col++
            }
            yy += s; row++
        }
    }

    /** Handle an [ActionOption] click (open a screen, etc). Subclasses override; default no-op. */
    protected open fun onAction(id: String) {}

    // ---------------------------------------------------------------- widgets (the switch is SoulScreenBase.drawToggle)

    protected fun drawSlider(
        g: GuiGraphicsExtractor,
        x0: Int,
        cy: Int,
        x1: Int,
        opt: SliderOption,
    ) {
        val display = pendingSlider[opt] ?: opt.get()
        val frac = ((display - opt.min) / (opt.max - opt.min)).coerceIn(0.0, 1.0)
        roundRect(g, x0, cy - 2, x1, cy + 2, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL) // track (4px)
        val fillX = x0 + (frac * (x1 - x0)).roundToInt()
        if (fillX > x0 + 1) roundRect(g, x0, cy - 2, fillX, cy + 2, Theme.ACCENT, SoulRenderPipelines.ROUND_PILL)
        roundRect(g, fillX - 6, cy - 6, fillX + 6, cy + 6, Theme.TEXT, SoulRenderPipelines.ROUND_PILL) // knob (6r)

        // Value readout (left of the track); click it to type an exact number.
        val editing = editingSlider === opt
        val valueStr = if (editing) editBuffer else formatSlider(opt, display)
        val vw = maxOf(textWidth(valueStr, fBody), textWidth("0.0", fBody)) + 4
        val vx1 = x0 - 6
        val vx0 = vx1 - vw
        if (editing) {
            roundRect(g, vx0 - 2, cy - 7, vx1 + 2, cy + 7, Theme.FIELD_BG, SoulRenderPipelines.ROUND_SMALL)
            if ((System.currentTimeMillis() / 500) % 2 == 0L) {
                g.fill(vx1, cy - 5, vx1 + 1, cy + 5, Theme.TEXT)
            }
        }
        drawTextV(g, valueStr, vx1 - textWidth(valueStr, fBody), cy - 7, cy + 7, if (editing) Theme.TEXT else Theme.TEXT_DIM, fBody)
        region(vx0 - 2, cy - 7, vx1 + 2, cy + 7) {
            commit()
            editingSlider = opt
            editBuffer = formatSlider(opt, display)
        }

        region(x0, cy - 6, x1, cy + 6) {
            editingSlider = null
            draggingSlider = opt
            dragTrackX0 = x0
            dragTrackX1 = x1
            setSliderFromMouse(opt, x0, x1, lastMouseX)
        }
    }

    /** Integer-stepped sliders read as whole numbers; others keep one decimal, two when the value is off the tenth grid (Auto's 1.25). */
    private fun formatSlider(opt: SliderOption, value: Double): String =
        if (opt.step >= 1.0) value.roundToInt().toString()
        else if (kotlin.math.abs(value * 10 - (value * 10).roundToInt()) > 1e-3) String.format(Locale.ROOT, "%.2f", value)
        else String.format(Locale.ROOT, "%.1f", value)

    /**
     * A keybind picker: a bordered box showing the current binding's display name ("Not bound"
     * when blank). Clicking arms capture mode; while capturing, [mouseClicked]/[keyPressed] at the
     * top of this class consume the next input as the binding (mouse click = mouse-button bind, so
     * re-clicking the box does NOT cancel — Escape clears the binding instead).
     */
    private fun drawKeybindButton(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        opt: KeybindOption,
    ) {
        val capturing = capturingKeybind === opt
        panel(g, x0, y0, x1, y1, Theme.FIELD_BG, if (capturing) Theme.ACCENT else Theme.FIELD_BORDER, SoulPanel.SMALL)
        val label = if (capturing) keyCapture.preview() ?: "> Press a key <" else keybindLabel(opt.get())
        val color = if (capturing) Theme.ACCENT else if (opt.get().isBlank()) Theme.TEXT_FAINT else Theme.TEXT
        drawTextV(g, label, (x0 + x1) / 2 - textWidth(label, fBody) / 2, y0, y1, color, fBody)
        region(x0, y0, x1, y1) {
            commit()
            closeDropdown()
            openColor = null
            keyCapture = KeyComboCapture()
            capturingKeybind = opt
        }
    }

    /** Human-readable name for a stored key or combo ("Not bound" when blank). */
    private fun keybindLabel(stored: String): String =
        if (stored.isBlank()) "Not bound" else KeyCombo.label(stored)

    // ---------------------------------------------------------------- sound field

    protected val soundFieldW = 150

    /** The ref the field's play button started, so that field shows a stop button while it plays. */
    private var fieldPreview: SoundRef? = null

    /**
     * A sound option's field: play button, the sound's name (subtitle first) and a caret. The play
     * button previews the stored sound; anywhere else opens the picker window over this screen.
     */
    private fun drawSoundField(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        opt: SoundOption,
    ) {
        val ref = opt.get()
        val hovered = mx in x0..x1 && my in y0..y1
        panel(g, x0, y0, x1, y1, Theme.FIELD_BG, if (hovered) Theme.CONTROL_HOVER else Theme.FIELD_BORDER, SoulPanel.SMALL)
        region(x0, y0, x1, y1) { openSoundPicker(opt) }
        val cy = (y0 + y1) / 2
        var textX = x0 + 7
        if (ref != null) {
            val r = 6
            val cx = x0 + 3 + r
            val playing = fieldPreview?.let { it == ref && SoulSounds.isPreviewing() } == true
            val playHover = mx in (cx - r)..(cx + r) && my in (cy - r)..(cy + r)
            roundRect(g, cx - r, cy - r, cx + r, cy + r, if (playing) Theme.ACCENT else if (playHover) Theme.CONTROL_HOVER else Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
            if (playing) {
                g.fill(cx - 2, cy - 2, cx + 2, cy + 2, Theme.TEXT)
            } else {
                playTriangle(g, cx.toFloat(), cy.toFloat(), r.toFloat(), Theme.TEXT)
            }
            region(cx - r, cy - r, cx + r, cy + r) {
                if (playing) {
                    SoulSounds.stopPreview()
                    fieldPreview = null
                } else {
                    fieldPreview = if (SoulSounds.preview(ref) != null) ref else null
                }
            }
            textX = cx + r + 6
        }
        val name = if (ref == null) "None" else SoundNames.title(ref)
        val color = if (ref == null) Theme.TEXT_FAINT else if (SoulSounds.canPlay(ref)) Theme.TEXT else 0xFFE05252.toInt()
        drawTextV(g, ellipsize(name, x1 - 14 - textX, fBody), textX, y0, y1, color, fBody)
        caret(g, x1 - 8, cy - 1)
    }

    private fun openSoundPicker(opt: SoundOption) {
        commit()
        closeDropdown()
        openColor = null
        val pick = SoundPick(opt.purpose, opt.label, opt.get(), opt.allowNone, onPick = { opt.set(it) })
        minecraft?.setScreenAndShow(SoundPickerScreen(this, pick))
    }

    // ---------------------------------------------------------------- editing state

    /** Commit whichever inline editor is active: a text option or a slider's typed number. */
    protected fun commit() {
        focusedText?.let {
            it.set(editBuffer)
            focusedText = null
        }
        editingSlider?.let { opt ->
            editBuffer.toDoubleOrNull()?.let { v -> applySliderValue(opt, v.coerceIn(opt.min, opt.max)) }
            editingSlider = null
        }
    }

    protected fun setSliderFromMouse(
        opt: SliderOption,
        x0: Int,
        x1: Int,
        mx: Int,
    ) {
        val frac = ((mx - x0).toDouble() / (x1 - x0)).coerceIn(0.0, 1.0)
        var value = opt.min + frac * (opt.max - opt.min)
        if (opt.step > 0.0) value = opt.min + (((value - opt.min) / opt.step).roundToInt() * opt.step)
        applySliderValue(opt, value.coerceIn(opt.min, opt.max))
    }

    /** Apply a slider value live, or stash it as pending when the option defers to "Apply". */
    protected fun applySliderValue(
        opt: SliderOption,
        value: Double,
    ) {
        if (opt.applyImmediately) opt.set(value) else pendingSlider[opt] = value
    }

    /** True when any deferred option has an uncommitted value (drives a footer Apply button). */
    protected fun hasPending(): Boolean = pendingSlider.any { (opt, v) -> v != opt.get() }

    /** Commit all pending deferred values to config. */
    protected fun applyPending() {
        if (pendingSlider.isEmpty()) return
        pendingSlider.forEach { (opt, v) -> opt.set(v) }
        pendingSlider.clear()
    }

    // ---------------------------------------------------------------- overlays

    /**
     * Before [SoulScreenBase]'s modal covers the screen: commit whatever was being typed and close
     * the picker / dropdown, so the user does not come back to a half-edited value under a popup
     * they can no longer see.
     */
    override fun dismissTransientEditors() {
        commit()
        openColor = null
        closeDropdown()
    }

    // ---------------------------------------------------------------- color picker

    protected val colorPickerOpen: Boolean get() = openColor != null

    /**
     * Open the picker for [opt] beside the swatch at [ax0]..[ax1] × [ay0]..[ay1]. [palette] adds quick colors
     * (RGB) under the square, 8 per row (the scoreboard's 16 Minecraft colors).
     */
    protected fun openColorPicker(opt: ColorOption, ax0: Int, ay0: Int, ax1: Int, ay1: Int, palette: List<Int> = emptyList()) {
        commit()
        closeDropdown()
        openColor = opt
        pickerPalette = palette
        pickerAnchor = intArrayOf(ax0, ay0, ax1, ay1)
        val cc = opt.get()
        val hsva = argbToHsva(cc.argb)
        pickH = hsva[0]; pickS = hsva[1]; pickV = hsva[2]
        pickA = if (opt.hasAlpha) hsva[3] else 1f
        pickChroma = cc.chroma
    }

    /** Close the picker, keeping the color it set (a screen's Escape). */
    protected fun closeColorPicker() {
        openColor = null
    }

    private fun applyPicker() {
        val opt = openColor ?: return
        val a = if (opt.hasAlpha) pickA else 1f
        opt.set(opt.get().copy(argb = hsvaToArgb(pickH, pickS, pickV, a), chroma = pickChroma))
    }

    private fun updatePickerDrag() {
        when (pickerDrag) {
            1 -> {
                pickS = fracIn(lastMouseX, svRect[0], svRect[2])
                pickV = 1f - fracIn(lastMouseY, svRect[1], svRect[3])
            }
            2 -> pickH = fracIn(lastMouseY, hueRect[1], hueRect[3])
            3 -> pickA = 1f - fracIn(lastMouseY, alphaRect[1], alphaRect[3])
        }
        applyPicker()
    }

    private fun fracIn(v: Int, a: Int, b: Int): Float =
        ((v - a).toFloat() / (b - a).coerceAtLeast(1)).coerceIn(0f, 1f)

    /**
     * Draw the color-picker popup (if open) anchored beside the swatch it was opened from (to its
     * left, clamped on-screen), within the [uiW]×[uiH] viewport: an SV square, a hue bar, an optional
     * opacity bar, and a live swatch + hex. Call LAST in the subclass render so it overlays everything;
     * a full-screen catch region under it closes it on an outside click.
     */
    protected fun drawColorPicker(g: GuiGraphicsExtractor, uiW: Int, uiH: Int, mx: Int, my: Int) {
        val opt = openColor ?: return

        // Full-screen catch → click outside closes (picker regions added after this win over it).
        region(0, 0, uiW, uiH) { commit(); openColor = null }

        val alpha = opt.hasAlpha
        val pad = 8
        val svSize = 96
        val bar = 12
        val gap = 8
        val barsW = bar + (if (alpha) gap + bar else 0)
        val hexH = 16
        // Chroma toggle row; when on, a note where its motion and speed are set (one setting for every chroma color).
        val chromaNote = if (pickChroma) wrapText(CHROMA_NOTE, svSize + gap + barsW, fCaption) else emptyList()
        val chromaH = 20 + if (chromaNote.isEmpty()) 0 else 2 + chromaNote.size * 11
        val panelW = pad + svSize + gap + barsW + pad
        val palRows = (pickerPalette.size + PAL_COLS - 1) / PAL_COLS
        val palH = if (palRows == 0) 0 else palRows * PAL_CHIP + (palRows - 1) * PAL_GAP + gap
        val panelH = pad + svSize + gap + palH + hexH + chromaH + pad
        // Anchor beside the swatch it was opened from: prefer to its left, fall back to its right if
        // there isn't room, then clamp fully on-screen. Top-align with the swatch, nudged up as needed.
        val anchorGap = 6
        val leftPx = pickerAnchor[0] - anchorGap - panelW
        val px =
            if (leftPx >= 2) {
                leftPx
            } else {
                (pickerAnchor[2] + anchorGap).coerceAtMost((uiW - panelW - 2).coerceAtLeast(2))
            }
        val py = pickerAnchor[1].coerceIn(2, (uiH - panelH - 2).coerceAtLeast(2))

        panel(g, px - 1, py - 1, px + panelW + 1, py + panelH + 1, Theme.PANEL_FLOATING, Theme.FIELD_BORDER)
        // Consume clicks on the panel background (between controls) so they don't close the picker.
        region(px, py, px + panelW, py + panelH) {}

        // SV square: per-column vertical gradient (hue@sat, full value) → black.
        val svX = px + pad
        val svY = py + pad
        svRect = intArrayOf(svX, svY, svX + svSize, svY + svSize)
        for (i in 0 until svSize) {
            val s = i.toFloat() / (svSize - 1)
            g.fillGradient(svX + i, svY, svX + i + 1, svY + svSize, hsvRgb(pickH, s, 1f), 0xFF000000.toInt())
        }
        // SV cursor.
        val curX = svX + (pickS * svSize).roundToInt()
        val curY = svY + ((1f - pickV) * svSize).roundToInt()
        roundRect(g, curX - 3, curY - 3, curX + 3, curY + 3, 0xFF000000.toInt(), SoulRenderPipelines.ROUND_PILL)
        roundRect(g, curX - 2, curY - 2, curX + 2, curY + 2, 0xFFFFFFFF.toInt(), SoulRenderPipelines.ROUND_PILL)
        region(svX, svY, svX + svSize, svY + svSize) { pickerDrag = 1; updatePickerDrag() }

        // Hue bar.
        val hueX = svX + svSize + gap
        val hueY = svY
        hueRect = intArrayOf(hueX, hueY, hueX + bar, hueY + svSize)
        for (i in 0 until svSize) {
            g.fill(hueX, hueY + i, hueX + bar, hueY + i + 1, hsvRgb(i.toFloat() / (svSize - 1), 1f, 1f))
        }
        val hy = hueY + (pickH * svSize).roundToInt()
        g.fill(hueX - 1, hy - 1, hueX + bar + 1, hy + 1, 0xFFFFFFFF.toInt())
        region(hueX, hueY, hueX + bar, hueY + svSize) { pickerDrag = 2; updatePickerDrag() }

        // Opacity bar (optional): transparent (top) → opaque, over a checkerboard.
        if (alpha) {
            val aX = hueX + bar + gap
            alphaRect = intArrayOf(aX, hueY, aX + bar, hueY + svSize)
            checker(g, aX, hueY, aX + bar, hueY + svSize)
            val solid = hsvRgb(pickH, pickS, pickV)
            g.fillGradient(aX, hueY, aX + bar, hueY + svSize, solid and 0x00FFFFFF, solid)
            val ay = hueY + ((1f - pickA) * svSize).roundToInt()
            g.fill(aX - 1, ay - 1, aX + bar + 1, ay + 1, 0xFFFFFFFF.toInt())
            region(aX, hueY, aX + bar, hueY + svSize) { pickerDrag = 3; updatePickerDrag() }
        }
        // Quick colors (optional): chips 8 per row; a click takes the color as it is.
        val palY = svY + svSize + gap
        val current = hsvRgb(pickH, pickS, pickV) and 0xFFFFFF
        for ((i, rgb) in pickerPalette.withIndex()) {
            val cx = svX + (i % PAL_COLS) * (PAL_CHIP + PAL_GAP)
            val cy = palY + (i / PAL_COLS) * (PAL_CHIP + PAL_GAP)
            panel(g, cx - 1, cy - 1, cx + PAL_CHIP + 1, cy + PAL_CHIP + 1, 0xFF000000.toInt() or rgb, if (rgb == current) 0xFFFFFFFF.toInt() else Theme.FIELD_BORDER, SoulPanel.SMALL)
            region(cx, cy, cx + PAL_CHIP, cy + PAL_CHIP) {
                val hsva = argbToHsva(0xFF000000.toInt() or rgb)
                pickH = hsva[0]; pickS = hsva[1]; pickV = hsva[2]
                pickChroma = false
                applyPicker()
            }
        }

        // Bottom: live swatch (effective) + hex readout of the base color.
        val hexY = palY + palH
        drawSwatch(g, svX, hexY, svX + 22, hexY + hexH, opt.get().effective(), alpha)
        val base = opt.get().argb
        val hex = if (alpha) String.format("#%08X", base) else String.format("#%06X", base and 0xFFFFFF)
        drawTextV(g, hex, svX + 28, hexY, hexY + hexH, Theme.TEXT, fBody)

        // Chroma toggle (SkyHanni-style): when on, this color is a window onto Soul's shared rainbow (render/Chroma).
        val panelRight = px + panelW - pad
        val chY = hexY + hexH + 4
        drawTextV(g, "Chroma", svX, chY, chY + toggleH, Theme.TEXT, fBody)
        drawToggle(g, panelRight - toggleW, chY, pickChroma) { pickChroma = !pickChroma; applyPicker() }

        var noteY = chY + toggleH + 4
        for (line in chromaNote) {
            drawTextV(g, line, svX, noteY, noteY + 11, Theme.TEXT_DIM, fCaption)
            noteY += 11
        }
    }

    // ---------------------------------------------------------------- dropdown

    /**
     * Draw the open dropdown's list. Kept as a name of its own because both config screens call it
     * from their render; the implementation is [SoulScreenBase.drawOpenDropdown], shared with any
     * other screen that wants a dropdown (the Profile Viewer's profile switcher).
     */
    protected fun drawDropdown(g: GuiGraphicsExtractor, uiW: Int, uiH: Int, mx: Int, my: Int) =
        drawOpenDropdown(g, uiW, uiH, mx, my)

    // --- HSV <-> ARGB helpers ---

    /** Full-opacity RGB int (0xFFRRGGBB) for HSV in [0,1]. */
    private fun hsvRgb(h: Float, s: Float, v: Float): Int = hsvaToArgb(h, s, v, 1f)

    private fun hsvaToArgb(h: Float, s: Float, v: Float, a: Float): Int {
        val hh = ((h % 1f) + 1f) % 1f
        val i = (hh * 6f).toInt() % 6
        val f = hh * 6f - (hh * 6f).toInt()
        val p = v * (1f - s)
        val q = v * (1f - f * s)
        val t = v * (1f - (1f - f) * s)
        var r = 0f; var g = 0f; var b = 0f
        when (i) {
            0 -> { r = v; g = t; b = p }
            1 -> { r = q; g = v; b = p }
            2 -> { r = p; g = v; b = t }
            3 -> { r = p; g = q; b = v }
            4 -> { r = t; g = p; b = v }
            else -> { r = v; g = p; b = q }
        }
        return (to255(a) shl 24) or (to255(r) shl 16) or (to255(g) shl 8) or to255(b)
    }

    private fun to255(f: Float): Int = (f * 255f).roundToInt().coerceIn(0, 255)

    /** [h, s, v, a] in [0,1] from a packed ARGB int. */
    private fun argbToHsva(argb: Int): FloatArray {
        val a = ((argb ushr 24) and 0xFF) / 255f
        val r = ((argb ushr 16) and 0xFF) / 255f
        val g = ((argb ushr 8) and 0xFF) / 255f
        val b = (argb and 0xFF) / 255f
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        val d = max - min
        val v = max
        val s = if (max == 0f) 0f else d / max
        var h = 0f
        if (d != 0f) {
            h = when {
                max == r -> ((g - b) / d) % 6f
                max == g -> (b - r) / d + 2f
                else -> (r - g) / d + 4f
            } / 6f
            if (h < 0f) h += 1f
        }
        return floatArrayOf(h, s, v, a)
    }
}
