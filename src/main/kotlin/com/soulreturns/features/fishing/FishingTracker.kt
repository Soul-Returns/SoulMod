package com.soulreturns.features.fishing

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.fishing.SeaCreatureCatalog
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.model.FishingFestivalEvent
import com.soulreturns.data.model.SeaCreatureCaught
import com.soulreturns.data.skyblock.FishingFestivalState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * Always-on tracker for sea-creature catches and double-hooks.
 *
 * Mechanism mirrors SkyHanni's `SeaCreatureManager` (see `docs/fishing-research.md`):
 *  - "It's a Double Hook!" / "It's a Double Hook! Woot woot!" lines set a sticky [pendingDoubleHook] flag.
 *  - The next sea-creature catch line consumes the flag and emits a [SeaCreatureCaught] event,
 *    recording the catch (+ optional double-hook attribution) into [PersistentStats] across
 *    three scopes: all-time, current session, and current fishing festival.
 *  - Interleaver lines (autopet, thunder-bottle, reindrake, blank) do not reset the flag —
 *    they can legitimately land between the double-hook line and the catch line.
 *
 * The double-hook chat line is subject to chat-compactor coalescing (`It's a Double Hook! (N)`).
 * We decode `(N)` deltas the same way [com.soulreturns.features.farming.seasoning.SeasoningTracker]
 * does, so receive-level compactors don't make the counter undercount.
 *
 * Festival counters are an additive bucket — incremented in parallel with all-time + session
 * whenever [FishingFestivalState.active] is true at catch time. The bucket is cleared on
 * [FishingFestivalEvent.Started] (fresh festival) but preserved through [FishingFestivalEvent.Ended]
 * so the HUD can keep showing the just-ended numbers until the next festival starts.
 */
object FishingTracker {
    private val logger = SoulLogger("Soul/Fishing")

    // SkyHanni's authoritative double-hook regex (codes stripped → plain literal). Optional
    // trailing " Woot woot!" is a Hypixel server-side flavour variant.
    private val DOUBLE_HOOK_PATTERN = Regex("""It's a Double Hook!(?: Woot woot!)?""")
    private val COMPACTED_COUNT_SUFFIX = Regex(""" \((\d+)\)$""")
    private const val COMPACTED_AGGREGATE_WINDOW_MS = 60_000L

    // Cocoon kill-time line — fires when specific equipment cocoons a sea creature on kill.
    // The creature name is whatever appears between "cocooned a " and the trailing "!".
    // This is a separate outcome from a regular catch — does NOT consume pendingDoubleHook
    // and does NOT increment catch counters (the catch line already counted earlier).
    private val COCOON_PATTERN = Regex("""^CAUGHT! You cocooned a (.+)!$""")

    // Lines that may legitimately land between the double-hook line and the catch line.
    // Do not reset the sticky flag when these appear (see SeaCreatureManager.kt:106-118).
    private val THUNDER_CHARGED_PATTERN = Regex("""> Your bottle of thunder has fully charged!""")
    private val REINDRAKE_SPAWN_PATTERN = Regex("""A Reindrake forms from the depths\.""")
    private val AUTOPET_PATTERN = Regex("""^Autopet equipped your """)

    @Volatile
    private var pendingDoubleHook: Boolean = false

    @Volatile
    var sessionDoubleHooks: Long = 0L
        private set

    @Volatile
    var sessionCatches: Long = 0L
        private set

    @Volatile
    var sessionCocoons: Long = 0L
        private set

    /** Per-creature session counts. In-memory only; reset by [resetSession]. */
    @Volatile
    var sessionCatchesByCreature: Map<String, Long> = emptyMap()
        private set

    @Volatile
    var sessionDoubleHooksByCreature: Map<String, Long> = emptyMap()
        private set

    @Volatile
    var sessionCocoonsByCreature: Map<String, Long> = emptyMap()
        private set

    private var lastDoubleHookBase: String? = null
    private var lastDoubleHookCount: Int = 0
    private var lastDoubleHookAt: Long = 0L

    fun register() {
        Events.subscribe(this)
    }

    /** [Reset Session] HUD button — wipe in-memory session counters. Persisted totals stay. */
    fun resetSession() {
        sessionDoubleHooks = 0L
        sessionCatches = 0L
        sessionCocoons = 0L
        sessionCatchesByCreature = emptyMap()
        sessionDoubleHooksByCreature = emptyMap()
        sessionCocoonsByCreature = emptyMap()
        FishingTimer.resetSession()
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        if (!cfg.dev.trackers.fishingTracker()) return
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        if (stripped.isEmpty()) return // empty line — interleaver, leave flag intact

        if (handleDoubleHook(stripped)) return
        if (handleCatch(stripped)) return
        if (handleCocoon(stripped)) return
        if (isInterleaver(stripped)) return

        // Anything else — clear the stale flag so a later catch unrelated to a hook isn't mis-attributed.
        pendingDoubleHook = false
    }

    @HandleEvent
    fun onFestivalStarted(
        @Suppress("UNUSED_PARAMETER") event: FishingFestivalEvent.Started
    ) {
        PersistentStats.update {
            festivalDoubleHooks = 0L
            festivalCatches = 0L
            festivalCocoons = 0L
            festivalDoubleHooksByCreature = emptyMap()
            festivalCatchesByCreature = emptyMap()
            festivalCocoonsByCreature = emptyMap()
        }
        logger.info("Reset festival counters on festival start")
    }

    private fun handleDoubleHook(stripped: String): Boolean {
        val suffixMatch = COMPACTED_COUNT_SUFFIX.find(stripped)
        val base = if (suffixMatch != null) stripped.substring(0, suffixMatch.range.first) else stripped
        if (!DOUBLE_HOOK_PATTERN.matches(base)) return false

        val count = suffixMatch?.groupValues?.get(1)?.toIntOrNull() ?: 1
        val now = System.currentTimeMillis()
        val isAggregateContinuation =
            base == lastDoubleHookBase &&
                count > lastDoubleHookCount &&
                now - lastDoubleHookAt < COMPACTED_AGGREGATE_WINDOW_MS
        val delta = if (isAggregateContinuation) count - lastDoubleHookCount else 1

        lastDoubleHookBase = base
        lastDoubleHookCount = count
        lastDoubleHookAt = now

        if (delta <= 0) return true

        pendingDoubleHook = true
        sessionDoubleHooks += delta
        val festivalActive = FishingFestivalState.active
        PersistentStats.update {
            doubleHooksAllTime += delta
            if (festivalActive) festivalDoubleHooks += delta
        }
        return true
    }

    private fun handleCatch(stripped: String): Boolean {
        val creature = SeaCreatureCatalog.match(stripped) ?: return false
        val now = System.currentTimeMillis()
        val wasDoubleHook = pendingDoubleHook
        pendingDoubleHook = false
        sessionCatches += 1
        sessionCatchesByCreature = sessionCatchesByCreature.plusOne(creature.name)
        if (wasDoubleHook) {
            sessionDoubleHooksByCreature = sessionDoubleHooksByCreature.plusOne(creature.name)
        }
        val festivalActive = FishingFestivalState.active
        PersistentStats.update {
            catchesAllTime += 1
            catchesByCreature = catchesByCreature.plusOne(creature.name)
            if (wasDoubleHook) {
                doubleHooksByCreature = doubleHooksByCreature.plusOne(creature.name)
            }
            if (festivalActive) {
                festivalCatches += 1
                festivalCatchesByCreature = festivalCatchesByCreature.plusOne(creature.name)
                if (wasDoubleHook) {
                    festivalDoubleHooksByCreature = festivalDoubleHooksByCreature.plusOne(creature.name)
                }
            }
        }
        Events.publish(SeaCreatureCaught(creature, wasDoubleHook, festivalActive, now))
        return true
    }

    private fun handleCocoon(stripped: String): Boolean {
        val match = COCOON_PATTERN.matchEntire(stripped) ?: return false
        val creatureName = match.groupValues[1].trim()
        // Match against the catalog by canonical name. If the catalog doesn't know the
        // creature (e.g. a new festival creature added before our snapshot is refreshed),
        // we still count it under the chat-line name so totals don't lose data.
        val key = SeaCreatureCatalog.byName(creatureName)?.name ?: creatureName
        sessionCocoons += 1
        sessionCocoonsByCreature = sessionCocoonsByCreature.plusOne(key)
        val festivalActive = FishingFestivalState.active
        PersistentStats.update {
            cocoonsAllTime += 1
            cocoonsByCreature = cocoonsByCreature.plusOne(key)
            if (festivalActive) {
                festivalCocoons += 1
                festivalCocoonsByCreature = festivalCocoonsByCreature.plusOne(key)
            }
        }
        return true
    }

    private fun isInterleaver(stripped: String): Boolean =
        AUTOPET_PATTERN.containsMatchIn(stripped) ||
            THUNDER_CHARGED_PATTERN.containsMatchIn(stripped) ||
            REINDRAKE_SPAWN_PATTERN.containsMatchIn(stripped)

    private fun Map<String, Long>.plusOne(key: String): Map<String, Long> = this + (key to ((this[key] ?: 0L) + 1L))
}
