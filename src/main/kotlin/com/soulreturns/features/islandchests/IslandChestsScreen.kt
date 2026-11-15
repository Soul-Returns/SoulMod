package com.soulreturns.features.islandchests

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.MsdfFont
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.inventorybuttons.InventoryButtonIcons
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.screen.SoulScreenBase
import com.soulreturns.ui.screen.SoulTextField
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import java.text.NumberFormat
import java.util.Locale
import kotlin.math.roundToInt

class IslandChestsScreen(private val parent: Screen? = null) : SoulScreenBase(Component.literal("Island chests")) {
    private val search = SoulTextField().apply {
        focused = true
        onChange = { listScroll.pos = 0f }
    }
    private val listScroll = ScrollState()
    private val locationScroll = ScrollState()
    private val bodyScroll = ScrollState()
    private val panelScroll = ScrollState()
    private val sharingScroll = ScrollState()
    private var selectedKey: String? = null
    private var profile: String? = null
    private var sharing = false
    private var viewport = intArrayOf(0, 0, 0, 0)
    private var mx = 0
    private var my = 0

    internal fun restore(query: String, itemKey: String?) {
        search.set(query)
        selectedKey = itemKey
        profile = IslandChests.view().profileId
    }

    override fun isPauseScreen() = false

    override fun tick() {
        super.tick()
        if (!cfg.islandChests.enabled || !IslandChests.view().ownIsland) onClose()
    }

    override fun onClose() {
        if (parent != null) Minecraft.getInstance().setScreenAndShow(parent) else super.onClose()
    }

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        if (!cfg.islandChests.enabled || !IslandChests.view().ownIsland) return
        regions.clear()
        scrollTargets.clear()
        val scale = SoulUi.factor()
        val w = width / scale
        val h = height / scale
        mx = (mouseX / scale).roundToInt()
        my = (mouseY / scale).roundToInt()
        val view = IslandChests.view()
        if (profile != view.profileId) {
            profile = view.profileId
            selectedKey = null
            listScroll.pos = 0f
            locationScroll.pos = 0f
            bodyScroll.pos = 0f
        }
        val results = if (view.profileId == null) emptyList() else IslandChests.search(search.text)
        val chosen = selectedKey?.let { key -> IslandChests.search("").firstOrNull { it.key == key } }
        val panel = IslandChestLayout.panel(w, h)
        val x = panel.x.roundToInt()
        val panelTop = panel.y.roundToInt()
        val panelBottom = (panel.y + panel.height).roundToInt()
        panelScroll.view = panelBottom - panelTop
        panelScroll.content = IslandChestLayout.contentHeight(panelScroll.view)
        panelScroll.clamp()
        val y = panelTop - panelScroll.pos.roundToInt()
        val right = (panel.x + panel.width).roundToInt()
        val bottom = y + panelScroll.content
        g.pose().pushMatrix()
        g.pose().scale(scale, scale)
        g.fill(0, 0, w.toInt(), h.toInt(), Theme.DIM)
        roundRect(g, x - 1, panelTop - 1, right + 1, panelBottom + 1, EDGE, SoulRenderPipelines.ROUND_LARGE)
        roundRect(g, x, panelTop, right, panelBottom, BG, SoulRenderPipelines.ROUND_LARGE)
        beginClip(g, x, panelTop, right, panelBottom)
        roundRect(g, x + 22, y + 18, x + 60, y + 56, 0xFF202F46.toInt())
        drawTextCentered(g, "S", x + 41, y + 22, 0xFF86B4FF.toInt(), em(25))
        drawText(g, "Island chests", x + 72, y + 18, WHITE, em(25))
        drawText(g, "Search what your island remembers.", x + 72, y + 49, MUTED, em(12))
        divider(g, x, y + 77, right)
        val scopeH = if (panel.compact) 53 else 35
        val scopeY = y + 78
        val name = view.profileName ?: if (view.profileId != null) "Current profile" else "Profile not resolved"
        drawText(g, ellipsize(name + if (view.ironman) " · Ironman" else "", (right - x - 180).coerceAtLeast(50), em(12)), x + 22, scopeY + 10, WHITE, em(12))
        val placeY = if (panel.compact) scopeY + 30 else scopeY + 10
        val placeX = if (panel.compact) x + 22 else x + 22 + textWidth(name + if (view.ironman) " · Ironman" else "", em(12)) + 20
        drawText(g, ellipsize("Your private island", (right - placeX - if (panel.compact) 22 else 135).coerceAtLeast(1), em(12)), placeX, placeY, MUTED, em(12))
        drawText(g, "Co-op sharing", right - 112, scopeY + 10, 0xFF9DAEC5.toInt(), em(12))
        clippedRegion(right - 120, scopeY, right - 12, scopeY + 30, intArrayOf(x, panelTop, right, panelBottom)) { sharing = true; search.focused = false }
        divider(g, x, scopeY + scopeH, right)
        val statusY = scopeY + scopeH + 1
        val status = when {
            !cfg.islandChests.enabled -> "Remembering disabled · saved contents remain searchable"
            view.profileId == null -> "Cache locked · unknown profile"
            view.syncState == "offline" -> "Offline · searching saved contents"
            view.sharingEnabled -> "Co-op cache · ${view.syncState}"
            else -> "Local cache · co-op sharing off"
        }
        drawText(g, ellipsize(status, right - x - 44, em(11)), x + 22, statusY + 9, if (view.syncState == "offline") AMBER else 0xFF9FBAAC.toInt(), em(11))
        val top = statusY + 30
        val foot = bottom - 28
        endClip(g)
        viewport = intArrayOf(x + 1, maxOf(top, panelTop), right - 1, maxOf(maxOf(top, panelTop), minOf(foot, panelBottom)))
        if (foot > top) {
            if (panel.compact) {
                val detailH = if (chosen == null) 330 else maxOf(330, 250 + chosen.matches.size * 78)
                bodyScroll.content = 230 + detailH
                bodyScroll.view = foot - top
                bodyScroll.clamp()
                val sy = top - bodyScroll.pos.roundToInt()
                drawSearch(g, view, results, x + 1, sy, right - 1, sy + 230)
                drawDetails(g, view, chosen, x + 1, sy + 230, right - 1, sy + 230 + detailH, false)
                drawScrollbar(g, mx, my, bodyScroll, x + 1, top, right - 1, foot)
                scrollTargets.add(0, scrollTargets.removeAt(scrollTargets.lastIndex))
            } else {
                val split = x + ((right - x) * 1.03f / 2.03f).roundToInt()
                drawSearch(g, view, results, x + 1, top, split, foot)
                drawDetails(g, view, chosen, split, top, right - 1, foot, true)
            }
        }
        beginClip(g, x, panelTop, right, panelBottom)
        divider(g, x, foot, right)
        drawText(g, "Only normal chest opens refresh remembered contents.", x + 20, foot + 10, MUTED, em(9))
        if (!panel.compact) drawText(g, "Esc · ${if (chosen != null) "Clear selection" else "Close"}     / · Search", right - 205, foot + 10, MUTED, em(9))
        endClip(g)
        if (panelScroll.max > 0) {
            drawScrollbar(g, mx, my, panelScroll, x, panelTop, right, panelBottom)
            scrollTargets.add(0, scrollTargets.removeAt(scrollTargets.lastIndex))
        }
        drawTooltip(g, w.toInt(), h.toInt())
        if (sharing) drawSharing(g, w.toInt(), h.toInt(), view)
        g.pose().popMatrix()
    }

    private fun clip(g: GuiGraphicsExtractor, x: Int, y: Int, right: Int, bottom: Int): IntArray {
        val rect = intArrayOf(maxOf(x, viewport[0]), maxOf(y, viewport[1]), minOf(right, viewport[2]), minOf(bottom, viewport[3]))
        beginClip(g, rect[0], rect[1], maxOf(rect[0], rect[2]), maxOf(rect[1], rect[3]))
        return rect
    }

    private fun drawSearch(g: GuiGraphicsExtractor, view: IslandChestView, results: List<ChestSearchResult>, x: Int, y: Int, right: Int, bottom: Int) {
        if (!IslandChestLayout.hasVisibleArea(x, y, right, bottom, viewport)) return
        val section = clip(g, x, y, right, bottom)
        val left = x + 20
        val edge = right - 14
        val fieldY = y + 16
        val disabled = view.profileId == null
        suppressRegions = disabled
        drawTextField(g, left, fieldY, edge - 25, fieldY + 35, search, "Search items…", section, inset = 11)
        suppressRegions = false
        if (search.text.isNotEmpty()) {
            drawText(g, "×", edge - 17, fieldY + 8, WHITE, em(18))
            clippedRegion(edge - 24, fieldY, edge, fieldY + 35, section) { search.set(""); listScroll.pos = 0f; search.focused = true }
        } else drawText(g, "/", edge - 14, fieldY + 12, MUTED, em(10))
        drawText(g, if (search.text.isBlank()) "All remembered items" else "${results.size} matching items", left + 3, fieldY + 47, MUTED, em(10))
        val listTop = fieldY + 66
        val listBottom = bottom - 35
        endClip(g)
        val listRect = clip(g, left, listTop, edge, maxOf(listTop, listBottom))
        listScroll.content = results.size * 57
        listScroll.view = (listBottom - listTop).coerceAtLeast(0)
        listScroll.clamp()
        val message = when {
            disabled -> "Waiting for your profile" to "Cached contents stay hidden until the current SkyBlock profile is known. The last-used profile is never assumed."
            !cfg.islandChests.enabled && view.chests.isEmpty() -> "Remembering is disabled" to "Enable Island chests in settings, then open a chest normally on your private island."
            view.chests.isEmpty() -> "No chests remembered yet" to "Open a chest on your private island to remember it. Contents from other profiles are never shown here."
            results.isEmpty() -> "No cached matches" to "Nothing remembered for “${search.text}”. Unopened chests are not searchable."
            else -> null
        }
        if (message != null) {
            drawTextCentered(g, message.first, (left + edge) / 2, listTop + 12, WHITE, em(17))
            for ((i, line) in wrapText(message.second, (edge - left - 20).coerceAtLeast(1), em(11)).withIndex()) drawTextCentered(g, line, (left + edge) / 2, listTop + 42 + i * 16, MUTED, em(11))
            if (!disabled && search.text.isNotBlank()) clippedButton(g, mx, my, left + 15, listBottom - 30, edge - 15, listBottom - 6, "Clear search", listRect) { search.set(""); search.focused = true }
        } else {
            for ((index, result) in results.withIndex()) {
                val rowY = listTop + index * 57 - listScroll.pos.roundToInt()
                if (listRect[1] >= listRect[3] || rowY + 54 <= listRect[1] || rowY >= listRect[3]) continue
                val selected = result.key == selectedKey
                if (selected || inside(mx, my, left, rowY, edge - 5, rowY + 54)) {
                    roundRect(g, left, rowY, edge - 5, rowY + 54, if (selected) 0xFF385A87.toInt() else 0xFF252525.toInt(), SoulRenderPipelines.ROUND_SMALL)
                    if (selected) roundRect(g, left + 1, rowY + 1, edge - 6, rowY + 53, 0xFF202D40.toInt(), SoulRenderPipelines.ROUND_SMALL)
                }
                icon(g, result.item, left + 11, rowY + 15, 24)
                val qty = number(result.count)
                val qtyW = textWidth(qty, em(14))
                drawText(g, ellipsize(itemName(result.item), (edge - left - 66 - qtyW).coerceAtLeast(1), em(13)), left + 48, rowY + 11, itemColor(result.item), em(13))
                val newest = result.matches.maxOfOrNull { it.chest.observedAt }
                val older = newest != null && result.matches.any { it.chest.observedAt < newest }
                drawText(g, ellipsize("${result.matches.size} ${chests(result.matches.size)}" + if (older) " · older check included" else "", (edge - left - 58).coerceAtLeast(1), em(10)), left + 48, rowY + 32, if (older) AMBER else MUTED, em(10))
                drawText(g, qty, edge - qtyW - 17, rowY + 20, WHITE, em(14))
                clippedRegion(left, rowY, edge - 5, rowY + 54, listRect) { selectedKey = result.key; locationScroll.pos = 0f; search.focused = false }
            }
        }
        endClip(g)
        drawScrollbar(g, mx, my, listScroll, left, maxOf(listTop, viewport[1]), edge, maxOf(maxOf(listTop, viewport[1]), minOf(listBottom, viewport[3])))
        clip(g, x, y, right, bottom)
        divider(g, left, bottom - 32, edge)
        drawText(g, "${view.chests.size} chests remembered", left, bottom - 19, MUTED, em(9))
        drawText(g, "Not your entire island", maxOf(left, edge - textWidth("Not your entire island", em(9))), bottom - 19, 0xFF555555.toInt(), em(9))
        endClip(g)
    }

    private fun drawDetails(g: GuiGraphicsExtractor, view: IslandChestView, result: ChestSearchResult?, x: Int, y: Int, right: Int, bottom: Int, independent: Boolean) {
        if (!IslandChestLayout.hasVisibleArea(x, y, right, bottom, viewport)) return
        val section = clip(g, x, y, right, bottom)
        val left = x + 20
        val edge = right - 20
        if (result == null) {
            val center = (left + edge) / 2
            roundRect(g, left, y + 18, edge, y + 140, 0xFF3B3933.toInt())
            roundRect(g, left + 1, y + 19, edge - 1, y + 139, 0xFF20231F.toInt())
            g.pose().pushMatrix()
            g.pose().translate((center - 24).toFloat(), (y + 52).toFloat())
            g.pose().scale(3f, 3f)
            g.fakeItem(InventoryButtonIcons.stack(InventoryButtonIcons.ItemLook("minecraft:chest")), 0, 0)
            g.pose().popMatrix()
            drawTextCentered(g, "Find it. Then go get it.", center, y + 159, WHITE, em(20))
            for ((i, line) in wrapText("Choose an item to see every chest where it was last observed.", (edge - left - 20).coerceAtLeast(1), em(12)).withIndex()) drawTextCentered(g, line, center, y + 194 + i * 19, MUTED, em(12))
            drawTextCentered(g, "1 Search    2 Choose item    3 Locate", center, y + 250, 0xFF8CB9FF.toInt(), em(13))
            for ((i, line) in wrapText("Only chests opened by you or a sharing co-op client. No scanning. No automatic opening.", (edge - left - 20).coerceAtLeast(1), em(10)).withIndex()) drawTextCentered(g, line, center, y + 282 + i * 16, MUTED, em(10))
            endClip(g)
            return
        }
        icon(g, result.item, left + 6, y + 24, 30)
        drawText(g, "LAST OBSERVED CONTENTS", left + 54, y + 20, MUTED, em(9))
        drawText(g, ellipsize(itemName(result.item), (edge - left - 54).coerceAtLeast(1), em(18)), left + 54, y + 37, itemColor(result.item), em(18))
        drawText(g, number(result.count), left + 1, y + 77, WHITE, em(29))
        drawText(g, "cached total", left + 1, y + 110, MUTED, em(10))
        val chestSummary = "${result.matches.size} ${chests(result.matches.size)}"
        drawText(g, chestSummary, edge - textWidth(chestSummary, em(17)), y + 83, WHITE, em(17))
        drawText(g, "double chests counted once", edge - textWidth("double chests counted once", em(10)), y + 110, MUTED, em(10))
        divider(g, left, y + 133, edge)
        val listTop = y + 145
        val actionY = bottom - 65
        val newest = result.matches.maxOfOrNull { it.chest.observedAt } ?: 0L
        val hasOlder = result.matches.any { it.chest.observedAt < newest }
        val note = if (hasOlder) "Older observations included. Items may have moved since these chests were opened." else "A snapshot, not a live count. Open a chest normally to refresh it."
        val noteLines = wrapText(note, (edge - left).coerceAtLeast(1), em(10))
        val listBottom = maxOf(listTop + 1, actionY - 14 - noteLines.size * 15)
        endClip(g)
        val listRect = clip(g, left, listTop, edge, listBottom)
        locationScroll.content = result.matches.size * 78
        locationScroll.view = listBottom - listTop
        locationScroll.clamp()
        for ((index, match) in result.matches.withIndex()) {
            val rowY = listTop + index * 78 - if (independent) locationScroll.pos.roundToInt() else 0
            if (rowY + 70 < listRect[1] || rowY > listRect[3]) continue
            val older = match.chest.observedAt < newest
            roundRect(g, left, rowY, edge - 6, rowY + 70, if (older) 0xFF494030.toInt() else EDGE, SoulRenderPipelines.ROUND_SMALL)
            roundRect(g, left + 1, rowY + 1, edge - 7, rowY + 69, 0xFF222222.toInt(), SoulRenderPipelines.ROUND_SMALL)
            drawText(g, marker(index), left + 10, rowY + 10, 0xFF9DB4D1.toInt(), em(9))
            drawText(g, if (match.chest.blocks.size == 2) "Double chest" else "Chest", left + 33, rowY + 9, WHITE, em(12))
            val count = number(match.count)
            drawText(g, count, edge - 17 - textWidth(count, em(14)), rowY + 8, WHITE, em(14))
            val coords = match.chest.blocks.joinToString(" / ") { "${it.x}, ${it.y}, ${it.z}" }
            val distance = IslandChestDisplay.distance(match.chest)?.let { "${it.roundToInt()}m" } ?: ""
            drawText(g, ellipsize(coords, (edge - left - 32 - textWidth(distance, em(10))).coerceAtLeast(1), em(10)), left + 10, rowY + 31, 0xFF8B8B8B.toInt(), em(10))
            drawText(g, distance, edge - 17 - textWidth(distance, em(10)), rowY + 31, MUTED, em(10))
            val observed = "Checked ${IslandChestLayout.age(match.chest.observedAt, System.currentTimeMillis())}"
            drawText(g, observed, left + 10, rowY + 51, if (older) AMBER else MUTED, em(9))
            val author = "by ${match.chest.observerName ?: match.chest.observerUuid.take(8)}"
            drawText(g, ellipsize(author, (edge - left - textWidth(observed, em(9)) - 35).coerceAtLeast(1), em(9)), left + 20 + textWidth(observed, em(9)), rowY + 51, MUTED, em(9))
            if (inside(mx, my, left, rowY, edge, rowY + 70) && inside(mx, my, listRect[0], listRect[1], listRect[2], listRect[3])) tooltip(left, rowY, edge, rowY + 70, listOf(coords, "Last checked: ${java.time.Instant.ofEpochMilli(match.chest.observedAt)}", "Contributor: ${match.chest.observerName ?: match.chest.observerUuid}"))
        }
        endClip(g)
        if (independent) drawScrollbar(g, mx, my, locationScroll, left, listTop, edge, listBottom)
        clip(g, x, y, right, bottom)
        for ((i, line) in noteLines.withIndex()) drawText(g, line, left, listBottom + 10 + i * 15, if (hasOlder) AMBER else MUTED, em(10))
        val allowed = cfg.islandChests.enabled && view.ownIsland && view.profileId != null
        suppressRegions = !allowed
        clippedButton(g, mx, my, left, actionY, edge, actionY + 33, if (result.matches.size == 1) "Locate chest" else "Locate all ${result.matches.size} chests", section, accent = allowed) {
            IslandChestDisplay.rememberSearch(search.text, result.key)
            IslandChests.startLocating(result.key)
            if (IslandChests.locating() != null) super.onClose()
        }
        suppressRegions = false
        val explanation = if (allowed) "Highlights every matching chest on your island" else "Return to your own island to locate. No automatic warp."
        drawTextCentered(g, ellipsize(explanation, edge - left, em(9)), (left + edge) / 2, actionY + 41, MUTED, em(9))
        endClip(g)
    }

    private fun drawSharing(g: GuiGraphicsExtractor, w: Int, h: Int, view: IslandChestView) {
        g.fill(0, 0, w, h, 0xB0000000.toInt())
        region(0, 0, w, h) { sharing = false }
        val pw = minOf(430, (w - 30).coerceAtLeast(1))
        val lines = wrapText("Shared snapshots are restricted to verified co-op members of this exact SkyBlock profile. Other profiles, visitors and party members get no access. Sharing requires Soul clients; cached search works offline.", (pw - 48).coerceAtLeast(1), em(12))
        val ph = 166 + lines.size * 19
        sharingScroll.content = ph
        sharingScroll.view = minOf(ph, (h - 30).coerceAtLeast(1))
        sharingScroll.clamp()
        val x = (w - pw) / 2
        val top = (h - sharingScroll.view) / 2
        val y = top - sharingScroll.pos.roundToInt()
        val modal = intArrayOf(x, top, x + pw, top + sharingScroll.view)
        roundRect(g, x, top, x + pw, top + sharingScroll.view, 0xFF242424.toInt())
        region(x, top, x + pw, top + sharingScroll.view) {}
        beginClip(g, modal[0], modal[1], modal[2], modal[3])
        drawText(g, "CURRENT PROFILE ONLY", x + 24, y + 19, MUTED, em(9))
        drawText(g, "Remember together.", x + 24, y + 38, WHITE, em(25))
        for ((i, line) in lines.withIndex()) drawText(g, line, x + 24, y + 76 + i * 19, 0xFFBBBBBB.toInt(), em(12))
        val by = y + 84 + lines.size * 19
        clippedButton(g, mx, my, x + 24, by, x + pw - 24, by + 27, if (view.sharingEnabled) "Turn co-op sharing off" else "Enable co-op sharing", modal, !view.sharingEnabled) {
            ConfigManager.update { copy(islandChests = islandChests.copy(shareWithCoop = !view.sharingEnabled)) }
            IslandChests.refresh()
            sharing = false
        }
        clippedButton(g, mx, my, x + 24, by + 34, x + pw - 24, by + 55, "Got it", modal) { sharing = false }
        endClip(g)
        drawScrollbar(g, mx, my, sharingScroll, x, top, x + pw, top + sharingScroll.view)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (sharing) {
            if (event.key() == GLFW.GLFW_KEY_ESCAPE) sharing = false
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
            if (selectedKey != null) selectedKey = null else onClose()
            return true
        }
        if (event.key() == GLFW.GLFW_KEY_SLASH && !search.focused) { search.focused = true; return true }
        if (event.key() == GLFW.GLFW_KEY_ENTER || event.key() == GLFW.GLFW_KEY_KP_ENTER) {
            if (IslandChests.view().profileId != null) selectedKey = IslandChests.search(search.text).firstOrNull()?.key
            return true
        }
        if (search.focused && IslandChests.view().profileId != null) return search.keyPressed(event, Minecraft.getInstance().keyboardHandler)
        return super.keyPressed(event)
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (sharing) return true
        return if (search.focused && IslandChests.view().profileId != null) search.charTyped(event) else super.charTyped(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, horizontalAmount: Double, verticalAmount: Double): Boolean {
        if (sharing) {
            sharingScroll.scrollBy((-verticalAmount * 30).toFloat())
            return true
        }
        val scale = SoulUi.factor()
        val target = scrollTargetAt((mouseX / scale).roundToInt(), (mouseY / scale).roundToInt()) ?: return false
        target.state.scrollBy((-verticalAmount * 30).toFloat())
        return true
    }

    private fun divider(g: GuiGraphicsExtractor, x: Int, y: Int, right: Int) = g.fill(x, y, right, y + 1, 0xFF2A2A2A.toInt())
    private fun inside(mx: Int, my: Int, x: Int, y: Int, right: Int, bottom: Int) = mx >= x && mx < right && my >= y && my < bottom

    companion object {
        private val BG = 0xF71A1A1A.toInt()
        private val EDGE = 0xFF333333.toInt()
        private val WHITE = 0xFFEEEEEE.toInt()
        private val MUTED = 0xFF888888.toInt()
        private val AMBER = 0xFFD0B986.toInt()
        private val numbers = NumberFormat.getIntegerInstance(Locale.ROOT)
        private val itemColors = HashMap<StoredChestItem, Int>()
        private val itemNames = HashMap<StoredChestItem, String>()
        internal fun em(size: Int) = size * MsdfFont.LEGACY_EM
        internal fun number(count: Long) = numbers.format(count)
        internal fun chests(count: Int) = if (count == 1) "chest" else "chests"
        internal fun marker(index: Int) = if (index < 26) ('A' + index).toString() else (index + 1).toString()
        internal fun itemName(item: StoredChestItem): String {
            if (itemNames.size > 512) itemNames.clear()
            return itemNames.getOrPut(item) { item.searchName() }
        }
        internal fun itemColor(item: StoredChestItem): Int {
            if (itemColors.size > 512) itemColors.clear()
            return itemColors.getOrPut(item) { item.searchColor() }
        }
        internal fun icon(g: GuiGraphicsExtractor, item: StoredChestItem, x: Int, y: Int, size: Int) {
            val look = item.look ?: InventoryButtonIcons.ItemLook(item.material)
            val stack = InventoryButtonIcons.stack(look)
            g.pose().pushMatrix()
            g.pose().translate(x.toFloat(), y.toFloat())
            g.pose().scale(size / 16f, size / 16f)
            g.fakeItem(stack, 0, 0)
            g.pose().popMatrix()
        }
    }
}
