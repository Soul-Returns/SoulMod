package com.soulreturns.features.dungeons.boss

/**
 * The boss fight's timing constants for one floor, in server ticks. Never one global set: the
 * values come from that floor's own evidence.
 *
 * - **F7**: five solo runs on the `set_time` server clock (docs/research/storm-crush-2026-11-12.md §1–2, §4).
 * - **M7**: eight party runs on the server clock (docs/research/m7-boss-2026-11-13.md §1–2): the Storm
 *   opening, Giga 1, the park leave, the pad grid (P+20k+1…+3, inside the self-check), the 3-check window
 *   and the Goldor period are F7's to the tick; the strike line comes at P+549–551. No M7 run reached a
 *   later Giga or a high phase: those numbers are F7's, [unverified] names them, and they calibrate
 *   themselves (Giga from its titles).
 */
class BossTimings(
    val floor: String,
    val source: String,
    /** What this floor's runs never showed, so it uses F7's values (for the log); empty = all measured. */
    val unverified: String = "",
    /** The pad poll and crush check loop, anchored at `Pathetic Maxor`. */
    val padPeriod: Int = 20,
    /** Polls after the last downward step that still check for Storm. */
    val crushChecks: Int = 3,
    /** Giga Lightning 1 strike after `Pathetic Maxor`. */
    val giga1Strike: Int = 520,
    /** Countdown title spacing (until a countdown shows its own). */
    val titleSpacing: Int = 25,
    /** Strike after the title `1`. */
    val strikeAfterOne: Int = 20,
    /** Storm stays at his Giga spot this long after the strike (`6` + 184). */
    val busyAfterStrike: Int = 39,
    /** Purple pad cue: from the cue line at P + [pyCue] count to the step-on tick P + [pyStepOn]; P + [pyEarliest]..[pyLatest] still makes the P + 660 check. */
    val pyCue: Int = 549,
    val pyStepOn: Int = 603,
    val pyEarliest: Int = 581,
    val pyLatest: Int = 640,
    /** Storm leaves his park spot for the E pillar. */
    val parkLeave: Int = 648,
    /** Goldor's death-zone check loop: from the P2→P3 floor collapse + 61.4 (M7), re-synced on every direct check. */
    val goldorPeriod: Int = 60,
    /** High phase (§2.3): Storm leaves the top at ascent + [highLength] (8 of 8 within 1), chases again at + [highReturn]. */
    val highLength: Int = 162,
    val highReturn: Int = 190,
    /** High-phase fireballs sit on one run-wide grid of this period (±2). */
    val fireballPeriod: Int = 40,
    /** Arm and bait (§10): the pad check to be on lies in ascent + [armFrom]..[armTo]. */
    val armFrom: Int = 160,
    val armTo: Int = 190,
) {
    companion object {
        val F7 = BossTimings("F7", source = "server clock, 5 solo F7 runs (2026-11-12)")
        val M7 = BossTimings(
            "M7", source = "server clock, 8 M7 party runs (2026-11-12)", unverified = "later Gigas and the high phase (F7 values)",
            pyCue = 550,
        )

        /** The table for a floor short name (`F7`, `M7`), or null where the helpers do not apply. */
        fun of(floor: String): BossTimings? = if (floor == "F7") F7 else if (floor == "M7") M7 else null
    }
}
