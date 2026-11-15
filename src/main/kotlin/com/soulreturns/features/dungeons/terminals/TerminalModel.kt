package com.soulreturns.features.dungeons.terminals

/**
 * One container slot of a terminal menu, reduced to what the solvers read. Pure data so the solvers
 * are unit-tested against the captured boards (`docs/research/f7-native-terminals-2026-11-12.md`).
 */
data class TermItem(
    /** Registry path without namespace, e.g. `lime_wool`. */
    val id: String,
    /** Display name with color codes stripped (Hypixel's legacy names: `Roast Chicken`, `Silver Wool`). */
    val name: String,
    val count: Int = 1,
    /** The stack's own component patch sets the glint override: Hypixel's "selected" mark. */
    val glintPatch: Boolean = false,
    /** The item type glows on its own (Enchanted Book), so the patch can never show its selection. */
    val intrinsicGlint: Boolean = false,
)

/** Index = container slot; null = empty (not loaded yet, or taken by a local click prediction). */
typealias Board = List<TermItem?>

const val FILLER = "black_stained_glass_pane"
const val PANE_RED = "red_stained_glass_pane"
const val PANE_LIME = "lime_stained_glass_pane"
const val PANE_WHITE = "white_stained_glass_pane"
const val PANE_MAGENTA = "magenta_stained_glass_pane"
const val LOCK_ON = "lime_terracotta"
const val LOCK_OFF = "red_terracotta"

/** Mouse buttons as the container click carries them. */
const val LEFT = 0
const val RIGHT = 1

/** Vanilla's middle click: always CLONE with this button, never another pairing. */
const val MIDDLE = 2

/**
 * The six F7/M7 terminal menus. Each knows its title, size and playfield; [slotOk] says whether an
 * item may sit in a slot of this menu at all, so anything unexpected falls back to the vanilla screen.
 */
sealed class TerminalType(val key: String, val rows: Int, val board: List<Int>, private val title: Regex) {
    val size: Int get() = rows * 9

    /** The title's parameter (letter, color word), "" for fixed titles; null when it does not match. */
    fun param(title: String): String? {
        val m = this.title.matchEntire(title) ?: return null
        return m.groupValues.getOrNull(1) ?: ""
    }

    open fun slotOk(spec: TerminalSpec, slot: Int, item: TermItem): Boolean = if (slot in board) boardOk(spec, item) else item.id == FILLER

    protected abstract fun boardOk(spec: TerminalSpec, item: TermItem): Boolean

    /** Whole-board checks for the first complete contents (counts, sets), beyond single slots. */
    open fun initialOk(spec: TerminalSpec, board: Board): Boolean = true

    object StartsWith : TerminalType("startswith", 5, grid(1, 1, 3, 7), Regex("What starts with: '(.+)'\\?")) {
        override fun boardOk(spec: TerminalSpec, item: TermItem) = item.id != FILLER && item.name.isNotBlank()
        override fun initialOk(spec: TerminalSpec, board: Board) = this.board.any { board[it]?.let { i -> Matchers.startsWith(spec.param, i) } == true }
    }

    object Select : TerminalType("select", 6, grid(1, 1, 4, 7), Regex("Select all the (.+) items!")) {
        override fun boardOk(spec: TerminalSpec, item: TermItem) = item.id != FILLER && item.name.isNotBlank()
        override fun initialOk(spec: TerminalSpec, board: Board) = this.board.any { board[it]?.let { i -> Matchers.color(spec.param, i) } == true }
    }

    object Panes : TerminalType("panes", 5, grid(1, 2, 3, 5), Regex("Correct all the panes!")) {
        override fun boardOk(spec: TerminalSpec, item: TermItem) = item.id == PANE_RED || item.id == PANE_LIME
    }

    object Order : TerminalType("order", 4, grid(1, 2, 2, 5), Regex("Click in order!")) {
        override fun boardOk(spec: TerminalSpec, item: TermItem) = (item.id == PANE_RED || item.id == PANE_LIME) && item.count in 1..board.size
        override fun initialOk(spec: TerminalSpec, board: Board) = this.board.map { board[it]!!.count }.toSet() == (1..this.board.size).toSet()
    }

    object Rubix : TerminalType("rubix", 5, grid(1, 3, 3, 3), Regex("Change all to same color!")) {
        override fun boardOk(spec: TerminalSpec, item: TermItem) = RubixColors.index(item.id) >= 0
    }

    object Melody : TerminalType("melody", 6, (1..3).flatMap { r -> (1..5).map { r * 9 + it } + (r * 9 + 7) }, Regex("Click the button on time!")) {
        override fun boardOk(spec: TerminalSpec, item: TermItem) = true

        override fun slotOk(spec: TerminalSpec, slot: Int, item: TermItem): Boolean {
            val row = slot / 9
            val col = slot % 9
            if (row == 0 || row == 4) return if (col in 1..5) item.id == FILLER || item.id == PANE_MAGENTA else item.id == FILLER
            if (row in 1..3) {
                if (col in 1..5) return item.id == PANE_WHITE || item.id == PANE_RED || item.id == PANE_LIME
                if (col == 7) return item.id == LOCK_ON || item.id == LOCK_OFF
            }
            return item.id == FILLER
        }

        override fun initialOk(spec: TerminalSpec, board: Board): Boolean {
            val m = MelodyState.read(board)
            return m.target != null && board.count { it?.id == LOCK_ON } == 1 && m.active != null && m.lanes[m.active].moving != null
        }
    }

    companion object {
        val all: List<TerminalType> by lazy { listOf(StartsWith, Select, Panes, Order, Rubix, Melody) }

        /** Container slots of a [rows]×[cols] block whose top-left cell is ([row0], [col0]). */
        fun grid(row0: Int, col0: Int, rows: Int, cols: Int): List<Int> = (row0 until row0 + rows).flatMap { r -> (col0 until col0 + cols).map { r * 9 + it } }

        /** The terminal a menu with this (color-stripped) title and [containerSlots] is, or null. */
        fun recognize(title: String, containerSlots: Int): TerminalSpec? {
            for (t in all) {
                val p = t.param(title.trim()) ?: continue
                if (t.size != containerSlots) return null
                return TerminalSpec(t, p.trim(), title.trim())
            }
            return null
        }
    }
}

/** A recognized menu: its type and the title's parameter ("L", "LIME"; "" for fixed titles). */
data class TerminalSpec(val type: TerminalType, val param: String, val title: String) {
    /** Every non-empty slot is allowed here. */
    fun slotsOk(board: Board): Boolean = board.indices.all { i -> board[i]?.let { type.slotOk(this, i, it) } ?: true }

    /** The first full contents: every slot filled, each allowed, and the whole-board checks pass. */
    fun initialOk(board: Board): Boolean = board.size == type.size && board.all { it != null } && slotsOk(board) && type.initialOk(this, board)
}

/** Which items count, for the two item terminals. Open sets: any letter, any color word. */
object Matchers {
    fun startsWith(letter: String, item: TermItem): Boolean = letter.isNotEmpty() && item.name.startsWith(letter, ignoreCase = true)

    // Items whose id does not carry their dye color. Hypixel names them the legacy way (Ink Sack).
    private val dyeItems = mapOf("ink_sac" to "black", "lapis_lazuli" to "blue", "cocoa_beans" to "brown", "bone_meal" to "white")

    /** The legacy name Hypixel uses for a color in item names ("Silver Wool" is light gray). */
    private val legacyNames = mapOf("light_gray" to "silver")

    /** `LIGHT BLUE` → `light_blue`; Hypixel's legacy `SILVER` is light gray. */
    fun colorKey(word: String): String {
        val k = word.trim().lowercase().replace(Regex("[\\s-]+"), "_")
        return if (k == "silver") "light_gray" else k
    }

    /** True when [item] is of the color [word] names: by its id's color, else by its display name. */
    fun color(word: String, item: TermItem): Boolean {
        val key = colorKey(word)
        if (key.isEmpty()) return false
        if (item.id.startsWith(key + "_") || dyeItems[item.id] == key) return true
        val name = item.name.lowercase()
        val spoken = key.replace('_', ' ')
        return name.startsWith("$spoken ") || legacyNames[key]?.let { name.startsWith("$it ") } == true
    }
}

/** Red → Orange → Yellow → Green → Blue → Red: a left click moves one step forward, a right click back. */
object RubixColors {
    val ids = listOf("red", "orange", "yellow", "green", "blue").map { it + "_stained_glass_pane" }
    val names = listOf("Red", "Orange", "Yellow", "Green", "Blue")

    fun index(id: String): Int = ids.indexOf(id)

    /** Signed cheapest move from [from] to [to]: positive = left clicks, negative = right clicks. */
    fun move(from: Int, to: Int): Int {
        val f = Math.floorMod(to - from, 5)
        return if (f <= 2) f else f - 5
    }

    /** The target color with the fewest total clicks for [colors]; ties go to the earlier color. */
    fun cheapest(colors: List<Int>): Int = (0 until 5).minBy { t -> cost(colors, t) }

    fun cost(colors: List<Int>, target: Int): Int = colors.sumOf { Math.abs(move(it, target)) }
}

/** The melody board: target column, the three lanes, which one is active and where its green pane is. */
data class MelodyState(val target: Int?, val lanes: List<Lane>, val active: Int?) {
    data class Lane(val row: Int, val moving: Int?, val active: Boolean) {
        val button: Int get() = row * 9 + 7
    }

    val aligned: Boolean get() = active != null && target != null && lanes[active].moving == target

    companion object {
        fun read(board: Board): MelodyState {
            val tops = (1..5).filter { board.getOrNull(it)?.id == PANE_MAGENTA }
            val lanes = (1..3).map { r ->
                val limes = (1..5).filter { board.getOrNull(r * 9 + it)?.id == PANE_LIME }
                Lane(r, limes.singleOrNull(), board.getOrNull(r * 9 + 7)?.id == LOCK_ON)
            }
            // Lanes only move down; after a skip Hypixel can leave the lane before last drawn as active (2026-11-12 22:52).
            val active = lanes.indices.lastOrNull { lanes[it].active }
            return MelodyState(tops.singleOrNull(), lanes, active)
        }
    }
}
