package com.soulreturns.features.experiments

/**
 * A game screen's model, fed one packet at a time (research §4.1): every `container_set_slot`, each
 * slot of a `container_set_content`, and every sound, in arrival order. [view] is the server's
 * contents; an identical resend is no transition, so duplicate resyncs and reopen bursts change nothing.
 *
 * [broken] = the layout stopped matching (an unexpected item): the screen hands the menu back to
 * vanilla. [off] = the solver lost track of the sequence: the Soul screen stays, judging stops.
 */
abstract class GameModel(val size: Int) {
    val view = arrayOfNulls<ExpItem>(size)
    var off: String? = null
        protected set
    var broken: String? = null
        protected set

    /** Times the solver went off or was blocked, for the debug line. */
    var events = 0
        protected set

    fun apply(slot: Int, item: ExpItem?, now: Long) {
        if (slot !in 0 until size) return
        val old = view[slot]
        if (old == item) return
        view[slot] = item
        onSlot(slot, old, item, now)
    }

    protected abstract fun onSlot(slot: Int, old: ExpItem?, item: ExpItem?, now: Long)

    open fun onSound(name: String, pitch: Float, now: Long) {}

    /** After the packets of one batch (each frame, each tick and before judging a press). */
    open fun settle(now: Long) {}

    /** [dt] ms of client time passed; [talking] = the server sent something within [Pending.SILENT_MS]. */
    open fun age(dt: Long, talking: Boolean) {}

    protected fun turnOff(why: String) {
        if (off == null) off = why
    }

    protected fun breakLayout(why: String) {
        if (broken == null) broken = why
    }

    protected fun replay(items: List<ExpItem?>, now: Long) {
        for (i in items.indices) if (i < size) apply(i, items[i], now)
    }

    companion object {
        /** `Timer: 12s` → 12 (the name, never the stack count, which is capped at 64). */
        fun timerOf(item: ExpItem?): Int? = item?.plain?.let { Regex("(\\d+)s$").find(it)?.groupValues?.get(1)?.toIntOrNull() }
    }
}
