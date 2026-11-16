package com.soulreturns.features.search.forge

import java.text.NumberFormat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/** Forge step kinds; compared with `==`, never `when` (rule://mc-api). */
enum class StepKind { FORGE, CRAFT, BASE, COINS }

/** One recipe as the engine uses it: inputs in catalog order, coins appended as [ForgeEngine.COINS]. */
class ForgeRecipe(
    val kind: StepKind,
    val inputs: List<Pair<String, Long>>,
    val count: Long,
    val baseSeconds: Long = 0,
    /** The forge's `Requires …` text for this profile, null when forgeable (or never seen locked). */
    val lock: String? = null,
)

/**
 * The recipes the engine expands over (mock `recipeOf`): forge first, then a single-output crafting
 * recipe. Multi-output crafts (DIAMOND from DIAMOND_BLOCK) are not recipes here, so those items stay base
 * materials. Built by [com.soulreturns.features.search.forge.ForgeRecipes] from the backend catalog.
 */
fun interface ForgeRecipeBook {
    fun recipeOf(id: String): ForgeRecipe?
}

/** Mock `expand` node. [make] = processes / crafts of the missing part that can be done now. */
class ForgeNode(
    val id: String,
    val need: Long,
    val use: Long,
    val missing: Long,
    val per: Long,
    val depth: Int,
    val key: String,
    val recipe: ForgeRecipe?,
    val kind: StepKind,
) {
    var times: Long = 0; internal set
    var make: Long = 0; internal set
    var children: List<ForgeNode> = emptyList(); internal set
}

/** Mock `aggregate` row: one step per id over every tree place. */
class ForgeStep(val id: String, val kind: StepKind, val recipe: ForgeRecipe?) {
    var need = 0L; internal set
    var use = 0L; internal set
    var missing = 0L; internal set
    var times = 0L; internal set
    var ready = 0L; internal set
    var make = 0L; internal set
    var depth = 0; internal set
    var at = 0; internal set
}

class TimeLeft(val serial: Long, val chain: Long, val processes: Long, val bound: Long)

/** A forge step in Right now: [now] processes placed into free slots, [sec] / [chain] of its first place. */
class PlannedForge(val step: ForgeStep, val sec: Long, val chain: Long, val now: Long)

class Share(val id: String, val need: Long, val use: Long)

class Waiting(val step: ForgeStep, val block: Share?, val locked: Boolean)

class ForgePlan(
    val forge: List<PlannedForge>,
    /** Output id per planned slot, in fill order. */
    val slots: List<String>,
    val freeAfter: Int,
    val craft: List<ForgeStep>,
    val waiting: List<Waiting>,
)

/** Tree state of a step (mock `status`). */
enum class StepState { COVERED, READY, PARTIAL, MISSING }

class StepStatus(val state: StepState, val make: Long, val short: Long)

/**
 * `mocks/search-everywhere/forge-engine.js`, ported 1:1: tree expansion with owned counts subtracted at
 * every level, aggregation, Right now plan, time totals. Pure; times follow the CURRENT Quick Forge
 * fraction [qf] (0.30 at perk level 20), never a per-process multiplier.
 */
object ForgeEngine {
    /** Pseudo item id for coin ingredients (`SKYBLOCK_COIN:n` in NEU, `§6300,000 Coins` in the menu). */
    const val COINS = "SKYBLOCK_COIN"

    /** Quick Forge at its max (perk level 20) = 30 %. */
    const val QF_MAX = 0.30

    /** HotM 2 opens the Forge, tiers 3–7 add one slot each (research §0): every captured profile has 7. */
    const val SLOTS = 7

    /**
     * UNVERIFIED (research): whether the Forge takes ingredients straight from the sacks. The mock assumes it
     * does, so sack counts count as carried like the inventory (no "Fetch first" hint for them). Flip this one
     * flag if a capture shows otherwise; the fetch hints then name the sacks too.
     */
    const val FORGE_TAKES_FROM_SACKS = true

    /** The player's menu duration: base × (1 − Quick Forge), whole seconds (`Math.round`). */
    fun shownSeconds(base: Long, qf: Double): Long = Math.round(base * (1 - qf))

    fun expand(goal: String, book: ForgeRecipeBook, have: (String) -> Long): ForgeNode {
        val pool = HashMap<String, Long>()
        fun left(id: String) = pool[id] ?: have(id)
        fun walk(id: String, need: Long, per: Long, depth: Int, path: List<String>, key: String): ForgeNode {
            val use = min(left(id), need)
            pool[id] = left(id) - use
            val missing = need - use
            val recipe = if (id in path) null else book.recipeOf(id)
            val kind = if (id == COINS) StepKind.COINS else recipe?.kind ?: StepKind.BASE
            val node = ForgeNode(id, need, use, missing, per, depth, key, recipe, kind)
            if (recipe != null && missing > 0) {
                node.times = ceilDiv(missing, recipe.count)
                val next = path + id
                node.children = recipe.inputs.mapIndexed { i, (cid, n) -> walk(cid, n * node.times, n, depth + 1, next, "$key/$i") }
                if (recipe.lock == null) {
                    var make = node.times
                    for (c in node.children) {
                        val crafted = if (c.kind == StepKind.CRAFT) c.make * c.recipe!!.count else 0L
                        make = min(make, (c.use + crafted) / c.per)
                    }
                    node.make = make
                }
            }
            return node
        }
        return walk(goal, 1, 1, 0, emptyList(), goal)
    }

    fun nodes(root: ForgeNode): Sequence<ForgeNode> = sequence {
        yield(root)
        for (c in root.children) yieldAll(nodes(c))
    }

    /** Processes or crafts that can start now from the allocated owned inputs alone. */
    fun readyTimes(n: ForgeNode): Long {
        if (n.times == 0L || n.recipe?.lock != null) return 0
        var ready = n.times
        for (c in n.children) ready = min(ready, c.use / c.per)
        return ready
    }

    fun status(missing: Long, make: Long, recipe: ForgeRecipe?): StepStatus {
        if (missing == 0L) return StepStatus(StepState.COVERED, 0, 0)
        if (make == 0L) return StepStatus(StepState.MISSING, 0, missing)
        val short = max(0L, missing - make * recipe!!.count)
        return StepStatus(if (short > 0) StepState.PARTIAL else StepState.READY, make, short)
    }

    fun status(n: ForgeNode) = status(n.missing, n.make, n.recipe)
    fun status(a: ForgeStep) = status(a.missing, a.make, a.recipe)

    fun chainBelow(n: ForgeNode, qf: Double): Long {
        if (n.times == 0L) return 0
        val own = if (n.kind == StepKind.FORGE) shownSeconds(n.recipe!!.baseSeconds, qf) else 0L
        return own + max(0L, n.children.maxOfOrNull { chainBelow(it, qf) } ?: 0L)
    }

    fun aggregate(root: ForgeNode): List<ForgeStep> {
        val out = LinkedHashMap<String, ForgeStep>()
        for (n in nodes(root)) {
            val a = out.getOrPut(n.id) { ForgeStep(n.id, n.kind, n.recipe) }
            a.need += n.need; a.use += n.use; a.missing += n.missing; a.times += n.times; a.ready += readyTimes(n); a.make += n.make
            a.depth = max(a.depth, n.depth); a.at += 1
        }
        return out.values.sortedWith { a, b -> if (a.depth != b.depth) b.depth - a.depth else IdOrder.compare(a.id, b.id) }
    }

    fun timeLeft(root: ForgeNode, agg: List<ForgeStep>, slots: Int, qf: Double): TimeLeft {
        val forge = agg.filter { it.kind == StepKind.FORGE }
        val serial = forge.sumOf { it.times * shownSeconds(it.recipe!!.baseSeconds, qf) }
        val processes = forge.sumOf { it.times }
        val chain = chainBelow(root, qf)
        return TimeLeft(serial, chain, processes, max(chain, ceil(serial.toDouble() / max(1, slots)).toLong()))
    }

    fun plan(root: ForgeNode, agg: List<ForgeStep>, freeSlots: Int, qf: Double): ForgePlan {
        class Place(val id: String, val ready: Long, val chain: Long, val sec: Long)
        val places = ArrayList<Place>()
        fun walk(n: ForgeNode, above: Long) {
            val own = if (n.kind == StepKind.FORGE && n.times > 0) shownSeconds(n.recipe!!.baseSeconds, qf) else 0L
            val ready = if (n.kind == StepKind.FORGE) readyTimes(n) else 0L
            if (ready > 0) places += Place(n.id, ready, above + own, own)
            for (c in n.children) walk(c, above + own)
        }
        walk(root, 0)
        places.sortWith { a, b ->
            if (a.chain != b.chain) b.chain.compareTo(a.chain) else if (a.sec != b.sec) b.sec.compareTo(a.sec) else IdOrder.compare(a.id, b.id)
        }
        var free = freeSlots.toLong()
        val slots = ArrayList<String>()
        val merged = LinkedHashMap<String, Triple<Long, Long, Long>>() // sec, chain, now
        for (p in places) {
            val now = min(p.ready, free)
            free -= now
            repeat(now.toInt()) { slots += p.id }
            val m = merged[p.id]
            merged[p.id] = if (m == null) Triple(p.sec, p.chain, now) else Triple(m.first, m.second, m.third + now)
        }
        val byStep = agg.associateBy { it.id }
        val forge = merged.map { (id, m) -> PlannedForge(byStep.getValue(id), m.first, m.second, m.third) }
        val craft = agg.filter { it.kind == StepKind.CRAFT && it.ready > 0 }
            .sortedWith { a, b -> if (a.depth != b.depth) b.depth - a.depth else IdOrder.compare(a.id, b.id) }
        val byId = LinkedHashMap<String, MutableList<ForgeNode>>()
        for (n in nodes(root)) if (n.times > 0) byId.getOrPut(n.id) { ArrayList() } += n
        val waiting = agg.filter { (it.kind == StepKind.FORGE || it.kind == StepKind.CRAFT) && it.times > it.ready }.map { a ->
            val share = LinkedHashMap<String, LongArray>()
            for (n in byId[a.id].orEmpty()) for (c in n.children) {
                val s = share.getOrPut(c.id) { LongArray(2) }
                s[0] += c.need; s[1] += c.use
            }
            val block = share.entries.filter { it.value[1] < it.value[0] }
                .sortedBy { it.value[1].toDouble() / it.value[0] }.firstOrNull()?.let { Share(it.key, it.value[0], it.value[1]) }
            Waiting(a, block, a.recipe?.lock != null)
        }.sortedWith { a, b -> if (a.step.depth != b.step.depth) b.step.depth - a.step.depth else IdOrder.compare(a.step.id, b.step.id) }
        return ForgePlan(forge, slots, free.toInt(), craft, waiting)
    }

    /** Base materials (and coins): missing first (largest missing first), then the covered ones. */
    fun gather(agg: List<ForgeStep>): List<ForgeStep> =
        agg.filter { it.kind == StepKind.BASE || it.kind == StepKind.COINS }.sortedWith { a, b ->
            val am = if (a.missing > 0) 1 else 0
            val bm = if (b.missing > 0) 1 else 0
            if (am != bm) bm - am
            else if (a.missing != b.missing) b.missing.compareTo(a.missing)
            else if (a.need != b.need) b.need.compareTo(a.need)
            else IdOrder.compare(a.id, b.id)
        }

    private fun ceilDiv(a: Long, b: Long) = (a + b - 1) / b
}

/** Text formats of the forge tab (mock `fmtDuration`, `amt`). */
object ForgeFormat {
    private val numbers = NumberFormat.getIntegerInstance(Locale.US)
    private val UNITS = listOf("d" to 86400L, "h" to 3600L, "m" to 60L, "s" to 1L)

    /** `4d 21h 36m`, `5h 36m`, `21s`, as the forge lore prints it; [max] keeps the most significant units. */
    fun duration(seconds: Long, max: Int = 4): String {
        if (seconds <= 0) return "0s"
        var sec = seconds
        val parts = ArrayList<Pair<Long, String>>()
        for ((u, s) in UNITS) {
            val v = sec / s
            sec -= v * s
            if (v != 0L || parts.isNotEmpty()) parts += v to u
        }
        return parts.take(max).filter { it.first != 0L }.joinToString(" ") { "${it.first}${it.second}" }
    }

    /** Exact below 10,000 ("5,120"), then three significant digits with k / M / B, floored ("3.43M"). */
    fun amount(n: Long): String {
        val a = abs(n)
        if (a < 10000) return numbers.format(n)
        for ((d, s) in listOf(1e9 to "B", 1e6 to "M", 1e3 to "k")) {
            if (a >= d) {
                val v = n / d
                val digits = if (abs(v) >= 100) 0 else if (abs(v) >= 10) 1 else 2
                val p = Math.pow(10.0, digits.toDouble())
                val text = (floor(v * p) / p).toString()
                return (if (text.endsWith(".0")) text.dropLast(2) else text) + s
            }
        }
        return n.toString()
    }

    fun number(n: Long): String = numbers.format(n)
}

/**
 * The mock's `a.id.localeCompare(b.id)` (ICU root collation) for SkyBlock ids: punctuation before digits
 * before letters, letters case-insensitive at first. Byte order would put `GOLDEN_PLATE` before
 * `GOLD_INGOT`; the mock does the opposite.
 */
object IdOrder : Comparator<String> {
    private const val PUNCT = "_-,;:!?.'\"()[]{}@*/\\&#%`^+<=>|~$"

    private fun rank(c: Char): Int {
        if (c.isLetter()) return 2000 + c.lowercaseChar().code
        if (c.isDigit()) return 1000 + (c - '0')
        val p = PUNCT.indexOf(c)
        return if (p >= 0) 100 + p else 500 + c.code.coerceAtMost(400)
    }

    override fun compare(a: String, b: String): Int {
        val n = min(a.length, b.length)
        for (i in 0 until n) {
            val d = rank(a[i]) - rank(b[i])
            if (d != 0) return d
        }
        if (a.length != b.length) return a.length - b.length
        // Tertiary: lowercase before uppercase.
        for (i in 0 until n) if (a[i] != b[i]) return if (a[i].isLowerCase()) -1 else 1
        return 0
    }
}
