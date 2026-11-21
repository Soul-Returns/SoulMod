package com.soulreturns.features.mining.nucleus

import com.soulreturns.core.events.Event
import java.util.Locale

/** One bundle line of a [NucleusRunFinished]: the name as printed, the id when known, the count. */
data class NucleusRunItem(val name: String, val id: String?, val count: Long)

/**
 * A Crystal Nucleus run ended with its loot bundle. Published on the client thread for **every** bundle
 * parsed in the Crystal Hollows, whatever the Nucleus tracker's toggles say (contests depend on it, not
 * the tracker); nothing in the tracker reads it.
 *
 * Times are epoch ms (wall clock). [startedAt]/[durationMs] are null for an untimed run ([partial]: the
 * first run after entering the Hollows, a server change, a restart or a missed bundle).
 *
 * @property profileId the SkyBlock profile id at the bundle, dashed lowercase, or null when unknown.
 * @property items bundle lines except the three powders, which are in [powder] (`mithril|gemstone|glacite`).
 */
data class NucleusRunFinished(
    val finishedAt: Long,
    val startedAt: Long?,
    val durationMs: Long?,
    val partial: Boolean,
    val profileId: String?,
    val items: List<NucleusRunItem>,
    val powder: Map<String, Long>,
) : Event

/**
 * The run clock for [NucleusRunFinished], in memory and independent of the tracker's stored run, with
 * the tracker's rules (rule nucleus "Run tracker"): a run starts at the previous bundle; without one
 * (entering the Hollows, after a restart or a missed bundle) it starts at its first crystal and is
 * partial; a server change makes a run with crystals partial and forgets a start without any; leaving
 * the Hollows with no crystal found forgets the start. Pure: the caller passes the time.
 */
class NucleusRunClock {
    var startedAt: Long? = null
        private set
    var partial: Boolean = false
        private set
    private var foundCount = 0

    fun onFound(index: Int, now: Long) {
        if (index <= foundCount) {
            // The count did not grow: the last bundle was missed.
            startedAt = null
            foundCount = 0
        }
        if (startedAt == null) {
            startedAt = now
            partial = true
        }
        foundCount = index
    }

    /** Ends the run at [now]; returns (startedAt, durationMs, partial) of the run that ended. */
    fun onBundle(now: Long): Triple<Long?, Long?, Boolean> {
        val start = startedAt
        val wasPartial = partial || start == null
        val duration = start?.takeIf { !wasPartial }?.let { now - it }?.takeIf { it > 0 }
        startedAt = now
        partial = false
        foundCount = 0
        return Triple(if (wasPartial) null else start, duration, duration == null)
    }

    fun onServerChange() {
        if (foundCount == 0) startedAt = null else partial = true
    }

    fun onLeftHollows() {
        if (foundCount == 0) startedAt = null
    }
}

object NucleusRunCapture {
    /** Powder line name (lowercase) -> contract `powder` key. */
    private val POWDER_KEYS = mapOf(
        "mithril powder" to "mithril",
        "gemstone powder" to "gemstone",
        "glacite powder" to "glacite",
    )

    /** Pure: the event for one bundle. [idFor] resolves names (the catalog in game, a map in tests). */
    fun build(
        items: List<NucleusLoot>,
        finishedAt: Long,
        timing: Triple<Long?, Long?, Boolean>,
        profileId: String?,
        idFor: (String) -> String?,
    ): NucleusRunFinished {
        val lines = ArrayList<NucleusRunItem>()
        val powder = LinkedHashMap<String, Long>()
        for (item in items) {
            val key = POWDER_KEYS[item.name.lowercase(Locale.ROOT)]
            if (key != null) {
                powder[key] = (powder[key] ?: 0L) + item.amount
                continue
            }
            val id = if (NucleusItems.isPowder(item.name)) null else idFor(item.name)
            lines += NucleusRunItem(item.name, id, item.amount)
        }
        return NucleusRunFinished(
            finishedAt = finishedAt,
            startedAt = timing.first,
            durationMs = timing.second,
            partial = timing.third,
            profileId = profileId,
            items = lines,
            powder = powder,
        )
    }
}
