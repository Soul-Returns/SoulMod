package com.soulreturns.features.experiments

import com.soulreturns.features.experiments.ExpMenus.Kind

/**
 * The Experimentation Table as the client sees it, fed packet by packet (pure, so recordings replay in
 * tests). One [Menu] per open container: PENDING until every slot has arrived (Hypixel sends them all
 * in the open's tick), then ACTIVE when the layout is the captured one, else FAILED (the vanilla menu).
 * Any later unexpected item fails it too: a surprise drops to vanilla rather than show a wrong state.
 *
 * A game reopened under the same title (a duplicate `open_screen`) continues its model: the new
 * container's burst is applied as transitions, so nothing already known is lost or counted twice.
 * Superpairs never carries memory over (Hypixel may build a new board).
 */
class ExpFlow(
    private val rare: (ExpItem) -> Boolean = { false },
    private val onRngPage: (page: Int, pages: Int, entries: List<RareRewards.Entry>) -> Unit = { _, _, _ -> },
) {
    enum class State { PENDING, ACTIVE, FAILED }

    data class Blocked(val slot: Int, val at: Long, val text: String, val kind: String)

    class Menu(val cid: Int, val title: String, val rows: Int, val rec: ExpMenus.Recognized, val openedAt: Long) {
        val size = rows * 9
        val items = arrayOfNulls<ExpItem>(size)

        /** Slots the server has sent so far (air included): the menu is judged once all arrived. */
        internal val arrived = BooleanArray(size)
        var state = State.PENDING
            internal set
        var why: String? = null
            internal set
        var model: GameModel? = null
            internal set
        var blocked: Blocked? = null
            internal set
        var sent = 0
            internal set
        var blocks = 0
            internal set
        val kind: Kind get() = rec.kind
        val list: List<ExpItem?> get() = items.asList()

        fun table() = ExpMenus.readTable(list)

        fun stakes() = ExpMenus.readStakes(rec.game ?: "", list)

        fun over() = ExpMenus.readOver(list)

        fun rewards() = ExpMenus.readRewards(list)
    }

    var menu: Menu? = null
        private set
    private var carry: GameModel? = null
    private var carryTitle: String? = null
    private var lastTick = -1L

    fun open(cid: Int, title: String, rows: Int, now: Long) {
        val rec = ExpMenus.recognize(title, rows)
        if (rec == null) {
            menu = null
            carry = null
            return
        }
        if (rec.kind == Kind.OVER || rec.kind == Kind.REWARDS) carry = null
        menu = Menu(cid, title, rows, rec, now)
    }

    fun slot(cid: Int, slot: Int, item: ExpItem?, now: Long) {
        val m = menu?.takeIf { it.cid == cid } ?: return
        if (slot !in 0 until m.size) return
        m.items[slot] = item
        m.arrived[slot] = true
        if (m.state == State.PENDING) return activate(m, now)
        if (m.state == State.FAILED) return
        val model = m.model
        if (model != null) {
            model.apply(slot, item, now)
            model.broken?.let { fail(m, it) }
        } else {
            revalidate(m)
        }
    }
    fun contents(cid: Int, items: List<ExpItem?>, now: Long) {
        val m = menu?.takeIf { it.cid == cid } ?: return
        for (i in 0 until m.size) m.items[i] = items.getOrNull(i)
        m.arrived.fill(true)
        if (m.state == State.PENDING) return activate(m, now)
        if (m.state == State.FAILED) return
        val model = m.model
        if (model != null) {
            for (i in 0 until m.size) model.apply(i, m.items[i], now)
            model.broken?.let { fail(m, it) }
        } else {
            revalidate(m)
        }
    }

    fun sound(name: String, pitch: Float, now: Long) {
        val m = menu ?: return
        if (m.state == State.ACTIVE) m.model?.onSound(name, pitch, now)
    }

    /** The container closed (server close, or Esc: Hypixel then opens the Over/Rewards page). */
    fun close() {
        menu = null
        carry = null
    }

    /** Each client tick and frame: age pending clicks by server traffic, settle batches, time out loading. */
    fun tick(now: Long, lastInbound: Long) {
        val dt = if (lastTick < 0) 0 else now - lastTick
        lastTick = now
        val m = menu ?: return
        if (m.state == State.PENDING && now - m.openedAt > LOAD_TIMEOUT_MS) return fail(m, "contents did not load")
        val model = m.model ?: return
        model.age(dt, now - lastInbound < Pending.SILENT_MS)
        model.settle(now)
    }

    /** One press on [slot] of a game: judge it ([Verdict.Send] = the caller sends now, exactly once). */
    fun press(slot: Int, now: Long): Verdict {
        val m = menu ?: return Verdict.Block("closed", "Menu closed")
        if (m.state != State.ACTIVE) return Verdict.Block("loading", "Not loaded yet")
        val v =
            when (val model = m.model) {
                is ChronoModel -> model.colorOfSlot(slot)?.let { model.press(it) } ?: Verdict.Block("wrong", "Not a color")
                is UltraModel -> if (slot in model.board) model.press(slot, now) else Verdict.Block("wrong", "Not on the board")
                is SuperpairsModel -> {
                    model.settle(now)
                    if (slot in model.grid) model.press(slot) else Verdict.Block("wrong", "Not a card")
                }
                else -> Verdict.Send
            }
        if (v is Verdict.Block) {
            m.blocked = Blocked(slot, now, v.text, v.kind)
            m.blocks++
        } else {
            m.sent++
        }
        return v
    }

    private fun activate(m: Menu, now: Long) {
        if (!m.arrived.all { it }) return
        val list = m.list
        when (m.kind) {
            Kind.CHRONO, Kind.ULTRA, Kind.SUPERPAIRS -> {
                val tier = m.rec.tier ?: ""
                val reuse = carry?.takeIf { carryTitle == m.title && m.kind != Kind.SUPERPAIRS }
                val model: GameModel? =
                    when {
                        reuse is ChronoModel && ChronoModel.layout(list) != null -> reuse.also { it.resync(list, now) }
                        reuse is UltraModel && UltraModel.layout(list) != null -> reuse.also { it.resync(list, now) }
                        m.kind == Kind.CHRONO -> ChronoModel.create(list, tier, now)
                        m.kind == Kind.ULTRA -> UltraModel.create(list, tier, now)
                        else -> SuperpairsModel.create(list, tier, now, rare)
                    }
                if (model == null) return fail(m, "layout not captured")
                model.broken?.let { return fail(m, it) }
                m.model = model
                carry = model
                carryTitle = m.title
            }
            Kind.RNG -> {
                val entries = RareRewards.readPage(list)
                if (entries.isEmpty()) return fail(m, "no entries")
                onRngPage(m.rec.page, m.rec.pages, entries)
            }
            else -> if (!valid(m)) return fail(m, "unexpected contents")
        }
        m.state = State.ACTIVE
    }

    private fun valid(m: Menu): Boolean =
        when (m.kind) {
            Kind.TABLE -> m.table() != null
            Kind.STAKES -> m.stakes() != null
            Kind.OVER -> m.over() != null
            Kind.REWARDS -> m.rewards() != null
            else -> true
        }

    private fun revalidate(m: Menu) {
        if (m.kind == Kind.RNG) {
            val entries = RareRewards.readPage(m.list)
            if (entries.isNotEmpty()) onRngPage(m.rec.page, m.rec.pages, entries)
            return
        }
        if (!valid(m)) fail(m, "unexpected contents")
    }

    private fun fail(m: Menu, why: String) {
        m.state = State.FAILED
        m.why = why
        if (m.model === carry) carry = null
    }

    companion object {
        /** Hypixel's full contents arrive in the open's tick; anything slower is not a menu we know. */
        const val LOAD_TIMEOUT_MS = 2000L
    }
}
