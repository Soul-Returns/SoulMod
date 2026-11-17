package com.soulreturns.features.search.forge

import com.mojang.blaze3d.pipeline.RenderPipeline
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.data.recipes.RecipeIndex
import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import com.soulreturns.features.search.SearchRow
import com.soulreturns.features.search.SearchSource
import com.soulreturns.features.search.SectionAction
import com.soulreturns.features.search.StorageSource
import com.soulreturns.ui.screen.SoulScreenBase
import net.minecraft.client.gui.GuiGraphicsExtractor

/** What the Forge tab needs from the screen it lives in (`SoulScreenBase`'s helpers are protected). */
internal interface ForgeCanvas {
    val mx: Int
    val my: Int
    fun em(size: Number): Float
    fun rrect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int, pipeline: RenderPipeline)
    fun text(g: GuiGraphicsExtractor, s: String, x: Int, top: Int, bottom: Int, color: Int, size: Float, semibold: Boolean)
    fun width(s: String, size: Float): Int
    fun fit(s: String, maxW: Int, size: Float): String
    fun wrap(s: String, maxW: Int, size: Float): List<String>

    /** Clips to [r] inside the body and returns the visible rect (`x0, y0, x1, y1`); pair with [endClip]. */
    fun clip(g: GuiGraphicsExtractor, r: Rect): IntArray
    fun endClip(g: GuiGraphicsExtractor)

    /** A click region cut to [clip]. */
    fun region(r: Rect, clip: IntArray, action: () -> Unit)
    fun hovered(r: Rect, clip: IntArray): Boolean
    fun tip(r: Rect, lines: List<String>)
    fun kbdWidth(label: String): Int
    fun kbd(g: GuiGraphicsExtractor, label: String, x: Int, y: Int)
    fun tracked(g: GuiGraphicsExtractor, text: String, x: Int, top: Int, bottom: Int, size: Int, color: Int): Int
    fun scrollbar(g: GuiGraphicsExtractor, st: SoulScreenBase.ScrollState, x0: Int, y0: Int, x1: Int, y1: Int)

    /** The compact body's scrollbar; its wheel target sits behind the inner lists'. */
    fun bodyScrollbar(g: GuiGraphicsExtractor, st: SoulScreenBase.ScrollState, r: Rect)
    fun polygon(g: GuiGraphicsExtractor, points: FloatArray, color: Int)
    fun icon(g: GuiGraphicsExtractor, name: String, x: Int, y: Int, size: Int, color: Int)
    fun textField(g: GuiGraphicsExtractor, r: Rect, field: com.soulreturns.ui.screen.SoulTextField, placeholder: String, clip: IntArray)

    /** The screen's `.s-btn`, right edge at [x1]; returns its width. */
    fun button(g: GuiGraphicsExtractor, x1: Int, y: Int, h: Int, action: SectionAction, clip: IntArray, size: Int = 11, pad: Int = 10): Int

    /** The search screen's real source sections of [row] (collapsible headers, cards, Locate / Open). */
    fun sectionsHeight(row: SearchRow, sources: List<SearchSource>, width: Int): Int
    fun drawSections(g: GuiGraphicsExtractor, row: SearchRow, sources: List<SearchSource>, x: Int, right: Int, top: Int, clip: IntArray)
}

/** Everything one frame of the Forge tab reads, gathered once. */
internal class ForgeData(
    val owned: OwnedSnapshot,
    val qfInfo: Forge.QuickForge,
    val slots: List<Forge.SlotView>,
    val free: Int,
    val index: RecipeIndex,
    val locks: Map<String, String>,
    val catalog: String,
    val model: ForgeModel?,
    val book: ForgeBookData,
) {
    val qf: Double get() = qfInfo.fraction
    val busy: List<Forge.Busy> = slots.filterIsInstance<Forge.Busy>()
    fun known(source: String) = owned.known[source] == true
    val cats: ForgeCategories get() = ForgeCategories.current(index, book)
}

/** The Forge tab's own state: lives across screen instances so K returns to the same material (reset per profile). */
internal object ForgeSession {
    private var profile: String? = null
    var picking = false
    var cat = "all"
    var query = ""
    var preview: String? = null
    var sel: String? = null
    var tab = "now"
    val open = HashMap<String, Boolean>()

    /** Manual fold state of a base material's compacted forms in Still to gather (absent = the default). */
    val gatherOpen = HashMap<String, Boolean>()
    var allOpen = false
    val treeScroll = SoulScreenBase.ScrollState()
    val sideScroll = SoulScreenBase.ScrollState()
    val detailScroll = SoulScreenBase.ScrollState()
    val recipeScroll = SoulScreenBase.ScrollState()
    val previewScroll = SoulScreenBase.ScrollState()
    val railScroll = SoulScreenBase.ScrollState()
    val bodyScroll = SoulScreenBase.ScrollState()

    /** Category key of the rail's "Not sorted yet" entry. */
    const val UNSORTED = "\u0000unsorted"

    fun forProfile(id: String?) {
        if (id == profile) return
        profile = id
        picking = false
        cat = "all"
        query = ""
        preview = null
        sel = null
        tab = "now"
        open.clear()
        gatherOpen.clear()
        allOpen = false
        for (s in listOf(treeScroll, sideScroll, detailScroll, recipeScroll, previewScroll, railScroll, bodyScroll)) s.pos = 0f
    }

    fun select(id: String?) {
        sel = id
        detailScroll.pos = 0f
    }
}

internal object ForgeColors {
    val OK = 0xFF8FD19A.toInt()
    val MISS = 0xFFE58F84.toInt()
    val AMBER = 0xFFBC9A61.toInt()
    val DIM = 0xFF777777.toInt()
    val TEXT = 0xFFEEEEEE.toInt()
    val FAINT = 0xFF666666.toInt()
    val LINK = 0xFF8CB9FF.toInt()
    val SEL_BG = 0xFF202D40.toInt()
    val SEL_EDGE = 0xFF385A87.toInt()
    val EDGE = 0xFF2A2A2A.toInt()
    val ROW_HOVER = 0xFF232323.toInt()
    val ROW_HOVER2 = 0xFF252525.toInt()
    val BAR_TRACK = 0xFF2C2C2C.toInt()
    val PILL_BG = 0xFF1B2A1F.toInt()
    val PILL_EDGE = 0xFF2F5A3A.toInt()
    val PILL_HOVER = 0xFF24402B.toInt()
    val SUB = 0xFFAAAAAA.toInt()
    val ICON_BG = 0xFF222222.toInt()

    /** `.k` chips: (text, background). */
    fun chip(kind: String): Pair<Int, Int> = if (kind == "forge") 0xFFF0A77A.toInt() to 0xFF3A2A20.toInt()
        else if (kind == "craft") 0xFF9DB4D1.toInt() to 0xFF232F3D.toInt()
        else if (kind == "coins") 0xFFE0C25A.toInt() to 0xFF3A321C.toInt()
        else 0xFF999999.toInt() to 0xFF2A2A2A.toInt()

    fun withAlpha(color: Int, alpha: Int) = (color and 0xFFFFFF) or (alpha shl 24)
}

/** One piece of an inline line: text, a `.k` chip, or a lock glyph + text. */
internal class Run(val text: String, val color: Int, val chip: String? = null, val lock: Boolean = false)

/**
 * Drawing and text helpers shared by the Forge tab's parts: durations with the Quick Forge 20 best case, the
 * per-source bars and breakdown tooltips, fetch hints, kind chips and the tree's Missing cell.
 */
internal class ForgeUi(val c: ForgeCanvas) {
    lateinit var d: ForgeData
    val ready get() = ::d.isInitialized

    fun em(size: Number) = c.em(size)

    // ---------------------------------------------------------------- numbers and times

    fun dur(base: Long, max: Int = 4): String = ForgeFormat.duration(ForgeEngine.shownSeconds(base, d.qf), max)

    /** The best case ("5h 36m with Quick Forge 20") when the player is below it, else null. */
    fun bestDur(base: Long, max: Int): String? =
        if (d.qf >= ForgeEngine.QF_MAX) null else ForgeFormat.duration(ForgeEngine.shownSeconds(base, ForgeEngine.QF_MAX), max)

    /** Mock `durBest`: current time, then the dim `· 5h 36m with Quick Forge 20`. */
    fun durRuns(base: Long, max: Int, color: Int): List<Run> {
        val best = bestDur(base, max) ?: return listOf(Run(dur(base, max), color))
        return listOf(Run(dur(base, max), color), Run(" · $best with Quick Forge 20", ForgeColors.DIM))
    }

    fun name(id: String) = ForgeItems.name(id)
    fun color(id: String) = ForgeItems.color(id)
    fun amt(n: Long) = ForgeFormat.amount(n)
    fun num(n: Long) = ForgeFormat.number(n)

    // ---------------------------------------------------------------- items

    /** `.f-ico` box ([box] 20 or 42 for the big one): the item at the mock's 1.125 / 1.875 scale. */
    fun itemIcon(g: GuiGraphicsExtractor, id: String, x: Int, y: Int, box: Int) {
        if (box == 42) {
            c.rrect(g, x, y, x + 42, y + 42, ForgeColors.ICON_BG, SoulRenderPipelines.ROUND_SMALL)
            ForgeItems.icon(g, id, x + 6, y + 6, 30)
        } else ForgeItems.icon(g, id, x + (box - 18) / 2, y + (box - 18) / 2, 18)
    }

    // ---------------------------------------------------------------- inline runs

    /** Chip width at 8 px with 0.8 px letter spacing and 4 px padding. */
    private fun chipWidth(text: String): Float = text.sumOf { c.width(it.toString(), em(8)).toDouble() }.toFloat() + 0.8f * text.length + 8f

    /** Draws [runs] on one line between [top] and [bottom]; the last run is cut with an ellipsis at [right]. Returns the end x. */
    fun runs(g: GuiGraphicsExtractor, x0: Int, top: Int, bottom: Int, right: Int, runs: List<Run>, size: Float): Int {
        var x = x0
        for (run in runs) {
            if (x >= right) break
            val mid = (top + bottom) / 2
            if (run.chip != null) {
                val label = run.chip.uppercase()
                val w = chipWidth(label).toInt()
                val (fg, bg) = ForgeColors.chip(run.chip)
                if (x + w > right) break
                c.rrect(g, x, mid - 5, x + w, mid + 6, bg, SoulRenderPipelines.ROUND_SMALL)
                var cx = x + 4f
                for (ch in label) {
                    c.text(g, ch.toString(), cx.toInt(), mid - 5, mid + 6, fg, em(8), false)
                    cx += c.width(ch.toString(), em(8)) + 0.8f
                }
                x += w + 5
                continue
            }
            if (run.lock) {
                c.icon(g, "lock", x, mid - 5, 10, run.color)
                x += 14
            }
            val shown = c.fit(run.text, right - x, size)
            c.text(g, shown, x, top, bottom, run.color, size, false)
            x += c.width(shown, size)
            if (shown.length < run.text.length) break
        }
        return x
    }

    fun runsWidth(runs: List<Run>, size: Float): Int = runs.sumOf { r ->
        if (r.chip != null) chipWidth(r.chip.uppercase()).toInt() + 5 else (if (r.lock) 14 else 0) + c.width(r.text, size)
    }

    // ---------------------------------------------------------------- have: per-source bar and breakdown

    private fun sourceColor(source: String): Int = if (source == "purse" || source == "bank") ForgeOwned.COIN_COLOR else ForgeOwned.source(source)?.color ?: ForgeColors.DIM

    /** Mock `srcBar`: 3 px track, one segment per source sized by its share of what is used. */
    fun sourceBar(g: GuiGraphicsExtractor, x: Int, y: Int, w: Int, id: String, use: Long, need: Long) {
        c.rrect(g, x, y, x + w, y + 3, ForgeColors.BAR_TRACK, SoulRenderPipelines.ROUND_PILL)
        val parts = d.owned.bySource(id)
        val total = parts.sumOf { it.count }
        if (total <= 0 || need <= 0) return
        val share = minOf(1.0, use.toDouble() / need)
        var acc = 0.0
        var left = x
        for (p in parts) {
            acc += p.count.toDouble() / total * share
            val right = x + Math.round(acc * w).toInt()
            if (right > left) g.fill(left, y, right, y + 3, sourceColor(p.source))
            left = right
        }
    }

    /** Mock `tipFor`: breakdown lines (`§` codes) with places. */
    fun breakdown(id: String, use: Long, need: Long): List<String> {
        if (id == ForgeEngine.COINS) {
            val lines = ArrayList<String>()
            lines += "§fCoins"
            d.owned.purse?.let { lines += "§7Purse §f${num(it)}" }
            d.owned.bank?.let { lines += "§7Bank §f${num(it)}" }
            lines += "§8Need ${num(need)}"
            return lines
        }
        val lines = arrayListOf("§f${name(id)}")
        for (p in d.owned.bySource(id)) lines += "§7${ForgeOwned.source(p.source)?.label ?: p.source} §f${num(p.count)} §8· ${p.where}"
        if (lines.size == 1) lines += "§8None seen yet"
        lines += "§8Need ${num(need)}" + if (use < need) " · missing ${num(need - use)}" else ""
        if (!d.known("sacks")) lines += "§6Sacks unknown: may be more"
        return lines
    }

    // ---------------------------------------------------------------- fetch hints

    /**
     * Island chests (a count) and storage pages (by name) holding inputs of [count] processes of [step] that
     * inventory (and sacks, see [ForgeEngine.FORGE_TAKES_FROM_SACKS]) do not carry.
     */
    private fun fetchFrom(recipe: ForgeRecipe, count: Long): List<String> {
        val chests = LinkedHashSet<String>()
        val pages = LinkedHashSet<String>()
        for ((cid, per) in recipe.inputs) {
            if (cid == ForgeEngine.COINS) continue
            val parts = d.owned.bySource(cid)
            val carried = parts.filter { it.source == "inventory" || (ForgeEngine.FORGE_TAKES_FROM_SACKS && it.source == "sacks") }.sumOf { it.count }
            if (carried >= per * count) continue
            for (p in parts) {
                if (p.source == "island") for (h in p.hits) chests += h.place.key
                else if (p.source == "storage") for (h in p.hits) pages += (h.place as? StorageSource.Place)?.page?.label ?: h.place.key
            }
        }
        return (if (chests.isEmpty()) emptyList() else listOf(if (chests.size == 1) "1 island chest" else "${chests.size} island chests")) + pages
    }

    /** "1 island chest, Ender Chest 2 +1" or empty when everything is carried. */
    fun fetchText(recipe: ForgeRecipe, count: Long): String {
        val f = fetchFrom(recipe, maxOf(1, count))
        return if (f.isEmpty()) "" else f.take(2).joinToString(", ") + if (f.size > 2) " +${f.size - 2}" else ""
    }

    fun viewTip(id: String, recipe: ForgeRecipe, count: Long): List<String> {
        val fetch = fetchText(recipe, count)
        return listOf("§fOpen the recipe in game", "§7Sends /viewrecipe $id") + if (fetch.isNotEmpty()) listOf("§6Fetch first: $fetch") else emptyList()
    }

    // ---------------------------------------------------------------- kind line

    /** Mock `kindLine`. [step] is the aggregate of the node's id (for the "in goal" repeat). */
    fun kindRuns(n: ForgeNode, step: ForgeStep?): List<Run> {
        val r = n.recipe
        val rep = if (step != null && step.at > 1 && n.kind != StepKind.BASE && n.kind != StepKind.COINS) listOf(Run(" · ${amt(step.need)} in goal", ForgeColors.DIM)) else emptyList()
        val times = if (n.times > 1) listOf(Run(" · ×${num(n.times)}", ForgeColors.DIM)) else emptyList()
        if (n.kind == StepKind.FORGE && r != null) {
            if (r.lock != null) return listOf(Run(r.lock, ForgeColors.MISS, lock = true))
            return listOf(Run("", 0, chip = "forge")) + durRuns(r.baseSeconds, 2, ForgeColors.SUB) + times + rep
        }
        if (n.kind == StepKind.CRAFT && r != null) {
            val ins = if (r.inputs.size == 1) "${num(r.inputs[0].second)} ${name(r.inputs[0].first)}" else "${r.inputs.size} inputs"
            return listOf(Run("", 0, chip = "craft"), Run("$ins → 1", ForgeColors.SUB)) + times + rep
        }
        val coins = n.kind == StepKind.COINS
        return listOf(Run("", 0, chip = if (coins) "coins" else "base"), Run(if (coins) "purse + bank" else "base material", ForgeColors.DIM)) + rep
    }

    // ---------------------------------------------------------------- the Missing cell

    /**
     * Mock `missingCell` / `missCell`: Have it with a check, red amount, or the readiness forms (green Craft N /
     * Forge N, with a red "M short" under it when only part can be made). A ready craft's label is the green pill
     * that opens the recipe in game. Right-aligned at [right], centered in [top]..[bottom].
     */
    fun missingCell(g: GuiGraphicsExtractor, right: Int, top: Int, bottom: Int, n: ForgeNode, clip: IntArray) {
        val st = ForgeEngine.status(n)
        stateCell(g, right, top, bottom, st, n.missing, n.kind, n.id, n.recipe, clip)
    }

    fun stateCell(g: GuiGraphicsExtractor, right: Int, top: Int, bottom: Int, st: StepStatus, missing: Long, kind: StepKind, id: String, recipe: ForgeRecipe?, clip: IntArray) {
        val mid = (top + bottom) / 2
        if (st.state == StepState.COVERED) {
            val w = c.width("Have it", em(11))
            c.icon(g, "check", right - w - 13, mid - 5, 10, ForgeColors.OK)
            c.text(g, "Have it", right - w, top, bottom, ForgeColors.OK, em(11), false)
            return
        }
        if (st.state == StepState.MISSING) {
            val t = amt(missing)
            c.text(g, t, right - c.width(t, em(12)), top, bottom, ForgeColors.MISS, em(12), false)
            return
        }
        val label = (if (kind == StepKind.FORGE) "Forge " else "Craft ") + amt(st.make)
        val partial = st.state == StepState.PARTIAL
        val labelMid = if (partial) mid - 6 else mid
        val w = c.width(label, em(11))
        if (kind == StepKind.CRAFT && recipe != null) {
            val pill = Rect(right - w - 12, labelMid - 8, right, labelMid + 8)
            val hover = c.hovered(pill, clip)
            c.rrect(g, pill.x, pill.y, pill.right, pill.bottom, ForgeColors.PILL_EDGE, SoulRenderPipelines.ROUND_SMALL)
            c.rrect(g, pill.x + 1, pill.y + 1, pill.right - 1, pill.bottom - 1, if (hover) ForgeColors.PILL_HOVER else ForgeColors.PILL_BG, SoulRenderPipelines.ROUND_SMALL)
            c.text(g, label, pill.x + 6, pill.y, pill.bottom, ForgeColors.OK, em(11), false)
            c.region(pill, clip) { Forge.viewRecipe(id) }
            if (hover) c.tip(pill, viewTip(id, recipe, st.make))
        } else c.text(g, label, right - w, labelMid - 8, labelMid + 8, ForgeColors.OK, em(11), false)
        if (partial) {
            val t = "${amt(st.short)} short"
            c.text(g, t, right - c.width(t, em(9)), mid + 6, mid + 17, ForgeColors.MISS, em(9), false)
        }
    }
}
