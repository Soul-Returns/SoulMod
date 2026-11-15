package com.soulreturns.features.storage

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import com.soulreturns.features.islandchests.searchKey
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.search.SearchItems
import com.soulreturns.util.KeyCombo
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.inventory.ContainerScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.core.component.DataComponents
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.player.Inventory
import net.minecraft.world.inventory.ChestMenu
import net.minecraft.world.inventory.Slot
import net.minecraft.world.item.ItemStack
import org.lwjgl.glfw.GLFW
import java.util.IdentityHashMap
import kotlin.math.max
import kotlin.math.roundToInt

/** Only real server slots participate in native container input; the wall's other cards are pictures. */
class StorageOverlayScreen(menu: ChestMenu, inventory: Inventory, title: Component) : ContainerScreen(menu, inventory, title) {
    private class ViewState(val scope: String?) {
        val search = SoulTextField()
        val vertical = SoulScreenBase.ScrollState()
        val horizontal = SoulScreenBase.ScrollState()
        val dock = SoulScreenBase.ScrollState()
        var filter = "all"
        var filtersExpanded = false
        var showEmpty = false
    }
    private data class WrappedText(val width: Int, val size: Int, val lines: List<String>)

    companion object {
        private var remembered: ViewState? = null
        private val formatting = Regex("§.")
        private val WHITE = 0xFFEEEEEE.toInt()
        private val MUTED = 0xFF888888.toInt()
        private val BLUE = 0xFF72BBFF.toInt()
        private val EDGE = 0xFF333333.toInt()
        private val AMBER = 0xFFD4B77A.toInt()
        private val SURFACE = 0xFF181818.toInt()
        private val SURFACE_EDGE = 0xFF444444.toInt()
        private val FILTER_IDS = arrayOf("all", "ender", "backpack")
        private val FILTER_LABELS = arrayOf("All", "Ender Chest", "Backpacks")
        private fun em(size: Int) = size * MsdfFont.LEGACY_EM
    }

    private val state = StorageOverlay.scopeKey().let { key ->
        remembered?.takeIf { key != null && it.scope == key } ?: ViewState(key).also { remembered = it }
    }
    private val ui = Chrome()
    private val projected = arrayOfNulls<StorageOverlayLayout.Cell>(menu.slots.size)
    private val nativeCoordinates = HashMap<Long, Int>(menu.slots.size)
    private val searchText = IdentityHashMap<ItemStack, String>()
    private var indexedPages: List<StoragePageView>? = null
    private var pages: List<StoragePageView> = emptyList()
    private var visiblePages: List<StoragePageView> = emptyList()
    private var cards: List<StorageOverlayLayout.Card> = emptyList()
    private var occupancy: List<String> = emptyList()
    private var pageCountLabel = ""
    private var nativeItemScale = 1f
    private var viewport = StorageOverlayLayout.Rect(0, 0, 1, 1)
    private val dockClip = IntArray(4)
    private val buttonWidths = intArrayOf(64, 110, 100)
    private var frame = StorageOverlayLayout.frame(1, 1, 3, false, buttonWidths)
    private var query = ""
    private var message = ""
    private var nativeGesture = false
    private var revealedOpenPage = false
    private var chromeGesture = false
    private var suppressSearchSlash = false
    private var searchMark: StorageOverlay.SearchMark? = null
    private var markKey: StorageOverlay.SearchMark? = null
    private val markCache = IdentityHashMap<ItemStack, Boolean>()
    private var markedStacks = 0
    private var mx = 0.0
    private var my = 0.0
    private var wallHeight = 0
    private var layoutPages: List<StoragePageView>? = null
    private var layoutQuery = ""
    private var layoutFilter = ""
    private var layoutFiltersExpanded = false
    private var layoutShowEmpty = false
    private var layoutWidth = -1
    private var layoutHeight = -1
    private var layoutColumns = -1
    private var layoutScale = -1f
    private var layoutX = -1
    private var layoutY = -1
    private var layoutDock = -1

    init {
        for (slot in menu.slots) nativeCoordinates[coordinateKey(slot.x, slot.y)] = slot.index
    }

    override fun init() {
        super.init()
        ui.init(width, height)
        layout()
    }

    private fun coordinateKey(x: Int, y: Int) = (x.toLong() shl 32) xor (y.toLong() and 0xffffffffL)
    private fun contextReady() = cfg.storageOverlay.enabled && minecraft.player?.containerMenu === menu
    private fun ready() = contextReady() && StorageOverlay.interactionReady(menu) && StorageOverlay.pendingPage() == null
    private fun filterLabel() = when (state.filter) { "ender" -> "Ender Chest"; "backpack" -> "Backpacks"; else -> "All" }
    private fun filtersLabel() = if (state.filter == "all") "Filters" else "Filters · ${filterLabel()}"
    private fun emptyLabel() = if (state.showEmpty) "Hide empty & locked" else "Empty & locked"

    private fun matches(stack: ItemStack): Boolean {
        if (stack.isEmpty) return false
        if (query.isEmpty()) return true
        val text = searchText.getOrPut(stack) {
            buildString {
                append(stack.hoverName.string)
                for (line in stack.get(DataComponents.LORE)?.lines().orEmpty()) { append('\n'); append(line.string) }
            }.replace(formatting, "")
        }
        return text.contains(query, ignoreCase = true)
    }

    private fun layout() {
        val nextPages = StorageOverlay.pages()
        val nextQuery = state.search.text.trim()
        val scale = SoulUi.factor()
        val uiWidth = (width / scale).toInt()
        val uiHeight = (height / scale).toInt()
        val columns = cfg.storageOverlay.columns
        if (layoutPages === nextPages && layoutQuery == nextQuery && layoutFilter == state.filter &&
            layoutFiltersExpanded == state.filtersExpanded && layoutShowEmpty == state.showEmpty &&
            layoutWidth == uiWidth && layoutHeight == uiHeight && layoutColumns == columns && layoutScale == scale &&
            layoutX == state.horizontal.pos.roundToInt() && layoutY == state.vertical.pos.roundToInt() &&
            layoutDock == state.dock.pos.roundToInt()) return
        pages = nextPages
        pageCountLabel = "${pages.size} pages"
        nativeItemScale = 1f / scale
        if (indexedPages !== pages) { searchText.clear(); indexedPages = pages }
        query = nextQuery
        visiblePages = pages.filter { page ->
            (state.filter == "all" || page.id.kind == state.filter) &&
                (query.isEmpty() || page.items?.any(::matches) == true)
        }
        val fittingColumns = StorageOverlayLayout.columnsFor(uiWidth, columns, scale)
        val slotSize = 18f / scale
        val rowTops = IntArray((visiblePages.size + fittingColumns - 1) / fittingColumns)
        var top = 0
        for (row in rowTops.indices) {
            rowTops[row] = top
            var tallest = 0
            for (i in row * fittingColumns until minOf(visiblePages.size, (row + 1) * fittingColumns)) {
                val page = visiblePages[i]
                tallest = max(tallest, StorageOverlayLayout.cardHeight(page.rows, page.items != null, slotSize))
            }
            top += tallest + StorageOverlayLayout.GAP
        }
        wallHeight = if (visiblePages.isEmpty()) 110 else top - StorageOverlayLayout.GAP
        if (state.showEmpty) wallHeight += 66
        buttonWidths[0] = ui.measure(filtersLabel(), 12) + 24
        buttonWidths[1] = ui.measure("Manage storage", 12) + 24
        buttonWidths[2] = ui.measure(emptyLabel(), 12) + 24
        frame = StorageOverlayLayout.frame(uiWidth, uiHeight, columns,
            state.filtersExpanded, buttonWidths, scale, wallHeight, state.dock.pos.roundToInt())
        state.dock.content = frame.dockContentHeight
        state.dock.view = frame.dockArea.height
        val previousDock = state.dock.pos.roundToInt()
        state.dock.clamp()
        if (previousDock != state.dock.pos.roundToInt()) {
            frame = StorageOverlayLayout.frame(uiWidth, uiHeight, columns,
                state.filtersExpanded, buttonWidths, scale, wallHeight, state.dock.pos.roundToInt())
        }
        viewport = StorageOverlayLayout.Rect(0, 0, uiWidth, uiHeight)
        dockClip[0] = max(0, frame.dockArea.x); dockClip[1] = frame.dockArea.y
        dockClip[2] = minOf(uiWidth, frame.dockArea.right); dockClip[3] = frame.dockArea.bottom
        state.horizontal.content = frame.wallWidth + 24
        state.horizontal.view = frame.wall.width
        state.horizontal.clamp()
        state.vertical.content = wallHeight + StorageOverlayLayout.WALL_INSET * 2
        state.vertical.view = frame.storageClip.height + 2
        state.vertical.clamp()
        val newlyOpen = visiblePages.indexOfFirst { it.open }
        if (!revealedOpenPage && newlyOpen >= 0 && frame.wall.height > 0) {
            val rowTop = rowTops[newlyOpen / frame.columns]
            val columnLeft = newlyOpen % frame.columns * (frame.cardWidth + StorageOverlayLayout.GAP)
            val topInView = rowTop - state.vertical.pos
            val leftInView = columnLeft + 12 - state.horizontal.pos
            val page = visiblePages[newlyOpen]
            val cardHeight = StorageOverlayLayout.cardHeight(page.rows, page.items != null, frame.slotSize)
            if (topInView < 0 || topInView + cardHeight > frame.storageClip.height) state.vertical.pos = rowTop.toFloat()
            if (leftInView < 0 || leftInView + frame.cardWidth > frame.storageClip.width) state.horizontal.pos = columnLeft.toFloat()
            state.vertical.clamp()
            state.horizontal.clamp()
            revealedOpenPage = true
        }
        cards = visiblePages.mapIndexed { index, page ->
            StorageOverlayLayout.card(frame, index % frame.columns, rowTops[index / frame.columns], page.rows,
                page.items != null, state.horizontal.pos.roundToInt(), state.vertical.pos.roundToInt())
        }
        occupancy = visiblePages.map { page ->
            val items = page.items
            if (items == null) "Unknown" else if (query.isNotEmpty()) "${items.count(::matches)} matches"
            else "${items.count { !it.isEmpty }}/${page.rows * 9}"
        }
        ui.invalidateText()
        projected.fill(null)
        val active = visiblePages.indexOfFirst { it.open }
        if (active >= 0) {
            val page = visiblePages[active]
            for (i in 0 until page.rows * 9) if (9 + i < menu.rowCount * 9) projected[9 + i] = cards[active].cell(i)
        }
        for (i in 0 until 36) {
            val index = menu.rowCount * 9 + i
            if (index < projected.size) projected[index] = frame.playerCell(i)
        }
        layoutPages = pages; layoutQuery = query; layoutFilter = state.filter
        layoutFiltersExpanded = state.filtersExpanded; layoutShowEmpty = state.showEmpty
        layoutWidth = uiWidth; layoutHeight = uiHeight; layoutColumns = columns
        layoutScale = scale
        layoutX = state.horizontal.pos.roundToInt(); layoutY = state.vertical.pos.roundToInt()
        layoutDock = state.dock.pos.roundToInt()
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractTransparentBackground(g)
    }

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        layout()
        val scale = SoulUi.factor()
        mx = mouseX / scale.toDouble(); my = mouseY / scale.toDouble()
        hoveredSlot = if (ready()) menu.slots.firstOrNull { it.isActive && isHovering(it.x, it.y, 16, 16, mouseX.toDouble(), mouseY.toDouble()) } else null
        ui.start()
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        ui.clip(g, viewport)
        searchMark = StorageOverlay.searchMark()
        markedStacks = searchMark?.let(::countMarked) ?: 0
        if (searchMark != null) drawSearchBar(g, searchMark!!) else drawHeader(g)
        drawWall(g, mouseX, mouseY)
        drawInventory(g, mouseX, mouseY)
        drawControls(g)
        ui.unclip(g)
        g.pose().popMatrix()
    }

    private fun drawHeader(g: GuiGraphicsExtractor) {
        val h = frame.header
        val titleWidth = ui.measure("Storage", 18)
        val count = pageCountLabel
        val countWidth = ui.measure(count, 11)
        val pillWidth = titleWidth + 9 + countWidth + 28
        val x = h.x + (h.width - pillWidth) / 2
        ui.surface(g, StorageOverlayLayout.Rect(x, h.y, pillWidth, h.height), pill = true)
        ui.text(g, "Storage", x + 14, h.y + 4, WHITE, 18, true)
        ui.text(g, count, x + 14 + titleWidth + 9, h.y + 9, MUTED, 11)
    }

    private fun markMatches(stack: ItemStack): Boolean {
        val mark = searchMark ?: return false
        if (stack.isEmpty) return false
        if (markKey !== mark) { markCache.clear(); markKey = mark }
        return markCache.getOrPut(stack) { SearchItems.of(stack)?.searchKey() == mark.itemKey }
    }

    private fun countMarked(mark: StorageOverlay.SearchMark): Int {
        val page = pages.firstOrNull { it.id == mark.page } ?: return 0
        val live = page.open && ready()
        val items = page.items ?: return 0
        return (0 until page.rows * 9).count { slot ->
            markMatches(if (live) menu.slots.getOrNull(9 + slot)?.item ?: ItemStack.EMPTY else items.getOrNull(slot) ?: ItemStack.EMPTY)
        }
    }

    /** Opened from Search everywhere: the title pill names the item and how many stacks are marked. */
    private fun drawSearchBar(g: GuiGraphicsExtractor, mark: StorageOverlay.SearchMark) {
        val h = frame.header
        val name = SearchItems.name(mark.item)
        val label = pages.firstOrNull { it.id == mark.page }?.label ?: "this page"
        val detail = "${SearchItems.number(mark.count)} in $label · $markedStacks ${if (markedStacks == 1) "stack" else "stacks"} marked"
        val keys = "${KeyCombo.label(cfg.islandChests.searchKey)} Back to search · Esc Close"
        val nameW = ui.measure(name, 13)
        val detailW = ui.measure(detail, 11)
        val keysW = ui.measure(keys, 10)
        val pillW = minOf(h.width - 24, 14 + 16 + 8 + nameW + 10 + detailW + 14 + keysW + 14)
        val x = h.x + (h.width - pillW) / 2
        ui.surface(g, StorageOverlayLayout.Rect(x, h.y, pillW, h.height), pill = true)
        SearchItems.icon(g, mark.item, x + 14, h.y + 5, 16)
        var cx = x + 38
        ui.text(g, ui.shorten(name, x + pillW - 14 - cx, 13), cx, h.y + 7, SearchItems.color(mark.item), 13, true)
        cx += nameW + 10
        if (cx < x + pillW - 14) ui.text(g, ui.shorten(detail, x + pillW - 14 - cx, 11), cx, h.y + 9, 0xFFB9D5F8.toInt(), 11)
        cx += detailW + 14
        if (cx < x + pillW - 14) ui.text(g, ui.shorten(keys, x + pillW - 14 - cx, 10), cx, h.y + 10, MUTED, 10)
    }

    private fun drawWall(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val wall = frame.wall
        if (frame.storageClip.height <= 0 || frame.storageClip.width <= 0) return
        ui.surface(g, wall)
        ui.clip(g, frame.storageClip)
        val pending = StorageOverlay.pendingPage()
        val interactive = ready()
        for (i in visiblePages.indices) {
            val page = visiblePages[i]
            val card = cards[i]
            if (!card.bounds.intersects(wall)) continue
            val hover = card.bounds.contains(mx.toDouble(), my.toDouble()) && frame.storageContains(mx.toDouble(), my.toDouble())
            val opening = pending == page.id
            val edge = if (opening) 0xFFBCAA77.toInt() else if (page.open) BLUE else if (hover) 0xFF555555.toInt() else EDGE
            ui.box(g, card.bounds, edge, true)
            val b = card.bounds
            ui.box(g, StorageOverlayLayout.Rect(b.x + 1, b.y + 1, b.width - 2, b.height - 2), if (page.open) 0xFF202A39.toInt() else if (hover) 0xFF252525.toInt() else 0xFF202020.toInt(), true)
            val status = ui.shorten(if (opening) "OPENING…" else if (page.open) "OPEN" else "", b.width - 20, 10)
            val statusWidth = if (status.isEmpty()) 0 else ui.measure(status, 10) + 8
            val count = occupancy[i]
            val countWidth = minOf(ui.measure(count, 10), max(0, b.width - statusWidth - 40))
            ui.text(g, ui.shorten(page.label, b.width - statusWidth - countWidth - 28, 12), b.x + 10, b.y + 11, 0xFFDDDDDD.toInt(), 12, true)
            if (countWidth > 0) ui.text(g, ui.shorten(count, countWidth, 10), b.right - 10 - statusWidth - countWidth, b.y + 13, MUTED, 10)
            if (status.isNotEmpty()) ui.text(g, status, b.right - statusWidth, b.y + 13, if (opening) AMBER else BLUE, 10)
            val items = page.items
            if (items == null) {
                g.fill(card.grid.x, card.grid.y, card.grid.right, card.grid.bottom, 0xFF191919.toInt())
                val unknownY = card.grid.y + card.grid.height / 2
                ui.center(g, "Not viewed yet", b.x + b.width / 2, unknownY - 13, MUTED, 13)
                ui.center(g, "Click to open", b.x + b.width / 2, unknownY + 7, 0xFF666666.toInt(), 11)
            } else {
                for (slot in 0 until page.rows * 9) {
                    val cell = card.cell(slot)
                    if (!cell.intersects(wall)) continue
                    val stack = items.getOrNull(slot) ?: ItemStack.EMPTY
                    val realSlot = if (page.open && interactive) menu.slots.getOrNull(9 + slot) else null
                    drawCell(g, cell, realSlot?.item ?: stack, realSlot, mouseX, mouseY, page.open, markPage = page.id == searchMark?.page)
                }
            }
        }
        if (visiblePages.isEmpty()) {
            ui.center(g, "No remembered matches.", wall.x + wall.width / 2, wall.y + 45, 0xFFAAAAAA.toInt(), 13)
            ui.center(g, "Unopened pages are not searched.", wall.x + wall.width / 2, wall.y + 68, 0xFF777777.toInt(), 10)
        }
        if (state.showEmpty) {
            val y = wall.y + StorageOverlayLayout.WALL_INSET + wallHeight - 44 - state.vertical.pos.roundToInt()
            val lines = ui.wrap(StorageOverlay.selectorSummary(), max(1, wall.width - 52), 12)
            for ((i, line) in lines.withIndex()) ui.center(g, line, wall.x + wall.width / 2, y + i * 15, MUTED, 12)
        }
        ui.unclip(g)
        ui.clip(g, viewport)
        ui.bars(g, mx.roundToInt(), my.roundToInt(), state.vertical, state.horizontal,
            StorageOverlayLayout.Rect(wall.x, wall.y + 4, wall.width - 3, wall.height - 6))
    }

    private fun drawInventory(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val d = frame.dock
        if (frame.inventoryClip.width <= 0 || frame.inventoryClip.height <= 0) return
        ui.clip(g, frame.inventoryClip)
        ui.surface(g, d)
        ui.text(g, "Your inventory", d.x + 10, d.y + 10, 0xFFBBBBBB.toInt(), 11, true)
        val interactive = ready()
        for (i in 0 until 36) {
            if (!frame.playerCell(i).intersects(frame.inventoryClip)) continue
            val slot = menu.slots.getOrNull(menu.rowCount * 9 + i)
            val stack = if (interactive) slot?.item else minecraft.player?.inventory?.getItem(if (i < 27) i + 9 else i - 27)
            drawCell(g, frame.playerCell(i), stack ?: ItemStack.EMPTY, slot.takeIf { interactive }, mouseX, mouseY, true, searchable = false)
        }
        ui.unclip(g)
        ui.clip(g, viewport)
    }

    private fun drawCell(g: GuiGraphicsExtractor, cell: StorageOverlayLayout.Cell, stack: ItemStack, realSlot: Slot?, mouseX: Int, mouseY: Int, open: Boolean, searchable: Boolean = true, markPage: Boolean = false) {
        val hovered = cell.contains(mx.toDouble(), my.toDouble()) &&
            if (searchable) frame.storageContains(mx.toDouble(), my.toDouble()) else frame.nativeContains(true, mx.toDouble(), my.toDouble())
        val searching = searchable && query.isNotEmpty()
        val match = searching && matches(stack)
        val border = if (match) BLUE else if (hovered) { if (open) 0xFFB1D6FF.toInt() else 0xFF777777.toInt() } else 0xFF353535.toInt()
        g.pose().pushMatrix()
        g.pose().translate(cell.x, cell.y)
        // Cancel only Soul's pose scale: items, decorations and frames stay at native GUI scale.
        g.pose().scale(nativeItemScale, nativeItemScale)
        g.fill(0, 0, 18, 18, border)
        g.fill(1, 1, 17, 17, if (match) 0xFF253B57.toInt() else if (hovered) { if (open) 0xFF384B66.toInt() else 0xFF363636.toInt() } else 0xFF292929.toInt())
        if (markPage && markMatches(stack)) {
            // Search everywhere's match: the island chest locator's 16×16 fill and 1 px inset edge, behind the item.
            g.fill(1, 1, 17, 17, 0x4072BBFF)
            g.fill(1, 1, 17, 2, BLUE)
            g.fill(1, 16, 17, 17, BLUE)
            g.fill(1, 2, 2, 16, BLUE)
            g.fill(16, 2, 17, 16, BLUE)
        }
        g.pose().pushMatrix()
        g.pose().translate(1f, 1f)
        if (realSlot != null) {
            g.pose().translate(-realSlot.x.toFloat(), -realSlot.y.toFloat())
            super.extractSlot(g, realSlot, mouseX, mouseY)
        } else if (!stack.isEmpty) {
            g.fakeItem(stack, 0, 0)
            g.itemDecorations(font, stack, 0, 0)
        }
        g.pose().popMatrix()
        if (searching && !stack.isEmpty && !match) g.fill(1, 1, 17, 17, 0xBF292929.toInt())
        g.pose().popMatrix()
    }

    private fun drawControls(g: GuiGraphicsExtractor) {
        ui.clip(g, frame.dockArea)
        ui.field(g, frame.search, state.search)
        val bar = frame.filtersButton
        ui.surface(g, StorageOverlayLayout.Rect(bar.x, bar.y, frame.emptyButton.right - bar.x, bar.height))
        ui.segment(g, frame.filtersButton, filtersLabel(), state.filtersExpanded || state.filter != "all", first = true) {
            state.filtersExpanded = !state.filtersExpanded
        }
        ui.segment(g, frame.manageButton, "Manage storage", false) {
            if (menu.carried.isEmpty) StorageOverlay.manage() else message = "Place the held item before managing storage."
        }
        ui.segment(g, frame.emptyButton, emptyLabel(), state.showEmpty, last = true) {
            state.showEmpty = !state.showEmpty
        }
        if (state.filtersExpanded) {
            val f = frame.filters
            ui.surface(g, f)
            for (i in FILTER_IDS.indices) {
                val x = f.x + f.width * i / 3
                ui.segment(g, StorageOverlayLayout.Rect(x, f.y, f.x + f.width * (i + 1) / 3 - x, f.height),
                    FILTER_LABELS[i], state.filter == FILTER_IDS[i], first = i == 0, last = i == FILTER_IDS.lastIndex, size = 11) {
                    state.filter = FILTER_IDS[i]; state.vertical.pos = 0f
                }
            }
        }
        val hint = when {
            message.isNotEmpty() -> message
            StorageOverlay.pendingPage() != null -> "Waiting for the server…"
            StorageOverlay.overviewReady(menu) -> "Click a page to open it. Esc Close · / Search"
            !StorageOverlay.interactionReady(menu) -> "Waiting for the server…"
            !menu.carried.isEmpty -> "Holding ${menu.carried.hoverName.string}"
            query.isNotEmpty() -> "${visiblePages.size} matching pages. Unopened pages are not searched."
            else -> "Esc Close · / Search · Shift-click to transfer"
        }
        val lines = ui.wrap(hint, frame.notice.width - 16, 11)
        val shown = minOf(lines.size, (frame.notice.height - 8) / 13)
        if (shown > 0) {
            var pillWidth = 0
            for (i in 0 until shown) pillWidth = max(pillWidth, ui.measure(lines[i], 11))
            val n = frame.notice
            ui.surface(g, StorageOverlayLayout.Rect(n.x, n.y, pillWidth + 16, shown * 13 + 8), pill = true)
            for (i in 0 until shown) ui.text(g, lines[i], n.x + 8, n.y + 4 + i * 13, MUTED, 11)
        }
        ui.clip(g, viewport)
        ui.dockBar(g, mx.roundToInt(), my.roundToInt(), state.dock, frame.dockArea)
    }

    override fun extractCarriedItem(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!ready()) return
        super.extractCarriedItem(g, mouseX, mouseY)
    }

    override fun extractTooltip(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (ready()) super.extractTooltip(g, mouseX, mouseY)
        if (hoveredSlot != null || !menu.carried.isEmpty || !frame.storageContains(mx.toDouble(), my.toDouble())) return
        for (i in cards.indices) {
            val page = visiblePages[i]
            if (page.open && ready()) continue
            val items = page.items ?: continue
            for (j in 0 until minOf(items.size, page.rows * 9)) if (!items[j].isEmpty && cards[i].cell(j).contains(mx.toDouble(), my.toDouble())) {
                g.setTooltipForNextFrame(font, items[j], mouseX, mouseY)
                return
            }
        }
    }

    // Vanilla's private getHoveredSlot delegates here for clicks, drags and releases. Keep the
    // original Slot instances/indices and map the entire displayed cell, not its 16 px item icon.
    override fun isHovering(x: Int, y: Int, width: Int, height: Int, mouseX: Double, mouseY: Double): Boolean {
        if (!ready()) return false
        val index = nativeCoordinates[coordinateKey(x, y)] ?: return false
        val rect = projected.getOrNull(index) ?: return false
        val factor = SoulUi.factor()
        val px = mouseX / factor; val py = mouseY / factor
        if (!frame.nativeContains(index >= menu.rowCount * 9, px, py)) return false
        return rect.contains(px, py)
    }

    override fun hasClickedOutside(mouseX: Double, mouseY: Double, left: Int, top: Int): Boolean =
        !frame.panel.contains(mouseX / SoulUi.factor(), mouseY / SoulUi.factor())

    override fun mouseClicked(click: MouseButtonEvent, doubleClick: Boolean): Boolean {
        layout()
        nativeGesture = false
        chromeGesture = false
        val x = click.x() / SoulUi.factor(); val y = click.y() / SoulUi.factor()
        val overDock = frame.inventoryClip.contains(x, y)
        if (!overDock && ui.mouseClicked(click, doubleClick)) { chromeGesture = true; return true }
        state.search.focused = false
        if (StorageOverlay.pendingPage() != null || !contextReady()) return true
        if (frame.storageContains(x, y)) {
            for (i in cards.indices) if (cards[i].bounds.contains(x, y)) {
                if (!visiblePages[i].open) {
                    if (!menu.carried.isEmpty) message = "Place the held item before switching pages."
                    else if (click.button() == GLFW.GLFW_MOUSE_BUTTON_LEFT) { message = ""; StorageOverlay.requestOpen(visiblePages[i].id) }
                    return true
                }
                break
            }
        }
        if (!ready()) return true
        if (overDock && (0 until 36).none { frame.playerCell(it).contains(x, y) }) return true
        message = ""
        nativeGesture = true
        return super.mouseClicked(click, doubleClick)
    }

    override fun mouseDragged(click: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        layout()
        if (chromeGesture) {
            ui.mouseDragged(click, dragX, dragY)
            return true
        }
        if (!ready()) return true
        if (!nativeGesture) return true
        return super.mouseDragged(click, dragX, dragY)
    }

    override fun mouseReleased(click: MouseButtonEvent): Boolean {
        if (chromeGesture) { chromeGesture = false; ui.mouseReleased(click); return true }
        if (!nativeGesture || !ready()) { nativeGesture = false; return true }
        nativeGesture = false
        layout()
        return super.mouseReleased(click)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        layout()
        val x = mouseX / SoulUi.factor(); val y = mouseY / SoulUi.factor()
        if (ready() && super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)) return true
        if (frame.dockArea.contains(x, y)) {
            if (state.dock.max > 0) state.dock.scrollBy((-scrollY * 38).toFloat())
            return true
        }
        if (!frame.wall.contains(x, y)) return false
        if (scrollX != 0.0) state.horizontal.scrollBy((-scrollX * 38).toFloat())
        else state.vertical.scrollBy((-scrollY * 38).toFloat())
        return true
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        suppressSearchSlash = false
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (state.search.text.isNotEmpty()) { state.search.set(""); state.search.focused = false; state.vertical.pos = 0f; return true }
            onClose(); return true
        }
        if (!cfg.storageOverlay.enabled) return true
        if (state.search.focused) { state.search.keyPressed(event, minecraft.keyboardHandler); state.vertical.pos = 0f; return true }
        if (event.key() == GLFW.GLFW_KEY_SLASH) { state.search.focused = true; suppressSearchSlash = true; return true }
        if (searchMark != null && SearchEverywhere.matchesBinding(event)) {
            onClose()
            if (minecraft.player?.containerMenu !== menu) SearchEverywhere.requestFromScreen()
            return true
        }
        if (!ready()) return true
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (suppressSearchSlash) {
            suppressSearchSlash = false
            if (event.codepoint() == '/'.code) return true
        }
        if (state.search.focused) { state.search.charTyped(event); state.vertical.pos = 0f; return true }
        return if (ready()) super.charTyped(event) else true
    }

    override fun onClose() {
        if (minecraft.player?.containerMenu !== menu) { minecraft.gui.setScreen(null); return }
        if (!menu.carried.isEmpty) { message = "Place the held item first."; return }
        super.onClose()
    }

    override fun removed() {
        if (!StorageOverlay.preservingMenu(menu) && minecraft.player?.containerMenu === menu) super.removed()
    }

    /** Compose the shared Soul primitives without replacing the native container event handler. */
    private inner class Chrome : SoulScreenBase(Component.literal("Storage")) {
        private var clipping = false
        private val wrapped = HashMap<String, WrappedText>()
        fun invalidateText() { wrapped.clear() }
        fun start() { regions.clear(); scrollTargets.clear() }
        fun box(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect, color: Int, small: Boolean = false) {
            if (r.width > 0 && r.height > 0) roundRect(g, r.x, r.y, r.right, r.bottom, color, if (small) SoulRenderPipelines.ROUND_SMALL else SoulRenderPipelines.ROUND_LARGE)
        }
        fun clip(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect) {
            if (clipping) endClip(g)
            beginClip(g, r.x, r.y, r.right, r.bottom)
            clipping = true
        }
        fun unclip(g: GuiGraphicsExtractor) {
            if (clipping) endClip(g)
            clipping = false
        }
        fun text(g: GuiGraphicsExtractor, value: String, x: Int, y: Int, color: Int, size: Int, bold: Boolean = false) =
            drawText(g, value, x, y, color, em(size), if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular)
        fun center(g: GuiGraphicsExtractor, value: String, x: Int, y: Int, color: Int, size: Int) = drawTextCentered(g, value, x, y, color, em(size))
        fun measure(value: String, size: Int) = textWidth(value, em(size))
        fun shorten(value: String, width: Int, size: Int) = ellipsize(value, width.coerceAtLeast(1), em(size))
        fun wrap(value: String, width: Int, size: Int): List<String> {
            val previous = wrapped[value]
            if (previous != null && previous.width == width && previous.size == size) return previous.lines
            return wrapText(value, max(1, width), em(size)).also { wrapped[value] = WrappedText(width, size, it) }
        }
        fun field(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect, field: SoulTextField) =
            drawTextField(g, r.x, r.y, r.right, r.bottom, field, "Search all storage…", viewport = dockClip,
                inset = minOf(12, max(1, r.width / 4)), background = SURFACE, border = SURFACE_EDGE)
        /** Opaque surface shared by the panel, inventory card, title pill, controls and hint. */
        fun surface(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect, pill: Boolean = false) {
            if (r.width <= 0 || r.height <= 0) return
            val shape = if (pill) SoulRenderPipelines.ROUND_PILL else SoulRenderPipelines.ROUND_SMALL
            roundRect(g, r.x, r.y, r.right, r.bottom, SURFACE_EDGE, shape)
            roundRect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, SURFACE, shape)
        }
        /** One segment of a segmented bar drawn over a [surface]; non-first segments get a divider. */
        fun segment(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect, label: String, accent: Boolean,
                    first: Boolean = false, last: Boolean = false, size: Int = 12, action: () -> Unit) {
            val hovered = r.contains(mx.toDouble(), my.toDouble())
            if (accent || hovered) {
                box(g, StorageOverlayLayout.Rect(r.x + if (first) 2 else 1, r.y + 2, r.width - (if (first) 2 else 1) - (if (last) 2 else 1), r.height - 4),
                    if (accent) 0xFF3B82F6.toInt() else 0xFF353535.toInt(), true)
            }
            if (!first) g.fill(r.x, r.y + 1, r.x + 1, r.bottom - 1, 0xFF444444.toInt())
            val shown = shorten(label, r.width - 8, size)
            text(g, shown, r.x + (r.width - measure(shown, size)) / 2, r.y + (r.height - 11) / 2, WHITE, size)
            clippedRegion(r.x, r.y, r.right - 1, r.bottom - 1, dockClip, action)
        }
        fun bars(g: GuiGraphicsExtractor, mx: Int, my: Int, vertical: ScrollState, horizontal: ScrollState, r: StorageOverlayLayout.Rect) {
            drawScrollbar(g, mx, my, horizontal, r.x, r.y, r.right, r.bottom - 13, horizontal = true)
            drawScrollbar(g, mx, my, vertical, r.x, r.y, r.right, r.bottom)
        }
        fun dockBar(g: GuiGraphicsExtractor, mx: Int, my: Int, scroll: ScrollState, r: StorageOverlayLayout.Rect) {
            drawScrollbar(g, mx, my, scroll, r.x, r.y, r.right, r.bottom)
        }
    }
}
