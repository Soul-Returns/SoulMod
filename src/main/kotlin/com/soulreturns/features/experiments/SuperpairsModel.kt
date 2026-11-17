package com.soulreturns.features.experiments

/**
 * Superpairs (research §1.3, §4.4). Hidden cards are `cyan_stained_glass` whose NAME is the game state
 * (the same on every hidden card); a revealed card is the reward item itself. Every face ever revealed
 * in this container is remembered by its full identity ([cardKey]), never by name alone (two different
 * `Enchanted Book`s exist).
 *
 * Blocking (the user's rule): only with a first card open (server-confirmed), a click on a hidden card
 * whose identity is known and differs from the open card's. Everything else goes through.
 *
 * [rare] tells a reward card worth a warning (RNG meter rare set or Hypixel's own ULTRA-RARE label).
 */
class SuperpairsModel private constructor(val grid: List<Int>, val tier: String, private val rare: (ExpItem) -> Boolean) : GameModel(54) {
    enum class State { IDLE, FIRST, MISMATCH, INSTANT }

    class Mem(val key: String, val item: ExpItem)

    /** Cards face-up right now. */
    val face = HashMap<Int, ExpItem>()

    /** Every card seen in this container (the end board excluded: it reveals everything). */
    val mem = HashMap<Int, Mem>()
    val matched = HashSet<Int>()
    val powers = HashSet<Int>()
    var open: Int? = null
        private set
    var mm: List<Int> = emptyList()
        private set
    var state = State.IDLE
        private set
    var mmAt = 0L
        private set
    var remaining = 0
        private set
    var timer = 0
        private set
    var final = false
        private set

    /** Rare cards: their slots, and how many distinct rare identities were revealed (each warns once). */
    val rareSlots = HashSet<Int>()
    private val rareKeys = HashSet<String>()
    var rareCount = 0
        private set
    var lastRare: ExpItem? = null
        private set
    var lastRareAt = 0L
        private set

    private var levelupAt = Long.MIN_VALUE / 2
    private val fresh = ArrayList<Int>()

    val ending: Boolean get() = remaining == 0 && state == State.MISMATCH && !final

    private var stateAt = 0L
    fun hidden(slot: Int): Boolean = slot !in face

    override fun onSlot(slot: Int, old: ExpItem?, item: ExpItem?, now: Long) {
        if (item == null) return
        if (slot == CLICKS_SLOT) {
            if (item.id != "bookshelf" && item.id != "cauldron") return breakLayout("slot 4 is ${item.id}")
            remaining = REMAINING.find(item.plain)?.groupValues?.get(1)?.toIntOrNull() ?: return breakLayout("slot 4 '${item.plain}'")
            return
        }
        if (slot == TIMER_SLOT) {
            timerOf(item)?.let { timer = it }
            return
        }
        if (slot !in grid) {
            if (!item.isBlackPane) breakLayout("slot $slot is ${item.id}")
            return
        }
        if (item.id == GLASS) {
            face.remove(slot)
            val st = GLASS_STATES[item.plain] ?: return breakLayout("glass '${item.plain}'")
            if (st != state) {
                if (st == State.MISMATCH) mmAt = now
                state = st
                stateAt = now
            }
            return
        }
        if (item.isPane) return breakLayout("grid slot $slot is ${item.id}")
        if (item.plainLore.any { it == PROCEED }) {
            final = true
            face[slot] = item
            return
        }
        val was = slot in face
        face[slot] = item
        mem[slot] = Mem(cardKey(item), item)
        if (!was) fresh += slot
    }

    private var levelupUsedAt: Long? = null

    /** The levelup comes a tick before the cards (8193 → 8194); the next revealed batch uses it. */
    override fun onSound(name: String, pitch: Float, now: Long) {
        if (name == LEVELUP) {
            levelupAt = now
            levelupUsedAt = null
        }
    }

    override fun settle(now: Long) {
        if (fresh.isNotEmpty()) {
            val used = levelupUsedAt
            val levelup = now - levelupAt <= LEVELUP_MS && (used == null || now - used <= SAME_TICK_MS)
            for (s in fresh) {
                val it = face[s] ?: continue
                checkRare(s, it, now)
                if (isPower(it)) {
                    powers += s
                    continue
                }
                // The partner of a card already matched alone completes the pair, whatever its timing: Instant Find's
                // pair can reach the client in a later batch than the clicked card, after the levelup was used up.
                val key = mem[s]?.key
                if (key != null && matched.count { it != s && mem[it]?.key == key } % 2 == 1) {
                    matched += s
                    if (open == s) open = null
                    continue
                }
                if (levelup) {
                    matched += s
                    val o = open
                    if (o != null && mem[o]?.key == mem[s]?.key) {
                        matched += o
                        open = null
                    }
                    continue
                }
                val o = open
                if (o == null) {
                    open = s
                } else {
                    mm = listOf(o, s)
                    open = null
                }
            }
            fresh.clear()
            if (levelup && used == null) levelupUsedAt = now
        }
        open?.let { if (it !in face) open = null }
        if (mm.isNotEmpty() && !mm.all { it in face }) mm = emptyList()
        // Still face-up well after the glass said "any": Hypixel matched them (their levelup came late).
        if (state == State.IDLE && mm.size == 2 && now - stateAt > SETTLED_MS) {
            matched += mm
            mm = emptyList()
        }
    }

    private fun checkRare(slot: Int, item: ExpItem, now: Long) {
        if (!rare(item)) return
        rareSlots += slot
        if (rareKeys.add(cardKey(item))) {
            rareCount++
            lastRare = item
            lastRareAt = now
        }
    }

    /** The hidden slot holding the open card's known partner. */
    fun partner(): Int? {
        if (state != State.FIRST) return null
        val o = open ?: return null
        val key = mem[o]?.key ?: return null
        return grid.firstOrNull { it != o && hidden(it) && mem[it]?.key == key }
    }

    /** Known, unmatched pairs among hidden cards, in grid order of their first card. */
    fun pairs(): List<List<Int>> {
        val by = LinkedHashMap<String, MutableList<Int>>()
        for (s in grid) {
            val m = mem[s] ?: continue
            if (!hidden(s) || isPower(m.item)) continue
            by.getOrPut(m.key) { ArrayList() } += s
        }
        return by.values.filter { it.size >= 2 }.map { it.take(2) }
    }

    fun press(slot: Int): Verdict {
        if (final || state != State.FIRST) return Verdict.Send
        val o = open ?: return Verdict.Send
        if (!hidden(slot)) return Verdict.Send
        val b = mem[slot] ?: return Verdict.Send
        val a = mem[o] ?: return Verdict.Send
        if (a.key == b.key) return Verdict.Send
        events++
        return Verdict.Block("mismatch", "Not a match: ${cardLabel(b.item)} is not the open ${cardLabel(a.item)}")
    }

    companion object {
        const val CLICKS_SLOT = 4
        const val TIMER_SLOT = 49
        const val GLASS = "cyan_stained_glass"
        const val LEVELUP = "entity.player.levelup"
        const val PROCEED = "Click any to proceed!"

        /** A mismatched pair hides on its own 30 ticks after the reveal. */
        const val HIDE_MS = 30 * 50L

        /** How long a levelup waits for its cards (it arrives a tick early; jitter up to ~300 ms was seen). */
        const val LEVELUP_MS = 600L

        /** A mismatched pair hides in the same burst as the glass turning "any"; later than this it was a match. */
        const val SETTLED_MS = 500L

        /** Cards of one server tick that a frame split still share its levelup. */
        const val SAME_TICK_MS = 40L
        private val REMAINING = Regex("^Remaining Clicks: (\\d+)$")

        val GLASS_STATES =
            mapOf(
                "Click any button!" to State.IDLE,
                "Click a second button!" to State.FIRST,
                "?" to State.MISMATCH,
                "Next button is instantly rewarded!" to State.INSTANT,
            )

        /** The captured grid (7x4): Transcendent. Other grids were never captured. */
        val GRID_7X4: List<Int> = listOf(1, 2, 3, 4).flatMap { r -> (1..7).map { c -> r * 9 + c } }

        fun isPower(item: ExpItem): Boolean = item.plainLore.any { it.contains("owerup") }

        /** Card identity (§4.4): item id + count + name + lore without the end board's line + head texture (+ dye). */
        fun cardKey(item: ExpItem): String =
            listOf(item.id, item.count.toString(), item.plain, item.plainLore.filter { it != PROCEED }.joinToString("|"), item.texture ?: "", item.dye?.toString() ?: "").joinToString("#")

        /** Short label a card is known by: a book by its enchant line. */
        fun cardLabel(item: ExpItem): String =
            if (item.plain == "Enchanted Book") item.plainLore.drop(1).firstOrNull { it.isNotBlank() } ?: item.plain else item.plain

        fun layout(items: List<ExpItem?>): List<Int>? {
            if (items.size < 54 || items.take(54).any { it == null }) return null
            val clicks = items[CLICKS_SLOT]!!
            if ((clicks.id != "bookshelf" && clicks.id != "cauldron") || !REMAINING.matches(clicks.plain)) return null
            val grid = ArrayList<Int>()
            for (s in 0 until 54) {
                if (s == CLICKS_SLOT || s == TIMER_SLOT) continue
                val it = items[s]!!
                if (it.isBlackPane) continue
                if (it.isPane) return null
                if (it.id == GLASS && it.plain !in GLASS_STATES) return null
                grid += s
            }
            if (grid != GRID_7X4) return null
            if (grid.none { items[it]!!.id == GLASS } && items.none { i -> i?.plainLore?.any { it == PROCEED } == true }) return null
            return grid
        }

        fun create(items: List<ExpItem?>, tier: String, now: Long, rare: (ExpItem) -> Boolean): SuperpairsModel? {
            val grid = layout(items) ?: return null
            return SuperpairsModel(grid, tier, rare).also { m ->
                m.replay(items, now)
                // Cards already face-up at the open (a reopen) are known but neither an open card nor a pair event.
                for (s in m.fresh) m.face[s]?.let { if (isPower(it)) m.powers += s }
                m.fresh.clear()
            }
        }
    }
}
