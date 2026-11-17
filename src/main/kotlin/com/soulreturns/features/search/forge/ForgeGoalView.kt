package com.soulreturns.features.search.forge

import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.search.SearchEverywhere
import com.soulreturns.features.search.SearchEverywhereLayout
import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import com.soulreturns.features.search.SearchRow
import com.soulreturns.features.search.SectionAction
import net.minecraft.client.gui.GuiGraphicsExtractor
import kotlin.math.max
import kotlin.math.min

/**
 * The goal view of the Forge tab, layout "tree" (`forge.js` goalView): goal bar, notice strip, then the tree pane
 * on the left and Right now | Still to gather (or a material's details) on the right. Under 660 Soul px the two
 * panes stack and the part below the goal bar scrolls as one.
 */
internal class ForgeGoalView(private val ui: ForgeUi, private val showInSearch: (SearchRow) -> Unit) {
    private val c get() = ui.c
    private val d get() = ui.d
    private val s = ForgeSession
    private lateinit var m: ForgeModel
    private var compact = false
    private var lastSplitContent = 0

    // ---------------------------------------------------------------- entry

    fun draw(g: GuiGraphicsExtractor, body: Rect, compactMode: Boolean) {
        m = d.model ?: return
        compact = compactMode
        val notice = noticeText()
        val layout = ForgeLayout.goal(body, notice != null, compact)
        goalBar(g, layout.bar)
        if (notice != null && layout.notice != null) {
            val r = layout.notice
            g.fill(r.x, r.y, r.right, r.bottom, 0xFF221D14.toInt())
            g.fill(r.x, r.bottom - 1, r.right, r.bottom, 0xFF3A3020.toInt())
            c.clip(g, r)
            c.text(g, c.fit(notice, r.width - 40, ui.em(10)), r.x + 20, r.y, r.bottom, ForgeColors.AMBER, ui.em(10), false)
            c.endClip(g)
        }
        val split = layout.split
        if (compact) {
            s.bodyScroll.content = lastSplitContent
            s.bodyScroll.view = split.height
            s.bodyScroll.clamp()
            val shift = s.bodyScroll.pos.toInt()
            val treeInner = Rect(layout.leftInner.x, layout.leftInner.y - shift, layout.leftInner.right, split.bottom + 100000)
            val treeH = treePane(g, treeInner, split)
            val rightTop = treeInner.y + treeH
            val rightInner = Rect(layout.rightInner.x, rightTop, layout.rightInner.right, split.bottom + 100000)
            val rightH = rightPane(g, rightInner, split)
            lastSplitContent = ForgeLayout.PANE_TOP + treeH + rightH + 8
            c.bodyScrollbar(g, s.bodyScroll, split)
        } else {
            g.fill(layout.left.right - 1, split.y, layout.left.right, split.bottom, ForgeColors.EDGE)
            g.fill(layout.right.x, split.y, layout.right.right, split.bottom, 0x88181818.toInt())
            treePane(g, layout.leftInner, Rect(layout.left.x, split.y, layout.left.right - 1, split.bottom))
            rightPane(g, layout.rightInner, layout.right)
        }
    }

    private fun noticeText(): String? {
        val gaps = ArrayList<String>()
        if (!d.known("sacks")) gaps += "Sacks unknown: open your sacks once"
        if (d.owned.known["storage"] == false) gaps += "Storage not seen yet"
        if (d.owned.known["island"] == false) gaps += "No island chests remembered"
        return if (gaps.isEmpty()) null else "Counts may be low · " + gaps.joinToString(" · ")
    }

    private fun intersect(a: Rect, b: Rect) = Rect(max(a.x, b.x), max(a.y, b.y), min(a.right, b.right), min(a.bottom, b.bottom).coerceAtLeast(max(a.y, b.y)))

    private fun visible(vis: IntArray) = vis[2] > vis[0] && vis[3] > vis[1]

    // ---------------------------------------------------------------- goal bar

    private fun goalBar(g: GuiGraphicsExtractor, bar: Rect) {
        val id = m.goal
        g.fill(bar.x, bar.y, bar.right, bar.bottom, 0xFF1B1B1B.toInt())
        g.fill(bar.x, bar.bottom - 1, bar.right, bar.bottom, ForgeColors.EDGE)
        val vis = c.clip(g, bar)
        if (!visible(vis)) { c.endClip(g); return }
        ui.itemIcon(g, id, bar.x + 20, bar.y + 10, 42)
        val root = m.root
        val lock = root.recipe?.lock
        val btnLabel = "Change goal"
        val btnW = c.width(btnLabel, ui.em(12)) + 24
        val btnX1 = bar.right - 20
        c.button(g, btnX1, bar.y + 17, 28, SectionAction(btnLabel) { s.picking = true; s.preview = id; s.cat = d.cats.categoryOf(id) ?: "all"; s.recipeScroll.pos = 0f; ForgeTab.focusRequested = true }, vis, 12, 12)
        var statsRight = btnX1 - btnW - 12
        if (!compact) {
            val ready = root.times == 1L && ForgeEngine.readyTimes(root) == 1L
            val t = m.time
            val b = m.best
            if (ready && lock == null) {
                val n = root.recipe!!.inputs.count { it.first != ForgeEngine.COINS }
                val best = ui.bestDur(root.recipe.baseSeconds, 4)
                statsRight = okStat(g, statsRight, bar, vis, "Ready to forge", "all $n inputs owned · ${ui.dur(root.recipe.baseSeconds)}" + (best?.let { " · $it at QF 20" } ?: ""))
            } else {
                val leftMat = m.gather.count { it.missing > 0 }
                statsRight = stat(g, statsRight, bar, vis, "$leftMat", "of ${m.gather.size} materials missing", null) - 18
                val timeValue = if (t.processes > 0) ForgeFormat.duration(t.bound, 2) else "–"
                val label = "at least, on ${ForgeEngine.SLOTS} slots" + if (d.qf < ForgeEngine.QF_MAX && t.processes > 0) " · ${ForgeFormat.duration(b.bound, 2)} at QF 20" else ""
                val tip = arrayListOf("§fForge time left", "§7In sequence §f${ForgeFormat.duration(t.serial, 3)}", "§7Longest chain §f${ForgeFormat.duration(t.chain, 3)}",
                    "§7On ${ForgeEngine.SLOTS} slots §fat least ${ForgeFormat.duration(t.bound, 3)}")
                if (d.qf < ForgeEngine.QF_MAX) tip += "§8With Quick Forge 20: ${ForgeFormat.duration(b.bound, 3)} on ${ForgeEngine.SLOTS} slots, chain ${ForgeFormat.duration(b.chain, 3)}"
                tip += "§8If every slot is refilled as soon as it finishes"
                statsRight = stat(g, statsRight, bar, vis, timeValue, label, tip) - 18
                statsRight = stat(g, statsRight, bar, vis, ui.num(t.processes), "forges left of ${ui.num(m.totalProcesses)}", null)
            }
        }
        val nameX = bar.x + 74
        val nameRight = statsRight - 12
        val category = d.cats.categoryOf(id)
        val eyebrowTop = bar.y + if (lock != null) 7 else 13
        var x = c.tracked(g, "GOAL" + (category?.let { " · ${it.uppercase()}" } ?: ""), nameX, eyebrowTop, eyebrowTop + 15, 9, ForgeColors.DIM)
        if (lock != null) {
            x = c.tracked(g, " · ", x, eyebrowTop, eyebrowTop + 15, 9, ForgeColors.DIM)
            c.icon(g, "lock", x + 3, eyebrowTop + 3, 9, ForgeColors.MISS)
            c.tracked(g, "LOCKED", x + 14, eyebrowTop, eyebrowTop + 15, 9, ForgeColors.MISS)
        }
        c.text(g, c.fit(ui.name(id), nameRight - nameX, ui.em(18)), nameX, eyebrowTop + 15, eyebrowTop + 37, ui.color(id), ui.em(18), false)
        if (lock != null) c.text(g, c.fit("$lock · plan anyway, forge later", nameRight - nameX, ui.em(10)), nameX, eyebrowTop + 37, eyebrowTop + 52, ForgeColors.MISS, ui.em(10), false)
        c.endClip(g)
    }

    /** One `.stat`: value (17 px) over a 9 px label, right-aligned at [right]; returns the left edge. */
    private fun stat(g: GuiGraphicsExtractor, right: Int, bar: Rect, vis: IntArray, value: String, label: String, tip: List<String>?): Int {
        val w = max(c.width(value, ui.em(17)), c.width(label, ui.em(9)))
        val r = Rect(right - w, bar.y + 14, right, bar.y + 48)
        c.text(g, value, right - c.width(value, ui.em(17)), bar.y + 15, bar.y + 36, ForgeColors.TEXT, ui.em(17), false)
        c.text(g, label, right - c.width(label, ui.em(9)), bar.y + 36, bar.y + 48, 0xFF888888.toInt(), ui.em(9), false)
        if (tip != null && c.hovered(r, vis)) c.tip(r, tip)
        return r.x
    }

    /** `.stat.ok`: green check, 15 px title and a 9 px line; returns the left edge. */
    private fun okStat(g: GuiGraphicsExtractor, right: Int, bar: Rect, vis: IntArray, title: String, line: String): Int {
        val w = max(c.width(title, ui.em(15)), c.width(line, ui.em(9)))
        val left = right - w - 26
        c.icon(g, "check", left, bar.y + 22, 18, ForgeColors.OK)
        c.text(g, title, left + 26, bar.y + 15, bar.y + 34, ForgeColors.OK, ui.em(15), false)
        c.text(g, line, left + 26, bar.y + 34, bar.y + 46, 0xFF888888.toInt(), ui.em(9), false)
        return left
    }

    // ---------------------------------------------------------------- tree

    private fun isOpen(n: ForgeNode) = s.open[n.key] ?: (s.allOpen || n.depth == 0)

    private fun flatten(n: ForgeNode, out: ArrayList<ForgeNode>) {
        out += n
        if (n.children.isNotEmpty() && isOpen(n)) for (ch in n.children) flatten(ch, out)
    }

    /** Draws the tree pane inside [inner]; returns its content height (header included) for the stacked layout. */
    private fun treePane(g: GuiGraphicsExtractor, inner: Rect, view: Rect): Int {
        val rows = ArrayList<ForgeNode>().also { flatten(m.root, it) }
        val made = m.agg.filter { it.times > 0 }
        val headerRect = intersect(Rect(inner.x - ForgeLayout.LEFT_PAD_X, inner.y, inner.right + ForgeLayout.LEFT_PAD_RIGHT, inner.y + ForgeLayout.TREE_TOP), view)
        var vis = c.clip(g, headerRect)
        if (visible(vis)) {
            val mid = inner.y + ForgeLayout.PANE_HEADER
            var x = c.tracked(g, "TREE", inner.x, inner.y, mid, 9, ForgeColors.DIM)
            val forgeSteps = made.count { it.kind == StepKind.FORGE }
            val craftSteps = made.count { it.kind == StepKind.CRAFT }
            c.text(g, "$forgeSteps forge + $craftSteps craft steps left", x + 8, inner.y, mid, ForgeColors.DIM, ui.em(10), false)
            var tx = inner.right
            for ((label, action) in listOf("Collapse" to { s.allOpen = false; s.open.clear() }, "Expand all" to { s.allOpen = true; s.open.clear() })) {
                val w = c.width(label, ui.em(10))
                tx -= w
                val r = Rect(tx, inner.y, tx + w, mid)
                c.text(g, label, tx, inner.y, mid, ForgeColors.LINK, ui.em(10), false)
                c.region(r, vis, action)
                tx -= 10
            }
            val cols = ForgeLayout.treeHeader(inner)
            c.text(g, "Item", inner.x + 4, mid, mid + ForgeLayout.COLS, ForgeColors.FAINT, ui.em(9), false)
            for ((label, r) in listOf("Need" to cols.need, "Have" to cols.have, "Missing" to cols.miss)) {
                c.text(g, label, r.right - c.width(label, ui.em(9)), r.y, r.bottom, ForgeColors.FAINT, ui.em(9), false)
            }
            g.fill(inner.x, inner.y + ForgeLayout.TREE_TOP - 1, inner.right, inner.y + ForgeLayout.TREE_TOP, 0xFF262626.toInt())
        }
        c.endClip(g)
        val area = Rect(inner.x, inner.y + ForgeLayout.TREE_TOP, inner.right, inner.bottom)
        val scrollPos = if (compact) 0 else {
            s.treeScroll.content = rows.size * ForgeLayout.TREE_ROW
            s.treeScroll.view = area.height
            s.treeScroll.clamp()
            s.treeScroll.pos.toInt()
        }
        vis = c.clip(g, intersect(area, view))
        if (visible(vis)) for ((i, n) in rows.withIndex()) {
            val row = ForgeLayout.treeRow(inner, i, scrollPos)
            if (row.bottom <= vis[1] || row.y >= vis[3]) continue
            treeRow(g, n, row, vis)
        }
        c.endClip(g)
        if (!compact) c.scrollbar(g, s.treeScroll, area.x, max(area.y, view.y), area.right, max(area.y, min(area.bottom, view.bottom)))
        return ForgeLayout.TREE_TOP + rows.size * ForgeLayout.TREE_ROW
    }

    private fun treeRow(g: GuiGraphicsExtractor, n: ForgeNode, row: Rect, vis: IntArray) {
        val selected = s.sel == n.id
        val hover = c.hovered(row, vis)
        if (selected) {
            c.rrect(g, row.x, row.y, row.right, row.bottom, ForgeColors.SEL_EDGE, SoulRenderPipelines.ROUND_SMALL)
            c.rrect(g, row.x + 1, row.y + 1, row.right - 1, row.bottom - 1, ForgeColors.SEL_BG, SoulRenderPipelines.ROUND_SMALL)
        } else if (hover) c.rrect(g, row.x, row.y, row.right, row.bottom, ForgeColors.ROW_HOVER, SoulRenderPipelines.ROUND_SMALL)
        c.region(row, vis) { s.select(n.id) }
        val cells = ForgeLayout.treeCells(row, n.depth)
        val step = m.byId[n.id]
        val covered = n.missing == 0L
        val mid = row.y + ForgeLayout.TREE_ROW / 2
        if (n.children.isNotEmpty()) {
            val open = isOpen(n)
            val cx = cells.caret.x + 5f
            val cy = mid.toFloat()
            if (open) c.polygon(g, floatArrayOf(cx - 4f, cy - 2.5f, cx + 4f, cy - 2.5f, cx, cy + 2.5f), 0xFF888888.toInt())
            else c.polygon(g, floatArrayOf(cx - 2f, cy - 4f, cx + 3f, cy, cx - 2f, cy + 4f), 0xFF888888.toInt())
            c.region(cells.caret, vis) { s.open[n.key] = !open }
        } else c.rrect(g, cells.caret.x + 3, mid - 2, cells.caret.x + 7, mid + 2, 0xFF3A3A3A.toInt(), SoulRenderPipelines.ROUND_PILL)
        ui.itemIcon(g, n.id, cells.icon.x, cells.icon.y, 20)
        val nameColor = if (covered) ForgeColors.withAlpha(ui.color(n.id), 0xB3) else ui.color(n.id)
        c.text(g, c.fit(ui.name(n.id), cells.nameRight - cells.nameX, ui.em(12)), cells.nameX, row.y + 3, row.y + 17, nameColor, ui.em(12), false)
        val kind = if (covered && n.kind != StepKind.BASE && n.kind != StepKind.COINS && n.depth > 0)
            listOf(Run("", 0, chip = n.kind.name.lowercase()), Run("covered, nothing to make", ForgeColors.DIM))
        else ui.kindRuns(n, step)
        ui.runs(g, cells.nameX, row.y + 17, row.y + 29, cells.nameRight, kind, ui.em(9))
        val col = cells.columns
        val need = ui.amt(n.need)
        c.text(g, need, col.need.right - c.width(need, ui.em(12)), row.y, row.bottom, ForgeColors.TEXT, ui.em(12), false)
        val have = if (n.use > 0) ui.amt(n.use) else "0"
        c.text(g, have, col.have.right - c.width(have, ui.em(12)), row.y + 4, row.y + 19, if (n.use > 0) ForgeColors.TEXT else ForgeColors.DIM, ui.em(12), false)
        ui.sourceBar(g, col.have.x, row.y + 23, col.have.width, n.id, n.use, n.need)
        val haveRect = Rect(col.have.x, row.y + 2, col.have.right, row.bottom - 2)
        if (c.hovered(haveRect, vis)) c.tip(haveRect, ui.breakdown(n.id, n.use, n.need))
        ui.missingCell(g, col.miss.right, row.y, row.bottom, n, vis)
    }

    // ---------------------------------------------------------------- right column

    /** Draws the right column inside [inner] (details of a material, else the tabs); returns its content height. */
    private fun rightPane(g: GuiGraphicsExtractor, inner: Rect, view: Rect): Int {
        val sel = s.sel?.takeIf { m.byId.containsKey(it) || d.owned.have(it) > 0 }
        if (sel == null && s.sel != null) s.sel = null
        if (sel != null) return detailPane(g, inner, view, sel)
        val seg = ForgeLayout.segmented(inner)
        val segVis = c.clip(g, intersect(seg, view))
        if (visible(segVis)) {
            val now = m.plan.forge.size + m.plan.craft.size
            val gather = m.gather.count { it.missing > 0 }
            c.rrect(g, seg.x, seg.y, seg.right, seg.bottom, 0xFF333333.toInt(), SoulRenderPipelines.ROUND_SMALL)
            c.rrect(g, seg.x + 1, seg.y + 1, seg.right - 1, seg.bottom - 1, 0xFF1D1D1D.toInt(), SoulRenderPipelines.ROUND_SMALL)
            val half = (seg.width - 8) / 2
            for ((i, tab) in listOf("now" to "Right now $now", "gather" to "Still to gather $gather").withIndex()) {
                val bx = seg.x + 3 + i * (half + 2)
                val r = Rect(bx, seg.y + 3, bx + half, seg.bottom - 3)
                val on = (s.tab == "gather") == (i == 1)
                val hover = c.hovered(r, segVis)
                if (on) {
                    c.rrect(g, r.x, r.y, r.right, r.bottom, ForgeColors.SEL_EDGE, SoulRenderPipelines.ROUND_SMALL)
                    c.rrect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, ForgeColors.SEL_BG, SoulRenderPipelines.ROUND_SMALL)
                } else if (hover) c.rrect(g, r.x, r.y, r.right, r.bottom, 0xFF252525.toInt(), SoulRenderPipelines.ROUND_SMALL)
                val count = tab.second.substringAfterLast(' ')
                val label = tab.second.removeSuffix(" $count")
                val lw = c.width(label, ui.em(11))
                val cw = c.width(count, ui.em(10))
                val x = r.x + (r.width - lw - 4 - cw) / 2
                c.text(g, label, x, r.y, r.bottom, if (on || hover) ForgeColors.TEXT else 0xFF999999.toInt(), ui.em(11), false)
                c.text(g, count, x + lw + 4, r.y, r.bottom, 0xFF9DB4D1.toInt(), ui.em(10), false)
                c.region(r, segVis) { s.tab = tab.first; s.select(null); s.sideScroll.pos = 0f }
            }
        }
        c.endClip(g)
        val scroll = ForgeLayout.sideScroll(inner)
        val contentRight = scroll.right - ForgeLayout.SCROLL_PAD
        val pos = if (compact) 0 else s.sideScroll.pos.toInt()
        val vis = c.clip(g, intersect(scroll, view))
        var h = 0
        if (visible(vis)) h = if (s.tab == "gather") gatherPane(g, scroll.x, scroll.y - pos, contentRight, vis) else nowPane(g, scroll.x, scroll.y - pos, contentRight, vis)
        c.endClip(g)
        if (!compact) {
            s.sideScroll.content = h + 8
            s.sideScroll.view = scroll.height
            s.sideScroll.clamp()
            c.scrollbar(g, s.sideScroll, scroll.x, max(scroll.y, view.y), scroll.right, max(max(scroll.y, view.y), min(scroll.bottom, view.bottom)))
        }
        return ForgeLayout.SEG + ForgeLayout.SEG_GAP + h + 8
    }

    private fun sectionHeader(g: GuiGraphicsExtractor, x: Int, right: Int, y: Int, first: Boolean, label: String, small: String): Int {
        val top = y + if (first) 2 else 12
        c.tracked(g, label, x + 2, top, top + 15, 9, 0xFF999999.toInt())
        c.text(g, small, right - 2 - c.width(small, ui.em(9)), top, top + 15, ForgeColors.FAINT, ui.em(9), false)
        return if (first) ForgeLayout.SEC_FIRST else ForgeLayout.SEC_NEXT
    }

    private fun row(g: GuiGraphicsExtractor, r: Rect, vis: IntArray, id: String) {
        val hover = c.hovered(r, vis)
        if (hover) c.rrect(g, r.x, r.y, r.right, r.bottom, ForgeColors.ROW_HOVER2, SoulRenderPipelines.ROUND_SMALL)
        c.region(r, vis) { s.select(id) }
    }

    private fun dashed(g: GuiGraphicsExtractor, r: Rect, color: Int) {
        var x = r.x
        while (x < r.right) { val e = min(x + 3, r.right); g.fill(x, r.y, e, r.y + 1, color); g.fill(x, r.bottom - 1, e, r.bottom, color); x += 6 }
        var y = r.y
        while (y < r.bottom) { val e = min(y + 3, r.bottom); g.fill(r.x, y, r.x + 1, e, color); g.fill(r.right - 1, y, r.right, e, color); y += 6 }
    }

    private fun slotStrip(g: GuiGraphicsExtractor, x: Int, y: Int, vis: IntArray) {
        class Cell(val kind: String, val id: String?, val sub: String, val tip: List<String>)
        val cells = ArrayList<Cell>()
        val pct = Math.round(d.qf * 100)
        for (b in d.busy) {
            val shown = ForgeEngine.shownSeconds(b.remainingBase, d.qf)
            val tip = arrayListOf("§f${ui.name(b.outputId)}", "§7Running · §a${ForgeFormat.duration(shown, 3)} left")
            if (d.qf < ForgeEngine.QF_MAX) tip += "§8${ForgeFormat.duration(ForgeEngine.shownSeconds(b.remainingBase, ForgeEngine.QF_MAX), 3)} with Quick Forge 20"
            cells += Cell("busy", b.outputId, ForgeFormat.duration(shown, 2), tip)
        }
        for (u in d.slots.filterIsInstance<Forge.Unknown>()) cells += Cell("unknown", null, "?", listOf("§fSlot not read", "§7Open The Forge to refresh"))
        for (id in m.plan.slots) {
            val base = m.byId[id]?.recipe?.baseSeconds ?: 0
            cells += Cell("plan", id, ui.dur(base).split(' ').take(2).joinToString(" "), listOf("§f${ui.name(id)}", "§aReady to start", "§7${ui.dur(base)} at Quick Forge $pct%"))
        }
        while (cells.size < ForgeEngine.SLOTS) cells += Cell("idle", null, "idle", emptyList())
        for ((i, cell) in cells.take(ForgeEngine.SLOTS).withIndex()) {
            val r = ForgeLayout.slot(x, y, i)
            val (edge, fill, text) = if (cell.kind == "busy") Triple(0xFF5A3F2C.toInt(), 0xFF2A2019.toInt(), 0xFFE8A882.toInt())
                else if (cell.kind == "plan") Triple(ForgeColors.SEL_EDGE, ForgeColors.SEL_BG, 0xFFB9D5F8.toInt())
                else Triple(0xFF303030.toInt(), 0, 0xFF888888.toInt())
            if (cell.kind == "idle" || cell.kind == "unknown") dashed(g, r, edge)
            else {
                c.rrect(g, r.x, r.y, r.right, r.bottom, edge, SoulRenderPipelines.ROUND_SMALL)
                c.rrect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, fill, SoulRenderPipelines.ROUND_SMALL)
            }
            if (cell.id != null) {
                ui.itemIcon(g, cell.id, r.x + 12, r.y + 5, 20)
                c.text(g, c.fit(cell.sub, r.width - 2, ui.em(8)), r.x + (r.width - c.width(c.fit(cell.sub, r.width - 2, ui.em(8)), ui.em(8))) / 2, r.y + 27, r.y + 39, text, ui.em(8), false)
            } else if (cell.kind == "unknown") c.text(g, "?", r.x + (r.width - c.width("?", ui.em(13))) / 2, r.y, r.bottom, text, ui.em(13), false)
            else c.text(g, "idle", r.x + (r.width - c.width("idle", ui.em(8))) / 2, r.y, r.bottom, text, ui.em(8), false)
            if (cell.tip.isNotEmpty() && c.hovered(r, vis)) c.tip(r, cell.tip)
        }
    }

    /** Right now; returns its content height. */
    private fun nowPane(g: GuiGraphicsExtractor, x: Int, top: Int, right: Int, vis: IntArray): Int {
        val p = m.plan
        val root = m.root
        val rootReady = root.times == 1L && ForgeEngine.readyTimes(root) == 1L
        var y = top
        if (rootReady) {
            val r = Rect(x, y, right, y + ForgeLayout.READY_CARD)
            c.rrect(g, r.x, r.y, r.right, r.bottom, ForgeColors.PILL_EDGE, SoulRenderPipelines.ROUND_MEDIUM)
            c.rrect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, ForgeColors.PILL_BG, SoulRenderPipelines.ROUND_MEDIUM)
            c.icon(g, "check", r.x + 12, r.y + 24, 18, ForgeColors.OK)
            c.text(g, "Everything is here.", r.x + 40, r.y + 14, r.y + 32, 0xFFCFE9D4.toInt(), ui.em(13), false)
            val base = root.recipe?.baseSeconds ?: 0
            ui.runs(g, r.x + 40, r.y + 34, r.y + 50, r.right - 12, listOf(Run("Forge ${ui.name(m.goal)} at The Forge: ", 0xFF9BC4A4.toInt())) + ui.durRuns(base, 4, 0xFF9BC4A4.toInt()), ui.em(10))
            y += ForgeLayout.READY_CARD + ForgeLayout.READY_GAP
        }
        val running = if (d.busy.isNotEmpty()) "${d.busy.size} running · " else ""
        y += sectionHeader(g, x, right, y, true, "FORGE NOW", "$running${d.free} of ${ForgeEngine.SLOTS} slots free")
        slotStrip(g, x, y, vis)
        y += ForgeLayout.SLOT_ROW
        for (a in p.forge) {
            val fetch = a.step.recipe?.let { ui.fetchText(it, max(1, a.now)) }.orEmpty()
            val h = if (fetch.isEmpty()) ForgeLayout.P_ROW else ForgeLayout.P_ROW_FETCH
            val r = Rect(x, y, right, y + h)
            if (r.bottom > vis[1] && r.y < vis[3]) {
                row(g, r, vis, a.step.id)
                val base = a.step.recipe?.baseSeconds ?: 0
                val sub = if (a.now > 0) "Start ${a.now} now" + if (a.step.ready > a.now) " · ${a.step.ready - a.now} more when a slot frees" else "" else "${a.step.ready} ready · no free slot"
                val strong = if (a.now > 0) "×${a.now}" else "–"
                val left = "${ui.num(a.step.times)} left"
                prowSides(g, r, a.step.id, strong, a.now > 0, left)
                val textRight = r.right - 9 - 50 - 9 - max(c.width(strong, ui.em(14)), 0) - 9
                c.text(g, c.fit(ui.name(a.step.id), textRight - (r.x + 38), ui.em(12)), r.x + 38, r.y + 6, r.y + 25, ui.color(a.step.id), ui.em(12), false)
                ui.runs(g, r.x + 38, r.y + 27, r.y + 42, textRight, listOf(Run("", 0, chip = "forge")) + ui.durRuns(base, 2, ForgeColors.SUB) + Run(" · $sub", ForgeColors.DIM), ui.em(9))
                if (fetch.isNotEmpty()) c.text(g, c.fit("Fetch first: $fetch", textRight - (r.x + 38), ui.em(9)), r.x + 38, r.y + 44, r.y + 59, ForgeColors.AMBER, ui.em(9), false)
            }
            y += h + ForgeLayout.ROW_GAP
        }
        if (p.forge.isEmpty()) {
            val text = if (p.waiting.isNotEmpty()) "No forge process has all its inputs yet." else "Nothing left to forge."
            c.text(g, text, x + 2, y + 4, y + 19, ForgeColors.DIM, ui.em(10), false)
            y += 23
        }
        if (p.craft.isNotEmpty()) {
            y += sectionHeader(g, x, right, y, false, "CRAFT NOW", "Crafting table or Supercraft")
            for (a in p.craft) {
                val recipe = a.recipe ?: continue
                val fetch = ui.fetchText(recipe, a.ready)
                val h = if (fetch.isEmpty()) ForgeLayout.P_ROW else ForgeLayout.P_ROW_FETCH
                val r = Rect(x, y, right, y + h)
                if (r.bottom > vis[1] && r.y < vis[3]) {
                    row(g, r, vis, a.id)
                    val strong = "×${ui.amt(a.ready)}"
                    val left = "${ui.num(a.times)} left"
                    val btn = SectionAction("Recipe") { Forge.viewRecipe(a.id) }
                    val btnX1 = r.right - 9 - 50 - 9 - c.width(strong, ui.em(14)) - 9
                    val bw = c.width("Recipe", ui.em(11)) + 24
                    val by = r.y + (r.height - 22) / 2
                    c.button(g, btnX1, by, 22, btn, vis, 11, 12)
                    val br = Rect(btnX1 - bw, by, btnX1, by + 22)
                    if (c.hovered(br, vis)) c.tip(br, ui.viewTip(a.id, recipe, a.ready))
                    prowSides(g, r, a.id, strong, true, left)
                    val textRight = btnX1 - bw - 9
                    val sub = (if (recipe.inputs.size == 1) "${ui.num(recipe.inputs[0].second)} ${ui.name(recipe.inputs[0].first)} → 1" else "${recipe.inputs.size} inputs → 1")
                    c.text(g, c.fit(ui.name(a.id), textRight - (r.x + 38), ui.em(12)), r.x + 38, r.y + 6, r.y + 25, ui.color(a.id), ui.em(12), false)
                    ui.runs(g, r.x + 38, r.y + 27, r.y + 42, textRight, listOf(Run("", 0, chip = "craft"), Run(sub, ForgeColors.SUB), Run(" · " + if (a.ready == a.times) "all" else "${ui.num(a.ready)} of ${ui.num(a.times)}", ForgeColors.DIM)), ui.em(9))
                    if (fetch.isNotEmpty()) c.text(g, c.fit("Fetch first: $fetch", textRight - (r.x + 38), ui.em(9)), r.x + 38, r.y + 44, r.y + 59, ForgeColors.AMBER, ui.em(9), false)
                }
                y += h + ForgeLayout.ROW_GAP
            }
        }
        val waiting = p.waiting.filter { !(it.step.kind == StepKind.FORGE && it.step.ready > 0) }
        if (waiting.isNotEmpty()) {
            y += sectionHeader(g, x, right, y, false, "WAITING FOR MATERIALS", "${waiting.size} steps")
            for (w in waiting.take(40)) {
                val r = Rect(x, y, right, y + ForgeLayout.W_ROW)
                if (r.bottom > vis[1] && r.y < vis[3]) {
                    row(g, r, vis, w.step.id)
                    ui.itemIcon(g, w.step.id, r.x + 9, r.y + 12, 20)
                    val strong = "×${ui.amt(w.step.times - w.step.ready)}"
                    c.text(g, strong, r.right - 9 - c.width(strong, ui.em(12)), r.y, r.bottom, ForgeColors.DIM, ui.em(12), false)
                    val textRight = r.right - 9 - c.width(strong, ui.em(12)) - 9
                    c.text(g, c.fit(ui.name(w.step.id), textRight - (r.x + 38), ui.em(12)), r.x + 38, r.y + 4, r.y + 23, ui.color(w.step.id), ui.em(12), false)
                    val sub = if (w.locked) listOf(Run(w.step.recipe?.lock.orEmpty(), ForgeColors.MISS, lock = true))
                        else if (w.block != null) listOf(Run("needs ${ui.name(w.block.id)}", ForgeColors.SUB), Run(" · ${ui.amt(w.block.use)} of ${ui.amt(w.block.need)}", ForgeColors.DIM)) else emptyList()
                    ui.runs(g, r.x + 38, r.y + 25, r.y + 40, textRight, sub, ui.em(9))
                }
                y += ForgeLayout.W_ROW + ForgeLayout.ROW_GAP
            }
        }
        return y - top
    }

    /** The right edge of a `.p-row`: ×count and "N left" (9 px, 50 wide). */
    private fun prowSides(g: GuiGraphicsExtractor, r: Rect, id: String, strong: String, active: Boolean, left: String) {
        ui.itemIcon(g, id, r.x + 9, r.y + (r.height - 20) / 2, 20)
        val leftX = r.right - 9 - 50
        c.text(g, left, leftX + 50 - c.width(left, ui.em(9)), r.y, r.bottom, ForgeColors.FAINT, ui.em(9), false)
        c.text(g, strong, leftX - 9 - c.width(strong, ui.em(14)), r.y, r.bottom, if (active) ForgeColors.TEXT else ForgeColors.DIM, ui.em(14), false)
    }

    /** Still to gather; returns its content height. */
    private fun gatherPane(g: GuiGraphicsExtractor, x: Int, top: Int, right: Int, vis: IntArray): Int {
        val rows = m.gather
        val missing = rows.count { it.missing > 0 }
        var y = top + sectionHeader(g, x, right, top, true, "STILL TO GATHER", "$missing of ${rows.size} base materials")
        val probe = ForgeLayout.gatherCells(Rect(x, y, right, y + ForgeLayout.GATHER_ROW))
        c.text(g, "Material", x + 4, y, y + ForgeLayout.COLS, ForgeColors.FAINT, ui.em(9), false)
        for ((label, r) in listOf("Need" to probe.columns.need, "Have" to probe.columns.have, "Missing" to probe.columns.miss)) {
            c.text(g, label, r.right - c.width(label, ui.em(9)), y, y + ForgeLayout.COLS, ForgeColors.FAINT, ui.em(9), false)
        }
        y += ForgeLayout.COLS
        for (a in rows) {
            val forms = m.compacted[a.id].orEmpty()
            val open = forms.isNotEmpty() && s.gatherOpen[a.id] ?: ForgeCompacted.defaultOpen(forms, d.owned::have)
            val r = Rect(x, y, right, y + ForgeLayout.GATHER_ROW)
            y += ForgeLayout.GATHER_PITCH
            if (r.bottom > vis[1] && r.y < vis[3]) gatherRow(g, r, vis, a, false, forms, open)
            if (!open) continue
            val lineTop = y - 1
            for (f in forms) {
                val fr = Rect(x, y, right, y + ForgeLayout.FORM_ROW)
                y += ForgeLayout.FORM_PITCH
                if (fr.bottom > vis[1] && fr.y < vis[3]) gatherRow(g, fr, vis, f, true, emptyList(), false)
            }
            g.fill(x + ForgeLayout.FORM_INDENT - 3, lineTop, x + ForgeLayout.FORM_INDENT - 2, y - ForgeLayout.FORM_PITCH + ForgeLayout.FORM_ROW - 8, 0xFF2E2E2E.toInt())
        }
        return y - top
    }

    /**
     * One Still to gather row. A base material with compacted [forms] toggles them on a click of the row; the
     * icon keeps the row's old action (details). Form rows ([form]) are the same row smaller and dimmer, indented.
     */
    private fun gatherRow(g: GuiGraphicsExtractor, r: Rect, vis: IntArray, a: ForgeStep, form: Boolean, forms: List<ForgeStep>, open: Boolean) {
        val selected = s.sel == a.id
        val hover = c.hovered(r, vis)
        if (selected) {
            c.rrect(g, r.x, r.y, r.right, r.bottom, ForgeColors.SEL_EDGE, SoulRenderPipelines.ROUND_SMALL)
            c.rrect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, ForgeColors.SEL_BG, SoulRenderPipelines.ROUND_SMALL)
        } else if (hover) c.rrect(g, r.x, r.y, r.right, r.bottom, ForgeColors.ROW_HOVER2, SoulRenderPipelines.ROUND_SMALL)
        val foldable = forms.isNotEmpty()
        if (foldable) c.region(r, vis) { s.gatherOpen[a.id] = !open } else c.region(r, vis) { s.select(a.id) }
        val cells = ForgeLayout.gatherCells(r, if (form) ForgeLayout.FORM_INDENT else 0, form)
        if (foldable) c.region(cells.icon, vis) { s.select(a.id) }
        ui.itemIcon(g, a.id, cells.icon.x, cells.icon.y, 20)
        if (foldable && c.hovered(cells.icon, vis)) c.tip(cells.icon, listOf("§fShow details"))
        val covered = a.missing == 0L
        val size = if (form) 11 else 12
        val base = ui.color(a.id)
        val nameColor = if (covered || form) ForgeColors.withAlpha(base, if (form && !covered) 0xCC else 0xB3) else base
        val nameW = cells.nameRight - cells.nameX
        val nameTop = if (form) r.y + 4 else r.y + 8
        val shown = c.fit(ui.name(a.id), nameW, ui.em(size))
        c.text(g, shown, cells.nameX, nameTop, nameTop + 15, nameColor, ui.em(size), false)
        if (foldable) {
            val cx = cells.nameX + c.width(shown, ui.em(size)) + 12f
            val cy = nameTop + 7.5f
            if (cx + 6 < cells.nameRight) {
                if (open) c.polygon(g, floatArrayOf(cx - 4f, cy - 2.5f, cx + 4f, cy - 2.5f, cx, cy + 2.5f), 0xFF888888.toInt())
                else c.polygon(g, floatArrayOf(cx - 2f, cy - 4f, cx + 3f, cy, cx - 2f, cy + 4f), 0xFF888888.toInt())
                val room = cells.nameRight - (cx.toInt() + 9)
                val n = forms.size
                val note = listOf(if (n == 1) "1 compacted form" else "$n compacted forms", if (n == 1) "1 form" else "$n forms")
                    .firstOrNull { c.width(it, ui.em(9)) <= room }.orEmpty()
                c.text(g, note, cx.toInt() + 9, nameTop, nameTop + 15, ForgeColors.DIM, ui.em(9), false)
            }
        }
        ui.sourceBar(g, cells.nameX, if (form) r.y + 23 else r.y + 28, nameW, a.id, a.use, a.need)
        val numSize = if (form) 11 else 12
        val dimOr = { on: Boolean, color: Int -> if (form && on) ForgeColors.SUB else color }
        val need = ui.amt(a.need)
        c.text(g, need, cells.columns.need.right - c.width(need, ui.em(numSize)), r.y, r.bottom, dimOr(true, ForgeColors.TEXT), ui.em(numSize), false)
        val have = if (a.use > 0) ui.amt(a.use) else "0"
        c.text(g, have, cells.columns.have.right - c.width(have, ui.em(numSize)), r.y, r.bottom, if (a.use > 0) dimOr(true, ForgeColors.TEXT) else ForgeColors.DIM, ui.em(numSize), false)
        val haveRect = Rect(cells.columns.have.x, r.y, cells.columns.have.right, r.bottom)
        if (c.hovered(haveRect, vis)) c.tip(haveRect, ui.breakdown(a.id, a.use, a.need))
        val iconY = r.y + r.height / 2 - 5
        if (a.missing > 0) {
            val t = ui.amt(a.missing)
            c.text(g, t, cells.columns.miss.right - c.width(t, ui.em(numSize)), r.y, r.bottom, ForgeColors.MISS, ui.em(numSize), false)
        } else {
            val w = c.width("Have it", ui.em(11))
            c.icon(g, "check", cells.columns.miss.right - w - 13, iconY, 10, ForgeColors.OK)
            c.text(g, "Have it", cells.columns.miss.right - w, r.y, r.bottom, ForgeColors.OK, ui.em(11), false)
        }
    }

    // ---------------------------------------------------------------- material details

    private fun searchRow(id: String): SearchRow? {
        val ids = SearchEverywhere.active().map { it.id }
        return SearchEverywhere.index.rows.filter { it.item.skyblockId == id }.maxByOrNull { it.total(ids) }
    }

    private class Static(val source: String, val label: String, val count: Long, val note: String, val color: Int)

    private fun detailPane(g: GuiGraphicsExtractor, inner: Rect, view: Rect, id: String): Int {
        val a = m.byId[id]
        val parts = d.owned.bySource(id)
        val total = d.owned.have(id)
        val kind = a?.kind ?: StepKind.BASE
        val area = Rect(inner.x, inner.y, inner.right, max(inner.y, inner.bottom))
        val pos = if (compact) 0 else s.detailScroll.pos.toInt()
        val x = inner.x
        val right = inner.right - 6
        val vis = c.clip(g, intersect(area, view))
        var y = inner.y - pos
        if (!visible(vis)) { c.endClip(g); return 400 }
        // Back link.
        val back = if (s.tab == "gather") "Still to gather" else "Right now"
        c.polygon(g, floatArrayOf(x + 5f, y + 9f, x + 5f, y + 17f, x.toFloat(), y + 13f), ForgeColors.LINK)
        c.text(g, back, x + 12, y, y + 18, ForgeColors.LINK, ui.em(11), false)
        c.region(Rect(x, y, x + 14 + c.width(back, ui.em(11)), y + 22), vis) { s.select(null) }
        y += ForgeLayout.D_BACK
        // Header.
        ui.itemIcon(g, id, x, y + 3, 42)
        val st = a?.let { ForgeEngine.status(it) }
        val eyebrow = if (kind == StepKind.FORGE) "FORGE STEP · ${ui.dur(a!!.recipe!!.baseSeconds).uppercase()} EACH" else if (kind == StepKind.CRAFT) "CRAFT STEP" else if (kind == StepKind.COINS) "COINS" else "BASE MATERIAL"
        c.tracked(g, eyebrow, x + 54, y + 5, y + 20, 9, ForgeColors.DIM)
        val row = searchRow(id)
        var nameRight = right
        if (row != null) {
            val label = "Show in Search"
            val w = c.width(label, ui.em(11))
            val r = Rect(right - w, y + 3, right, y + 21)
            c.text(g, label, right - w, y + 3, y + 21, ForgeColors.LINK, ui.em(11), false)
            c.region(r, vis) { showInSearch(row) }
        }
        c.text(g, c.fit(ui.name(id), nameRight - (x + 54), ui.em(18)), x + 54, y + 26, y + 48, ui.color(id), ui.em(18), false)
        y += ForgeLayout.D_HEAD
        // Summary.
        val colW = (right - x - 2) / 3
        val labels = listOf("Need", "Have", "Missing")
        for (i in 0..2) c.text(g, labels[i], x + 1 + i * colW, y + 10, y + 24, ForgeColors.DIM, ui.em(9), false)
        c.text(g, ui.num(a?.need ?: 0), x + 1, y + 24, y + 50, ForgeColors.TEXT, ui.em(16), false)
        c.text(g, ui.num(total), x + 1 + colW, y + 24, y + 50, ForgeColors.TEXT, ui.em(16), false)
        val mx = x + 1 + 2 * colW
        if (a == null || a.missing == 0L) c.text(g, "none", mx, y + 24, y + 50, ForgeColors.OK, ui.em(16), false)
        else if (st!!.state == StepState.MISSING) c.text(g, ui.num(a.missing), mx, y + 24, y + 50, ForgeColors.MISS, ui.em(16), false)
        else {
            val label = (if (kind == StepKind.FORGE) "Forge " else "Craft ") + ui.amt(st.make)
            c.text(g, label, mx, y + 24, y + 50, ForgeColors.OK, ui.em(16), false)
            if (st.short > 0) c.text(g, "${ui.amt(st.short)} short", mx + c.width(label, ui.em(16)) + 4, y + 24, y + 50, ForgeColors.MISS, ui.em(9), false)
        }
        g.fill(x, y + ForgeLayout.D_SUM - 1, right, y + ForgeLayout.D_SUM, ForgeColors.EDGE)
        y += ForgeLayout.D_SUM + ForgeLayout.D_GAP
        // Made from.
        val recipe = a?.recipe
        if (a != null && recipe != null) {
            c.tracked(g, "MADE FROM, PER " + if (kind == StepKind.FORGE) "PROCESS" else "CRAFT", x, y + 4, y + 19, 9, ForgeColors.DIM)
            y += ForgeLayout.MADE_EYEBROW
            for ((cid, n) in recipe.inputs) {
                val r = Rect(x, y, right, y + ForgeLayout.MINI_ROW)
                ui.itemIcon(g, cid, x, y + 2, 20)
                val hover = c.hovered(r, vis)
                val nm = ui.name(cid)
                c.text(g, c.fit(nm, right - x - 27 - 60, ui.em(11)), x + 27, y, y + ForgeLayout.MINI_ROW, ui.color(cid), ui.em(11), false)
                if (hover) g.fill(x + 27, y + 17, x + 27 + c.width(c.fit(nm, right - x - 27 - 60, ui.em(11)), ui.em(11)), y + 18, ui.color(cid))
                val num = ui.num(n)
                c.text(g, num, right - c.width(num, ui.em(11)), y, y + ForgeLayout.MINI_ROW, 0xFFCCCCCC.toInt(), ui.em(11), false)
                c.region(r, vis) { s.select(cid) }
                y += ForgeLayout.MINI_ROW
            }
            if (kind == StepKind.CRAFT && st != null && (st.state == StepState.READY || st.state == StepState.PARTIAL)) {
                val by = y + 6
                val w = c.width("Open recipe in game", ui.em(11)) + 24
                val br = Rect(x, by, x + w, by + 24)
                c.button(g, x + w, by, 24, SectionAction("Open recipe in game", accent = true) { Forge.viewRecipe(id) }, vis, 11, 12)
                if (c.hovered(br, vis)) c.tip(br, ui.viewTip(id, recipe, st.make))
                val fetch = ui.fetchText(recipe, st.make)
                if (fetch.isNotEmpty()) c.text(g, c.fit("Fetch first: $fetch", right - x - w - 10, ui.em(9)), x + w + 10, by, by + 24, ForgeColors.AMBER, ui.em(9), false)
                y += ForgeLayout.RECIPE_ROW
            }
            if (a.times > 0) {
                val text = "${ui.num(a.times)} ${if (kind == StepKind.FORGE) "processes" else "crafts"} to go · ${ui.num(a.ready)} can start now"
                val runs = arrayListOf(Run(text, ForgeColors.DIM))
                if (kind == StepKind.FORGE) { runs += Run(" · ", ForgeColors.DIM); runs += ui.durRuns(recipe.baseSeconds * a.times, 2, ForgeColors.DIM); runs += Run(" in sequence", ForgeColors.DIM) }
                ui.runs(g, x, y + 4, y + 19, right, runs, ui.em(10))
                y += ForgeLayout.MADE_NOTE
            }
            y += ForgeLayout.D_GAP
        }
        // Used for.
        val parents = m.agg.filter { p -> p.times > 0 && p.recipe?.inputs?.any { it.first == id } == true }
        if (parents.isNotEmpty()) {
            var lx = x
            var ly = y
            fun piece(text: String, color: Int, link: String?) {
                val w = c.width(text, ui.em(10))
                if (lx > x && lx + w > right) { lx = x; ly += 15 }
                c.text(g, text, lx, ly, ly + 16, color, ui.em(10), false)
                if (link != null) c.region(Rect(lx, ly, lx + w, ly + 16), vis) { s.select(link) }
                lx += w
            }
            piece("Used for ", 0xFF888888.toInt(), null)
            for ((i, p) in parents.withIndex()) {
                piece(ui.name(p.id), ForgeColors.LINK, p.id)
                if (i < parents.size - 1) piece(", ", 0xFF888888.toInt(), null)
            }
            y = ly + ForgeLayout.USED + ForgeLayout.D_GAP
        }
        // Places: the search screen's real sections, then static headers.
        val active = SearchEverywhere.active()
        val sources = if (row == null) emptyList() else active.filter { row.has(it.id) }
        var placesH = 0
        if (row != null && sources.isNotEmpty()) {
            placesH = c.sectionsHeight(row, sources, right - x)
            c.drawSections(g, row, sources, x, right, y, vis)
        }
        val searchIds = SearchEverywhere.sources.map { it.id }
        val statics = parts.filter { it.source !in searchIds }.map { p ->
            val label = if (p.source == "purse") "PURSE" else if (p.source == "bank") "BANK" else (ForgeOwned.source(p.source)?.label ?: p.source).uppercase()
            val note = if (p.source == "sacks") "from your sacks" else if (p.source == "inventory") "carried now" else if (p.source == "forge") "running in the forge" else ""
            Static(p.source, label, p.count, note, if (p.source == "purse" || p.source == "bank") ForgeOwned.COIN_COLOR else ForgeOwned.source(p.source)?.color ?: ForgeColors.DIM)
        }
        var py = y + placesH
        for (st2 in statics) {
            if (py > y) py += 8 + 4
            val r = Rect(x, py, right - 2, py + SearchEverywhereLayout.SECTION_H)
            if (r.bottom > vis[1] && r.y < vis[3]) staticHeader(g, r, st2)
            py += SearchEverywhereLayout.SECTION_H
        }
        if (py == y) {
            c.text(g, "You have none of it in any remembered place.", x + 2, y + 4, y + 19, ForgeColors.DIM, ui.em(10), false)
            py = y + 23
        }
        // Closing note.
        val uncached = listOfNotNull(
            if (!d.known("sacks")) "Sacks unknown" else null,
            if (d.owned.known["storage"] == false) "storage not seen yet" else null,
            if (d.owned.known["island"] == false) "no island chests yet" else null,
        )
        val note = if (uncached.isEmpty()) "What you last saw, not a live count. Opening a chest or page refreshes it." else uncached.joinToString(" · ") + ": you may have more."
        var ny = py + ForgeLayout.NOTE_GAP
        for (line in c.wrap(note, right - x, ui.em(10))) {
            c.text(g, line, x, ny, ny + 15, if (uncached.isEmpty()) ForgeColors.DIM else ForgeColors.AMBER, ui.em(10), false)
            ny += 15
        }
        c.endClip(g)
        val content = ny - (inner.y - pos) + 8
        if (!compact) {
            s.detailScroll.content = content
            s.detailScroll.view = area.height
            s.detailScroll.clamp()
            c.scrollbar(g, s.detailScroll, area.x, max(area.y, view.y), area.right, max(max(area.y, view.y), min(area.bottom, view.bottom)))
        }
        return content
    }

    private fun staticHeader(g: GuiGraphicsExtractor, r: Rect, st: Static) {
        c.rrect(g, r.x, r.y, r.right, r.bottom, 0xFF303030.toInt(), SoulRenderPipelines.ROUND_SMALL)
        c.rrect(g, r.x + 1, r.y + 1, r.right - 1, r.bottom - 1, 0xFF1F1F1F.toInt(), SoulRenderPipelines.ROUND_SMALL)
        val cy = r.y + 18
        c.rrect(g, r.x + 23, cy - 3, r.x + 30, cy + 4, st.color, SoulRenderPipelines.ROUND_SMALL)
        var x = c.tracked(g, st.label, r.x + 37, r.y, r.bottom, 10, 0xFFAAAAAA.toInt()) + 11
        val count = ui.num(st.count)
        c.text(g, count, x, r.y, r.bottom, 0xFFDDDDDD.toInt(), ui.em(12), false)
        x += c.width(count, ui.em(12)) + 11
        if (st.note.isNotEmpty()) c.text(g, c.fit(st.note, r.right - 11 - x, ui.em(10)), x, r.y, r.bottom, ForgeColors.DIM, ui.em(10), false)
    }

    private fun focusSearch() { ForgeTab.focusRequested = true }
}
