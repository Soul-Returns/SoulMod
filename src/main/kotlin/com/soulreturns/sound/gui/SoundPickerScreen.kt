package com.soulreturns.sound.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.sound.SoundLibrary
import com.soulreturns.sound.SoundRef
import com.soulreturns.ui.SoulUi
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.nio.file.Path
import kotlin.math.roundToInt

/** What a picker is choosing for, and where the choice goes. */
class SoundPick(
    /** The feature, shown before the title ("Bobbin Time"). */
    val purpose: String,
    /** The setting ("Alert sound"). */
    val title: String,
    /** The current value; preselected, its volume and pitch kept. */
    val current: SoundRef?,
    /** Offer "None" (no sound). */
    val allowNone: Boolean,
    /** Gets the chosen ref (null = None) when the user presses Use. */
    val onPick: (SoundRef?) -> Unit,
    /** Only Minecraft's own sounds (the replacer matches game sounds; a custom file never plays as one). */
    val minecraftOnly: Boolean = false,
)

/**
 * The picker window over the settings (the user's pick): a smaller, list-first library drawn on top
 * of [parent], which stays exactly where it was. Favorites, recently used, the user's own sounds and a
 * few suggestions come first; search covers every sound; "Open full library" switches to the
 * [SoundLibraryScreen] with the same pick, which also returns to [parent].
 */
class SoundPickerScreen(
    private val parent: Screen,
    private val pick: SoundPick,
) : SoundScreenBase(Component.literal("Pick a sound")) {
    private sealed class Entry {
        class Header(val text: String) : Entry()

        class Item(val item: SoundItem) : Entry()

        object None : Entry()

        class Hint(val text: String) : Entry()
    }

    private val model = SoundBrowserModel()

    /** Null with [noneSelected] = "None"; null without it = nothing chosen yet. */
    private var selected: SoundItem? = null
    private var noneSelected = false
    private var volume = 1f
    private var pitch = 1f

    private var scroll = 0f
    private var contentH = 0
    private var listRect = intArrayOf(0, 0, 0, 0)
    private var scrollToSelection = true

    private var entriesKey: String? = null
    private var entries: List<Entry> = emptyList()

    override fun init() {
        parent.init(width, height)
        if (selected == null && !noneSelected) {
            val current = pick.current
            if (current == null) {
                noneSelected = pick.allowNone
            } else {
                selected = model.itemFor(current)
                volume = current.volume
                pitch = current.pitch
            }
        }
    }

    override fun isPauseScreen(): Boolean = parent.isPauseScreen

    private fun entries(): List<Entry> {
        val q = search.text.trim()
        val key = "$q|${SoundLibrary.version}"
        if (key == entriesKey) return entries
        if (q != entriesKey?.substringBeforeLast('|')) scroll = 0f
        entriesKey = key
        val out = ArrayList<Entry>()
        val mcOnly = pick.minecraftOnly
        if (q.isNotEmpty()) {
            val hits = model.search(q).filter { !mcOnly || it.custom == null }
            out.add(Entry.Header(if (hits.isEmpty()) "No sound matches \"$q\"" else "${hits.size} ${if (hits.size == 1) "sound" else "sounds"}"))
            hits.mapTo(out) { Entry.Item(it) }
        } else {
            if (pick.allowNone) out.add(Entry.None)
            val shown = HashSet<String>()

            fun section(
                title: String,
                items: List<SoundItem>,
            ) {
                val fresh = items.filter { (!mcOnly || it.custom == null) && shown.add(it.ref.key) }
                if (fresh.isEmpty()) return
                out.add(Entry.Header(title))
                fresh.mapTo(out) { Entry.Item(it) }
            }
            pick.current?.let { cur -> model.itemFor(cur)?.let { section("Current", listOf(it)) } }
            if (mcOnly) section("Heard just now", model.heard().take(12))
            section("Favorites", model.favorites())
            section("Recently used", model.recents())
            if (!mcOnly) section("My sounds", model.custom())
            section("Suggested", model.suggested())
            val total = model.vanilla.size + if (mcOnly) 0 else model.custom().size
            out.add(Entry.Hint("Type to search all ${String.format(java.util.Locale.ROOT, "%,d", total)} sounds"))
        }
        return out.also { entries = it }
    }

    // ---------------------------------------------------------------- render

    override fun extractRenderState(
        g: GuiGraphicsExtractor,
        mouseX: Int,
        mouseY: Int,
        partialTick: Float,
    ) {
        // The settings stay visible (and untouched) behind the window; they get no hover.
        parent.extractRenderState(g, -10_000, -10_000, partialTick)
        g.nextStratum()
        regions.clear()

        val s = SoulUi.factor()
        val uiW = (width / s).toInt()
        val uiH = (height / s).toInt()
        val mx = (mouseX / s).toInt()
        val my = (mouseY / s).toInt()
        g.pose().pushMatrix()
        g.pose().scale(s, s)

        g.fill(0, 0, uiW, uiH, 0x80000000.toInt())
        region(0, 0, uiW, uiH) {}

        val w = (uiW * 0.62f).toInt().coerceAtLeast(300).coerceAtMost(540).coerceAtMost(uiW - 12)
        val h = (uiH * 0.86f).toInt().coerceAtMost(460).coerceAtMost(uiH - 12)
        val x0 = (uiW - w) / 2
        val y0 = (uiH - h) / 2
        val x1 = x0 + w
        val y1 = y0 + h
        val topH = 32
        val footH = 30
        val barH = 26

        roundRect(g, x0 - 1, y0 - 1, x1 + 1, y1 + 1, Theme.FIELD_BORDER, SoulRenderPipelines.ROUND_MEDIUM)
        roundRect(g, x0, y0, x1, y1, Theme.PANEL or 0xFF000000.toInt(), SoulRenderPipelines.ROUND_MEDIUM)
        region(x0, y0, x1, y1) {}

        // Top: "<purpose> › <title>" + search.
        roundRect(g, x0, y0, x1, y0 + topH, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        val searchW = (w * 0.4f).toInt().coerceIn(90, 200)
        val sx1 = x1 - 10
        val sx0 = sx1 - searchW
        drawCrumb(g, pick.purpose, pick.title, x0 + 12, y0, y0 + topH, sx0 - 12 - (x0 + 12))
        drawTextField(g, sx0, y0 + 7, sx1, y0 + topH - 7, search, "Search…")

        // List.
        val ly0 = y0 + topH
        val ly1 = y1 - footH - barH
        drawList(g, mx, my, x0, ly0, x1, ly1)

        // Selection bar: volume + pitch of the chosen sound.
        g.fill(x0, ly1, x1, ly1 + 1, Theme.SEPARATOR)
        val bcy = ly1 + barH / 2
        val sel = selected
        if (sel != null && pick.minecraftOnly) {
            drawTextV(g, ellipsize("Rule for: ${sel.title}  ·  ${sel.idLine}", w - 24, fBody), x0 + 12, ly1, ly1 + barH, Theme.TEXT_DIM, fBody)
        } else if (sel != null) {
            val half = (w - 36) / 2
            val labelW = textWidth("Volume", fBody)
            drawLabeledSlider(g, "Volume", volumeText(volume), labelW, x0 + 12, bcy, x0 + 12 + half, volume, { volume = volumeFromFrac(it) }) {
                preview(sel.ref.copy(volume = volume, pitch = pitch))
            }
            drawLabeledSlider(g, "Pitch", pitchText(pitch), textWidth("Pitch", fBody), x1 - 12 - half + 12, bcy, x1 - 12, pitchFrac(pitch), { pitch = pitchFromFrac(it) }) {
                preview(sel.ref.copy(volume = volume, pitch = pitch))
            }
        } else {
            val text = if (noneSelected) "No sound will play" else "Choose a sound above"
            drawTextV(g, text, x0 + 12, ly1, ly1 + barH, Theme.TEXT_FAINT, fBody)
        }

        // Footer.
        val fy0 = y1 - footH
        roundRect(g, x0, fy0, x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_BOTTOM)
        val by0 = fy0 + 6
        val by1 = y1 - 6
        val useW = buttonW("Use").coerceAtLeast(56)
        val useX0 = x1 - 8 - useW
        val canUse = sel != null || noneSelected
        if (canUse) {
            drawButton(g, mx, my, useX0, by0, x1 - 8, by1, "Use", accent = true) { use() }
        } else {
            roundRect(g, useX0, by0, x1 - 8, by1, Theme.PANEL_HOVER, SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, "Use", useX0 + (useW - textWidth("Use", fBody)) / 2, by0, by1, Theme.TEXT_FAINT, fBody)
        }
        val cancelW = buttonW("Cancel").coerceAtLeast(56)
        drawButton(g, mx, my, useX0 - 6 - cancelW, by0, useX0 - 6, by1, "Cancel") { onClose() }
        if (!pick.minecraftOnly) {
            val fullLabel = "Open full library"
            drawGhostButton(g, mx, my, x0 + 6, by0, x0 + 6 + buttonW(fullLabel), by1, fullLabel) { openFull() }
        }

        drawAlertModal(g, uiW, uiH, mx, my)
        g.pose().popMatrix()
    }

    private fun drawList(
        g: GuiGraphicsExtractor,
        mx: Int,
        my: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
    ) {
        listRect = intArrayOf(x0, y0, x1, y1)
        val viewport = listRect
        val list = entries()
        if (scrollToSelection) {
            scrollToSelection = false
            var y = 0
            for (e in list) {
                val eh = entryH(e)
                val hit = (e is Entry.Item && selected?.ref?.sameSound(e.item.ref) == true) || (e is Entry.None && noneSelected)
                if (hit) {
                    scroll = (y - (y1 - y0) / 2 + eh).toFloat()
                    break
                }
                y += eh
            }
        }
        contentH = list.sumOf { entryH(it) } + 8
        scroll = clampScroll(scroll, contentH, y1 - y0)
        beginClip(g, x0, y0, x1, y1)
        var y = y0 + 4 - scroll.roundToInt()
        val rx0 = x0 + 6
        val rx1 = x1 - 6
        var index = 0
        for (e in list) {
            val eh = entryH(e)
            if (y + eh > y0 && y < y1) {
                when (e) {
                    is Entry.Header -> drawTextV(g, e.text.uppercase(), rx0 + 4, y + 4, y + eh, Theme.TEXT_FAINT, fCaption)
                    is Entry.Hint -> drawTextV(g, e.text, rx0 + 4, y, y + eh, Theme.TEXT_FAINT, fCaption)
                    is Entry.None -> {
                        drawRowBg(g, mx, my, rx0, y, rx1, y + eh, 0, noneSelected, viewport)
                        val cy = y + eh / 2
                        roundRect(g, rx0 + 5, cy - 8, rx0 + 21, cy + 8, Theme.CONTROL, SoulRenderPipelines.ROUND_PILL)
                        g.fill(rx0 + 9, cy, rx0 + 17, cy + 1, Theme.TEXT_DIM)
                        drawTitles(g, "None", "No sound plays", rx0 + 27, rx1 - 6, y, y + eh)
                        clippedRegion(rx0, y, rx1, y + eh - 1, viewport) {
                            val double = doubleClicked("none")
                            noneSelected = true
                            selected = null
                            if (double) use()
                        }
                    }
                    is Entry.Item -> {
                        val item = e.item
                        val isSel = selected?.ref?.sameSound(item.ref) == true
                        drawItemRow(g, mx, my, rx0, y, rx1, y + eh, item, index, isSel, viewport, showTag = false) { double ->
                            select(item)
                            if (double) use()
                        }
                    }
                }
            }
            if (e is Entry.Item) index++
            y += eh
        }
        endClip(g)
    }

    private fun entryH(e: Entry): Int =
        when (e) {
            is Entry.Header -> 18
            is Entry.Hint -> 22
            else -> ROW_H
        }

    private fun select(item: SoundItem) {
        val cur = pick.current
        if (selected?.ref?.sameSound(item.ref) != true) {
            if (cur != null && cur.sameSound(item.ref)) {
                volume = cur.volume
                pitch = cur.pitch
            } else {
                volume = 1f
                pitch = 1f
            }
        }
        selected = item
        noneSelected = false
    }

    // ---------------------------------------------------------------- actions

    private fun use() {
        val sel = selected
        val ref = if (sel != null) sel.ref.copy(volume = volume, pitch = pitch) else if (noneSelected) null else return
        if (ref != null) SoundLibrary.markUsed(ref)
        pick.onPick(ref)
        minecraft?.setScreenAndShow(parent)
    }

    private fun openFull() {
        val sel = selected
        val start = if (sel != null) sel.ref.copy(volume = volume, pitch = pitch) else pick.current
        minecraft?.setScreenAndShow(SoundLibraryScreen(parent, pick, start))
    }

    override fun onClose() {
        if (alertModalOpen) {
            closeAlert()
            return
        }
        minecraft?.setScreenAndShow(parent)
    }

    override fun onFilesDrop(paths: List<Path>) {
        importFiles(paths) { added ->
            val first = added.firstOrNull() ?: return@importFiles
            search.set("")
            model.itemFor(SoundRef.custom(first.id))?.let { select(it) }
            scrollToSelection = true
        }
    }

    // ---------------------------------------------------------------- input

    override fun handleKey(event: KeyEvent): Boolean {
        val key = event.key()
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (selected != null || noneSelected) use()
            return true
        }
        if (key == GLFW.GLFW_KEY_UP || key == GLFW.GLFW_KEY_DOWN) {
            val selectable = entries().filter { it is Entry.Item || it is Entry.None }
            if (selectable.isEmpty()) return true
            val at =
                selectable.indexOfFirst {
                    (it is Entry.Item && selected?.ref?.sameSound(it.item.ref) == true) || (it is Entry.None && noneSelected)
                }
            val next = selectable[(if (at < 0) 0 else at + if (key == GLFW.GLFW_KEY_UP) -1 else 1).coerceIn(0, selectable.size - 1)]
            if (next is Entry.Item) {
                select(next.item)
            } else {
                noneSelected = true
                selected = null
            }
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
        scroll = clampScroll(scroll - scrollY.toFloat() * SCROLL_STEP, contentH, listRect[3] - listRect[1])
        return true
    }
}
