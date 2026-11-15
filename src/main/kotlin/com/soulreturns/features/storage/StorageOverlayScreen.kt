package com.soulreturns.features.storage

import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
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
        var filter = "all"
        var filtersExpanded = false
        var showEmpty = false
    }

    companion object {
        private var remembered: ViewState? = null
        private val formatting = Regex("§.")
        private val WHITE = 0xFFEEEEEE.toInt()
        private val MUTED = 0xFF888888.toInt()
        private val BLUE = 0xFF72BBFF.toInt()
        private val EDGE = 0xFF333333.toInt()
        private val AMBER = 0xFFD4B77A.toInt()
        private fun em(size: Int) = size * MsdfFont.LEGACY_EM
    }

    private val state = StorageOverlay.scopeKey().let { key ->
        remembered?.takeIf { key != null && it.scope == key } ?: ViewState(key).also { remembered = it }
    }
    private val ui = Chrome()
    private val projected = arrayOfNulls<StorageOverlayLayout.Rect>(menu.slots.size)
    private val nativeCoordinates = HashMap<Long, Int>(menu.slots.size)
    private val searchText = IdentityHashMap<ItemStack, String>()
    private var indexedPages: List<StoragePageView>? = null
    private var pages: List<StoragePageView> = emptyList()
    private var visiblePages: List<StoragePageView> = emptyList()
    private var cards: List<StorageOverlayLayout.Card> = emptyList()
    private var frame = StorageOverlayLayout.frame(1, 1, 3, false, 64)
    private var query = ""
    private var message = ""
    private var nativeGesture = false
    private var revealedOpenPage = false
    private var chromeGesture = false
    private var suppressSearchSlash = false
    private var mx = 0
    private var my = 0
    private var wallHeight = 0
    private var layoutPages: List<StoragePageView>? = null
    private var layoutQuery = ""
    private var layoutFilter = ""
    private var layoutFiltersExpanded = false
    private var layoutShowEmpty = false
    private var layoutWidth = -1
    private var layoutHeight = -1
    private var layoutColumns = -1
    private var layoutX = -1
    private var layoutY = -1

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
            layoutWidth == uiWidth && layoutHeight == uiHeight && layoutColumns == columns &&
            layoutX == state.horizontal.pos.roundToInt() && layoutY == state.vertical.pos.roundToInt()) return
        pages = nextPages
        if (indexedPages !== pages) { searchText.clear(); indexedPages = pages }
        query = nextQuery
        visiblePages = pages.filter { page ->
            (state.filter == "all" || page.id.kind == state.filter) &&
                (query.isEmpty() || page.items?.any(::matches) == true)
        }
        frame = StorageOverlayLayout.frame(uiWidth, uiHeight, columns,
            state.filtersExpanded, ui.measure(filtersLabel(), 13) + 24)
        state.horizontal.content = frame.wallWidth + 32
        state.horizontal.view = frame.wall.width
        state.horizontal.clamp()
        val rowTops = IntArray((visiblePages.size + frame.columns - 1) / frame.columns)
        var top = 0
        for (row in rowTops.indices) {
            rowTops[row] = top
            var tallest = 0
            for (i in row * frame.columns until minOf(visiblePages.size, (row + 1) * frame.columns)) {
                val page = visiblePages[i]
                tallest = max(tallest, StorageOverlayLayout.cardHeight(page.rows, page.items != null, frame.slotSize))
            }
            top += tallest + StorageOverlayLayout.GAP
        }
        wallHeight = if (visiblePages.isEmpty()) 110 else top - StorageOverlayLayout.GAP
        if (state.showEmpty) wallHeight += 66
        state.vertical.content = wallHeight + 20 + StorageOverlayLayout.DOCK_HEIGHT + 10
        state.vertical.view = frame.wall.height
        state.vertical.clamp()
        val newlyOpen = visiblePages.indexOfFirst { it.open }
        if (!revealedOpenPage && newlyOpen >= 0 && frame.wall.height > 0) {
            val rowTop = rowTops[newlyOpen / frame.columns]
            val columnLeft = newlyOpen % frame.columns * (frame.cardWidth + StorageOverlayLayout.GAP)
            val topInView = rowTop + 10 - state.vertical.pos
            val leftInView = columnLeft + 16 - state.horizontal.pos
            val page = visiblePages[newlyOpen]
            val cardHeight = StorageOverlayLayout.cardHeight(page.rows, page.items != null, frame.slotSize)
            val cardLeft = frame.wall.x + columnLeft + 16 - state.horizontal.pos
            val behindDock = cardLeft < frame.dock.right && cardLeft + frame.cardWidth > frame.dock.x
            val visibleHeight = if (behindDock) max(0, frame.dock.y - frame.wall.y - 10) else frame.wall.height
            if (topInView < 0 || topInView + cardHeight > visibleHeight) state.vertical.pos = rowTop.toFloat()
            if (leftInView < 0 || leftInView + frame.cardWidth > frame.wall.width) state.horizontal.pos = columnLeft.toFloat()
            state.vertical.clamp()
            state.horizontal.clamp()
            revealedOpenPage = true
        }
        cards = visiblePages.mapIndexed { index, page ->
            StorageOverlayLayout.card(frame, index % frame.columns, rowTops[index / frame.columns], page.rows,
                page.items != null, state.horizontal.pos.roundToInt(), state.vertical.pos.roundToInt())
        }
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
        layoutX = state.horizontal.pos.roundToInt(); layoutY = state.vertical.pos.roundToInt()
    }

    override fun extractBackground(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        extractTransparentBackground(g)
    }

    override fun extractContents(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        layout()
        val scale = SoulUi.factor()
        mx = (mouseX / scale).toInt(); my = (mouseY / scale).toInt()
        hoveredSlot = if (ready()) menu.slots.firstOrNull { it.isActive && isHovering(it.x, it.y, 16, 16, mouseX.toDouble(), mouseY.toDouble()) } else null
        ui.start()
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        val p = frame.panel
        ui.box(g, p, EDGE)
        ui.box(g, StorageOverlayLayout.Rect(p.x + 1, p.y + 1, max(0, p.width - 2), max(0, p.height - 2)), 0xF51A1A1A.toInt())
        ui.clip(g, p)
        drawHeader(g)
        drawWall(g, mouseX, mouseY)
        drawInventory(g, mouseX, mouseY)
        drawFooter(g)
        ui.unclip(g)
        g.pose().popMatrix()
    }

    private fun drawHeader(g: GuiGraphicsExtractor) {
        val h = frame.header
        ui.text(g, "Storage", h.x + 18, h.y + 15, WHITE, 18, true)
        ui.text(g, "${pages.size} pages", h.x + 28 + ui.measure("Storage", 18), h.y + 19, MUTED, 11)
        ui.field(g, frame.search, state.search)
        ui.button(g, frame.filtersButton, filtersLabel(), state.filtersExpanded || state.filter != "all") { state.filtersExpanded = !state.filtersExpanded }
        ui.button(g, frame.closeButton, "×") { onClose() }
        g.fill(h.x, h.bottom, h.right, h.bottom + 1, 0xFF2A2A2A.toInt())
        if (state.filtersExpanded) {
            var x = frame.filters.x + 16
            for ((id, label) in listOf("all" to "All", "ender" to "Ender Chest", "backpack" to "Backpacks")) {
                val w = ui.measure(label, 13) + 24
                ui.button(g, StorageOverlayLayout.Rect(x, frame.filters.y + 8, w, 18), label, state.filter == id) {
                    state.filter = id; state.vertical.pos = 0f
                }
                x += w + 4
            }
            val hint = if (query.isEmpty()) "Click a page to open it here" else "${visiblePages.size} matching pages"
            if (frame.filters.right - x > ui.measure(hint, 12) + 20) ui.text(g, hint, frame.filters.right - ui.measure(hint, 12) - 16, frame.filters.y + 11, MUTED, 12)
        }
    }

    private fun drawWall(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val wall = frame.wall
        if (wall.height <= 0) return
        ui.clip(g, wall)
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
            val status = if (opening) "OPENING…" else if (page.open) "OPEN" else "PREVIEW"
            val statusWidth = ui.measure(status, 10)
            ui.text(g, ui.shorten(page.label, b.width - statusWidth - 30, 13), b.x + 9, b.y + 12, 0xFFDDDDDD.toInt(), 13, true)
            ui.text(g, status, b.right - 9 - statusWidth, b.y + 14, if (opening) AMBER else if (page.open) 0xFF83B7FF.toInt() else MUTED, 10)
            val items = page.items
            if (items == null) {
                g.fill(b.x + 9, card.grid.y, b.right - 9, card.grid.y + frame.slotSize * 5 + 4, 0xFF191919.toInt())
                val unknownY = card.grid.y + (frame.slotSize * 5 + 4) / 2
                ui.center(g, "Not viewed yet", b.x + b.width / 2, unknownY - 13, MUTED, 13)
                ui.center(g, "Click to open", b.x + b.width / 2, unknownY + 7, 0xFF666666.toInt(), 11)
            } else {
                for (slot in 0 until page.rows * 9) {
                    val cell = card.cell(slot)
                    if (!cell.intersects(wall)) continue
                    val stack = items.getOrNull(slot) ?: ItemStack.EMPTY
                    val realSlot = if (page.open && interactive) menu.slots.getOrNull(9 + slot) else null
                    drawCell(g, cell, realSlot?.item ?: stack, realSlot, mouseX, mouseY, page.open)
                }
            }
            val occupancy = if (items == null) "Contents unknown" else if (query.isNotEmpty()) "${items.count(::matches)} matching stacks" else "${items.count { !it.isEmpty }} / ${page.rows * 9} slots"
            val gridBottom = if (items == null) card.grid.y + frame.slotSize * 5 + 4 else card.grid.bottom
            ui.text(g, occupancy, b.x + 9, gridBottom + 8, MUTED, 11)
        }
        if (visiblePages.isEmpty()) {
            ui.center(g, "No remembered matches.", wall.x + wall.width / 2, wall.y + 45, 0xFFAAAAAA.toInt(), 13)
            ui.center(g, "Unopened pages are not searched.", wall.x + wall.width / 2, wall.y + 68, 0xFF777777.toInt(), 10)
        }
        if (state.showEmpty) {
            val y = wall.y + wallHeight - 44 - state.vertical.pos.roundToInt()
            val lines = ui.wrap(StorageOverlay.selectorSummary(), max(1, wall.width - 52), 12)
            for ((i, line) in lines.withIndex()) ui.center(g, line, wall.x + wall.width / 2, y + i * 15, MUTED, 12)
        }
        ui.unclip(g)
        ui.clip(g, frame.panel)
        ui.bars(g, mx, my, state.vertical, state.horizontal, wall)
    }

    private fun drawInventory(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        val d = frame.dock
        if (frame.inventoryClip.width <= 0 || frame.inventoryClip.height <= 0) return
        ui.clip(g, frame.inventoryClip)
        ui.box(g, d, 0xFF444444.toInt(), true)
        ui.box(g, StorageOverlayLayout.Rect(d.x + 1, d.y + 1, d.width - 2, d.height - 2), 0xFF181818.toInt(), true)
        ui.text(g, "Your inventory", d.x + 10, d.y + 10, 0xFFBBBBBB.toInt(), 11, true)
        val interactive = ready()
        for (i in 0 until 36) {
            val slot = menu.slots.getOrNull(menu.rowCount * 9 + i)
            val stack = if (interactive) slot?.item else minecraft.player?.inventory?.getItem(if (i < 27) i + 9 else i - 27)
            drawCell(g, frame.playerCell(i), stack ?: ItemStack.EMPTY, slot.takeIf { interactive }, mouseX, mouseY, true, searchable = false)
        }
        ui.unclip(g)
        ui.clip(g, frame.panel)
    }

    private fun drawCell(g: GuiGraphicsExtractor, cell: StorageOverlayLayout.Rect, stack: ItemStack, realSlot: Slot?, mouseX: Int, mouseY: Int, open: Boolean, searchable: Boolean = true) {
        val hovered = cell.contains(mx.toDouble(), my.toDouble()) &&
            if (searchable) frame.storageContains(mx.toDouble(), my.toDouble()) else frame.nativeContains(true, mx.toDouble(), my.toDouble())
        val searching = searchable && query.isNotEmpty()
        val match = searching && matches(stack)
        val border = if (match) BLUE else if (hovered) { if (open) 0xFFB1D6FF.toInt() else 0xFF777777.toInt() } else 0xFF353535.toInt()
        g.fill(cell.x, cell.y, cell.right, cell.bottom, border)
        g.fill(cell.x + 1, cell.y + 1, cell.right - 1, cell.bottom - 1, if (match) 0xFF253B57.toInt() else if (hovered) { if (open) 0xFF384B66.toInt() else 0xFF363636.toInt() } else 0xFF292929.toInt())
        g.pose().pushMatrix()
        g.pose().translate(cell.x + 1f, cell.y + 1f)
        val itemScale = (cell.width - 2) / 16f
        g.pose().scale(itemScale, itemScale)
        if (realSlot != null) {
            g.pose().translate(-realSlot.x.toFloat(), -realSlot.y.toFloat())
            super.extractSlot(g, realSlot, mouseX, mouseY)
        } else if (!stack.isEmpty) {
            g.fakeItem(stack, 0, 0)
            g.itemDecorations(font, stack, 0, 0)
        }
        g.pose().popMatrix()
        if (searching && !stack.isEmpty && !match) g.fill(cell.x + 1, cell.y + 1, cell.right - 1, cell.bottom - 1, 0xBF292929.toInt())
    }

    private fun drawFooter(g: GuiGraphicsExtractor) {
        val f = frame.footer
        g.fill(f.x, f.y, f.right, f.y + 1, 0xFF2A2A2A.toInt())
        val manageWidth = ui.measure("Manage storage", 12) + 24
        val emptyLabel = if (state.showEmpty) "Hide empty & locked" else "Empty & locked"
        val emptyWidth = ui.measure(emptyLabel, 12) + 24
        val manage = StorageOverlayLayout.Rect(f.right - 16 - manageWidth, f.y + 8, manageWidth, 18)
        val empty = StorageOverlayLayout.Rect(manage.x - 10 - emptyWidth, manage.y, emptyWidth, 18)
        ui.button(g, empty, emptyLabel, state.showEmpty, 12) { state.showEmpty = !state.showEmpty }
        ui.button(g, manage, "Manage storage", false, 12) {
            if (menu.carried.isEmpty) StorageOverlay.manage() else message = "Place the held item before managing storage."
        }
        val hint = when {
            message.isNotEmpty() -> message
            StorageOverlay.overviewReady(menu) -> "Click a page to open it · Esc Close · / Search"
            StorageOverlay.pendingPage() != null || !StorageOverlay.interactionReady(menu) -> "Waiting for the server…"
            !menu.carried.isEmpty -> "Holding ${menu.carried.hoverName.string}"
            else -> "Esc Close · / Search · Shift-click to transfer"
        }
        val hintWidth = empty.x - f.x - 30
        if (hintWidth > 24) ui.text(g, ui.shorten(hint, hintWidth, 11), f.x + 16, f.y + 12, 0xFF777777.toInt(), 11)
    }

    override fun extractCarriedItem(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int) {
        if (!ready()) return
        val scale = SoulUi.factor()
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        super.extractCarriedItem(g, (mouseX / scale).roundToInt(), (mouseY / scale).roundToInt())
        g.pose().popMatrix()
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
            if (!frame.inventoryClip.contains(click.x() / SoulUi.factor(), click.y() / SoulUi.factor())) ui.mouseDragged(click, dragX, dragY)
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
        if (frame.inventoryClip.contains(x, y)) return true
        if (!frame.storageContains(x, y)) return false
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
        fun wrap(value: String, width: Int, size: Int) = wrapText(value, width, em(size))
        fun field(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect, field: SoulTextField) =
            drawTextField(g, r.x, r.y, r.right, r.bottom, field, "Search all storage…", inset = 12)
        fun button(g: GuiGraphicsExtractor, r: StorageOverlayLayout.Rect, label: String, accent: Boolean = false, size: Int = 13, action: () -> Unit) {
            val hovered = r.contains(mx.toDouble(), my.toDouble())
            roundRect(g, r.x, r.y, r.right, r.bottom, if (accent) 0xFF3B82F6.toInt() else if (hovered) 0xFF353535.toInt() else 0xE0242424.toInt(), SoulRenderPipelines.ROUND_SMALL)
            drawTextV(g, label, r.x + (r.width - measure(label, size)) / 2, r.y, r.bottom, WHITE, em(size))
            region(r.x, r.y, r.right - 1, r.bottom - 1, action)
        }
        fun bars(g: GuiGraphicsExtractor, mx: Int, my: Int, vertical: ScrollState, horizontal: ScrollState, r: StorageOverlayLayout.Rect) {
            drawScrollbar(g, mx, my, horizontal, r.x, r.y, r.right, r.bottom - 13, horizontal = true)
            drawScrollbar(g, mx, my, vertical, r.x, r.y, r.right, r.bottom)
        }
    }
}
