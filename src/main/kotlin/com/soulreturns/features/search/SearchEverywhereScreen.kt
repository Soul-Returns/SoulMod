package com.soulreturns.features.search

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.islandchests.IslandChests
import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import com.soulreturns.util.KeyCombo
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.roundToInt

/** The Search everywhere screen, `mocks/search-everywhere/` variant merged; numbers from [SearchEverywhereLayout]. */
class SearchEverywhereScreen(private val parent: Screen? = null) : SoulScreenBase(Component.literal("Search everywhere")) {
    private val search = SoulTextField().apply {
        focused = true
        onChange = { listScroll.pos = 0f }
    }
    private val listScroll = ScrollState()
    private val placesScroll = ScrollState()
    private val bodyScroll = ScrollState()
    private var filter = "all"
    private var selectedKey: String? = null
    /** Source sections of the details pane start collapsed (approved mock); clicking a header toggles. */
    private val expanded = HashSet<String>()
    private var profile: String? = SearchEverywhere.profile()
    private var detailHeight = SearchEverywhereLayout.COMPACT_DETAIL
    private var view = intArrayOf(0, 0, 0, 0)
    private var mx = 0
    private var my = 0

    internal fun restore(query: String, itemKey: String?) {
        search.set(query)
        selectedKey = itemKey
    }

    override fun isPauseScreen() = false

    override fun onClose() {
        if (parent != null) Minecraft.getInstance().setScreenAndShow(parent) else super.onClose()
    }

    private fun filtered(active: List<SearchSource>) = if (filter == "all") active else active.filter { it.id == filter }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        regions.clear()
        scrollTargets.clear()
        val scale = SoulUi.factor()
        val w = width / scale
        val h = height / scale
        mx = (mouseX / scale).roundToInt()
        my = (mouseY / scale).roundToInt()
        if (profile != SearchEverywhere.profile()) {
            profile = SearchEverywhere.profile()
            selectedKey = null
            listScroll.pos = 0f
            placesScroll.pos = 0f
        }
        val active = SearchEverywhere.active()
        val rows = SearchEverywhere.rows(active)
        if (filter != "all" && active.none { it.id == filter }) filter = "all"
        val filtered = filtered(active)
        val results = SearchEverywhere.index.search(search.text, filtered.map { it.id })
        val selected = selectedKey?.let { key -> rows.firstOrNull { it.key == key } }
        val frame = SearchEverywhereLayout.frame(w, h, detailHeight, bodyScroll.pos.roundToInt())
        val o = frame.outer
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        g.fill(0, 0, w.toInt(), h.toInt(), Theme.DIM)
        roundRect(g, o.x, o.y, o.right, o.bottom, EDGE, SoulRenderPipelines.ROUND_LARGE)
        roundRect(g, o.x + 1, o.y + 1, o.right - 1, o.bottom - 1, BG, SoulRenderPipelines.ROUND_LARGE)
        beginClip(g, o.x, o.y, o.right, o.bottom)
        drawHeader(g, frame)
        drawScope(g, frame.scope, active)
        drawFoot(g, frame, selected != null)
        endClip(g)
        val body = frame.body
        view = intArrayOf(body.x, body.y, body.right, body.bottom)
        if (frame.compact) {
            bodyScroll.content = SearchEverywhereLayout.COMPACT_SEARCH + max(SearchEverywhereLayout.COMPACT_DETAIL, detailHeight)
            bodyScroll.view = body.height
            bodyScroll.clamp()
            drawSearch(g, frame.search, active, filtered, results, true)
            g.fill(body.x, frame.search.bottom - 1, body.right, frame.search.bottom, EDGE)
            detailHeight = drawDetail(g, frame.detail, active, selected, false)
            drawScrollbar(g, mx, my, bodyScroll, body.x, body.y, body.right, body.bottom)
            scrollTargets.add(0, scrollTargets.removeAt(scrollTargets.lastIndex))
        } else {
            g.fill(frame.search.right, body.y, frame.search.right + 1, body.bottom, DIVIDER)
            drawSearch(g, frame.search, active, filtered, results, false)
            drawDetail(g, frame.detail, active, selected, true)
        }
        drawTooltip(g, w.toInt(), h.toInt())
        g.pose().popMatrix()
    }

    // ---------------------------------------------------------------- chrome

    private fun drawHeader(g: GuiGraphicsExtractor, frame: SearchEverywhereLayout.Frame) {
        val r = frame.header
        roundRect(g, r.x, r.y, r.right, r.bottom, HEAD_BG, SoulRenderPipelines.ROUND_TOP)
        g.fill(r.x, r.bottom - 1, r.right, r.bottom, DIVIDER)
        roundRect(g, r.x + 22, r.y + 20, r.x + 60, r.y + 58, 0xFF202F46.toInt())
        drawTextV(g, "S", r.x + 41 - textWidth("S", em(25), MsdfFonts.menuSemibold) / 2, r.y + 20, r.y + 58, 0xFF86B4FF.toInt(), em(25), MsdfFonts.menuSemibold)
        drawTextV(g, "Search everywhere", r.x + 72, r.y + 15, r.y + 43, WHITE, em(25), MsdfFonts.menuSemibold)
        drawTextV(g, "Every item you have seen, wherever it is.", r.x + 72, r.y + 45, r.y + 64, MUTED, em(12))
        if (!frame.compact) {
            val label = KeyCombo.label(cfg.islandChests.searchKey).ifEmpty { "Unbound" }
            kbd(g, label, r.right - 22 - kbdWidth(label), r.y + 30)
        }
    }

    private fun drawScope(g: GuiGraphicsExtractor, r: Rect, active: List<SearchSource>) {
        g.fill(r.x, r.y, r.right, r.bottom, SCOPE_BG)
        g.fill(r.x, r.bottom - 1, r.right, r.bottom, DIVIDER)
        val cy = r.y + 17
        roundRect(g, r.x + 22, cy - 3, r.x + 28, cy + 3, 0xFF8FB2E7.toInt(), SoulRenderPipelines.ROUND_PILL)
        val name = (SkyblockProfileState.current ?: if (SkyblockProfileState.profileId != null) "Current profile" else "Profile not resolved") +
            if (SkyblockState.profileType == ProfileType.IRONMAN) " · Ironman" else ""
        val nameW = textWidth(name, em(12), MsdfFonts.menuSemibold)
        drawTextV(g, name, r.x + 35, r.y + 7, r.y + 26, 0xFFDEDEDE.toInt(), em(12), MsdfFonts.menuSemibold)
        val sep = r.x + 35 + nameW + 13
        g.fill(sep, cy - 7, sep + 1, cy + 7, 0xFF383838.toInt())
        val home = IslandChests.onOwnIsland()
        val place = if (home) "Your private island" else if (LocationApi.inDungeon) "Dungeon run" else SkyblockLocation.area ?: "SkyBlock"
        val away = !home && active.any { it.id == IslandChestSource.id }
        val text = place + if (away) " · island chests from cache" else ""
        drawTextV(g, ellipsize(text, (r.right - 22 - sep - 14).coerceAtLeast(1), em(12)), sep + 14, r.y + 7, r.y + 26, if (away) AWAY else MUTED, em(12))
    }

    private fun drawFoot(g: GuiGraphicsExtractor, frame: SearchEverywhereLayout.Frame, selected: Boolean) {
        val r = frame.foot
        roundRect(g, r.x, r.y, r.right, r.bottom, HEAD_BG, SoulRenderPipelines.ROUND_BOTTOM)
        g.fill(r.x, r.y, r.right, r.y + 1, DIVIDER)
        val left = "Island chests refresh when a chest is opened, storage when a page is opened."
        drawTextV(g, ellipsize(left, r.width - 40 - if (frame.compact) 0 else 230, em(9)), r.x + 20, r.y + 1, r.bottom, FAINT, em(9))
        if (frame.compact) return
        val parts = listOf("Tab" to "Source ·", "Enter" to "First result ·", "Esc" to if (selected) "Clear selection" else "Close")
        var x = r.right - 20 - parts.sumOf { (key, text) -> kbdWidth(key) + 4 + textWidth(text, em(9)) } - 4 * (parts.size - 1)
        for ((key, text) in parts) {
            kbd(g, key, x, r.y + 6)
            x += kbdWidth(key) + 4
            drawTextV(g, text, x, r.y + 1, r.bottom, FAINT, em(9))
            x += textWidth(text, em(9)) + 4
        }
    }

    private fun kbdWidth(label: String) = textWidth(label, em(10)) + 10

    private fun kbd(g: GuiGraphicsExtractor, label: String, x: Int, y: Int) {
        val w = kbdWidth(label)
        roundRect(g, x, y, x + w, y + 18, 0xFF3A3A3A.toInt(), SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x + 1, y + 1, x + w - 1, y + 17, HEAD_BG, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, label, x + 5, y, y + 18, MUTED, em(10))
    }

    /** `.s-btn`: 0 10 padding, font 11; accent blue, disabled dimmed without a click region. */
    private fun button(g: GuiGraphicsExtractor, x1: Int, y: Int, h: Int, action: SectionAction, clip: IntArray): Int {
        val w = textWidth(action.label, em(11)) + 20
        val x = x1 - w
        val hover = action.enabled && mx in x until x1 && my in y until y + h && inside(clip)
        val fill = if (!action.enabled) 0xFF232323.toInt() else if (action.accent) { if (hover) Theme.ACCENT else Theme.ACCENT_DIM } else if (hover) 0xFF353535.toInt() else 0xFF2A2A2A.toInt()
        if (!action.accent || !action.enabled) roundRect(g, x, y, x1, y + h, 0xFF3A3A3A.toInt(), SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, x + if (action.accent && action.enabled) 0 else 1, y + if (action.accent && action.enabled) 0 else 1,
            x1 - if (action.accent && action.enabled) 0 else 1, y + h - if (action.accent && action.enabled) 0 else 1, fill, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, action.label, x + 10, y, y + h, if (action.enabled) WHITE else 0xFF666666.toInt(), em(11))
        if (action.enabled) clippedRegion(x, y, x1, y + h, clip) { run(action) }
        return w
    }

    private fun run(action: SectionAction) {
        selectedKey?.let { SearchEverywhere.remember(search.text, it) }
        action.run()
    }

    private fun inside(clip: IntArray) = mx >= clip[0] && mx < clip[2] && my >= clip[1] && my < clip[3]

    private fun dot(g: GuiGraphicsExtractor, x: Int, cy: Int, size: Int, color: Int) =
        roundRect(g, x, cy - size / 2, x + size, cy - size / 2 + size, color, SoulRenderPipelines.ROUND_SMALL)

    /** CSS letter-spacing 1px for the small caps labels; returns the end x. */
    private fun tracked(g: GuiGraphicsExtractor, text: String, x: Int, top: Int, bottom: Int, size: Int, color: Int): Int {
        var cx = x.toFloat()
        for (ch in text) {
            val s = ch.toString()
            drawTextV(g, s, cx.roundToInt(), top, bottom, color, em(size))
            cx += MsdfFonts.menuRegular.widthF(s, em(size)) + 1f
        }
        return (cx - 1f).roundToInt()
    }

    private fun clip(g: GuiGraphicsExtractor, r: Rect): IntArray {
        val c = intArrayOf(max(r.x, view[0]), max(r.y, view[1]), minOf(r.right, view[2]), minOf(r.bottom, view[3]))
        beginClip(g, c[0], c[1], max(c[0], c[2]), max(c[1], c[3]))
        return c
    }

    private fun visible(r: Rect) = max(r.x, view[0]) < minOf(r.right, view[2]) && max(r.y, view[1]) < minOf(r.bottom, view[3])

    // ---------------------------------------------------------------- search column

    private fun drawSearch(g: GuiGraphicsExtractor, col: Rect, active: List<SearchSource>, filtered: List<SearchSource>, results: List<SearchRow>, compact: Boolean) {
        if (!visible(col)) return
        val section = clip(g, col)
        val field = SearchEverywhereLayout.field(col)
        drawTextField(g, field.x, field.y, field.right, field.bottom, search, "Search all items…", section, inset = 38, background = 0xFF242424.toInt(), border = 0xFF3A3A3A.toInt())
        drawIcon(g, "search", field.x + 12, field.y + 9, 17, MUTED)
        if (search.text.isNotEmpty()) {
            drawTextV(g, "×", field.right - 22, field.y, field.bottom, 0xFFAAAAAA.toInt(), em(18))
            clippedRegion(field.right - 30, field.y, field.right, field.bottom, section) { search.set(""); listScroll.pos = 0f; search.focused = true }
        } else kbd(g, "/", field.right - 12 - kbdWidth("/"), field.y + 8)

        // Source filter (`chipsHtml`): All plus every active source, counts over the query.
        val chipIds = listOf("all") + active.map { it.id }
        val labels = listOf("All") + active.map { it.label }
        val counts = chipIds.map { id -> SearchEverywhere.index.search(search.text, if (id == "all") active.map { it.id } else listOf(id)).size }
        val none = listOf(false) + active.map { it.entries().isEmpty() }
        val countText = counts.indices.map { if (none[it]) "–" else counts[it].toString() }
        val chips = SearchEverywhereLayout.chips(col, chipIds.indices.map { SearchEverywhereLayout.chipWidth(textWidth(labels[it], em(11)), textWidth(countText[it], em(10)), it > 0) })
        for (i in chips.indices) {
            val r = chips[i]
            val on = filter == chipIds[i]
            val hover = r.contains(mx, my) && inside(section)
            roundRect(g, r.x, r.y, r.right, r.bottom, if (on) 0xFF385A87.toInt() else EDGE, SoulRenderPipelines.ROUND_PILL)
            roundRect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, if (on) 0xFF202D40.toInt() else if (hover) 0xFF2A2A2A.toInt() else 0xFF202020.toInt(), SoulRenderPipelines.ROUND_PILL)
            var x = r.x + 11
            if (i > 0) { dot(g, x, r.y + 12, 7, active[i - 1].color); x += 13 }
            drawTextV(g, labels[i], x, r.y, r.bottom, if (none[i]) FAINT else if (on || hover) WHITE else 0xFFBBBBBB.toInt(), em(11))
            x += textWidth(labels[i], em(11)) + 6
            drawTextV(g, countText[i], x, r.y, r.bottom, if (on) 0xFF9DB4D1.toInt() else MUTED, em(10))
            clippedRegion(r.x, r.y, r.right, r.bottom, section) { filter = chipIds[i]; listScroll.pos = 0f; search.focused = true }
        }
        val chipsBottom = chips.last().bottom
        val head = Rect(col.x + 20, chipsBottom, SearchEverywhereLayout.field(col).right, chipsBottom + SearchEverywhereLayout.LIST_HEADER)
        val query = search.text.trim()
        drawTextV(g, if (query.isEmpty()) "Everything you have seen" else plural(results.size, "matching item"), head.x + 3, head.y + 12, head.y + 28, FAINT, em(10))
        drawTextV(g, "Total", head.right - 12 - textWidth("Total", em(10)), head.y + 12, head.y + 28, FAINT, em(10))
        endClip(g)

        val list = SearchEverywhereLayout.list(col, chipsBottom)
        val listClip = clip(g, list)
        listScroll.view = list.height
        val message = emptyState(active, filtered, results, query)
        if (message != null) {
            listScroll.content = 0
            drawEmpty(g, list, listClip, message)
        } else {
            listScroll.content = results.size * SearchEverywhereLayout.ROW_PITCH - 3
            listScroll.clamp()
            val ids = filtered.map { it.id }
            for ((index, row) in results.withIndex()) {
                val r = SearchEverywhereLayout.row(list, index, listScroll.pos.roundToInt())
                if (r.bottom <= listClip[1] || r.y >= listClip[3]) continue
                drawRow(g, r, row, filtered, ids, listClip)
            }
        }
        endClip(g)
        if (!compact || listScroll.content > listScroll.view) drawScrollbar(g, mx, my, listScroll, list.x, max(list.y, view[1]), list.right, max(max(list.y, view[1]), minOf(list.bottom, view[3])))

        val foot = SearchEverywhereLayout.listFoot(col)
        clip(g, foot)
        g.fill(foot.x, foot.y, foot.right, foot.y + 1, DIVIDER)
        val note = "Last seen, not live"
        val noteX = foot.right - textWidth(note, em(9))
        var x = foot.x
        for (source in active) {
            val coverage = source.coverage()
            val text = ellipsize(coverage.short, (noteX - 12 - x - 12).coerceAtLeast(1), em(9))
            if (x + 12 >= noteX - 12) break
            dot(g, x, foot.y + 16, 7, source.color)
            drawTextV(g, text, x + 12, foot.y + 8, foot.y + 24, if (coverage.none) AMBER else FAINT, em(9))
            x += 12 + textWidth(text, em(9)) + 12
        }
        drawTextV(g, note, noteX, foot.y + 8, foot.y + 24, 0xFF555555.toInt(), em(9))
        endClip(g)
    }

    private fun drawRow(g: GuiGraphicsExtractor, r: Rect, row: SearchRow, filtered: List<SearchSource>, ids: List<String>, clip: IntArray) {
        val selected = row.key == selectedKey
        val hover = r.contains(mx, my) && inside(clip)
        if (selected) {
            roundRect(g, r.x, r.y, r.right, r.bottom, 0xFF385A87.toInt(), SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, 0xFF202D40.toInt(), SoulRenderPipelines.ROUND_SMALL)
        } else if (hover) roundRect(g, r.x, r.y, r.right, r.bottom, 0xFF252525.toInt(), SoulRenderPipelines.ROUND_SMALL)
        SearchItems.icon(g, row.item, r.x + 12, r.y + 15, 24)
        val total = SearchItems.number(row.total(ids))
        val totalW = textWidth(total, em(14))
        drawTextV(g, total, r.right - 9 - totalW, r.y + 16, r.y + 38, WHITE, em(14))
        val textRight = r.right - 9 - totalW - 10
        drawTextV(g, ellipsize(SearchItems.name(row.item), (textRight - r.x - 49).coerceAtLeast(1), em(13)), r.x + 49, r.y + 7, r.y + 28, SearchItems.color(row.item), em(13))
        var x = r.x + 49
        for (source in filtered) {
            val hits = row.hitsIn(source.id)
            if (hits.isEmpty() || x + 12 >= textRight) continue
            val text = ellipsize(source.where(hits), (textRight - x - 12).coerceAtLeast(1), em(10))
            dot(g, x, r.y + 39, 7, source.color)
            drawTextV(g, text, x + 12, r.y + 31, r.y + 47, MUTED, em(10))
            x += 12 + textWidth(text, em(10)) + 10
        }
        clippedRegion(r.x, r.y, r.right, r.bottom, clip) {
            selectedKey = row.key
            placesScroll.pos = 0f
            search.focused = false
        }
    }

    // ---------------------------------------------------------------- empty states (mock `listHtml`)

    private sealed interface Part
    private class Title(val text: String) : Part
    private class Para(val text: String) : Part
    private class Coverage(val lines: List<Triple<Int, String, Int>>) : Part
    private class Link(val text: String, val action: () -> Unit) : Part
    private class ClearButton : Part

    private fun emptyState(active: List<SearchSource>, filtered: List<SearchSource>, results: List<SearchRow>, query: String): List<Part>? {
        if (active.isEmpty()) return listOf(Title("Nothing to search yet"),
            Para("Turn on Island chests or Storage overlay in /soul.\nSearch everywhere covers what those features remember."))
        val cached = filtered.filter { it.entries().isNotEmpty() }
        if (cached.isEmpty() || (results.isEmpty() && query.isEmpty())) {
            if (filtered.size == 1) return listOf(Title(filtered[0].emptyTitle), Para(filtered[0].emptyText))
            return listOf(Title("Nothing seen yet"),
                Para("Soul searches what you have already seen:\nchests you open on your island and the storage pages you look at."),
                Coverage(active.map { Triple(it.color, if (it.entries().isNotEmpty()) it.searched() else "${it.label}: nothing seen yet", MUTED) }))
        }
        if (results.isNotEmpty()) return null
        // The MSDF fonts have no curly quotes (the mock's “…”), so straight ones.
        val parts = mutableListOf<Part>(Title("No matches for \"$query\""),
            Coverage(filtered.map { Triple(it.color, if (it.entries().isNotEmpty()) it.searched() else "${it.label}: nothing seen yet", MUTED) }))
        if (filter != "all") {
            val elsewhere = SearchEverywhere.index.search(query, active.map { it.id }).size
            if (elsewhere > 0) parts += Link("${plural(elsewhere, "match", "matches")} in other sources · show all") { filter = "all"; listScroll.pos = 0f }
        }
        parts += Para("Pages and chests you never opened are not searchable.")
        parts += ClearButton()
        return parts
    }

    private fun drawEmpty(g: GuiGraphicsExtractor, list: Rect, clip: IntArray, parts: List<Part>) {
        val width = (list.width - 20).coerceAtLeast(1)
        val cx = list.x + list.width / 2
        fun paraLines(p: Para) = p.text.split('\n').flatMap { wrapText(it, width, em(11)) }
        fun coverageLines(c: Coverage) = c.lines.map { (color, text, textColor) -> Triple(color, ellipsize(text, width - 40, em(10)), textColor) }
        fun height(part: Part): Int = when (part) {
            is Title -> 21
            is Para -> (paraLines(part).size * 17.6f).roundToInt()
            is Coverage -> 18 + part.lines.size * 17 - 5 + 2
            is Link -> 14
            is ClearButton -> 26
        }
        val total = parts.sumOf(::height) + 10 * (parts.size - 1)
        var y = max(list.y + 8, list.y + (list.height - total) / 2)
        for (part in parts) {
            val h = height(part)
            when (part) {
                is Title -> drawTextV(g, part.text, cx - textWidth(part.text, em(17)) / 2, y, y + h, 0xFFCCCCCC.toInt(), em(17))
                is Para -> paraLines(part).forEachIndexed { i, line ->
                    val top = y + (i * 17.6f).roundToInt()
                    drawTextV(g, line, cx - textWidth(line, em(11)) / 2, top, top + 18, MUTED, em(11))
                }
                is Coverage -> {
                    val lines = coverageLines(part)
                    val boxW = lines.maxOf { textWidth(it.second, em(10)) } + 22 + 14
                    val x = cx - boxW / 2
                    roundRect(g, x, y, x + boxW, y + h, 0xFF2E2E2E.toInt(), SoulRenderPipelines.ROUND_SMALL)
                    roundRect(g, x + 1, y + 1, x + boxW - 1, y + h - 1, SCOPE_BG, SoulRenderPipelines.ROUND_SMALL)
                    lines.forEachIndexed { i, (color, text, textColor) ->
                        val top = y + 10 + i * 17
                        dot(g, x + 12, top + 6, 7, color)
                        drawTextV(g, text, x + 26, top, top + 12, textColor, em(10))
                    }
                }
                is Link -> {
                    val lw = textWidth(part.text, em(11))
                    drawTextV(g, part.text, cx - lw / 2, y, y + h, 0xFF8CB9FF.toInt(), em(11))
                    clippedRegion(cx - lw / 2, y, cx + lw / 2, y + h, clip, part.action)
                }
                is ClearButton -> {
                    val bw = textWidth("Clear search", em(11)) + 20
                    button(g, cx + bw / 2, y, 26, SectionAction("Clear search") { search.set(""); search.focused = true; listScroll.pos = 0f }, clip)
                }
            }
            y += h + 10
        }
    }

    // ---------------------------------------------------------------- details column

    /** Returns the column's content height (compact layouts grow to fit it). */
    private fun drawDetail(g: GuiGraphicsExtractor, col: Rect, active: List<SearchSource>, row: SearchRow?, independent: Boolean): Int {
        g.fill(max(col.x, view[0]), max(col.y, view[1]), minOf(col.right, view[2]), max(max(col.y, view[1]), minOf(col.bottom, view[3])), DETAIL_BG)
        val sources = if (row == null) emptyList() else active.filter { row.has(it.id) }
        if (row == null || sources.isEmpty()) return drawStart(g, col, active)
        if (!visible(col)) return SearchEverywhereLayout.COMPACT_DETAIL
        clip(g, col)
        val inner = SearchEverywhereLayout.inner(col)
        roundRect(g, inner.x, col.y + 21, inner.x + 42, col.y + 63, 0xFF222222.toInt(), SoulRenderPipelines.ROUND_SMALL)
        SearchItems.icon(g, row.item, inner.x + 6, col.y + 27, 30)
        val eyebrow = if (sources.size > 1) "SEEN IN ${sources.size} SOURCES" else "LAST SEEN IN ${sources[0].label.uppercase()}"
        tracked(g, eyebrow, inner.x + 54, col.y + 23, col.y + 38, 9, FAINT)
        drawTextV(g, ellipsize(SearchItems.name(row.item), (inner.width - 54).coerceAtLeast(1), em(18)), inner.x + 54, col.y + 44, col.y + 66, SearchItems.color(row.item), em(18))

        val total = SearchItems.number(row.total(sources.map { it.id }))
        drawTextV(g, total, inner.x + 1, col.y + 78, col.y + 108, WHITE, em(29))
        drawTextV(g, "total seen", inner.x + 1, col.y + 108, col.y + 124, FAINT, em(10))
        val placeCount = sources.sumOf { row.hitsIn(it.id).size }
        val placesText = plural(placeCount, "place")
        drawTextV(g, placesText, inner.right - textWidth(placesText, em(17)), col.y + 79, col.y + 106, 0xFFDDDDDD.toInt(), em(17))
        val split = sources.joinToString(" · ") { it.places(row.hitsIn(it.id).size) }
        val splitText = ellipsize(split, inner.width / 2 + 60, em(10))
        drawTextV(g, splitText, inner.right - textWidth(splitText, em(10)), col.y + 106, col.y + 122, FAINT, em(10))
        g.fill(inner.x, col.y + 136, inner.right, col.y + 137, DIVIDER)

        val built = sources.map { it to it.section(row.item, row.key, row.hitsIn(it.id)) }
        val stale = built.any { it.second.stale }
        val note = if (stale) "Older observations included. Items may have moved since." else "What you last saw, not a live count. Opening a chest or page refreshes it."
        val noteLines = wrapText(note, inner.width, em(10))
        val noteWidth = inner.width - 14
        val sourceNotes = built.map { (_, s) -> s.note?.let { wrapText(it, noteWidth, em(10)) }.orEmpty() }
        val boxes = SearchEverywhereLayout.sections(sourceNotes.map { it.size }, built.map { (source, s) -> if (source.id in expanded) s.cards.size else 0 })
        val contentH = boxes.lastOrNull()?.bottom ?: 0
        val places = if (independent) SearchEverywhereLayout.places(col, noteLines.size)
            else Rect(inner.x, col.y + SearchEverywhereLayout.PLACES_TOP, inner.right, col.y + SearchEverywhereLayout.PLACES_TOP + contentH)
        endClip(g)
        val placesClip = clip(g, places)
        if (independent) {
            placesScroll.content = contentH
            placesScroll.view = places.height
            placesScroll.clamp()
        }
        val top = places.y - if (independent) placesScroll.pos.roundToInt() else 0
        for ((i, pair) in built.withIndex()) {
            val (source, s) = pair
            val box = boxes[i]
            drawSectionHeader(g, source, s, row, Rect(places.x, top + box.top, places.right - 2, top + box.top + SearchEverywhereLayout.SECTION_H), placesClip)
            sourceNotes[i].forEachIndexed { line, text ->
                val y = top + box.noteTop + line * SearchEverywhereLayout.NOTE_LINE
                drawTextV(g, text, places.x + 14, y, y + 15, if (s.noteWarn) AMBER else MUTED, em(10))
            }
            if (source.id in expanded) s.cards.forEachIndexed { c, card ->
                val y = top + box.cardsTop + c * (SearchEverywhereLayout.CARD_H + SearchEverywhereLayout.SECTION_GAP)
                drawCard(g, source, card, Rect(places.x, y, places.right - 2, y + SearchEverywhereLayout.CARD_H), placesClip)
            }
        }
        endClip(g)
        if (independent) drawScrollbar(g, mx, my, placesScroll, places.x, places.y, places.right + 6, places.bottom)
        val noteTop = if (independent) inner.bottom - noteLines.size * SearchEverywhereLayout.NOTE_LINE else places.bottom + 10
        clip(g, col)
        noteLines.forEachIndexed { i, line ->
            drawTextV(g, line, inner.x, noteTop + i * 15, noteTop + i * 15 + 15, if (stale) AMBER else FAINT, em(10))
        }
        endClip(g)
        return SearchEverywhereLayout.PLACES_TOP + contentH + 10 + noteLines.size * 15 + SearchEverywhereLayout.PAD_Y
    }

    private fun drawSectionHeader(g: GuiGraphicsExtractor, source: SearchSource, s: SearchSection, row: SearchRow, r: Rect, clip: IntArray) {
        if (r.bottom <= clip[1] || r.y >= clip[3]) return
        val open = source.id in expanded
        val hover = r.contains(mx, my) && inside(clip)
        roundRect(g, r.x, r.y, r.right, r.bottom, 0xFF303030.toInt(), SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, if (hover) 0xFF252525.toInt() else 0xFF1F1F1F.toInt(), SoulRenderPipelines.ROUND_SMALL)
        // The header toggles; the action button registers after it and wins its own clicks.
        clippedRegion(r.x, r.y, r.right, r.bottom, clip) { if (!expanded.add(source.id)) expanded.remove(source.id) }
        val cy = r.y + 18
        val cx = r.x + 10f
        if (open) fillPolygon(g, floatArrayOf(cx, cy - 2.5f, cx + 8f, cy - 2.5f, cx + 4f, cy + 2.5f), 0xFF888888.toInt())
        else fillPolygon(g, floatArrayOf(cx + 1.5f, cy - 4f, cx + 6.5f, cy.toFloat(), cx + 1.5f, cy + 4f), 0xFF888888.toInt())
        dot(g, r.x + 23, cy, 7, source.color)
        var x = tracked(g, source.label.uppercase(), r.x + 37, r.y, r.bottom, 10, 0xFFAAAAAA.toInt()) + 11
        val total = SearchItems.number(row.total(listOf(source.id)))
        drawTextV(g, total, x, r.y, r.bottom, 0xFFDDDDDD.toInt(), em(12))
        x += textWidth(total, em(12)) + 11
        val actionW = s.action?.let { button(g, r.right - 11, r.y + 6, 24, it, clip) + 8 } ?: 0
        drawTextV(g, ellipsize(s.places, (r.right - 11 - actionW - x).coerceAtLeast(1), em(10)), x, r.y, r.bottom, FAINT, em(10))
    }

    private fun drawCard(g: GuiGraphicsExtractor, source: SearchSource, card: HitCard, r: Rect, clip: IntArray) {
        if (r.bottom <= clip[1] || r.y >= clip[3]) return
        roundRect(g, r.x, r.y, r.right, r.bottom, if (card.stale) 0xFF494030.toInt() else 0xFF303030.toInt(), SoulRenderPipelines.ROUND_SMALL)
        roundRect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, CARD_BG, SoulRenderPipelines.ROUND_SMALL)
        val badgeW = max(17, textWidth(card.badge, em(9)) + 6)
        roundRect(g, r.x + 11, r.y + 10, r.x + 11 + badgeW, r.y + 27, source.badgeBack, SoulRenderPipelines.ROUND_SMALL)
        drawTextV(g, card.badge, r.x + 11 + (badgeW - textWidth(card.badge, em(9))) / 2, r.y + 10, r.y + 27, source.badgeText, em(9))
        var right = r.right - 11
        card.action?.let { right -= button(g, right, r.y + 8, 22, it, clip) + 8 }
        val count = SearchItems.number(card.count)
        val countW = textWidth(count, em(14))
        drawTextV(g, count, right - countW, r.y + 8, r.y + 30, WHITE, em(14))
        val titleX = r.x + 11 + badgeW + 6
        drawTextV(g, ellipsize(card.title, (right - countW - 8 - titleX).coerceAtLeast(1), em(12)), titleX, r.y + 9, r.y + 28, 0xFFC9C9C9.toInt(), em(12))
        val trailingW = textWidth(card.trailing, em(10))
        val subRight = r.right - 11 - trailingW - 6
        var x = r.x + 11
        val detail = ellipsize(card.detail, (subRight - x).coerceAtLeast(1), em(10))
        drawTextV(g, detail, x, r.y + 34, r.y + 50, 0xFF8B8B8B.toInt(), em(10))
        x += textWidth(detail, em(10)) + 6
        if (card.dim.isNotEmpty() && x < subRight) {
            val dim = ellipsize(card.dim, subRight - x, em(10))
            drawTextV(g, dim, x, r.y + 34, r.y + 50, 0xFF626262.toInt(), em(10))
            x += textWidth(dim, em(10)) + 6
        }
        if (card.warn.isNotEmpty() && x < subRight) drawTextV(g, ellipsize(card.warn, subRight - x, em(10)), x, r.y + 34, r.y + 50, AMBER, em(10))
        drawTextV(g, card.trailing, r.right - 11 - trailingW, r.y + 34, r.y + 50, if (card.stale) AMBER else FAINT, em(10))
        if (card.tooltip.isNotEmpty() && r.contains(mx, my) && inside(clip)) tooltip(r.x, r.y, r.right, r.bottom, card.tooltip)
    }

    /** Nothing selected: the sources overview (mock `.start`). */
    private fun drawStart(g: GuiGraphicsExtractor, col: Rect, active: List<SearchSource>): Int {
        if (!visible(col)) return SearchEverywhereLayout.COMPACT_DETAIL
        val section = clip(g, col)
        val inner = SearchEverywhereLayout.inner(col)
        tracked(g, "SEARCH EVERYWHERE", inner.x, inner.y, inner.y + 15, 9, FAINT)
        drawTextV(g, "Find it, wherever it is.", inner.x, inner.y + 19, inner.y + 51, WHITE, em(20))
        val lines = wrapText("Type an item name, then choose a result to see every chest and storage page it was last seen in.", inner.width, em(12))
        lines.forEachIndexed { i, line ->
            val top = inner.y + 57 + (i * 19.2f).roundToInt()
            drawTextV(g, line, inner.x, top, top + 19, 0xFF999999.toInt(), em(12))
        }
        var y = inner.y + 57 + (lines.size * 19.2f).roundToInt() + 18
        for (source in active) {
            val coverage = source.coverage()
            val r = Rect(inner.x, y, inner.right, y + SearchEverywhereLayout.SRC_ROW_H)
            val hover = r.contains(mx, my) && inside(section)
            roundRect(g, r.x, r.y, r.right, r.bottom, 0xFF303030.toInt(), SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, if (hover) 0xFF252525.toInt() else CARD_BG, SoulRenderPipelines.ROUND_SMALL)
            dot(g, r.x + 12, r.y + 30, 9, source.color)
            drawTextV(g, source.label, r.x + 31, r.y + 10, r.y + 31, WHITE, em(13))
            drawTextV(g, ellipsize(coverage.long, r.right - 12 - r.x - 31, em(10)), r.x + 31, r.y + 34, r.y + 50, if (coverage.none) AMBER else MUTED, em(10))
            clippedRegion(r.x, r.y, r.right, r.bottom, section) { filter = source.id; listScroll.pos = 0f; search.focused = true }
            y += SearchEverywhereLayout.SRC_ROW_H + SearchEverywhereLayout.SRC_ROW_GAP
        }
        val honest = listOf("Only what you or a sharing co-op client have seen.", "No scanning. No automatic opening.")
        val honestTop = max(y + 6, inner.bottom - 32)
        honest.forEachIndexed { i, line ->
            drawTextV(g, line, (inner.x + inner.right) / 2 - textWidth(line, em(10)) / 2, honestTop + i * 16, honestTop + i * 16 + 16, 0xFF626262.toInt(), em(10))
        }
        endClip(g)
        return honestTop + 32 + SearchEverywhereLayout.PAD_Y - col.y
    }

    // ---------------------------------------------------------------- input

    override fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (selectedKey != null) selectedKey = null else onClose()
            return true
        }
        if (key == GLFW.GLFW_KEY_TAB) {
            val ids = listOf("all") + SearchEverywhere.active().map { it.id }
            val shift = event.modifiers() and GLFW.GLFW_MOD_SHIFT != 0
            filter = ids[(ids.indexOf(filter).coerceAtLeast(0) + if (shift) ids.size - 1 else 1) % ids.size]
            listScroll.pos = 0f
            return true
        }
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            val active = SearchEverywhere.active()
            SearchEverywhere.rows(active)
            SearchEverywhere.index.search(search.text, filtered(active).map { it.id }).firstOrNull()?.let {
                selectedKey = it.key
                placesScroll.pos = 0f
            }
            return true
        }
        if (key == GLFW.GLFW_KEY_SLASH && !search.focused) { search.focused = true; return true }
        if (search.focused) return search.keyPressed(event, Minecraft.getInstance().keyboardHandler)
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean =
        if (search.focused) search.charTyped(event) else super.charTyped(event)

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        val scale = SoulUi.factor()
        val target = scrollTargetAt((mouseX / scale).roundToInt(), (mouseY / scale).roundToInt()) ?: return false
        target.state.scrollBy((-verticalAmount * 30).toFloat())
        return true
    }

    companion object {
        private val BG = 0xF71A1A1A.toInt()
        private val EDGE = 0xFF333333.toInt()
        private val HEAD_BG = 0xFF161616.toInt()
        private val SCOPE_BG = 0xFF1D1D1D.toInt()
        private val DETAIL_BG = 0x88181818.toInt()
        private val CARD_BG = 0x88222222.toInt()
        private val DIVIDER = 0xFF2A2A2A.toInt()
        private val WHITE = 0xFFEEEEEE.toInt()
        private val MUTED = 0xFF888888.toInt()
        private val FAINT = 0xFF777777.toInt()
        private val AMBER = 0xFFBC9A61.toInt()
        private val AWAY = 0xFFD0B986.toInt()
        private fun em(size: Int) = SearchItems.em(size)
    }
}
