package com.soulreturns.features.dungeons.boss

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max

/** The card's settings for the crosshair timers (mocks/boss-timers/SPEC.md §7). */
class CrosshairOptions(
    val ticks: Boolean = false,
    val pad: Boolean = true,
    val goldor: Boolean = true,
    val action: Boolean = true,
    val purple: Boolean = true,
    /** The arm cue on the action line and the bounce hint under the window (experimental). */
    val arm: Boolean = true,
    val window: Boolean = true,
    val giga: Boolean = true,
    val high: Boolean = true,
    /** [CrosshairTimers.PAD_NEAR], [CrosshairTimers.PAD_ON] or [CrosshairTimers.PAD_ALWAYS]. */
    val padWhen: Int = CrosshairTimers.PAD_NEAR,
    val stayTime: Boolean = true,
    /** The Goldor death tick also as the ring (off: digits only). */
    val goldorRing: Boolean = false,
    val lateAmount: Boolean = true,
    val eta: Boolean = true,
    val fireball: Boolean = false,
    /** The countdown to Goldor's phase (terminals, levers and devices take clicks) in the phase place. */
    val terminals: Boolean = true,
)

/** Place 1: the pad poll loop, or Goldor's death tick. [ticks] to the next tick of the [period] loop. */
class PadPlace(val goldor: Boolean, val ticks: Double, val period: Int)

/** Place 2: what to do on the pads. [kind] is one of [CrosshairTimers.ACT_ON] and the rest. */
class ActionPlace(val kind: Int, val pillar: Pillar?, val ticks: Double? = null) {
    /** [CrosshairTimers.ACT_ON]: from the arm cue (else the Purple cue). */
    var arm = false
    /** [CrosshairTimers.ACT_ON]: the step is due now. */
    var now = false
    /** [CrosshairTimers.ACT_OFF]: the next poll is close. */
    var urgent = false
    var alpha = 1.0
}

/** Place 3: the armed crush window, or its result in the same place. */
class WindowPlace(val result: Boolean, val pillar: Pillar, val ticks: Double = 0.0) {
    // Window
    var storm = STORM_NONE
    var stormDistance = 0.0
    var stormEta: Double? = null
    var passed: Long? = null
    var etaGood = false
    var stepOff = false
    // Result
    var outcome = 0
    var check: Int? = null
    var late: Long? = null
    /** Missed: Storm's closest distance to the footprint. */
    var closest: Double? = null
    /** Missed: Storm was inside between checks; the nearest check and his ticks off it (< 0 = before it). */
    var betweenCheck: Int? = null
    var betweenOff: Long? = null
    var between = false
    /** Missed: Storm was under the pillar's floor level at a check. */
    var belowFloor = false
    var alpha = 1.0

    companion object {
        const val STORM_NONE = 0
        const val STORM_PASSED = 1
        const val STORM_INSIDE = 2
        const val STORM_APPROACHING = 3
        const val STORM_AWAY = 4
        /** Inside the footprint's square but under the pillar's floor level: no crush (M7). */
        const val STORM_BELOW = 5
    }
}

/** Place 4: Storm's phase, or the countdown to Goldor's. [kind] is one of [CrosshairTimers.PHASE_LIGHTNING] and the rest. */
class PhasePlace(val kind: Int, val ticks: Double, val fireball: Double? = null)

/** Everything at one tick; [title] = a vanilla Giga countdown title is on screen (the clocks lift above it). */
class Places(val pad: PadPlace?, val action: ActionPlace?, val window: WindowPlace?, val phase: PhasePlace?, val title: Boolean)

class PadView(val text: String, val fraction: Double, val color: Int, val ring: Boolean)

/** One line: [text] letter-spaced, then [time] in tabular digits (no spacing). */
class ActionView(val text: String, val time: String?, val color: Int, val alpha: Double)

class PhaseView(val word: String, val digits: String, val fireball: String?, val color: Int)

/** The crush window block: label over the body over an optional hint. */
class BlockView(
    val label: String,
    val color: Int,
    val hot: Boolean,
    val body: Int,
    val bodyText: String,
    val hint: String?,
    val hintColor: Int,
    val alpha: Double,
)

class PlacesView(val pad: PadView?, val action: ActionView?, val window: BlockView?, val phase: PhaseView?, val title: Boolean) {
    val empty: Boolean get() = pad == null && action == null && window == null && phase == null
}

/**
 * The crosshair timers' logic, ported 1:1 from `mocks/boss-timers/model.js` (`snapshot`, `places`,
 * `standPoll`) and `mock.js` (`padPlace`, `actionPlace`, `windowPlace`, `phasePlace`, `look`,
 * `hintOf`, `bodyOf`): four fixed places, each always meaning the same thing. Pure: fed by [StormModel].
 */
object CrosshairTimers {
    const val ACT_ON = 0
    const val ACT_STAY = 1
    const val ACT_OFF = 2
    const val ACT_EARLY = 3
    const val ACT_LATE = 4

    const val PHASE_LIGHTNING = 0
    const val PHASE_GIGA = 1
    const val PHASE_HIGH = 2
    const val PHASE_RETURNS = 3
    /** From Storm's death to Goldor's phase start T: terminals, levers and devices take clicks from then on. */
    const val PHASE_TERMINALS = 4

    const val CRUSHED = 0
    const val LATE = 1
    const val MISSED = 2

    const val BODY_DIGITS = 0
    const val BODY_WORD = 1
    const val BODY_CHECK = 2
    const val BODY_CROSS = 3

    const val PAD_NEAR = 0
    const val PAD_ON = 1
    const val PAD_ALWAYS = 2

    /** A result and the "too early" line show 60 ticks, the last 10 fade out. */
    const val RESULT_TICKS = 60.0
    const val RESULT_FADE_TICKS = 10.0
    /** The crush window's last second turns hot. */
    const val WARN = 20.0
    /** The ETA reads green when it beats the window by 0.3 s. */
    const val ETA_MARGIN = 6.0
    /** ETA = distance / Storm's current approach speed, while he approaches (> 0.02 b/tick). */
    const val APPROACHING = 0.02
    /** The arm cue shows this long before its poll (best trip 43 ticks + up to 19 waiting). */
    const val ARM_LEAD = 75.0
    /** STEP ON for the arm poll counts to this many ticks before it (the walk onto the pad). */
    const val STEP_LEAD = 17.0
    /** STEP OFF turns urgent this long after the poll (the next one is close). */
    const val OFF_URGENT = 12.0
    /** The Purple cue's TOO LATE shows from P+640 for this long. */
    const val LATE_TICKS = 40
    /** "Near a pad": horizontally within this of the pad's 7x7 square, and roughly at pad height. */
    const val NEAR_PAD = 8.0
    const val NEAR_PAD_Y = 6.0

    // Colors (SPEC §3)
    const val PURPLE_COLOR = 0xFFB45CFF.toInt()
    const val YELLOW_COLOR = 0xFFFFE14D.toInt()
    const val GREEN_COLOR = 0xFF55FF55.toInt()
    const val RED_COLOR = 0xFFFF5555.toInt()
    const val PAD_COLOR = 0xFF55FFFF.toInt()
    const val GIGA_COLOR = 0xFF6CB8FF.toInt()
    const val GOLDOR_COLOR = 0xFFFF7A7A.toInt()
    const val HIGH_COLOR = 0xFFFFA040.toInt()
    const val CRUSHED_COLOR = 0xFF55FF55.toInt()
    const val LATE_COLOR = 0xFFFF5555.toInt()
    const val MISSED_COLOR = 0xFFFF9D6B.toInt()
    const val STALE_COLOR = 0xFF9AA0AB.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    const val GOOD_COLOR = 0xFF7DFF9A.toInt()
    const val WARN_COLOR = 0xFFFFB36B.toInt()
    const val OFF_COLOR = 0xFF55FF55.toInt()
    const val BAD_COLOR = 0xFFFF5555.toInt()

    fun pillarColor(p: Pillar): Int =
        if (p === StormGeometry.E) PURPLE_COLOR else if (p === StormGeometry.WS) YELLOW_COLOR else if (p === StormGeometry.WN) GREEN_COLOR else RED_COLOR

    /** Ticks to the next multiple of [period] after an anchor ([sinceAnchor] = t - anchor); 0 on the line itself. */
    fun toGrid(sinceAnchor: Double, period: Int): Double = ceil(sinceAnchor / period) * period - sinceAnchor

    /** The poll a stand from [from] covers: the first pad grid tick at or after it (model.js `standPoll`, still standing). */
    fun standPoll(from: Long, anchor: Long, period: Int): Long = anchor + Math.floorDiv(from - anchor + period - 1, period.toLong()) * period

    // ------------------------------------------------------------------ number formats (mock.js fmtN, fmtLate, fmtEta)

    private fun whole(ticks: Double): Long = max(0.0, ceil(ticks - 1e-6)).toLong()

    /** JavaScript's `toFixed`: rounds the exact binary value, ties up (Java's formatter rounds the shortest decimal). */
    fun toFixed(v: Double, digits: Int): String = BigDecimal(v).setScale(digits, RoundingMode.HALF_UP).toPlainString()

    /** Seconds with one decimal, or whole ticks; never a unit. */
    fun number(ticks: Double, o: CrosshairOptions): String = if (o.ticks) whole(ticks).toString() else toFixed(whole(ticks) / 20.0, 1)

    fun late(ticks: Long, o: CrosshairOptions): String = if (o.ticks) "+$ticks" else "+" + toFixed(ticks / 20.0, 2)

    private fun eta(ticks: Double, o: CrosshairOptions): String = if (o.ticks) "${whole(ticks)} t" else "${toFixed(whole(ticks) / 20.0, 1)} s"

    // ------------------------------------------------------------------ the places (model.js snapshot + places)

    /** The pillars a player can be crushed under; the fourth (EN, red) never moves and has no cue, window or result. */
    private fun crushable(p: Pillar?): Pillar? = p?.takeIf { it !== StormGeometry.EN }

    /** The pad within [NEAR_PAD] of the player (horizontal distance to its square), or null. */
    fun nearPad(m: StormModel): Pillar? {
        val p = m.player ?: return null
        return StormGeometry.ALL.firstOrNull { pl ->
            val dx = max(max(pl.padX0 - p[0], p[0] - (pl.padX0 + Pillar.SIZE)), 0.0)
            val dz = max(max(pl.padZ0 - p[2], p[2] - (pl.padZ0 + Pillar.SIZE)), 0.0)
            crushable(pl) != null && hypot(dx, dz) <= NEAR_PAD && p[1] >= Pillar.PAD_Y_MIN - NEAR_PAD_Y && p[1] <= Pillar.PAD_Y_MAX + NEAR_PAD_Y
        }
    }

    /** The four places at [t] (server ticks with fraction), before the toggles. */
    fun places(m: StormModel, t: Double, o: CrosshairOptions): Places {
        val timings = m.timings
        val p3 = m.phase3
        // Goldor's death tick from T on: before `Who dares` too when the collapse already started his phase (M7).
        if (m.phase == BossPhase.GOLDOR || (m.phase == BossPhase.STORM_DONE && p3.began(t))) {
            val a = m.goldorAnchor ?: return Places(null, null, null, null, false)
            return Places(PadPlace(true, toGrid(t - a, timings.goldorPeriod), timings.goldorPeriod), null, null, null, false)
        }
        // Storm is dead: the countdown to T takes the phase place (his end line may still be queued, M7).
        val due = p3.expected()?.takeIf { (p3.stormDeath != null || p3.stormEnd != null) && t < it }
        val terminals = due?.let { PhasePlace(PHASE_TERMINALS, it - t) }
        if (m.phase == BossPhase.STORM_DONE) return Places(null, null, null, terminals, false)
        val p = m.pathetic
        if (m.phase != BossPhase.STORM || p == null) return Places(null, null, null, null, false)
        val tick = floor(t).toLong()
        val live = m.stormEnded == null
        val standing = crushable(m.onPad)
        val anchor = m.padAnchor
        val period = timings.padPeriod

        // Pad tick: while near a pad (or on it, or the whole phase, by setting).
        val pad =
            if (live && anchor != null && (o.padWhen == PAD_ALWAYS || (if (o.padWhen == PAD_ON) standing else nearPad(m)) != null)) {
                PadPlace(false, toGrid(t - anchor, period), period)
            } else {
                null
            }

        // Crush window: the newest armed one; else the latest result.
        var window: WindowPlace? = null
        val active = m.armed(m.now).filter { t < it.windowEnd!! }.maxByOrNull { it.runStart ?: 0L }
        val bait = m.bait(tick)
        if (active != null) {
            val end = active.windowEnd!!
            val w = WindowPlace(false, active.pillar, end - t)
            val s = m.stormPos
            val passed = active.passedBefore
            if (passed != null) {
                w.storm = WindowPlace.STORM_PASSED
                w.passed = passed
            } else if (m.stormTracked && s != null) {
                val depth = active.pillar.depth(s[0], s[2])
                if (m.below(active.pillar, s)) {
                    w.storm = WindowPlace.STORM_BELOW
                } else if (depth > 0) {
                    w.storm = WindowPlace.STORM_INSIDE
                } else {
                    val v = m.approachSpeed(active.pillar) ?: 0.0
                    w.storm = if (v > APPROACHING) WindowPlace.STORM_APPROACHING else WindowPlace.STORM_AWAY
                    w.stormDistance = -depth
                    w.stormEta = if (v > APPROACHING) -depth / v else null
                }
            }
            w.etaGood = w.stormEta.let { e -> e != null && e < end - t - ETA_MARGIN }
            w.stepOff = bait != null && bait.stage == BaitView.BAIT && bait.pillar === active.pillar && standing != null
            window = w
        } else {
            val r = m.latestResult
            val rp = r?.let { StormGeometry.pillar(it.pillar) }
            if (r != null && rp != null && t >= r.tick && t - r.tick < RESULT_TICKS) {
                val w = WindowPlace(true, rp)
                w.outcome = if (r.kind == CrushResult.CRUSHED) CRUSHED else if (r.kind == CrushResult.LATE) LATE else MISSED
                w.check = r.check
                w.late = r.lateTicks
                if (w.outcome == MISSED) {
                    w.passed = r.passedBefore
                    w.closest = r.closest?.takeIf { it > 0.0 }
                    w.between = r.kind == CrushResult.BETWEEN
                    w.belowFloor = r.kind == CrushResult.BELOW
                    w.betweenCheck = r.nearestCheck
                    w.betweenOff = r.offCheck
                }
                val age = t - r.tick
                w.alpha = if (age < RESULT_TICKS - RESULT_FADE_TICKS) 1.0 else (RESULT_TICKS - age) / RESULT_FADE_TICKS
                window = w
            }
        }

        // Phase: once Storm is dead the countdown to Goldor's phase; else Giga 1 from P, later ones from their `6`
        // title; else High to A+162, then Storm returns to A+190.
        var phase: PhasePlace? = terminals
        val strike = m.gigaStrike
        val title = m.gigaFromTitles && strike != null && t < strike
        if (phase == null && strike != null && t < strike) {
            phase = PhasePlace(if (strike - p <= timings.giga1Strike + FIRST_GIGA_SLACK) PHASE_LIGHTNING else PHASE_GIGA, strike - t)
        } else if (phase == null) {
            val a = m.highPhase(tick)
            if (a != null) {
                val e = t - a
                if (e < timings.highLength) {
                    phase = PhasePlace(PHASE_HIGH, timings.highLength - e, m.nextFireball(tick)?.let { f -> f - t })
                } else if (e < timings.highReturn) {
                    phase = PhasePlace(PHASE_RETURNS, timings.highReturn - e)
                }
            }
        }

        // Action: standing on a pad always wins; else too early > arm cue > Purple cue > too late. STAY, STEP OFF
        // and TOO EARLY only for the opening Purple stand: later stands are plain (user, after real runs, 2026-11-12).
        var action: ActionPlace? = null
        fun opening(pad: Pillar, from: Long?) = pad === StormGeometry.E && from != null && from <= p + timings.pyLatest
        if (live) {
            if (standing != null) {
                val since = m.onPadSince
                if (!opening(standing, since)) return Places(pad, null, window, phase, title)
                val poll = if (anchor != null && since != null) standPoll(since, anchor, period) else null
                action =
                    if (poll == null) {
                        ActionPlace(ACT_STAY, standing, anchor?.let { toGrid(t - it, period) })
                    } else if (t <= poll) {
                        ActionPlace(ACT_STAY, standing, poll - t)
                    } else {
                        ActionPlace(ACT_OFF, standing).also { it.urgent = t - poll > OFF_URGENT }
                    }
            } else {
                val s = m.lastStand?.takeIf { opening(it.pad, it.from) }
                val early = s != null && anchor != null && standPoll(s.from, anchor, period) >= s.to && t >= s.to && t < s.to + RESULT_TICKS
                val purpleStep = m.eStepAt?.takeIf { it >= p + timings.pyEarliest }
                if (early) {
                    val age = t - s!!.to
                    action = ActionPlace(ACT_EARLY, s.pad, toGrid((s.to - anchor!!).toDouble(), period))
                    action.alpha = if (age < RESULT_TICKS - RESULT_FADE_TICKS) 1.0 else (RESULT_TICKS - age) / RESULT_FADE_TICKS
                } else if (bait != null && bait.stage == BaitView.ARM && t >= bait.armPoll - ARM_LEAD && t < bait.armPoll + 1) {
                    val left = bait.armPoll - t
                    action = ActionPlace(ACT_ON, bait.pillar, max(0.0, left - STEP_LEAD)).also {
                        it.arm = true
                        it.now = left <= STEP_LEAD
                    }
                } else if (t >= p + timings.pyCue && t < p + timings.pyStepOn) {
                    action = ActionPlace(ACT_ON, StormGeometry.E, p + timings.pyStepOn - t)
                } else if (t >= p + timings.pyStepOn && (purpleStep == null || t < purpleStep) && t < p + timings.pyLatest) {
                    action = ActionPlace(ACT_ON, StormGeometry.E, 0.0).also { it.now = true }
                } else if (t >= p + timings.pyLatest && t < p + timings.pyLatest + LATE_TICKS && purpleStep == null) {
                    action = ActionPlace(ACT_LATE, StormGeometry.E)
                }
            }
        }
        return Places(pad, action, window, phase, title)
    }

    // ------------------------------------------------------------------ what each place looks like (mock.js)

    /** The places as drawn: toggles applied, colors, texts and number formats. */
    fun view(pl: Places, o: CrosshairOptions, stale: Boolean): PlacesView {
        fun pick(c: Int) = if (stale) STALE_COLOR else c
        val pad = pl.pad?.takeIf { if (it.goldor) o.goldor else o.pad }?.let {
            PadView(number(it.ticks, o), (it.ticks / it.period).coerceIn(0.0, 1.0), pick(if (it.goldor) GOLDOR_COLOR else PAD_COLOR), !it.goldor || o.goldorRing)
        }
        val action = pl.action?.let { action(it, o) }?.let { ActionView(it.text, it.time, pick(it.color), it.alpha) }
        val window = pl.window?.takeIf { o.window }?.let { block(it, o, stale) }
        val phase = pl.phase?.let { ph ->
            if (ph.kind == PHASE_TERMINALS) {
                return@let if (o.terminals) PhaseView("TERMINALS", number(ph.ticks, o), null, pick(GOLDOR_COLOR)) else null
            }
            val high = ph.kind == PHASE_HIGH || ph.kind == PHASE_RETURNS
            if (if (high) !o.high else !o.giga) return@let null
            val word = arrayOf("LIGHTNING", "GIGA", "HIGH", "STORM RETURNS")[ph.kind]
            val fb = if (o.fireball && ph.fireball != null) " · next fireball ${number(ph.fireball, o)}" else null
            PhaseView(word, number(ph.ticks, o), fb, pick(if (high) HIGH_COLOR else GIGA_COLOR))
        }
        return PlacesView(pad, action, window, phase, pl.title)
    }

    private class Line(val text: String, val time: String?, val color: Int, val alpha: Double)

    private fun action(a: ActionPlace, o: CrosshairOptions): Line? {
        if (!o.action) return null
        if (a.kind == ACT_ON && !(if (a.arm) o.arm else o.purple)) return null
        if (a.kind == ACT_LATE && !o.purple) return null
        val name = a.pillar?.name?.uppercase() ?: ""
        return if (a.kind == ACT_ON) {
            if (a.now) Line("STEP ON $name NOW", null, pillarColor(a.pillar!!), a.alpha) else Line("STEP ON $name in ", number(a.ticks!!, o), pillarColor(a.pillar!!), a.alpha)
        } else if (a.kind == ACT_STAY) {
            if (o.stayTime && a.ticks != null) Line("STAY ", number(a.ticks, o), WHITE, a.alpha) else Line("STAY", null, WHITE, a.alpha)
        } else if (a.kind == ACT_OFF) {
            if (a.urgent) Line("STEP OFF NOW", null, WARN_COLOR, a.alpha) else Line("STEP OFF", null, OFF_COLOR, a.alpha)
        } else if (a.kind == ACT_EARLY) {
            Line("OFF ${toFixed(a.ticks!! / 20.0, 1)} s TOO EARLY", null, BAD_COLOR, a.alpha)
        } else {
            Line("TOO LATE", null, BAD_COLOR, a.alpha)
        }
    }

    private fun block(w: WindowPlace, o: CrosshairOptions, stale: Boolean): BlockView {
        val name = w.pillar.name
        val color: Int
        val label: String
        var body = BODY_DIGITS
        var text = ""
        var hint: String? = null
        // A hint without its own color stays white on a stale clock too (mock.js block: only a set color greys).
        var hintColor: Int? = null
        if (!w.result) {
            color = pillarColor(w.pillar)
            label = name
            text = number(w.ticks, o)
            if (o.arm && w.stepOff) {
                hint = "Bounce back · stand by the pillar"
            } else if (o.eta) {
                if (w.storm == WindowPlace.STORM_PASSED) {
                    hint = "Storm passed ${toFixed(w.passed!! / 20.0, 1)} s before the step"
                    hintColor = WARN_COLOR
                } else if (w.storm == WindowPlace.STORM_BELOW) {
                    hint = "Storm below the floor"
                    hintColor = WARN_COLOR
                } else if (w.storm == WindowPlace.STORM_INSIDE) {
                    hint = "Storm inside"
                    hintColor = GOOD_COLOR
                } else if (w.storm != WindowPlace.STORM_NONE) {
                    hint = "Storm ${floor(w.stormDistance + 0.5).toLong()} b · ETA ${w.stormEta?.let { e -> eta(e, o) } ?: "—"}"
                    if (w.etaGood) hintColor = GOOD_COLOR
                }
            }
        } else if (w.outcome == CRUSHED) {
            color = CRUSHED_COLOR
            label = "Crushed" + (w.check?.let { c -> " · check $c" } ?: "")
            body = BODY_CHECK
        } else if (w.outcome == LATE) {
            color = LATE_COLOR
            label = "Late · $name"
            if (o.lateAmount && w.late != null) {
                text = late(w.late!!, o)
            } else {
                body = BODY_WORD
                text = "LATE"
            }
        } else {
            color = MISSED_COLOR
            label = "Missed · $name"
            body = BODY_CROSS
            if (w.passed != null) {
                hint = "Storm passed ${toFixed(w.passed!! / 20.0, 1)} s before the step"
                hintColor = WARN_COLOR
            } else if (w.belowFloor) {
                hint = "Storm below the floor"
                hintColor = WARN_COLOR
            } else if (w.between) {
                val c = w.betweenCheck
                val off = w.betweenOff
                hint = if (c != null && off != null) {
                    "Storm passed between checks, ${toFixed(abs(off) / 20.0, 1)} s ${if (off < 0) "before" else "after"} check $c"
                } else {
                    "Storm passed between checks"
                }
                hintColor = WARN_COLOR
            } else if (w.closest != null) {
                hint = "Storm never closer than ${toFixed(w.closest!!, 1)} b"
                hintColor = WARN_COLOR
            }
        }
        val hot = !stale && !w.result && w.ticks <= WARN
        return BlockView(
            label.uppercase(), if (stale) STALE_COLOR else color, hot, body, text, hint,
            hintColor?.let { if (stale) STALE_COLOR else it } ?: WHITE, w.alpha,
        )
    }

    /** A strike this close to P + giga1Strike is Giga 1 (titles move it by a tick or two). */
    private const val FIRST_GIGA_SLACK = 40
}
