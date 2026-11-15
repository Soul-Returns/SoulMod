package com.soulreturns.features.fishing

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.model.DoubleHookDetected
import com.soulreturns.data.model.FishingFestivalEvent
import com.soulreturns.data.model.SeaCreatureCaught
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.stats.TrackerSessions
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * The mod's **only** reader of fishing chat: sea-creature catches, double hooks and cocoons, counted
 * across three scopes and published as events for everything else.
 *
 * Everything that used to have its own regex for the double-hook line now subscribes to
 * [DoubleHookDetected] — [DoubleHookResponse] included. Two regexes for one chat line is a
 * divergence waiting to happen the next time Hypixel reworks the wording.
 *
 * ### The double hook is a sticky flag, not an adjacency check
 * `It's a Double Hook!` sets a flag that the **next** catch line consumes. Several lines can
 * legitimately land in between — a thunder bottle charging, a Reindrake surfacing, an autopet swap,
 * a blank line — and none of them may clear it. This mirrors SkyHanni's `SeaCreatureManager`, and
 * dropping the interleaver list is the single most likely way to break attribution in a rewrite.
 * Anything *else* does clear it, so a catch minutes later is not credited to a stale hook.
 *
 * ### Chat compactors coalesce the line
 * A receive-side compactor rewrites repeats as `It's a Double Hook! (3)`. The `(N)` is a running
 * total within the compactor's own window, so the counter must add the **delta** against the last
 * one seen, not one and not N. Outside a 60 s window the same `(N)` is a fresh aggregate rather than
 * a continuation.
 *
 * ### The cocoon line is a third outcome
 * `CAUGHT! You cocooned a X!` fires when equipment cocoons a sea creature on kill. It is not a
 * catch: the catch line already counted, so it must not increment catches, and it must not consume
 * the double-hook flag either — the hook belongs to whatever gets caught next.
 *
 * ### Three scopes, one shape
 * All three live in the profile-keyed stats file. Session follows the shared tracker-session lifetime
 * ([TrackerSessions]): it survives a quick restart and resets on a launch more than 30 minutes after
 * the client last ran. The festival bucket is **additive**, incremented alongside the other two,
 * cleared on festival *start* and preserved through festival *end* — so the numbers stay readable
 * after a festival finishes, which is exactly when people want to look at them.
 */
object FishingTracker {
    private val logger = SoulLogger("Soul/Fishing")

    /** SkyHanni's line, colour codes already stripped. The `Woot woot!` suffix is a server variant. */
    private val DOUBLE_HOOK = Regex("""It's a Double Hook!(?: Woot woot!)?""")

    /** The `(N)` a chat compactor appends when it coalesces repeats. */
    private val COMPACTED_SUFFIX = Regex(""" \((\d+)\)$""")

    /** Past this, the same `(N)` is a new aggregate rather than a continuation of the last one. */
    private const val COMPACTED_WINDOW_MS = 60_000L

    private val COCOON = Regex("""^CAUGHT! You cocooned a (.+)!$""")

    // Lines that may sit between the hook line and the catch line without breaking attribution.
    private val THUNDER_CHARGED = Regex("""> Your bottle of thunder has fully charged!""")
    private val REINDRAKE = Regex("""A Reindrake forms from the depths\.""")
    private val AUTOPET = Regex("""^Autopet equipped your """)

    /** How many unrecognised candidate lines to keep for `/soul dev fishing unmatched`. */
    private const val UNMATCHED_HISTORY = 40

    @Volatile private var pendingDoubleHook = false

    /** Persisted with the totals; see [FishingStats]. */
    val session: FishingCounts get() = FishingStatsStore.read().session

    // Compactor decoding state.
    private var lastCompactedBase: String? = null
    private var lastCompactedCount = 0
    private var lastCompactedAt = 0L

    /**
     * Lines the catalog did not recognise that might have been a catch, newest last.
     *
     * A sea creature Hypixel adds tomorrow is invisible to the tracker until an admin adds it at
     * `/admin/sea-creatures`, and the only symptom is a counter that quietly does not move. This
     * turns that into a line the user can paste into the admin UI.
     */
    private val unmatched = ArrayDeque<String>()

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        // Wire the festival's persistence before it registers: it resumes an in-progress festival
        // from the stored timestamp at that moment.
        FishingFestivalState.loadPersistedStart = { FishingStatsStore.read().festivalStartAt }
        FishingFestivalState.savePersistedStart = { at -> FishingStatsStore.update { copy(festivalStartAt = at) } }
        TrackerSessions.resetOnFreshStart(FishingStatsStore.handle) { withoutSession() }
        Events.subscribe(this)
    }

    /** Zero the session counters and the fishing stopwatch. All-time and festival are untouched. */
    fun resetSession() {
        FishingTimer.resetSession()
        FishingStatsStore.update { withoutSession() }
        logger.info("Session counters reset")
    }

    /** The persisted all-time counters for the active profile. */
    fun allTime(): FishingCounts = FishingStatsStore.read().allTime

    /**
     * The current fishing festival's counters. Cleared when a festival **starts** and preserved
     * through its end, so the numbers stay readable until the next one begins.
     */
    fun festival(): FishingCounts = FishingStatsStore.read().festival

    /** Newest last. Copied, so a dev command never iterates the live deque. */
    fun unmatchedLines(): List<String> = synchronized(unmatched) { unmatched.toList() }

    /**
     * Record fishing XP the action bar reported. Called by [FishingXpTracker] and nowhere else — the
     * catalog's `fishingExperience` is a base value that every real account multiplies, so it is not
     * a number this tracker is allowed to add up.
     */
    fun recordXp(gained: Double) {
        if (gained <= 0.0) return
        val duringFestival = FishingFestivalState.active
        FishingStatsStore.update {
            copy(
                session = session.withXp(gained),
                allTime = allTime.withXp(gained),
                festival = if (duringFestival) festival.withXp(gained) else festival,
            )
        }
    }

    // ------------------------------------------------------------------ chat

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        // Sea creatures only exist inside SkyBlock, so on any other Hypixel game this whole feature
        // costs one boolean read per line.
        if (!SkyblockState.onSkyblock) return

        val line = MessageDetector.stripColorCodes(event.raw).trim()
        if (line.isEmpty()) return // blank line — an interleaver; leave the flag alone

        if (handleDoubleHook(line)) return
        // Cocoon before catch: a cocoon line is `CAUGHT!`-shaped, so checking it second would file
        // every one of them as an unrecognised catch.
        if (handleCocoon(line)) return
        if (handleCatch(line)) return
        if (isInterleaver(line)) return

        // Nothing recognised this line. If it looks like it should have been a catch, keep it for
        // `/soul dev fishing unmatched` — then end the window the hook line opened.
        recordUnmatched(line)
        pendingDoubleHook = false
    }

    /** Clear the festival bucket when a new festival starts — never when one ends. */
    @HandleEvent
    fun onFestivalStarted(event: FishingFestivalEvent.Started) {
        FishingStatsStore.update { copy(festival = FishingCounts(), festivalStartAt = event.firstSeenStartAt) }
        logger.info("Cleared festival counters for the festival starting at ${event.firstSeenStartAt}")
    }

    private fun handleDoubleHook(line: String): Boolean {
        val suffix = COMPACTED_SUFFIX.find(line)
        val base = if (suffix != null) line.substring(0, suffix.range.first) else line
        if (!DOUBLE_HOOK.matches(base)) return false

        val count = suffix?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val now = System.currentTimeMillis()
        val continuesAggregate = base == lastCompactedBase &&
            count > lastCompactedCount &&
            now - lastCompactedAt < COMPACTED_WINDOW_MS
        val delta = if (continuesAggregate) count - lastCompactedCount else 1

        lastCompactedBase = base
        lastCompactedCount = count
        lastCompactedAt = now

        // A repeat of the same `(N)` inside the window means the compactor redrew its line without a
        // new hook. Still a double-hook line, so the flag stands; just nothing to add.
        if (delta <= 0) return true

        pendingDoubleHook = true
        val duringFestival = FishingFestivalState.active
        FishingStatsStore.update {
            copy(
                session = session.withDoubleHooks(delta),
                allTime = allTime.withDoubleHooks(delta),
                festival = if (duringFestival) festival.withDoubleHooks(delta) else festival,
            )
        }
        Events.publish(DoubleHookDetected(delta, now))
        return true
    }

    private fun handleCatch(line: String): Boolean {
        val creature = SeaCreatureCatalog.match(line) ?: return false
        val wasDoubleHook = pendingDoubleHook
        pendingDoubleHook = false
        val duringFestival = FishingFestivalState.active

        FishingStatsStore.update {
            copy(
                session = session.withCatch(creature.name, wasDoubleHook),
                allTime = allTime.withCatch(creature.name, wasDoubleHook),
                festival = if (duringFestival) festival.withCatch(creature.name, wasDoubleHook) else festival,
            )
        }
        Events.publish(SeaCreatureCaught(creature, wasDoubleHook, duringFestival, System.currentTimeMillis()))
        return true
    }

    private fun handleCocoon(line: String): Boolean {
        val match = COCOON.matchEntire(line) ?: return false
        val raw = match.groupValues[1].trim()
        // Prefer the catalog's canonical name so cocoons and catches share a key. A creature the
        // catalog has not heard of is still counted under the chat line's own name — losing the data
        // point would be worse than a breakdown row that is one admin edit away from tidy.
        val name = SeaCreatureCatalog.byName(raw)?.name ?: raw
        val duringFestival = FishingFestivalState.active

        FishingStatsStore.update {
            copy(
                session = session.withCocoon(name),
                allTime = allTime.withCocoon(name),
                festival = if (duringFestival) festival.withCocoon(name) else festival,
            )
        }
        return true
    }

    private fun isInterleaver(line: String): Boolean =
        AUTOPET.containsMatchIn(line) ||
            THUNDER_CHARGED.containsMatchIn(line) ||
            REINDRAKE.containsMatchIn(line)

    /**
     * Keep a line that might have been a catch of a creature the catalog does not know yet.
     *
     * Three signals qualify, in descending order of confidence:
     *  1. the line contains `CAUGHT!` — Hypixel's own wording for a cocoon, and for whatever else
     *     it decides to announce that way;
     *  2. a double hook is pending — the only lines that legitimately sit between the hook and the
     *     catch are the interleavers, and those were already ruled out above;
     *  3. the fishing stopwatch is running — the player is demonstrably fishing right now.
     *
     * Signal 3 is the noisy one and it is here deliberately. **Catalog catch lines mostly do not say
     * `CAUGHT!`** — the seeded set reads "A Squid appeared.", "Look! A Wetwing emerges!" — so
     * capturing only that shape would miss exactly the case this exists for. Skill-XP and drop lines
     * come along for the ride; the buffer is a dev command's ring of forty strings, deduplicated, so
     * that costs nothing and losing a new creature's line costs a lot.
     */
    private fun recordUnmatched(line: String) {
        if (!line.contains("CAUGHT!") && !pendingDoubleHook && FishingTimer.isPaused) return
        synchronized(unmatched) {
            if (unmatched.contains(line)) return
            unmatched.addLast(line)
            while (unmatched.size > UNMATCHED_HISTORY) unmatched.removeFirst()
        }
        logger.debug("Unmatched CAUGHT! line (catalog may be behind): $line")
    }
}
