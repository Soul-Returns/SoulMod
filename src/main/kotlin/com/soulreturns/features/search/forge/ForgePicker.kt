package com.soulreturns.features.search.forge

import com.soulreturns.config.gui.render.SoulPanel
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import com.soulreturns.features.search.SectionAction
import com.soulreturns.ui.screen.SoulTextField
import net.minecraft.client.gui.GuiGraphicsExtractor
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/**
 * The recipe picker (`forge.js` picker): category rail, recipe list with search, and the preview / intro column.
 * Under 660 Soul px the three parts stack and the body scrolls.
 */
internal class ForgePicker(private val ui: ForgeUi, private val field: SoulTextField) {
    private val c get() = ui.c
    private val d get() = ui.d
    private val s = ForgeSession
    private var lastContent = 0
    private var sortedFor: Pair<Any?, Any?>? = null
    private var sorted: List<String> = emptyList()
    private var previewKey: List<Any?> = emptyList()
    private var previewModel: PreviewModel? = null

    private class PreviewModel(val time: TimeLeft, val best: TimeLeft, val missing: Int)

    // ---------------------------------------------------------------- recipes and rail data

    private fun categoryOf(id: String) = d.cats.categoryOf(id)

    /** Mock `recipes()`: the forge's own order (category, then page and slot); unplaced recipes follow by name. */
    private fun allRecipes(): List<String> {
        val key = d.index to d.book
        if (sortedFor?.first === key.first && sortedFor?.second === key.second) return sorted
        val placement = d.cats
        val cats = placement.categories
        sorted = d.index.forge.keys.sortedWith(
            compareBy<String>({ id -> placement.categoryOf(id)?.let { cats.indexOf(it) }?.takeIf { it >= 0 } ?: Int.MAX_VALUE },
                { id -> placement.orderOf(id) },
                { id -> ui.name(id).lowercase(Locale.ROOT) }, { it }))
        sortedFor = key
        return sorted
    }

    private fun matchesCategory(id: String): Boolean {
        val cat = s.cat
        if (cat == "all") return true
        if (cat == ForgeSession.UNSORTED) return d.cats.unsorted(id)
        return categoryOf(id) == cat
    }

    private fun query() = s.query.trim().lowercase(Locale.ROOT)

    /** Mock `pickList`: filtered by category and name, names starting with the query first. */
    fun pickList(): List<String> {
        val q = query()
        val list = allRecipes().filter { id -> matchesCategory(id) && (q.isEmpty() || ui.name(id).lowercase(Locale.ROOT).contains(q)) }
        return if (q.isEmpty()) list else list.sortedBy { !ui.name(it).lowercase(Locale.ROOT).startsWith(q) }
    }

    /** Enter in the search field: preview the first result. */
    fun previewFirst() {
        pickList().firstOrNull()?.let { s.preview = it; s.previewScroll.pos = 0f }
    }

    private fun directReady(id: String): Boolean {
        val r = d.index.forge[id] ?: return false
        if (d.locks[id] != null) return false
        return r.inputs.all { (cid, n) -> d.owned.have(cid) >= n } && (r.coins == 0L || d.owned.have(ForgeEngine.COINS) >= r.coins)
    }

    private class Cat(val key: String, val label: String, val count: Int)

    private fun categories(): List<Cat> {
        val all = allRecipes()
        val out = ArrayList<Cat>()
        out += Cat("all", "All recipes", all.size)
        for (cat in d.cats.categories) out += Cat(cat, cat, all.count { categoryOf(it) == cat })
        val unsorted = all.count { d.cats.unsorted(it) }
        if (unsorted > 0) out += Cat(ForgeSession.UNSORTED, "Not sorted yet", unsorted)
        return out
    }

    // ---------------------------------------------------------------- entry

    fun draw(g: GuiGraphicsExtractor, body: Rect, compact: Boolean) {
        if (compact) {
            s.bodyScroll.content = lastContent
            s.bodyScroll.view = body.height
            s.bodyScroll.clamp()
            val top = body.y - s.bodyScroll.pos.toInt()
            val railH = rail(g, Rect(body.x, top, body.right, top + 1000000), body, true)
            val recipesTop = top + railH
            val recipesH = recipes(g, Rect(body.x, recipesTop, body.right, recipesTop + 1000000), body, true)
            val previewH = preview(g, Rect(body.x, recipesTop + recipesH, body.right, recipesTop + recipesH + 1000000), body, true)
            lastContent = railH + recipesH + previewH
            c.bodyScrollbar(g, s.bodyScroll, body)
            return
        }
        val p = ForgeLayout.picker(body)
        rail(g, p.rail, body, false)
        recipes(g, p.recipes, body, false)
        preview(g, p.preview, body, false)
    }

    private fun intersect(a: Rect, b: Rect) = Rect(max(a.x, b.x), max(a.y, b.y), min(a.right, b.right), min(a.bottom, b.bottom).coerceAtLeast(max(a.y, b.y)))
    private fun visible(vis: IntArray) = vis[2] > vis[0] && vis[3] > vis[1]

    // ---------------------------------------------------------------- rail

    private fun rail(g: GuiGraphicsExtractor, r: Rect, view: Rect, compact: Boolean): Int {
        val cats = categories()
        val vis = c.clip(g, intersect(r, view))
        var height = 14 + cats.size * (ForgeLayout.CAT_H + ForgeLayout.CAT_GAP) - ForgeLayout.CAT_GAP + 10
        if (visible(vis)) {
            if (!compact) {
                g.fill(r.x, r.y, r.right, r.bottom, 0xAA161616.toInt())
                g.fill(r.right - 1, r.y, r.right, r.bottom, ForgeColors.EDGE)
            }
            for ((i, cat) in cats.withIndex()) {
                val cr = ForgeLayout.category(r, i)
                if (cr.bottom <= vis[1] || cr.y >= vis[3]) continue
                val on = s.cat == cat.key
                val hover = c.hovered(cr, vis)
                if (on) {
                    c.panel(g, cr.x, cr.y, cr.right, cr.bottom, ForgeColors.SEL_BG, ForgeColors.SEL_EDGE, SoulPanel.SMALL)
                } else if (hover) c.rrect(g, cr.x, cr.y, cr.right, cr.bottom, ForgeColors.ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
                val n = cat.count.toString()
                val nw = c.width(n, ui.em(10))
                c.text(g, c.fit(cat.label, cr.width - 18 - nw - 6, ui.em(12)), cr.x + 9, cr.y, cr.bottom, if (on) ForgeColors.TEXT else 0xFFBBBBBB.toInt(), ui.em(12), false)
                c.text(g, n, cr.right - 9 - nw, cr.y, cr.bottom, if (on) 0xFF9DB4D1.toInt() else ForgeColors.FAINT, ui.em(10), false)
                c.region(cr, vis) { s.cat = cat.key; s.preview = null; s.recipeScroll.pos = 0f }
            }
            var y = ForgeLayout.category(r, cats.size).y
            if (d.cats.categories.isEmpty()) {
                var ly = y + 6
                for (line in c.wrap("Open the Forge's category pages once to sort recipes", r.width - 28, ui.em(9))) {
                    c.text(g, line, r.x + 14, ly, ly + 13, ForgeColors.FAINT, ui.em(9), false)
                    ly += 13
                }
                height += 6 + 13 * 3
                y = ly
            }
            val goal = Forge.goal()?.takeIf { it in d.index.forge }
            if (goal != null) {
                val gm = if (compact) Rect(r.x + 12, y + 8, r.right - 9, y + 8 + 49) else ForgeLayout.goalMini(r)
                if (compact) height += 8 + 49 + 10
                val hover = c.hovered(gm, vis)
                c.panel(g, gm.x, gm.y, gm.right, gm.bottom, if (hover) ForgeColors.ROW_HOVER2 else 0xFF1F1F1F.toInt(), 0xFF303030.toInt(), SoulPanel.SMALL)
                ui.itemIcon(g, goal, gm.x + 9, gm.y + 15, 20)
                c.tracked(g, "CURRENT GOAL", gm.x + 37, gm.y + 8, gm.y + 24, 8, ForgeColors.DIM)
                c.text(g, c.fit(ui.name(goal), gm.right - 8 - (gm.x + 37), ui.em(11)), gm.x + 37, gm.y + 24, gm.y + 41, ui.color(goal), ui.em(11), false)
                c.region(gm, vis) { s.picking = false }
            }
        }
        c.endClip(g)
        return height
    }

    // ---------------------------------------------------------------- recipes

    private fun recipes(g: GuiGraphicsExtractor, r: Rect, view: Rect, compact: Boolean): Int {
        val list = pickList()
        val fieldRect = ForgeLayout.searchField(r)
        var vis = c.clip(g, intersect(Rect(r.x, r.y, r.right, r.y + 14 + ForgeLayout.FIELD_H + ForgeLayout.LIST_HEAD), view))
        if (!compact) g.fill(r.right - 1, r.y, r.right, r.bottom, ForgeColors.EDGE)
        if (visible(vis)) {
            c.textField(g, fieldRect, field, "Search forge recipes…", vis)
            c.icon(g, "search", fieldRect.x + 12, fieldRect.y + 9, 17, 0xFF888888.toInt())
            if (field.text.isNotEmpty()) {
                c.text(g, "×", fieldRect.right - 22, fieldRect.y, fieldRect.bottom, 0xFFAAAAAA.toInt(), ui.em(18), false)
                c.region(Rect(fieldRect.right - 30, fieldRect.y, fieldRect.right, fieldRect.bottom), vis) { clearQuery() }
            } else c.kbd(g, "/", fieldRect.right - 12 - c.kbdWidth("/"), fieldRect.y + 8)
            val q = query()
            val head = if (q.isNotEmpty()) "${list.size} matching" else if (s.cat == "all") "Every forge recipe" else if (s.cat == ForgeSession.UNSORTED) "Not sorted yet" else s.cat
            val lockedCount = list.count { d.locks[it] != null }
            val headTop = fieldRect.bottom + 12
            c.text(g, head, fieldRect.x + 3, headTop, headTop + 16, ForgeColors.DIM, ui.em(10), false)
            if (lockedCount > 0) {
                val t = "$lockedCount locked"
                c.text(g, t, fieldRect.right - 8 - c.width(t, ui.em(10)), headTop, headTop + 16, ForgeColors.DIM, ui.em(10), false)
            }
        }
        c.endClip(g)
        val area = ForgeLayout.recipeList(r)
        val scroll = if (compact) 0 else {
            s.recipeScroll.content = list.size * ForgeLayout.R_PITCH
            s.recipeScroll.view = area.height
            s.recipeScroll.clamp()
            s.recipeScroll.pos.toInt()
        }
        vis = c.clip(g, intersect(area, view))
        if (visible(vis)) {
            if (list.isEmpty()) {
                val title = "No recipe matches \"${s.query.trim()}\""
                val cx = area.x + area.width / 2
                val ty = area.y + max(20, area.height / 2 - 30)
                c.text(g, title, cx - c.width(title, ui.em(17)) / 2, ty, ty + 27, 0xFFCCCCCC.toInt(), ui.em(17), false)
                val bw = c.width("Clear search", ui.em(11)) + 24
                c.button(g, cx + bw / 2, ty + 37, 18, SectionAction("Clear search") { clearQuery() }, vis, 11, 12)
            }
            for ((i, id) in list.withIndex()) {
                val row = ForgeLayout.recipeRow(area, i, scroll)
                if (row.bottom <= vis[1] || row.y >= vis[3]) continue
                recipeRow(g, id, row, vis)
            }
        }
        c.endClip(g)
        if (!compact) c.scrollbar(g, s.recipeScroll, area.x, max(area.y, view.y), area.right, max(area.y, min(area.bottom, view.bottom)))
        return 14 + ForgeLayout.FIELD_H + ForgeLayout.LIST_HEAD + max(list.size * ForgeLayout.R_PITCH, 90) + 10
    }

    private fun clearQuery() {
        field.set("")
        s.query = ""
        s.recipeScroll.pos = 0f
        field.focused = true
    }

    private fun recipeRow(g: GuiGraphicsExtractor, id: String, row: Rect, vis: IntArray) {
        val lock = d.locks[id]
        val selected = s.preview == id
        val hover = c.hovered(row, vis)
        if (selected) {
            c.panel(g, row.x, row.y, row.right, row.bottom, ForgeColors.SEL_BG, ForgeColors.SEL_EDGE, SoulPanel.SMALL)
        } else if (hover) c.rrect(g, row.x, row.y, row.right, row.bottom, ForgeColors.ROW_HOVER2, SoulRenderPipelines.ROUND_SMALL)
        c.region(row, vis) { s.preview = id; s.previewScroll.pos = 0f }
        ui.itemIcon(g, id, row.x + 9, row.y + 10, 20)
        if (lock != null) c.rrect(g, row.x + 9, row.y + 10, row.x + 29, row.y + 30, 0x8C1A1A1A.toInt(), SoulRenderPipelines.ROUND_SMALL)
        val goal = Forge.goal() == id
        val badge = if (goal) "GOAL" else if (directReady(id)) "CAN FORGE" else null
        var right = row.right - 9
        if (badge != null) {
            val bw = c.width(badge, ui.em(8)) + (badge.length * 0.8f).toInt() + 10
            val (fg, bg) = if (goal) 0xFF86B4FF.toInt() to 0xFF202F46.toInt() else ForgeColors.OK to 0xFF1D2E21.toInt()
            c.rrect(g, right - bw, row.y + 12, right, row.y + 29, bg, SoulRenderPipelines.ROUND_SMALL)
            var cx = right - bw + 5f
            for (ch in badge) { c.text(g, ch.toString(), cx.toInt(), row.y + 12, row.y + 29, fg, ui.em(8), false); cx += c.width(ch.toString(), ui.em(8)) + 0.8f }
            right -= bw + 9
        }
        val nx = row.x + 38
        c.text(g, c.fit(ui.name(id), right - nx, ui.em(12)), nx, row.y + 4, row.y + 21, if (lock != null) 0xFF8A8A8A.toInt() else ui.color(id), ui.em(12), false)
        val entry = d.index.forge[id]
        val sub = if (lock != null) listOf(Run(lock.removePrefix("Requires "), ForgeColors.MISS, lock = true))
            else if (entry != null) listOf(Run("${ui.dur(entry.baseSeconds)} · ${entry.inputs.size + if (entry.coins > 0) 1 else 0} inputs", ForgeColors.SUB)) else emptyList()
        ui.runs(g, nx, row.y + 21, row.y + 35, right, sub, ui.em(9))
    }

    // ---------------------------------------------------------------- preview / intro

    private fun preview(g: GuiGraphicsExtractor, r: Rect, view: Rect, compact: Boolean): Int {
        val inner = if (compact) Rect(r.x + 18, r.y + 16, r.right - 18, r.bottom) else ForgeLayout.previewInner(r)
        if (!compact) g.fill(r.x, r.y, r.right, r.bottom, 0x88181818.toInt())
        val pos = if (compact) 0 else s.previewScroll.pos.toInt()
        val vis = c.clip(g, intersect(Rect(r.x, r.y, r.right, r.bottom), view))
        var h = 0
        if (visible(vis)) {
            val id = s.preview?.takeIf { it in d.index.forge }
            val right = inner.right - 4
            h = if (id == null) intro(g, inner.x, inner.y - pos, right, inner.bottom, vis, compact) else previewPane(g, id, inner.x, inner.y - pos, right, vis)
        }
        c.endClip(g)
        if (!compact) {
            s.previewScroll.content = h + 16
            s.previewScroll.view = r.height
            s.previewScroll.clamp()
            c.scrollbar(g, s.previewScroll, r.x, max(r.y, view.y), r.right, max(r.y, min(r.bottom, view.bottom)))
        }
        return h + 32
    }

    private fun previewModel(id: String): PreviewModel {
        val key = listOf(id, d.index, d.locks, d.owned, d.qf)
        previewModel?.let { if (key == previewKey) return it }
        val book = ForgeRecipes(d.index, d.locks)
        val tree = ForgeEngine.expand(id, book, d.owned::have)
        val agg = ForgeEngine.aggregate(tree)
        val model = PreviewModel(ForgeEngine.timeLeft(tree, agg, ForgeEngine.SLOTS, d.qf), ForgeEngine.timeLeft(tree, agg, ForgeEngine.SLOTS, ForgeEngine.QF_MAX),
            ForgeEngine.gather(agg).count { it.missing > 0 })
        previewKey = key
        previewModel = model
        return model
    }

    private fun previewPane(g: GuiGraphicsExtractor, id: String, x: Int, top: Int, right: Int, vis: IntArray): Int {
        val entry = d.index.forge[id] ?: return 0
        val lock = d.locks[id]
        val pm = previewModel(id)
        var y = top
        ui.itemIcon(g, id, x, y + 3, 42)
        c.tracked(g, (categoryOf(id) ?: "Forge").uppercase(), x + 54, y + 5, y + 20, 9, ForgeColors.DIM)
        c.text(g, c.fit(ui.name(id), right - x - 54, ui.em(18)), x + 54, y + 26, y + 48, ui.color(id), ui.em(18), false)
        y += 58
        ui.runs(g, x, y, y + 18, right, listOf(Run("", 0, chip = "forge")) + ui.durRuns(entry.baseSeconds, 4, 0xFFCCCCCC.toInt()), ui.em(11))
        y += 18 + 8
        if (lock != null) {
            val lines = c.wrap(lock, right - x - 18, ui.em(10))
            val h = 16 + 15 * (lines.size + 1)
            c.panel(g, x, y, right, y + h, 0xFF251A18.toInt(), 0xFF4A2A26.toInt(), SoulPanel.SMALL)
            c.icon(g, "lock", x + 9, y + 11, 9, ForgeColors.MISS)
            lines.forEachIndexed { i, line -> c.text(g, line, x + 9 + if (i == 0) 13 else 0, y + 8 + i * 15, y + 23 + i * 15, ForgeColors.MISS, ui.em(10), false) }
            c.text(g, "From your last Forge visit. You can still plan it.", x + 9, y + 8 + lines.size * 15, y + 23 + lines.size * 15, ForgeColors.DIM, ui.em(10), false)
            y += h + 10
        }
        c.tracked(g, "ITEMS REQUIRED", x, y, y + 15, 9, ForgeColors.DIM)
        y += 15 + 6
        val inputs = entry.inputs.map { it.key to it.value } + if (entry.coins > 0) listOf(ForgeEngine.COINS to entry.coins) else emptyList()
        for ((cid, n) in inputs) {
            val have = d.owned.have(cid)
            val ok = have >= n
            ui.itemIcon(g, cid, x, y + 2, 20)
            val amount = ui.amt(n)
            val haveText = "${ui.amt(min(have, n))}/${ui.amt(n)}"
            val haveRight = right
            val amountRight = right - 66 - 6
            val hw = c.width(haveText, ui.em(9))
            c.text(g, haveText, haveRight - hw, y, y + ForgeLayout.IN_ROW, if (ok) ForgeColors.OK else ForgeColors.DIM, ui.em(9), false)
            if (ok) c.icon(g, "check", haveRight - hw - 13, y + 7, 10, ForgeColors.OK)
            c.text(g, amount, amountRight - c.width(amount, ui.em(11)), y, y + ForgeLayout.IN_ROW, ForgeColors.TEXT, ui.em(11), false)
            c.text(g, c.fit(ui.name(cid), amountRight - 44 - (x + 26), ui.em(11)), x + 26, y, y + ForgeLayout.IN_ROW, ui.color(cid), ui.em(11), false)
            y += ForgeLayout.IN_ROW + ForgeLayout.IN_GAP
        }
        y += 12 - ForgeLayout.IN_GAP
        g.fill(x, y, right, y + 1, ForgeColors.EDGE)
        c.tracked(g, "FROM WHAT YOU OWN", x, y + 11, y + 26, 9, ForgeColors.DIM)
        val bright = 0xFFDDDDDD.toInt()
        if (pm.time.processes > 0) {
            ui.runs(g, x, y + 37, y + 55, right, listOf(Run(ui.num(pm.time.processes), bright), Run(" forge processes · ", ForgeColors.SUB), Run("${pm.missing}", bright), Run(" base materials missing", ForgeColors.SUB)), ui.em(11))
            val qfBest = if (d.qf < ForgeEngine.QF_MAX) listOf(Run(" · ${ForgeFormat.duration(pm.best.bound, 2)} with QF 20", ForgeColors.DIM)) else emptyList()
            ui.runs(g, x, y + 55, y + 72, right, listOf(Run("at least ", ForgeColors.SUB), Run(ForgeFormat.duration(pm.time.bound, 2), bright), Run(" on ${ForgeEngine.SLOTS} slots", ForgeColors.SUB)) + qfBest, ui.em(11))
        } else {
            c.icon(g, "check", x, y + 42, 10, ForgeColors.OK)
            c.text(g, "Only this one process is left.", x + 13, y + 37, y + 55, ForgeColors.OK, ui.em(11), false)
        }
        y += 72 + 14
        val isGoal = Forge.goal() == id
        val goalSet = Forge.goal()?.takeIf { it in d.index.forge }
        val label = if (isGoal) "This is your goal" else if (goalSet != null) "Make this the goal" else "Set as goal"
        val bw = c.width(label, ui.em(12)) + 28
        c.button(g, x + bw, y, 28, SectionAction(label, accent = !isGoal) {
            if (isGoal) s.picking = false else setGoal(id)
        }, vis, 12, 14)
        if (goalSet != null && !isGoal) c.text(g, "Replaces ${ui.name(goalSet)}", x + bw + 10, y, y + 28, ForgeColors.DIM, ui.em(9), false)
        y += 28
        return y - top
    }

    private fun setGoal(id: String) {
        Forge.setGoal(id)
        s.picking = false
        s.select(null)
        s.open.clear()
        s.allOpen = false
        s.treeScroll.pos = 0f
        s.sideScroll.pos = 0f
    }

    private fun intro(g: GuiGraphicsExtractor, x: Int, top: Int, right: Int, bottom: Int, vis: IntArray, compact: Boolean): Int {
        var y = top
        c.tracked(g, "FORGE", x, y, y + 15, 9, ForgeColors.DIM)
        c.text(g, "What do you want to forge?", x, y + 19, y + 51, ForgeColors.TEXT, ui.em(20), false)
        val lines = c.wrap("Pick a recipe. Soul breaks it down to base materials, subtracts what you already have at every step and tells you what you can start now.", right - x, ui.em(12))
        lines.forEachIndexed { i, line ->
            val ly = y + 57 + Math.round(i * 19.2f)
            c.text(g, line, x, ly, ly + 19, 0xFF999999.toInt(), ui.em(12), false)
        }
        y += 57 + Math.round(lines.size * 19.2f) + 18
        val rows = listOf("island", "storage", "sacks", "inventory")
        for (id in rows) {
            val src = ForgeOwned.source(id) ?: continue
            val known = if (id == "inventory") true else d.known(id)
            val search = SearchEverywhere.sources.firstOrNull { it.id == id }
            val text = if (id == "island") (if (known) search?.coverage()?.long ?: "Chests remembered" else "No chests remembered yet")
                else if (id == "storage") (if (known) search?.coverage()?.long ?: "Pages seen" else "Not seen yet")
                else if (id == "sacks") (if (known) "Counted from sack menus and [Sacks] messages" else "Unknown: open your sacks once")
                else "Live"
            val r = Rect(x, y, right + 4 - 15, y + 60)
            c.panel(g, r.x, r.y, r.right, r.bottom, SoulPanel.over(0x88222222.toInt(), 0xFF303030.toInt()), 0xFF303030.toInt(), SoulPanel.SMALL)
            c.rrect(g, r.x + 12, r.y + 25, r.x + 21, r.y + 34, src.color, SoulRenderPipelines.ROUND_SMALL)
            c.text(g, src.label, r.x + 31, r.y + 10, r.y + 31, ForgeColors.TEXT, ui.em(13), false)
            c.text(g, c.fit(text, r.right - 12 - (r.x + 31), ui.em(10)), r.x + 31, r.y + 34, r.y + 50, if (known) 0xFF888888.toInt() else ForgeColors.AMBER, ui.em(10), false)
            y += 68
        }
        val pct = Math.round(d.qf * 100)
        val honest = listOf(if (d.qfInfo.known) "Times are yours: Quick Forge $pct%." else "Times are yours: Quick Forge not read yet.", "Your goal is remembered per profile.")
        val honestTop = if (compact) y + 6 else max(y + 6, bottom - 32)
        honest.forEachIndexed { i, line ->
            c.text(g, line, (x + right) / 2 - c.width(line, ui.em(10)) / 2, honestTop + i * 16, honestTop + i * 16 + 16, 0xFF626262.toInt(), ui.em(10), false)
        }
        return honestTop + 32 - top
    }
}
