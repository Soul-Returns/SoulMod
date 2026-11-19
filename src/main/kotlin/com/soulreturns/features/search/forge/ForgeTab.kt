package com.soulreturns.features.search.forge

import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.data.recipes.RecipeCatalogClient
import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.SkyblockSelfProfile
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import com.soulreturns.features.search.SearchRow
import com.soulreturns.features.search.SectionAction
import com.soulreturns.ui.screen.SoulTextField
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import org.lwjgl.glfw.GLFW

/**
 * The Forge tab of Search everywhere (`mocks/search-everywhere/forge.js`): the scope line, the body (catalog
 * state, recipe picker or goal view) and the footer. The screen owns the chrome and routes input here; this
 * class owns the state across screen instances in [ForgeSession].
 */
internal class ForgeTab(private val canvas: ForgeCanvas, showInSearch: (SearchRow) -> Unit) {
    private val ui = ForgeUi(canvas)
    private val s = ForgeSession
    val field = SoulTextField().apply {
        set(s.query)
        onChange = { s.query = it; s.recipeScroll.pos = 0f }
    }
    private val picker = ForgePicker(ui, field)
    private val goalView = ForgeGoalView(ui, showInSearch)

    companion object {
        /** Set by a click that wants the recipe search focused (Change goal); consumed by the next frame. */
        @JvmStatic var focusRequested = false

        private const val API_MAX_AGE_MS = 5 * 60_000L
    }

    // ---------------------------------------------------------------- per-frame data

    private fun gather(): ForgeData {
        val now = System.currentTimeMillis()
        val owned = ForgeOwned.snapshot()
        val qf = Forge.quickForge()
        val slots = Forge.slots(now, qf.fraction)
        val free = if (slots.isEmpty()) ForgeEngine.SLOTS else slots.count { it is Forge.Idle }
        val index = RecipeCatalogClient.index
        val locks = ForgeStore.read().locked
        val model = ForgeModel.of(Forge.goal(), index, locks, owned, qf.fraction, free)
        return ForgeData(owned, qf, slots, free, index, locks, RecipeCatalogClient.state(), model, ForgeStore.book())
    }

    /** Call once per frame before drawing in Forge mode. */
    fun begin() {
        s.forProfile(SearchEverywhere.profile())
        // The SkyBlock API's forge processes fill slots this client never saw (a stale self profile reloads at most every 5 min).
        Forge.syncApi()
        SkyblockSelfProfile.refreshIfStale(API_MAX_AGE_MS)
        SearchEverywhere.rows(SearchEverywhere.active())
        val first = !ui.ready
        ui.d = gather()
        if (first) field.focused = picking()
        if (focusRequested) {
            focusRequested = false
            field.focused = true
        }
    }

    private val data: ForgeData get() { if (!ui.ready) begin(); return ui.d }

    /** The picker shows (and takes typing) when picking or there is no usable goal. */
    fun picking(): Boolean = data.catalog != "loading" && data.catalog != "offline" && (s.picking || data.model == null)

    // ---------------------------------------------------------------- scope line

    fun drawScope(g: GuiGraphicsExtractor, r: Rect) {
        val d = data
        val c = canvas
        g.fill(r.x, r.y, r.right, r.bottom, 0xFF1D1D1D.toInt())
        g.fill(r.x, r.bottom - 1, r.right, r.bottom, 0xFF2A2A2A.toInt())
        val cy = r.y + 17
        c.rrect(g, r.x + 22, cy - 3, r.x + 28, cy + 3, 0xFF8FB2E7.toInt(), SoulRenderPipelines.ROUND_PILL)
        val name = (SkyblockProfileState.current ?: if (SkyblockProfileState.profileId != null) "Current profile" else "Profile not resolved") +
            if (SkyblockState.profileType == ProfileType.IRONMAN) " · Ironman" else ""
        c.text(g, name, r.x + 35, r.y + 7, r.y + 26, 0xFFDEDEDE.toInt(), ui.em(12), true)
        val (catalogText, catalogColor) = catalogText(d)
        val right = r.right - 22
        val cw = c.width(catalogText, ui.em(12))
        c.text(g, catalogText, right - cw, r.y + 7, r.y + 26, catalogColor, ui.em(12), false)
        val limit = right - cw - 14
        var x = r.x + 35 + c.width(name, ui.em(12)) + 13
        val qf = d.qfInfo
        val pct = Math.round(qf.fraction * 100)
        val qfText = if (!qf.known) "Quick Forge not read yet · base times"
            else if (qf.fraction > 0) "Quick Forge $pct%" + (qf.level?.let { " (level $it)" } ?: "") + " · times are yours"
            else "No Quick Forge in your active HotM tree · times are yours"
        val slotsText = if (d.slots.isEmpty()) "Forge slots not seen yet" else "${d.free} of ${ForgeEngine.SLOTS} forge slots free"
        val tips = listOf(
            if (qf.known) emptyList() else listOf("§fQuick Forge not read yet", "§7Open Heart of the Mountain or a forge recipe page", "§7once; until then times are base times."),
            if (d.slots.isEmpty()) listOf("§fForge slots not seen yet", "§7Open The Forge once to read them.") else emptyList(),
        )
        for ((i, pair) in listOf(qfText to if (qf.known) 0xFF888888.toInt() else ForgeColors.AMBER, slotsText to 0xFF888888.toInt()).withIndex()) {
            val (text, color) = pair
            g.fill(x, cy - 7, x + 1, cy + 7, 0xFF383838.toInt())
            x += 14
            if (x >= limit) break
            val shown = c.fit(text, limit - x, ui.em(12))
            c.text(g, shown, x, r.y + 7, r.y + 26, color, ui.em(12), false)
            val w = c.width(shown, ui.em(12))
            val hit = Rect(x, r.y, x + w, r.bottom)
            if (tips[i].isNotEmpty() && c.hovered(hit, intArrayOf(r.x, r.y, r.right, r.bottom))) c.tip(hit, tips[i])
            x += w + 13
        }
    }

    private fun catalogText(d: ForgeData): Pair<String, Int> {
        val away = 0xFFD0B986.toInt()
        val muted = 0xFF888888.toInt()
        val age = d.index.updatedAt?.let { ago(System.currentTimeMillis() - it) }
        return when (d.catalog) {
            "stale" -> ("Offline · recipes from " + (age ?: "an unknown date")) to away
            "loaded" -> ("Recipes updated " + (age ?: "recently")) to muted
            "loading" -> "Loading recipes…" to muted
            else -> "Recipes not downloaded" to away
        }
    }

    private fun ago(ms: Long): String {
        val minutes = ms / 60000
        if (minutes < 1) return "just now"
        if (minutes < 60) return if (minutes == 1L) "1 minute ago" else "$minutes minutes ago"
        val hours = minutes / 60
        if (hours < 24) return if (hours == 1L) "1 hour ago" else "$hours hours ago"
        val days = hours / 24
        return if (days == 1L) "1 day ago" else "$days days ago"
    }

    // ---------------------------------------------------------------- body

    fun drawBody(g: GuiGraphicsExtractor, body: Rect, compact: Boolean) {
        val d = data
        if (d.catalog == "loading" || d.catalog == "offline") catalogState(g, body, d)
        else if (s.picking || d.model == null) picker.draw(g, body, compact)
        else goalView.draw(g, body, compact)
    }

    private fun catalogState(g: GuiGraphicsExtractor, body: Rect, d: ForgeData) {
        val c = canvas
        val vis = c.clip(g, body)
        val goal = Forge.goal()
        val goalText = goal?.let { "Your goal is saved: " + ui.name(it) }
        val loading = d.catalog == "loading"
        val title = if (loading) "Loading forge recipes…" else "Forge recipes not downloaded yet"
        val lines = if (loading) listOf("Downloaded once from Soul's server, then kept on disk.")
            else listOf("Soul could not reach its server. The recipe list downloads once", "and then works offline. Search keeps working meanwhile.")
        // Heights: spinner 28 | title 27 | paragraph lines 17.6 each | goal line 17.6 | button 18, 10 apart.
        val parts = ArrayList<Pair<String, Int>>()
        if (loading) parts += "spin" to 28
        parts += "title" to 27
        if (!loading) parts += "para" to Math.round(lines.size * 17.6f)
        if (goalText != null) parts += "goal" to 18
        if (loading) parts += "para" to 18
        if (!loading) parts += "button" to 18
        val total = parts.sumOf { it.second } + 10 * (parts.size - 1)
        var y = body.y + (body.height - total) / 2
        val cx = body.x + body.width / 2
        for ((kind, h) in parts) {
            if (kind == "spin") {
                c.rrect(g, cx - 13, y, cx + 13, y + 26, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_PILL)
                c.rrect(g, cx - 11, y + 2, cx + 11, y + 24, 0xFF1D1D1D.toInt(), SoulRenderPipelines.ROUND_PILL)
                val t = (System.currentTimeMillis() % 1000) / 1000.0 * Math.PI * 2
                val px = cx + (Math.cos(t) * 12).toInt()
                val py = y + 13 + (Math.sin(t) * 12).toInt()
                c.rrect(g, px - 3, py - 3, px + 3, py + 3, 0xFF86B4FF.toInt(), SoulRenderPipelines.ROUND_PILL)
            } else if (kind == "title") c.text(g, title, cx - c.width(title, ui.em(17)) / 2, y, y + h, 0xFFCCCCCC.toInt(), ui.em(17), false)
            else if (kind == "goal" && goal != null) {
                val prefix = "Your goal is saved: "
                val total2 = c.width(prefix, ui.em(11)) + c.width(ui.name(goal), ui.em(11))
                var gx = cx - total2 / 2
                c.text(g, prefix, gx, y, y + h, ForgeColors.DIM, ui.em(11), false)
                gx += c.width(prefix, ui.em(11))
                c.text(g, ui.name(goal), gx, y, y + h, ui.color(goal), ui.em(11), false)
            } else if (kind == "para") lines.forEachIndexed { i, line ->
                val ly = y + Math.round(i * 17.6f)
                c.text(g, line, cx - c.width(line, ui.em(11)) / 2, ly, ly + 18, 0xFF888888.toInt(), ui.em(11), false)
            } else if (kind == "button") {
                val bw = c.width("Try again", ui.em(11)) + 24
                c.button(g, cx + bw / 2, y, 18, SectionAction("Try again") { RecipeCatalogClient.retry() }, vis, 11, 12)
            }
            y += h + 10
        }
        c.endClip(g)
    }

    // ---------------------------------------------------------------- footer

    fun footerLeft() = "Owned = island chests, storage, sacks, inventory and the forge. Last seen, not live."

    fun footerKeys(): List<Pair<String, String>> {
        val esc = if (s.sel != null) "Back" else if (s.picking && Forge.goal() != null && data.model != null) "Back to goal" else "Close"
        return listOf("Ctrl+Tab" to "Search ·", "Esc" to esc)
    }

    // ---------------------------------------------------------------- input

    /** Escape: clear the selection, else leave the picker for the goal; false = the screen closes. */
    fun escape(): Boolean {
        if (s.sel != null) { s.select(null); return true }
        if (s.picking && Forge.goal() != null && data.model != null) { s.picking = false; return true }
        return false
    }

    /** Mock `key()`: Enter previews the first recipe, `/` focuses the recipe search; true = consumed. */
    fun keyPressed(event: KeyEvent): Boolean {
        val key = event.key()
        if (key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) {
            if (field.focused && picking()) picker.previewFirst()
            return true
        }
        if (key == GLFW.GLFW_KEY_SLASH && !field.focused && picking()) { field.focused = true; return true }
        if (field.focused && picking()) return field.keyPressed(event, Minecraft.getInstance().keyboardHandler)
        return false
    }

    fun charTyped(event: CharacterEvent): Boolean = if (field.focused && picking()) field.charTyped(event) else false

    /** The mode switched to Forge: the recipe search takes the focus when it is on screen. */
    fun entered() {
        field.focused = picking()
    }
}
