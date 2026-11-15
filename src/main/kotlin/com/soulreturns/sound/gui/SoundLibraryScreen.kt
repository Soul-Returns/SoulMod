package com.soulreturns.sound.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.sound.SoulSounds
import com.soulreturns.sound.SoundLibrary
import com.soulreturns.sound.SoundRef
import com.soulreturns.ui.SoulUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import kotlin.math.roundToInt

/**
 * The full sound library ("Browser", the user's pick): folders on the left (Favorites, Recently
 * used, Recently heard, My sounds, then Minecraft's sound groups), the sounds in the middle, the
 * selected sound on the right with a level strip, volume and pitch (previewed live), Test and Reset,
 * and rename/delete for the user's own files. Search covers every sound. Files dropped on the window
 * are imported.
 *
 * With a [pick] it is a picker (breadcrumb "Pick a sound for …", Use this sound); without one it is
 * the standalone library. Either way Cancel/Done/Escape return to [parent].
 */
class SoundLibraryScreen(
    private val parent: Screen?,
    private val pick: SoundPick? = null,
    /** Preselected sound (its volume/pitch kept); defaults to the pick's current value. */
    private val start: SoundRef? = pick?.current,
) : SoundScreenBase(Component.literal("Sound library")) {
    companion object {
        fun open(parent: Screen? = null) {
            val mc = Minecraft.getInstance()
            mc.execute { mc.setScreenAndShow(SoundLibraryScreen(parent)) }
        }

        private const val TOP_H = 34
        private const val FOOT_H = 32
        private const val SIDE_ROW_H = 17
    }

    private val model = SoundBrowserModel()
    private var folder = ""
    private var selected: SoundItem? = null
    private var volume = 1f
    private var pitch = 1f

    private var listScroll = 0f
    private var listContentH = 0
    private var listRect = intArrayOf(0, 0, 0, 0)
    private var sideScroll = 0f
    private var sideContentH = 0
    private var sideRect = intArrayOf(0, 0, 0, 0)
    private var scrollToSelection = false
    private var lastQuery = ""

    override fun init() {
        if (folder.isNotEmpty()) return
        val first = start?.let { model.itemFor(it) }
        if (first != null && start != null) {
            selected = first
            volume = start.volume
            pitch = start.pitch
            folder =
                when {
                    SoundLibrary.isFavorite(first.ref) -> SoundBrowserModel.FAVORITES
                    first.custom != null -> SoundBrowserModel.MINE
                    else -> SoundBrowserModel.GROUP + first.tag
                }
            scrollToSelection = true
        } else {
            folder =
                when {
                    SoundLibrary.favorites().isNotEmpty() -> SoundBrowserModel.FAVORITES
                    SoundLibrary.entries().isNotEmpty() -> SoundBrowserModel.MINE
                    else -> model.groups.firstOrNull { it.title == "Entity" }?.id ?: model.groups.firstOrNull()?.id ?: SoundBrowserModel.FAVORITES
                }
        }
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        regions.clear()
        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()
        g.pose().pushMatrix()
        g.pose().scale(s, s)

        val cardW = (uiW * 0.92f).toInt().coerceAtMost(1040).coerceAtMost(uiW - 8)
        val cardH = (uiH * 0.9f).toInt().coerceAtMost(680).coerceAtMost(uiH - 8)
        val x0 = (uiW - cardW) / 2
        val y0 = (uiH - cardH) / 2
        val x1 = x0 + cardW
        val y1 = y0 + cardH
        val bodyY0 = y0 + TOP_H
        val bodyY1 = y1 - FOOT_H
        // Wide enough for the longest folder name with its count, so "Recently heard" is never cut.
        val sideMin = textWidth("Recently heard", fBody) + textWidth("000", fCaption) + 34
        val sideW = (cardW * 0.21f).toInt().coerceIn(sideMin, maxOf(sideMin, 170))
        val inspW = (cardW * 0.3f).toInt().coerceIn(140, 260)

        g.fill(0, 0, uiW, uiH, Theme.DIM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)

        drawTop(g, x0, y0, x1)
        drawSidebar(g, mx, my, x0, bodyY0, x0 + sideW, bodyY1)
        drawList(g, mx, my, x0 + sideW, bodyY0, x1 - inspW, bodyY1)
        drawInspector(g, mx, my, x1 - inspW, bodyY0, x1, bodyY1)
        drawFooter(g, mx, my, x0, bodyY1, x1, y1)

        drawOpenDropdown(g, uiW, uiH, mx, my)
        drawAlertModal(g, uiW, uiH, mx, my)
        g.pose().popMatrix()
    }

    private fun drawTop(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
    ) {
        val y1 = y0 + TOP_H
        roundRect(g, x0, y0, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fillGradient(x0, y1, x1, y1 + 5, 0x50000000, 0x00000000)
        val searchW = ((x1 - x0) * 0.32f).toInt().coerceIn(100, 240)
        val sx1 = x1 - 12
        val sx0 = sx1 - searchW
        val room = sx0 - 12 - (x0 + 14)
        if (pick != null) {
            drawCrumb(g, "Sound library › Pick a sound for", pick.purpose, x0 + 14, y0, y1, room, separator = " ")
        } else {
            drawCrumb(g, null, "Sound library", x0 + 14, y0, y1, room)
        }
        val total = String.format(java.util.Locale.ROOT, "%,d", model.vanilla.size + model.custom().size)
        drawTextField(g, sx0, y0 + 8, sx1, y1 - 8, search, "Search $total sounds…")
    }

    private fun drawSidebar(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        g.fill(x0, y0, x1, y1, Theme.SIDEBAR)
        sideRect = intArrayOf(x0, y0, x1, y1)
        val searching = search.text.isNotBlank()
        beginClip(g, x0, y0, x1, y1)
        var y = y0 + 6 - sideScroll.roundToInt()

        fun header(text: String) {
            drawTextV(g, text.uppercase(), x0 + 12, y + 4, y + 16, Theme.TEXT_FAINT, fCaption)
            y += 18
        }

        fun item(
            id: String,
            title: String,
            count: Int,
        ) {
            val iy0 = y
            val iy1 = y + SIDE_ROW_H
            val on = id == folder && !searching
            val hovered = mx in x0..x1 && my in iy0 until iy1 && within(sideRect, mx, my)
            if (on) {
                roundRect(g, x0 + 6, iy0, x1 - 6, iy1, Theme.ACCENT, SoulRenderPipelines.ROUND_SMALL)
            } else if (hovered) {
                roundRect(g, x0 + 6, iy0, x1 - 6, iy1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            }
            val c = count.toString()
            val cw = textWidth(c, fCaption)
            drawTextV(g, c, x1 - 12 - cw, iy0, iy1, if (on) 0xFFDBE7FF.toInt() else Theme.TEXT_FAINT, fCaption)
            drawTextV(g, ellipsize(title, x1 - 12 - cw - 6 - (x0 + 14), fBody), x0 + 14, iy0, iy1, if (on) 0xFFFFFFFF.toInt() else if (hovered) Theme.TEXT else Theme.TEXT_DIM, fBody)
            clippedRegion(x0, iy0, x1, iy1 - 1, sideRect) { openFolder(id) }
            y += SIDE_ROW_H + 1
        }

        header("Library")
        for (id in listOf(SoundBrowserModel.FAVORITES, SoundBrowserModel.RECENT, SoundBrowserModel.HEARD, SoundBrowserModel.MINE)) {
            item(id, model.folderTitle(id), model.count(id))
        }
        y += 4
        header("Minecraft")
        for (group in model.groups) item(group.id, group.title, group.count)
        endClip(g)
        sideContentH = y + sideScroll.roundToInt() - y0 + 6
        sideScroll = clampScroll(sideScroll, sideContentH, y1 - y0)
    }

    private fun rows(): List<SoundItem> = model.rows(folder, search.text)

    private fun drawList(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        val query = search.text.trim()
        if (query != lastQuery) {
            lastQuery = query
            listScroll = 0f
        }
        val rows = rows()
        val searching = query.isNotEmpty()
        val captionH = 20
        val caption =
            if (searching) {
                "${rows.size} ${if (rows.size == 1) "sound matches" else "sounds match"} \"$query\""
            } else {
                "${model.folderTitle(folder)} · ${rows.size}"
            }
        drawTextV(g, ellipsize(caption, x1 - x0 - 24, fCaption), x0 + 12, y0 + 4, y0 + captionH, Theme.TEXT_DIM, fCaption)

        val ly0 = y0 + captionH + 2
        listRect = intArrayOf(x0, ly0, x1, y1)
        if (scrollToSelection) {
            scrollToSelection = false
            val at = rows.indexOfFirst { selected?.ref?.sameSound(it.ref) == true }
            if (at >= 0) listScroll = (at * ROW_H - (y1 - ly0) / 2 + ROW_H / 2).toFloat()
        }
        listContentH = rows.size * ROW_H + 6
        listScroll = clampScroll(listScroll, listContentH, y1 - ly0)

        if (rows.isEmpty()) {
            val text =
                if (searching) {
                    "No sound matches \"$query\"."
                } else {
                    when (folder) {
                        SoundBrowserModel.FAVORITES -> "No favorites yet. Click the star on any sound to keep it here."
                        SoundBrowserModel.RECENT -> "Sounds you preview or pick show up here."
                        SoundBrowserModel.HEARD -> "Nothing heard yet. Sounds the game plays show up here, newest first."
                        SoundBrowserModel.MINE -> "Drop .ogg, .wav or .mp3 files onto this window to add your own sounds."
                        else -> "This folder is empty."
                    }
                }
            val lines = wrapText(text, x1 - x0 - 40, fBody)
            var ty = ly0 + 24
            for (line in lines) {
                drawTextCentered(g, line, (x0 + x1) / 2, ty, Theme.TEXT_FAINT, fBody)
                ty += 13
            }
            return
        }

        val showTag = searching || !folder.startsWith(SoundBrowserModel.GROUP)
        beginClip(g, x0, ly0, x1, y1)
        val first = (listScroll / ROW_H).toInt().coerceAtLeast(0)
        val last = ((listScroll + (y1 - ly0)) / ROW_H).toInt().coerceAtMost(rows.size - 1)
        for (i in first..last) {
            val ry0 = ly0 + 2 + i * ROW_H - listScroll.roundToInt()
            val item = rows[i]
            val isSel = selected?.ref?.sameSound(item.ref) == true
            drawItemRow(g, mx, my, x0 + 6, ry0, x1 - 6, ry0 + ROW_H, item, i, isSel, listRect, showTag) { double ->
                select(item)
                if (double) {
                    if (pick != null) use() else preview(item.ref.copy(volume = volume, pitch = pitch))
                }
            }
        }
        endClip(g)
    }

    private fun drawInspector(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        g.fill(x0, y0, x1, y1, Theme.SECTION)
        g.fill(x0, y0, x0 + 1, y1, Theme.SEPARATOR)
        val pad = 12
        val ix0 = x0 + pad
        val ix1 = x1 - pad
        val item = selected
        if (item == null) {
            val lines = wrapText("Pick a sound to hear it and tune its volume and pitch.", ix1 - ix0, fBody)
            var ty = y0 + 30
            for (line in lines) {
                drawTextCentered(g, line, (x0 + x1) / 2, ty, Theme.TEXT_FAINT, fBody)
                ty += 13
            }
            return
        }
        val semi = MsdfFonts.menuSemibold
        var y = y0 + pad
        val titleLines = wrapText(item.title, ix1 - ix0, fHeading).take(2)
        for ((i, line) in titleLines.withIndex()) {
            val shown = if (i == 1 && wrapText(item.title, ix1 - ix0, fHeading).size > 2) ellipsize("$line …", ix1 - ix0, fHeading, semi) else line
            drawTextV(g, shown, ix0, y, y + 13, Theme.TEXT, fHeading, semi)
            y += 13
        }
        drawTextV(g, ellipsize(item.idLine, ix1 - ix0, fCaption), ix0, y, y + 11, Theme.TEXT_FAINT, fCaption)
        y += 13
        val meta =
            item.custom?.let { "My sound · ${durationText(it.durationMs)} · ${it.format}" } ?: "Minecraft · ${item.tag}"
        drawTextV(g, ellipsize(meta, ix1 - ix0, fCaption), ix0, y, y + 11, Theme.TEXT_DIM, fCaption)
        y += 16

        // The level strip is the first thing to go when a large UI scale leaves no room for it.
        val below = 20 + 24 + 28 + (if (item.custom != null) 26 else 0) + pad
        if (y + 40 + below <= y1) {
            drawWave(g, ix0, y, ix1, y + 30, item)
            y += 40
        }

        val labelW = maxOf(textWidth("Volume", fBody), textWidth("Pitch", fBody))
        val ref = item.ref
        drawLabeledSlider(g, "Volume", volumeText(volume), labelW, ix0, y + 6, ix1, volume, { volume = volumeFromFrac(it) }) {
            preview(ref.copy(volume = volume, pitch = pitch))
        }
        y += 20
        drawLabeledSlider(g, "Pitch", pitchText(pitch), labelW, ix0, y + 6, ix1, pitchFrac(pitch), { pitch = pitchFromFrac(it) }) {
            preview(ref.copy(volume = volume, pitch = pitch))
        }
        y += 24

        val bh = 18
        val testW = buttonW("Test") + 10
        val playing = isPlaying(ref)
        drawButton(g, mx, my, ix0, y, ix0 + testW, y + bh, "", accent = playing) { togglePreview(ref.copy(volume = volume, pitch = pitch)) }
        val label = if (playing) "Stop" else "Test"
        val lw = textWidth(label, fBody)
        val gx = ix0 + (testW - lw - 9) / 2
        if (playing) {
            g.fill(gx, y + 6, gx + 6, y + 12, Theme.TEXT)
        } else {
            fillPolygon(g, floatArrayOf(gx.toFloat(), y + 5f, gx + 6f, y + 9f, gx.toFloat(), y + 13f), Theme.TEXT)
        }
        drawTextV(g, label, gx + 9, y, y + bh, Theme.TEXT, fBody)
        val resetX0 = ix0 + testW + 6
        drawGhostButton(g, mx, my, resetX0, y, resetX0 + buttonW("Reset"), y + bh, "Reset") {
            volume = 1f
            pitch = 1f
        }
        val fav = SoundLibrary.isFavorite(ref)
        drawStarToggle(g, mx, my, ix1, y + bh / 2, fav, null) { SoundLibrary.toggleFavorite(ref) }
        y += bh + 10

        val custom = item.custom
        if (custom != null) {
            g.fill(ix0, y, ix1, y + 1, Theme.SEPARATOR)
            y += 8
            val rw = buttonW("Rename")
            drawButton(g, mx, my, ix0, y, ix0 + rw, y + bh, "Rename") {
                showPrompt("Rename sound", "The new name for \"${custom.name}\". Settings that use it keep working.", "Rename") { name ->
                    if (SoundLibrary.rename(custom.id, name)) {
                        closeAlert()
                        selected = model.itemFor(SoundRef.custom(custom.id))
                    } else {
                        setPromptState(busy = false, error = "That name is empty.")
                    }
                }
            }
            val dw = buttonW("Delete")
            drawGhostButton(g, mx, my, ix0 + rw + 6, y, ix0 + rw + 6 + dw, y + bh, "Delete") {
                showConfirm(
                    "Delete \"${custom.name}\"?",
                    "The file is removed from the library. Settings that use it go quiet until you pick another sound.",
                    "Delete",
                ) {
                    SoulSounds.stopPreview()
                    SoundLibrary.delete(custom.id)
                    selected = null
                }
            }
        }
    }

    private fun drawFooter(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        roundRect(g, x0, y0, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_BOTTOM)
        g.fill(x0, y0, x1, y0 + 1, Theme.SEPARATOR)
        val by0 = y0 + 6
        val by1 = y1 - 6
        var right = x1 - 8
        if (pick != null) {
            val useLabel = "Use this sound"
            val useW = buttonW(useLabel)
            val sel = selected
            if (sel != null) {
                drawButton(g, mx, my, right - useW, by0, right, by1, useLabel, accent = true) { use() }
            } else {
                roundRect(g, right - useW, by0, right, by1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
                drawTextV(g, useLabel, right - useW + 10, by0, by1, Theme.TEXT_FAINT, fBody)
            }
            right -= useW + 6
            val cw = buttonW("Cancel").coerceAtLeast(56)
            drawButton(g, mx, my, right - cw, by0, right, by1, "Cancel") { onClose() }
            right -= cw + 6
            if (pick.allowNone) {
                val nw = buttonW("No sound")
                drawGhostButton(g, mx, my, right - nw, by0, right, by1, "No sound") {
                    pick.onPick(null)
                    returnTo(parent)
                }
                right -= nw + 6
            }
        } else {
            val dw = buttonW("Done").coerceAtLeast(56)
            drawButton(g, mx, my, right - dw, by0, right, by1, "Done") { onClose() }
            right -= dw + 6
        }
        val full = "+ Import files (or drop them here)"
        val label = if (buttonW(full) <= right - x0 - 14) full else "+ Import"
        drawGhostButton(g, mx, my, x0 + 6, by0, x0 + 6 + buttonW(label), by1, label) { showImportHelp() }
    }

    // ---------------------------------------------------------------- actions

    private fun openFolder(id: String) {
        folder = id
        search.set("")
        search.focused = false
        listScroll = 0f
    }

    private fun select(item: SoundItem) {
        if (selected?.ref?.sameSound(item.ref) != true) {
            val keep = start?.takeIf { it.sameSound(item.ref) }
            volume = keep?.volume ?: 1f
            pitch = keep?.pitch ?: 1f
        }
        selected = item
    }

    private fun use() {
        val p = pick ?: return
        val item = selected ?: return
        val ref = item.ref.copy(volume = volume, pitch = pitch)
        SoundLibrary.markUsed(ref)
        p.onPick(ref)
        returnTo(parent)
    }

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            return
        }
        returnTo(parent)
    }

    override fun onFilesDrop(paths: List<Path>) {
        importFiles(paths) { added ->
            val first = added.firstOrNull() ?: return@importFiles
            openFolder(SoundBrowserModel.MINE)
            model.itemFor(SoundRef.custom(first.id))?.let { select(it) }
            scrollToSelection = true
        }
    }

    // ---------------------------------------------------------------- input

    override fun handleKey(event: KeyEvent): Boolean {
        val key = event.key()
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            val item = selected ?: return false
            if (pick != null) use() else preview(item.ref.copy(volume = volume, pitch = pitch))
            return true
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            val rows = rows()
            if (rows.isEmpty()) return true
            val at = rows.indexOfFirst { selected?.ref?.sameSound(it.ref) == true }
            val next = if (at < 0) 0 else (at + if (key == GLFW.GLFW_KEY_UP) -1 else 1).coerceIn(0, rows.size - 1)
            select(rows[next])
            scrollToSelection = true
            return true
        }
        return false
    }

    override fun mouseScrolled(
        mouseX: Double,
        mouseY: Double,
        scrollX: Double,
        scrollY: Double,
    ): Boolean {
        if (alertModalOpen) return true
        val s = SoulUi.factor()
        val mx = (mouseX / s).roundToInt()
        val my = (mouseY / s).roundToInt()
        if (within(sideRect, mx, my)) {
            sideScroll = clampScroll(sideScroll - scrollY.toFloat() * SCROLL_STEP, sideContentH, sideRect[3] - sideRect[1])
        } else {
            listScroll = clampScroll(listScroll - scrollY.toFloat() * SCROLL_STEP, listContentH, listRect[3] - listRect[1])
        }
        return true
    }
}
