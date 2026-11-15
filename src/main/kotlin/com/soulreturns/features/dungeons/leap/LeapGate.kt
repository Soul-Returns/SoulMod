package com.soulreturns.features.dungeons.leap

/**
 * What one open leap menu allows, pure (LeapMenuTest): fed every [LeapMenuReader.Result] as slots arrive, it knows
 * each teammate's head slot from the moment that head is there, and decides what one press on a teammate does.
 * The press itself is [LeapSession.leap]; this never sends anything.
 *
 * PENDING: some slots are still empty; the heads that arrived are clickable already. ACTIVE: every slot is there.
 * FAILED: something we do not know (or contents not complete after [LOAD_TIMEOUT_MS]): vanilla takes over. SENT:
 * one leap went out, every later press does nothing.
 */
class LeapGate(private val openAt: Long) {
    companion object {
        const val PENDING = 0
        const val ACTIVE = 1
        const val FAILED = 2
        const val SENT = 3

        /** Hypixel sends all 36 slots in the open's frame (research §1.4); anything this slow is not a menu we know. */
        const val LOAD_TIMEOUT_MS = 2000L
    }

    /** What one press on a teammate does. */
    sealed class Press {
        /** Send exactly one click on [slot], now. */
        data class Send(val slot: Int) : Press()

        /** That teammate's head has not arrived: nothing is sent (never queued). */
        object NotYet : Press()

        object Dead : Press()

        /** Every slot is there and none is theirs. */
        object NotInMenu : Press()

        /** Already sent one leap, or the menu is vanilla's now. */
        object Done : Press()
    }

    var state = PENDING
        private set

    var why: String? = null
        private set

    /** The heads that have arrived, in slot order. */
    var heads: List<LeapMenuReader.Head> = emptyList()
        private set

    private val readyAt = LinkedHashMap<String, Long>()

    /** Ms from the open until [name]'s head arrived, or null while it has not. */
    fun readyAfter(name: String): Long? = readyAt[name.lowercase()]?.let { it - openAt }

    /** All heads' arrival times, in arrival order: name to ms since the open. */
    fun arrivals(): List<Pair<String, Long>> = heads.sortedBy { readyAt[it.name.lowercase()] ?: Long.MAX_VALUE }
        .map { it.name to ((readyAt[it.name.lowercase()] ?: openAt) - openAt) }

    fun head(name: String): LeapMenuReader.Head? = heads.firstOrNull { it.name.equals(name, ignoreCase = true) }

    /** One read of the menu at [now]. After a sent leap the menu is no longer judged (vanilla's prediction empties the slot). */
    fun update(read: LeapMenuReader.Result, now: Long) {
        if (state == FAILED || state == SENT) return
        when (read) {
            is LeapMenuReader.Result.Fail -> fail(read.why)
            is LeapMenuReader.Result.Pending -> {
                take(read.heads, now)
                if (now - openAt > LOAD_TIMEOUT_MS) fail("contents did not load")
            }
            is LeapMenuReader.Result.Ok -> {
                take(read.heads, now)
                state = ACTIVE
            }
        }
    }

    private fun take(arrived: List<LeapMenuReader.Head>, now: Long) {
        heads = arrived
        for (h in arrived) readyAt.putIfAbsent(h.name.lowercase(), now)
    }

    private fun fail(reason: String) {
        state = FAILED
        why = reason
    }

    /** What a press on [name] does now; [Press.Send] must be followed by the click and [sent]. */
    fun press(name: String, blockDead: Boolean): Press {
        if (state == FAILED || state == SENT) return Press.Done
        val h = head(name) ?: return if (state == ACTIVE) Press.NotInMenu else Press.NotYet
        if (h.dead && blockDead) return Press.Dead
        return Press.Send(h.slot)
    }

    fun sent() {
        state = SENT
    }
}
