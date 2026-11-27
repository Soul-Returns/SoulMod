package com.soulreturns.features.scoreboard.gui

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.ScoreboardConfig
import com.soulreturns.config.ScoreboardEntry
import com.soulreturns.config.ScoreboardLook
import com.soulreturns.config.ScoreboardSetup
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.ChromaOptions
import com.soulreturns.config.gui.ConfigScreenBase
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.model.ColorOption
import com.soulreturns.config.gui.model.CycleOption
import com.soulreturns.config.gui.model.SliderOption
import com.soulreturns.config.gui.render.Chroma
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.features.scoreboard.ColorPart
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineChips
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineNumber
import com.soulreturns.features.scoreboard.LineOption
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineText
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.OptionValues
import com.soulreturns.features.scoreboard.ScoreboardActivities
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardHud
import com.soulreturns.features.scoreboard.ScoreboardValues
import com.soulreturns.features.scoreboard.importer.ScoreboardImport
import com.soulreturns.features.scoreboard.lines.CustomTextLine
import com.soulreturns.features.scoreboard.lines.EmptyLine
import com.soulreturns.features.scoreboard.lines.FooterLine
import com.soulreturns.features.scoreboard.lines.NewLinesLine
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulTextField
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.joml.Matrix3x2f
import org.lwjgl.glfw.GLFW
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The Scoreboard setup screen (spec `mocks/scoreboard/`, "Setup screen"): the line list | the live preview
 * drawn by [ScoreboardHud] | an inspector generated from the selected [LineType]'s options and color parts,
 * or the Look with nothing selected. With "Different setups per activity" on, a strip picks the setup to
 * edit. Everything comes from [ScoreboardCatalog] and `cfg.scoreboard`, and every edit applies at once
 * through [ConfigManager.update].
 */
class ScoreboardSetupScreen(private val parent: Screen?) : ConfigScreenBase(Component.literal("Scoreboard")) {
    companion object {
        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(ScoreboardSetupScreen(parent)) }
        }

        private const val ROW_H = 19
        private const val LEFT_W = 250
        private const val RIGHT_W = 380
        private const val GAP = 12
        private const val BANNER_H = 26
        private const val OPT_ROW_H = 32
        private const val NOTICE_MS = 6000L
        private const val ORDER_ROW_H = 22

        private val DIM = 0x8C000000.toInt()
        private val INSPECTOR = 0xFF1D1D1D.toInt()
        private val SECTION = 0xFF1E1E1E.toInt()
        private val ROW_HOVER = 0xFF2C2C2C.toInt()
        private val CHIP = 0xFF2A2A2A.toInt()
        private val CHIP_HOVER = 0xFF353535.toInt()
        private val PURPLE = 0xFFA855F7.toInt()
        private val DOT = 0xFFC084FC.toInt()
        private val SOUL_TAG = 0xFF7C3AED.toInt()
        private val AMBER = 0xFFFBBF24.toInt()
        private val GREEN = 0xFF22C55E.toInt()
        private val WHITE = 0xFFFFFFFF.toInt()
        private val G_BB = 0xFFBBBBBB.toInt()
        private val G_CC = 0xFFCCCCCC.toInt()
        private val G_DD = 0xFFDDDDDD.toInt()
        private val G_AA = 0xFFAAAAAA.toInt()
        private val G_77 = 0xFF777777.toInt()
        private val G_66 = 0xFF666666.toInt()
        private val G_55 = 0xFF555555.toInt()
        private val VALUE_CHIP = 0xFFC4B5FD.toInt()

        /** The picker's quick colors: the 16 Minecraft colors (the mock's palette, black first). */
        private val MC_COLORS =
            listOf(
                0x000000, 0xFFFFFF, 0xAAAAAA, 0x555555, 0x55FF55, 0x00AA00, 0x55FFFF, 0x00AAAA,
                0x5555FF, 0x0000AA, 0xFF55FF, 0xAA00AA, 0xFFFF55, 0xFFAA00, 0xFF5555, 0xAA0000,
            )

        /** Legacy code char of each Minecraft color, so a picked one goes into text as `§6`, not `§#ffaa00`. */
        private val LEGACY = "0123456789abcdef"

        private val CHROMA_TARGETS = listOf("header" to "Header", "labels" to "Labels", "values" to "Values", "footer" to "Footer", "border" to "Border")
        private val NUMBER_STYLES = listOf("tcn" to "Purse: 1,234", "ctn" to "Purse: 1,234 (all colored)", "cnt" to "1,234 Purse", "cnrt" to "1,234 Purse (white label)")
        private val ALIGNS = listOf("left", "center", "right")
    }

    private val f12 = 12f * MsdfFont.LEGACY_EM
    private val f14 = 14f * MsdfFont.LEGACY_EM
    private val f9 = 9f * MsdfFont.LEGACY_EM

    // ------------------------------------------------------------------------------------------ state

    /** The setup being edited: null = Everywhere, else an activity id. */
    private var tab: String? = null

    /** Activities picked through "+ Activity" this session (shown in the strip before they have a setup). */
    private val sessionTabs = LinkedHashSet<String>()

    /** Stored index of the selected entry in the edited setup; -1 = none (the Look). */
    private var selected = -1
    private var pickerOpen = false
    private var addActOpen = false
    private var advancedOpen = false
    private var moreValues = false
    private var importBanner = !cfg.scoreboard.importOffered && ScoreboardImport.available()
    private var notice: String? = null
    private var noticeAt = 0L
    private var revealSelected = false

    private val listSb = ScrollState()
    private val inspectorSb = ScrollState()
    private val pickerSb = ScrollState()
    private val search = SoulTextField()
    private val fields = HashMap<String, SoulTextField>()
    private val tokens = HashMap<String, Any>()

    // Drag to reorder (list rows): the pressed entry, the press position, and whether it moved yet.
    private var pressed = -1
    private var pressY = 0.0
    private var dragging = false
    private var deselectOnRelease = false
    private var dropSlot = -1

    /** Stepper wheel targets this frame: rect + step(delta). */
    private val wheelSteps = ArrayList<Pair<IntArray, (Int) -> Unit>>()

    // Text color popup ("Color…"): where its code went in and how long it is, so another pick replaces it.
    private var textPick = ChromaColor(0xFFFFAA00.toInt())
    private var textPickAt = -1
    private var textPickLen = 0

    // Per-frame layout read by the input handlers.
    private var rows: List<Int> = emptyList()
    private var nonPinned: List<Int> = emptyList()
    private val listRect = IntArray(4)
    private var stripActs: List<String> = emptyList()

    // Look options with a widget state of their own (slider drag, typed readout): one instance per screen.
    private val chromaSpeed = ChromaOptions.speed()
    private val chromaMotion = ChromaOptions.motion()
    private val bgOpacity =
        SliderOption(
            "Background opacity", null, { cfg.scoreboard.look.backgroundOpacity.toDouble() },
            { v -> look { copy(backgroundOpacity = v.roundToInt().coerceIn(0, 100)) } }, ScoreboardLook().backgroundOpacity.toDouble(), 0.0, 100.0, 5.0,
        )
    private val borderSpeed =
        SliderOption(
            "Border speed", null, { cfg.scoreboard.look.borderSpeed.toDouble() },
            { v -> look { copy(borderSpeed = v.toFloat()) } }, ScoreboardLook().borderSpeed.toDouble(),
            ScoreboardLook.MIN_BORDER_SPEED.toDouble(), ScoreboardLook.MAX_BORDER_SPEED.toDouble(), 0.5,
        )

    // ---------------------------------------------------------------------------------- config access

    private fun sb(block: ScoreboardConfig.() -> ScoreboardConfig) = ConfigManager.update { copy(scoreboard = scoreboard.block()) }

    private fun look(block: ScoreboardLook.() -> ScoreboardLook) = sb { copy(look = look.block()) }

    /** Whether [tab]'s lines can be edited here: Everywhere, or an activity with its own setup. */
    private fun editable(c: ScoreboardConfig): Boolean = tab.let { it == null || it in c.activities }

    /** The entries the list and the preview show. */
    private fun entries(c: ScoreboardConfig): List<ScoreboardEntry> = tab?.let { c.activities[it]?.entries } ?: c.main.entries

    private fun updateEntries(transform: (List<ScoreboardEntry>) -> List<ScoreboardEntry>) {
        val t = tab
        sb {
            if (t == null) {
                copy(main = ScoreboardSetup(transform(main.entries)))
            } else {
                val setup = activities[t] ?: return@sb this
                copy(activities = activities + (t to ScoreboardSetup(transform(setup.entries))))
            }
        }
    }

    private fun updateEntry(index: Int, transform: (ScoreboardEntry) -> ScoreboardEntry) =
        updateEntries { list -> if (index in list.indices) list.toMutableList().also { it[index] = transform(it[index]) } else list }

    private fun setOption(index: Int, key: String, value: JsonElement) =
        updateEntry(index) { e -> e.copy(options = JsonObject(e.options + (key to value))) }

    private fun setColor(index: Int, key: String, color: ChromaColor?) =
        updateEntry(index) { e -> e.copy(colors = if (color == null) e.colors - key else e.colors + (key to color)) }

    private fun select(index: Int) {
        if (index == selected) return
        selected = index
        fields.values.forEach { it.focused = false }
        fields.clear()
        moreValues = false
        inspectorSb.pos = 0f
        closeDropdown()
        closeColorPicker()
    }

    private fun token(key: String): Any = tokens.getOrPut(key) { Any() }

    // ------------------------------------------------------------------------------------------ render

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        scrollTargets.clear()
        wheelSteps.clear()
        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val modal = alertModalOpen
        val mx = if (modal) -10000 else (mouseX / s).roundToInt()
        val my = if (modal) -10000 else (mouseY / s).roundToInt()
        lastMouseX = (mouseX / s).roundToInt()
        lastMouseY = (mouseY / s).roundToInt()

        val c = cfg.scoreboard
        if (!c.perActivity) tab = null
        val canEdit = editable(c)
        val entries = entries(c)
        if (!canEdit || selected !in entries.indices || ScoreboardCatalog.type(entries[selected].type) == null) {
            if (selected != -1) select(-1)
        }
        val known = entries.indices.filter { ScoreboardCatalog.type(entries[it].type) != null }
        val pin = { i: Int -> ScoreboardCatalog.type(entries[i].type)?.pinned ?: LineType.PIN_NONE }
        rows = known.filter { pin(it) == LineType.PIN_TOP } + known.filter { pin(it) == LineType.PIN_NONE } + known.filter { pin(it) == LineType.PIN_BOTTOM }
        nonPinned = known.filter { pin(it) == LineType.PIN_NONE }
        val now = System.currentTimeMillis()
        val preview = ScoreboardPreview.build(c, entries, tab, now)

        g.fill(0, 0, width, height, DIM)
        g.pose().pushMatrix()
        g.pose().scale(s, s)

        val m = if (uiW < 700) 4 else 12
        val sx0 = m
        val sy0 = if (uiH < 500) 4 else 10
        val sx1 = uiW - m
        val sy1 = uiH - sy0
        panel(g, sx0 - 1, sy0 - 1, sx1 + 1, sy1 + 1, Theme.PANEL_FLOATING, Theme.FIELD_BORDER)
        val ix0 = sx0 + 12
        val ix1 = sx1 - 12
        var y = sy0 + 12

        val switchRect = drawTopRow(g, mx, my, c, ix0, ix1, y)
        y += BANNER_H + 8
        if (importBanner) {
            drawImportBanner(g, mx, my, ix0, ix1, y)
            y += BANNER_H + 10
        }
        if (c.perActivity) {
            drawScopeBanner(g, mx, my, c, ix0, ix1, y)
            y += BANNER_H + 10
        }

        val colTop = y
        val colBottom = sy1 - 12
        val innerW = ix1 - ix0
        val rightW = min(RIGHT_W, (innerW * 0.32f).toInt())
        val leftW = min(LEFT_W, (innerW * 0.22f).toInt())
        val lx1 = ix0 + leftW
        val rx0 = ix1 - rightW
        val midX0 = lx1 + GAP
        val midX1 = rx0 - GAP

        drawPreview(g, if (pickerOpen) -10000 else mx, if (pickerOpen) -10000 else my, c, entries, preview, canEdit, midX0, colTop, midX1, colBottom)
        if (pickerOpen && canEdit) drawPicker(g, mx, my, entries, midX0, colTop, midX1, colBottom)
        drawList(g, mx, my, c, entries, preview.here, canEdit, ix0, colTop, lx1, colBottom)
        drawInspector(g, mx, my, c, entries, preview.here, canEdit, rx0, colTop, ix1, colBottom)

        if (addActOpen) drawAddActivity(g, mx, my, uiW, uiH)
        drawNotice(g, switchRect, now)
        drawTooltip(g, uiW, uiH)
        drawColorPicker(g, uiW, uiH, mx, my)
        drawDropdown(g, uiW, uiH, mx, my)
        drawAlertModal(g, uiW, uiH, lastMouseX, lastMouseY)
        g.pose().popMatrix()
    }

    // ----------------------------------------------------------------------------------------- top row

    /** Title, the activity strip (switch on), the master switch and the buttons; returns the switch block's rect. */
    private fun drawTopRow(g: GuiGraphicsExtractor, mx: Int, my: Int, c: ScoreboardConfig, x0: Int, x1: Int, y: Int): IntArray {
        val cy = y + BANNER_H / 2
        drawTextV(g, "Scoreboard", x0, y, y + BANNER_H, WHITE, fTitle, MsdfFonts.menuSemibold)
        var x = x0 + textWidth("Scoreboard", fTitle, MsdfFonts.menuSemibold) + 16

        // Buttons on the right.
        val doneW = textWidth("Done", fBody) + 24
        val resetW = textWidth("Reset to default", fBody) + 24
        val bx1 = x1
        drawButton(g, mx, my, bx1 - doneW, cy - 9, bx1, cy + 9, "Done", accent = true) { onClose() }
        drawButton(g, mx, my, bx1 - doneW - 6 - resetW, cy - 9, bx1 - doneW - 6, cy + 9, "Reset to default") { confirmReset() }
        val rightLimit = bx1 - doneW - 6 - resetW - 12

        if (c.perActivity) x = drawStrip(g, mx, my, c, x, cy, rightLimit - 290)

        // "Different setups per activity" + its fixed description + the switch.
        val label = "Different setups per activity"
        val desc = "Give Dungeons, Kuudra and other activities their own lines"
        val sx = x + 8
        val tw = max(textWidth(label, f12), textWidth(desc, fCaption))
        drawText(g, label, sx, cy - 10, G_DD, f12)
        drawText(g, desc, sx, cy + 3, G_77, fCaption)
        val tx = sx + tw + 10
        drawToggle(g, tx, cy - toggleH / 2, c.perActivity) { togglePerActivity() }
        return intArrayOf(sx, y, tx + toggleW, y + BANNER_H)
    }

    private fun togglePerActivity() {
        val c = cfg.scoreboard
        if (c.perActivity) {
            val kept = ScoreboardActivities.all.filter { it.id in c.activities }.map { it.name }
            notice =
                if (kept.isEmpty()) null
                else "Your ${kept.joinToString(" and ")} setup${if (kept.size > 1) "s are" else " is"} saved for when you turn this back on"
            noticeAt = System.currentTimeMillis()
            tab = null
            select(-1)
        }
        addActOpen = false
        sb { copy(perActivity = !perActivity) }
    }

    /** The "Setup for" strip: Everywhere, then the activities (a dot = own setup), "+ Activity", the legend. */
    private fun drawStrip(g: GuiGraphicsExtractor, mx: Int, my: Int, c: ScoreboardConfig, x0: Int, cy: Int, limit: Int): Int {
        var x = x0
        drawTextV(g, "Setup for", x, cy - 9, cy + 9, Theme.TEXT_DIM, fBody)
        x += textWidth("Setup for", fBody) + 8
        stripActs = ScoreboardActivities.all.map { it.id }.filter { id ->
            ScoreboardActivities.of(id)?.layout != null || id in c.activities || id in sessionTabs
        }
        x = chip(g, mx, my, x, cy, "Everywhere", tab == null, Theme.ACCENT, dot = false) { setTab(null) }
        for (id in stripActs) {
            val a = ScoreboardActivities.of(id) ?: continue
            x = chip(g, mx, my, x + 4, cy, a.name, tab == id, PURPLE, dot = id in c.activities) { setTab(id) }
        }
        // "+ Activity": dashed outline in the mock, a 1 px #444 ring here.
        val addW = textWidth("+ Activity", fBody) + 20
        val ax = x + 4
        val hover = mx in ax..ax + addW && my in cy - 9..cy + 9
        panel(g, ax, cy - 9, ax + addW, cy + 9, 0xFF1B1B1B.toInt(), if (hover) 0xFF666666.toInt() else 0xFF444444.toInt(), SoulPanel.SMALL)
        drawTextV(g, "+ Activity", ax + 10, cy - 9, cy + 9, Theme.TEXT_DIM, fBody)
        addActRect[0] = ax; addActRect[1] = cy - 9; addActRect[2] = ax + addW; addActRect[3] = cy + 9
        region(ax, cy - 9, ax + addW, cy + 9) { addActOpen = !addActOpen }
        x = ax + addW
        // Legend, when it fits.
        val legend = "own setup · the rest use Everywhere"
        val lw = 8 + textWidth(legend, fCaption)
        if (x + 8 + lw < limit) {
            roundRect(g, x + 8, cy - 3, x + 14, cy + 3, DOT, SoulRenderPipelines.ROUND_PILL)
            drawTextV(g, legend, x + 16, cy - 8, cy + 8, G_66, fCaption)
            x += 8 + lw
        }
        return x
    }

    private val addActRect = IntArray(4)

    private fun chip(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, cy: Int, label: String, on: Boolean, onColor: Int, dot: Boolean, action: () -> Unit): Int {
        val w = textWidth(label, fBody) + 20 + if (dot) 12 else 0
        val hover = mx in x..x + w && my in cy - 9..cy + 9
        roundRect(g, x, cy - 9, x + w, cy + 9, if (on) onColor else if (hover) CHIP_HOVER else CHIP, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, label, x + 10, cy - 9, cy + 9, if (on) WHITE else G_BB, fBody)
        if (dot) {
            val dx = x + 10 + textWidth(label, fBody) + 6
            roundRect(g, dx, cy - 3, dx + 6, cy + 3, if (on) WHITE else DOT, SoulRenderPipelines.ROUND_PILL)
        }
        region(x, cy - 9, x + w, cy + 9, action)
        return x + w
    }

    private fun setTab(id: String?) {
        if (tab == id) return
        tab = id
        addActOpen = false
        pickerOpen = false
        listSb.pos = 0f
        select(-1)
    }

    /** "+ Activity": the activities not in the strip yet. */
    private fun drawAddActivity(g: GuiGraphicsExtractor, mx: Int, my: Int, uiW: Int, uiH: Int) {
        region(0, 0, uiW, uiH) { addActOpen = false }
        val rest = ScoreboardActivities.all.filter { it.id !in stripActs }
        val px = addActRect[0]
        val py = addActRect[3] + 6
        val pw = 180
        val ph = 8 + 18 + rest.size * 20 + 8
        popupFrame(g, px, py, px + pw, py + ph)
        region(px, py, px + pw, py + ph) {}
        groupLabel(g, "Own setup for…", px + 8, py + 8)
        var y = py + 8 + 18
        for (a in rest) {
            val hover = mx in px + 4..px + pw - 4 && my in y..y + 20
            if (hover) roundRect(g, px + 4, y, px + pw - 4, y + 20, CHIP_HOVER, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, a.name, px + 10, y, y + 20, Theme.TEXT, fBody)
            region(px + 4, y, px + pw - 4, y + 20) {
                sessionTabs += a.id
                addActOpen = false
                setTab(a.id)
            }
            y += 20
        }
    }

    private fun popupFrame(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int) {
        panel(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, SECTION, Theme.ACCENT)
    }

    private fun drawNotice(g: GuiGraphicsExtractor, anchor: IntArray, now: Long) {
        val text = notice ?: return
        val age = now - noticeAt
        if (age > NOTICE_MS) {
            notice = null
            return
        }
        val fade = if (age < NOTICE_MS * 3 / 4) 1f else 1f - (age - NOTICE_MS * 3 / 4).toFloat() / (NOTICE_MS / 4)
        val a = (fade * 255).toInt().coerceIn(0, 255)
        val w = textWidth(text, fBody) + 20
        val x = anchor[0]
        val y = anchor[3] + 6
        roundRect(g, x, y, x + w, y + 20, (a shl 24) or 0x2A2A2A, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, text, x + 10, y, y + 20, (a shl 24) or 0xDDDDDD, fBody)
    }

    // ----------------------------------------------------------------------------------------- banners

    private fun banner(g: GuiGraphicsExtractor, x0: Int, y: Int, x1: Int, stripe: Int, tint: Int, parts: List<Pair<String, Boolean>>) {
        roundRect(g, x0, y, x1, y + BANNER_H, tint, SoulRenderPipelines.ROUND_SMALL)
        slice(g, x0, y, x1, y + BANNER_H, SoulPanel.SMALL, x0, y, x0 + 3, y + BANNER_H, stripe)
        var x = x0 + 10
        for ((text, bold) in parts) {
            val font = if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular
            drawTextV(g, text, x, y, y + BANNER_H, if (bold) WHITE else G_CC, fBody, font)
            x += textWidth(text, fBody, font)
        }
    }

    private fun bannerButton(g: GuiGraphicsExtractor, mx: Int, my: Int, x1: Int, y: Int, label: String, accent: Boolean, action: () -> Unit): Int {
        val w = textWidth(label, fBody) + 24
        val cy = y + BANNER_H / 2
        drawButton(g, mx, my, x1 - w, cy - 9, x1, cy + 9, label, accent, action)
        return x1 - w
    }

    private fun drawImportBanner(g: GuiGraphicsExtractor, mx: Int, my: Int, x0: Int, x1: Int, y: Int) {
        banner(
            g, x0, y, x1, GREEN, 0x1F22C55E,
            listOf(
                "Custom Scoreboard setup found" to true,
                " · copy its lines, order, events, labels and background? Later: Look › Advanced › Import." to false,
            ),
        )
        val bx = bannerButton(g, mx, my, x1 - 10, y, "Not now", false) {
            importBanner = false
            ScoreboardImport.markOffered()
        }
        bannerButton(g, mx, my, bx - 6, y, "Import it", true) { runImport() }
    }

    private fun runImport() {
        importBanner = false
        val result = ScoreboardImport.apply()
        tab = null
        select(-1)
        if (result == null) {
            showAlert("Nothing imported", "Custom Scoreboard's setup could not be read.")
            return
        }
        val skipped = if (result.skipped.isEmpty()) "" else " Not brought over: ${result.skipped.joinToString(", ")}."
        val brought = result.imported.joinToString("; ")
        showAlert("Imported", "Custom Scoreboard's setup is now your Everywhere setup: $brought.$skipped", success = true)
    }

    private fun drawScopeBanner(g: GuiGraphicsExtractor, mx: Int, my: Int, c: ScoreboardConfig, x0: Int, x1: Int, y: Int) {
        val id = tab
        if (id == null) {
            banner(g, x0, y, x1, Theme.ACCENT, 0x243B82F6, listOf("Editing " to false, "Everywhere" to true, " · every island, and every activity without its own setup" to false))
            return
        }
        val a = ScoreboardActivities.of(id)
        val name = a?.name ?: id
        if (id in c.activities) {
            banner(g, x0, y, x1, PURPLE, 0x29A855F7, listOf("Editing " to false, name to true, " only · Everywhere stays as it is" to false))
            bannerButton(g, mx, my, x1 - 10, y, "Same as Everywhere", false) {
                showConfirm("Use Everywhere for $name?", "$name's own lines are removed and it shows your Everywhere setup again.", "Use Everywhere") {
                    select(-1)
                    sb { copy(activities = activities - id) }
                }
            }
        } else {
            val place = a?.previewPlace ?: name
            banner(g, x0, y, x1, PURPLE, 0x29A855F7, listOf(name to true, " uses your Everywhere setup. The preview shows it in $place." to false))
            bannerButton(g, mx, my, x1 - 10, y, "Customize for $name", true) {
                sb { copy(activities = activities + (id to ScoreboardActivities.startingSetup(this, id))) }
            }
        }
    }

    // -------------------------------------------------------------------------------------------- list

    private fun drawList(g: GuiGraphicsExtractor, mx: Int, my: Int, c: ScoreboardConfig, entries: List<ScoreboardEntry>, here: Set<Int>, canEdit: Boolean, x0: Int, y0: Int, x1: Int, y1: Int) {
        val hy = y0 + 9
        drawTextV(g, "Lines · ${rows.size}", x0, hy - 9, hy + 9, G_AA, fBody)
        val addW = textWidth("+ Add line", fBody) + 24
        if (canEdit) {
            if (pickerOpen) {
                roundRect(g, x1 - addW, hy - 9, x1, hy + 9, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
                SoulText.drawCenteredOn(g, "+ Add line", x1 - addW / 2, hy, fBody, Theme.TEXT, forceCustom = true, scissor = currentClip)
                region(x1 - addW, hy - 9, x1, hy + 9) { pickerOpen = false }
            } else {
                drawButton(g, mx, my, x1 - addW, hy - 9, x1, hy + 9, "+ Add line", accent = true) { openPicker() }
            }
        } else {
            roundRect(g, x1 - addW, hy - 9, x1, hy + 9, Theme.ACCENT_DIM, SoulRenderPipelines.ROUND_SMALL)
            SoulText.drawCenteredOn(g, "+ Add line", x1 - addW / 2, hy, fBody, 0x66FFFFFF, forceCustom = true)
        }

        val ly0 = y0 + 18 + 6
        listRect[0] = x0; listRect[1] = ly0; listRect[2] = x1; listRect[3] = y1
        listSb.view = y1 - ly0
        listSb.content = rows.size * ROW_H
        if (revealSelected) {
            revealSelected = false
            val r = rows.indexOf(selected)
            if (r >= 0) listSb.pos = (r * ROW_H - listSb.view / 2f + ROW_H / 2f)
        }
        listSb.clamp()
        val start = regions.size
        beginClip(g, x0, ly0, x1, y1)
        val activityList = tab != null
        val listX1 = x1 - 4
        for ((r, idx) in rows.withIndex()) {
            val ry = ly0 + r * ROW_H - listSb.pos.roundToInt()
            if (ry + ROW_H < ly0 || ry > y1) continue
            drawRow(g, mx, my, entries, idx, here, canEdit, activityList, x0, ry, listX1)
        }
        // Drop marker while dragging.
        if (dragging && dropSlot >= 0) {
            val beforeRow = if (dropSlot < nonPinned.size) rows.indexOf(nonPinned[dropSlot]) else rows.indexOf(nonPinned.last()) + 1
            val ry = ly0 + beforeRow * ROW_H - listSb.pos.roundToInt()
            g.fill(x0 + 2, ry - 1, listX1 - 2, ry + 1, Theme.ACCENT)
        }
        endClip(g)
        clipRegions(start, x0, ly0, x1, y1)
        drawScrollbar(g, mx, my, listSb, x0, ly0, x1, y1)
    }

    private fun drawRow(g: GuiGraphicsExtractor, mx: Int, my: Int, entries: List<ScoreboardEntry>, idx: Int, here: Set<Int>, canEdit: Boolean, activityList: Boolean, x0: Int, y: Int, x1: Int) {
        val e = entries[idx]
        val type = ScoreboardCatalog.type(e.type) ?: return
        val pinned = type.pinned != LineType.PIN_NONE
        val sep = type === EmptyLine
        val sel = canEdit && idx == selected
        val hover = canEdit && !dragging && mx in x0..x1 && my in y..y + ROW_H - 1 && my in listRect[1]..listRect[3]
        val isHere = pinned || sep || idx in here
        var alpha = 255
        if (e.hidden) alpha = alpha * 4 / 10
        if (!canEdit) alpha /= 2
        if (dragging && idx == pressed) alpha = alpha / 2
        val fade = { argb: Int -> (((argb ushr 24) * alpha / 255) shl 24) or (argb and 0xFFFFFF) }
        if (sel) {
            roundRect(g, x0, y, x1, y + ROW_H, if (activityList) PURPLE else Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
        } else if (hover) {
            roundRect(g, x0, y, x1, y + ROW_H, ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
        }
        if (activityList && canEdit && !sel) g.fill(x0, y + 1, x0 + 2, y + ROW_H - 1, 0x99A855F7.toInt())
        val cy = y + ROW_H / 2
        // Handle: ⤒ for the pinned rows, a 2×3 grip for the rest.
        val hx = x0 + 6
        if (pinned) {
            val col = fade(if (sel) WHITE else AMBER)
            g.fill(hx, cy - 4, hx + 7, cy - 3, col)
            g.fill(hx + 3, cy - 2, hx + 4, cy + 4, col)
            g.fill(hx + 2, cy - 1, hx + 5, cy, col)
            g.fill(hx + 1, cy, hx + 6, cy + 1, col)
        } else {
            val col = fade(if (sel) WHITE else G_55)
            for (gy in 0..2) for (gx in 0..1) g.fill(hx + 1 + gx * 3, cy - 4 + gy * 3, hx + 3 + gx * 3, cy - 2 + gy * 3, col)
        }
        // Name (or a custom row's own text), its tag, the visibility dot.
        val nx = hx + 8 + 6
        val eyeX1 = x1 - 6
        val eyeX0 = eyeX1 - 14
        // A custom row shows its own text, untagged (the mock's list).
        val tag = type.tag.takeIf { type !== CustomTextLine }
        val tagW = tag?.let { tagWidth(it) + 6 } ?: 0
        val nameMax = (if (pinned) x1 - 6 else eyeX0 - 6) - tagW - nx
        val nameColor = fade(if (sel) WHITE else if (pinned) AMBER else if (sep) G_55 else if (!isHere) G_66 else G_DD)
        val drawn =
            if (type === CustomTextLine) {
                val text = OptionValues(e.options, type.options).string(CustomTextLine.TEXT).replace(Regex("\\{(\\w+)}"), "§7{$1}")
                LegacyDraw.draw(g, text, nx, cy, fBody, nameColor, currentClip, alpha, nameMax)
            } else if (!isHere && !sel) {
                italic(g, nx, cy) { drawEllipsized(g, type.name, 0, 0, nameMax, nameColor, fBody) }
            } else {
                drawEllipsized(g, type.name, nx, cy, nameMax, nameColor, if (sep) fCaption else fBody)
            }
        tag?.let { drawTag(g, nx + drawn + 6, cy, it, alpha) }
        if (!pinned) {
            val ecx = (eyeX0 + eyeX1) / 2
            val eyeColor = fade(if (sel) WHITE else if (e.hidden) Theme.TEXT_DIM else Theme.ACCENT)
            roundRect(g, ecx - 4, cy - 4, ecx + 4, cy + 4, eyeColor, SoulRenderPipelines.STROKE_1_PILL)
            if (!e.hidden) roundRect(g, ecx - 2, cy - 2, ecx + 2, cy + 2, eyeColor, SoulRenderPipelines.ROUND_PILL)
        }
        if (!canEdit) return
        region(x0, y, x1, y + ROW_H - 1) { pressRow(idx, pinned) }
        if (!pinned) region(eyeX0 - 2, y, eyeX1 + 2, y + ROW_H - 1) { updateEntry(idx) { it.copy(hidden = !it.hidden) } }
    }

    /** Draw [text] cut to [maxW] with its cap band on [cy]; returns its width. */
    private fun drawEllipsized(g: GuiGraphicsExtractor, text: String, x: Int, cy: Int, maxW: Int, color: Int, size: Float): Int {
        val shown = if (textWidth(text, size) > maxW) ellipsize(text, maxW, size) else text
        drawTextV(g, shown, x, cy - 9, cy + 9, color, size)
        return textWidth(shown, size)
    }

    /** Run [block] slanted like italic text about ([x], [cy]) (the MSDF fonts have no italic); returns its result. */
    private fun italic(g: GuiGraphicsExtractor, x: Int, cy: Int, block: () -> Int): Int {
        g.pose().pushMatrix()
        g.pose().translate(x.toFloat(), cy.toFloat())
        g.pose().mul(Matrix3x2f(1f, 0f, -0.2f, 1f, 0f, 0f))
        val w = block()
        g.pose().popMatrix()
        return w
    }

    private fun tagWidth(tag: String): Int = textWidth(tag, f9) + 8

    /** The picker/list tag: `Soul` white on violet, `tab list` gray. */
    private fun drawTag(g: GuiGraphicsExtractor, x: Int, cy: Int, tag: String, alpha: Int = 255) {
        val soul = tag == "Soul"
        val w = tagWidth(tag)
        val bg = if (soul) SOUL_TAG else 0xFF333333.toInt()
        val fg = if (soul) WHITE else G_AA
        val a = { c: Int -> (((c ushr 24) * alpha / 255) shl 24) or (c and 0xFFFFFF) }
        roundRect(g, x, cy - 6, x + w, cy + 6, a(bg), SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, tag, x + 4, cy - 6, cy + 6, a(fg), f9)
    }

    private fun pressRow(idx: Int, pinned: Boolean) {
        commit()
        deselectOnRelease = idx == selected
        select(idx)
        if (!pinned) {
            pressed = idx
            pressY = lastMouseY.toDouble()
            dragging = false
            dropSlot = -1
        } else {
            pressed = -1
            if (deselectOnRelease) select(-1)
            deselectOnRelease = false
        }
    }

    /** The slot among the movable rows the pointer is over: 0 = before the first, size = after the last. */
    private fun slotAt(y: Int): Int {
        val ly0 = listRect[1]
        for ((s, idx) in nonPinned.withIndex()) {
            val r = rows.indexOf(idx)
            val mid = ly0 + r * ROW_H - listSb.pos.roundToInt() + ROW_H / 2
            if (y < mid) return s
        }
        return nonPinned.size
    }

    private fun drop(from: Int, slot: Int) {
        if (nonPinned.isEmpty()) return
        val anchor = if (slot < nonPinned.size) nonPinned[slot] else nonPinned.last() + 1
        if (anchor == from || anchor == from + 1) return
        val at = if (from < anchor) anchor - 1 else anchor
        updateEntries { list ->
            if (from !in list.indices) return@updateEntries list
            val out = list.toMutableList()
            val moving = out.removeAt(from)
            out.add(at.coerceIn(0, out.size), moving)
            out
        }
        selected = at
    }

    // ---------------------------------------------------------------------------------------- picker

    private fun openPicker() {
        commit()
        pickerOpen = true
        search.set("")
        search.focused = true
        fields.values.forEach { it.focused = false }
        pickerSb.pos = 0f
    }

    private fun drawPicker(g: GuiGraphicsExtractor, mx: Int, my: Int, entries: List<ScoreboardEntry>, x0: Int, y0: Int, x1: Int, y1: Int) {
        popupFrame(g, x0, y0, x1, y1)
        region(x0, y0, x1, y1) {}
        val all = ScoreboardCatalog.lines.filter { it.inPicker }
        val q = search.text.trim().lowercase()
        val hits = all.filter { q.isEmpty() || "${it.name} ${it.description}".lowercase().contains(q) }
        val px0 = x0 + 8
        val px1 = x1 - 8
        val ty = y0 + 8
        // Top: search, group jumps, close.
        val fw = min(300, (px1 - px0) / 2)
        drawTextField(g, px0, ty, px0 + fw, ty + 18, search, "Search ${all.size} lines…", onFocus = { fields.values.forEach { it.focused = false } })
        search.onChange = { pickerSb.pos = 0f }
        val closeX = px1 - 10
        val closeHover = mx in closeX - 4..px1 && my in ty..ty + 18
        drawClose(g, closeX - 1, ty + 9, if (closeHover) WHITE else Theme.TEXT_DIM)
        region(closeX - 4, ty, px1, ty + 18) { pickerOpen = false }
        val groupsHit = ScoreboardCatalog.groups.filter { gname -> hits.any { it.group == gname } }

        // Layout: group headers and rows, with their y inside the body.
        val bodyTop = ty + 18 + 6
        val nameX = px0 + 6
        val descW = min(250, (px1 - px0) / 2)
        val exX = nameX + descW + 12
        class Item(val type: LineType?, val group: String, val y: Int, val h: Int, val desc: List<String>)
        val items = ArrayList<Item>()
        var cy = 0
        val headerY = HashMap<String, Int>()
        for (gname in groupsHit) {
            val inGroup = hits.filter { it.group == gname }
            headerY[gname] = cy
            items += Item(null, gname, cy, 24, emptyList())
            cy += 24
            for (t in inGroup) {
                val desc = wrapText(t.description, descW, fCaption)
                val h = 8 + 14 + desc.size * 12
                items += Item(t, gname, cy, h, desc)
                cy += h
            }
        }

        // Group jumps after the field.
        var jx = px0 + fw + 8
        for (gname in groupsHit) {
            val w = textWidth(gname, fCaption) + 10
            if (jx + w > closeX - 8) break
            val hover = mx in jx..jx + w && my in ty + 2..ty + 16
            roundRect(g, jx, ty + 2, jx + w, ty + 16, if (hover) Theme.ACCENT else CHIP, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, gname, jx + 5, ty + 2, ty + 16, if (hover) WHITE else G_BB, fCaption)
            val target = headerY[gname] ?: 0
            region(jx, ty + 2, jx + w, ty + 16) { pickerSb.pos = target.toFloat() }
            jx += w + 3
        }

        pickerSb.view = y1 - 8 - bodyTop
        pickerSb.content = cy
        pickerSb.clamp()
        val start = regions.size
        beginClip(g, x0, bodyTop, x1, y1 - 8)
        val off = bodyTop - pickerSb.pos.roundToInt()
        if (items.isEmpty()) drawTextV(g, "No line matches.", px0, bodyTop, bodyTop + 20, Theme.TEXT_DIM, fBody)
        val present = entries.map { it.type }.toSet()
        for (it in items) {
            val iy = off + it.y
            if (iy + it.h < bodyTop || iy > y1) continue
            val t = it.type
            if (t == null) {
                val count = hits.count { h -> h.group == it.group }
                groupLabel(g, it.group.uppercase(), px0, iy + 10, " $count")
                continue
            }
            val hover = mx in px0..px1 && my in iy..iy + it.h - 1 && my in bodyTop..y1 - 8
            if (hover) roundRect(g, px0, iy, px1, iy + it.h, ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
            val added = !t.repeatable && t.id in present
            val nameW = textWidth(t.name, fBody)
            drawTextV(g, t.name, nameX, iy + 4, iy + 18, if (added) Theme.TEXT_DIM else Theme.TEXT, fBody)
            var tx = nameX + nameW
            t.tag?.let { tag ->
                drawTag(g, tx + 6, iy + 11, tag)
                tx += 6 + tagWidth(tag)
            }
            if (added) drawTextV(g, "in your list", tx + 8, iy + 4, iy + 18, G_66, fCaption)
            for ((i, line) in it.desc.withIndex()) drawTextV(g, line, nameX, iy + 18 + i * 12, iy + 30 + i * 12, G_77, fCaption)
            val ex = t.example.firstOrNull().orEmpty()
            val exCy = iy + it.h / 2
            if (LegacyText.strip(ex).isBlank()) {
                italic(g, exX, exCy) { drawEllipsized(g, "gap", 0, 0, 60, G_55, fBody) }
            } else {
                LegacyDraw.draw(g, ex, exX, exCy, fBody, WHITE, currentClip, maxW = px1 - 6 - exX)
            }
            region(px0, iy, px1, iy + it.h - 1) { add(t) }
        }
        endClip(g)
        clipRegions(start, x0, bodyTop, x1, y1 - 8)
        drawScrollbar(g, mx, my, pickerSb, x0, bodyTop, x1 - 2, y1 - 8)
    }

    /** "+ Add line": below the selected row (else before "New lines from Hypixel", else before the Footer); selects it. */
    private fun add(type: LineType) {
        val entries = entries(cfg.scoreboard)
        if (!type.repeatable) {
            val existing = entries.indexOfFirst { it.type == type.id }
            if (existing >= 0) {
                pickerOpen = false
                select(existing)
                revealSelected = true
                return
            }
        }
        val selType = entries.getOrNull(selected)?.let { ScoreboardCatalog.type(it.type) }
        val at =
            if (selType != null && selType.pinned == LineType.PIN_BOTTOM) {
                selected
            } else if (selType != null) {
                selected + 1
            } else {
                val extra = entries.indexOfFirst { it.type == NewLinesLine.id }
                val footer = entries.indexOfFirst { it.type == FooterLine.id }
                if (extra >= 0) extra else if (footer >= 0) footer else entries.size
            }
        updateEntries { list -> list.toMutableList().also { it.add(at.coerceIn(0, list.size), type.newEntry()) } }
        pickerOpen = false
        select(at)
        revealSelected = true
    }

    private fun drawClose(g: GuiGraphicsExtractor, cx: Int, cy: Int, color: Int) {
        for (i in -3..3) {
            g.fill(cx + i, cy + i, cx + i + 1, cy + i + 1, color)
            g.fill(cx + i, cy - i, cx + i + 1, cy - i + 1, color)
        }
    }

    private fun groupLabel(g: GuiGraphicsExtractor, text: String, x: Int, cy: Int, count: String? = null) {
        val t = text.uppercase()
        drawTextV(g, t, x, cy - 6, cy + 6, Theme.TEXT_DIM, fCaption)
        if (count != null) drawTextV(g, count, x + textWidth(t, fCaption), cy - 6, cy + 6, G_55, fCaption)
    }

    // ----------------------------------------------------------------------------------------- preview

    private fun drawPreview(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        c: ScoreboardConfig,
        entries: List<ScoreboardEntry>,
        preview: ScoreboardPreview.Result,
        canEdit: Boolean,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val ymid = y0 + (y1 - y0) * 48 / 100
        g.fillGradient(x0, y0, x1, ymid, 0xFF6F9FD8.toInt(), 0xFFA9C8EE.toInt())
        g.fillGradient(x0, ymid, x1, y1, 0xFF5D8A3C.toInt(), 0xFF3F6A2A.toInt())
        val look = c.look
        val lines = preview.lines
        val (w, h) = ScoreboardHud.measure(lines, look)
        val fit = min((y1 - y0 - 64).toFloat() / h, (x1 - x0 - 24).toFloat() / w)
        val zoom = (floor(fit * 10f) / 10f).coerceIn(0.5f, 1.5f)
        val bw = w * zoom
        val bh = h * zoom
        val bx = (x0 + x1) / 2f - bw / 2f
        val by = (y0 + y1) / 2f - bh / 2f
        g.pose().pushMatrix()
        g.pose().translate(bx, by)
        g.pose().scale(zoom, zoom)
        ScoreboardHud.draw(g, lines, look)
        g.pose().popMatrix()

        // Hover outline and name; the selected line's rows marked; a click selects (again: deselects).
        if (canEdit && !c.look.hypixelLinesOnly) {
            val pad = look.padding.coerceIn(0, 20)
            val lx0 = (bx + pad * zoom).roundToInt()
            val lx1 = (bx + (w - pad) * zoom).roundToInt()
            for ((i, line) in lines.withIndex()) {
                if (line.entry < 0) continue
                val ry0 = (by + ScoreboardHud.rowTop(i, look) * zoom).roundToInt()
                val ry1 = (by + (ScoreboardHud.rowTop(i, look) + ScoreboardHud.rowHeight()) * zoom).roundToInt()
                val sel = line.entry == selected
                val hover = mx in lx0..lx1 && my in ry0 until ry1
                if (sel || hover) {
                    g.fill(lx0, ry0, lx1, ry1, if (sel) 0x473B82F6 else 0x1F3B82F6)
                    outline(g, lx0, ry0, lx1, ry1, if (sel) Theme.ACCENT else 0xB33B82F6.toInt())
                }
                if (hover) {
                    val name = entries.getOrNull(line.entry)?.let { ScoreboardCatalog.type(it.type)?.name } ?: ""
                    tooltip(lx0, ry0, lx1, ry1, listOf("§f$name", "§7Click to edit"))
                }
                val entry = line.entry
                region(lx0, ry0, lx1, ry1 - 1) {
                    commit()
                    if (selected == entry) {
                        select(-1)
                    } else {
                        select(entry)
                        revealSelected = true
                    }
                }
            }
        }

        // Corner label and hint.
        val label = "Preview · ${preview.label} · ${(zoom * 100).roundToInt()}%"
        val lw = textWidth(label, fBody) + 16
        roundRect(g, x0 + 8, y0 + 8, x0 + 8 + lw, y0 + 8 + 18, 0xD9141414.toInt(), SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, label, x0 + 16, y0 + 8, y0 + 26, G_CC, fBody)
        val hint = if (canEdit) "Click a line to edit it" else "Customize for ${tab?.let { ScoreboardActivities.of(it)?.name } ?: tab} to edit its lines"
        val hw = textWidth(hint, fCaption) + 16
        val hx = (x0 + x1) / 2 - hw / 2
        roundRect(g, hx, y1 - 8 - 16, hx + hw, y1 - 8, 0xB3141414.toInt(), SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, hint, hx + 8, y1 - 24, y1 - 8, G_AA, fCaption)
    }

    private fun outline(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.fill(x0, y0, x1, y0 + 1, color)
        g.fill(x0, y1 - 1, x1, y1, color)
        g.fill(x0, y0 + 1, x0 + 1, y1 - 1, color)
        g.fill(x1 - 1, y0 + 1, x1, y1 - 1, color)
    }

    // --------------------------------------------------------------------------------------- inspector

    /** A layout cursor through the inspector's column. */
    private class Col(val x0: Int, val x1: Int, var y: Int)

    private fun drawInspector(g: GuiGraphicsExtractor, mx: Int, my: Int, c: ScoreboardConfig, entries: List<ScoreboardEntry>, here: Set<Int>, canEdit: Boolean, x0: Int, y0: Int, x1: Int, y1: Int) {
        roundRect(g, x0, y0, x1, y1, INSPECTOR, SoulRenderPipelines.ROUND_MEDIUM)
        val start = regions.size
        val wheelStart = wheelSteps.size
        beginClip(g, x0, y0, x1, y1)
        val col = Col(x0 + 12, x1 - 12, y0 + 10 - inspectorSb.pos.roundToInt())
        val top = col.y
        val e = if (canEdit) entries.getOrNull(selected) else null
        val type = e?.let { ScoreboardCatalog.type(it.type) }
        if (e != null && type != null) {
            lineInspector(g, mx, my, col, c, e, type, selected in here)
        } else {
            lookInspector(g, mx, my, col, c)
        }
        endClip(g)
        clipRegions(start, x0, y0, x1, y1)
        for (i in wheelSteps.size - 1 downTo wheelStart) {
            val r = wheelSteps[i].first
            if (r[3] < y0 || r[1] > y1) wheelSteps.removeAt(i)
        }
        inspectorSb.view = y1 - y0
        inspectorSb.content = col.y - top + 20
        inspectorSb.clamp()
        drawScrollbar(g, mx, my, inspectorSb, x0, y0, x1, y1)
    }

    private fun header(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, title: String, tag: String?, sub: String, close: Boolean) {
        val ty = col.y
        drawTextV(g, title, col.x0, ty, ty + 18, WHITE, f14, MsdfFonts.menuSemibold)
        tag?.let { drawTag(g, col.x0 + textWidth(title, f14, MsdfFonts.menuSemibold) + 6, ty + 9, it) }
        if (close) {
            val cx = col.x1 - 6
            val hover = mx in cx - 6..col.x1 && my in ty..ty + 18
            drawClose(g, cx, ty + 9, if (hover) WHITE else Theme.TEXT_DIM)
            region(cx - 6, ty, col.x1, ty + 18) { select(-1) }
        }
        var y = ty + 18 + 3
        for (line in wrapText(sub, col.x1 - col.x0, fBody)) {
            drawTextV(g, line, col.x0, y, y + 13, Theme.TEXT_DIM, fBody)
            y += 13
        }
        y += 8
        g.fill(col.x0, y, col.x1, y + 1, ROW_HOVER)
        col.y = y + 1 + 6
    }

    private fun sectionLabel(g: GuiGraphicsExtractor, col: Col, text: String) {
        col.y += 10
        groupLabel(g, text, col.x0, col.y + 6)
        col.y += 12 + 4
    }

    private fun note(g: GuiGraphicsExtractor, col: Col, text: String) {
        col.y += 6
        for (line in wrapText(text, col.x1 - col.x0, fBody)) {
            drawTextV(g, line, col.x0, col.y, col.y + 13, Theme.TEXT_DIM, fBody)
            col.y += 13
        }
        col.y += 6
    }

    /**
     * One option row: label (and a hint under it) on the left, a control [controlW] wide on the right;
     * [control] draws it given its left edge and center line.
     */
    private fun row(g: GuiGraphicsExtractor, col: Col, label: String, hint: String?, controlW: Int, control: (Int, Int) -> Unit) {
        val labelW = (col.x1 - col.x0 - controlW - 8).coerceAtLeast(40)
        val labelLines = wrapText(label, labelW, fBody)
        val hintLines = hint?.let { wrapText(it, labelW, fCaption) }.orEmpty()
        val blockH = labelLines.size * 14 + hintLines.size * 11
        val h = max(OPT_ROW_H, blockH + 8)
        val cy = col.y + h / 2
        var ly = cy - blockH / 2
        for (l in labelLines) {
            drawTextV(g, l, col.x0, ly, ly + 14, Theme.TEXT, fBody)
            ly += 14
        }
        for (l in hintLines) {
            drawTextV(g, l, col.x0, ly, ly + 11, Theme.TEXT_DIM, fCaption)
            ly += 11
        }
        control(col.x1 - controlW, cy)
        col.y += h
    }

    private fun switchRow(g: GuiGraphicsExtractor, col: Col, label: String, hint: String?, on: Boolean, set: (Boolean) -> Unit) =
        row(g, col, label, hint, toggleW) { x, cy -> drawToggle(g, x, cy - toggleH / 2, on) { set(!on) } }

    private fun segWidth(labels: List<String>): Int = labels.sumOf { textWidth(it, fBody) + SEG_PAD * 2 } + (labels.size - 1) * SEG_GAP

    private fun segRow(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, label: String, hint: String?, opt: CycleOption) {
        val w = segWidth(opt.labels)
        row(g, col, label, hint, w) { x, cy -> drawSegmented(g, mx, my, x, cy - 9, cy + 9, opt) }
    }

    private fun dropdownRow(g: GuiGraphicsExtractor, col: Col, label: String, hint: String?, key: String, labels: List<String>, current: Int, width: Int, set: (Int) -> Unit) {
        row(g, col, label, hint, width) { x, cy ->
            val tok = token(key)
            drawDropdownButton(g, x, cy - 9, x + width, cy + 9, ellipsize(labels.getOrElse(current) { "" }, width - 20, fBody), isDropdownOpen(tok))
            region(x, cy - 9, x + width, cy + 9) {
                if (isDropdownOpen(tok)) closeDropdown() else openDropdownAt(tok, x, cy - 9, x + width, cy + 9, labels, current, set)
            }
        }
    }

    private fun sliderRow(g: GuiGraphicsExtractor, col: Col, label: String, hint: String?, opt: SliderOption, trackW: Int) {
        val readout = textWidth("00.0", fBody) + 4
        row(g, col, label, hint, readout + 6 + trackW + 6) { x, cy -> drawSlider(g, x + readout + 6 + 6, cy, x + readout + 6 + 6 + trackW, opt) }
    }

    /** A number with up/down arrows; the wheel steps it too. */
    private fun stepper(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, cy: Int, value: Int, min: Int, max: Int, unit: String?, set: (Int) -> Unit) {
        val w = 48
        val y0 = cy - 9
        val y1 = cy + 9
        panel(g, x, y0, x + w, y1, Theme.FIELD_BG, Theme.FIELD_BORDER, SoulPanel.SMALL)
        val text = value.toString()
        drawTextV(g, text, x + (w - 12) / 2 - textWidth(text, fBody) / 2 + 1, y0, y1, Theme.TEXT, fBody)
        val ax = x + w - 12
        val over = mx in x..x + w && my in y0..y1
        val arrow = if (over) Theme.TEXT else Theme.TEXT_DIM
        for (i in 0..2) g.fill(ax + 5 - i, y0 + 4 + i, ax + 6 + i, y0 + 5 + i, arrow)
        for (i in 0..2) g.fill(ax + 5 - i, y1 - 5 - i, ax + 6 + i, y1 - 4 - i, arrow)
        region(ax, y0, x + w, cy) { set((value + 1).coerceIn(min, max)) }
        region(ax, cy, x + w, y1) { set((value - 1).coerceIn(min, max)) }
        if (unit != null) drawTextV(g, unit, x + w + 4, y0, y1, Theme.TEXT_DIM, fBody)
        wheelSteps += intArrayOf(x, y0, x + w, y1) to { d: Int -> set((value + d).coerceIn(min, max)) }
    }

    private fun stepperRow(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, label: String, hint: String?, value: Int, min: Int, max: Int, unit: String?, set: (Int) -> Unit) {
        val w = 48 + if (unit != null) 4 + textWidth(unit, fBody) else 0
        row(g, col, label, hint, w) { x, cy -> stepper(g, mx, my, x, cy, value, min, max, unit, set) }
    }

    /**
     * A color part's control: swatch (Soul's picker on click) + its value or [noneLabel] + a reset back to it.
     * [chromaPart] = the Look's chroma covers it: a chroma swatch and "Chroma · set in Look", no picker.
     */
    private fun swatchControl(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x: Int,
        cy: Int,
        value: ChromaColor?,
        default: Int?,
        noneLabel: String,
        chromaPart: Boolean,
        open: (IntArray) -> Unit,
        reset: (() -> Unit)?,
    ) {
        val sx0 = x
        val sy0 = cy - 8
        val sx1 = x + 26
        val sy1 = cy + 8
        if (chromaPart) {
            drawSwatch(g, sx0, sy0, sx1, sy1, Chroma.paint(255), false)
            drawTextV(g, "Chroma · set in Look", sx1 + 6, sy0, sy1, 0xFF999999.toInt(), fBody)
            return
        }
        if (value != null) {
            drawSwatch(g, sx0, sy0, sx1, sy1, value.effective(), false)
        } else if (default != null) {
            drawSwatch(g, sx0, sy0, sx1, sy1, 0xFF000000.toInt() or default, false)
        } else {
            panel(g, sx0 - 1, sy0 - 1, sx1 + 1, sy1 + 1, 0xFF1A1A1A.toInt(), 0xFF3A3A3A.toInt(), SoulPanel.SMALL)
            var sx = sx0 + 1
            while (sx < sx1 - 1) {
                g.fill(sx, sy0 + 1, min(sx + 3, sx1 - 1), sy1 - 1, 0xFF2A2A2A.toInt())
                sx += 6
            }
        }
        region(sx0, sy0, sx1, sy1) { open(intArrayOf(sx0, sy0, sx1, sy1)) }
        val text = if (value == null) noneLabel else if (value.chroma) "Chroma" else String.format("#%06X", value.argb and 0xFFFFFF)
        drawTextV(g, text, sx1 + 6, sy0, sy1, 0xFF999999.toInt(), fBody)
        if (value != null && reset != null) {
            val rx = sx1 + 6 + max(58, textWidth(text, fBody)) + 4
            val hover = mx in rx - 2..rx + 12 && my in sy0..sy1
            drawTextV(g, "↺", rx, sy0, sy1, if (hover) WHITE else Theme.TEXT_DIM, f12)
            region(rx - 2, sy0, rx + 12, sy1) { reset() }
        }
    }

    private val swatchW: Int get() = 26 + 6 + 58 + 4 + 14

    /** A border color row: [get] reads it from the Look, [set] writes it; [chroma] = Look › Chroma on › Border. */
    private fun borderColorRow(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        col: Col,
        label: String,
        chroma: Boolean,
        get: (ScoreboardLook) -> ChromaColor,
        set: ScoreboardLook.(ChromaColor) -> ScoreboardLook,
    ) {
        val d = get(ScoreboardLook())
        val current = get(cfg.scoreboard.look)
        row(g, col, label, null, swatchW) { x, cy ->
            swatchControl(g, mx, my, x, cy, current.takeIf { it != d }, d.argb and 0xFFFFFF, String.format("#%06X", d.argb and 0xFFFFFF), chroma, open = { r ->
                openColorPicker(ColorOption("Border $label", null, { get(cfg.scoreboard.look) }, { v -> look { set(v) } }, d), r[0], r[1], r[2], r[3], MC_COLORS)
            }, reset = { look { set(d) } })
        }
    }

    // ---- the selected line

    private fun lineInspector(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, c: ScoreboardConfig, e: ScoreboardEntry, type: LineType, isHere: Boolean) {
        val idx = selected
        val pinned = type.pinned != LineType.PIN_NONE
        val sub =
            if (pinned) {
                type.description
            } else if (type.where == null) {
                "${type.description} Shows everywhere it has a value."
            } else {
                "${type.description} Where: ${type.where}${if (isHere) "" else " (not here now)"}."
            }
        header(g, mx, my, col, type.name, type.tag, sub, close = true)
        val values = OptionValues(e.options, type.options)
        val visibleOptions = type.options.filter { it.visible(values) }

        if (type === EmptyLine) {
            note(g, col, "Two gaps in a row fold into one; gaps at the top or bottom are dropped.")
        } else if (pinned) {
            // Header/Footer: "Show" first, then its colors, then the rest (the mock's order).
            visibleOptions.firstOrNull()?.let { optionRow(g, mx, my, col, idx, it, values) }
            colorRows(g, mx, my, col, c, idx, e, type, values, withHeading = false)
            visibleOptions.drop(1).forEach { optionRow(g, mx, my, col, idx, it, values) }
        } else {
            val hadColors = colorRows(g, mx, my, col, c, idx, e, type, values, withHeading = true)
            if (visibleOptions.isNotEmpty() && hadColors) sectionLabel(g, col, "Options")
            visibleOptions.forEach { optionRow(g, mx, my, col, idx, it, values) }
            if (!hadColors && visibleOptions.isEmpty()) note(g, col, "No options; it shows what Hypixel shows.")
        }

        if (!pinned) {
            col.y += 12
            g.fill(col.x0, col.y, col.x1, col.y + 1, ROW_HOVER)
            col.y += 10
            val hideLabel = if (e.hidden) "Show" else "Hide"
            val hw = textWidth(hideLabel, fBody) + 24
            drawButton(g, mx, my, col.x0, col.y, col.x0 + hw, col.y + 18, hideLabel) { updateEntry(idx) { it.copy(hidden = !it.hidden) } }
            val rw = textWidth("Remove line", fBody) + 24
            drawButton(g, mx, my, col.x0 + hw + 6, col.y, col.x0 + hw + 6 + rw, col.y + 18, "Remove line") { removeSelected() }
            col.y += 18
        }
    }

    /** The type's color swatches ("Colors" heading first when [withHeading]); returns whether any showed. */
    private fun colorRows(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, c: ScoreboardConfig, idx: Int, e: ScoreboardEntry, type: LineType, values: OptionValues, withHeading: Boolean): Boolean {
        val colors = type.colors
        val heading = colors is LineColors.HeadingLabels
        val parts = colors.parts.filter { (heading && it.key == "v") || it.visible(values) }
        if (parts.isEmpty()) return false
        if (withHeading) sectionLabel(g, col, "Colors")
        for (p in parts) {
            if (heading && p.key == "v") {
                val one = values.bool(LineColors.ONE_VALUE_KEY)
                switchRow(g, col, "One color for all values", "Off: each value keeps Hypixel's color", one) { v -> setOption(idx, LineColors.ONE_VALUE_KEY, JsonPrimitive(v)) }
                if (!one) continue
            }
            colorRow(g, mx, my, col, c, idx, e, p)
        }
        return true
    }

    private fun colorRow(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, c: ScoreboardConfig, idx: Int, e: ScoreboardEntry, p: ColorPart) {
        val chromaPart = p.chromaTarget != null && p.chromaTarget in c.look.chromaOn
        val value = e.colors[p.key]
        row(g, col, p.label, p.hint, swatchW) { x, cy ->
            swatchControl(g, mx, my, x, cy, value, p.default, p.noneLabel, chromaPart, open = { r ->
                val start = value ?: ChromaColor(0xFF000000.toInt() or (p.default ?: 0xFFFFFF))
                var current = start
                val opt = ColorOption(p.label, null, { current }, { v -> current = v; setColor(idx, p.key, v) }, start)
                openColorPicker(opt, r[0], r[1], r[2], r[3], MC_COLORS)
            }, reset = { setColor(idx, p.key, null) })
        }
    }

    private fun optionRow(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, idx: Int, opt: LineOption, values: OptionValues) {
        when (opt) {
            is LineSwitch -> switchRow(g, col, opt.label, opt.hint, values.bool(opt.key)) { v -> setOption(idx, opt.key, JsonPrimitive(v)) }
            is LineChoice -> {
                val labels = opt.choices.map { it.label }
                val current = opt.choices.indexOfFirst { it.value == values.string(opt.key) }.coerceAtLeast(0)
                val set = { i: Int -> setOption(idx, opt.key, JsonPrimitive(opt.choices[i].value)) }
                val sw = segWidth(labels)
                if (opt.segmented && sw <= col.x1 - col.x0 - 60) {
                    segRow(g, mx, my, col, opt.label, opt.hint, CycleOption(opt.label, null, { current }, set, labels, 0))
                } else {
                    val w = (labels.maxOf { textWidth(it, fBody) } + 26).coerceIn(100, 170)
                    dropdownRow(g, col, opt.label, opt.hint, "opt:$idx:${opt.key}", labels, current, w, set)
                }
            }
            is LineText -> {
                if (opt.rich) {
                    textEditor(g, mx, my, col, idx, opt, values)
                } else {
                    val w = opt.width.coerceAtMost(col.x1 - col.x0 - 60)
                    val f = field("opt:$idx:${opt.key}", values.string(opt.key)) { v -> setOption(idx, opt.key, JsonPrimitive(v)) }
                    row(g, col, opt.label, opt.hint, w) { x, cy -> drawField(g, x, cy - 9, x + w, cy + 9, f) }
                }
            }
            is LineNumber -> stepperRow(g, mx, my, col, opt.label, opt.hint, values.int(opt.key), opt.min, opt.max, opt.unit) { v -> setOption(idx, opt.key, JsonPrimitive(v)) }
            is LineChips -> if (opt.ordered) orderedList(g, mx, my, col, idx, opt, values) else chipTray(g, mx, my, col, idx, opt, values)
        }
    }

    /** A [LineChips] set: chips that toggle (the mock's "Money on one line" stats); stored in [LineChips.choices] order. */
    private fun chipTray(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, idx: Int, opt: LineChips, values: OptionValues) {
        sectionLabel(g, col, opt.label)
        val on = values.list(opt.key).toSet()
        var x = col.x0
        var y = col.y
        for (ch in opt.choices) {
            val w = textWidth(ch.label, fBody) + 16
            if (x + w > col.x1) {
                x = col.x0
                y += 22
            }
            val sel = ch.value in on
            val hover = mx in x..x + w && my in y..y + 18
            roundRect(g, x, y, x + w, y + 18, if (sel) Theme.ACCENT else if (hover) CHIP_HOVER else CHIP, SoulRenderPipelines.ROUND_PILL)
            drawTextV(g, ch.label, x + 8, y, y + 18, if (sel) WHITE else Theme.TEXT_DIM, fBody)
            region(x, y, x + w, y + 18) {
                val next = if (sel) on - ch.value else on + ch.value
                setOption(idx, opt.key, JsonArray(opt.choices.filter { it.value in next }.map { JsonPrimitive(it.value) }))
            }
            x += w + 4
        }
        opt.hint?.let { note(g, col, it) }
        col.y = y + 22 + 4
    }

    /** An ordered [LineChips] list (the events line): every choice with a switch, on ones first in priority order; drag to reorder. */
    private fun orderedList(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, idx: Int, opt: LineChips, values: OptionValues) {
        sectionLabel(g, col, "${opt.label} · drag to reorder")
        val enabled = values.list(opt.key).filter { v -> opt.choices.any { it.value == v } }.distinct()
        val order = enabled + opt.choices.map { it.value }.filter { it !in enabled }
        val top = col.y
        val d = orderDrag?.takeIf { it.entry == idx && it.key == opt.key }
        for ((i, v) in order.withIndex()) {
            val y = top + i * ORDER_ROW_H
            val label = opt.choices.first { it.value == v }.label
            val isOn = v in enabled
            val lifted = d != null && d.moving && d.from == i
            val hover = mx in col.x0..col.x1 && my in y..y + ORDER_ROW_H - 1
            if (hover && d == null) roundRect(g, col.x0 - 4, y, col.x1 + 4, y + ORDER_ROW_H, ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
            val textColor = if (lifted) G_66 else if (isOn) G_DD else Theme.TEXT_DIM
            val cy = y + ORDER_ROW_H / 2
            for (gy in 0..2) for (gx in 0..1) g.fill(col.x0 + 1 + gx * 3, cy - 4 + gy * 3, col.x0 + 3 + gx * 3, cy - 2 + gy * 3, G_55)
            drawTextV(g, label, col.x0 + 14, y, y + ORDER_ROW_H, textColor, fBody)
            drawToggle(g, col.x1 - toggleW, cy - toggleH / 2, isOn) {
                val next = if (isOn) enabled - v else enabled + v
                setOption(idx, opt.key, JsonArray(next.map { JsonPrimitive(it) }))
            }
            val rowY = y
            region(col.x0, y, col.x1 - toggleW - 4, y + ORDER_ROW_H - 1) {
                orderDrag = OrderDrag(idx, opt.key, order, enabled, i, top, lastMouseY)
            }
            if (d != null && d.moving && d.slot == i && d.slot != d.from && d.slot != d.from + 1) g.fill(col.x0, rowY - 1, col.x1, rowY + 1, Theme.ACCENT)
        }
        if (d != null && d.moving && d.slot == order.size && d.from != order.size - 1) g.fill(col.x0, top + order.size * ORDER_ROW_H - 1, col.x1, top + order.size * ORDER_ROW_H + 1, Theme.ACCENT)
        col.y = top + order.size * ORDER_ROW_H + 4
    }

    /** A drag in an ordered list: the entry and option, the order shown at press, the pressed row and where it would land. */
    private class OrderDrag(val entry: Int, val key: String, val order: List<String>, val enabled: List<String>, val from: Int, val top: Int, val pressY: Int) {
        var moving = false
        var slot = from
    }

    private var orderDrag: OrderDrag? = null

    private fun finishOrderDrag(d: OrderDrag) {
        if (!d.moving || d.slot == d.from || d.slot == d.from + 1) return
        val list = d.order.toMutableList()
        val v = list.removeAt(d.from)
        list.add(if (d.slot > d.from) d.slot - 1 else d.slot, v)
        setOption(d.entry, d.key, JsonArray(list.filter { it in d.enabled }.map { JsonPrimitive(it) }))
    }

    private fun field(key: String, value: String, onChange: (String) -> Unit): SoulTextField {
        val f = fields.getOrPut(key) { SoulTextField(value) }
        if (!f.focused && f.text != value) f.set(value)
        f.onChange = onChange
        return f
    }

    private fun drawField(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, f: SoulTextField) {
        drawTextField(g, x0, y0, x1, y1, f, onFocus = {
            fields.values.forEach { o -> if (o !== f) o.focused = false }
            search.focused = false
        })
    }

    /** Insert [s] into [f] at [at] (replacing [cut] chars), caret after it; returns where it went. */
    private fun insert(f: SoulTextField, s: String, at: Int = f.cursor, cut: Int = 0): Int {
        val t = f.text
        val p = at.coerceIn(0, t.length)
        val end = (p + cut).coerceAtMost(t.length)
        val next = t.substring(0, p) + s + t.substring(end)
        f.set(next)
        f.cursor = p + s.length
        f.anchor = f.cursor
        f.onChange(next)
        return p
    }

    /** The mock's text editor: field, rendered preview, Color… / Chroma / B / I / ↺ at the cursor, and Insert value. */
    private fun textEditor(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, idx: Int, opt: LineText, values: OptionValues) {
        val f = field("opt:$idx:${opt.key}", values.string(opt.key)) { v -> setOption(idx, opt.key, JsonPrimitive(v)) }
        val w = min(opt.width, col.x1 - col.x0)
        col.y += 6
        drawField(g, col.x0, col.y, col.x0 + w, col.y + 18, f)
        col.y += 18 + 4
        // Rendered.
        roundRect(g, col.x0, col.y, col.x0 + w, col.y + 22, CHIP, SoulRenderPipelines.ROUND_SMALL)
        LegacyDraw.draw(g, f.text.ifEmpty { " " }, col.x0 + 8, col.y + 11, f12, WHITE, currentClip, maxW = w - 16)
        col.y += 22 + 6
        // Tools.
        val ty0 = col.y
        val ty1 = col.y + 14
        val tcy = col.y + 7
        var x = col.x0
        val colorW = 10 + 4 + textWidth("Color…", fCaption) + 12
        val colorHover = mx in x..x + colorW && my in ty0..ty1
        roundRect(g, x, ty0, x + colorW, ty1, if (colorHover) CHIP_HOVER else ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x + 6, tcy - 5, x + 16, tcy + 5, Chroma.paint(255), SoulRenderPipelines.ROUND_PILL)
        drawTextV(g, "Color…", x + 20, ty0, ty1, G_DD, fCaption)
        val cx0 = x
        region(x, ty0, x + colorW, ty1) {
            textPick = ChromaColor(0xFFFFAA00.toInt())
            textPickAt = -1
            textPickLen = 0
            val opt2 =
                ColorOption("Text color", null, { textPick }, { v ->
                    textPick = v
                    val code = codeFor(v)
                    textPickAt = insert(f, code, if (textPickAt >= 0) textPickAt else f.cursor, textPickLen)
                    textPickLen = code.length
                }, textPick)
            openColorPicker(opt2, cx0, ty0, cx0 + colorW, ty1, MC_COLORS)
        }
        x += colorW + 3
        x = toolChip(g, mx, my, x, ty0, ty1, "Chroma", chroma = true) { insert(f, LegacyText.CHROMA) } + 3
        x = toolChip(g, mx, my, x, ty0, ty1, "B", bold = true) { insert(f, "§l") } + 3
        x = toolChip(g, mx, my, x, ty0, ty1, "I", slant = true) { insert(f, "§o") } + 3
        x = toolChip(g, mx, my, x, ty0, ty1, "↺") { insert(f, "§r") }
        val tip = "at the cursor · color applies until the next one"
        if (x + 4 + textWidth(tip, fCaption) <= col.x1) drawTextV(g, tip, x + 4, ty0, ty1, G_77, fCaption)
        col.y = ty1 + 6
        if (opt.values) valueChips(g, mx, my, col, f)
        // Header/Footer note while the Look's chroma covers the whole part.
        col.y += 2
    }

    private fun toolChip(g: GuiGraphicsExtractor, mx: Int, my: Int, x: Int, y0: Int, y1: Int, label: String, chroma: Boolean = false, bold: Boolean = false, slant: Boolean = false, action: () -> Unit): Int {
        val font = if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular
        val size = if (label == "↺") fBody else fCaption
        val w = max(16, textWidth(label, size, font) + if (chroma) 10 else 6)
        val hover = mx in x..x + w && my in y0..y1
        val bg = if (chroma) Chroma.paint(if (hover) 0xFF else 0xB3) else if (hover) CHIP_HOVER else ROW_HOVER
        roundRect(g, x, y0, x + w, y1, bg, SoulRenderPipelines.ROUND_SMALL)
        val tx = x + w / 2 - textWidth(label, size, font) / 2
        if (slant) {
            italic(g, tx, (y0 + y1) / 2) { drawTextV(g, label, 0, -7, 7, G_DD, size, font); 0 }
        } else {
            drawTextV(g, label, tx, y0, y1, if (chroma) WHITE else G_DD, size, font)
        }
        region(x, y0, x + w, y1, action)
        return x + w
    }

    /** `§6` for a Minecraft color, `§#rrggbb` for any other, `§z` for chroma. */
    private fun codeFor(c: ChromaColor): String {
        if (c.chroma) return LegacyText.CHROMA
        val rgb = c.argb and 0xFFFFFF
        val i = listOf(0x000000, 0x0000AA, 0x00AA00, 0x00AAAA, 0xAA0000, 0xAA00AA, 0xFFAA00, 0xAAAAAA, 0x555555, 0x5555FF, 0x55FF55, 0x55FFFF, 0xFF5555, 0xFF55FF, 0xFFFF55, 0xFFFFFF).indexOf(rgb)
        return if (i >= 0) "§" + LEGACY[i] else LegacyText.rgbCode(rgb)
    }

    /** "Insert value": the quick six, "More values…" with every value by group. */
    private fun valueChips(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, f: SoulTextField) {
        var x = col.x0
        var y = col.y
        val lh = 16
        drawTextV(g, "Insert value", x, y, y + 14, Theme.TEXT_DIM, fCaption)
        x += textWidth("Insert value", fCaption) + 4
        fun chip(key: String) {
            val label = "{$key}"
            val w = textWidth(label, fCaption) + 10
            if (x + w > col.x1) {
                x = col.x0
                y += lh
            }
            val hover = mx in x..x + w && my in y..y + 14
            roundRect(g, x, y, x + w, y + 14, if (hover) CHIP_HOVER else CHIP, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, label, x + 5, y, y + 14, VALUE_CHIP, fCaption)
            val r = intArrayOf(x, y, x + w, y + 14)
            ScoreboardValues.get(key)?.let { v -> if (hover) tooltip(r[0], r[1], r[2], r[3], listOf("§f${v.name}", "§7" + LegacyText.strip(v.example))) }
            region(r[0], r[1], r[2], r[3]) { insert(f, label) }
            x += w + 4
        }
        for (key in ScoreboardValues.quick) if (ScoreboardValues.get(key) != null) chip(key)
        val more = if (moreValues) "Fewer" else "More values…"
        val mw = textWidth(more, fCaption) + 6
        if (x + mw > col.x1) {
            x = col.x0
            y += lh
        }
        drawTextV(g, more, x + 3, y, y + 14, 0xFF9CA3AF.toInt(), fCaption)
        g.fill(x + 3, y + 12, x + 3 + textWidth(more, fCaption), y + 13, 0xFF9CA3AF.toInt())
        region(x, y, x + mw, y + 14) { moreValues = !moreValues }
        y += lh
        if (moreValues) {
            val boxTop = y + 2
            var by = boxTop + 6
            val rowsByGroup = ScoreboardValues.groups.map { gname -> gname to ScoreboardValues.all().filter { it.group == gname } }.filter { it.second.isNotEmpty() }
            // Measure first, then draw the box behind and the chips on it.
            val placed = ArrayList<Triple<String, Int, Int>>()
            val labels = ArrayList<Pair<String, Int>>()
            for ((gname, list) in rowsByGroup) {
                labels += gname to by
                var cx = col.x0 + 8 + 56
                for (v in list) {
                    val w = textWidth("{${v.key}}", fCaption) + 10
                    if (cx + w > col.x1 - 8) {
                        cx = col.x0 + 8 + 56
                        by += lh
                    }
                    placed += Triple(v.key, cx, by)
                    cx += w + 4
                }
                by += lh + 1
            }
            roundRect(g, col.x0, boxTop, col.x1, by + 4, SECTION, SoulRenderPipelines.ROUND_SMALL)
            for ((gname, ly) in labels) drawTextV(g, gname, col.x0 + 8, ly, ly + 14, G_77, fCaption)
            for ((key, cx, cy) in placed) {
                val label = "{$key}"
                val w = textWidth(label, fCaption) + 10
                val hover = mx in cx..cx + w && my in cy..cy + 14
                roundRect(g, cx, cy, cx + w, cy + 14, if (hover) CHIP_HOVER else CHIP, SoulRenderPipelines.ROUND_SMALL)
                drawTextV(g, label, cx + 5, cy, cy + 14, VALUE_CHIP, fCaption)
                ScoreboardValues.get(key)?.let { v -> if (hover) tooltip(cx, cy, cx + w, cy + 14, listOf("§f${v.name}", "§7" + LegacyText.strip(v.example))) }
                region(cx, cy, cx + w, cy + 14) { insert(f, label) }
            }
            y = by + 4 + 6
        }
        col.y = y
    }

    // ---- the Look (nothing selected)

    private fun lookInspector(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, c: ScoreboardConfig) {
        val l = c.look
        header(g, mx, my, col, if (c.perActivity) "Look · all setups" else "Look", null, "Select a line in the list or on the preview to change it.", close = false)
        // Background: opacity slider while on, then the switch.
        row(g, col, "Background", null, if (l.background) 4 + textWidth("00.0", fBody) + 6 + 90 + 8 + toggleW else toggleW) { x, cy ->
            val tx = col.x1 - toggleW
            if (l.background) drawSlider(g, tx - 8 - 90, cy, tx - 8, bgOpacity)
            drawToggle(g, tx, cy - toggleH / 2, l.background) { look { copy(background = !background) } }
        }
        segRow(g, mx, my, col, "Text", null, choice("Text", ALIGNS, listOf("Left", "Center", "Right"), l.align) { v -> look { copy(align = v) } })
        switchRow(g, col, "Text shadow", null, l.textShadow) { v -> look { copy(textShadow = v) } }

        sectionLabel(g, col, "Chroma · every chroma color on the scoreboard")
        sliderRow(g, col, "Speed", null, chromaSpeed, 110)
        segRow(g, mx, my, col, "Motion", null, chromaMotion)
        if (cfg.chroma.motion != com.soulreturns.config.ChromaConfig.TOGETHER) note(g, col, "One rainbow across the whole screen: every chroma text in Soul lines up.")
        val chipsW = CHROMA_TARGETS.sumOf { textWidth(it.second, fBody) + 16 } + 4 * (CHROMA_TARGETS.size - 1)
        row(g, col, "Chroma on", "A whole part at once", chipsW) { x0, cy ->
            var x = x0
            for ((key, label) in CHROMA_TARGETS) {
                val on = key in l.chromaOn
                val w = textWidth(label, fBody) + 16
                val hover = mx in x..x + w && my in cy - 9..cy + 9
                roundRect(g, x, cy - 9, x + w, cy + 9, if (on) Theme.ACCENT else if (hover) CHIP_HOVER else CHIP, SoulRenderPipelines.ROUND_PILL)
                drawTextV(g, label, x + 8, cy - 9, cy + 9, if (on) WHITE else Theme.TEXT_DIM, fBody)
                region(x, cy - 9, x + w, cy + 9) { look { copy(chromaOn = if (key in chromaOn) chromaOn - key else chromaOn + key) } }
                x += w + 4
            }
        }

        // Advanced, collapsed by default.
        col.y += 6
        val secTop = col.y
        val headH = 18 + 16
        val hover = mx in col.x0..col.x1 && my in secTop..secTop + headH
        // The card goes first (later fills cover earlier widgets), sized by last frame's rows.
        val secBottom = secTop + if (advancedOpen) advancedH else headH
        roundRect(g, col.x0, secTop, col.x1, secBottom, if (hover && !advancedOpen) 0xFF232323.toInt() else SECTION, SoulRenderPipelines.ROUND_MEDIUM)
        val cy = secTop + headH / 2
        if (advancedOpen) caret(g, col.x0 + 14, cy - 1) else caretRight(g, col.x0 + 14, cy, Theme.TEXT_DIM)
        drawTextV(g, "Advanced", col.x0 + 22, cy - 9, cy + 9, Theme.TEXT, fHeading, MsdfFonts.menuSemibold)
        region(col.x0, secTop, col.x1, secTop + headH) { advancedOpen = !advancedOpen }
        if (advancedOpen) {
            val inner = Col(col.x0 + 12, col.x1 - 12, secTop + headH)
            advanced(g, mx, my, inner, c)
            advancedH = inner.y + 8 - secTop
        }
        col.y = secTop + (if (advancedOpen) advancedH else headH) + 6
    }

    private var advancedH = 0

    private fun choice(label: String, values: List<String>, labels: List<String>, current: String, set: (String) -> Unit): CycleOption =
        CycleOption(label, null, { values.indexOf(current).coerceAtLeast(0) }, { i -> set(values[i.coerceIn(0, values.size - 1)]) }, labels, 0)

    private fun advanced(g: GuiGraphicsExtractor, mx: Int, my: Int, col: Col, c: ScoreboardConfig) {
        val l = c.look
        val d = ScoreboardLook()
        sectionLabel(g, col, "Numbers")
        segRow(g, mx, my, col, "Numbers", null, choice("Numbers", listOf("long", "short"), listOf("1,193,004,241", "1.19B"), l.numbers) { v -> look { copy(numbers = v) } })
        dropdownRow(
            g, col, "Style", null, "numberStyle", NUMBER_STYLES.map { it.second },
            NUMBER_STYLES.indexOfFirst { it.first == l.numberStyle }.coerceAtLeast(0), 170,
        ) { i -> look { copy(numberStyle = NUMBER_STYLES[i].first) } }

        sectionLabel(g, col, "Background")
        row(g, col, "Color", null, swatchW) { x, cy ->
            val value = l.backgroundColor.takeIf { it != d.backgroundColor }
            swatchControl(g, mx, my, x, cy, value, 0x000000, "#000000", false, open = { r ->
                openColorPicker(ColorOption("Background", null, { cfg.scoreboard.look.backgroundColor }, { v -> look { copy(backgroundColor = v) } }, d.backgroundColor), r[0], r[1], r[2], r[3], MC_COLORS)
            }, reset = { look { copy(backgroundColor = d.backgroundColor) } })
        }
        stepperRow(g, mx, my, col, "Corner radius", null, l.cornerRadius, 0, 20, null) { v -> look { copy(cornerRadius = v) } }
        stepperRow(g, mx, my, col, "Padding", null, l.padding, 0, 20, null) { v -> look { copy(padding = v) } }
        switchRow(g, col, "Border", null, l.border) { v -> look { copy(border = v) } }
        if (l.border) {
            val gradient = l.borderStyle != ScoreboardLook.BORDER_SOLID
            segRow(
                g, mx, my, col, "Border style", null,
                choice("Border style", listOf(ScoreboardLook.BORDER_GRADIENT, ScoreboardLook.BORDER_SOLID), listOf("Gradient", "One color"), l.borderStyle) { v ->
                    look { copy(borderStyle = v) }
                },
            )
            // Chroma on the border (Chroma on › Border) draws it in chroma instead: one row says so.
            val chroma = "border" in l.chromaOn
            borderColorRow(g, mx, my, col, if (gradient && !chroma) "Border color 1" else "Border color", chroma, { it.borderColor }) { v -> copy(borderColor = v) }
            if (gradient && !chroma) {
                borderColorRow(g, mx, my, col, "Border color 2", false, { it.borderColor2 }) { v -> copy(borderColor2 = v) }
                sliderRow(g, col, "Border speed", "How fast the colors go round", borderSpeed, 90)
            }
        }
        switchRow(g, col, "Blur behind", null, l.blur) { v -> look { copy(blur = v) } }
        switchRow(g, col, "Image from resource pack", null, l.backgroundImage) { v -> look { copy(backgroundImage = v) } }

        sectionLabel(g, col, "Lines")
        stepperRow(g, mx, my, col, "Line spacing", null, l.lineSpacing, 0, 10, "px") { v -> look { copy(lineSpacing = v) } }
        switchRow(g, col, "Hide lines with no value", null, l.hideEmpty) { v -> look { copy(hideEmpty = v) } }
        switchRow(g, col, "Hypixel's lines only (restyled)", null, l.hypixelLinesOnly) { v -> look { copy(hypixelLinesOnly = v) } }
        switchRow(g, col, "Keep lines while switching islands", null, l.keepWhileSwitching) { v -> look { copy(keepWhileSwitching = v) } }
        switchRow(g, col, "Click and hover actions", null, l.clickActions) { v -> look { copy(clickActions = v) } }

        sectionLabel(g, col, "When to show")
        switchRow(g, col, "Hide with the tab list open", null, l.hideWithTab) { v -> look { copy(hideWithTab = v) } }
        switchRow(g, col, "Hide with chat open", null, l.hideWithChat) { v -> look { copy(hideWithChat = v) } }
        switchRow(g, col, "Hide with F3 open", null, l.hideWithF3) { v -> look { copy(hideWithF3 = v) } }
        switchRow(g, col, "Outside SkyBlock", null, l.outsideSkyblock) { v -> look { copy(outsideSkyblock = v) } }
        switchRow(g, col, "Keep Hypixel's sidebar too", "Off: the Soul scoreboard replaces it", c.keepVanilla) { v -> sb { copy(keepVanilla = v) } }

        if (c.perActivity) {
            sectionLabel(g, col, "Position")
            for (id in stripActs) {
                val a = ScoreboardActivities.of(id) ?: continue
                val hint = if (id in c.activities) "Off: where Everywhere sits (Move GUI)" else "Also with the Everywhere lines"
                switchRow(g, col, "Own position in ${a.name}", hint, id in c.ownPositionIn) { v ->
                    sb { copy(ownPositionIn = if (v) ownPositionIn + id else ownPositionIn - id) }
                }
            }
        }

        sectionLabel(g, col, "Import")
        val bw = textWidth("Import…", fBody) + 24
        row(g, col, "Import from Custom Scoreboard", "Lines, order, events, labels, background", bw) { x, cy ->
            drawButton(g, mx, my, x, cy - 9, x + bw, cy + 9, "Import…") {
                if (ScoreboardImport.available()) {
                    importBanner = true
                } else {
                    showAlert("Nothing to import", "No Custom Scoreboard setup was found in config/customscoreboard/.")
                }
            }
        }
    }

    // ------------------------------------------------------------------------------------------ edits

    private fun removeSelected() {
        val idx = selected
        val type = entries(cfg.scoreboard).getOrNull(idx)?.let { ScoreboardCatalog.type(it.type) } ?: return
        if (type.pinned != LineType.PIN_NONE) return
        select(-1)
        updateEntries { list -> if (idx in list.indices) list.toMutableList().also { it.removeAt(idx) } else list }
    }

    private fun confirmReset() {
        showConfirm(
            "Reset the scoreboard?",
            "Every setup and the Look go back to how Soul ships them. Its position and on/off switch stay.",
            "Reset",
        ) {
            select(-1)
            tab = null
            sessionTabs.clear()
            val d = ScoreboardConfig()
            sb { copy(main = d.main, perActivity = d.perActivity, activities = d.activities, look = d.look, keepVanilla = d.keepVanilla) }
        }
    }

    /** Keep the hit regions added since [start] inside the viewport (rows scrolled under a header stay unclickable). */
    private fun clipRegions(start: Int, x0: Int, y0: Int, x1: Int, y1: Int) {
        val kept = ArrayList<Region>()
        for (i in start until regions.size) {
            val r = regions[i]
            val a = max(r.x0, x0)
            val b = max(r.y0, y0)
            val cc = min(r.x1, x1)
            val dd = min(r.y1, y1)
            if (a <= cc && b <= dd) kept += Region(a, b, cc, dd, r.onClick)
        }
        while (regions.size > start) regions.removeAt(regions.size - 1)
        regions.addAll(kept)
    }

    // ------------------------------------------------------------------------------------------ input

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        notice = null
        return super.mouseClicked(click, doubleClick)
    }

    override fun onEmptyClick() {
        super.onEmptyClick()
        fields.values.forEach { it.focused = false }
        search.focused = false
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        orderDrag?.let { d ->
            val y = (click.y() / SoulUi.factor()).roundToInt()
            if (!d.moving && abs(y - d.pressY) > 3) d.moving = true
            if (d.moving) d.slot = ((y - d.top + ORDER_ROW_H / 2) / ORDER_ROW_H).coerceIn(0, d.order.size)
            return true
        }
        if (pressed >= 0) {
            val s = SoulUi.factor()
            val y = click.y() / s
            if (!dragging && abs(y - pressY) > 3) dragging = true
            if (dragging) dropSlot = slotAt(y.roundToInt())
            return true
        }
        return super.mouseDragged(click, dragX, dragY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        orderDrag?.let { d ->
            orderDrag = null
            finishOrderDrag(d)
            return true
        }
        if (pressed >= 0) {
            val from = pressed
            pressed = -1
            if (dragging) {
                dragging = false
                if (dropSlot >= 0) drop(from, dropSlot)
                dropSlot = -1
            } else if (deselectOnRelease) {
                select(-1)
            }
            deselectOnRelease = false
            return true
        }
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (alertModalOpen) return true
        val s = SoulUi.factor()
        val x = (mouseX / s).roundToInt()
        val y = (mouseY / s).roundToInt()
        if (!dropdownOpen && !colorPickerOpen) {
            wheelSteps.lastOrNull { (r, _) -> x in r[0]..r[2] && y in r[1]..r[3] }?.let { (_, step) ->
                step(if (scrollY > 0) 1 else -1)
                return true
            }
        }
        scrollTargetAt(x, y)?.let {
            it.state.scrollBy(-scrollY.toFloat() * 24f)
            return true
        }
        return true
    }

    private fun focusedField(): SoulTextField? = if (search.focused && pickerOpen) search else fields.values.firstOrNull { it.focused }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) return super.keyPressed(event)
        val key = event.key()
        val esc = key == GLFW.GLFW_KEY_ESCAPE
        if (esc && colorPickerOpen) {
            closeColorPicker()
            return true
        }
        if (esc && dropdownOpen) {
            closeDropdown()
            return true
        }
        if (esc && addActOpen) {
            addActOpen = false
            return true
        }
        if (editingSlider != null) return super.keyPressed(event)
        val field = focusedField()
        if (field != null) {
            if (field === search && esc) {
                pickerOpen = false
                search.focused = false
            } else if (esc || key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
                field.focused = false
            } else {
                field.keyPressed(event, minecraft?.keyboardHandler)
            }
            return true
        }
        if (esc && pickerOpen) {
            pickerOpen = false
            return true
        }
        if (key == GLFW.GLFW_KEY_DELETE && selected >= 0) {
            removeSelected()
            return true
        }
        if ((key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) && editable(cfg.scoreboard) && rows.isNotEmpty()) {
            val r = rows.indexOf(selected)
            val next = if (r < 0) (if (key == GLFW.GLFW_KEY_DOWN) 0 else rows.size - 1) else (r + if (key == GLFW.GLFW_KEY_DOWN) 1 else -1).coerceIn(0, rows.size - 1)
            select(rows[next])
            revealSelected = true
            return true
        }
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        val field = focusedField() ?: return super.charTyped(event)
        field.charTyped(event)
        return true
    }

    override fun isPauseScreen(): Boolean = false

    override fun onClose() {
        commit()
        if (parent != null) minecraft?.setScreenAndShow(parent) else super.onClose()
    }
}
