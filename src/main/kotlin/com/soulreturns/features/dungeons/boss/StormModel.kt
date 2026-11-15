package com.soulreturns.features.dungeons.boss

import com.soulreturns.features.dungeons.devices.GoldorPhaseModel
import java.util.ArrayDeque
import java.util.Locale
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Boss phases, as ints (the project avoids `when` on enums). */
object BossPhase {
    const val NONE = 0
    const val MAXOR = 1
    const val STORM = 2
    const val STORM_DONE = 3
    const val GOLDOR = 4
    const val CORE = 5
    const val NECRON = 6

    fun name(p: Int): String = arrayOf("none", "Maxor", "Storm", "Storm done", "Goldor", "Goldor core", "Necron").getOrElse(p) { "?" }
}

/** Where the Goldor death-zone grid comes from, worst first; all but [DIRECT] show as estimated. */
object GoldorSource {
    const val NONE = 0
    const val WHO_DARES = 1
    const val ANTS = 2
    const val COLLAPSE = 3
    const val DIRECT = 4

    fun name(s: Int): String = arrayOf("none", "Who dares", "Little ants", "floor collapse", "direct check").getOrElse(s) { "?" }
}

/** What Storm is doing, from his position alone (§2.2). States, never predictions. */
object StormState {
    const val UNTRACKED = 0
    const val CHASE = 1
    const val HIGH = 2
    const val GIGA = 3
    const val PARKED = 4
    /** Left the top of a high phase, pausing before he flies down to you (A+162 until A+190). */
    const val RETURNING = 5

    fun name(s: Int): String = arrayOf("untracked", "chase", "high", "Giga", "parked", "returning").getOrElse(s) { "?" }
}

/** The outcome of one crush window, shown for [StormModel.RESULT_TICKS] after [tick]. */
class CrushResult(val pillar: String, val kind: Int, val text: String, val tick: Long) {
    /** The window it settles (its last check), when known. */
    var windowEnd: Long? = null
    /** [CRUSHED]: the check that crushed (1..3). */
    var check: Int? = null
    /** [LATE]: ticks after the window's end. */
    var lateTicks: Long? = null
    /** Storm left the footprint this many ticks before the window's first layer moved. */
    var passedBefore: Long? = null
    /** [FAR]: Storm's closest distance to the footprint during the window (never 0: inside is [BETWEEN]). */
    var closest: Double? = null
    /** [BETWEEN]: the check nearest to Storm's time inside, and his ticks off it (< 0 = he left before it, > 0 = came after it). */
    var nearestCheck: Int? = null
    var offCheck: Long? = null

    companion object {
        const val CRUSHED = 0
        const val LATE = 1
        const val EDGE = 2
        const val EARLY = 3
        const val BUSY = 4
        const val FAR = 5
        const val UNTRACKED = 6
        const val BETWEEN = 7
        /** Storm inside the footprint at a check but below the pillar's floor level (y < [StormModel.MIN_CRUSH_Y]): never crushed (M7). */
        const val BELOW = 8
    }
}

/**
 * Storm at a crush check: [depth] > 0 inside the footprint by that much, else minus the distance; null = untracked.
 * [below]: inside the footprint's square but under the pillar's floor level, which never crushes ([depth] is minus the height gap).
 */
class CheckRecord(val tick: Long, val index: Int, val depth: Double?, val below: Boolean = false)

/** One stand on a pad: the first tick on it and the first tick off it. */
class Stand(val pad: Pillar, val from: Long, val to: Long)

class PillarState(val pillar: Pillar) {
    var bottom = StormGeometry.BOTTOM_START
    /** -1 the last move was down, +1 up, 0 untouched. */
    var dir = 0
    var retired = false
    var lastSound: Long? = null
    var lastGolem: Long? = null
    var lastDownSound: Long? = null
    var lastDownPoll: Long? = null
    var windowEnd: Long? = null
    /** First layer of the current continuous downward run, and the poll it belongs to. */
    var runStart: Long? = null
    var runPoll: Long? = null
    var runLayers = 0
    var firstCheck: Long? = null
    var open = false
    /** The window ends before Storm leaves his park spot (P + parkLeave): he cannot be under a pillar, so it shows and settles nothing. */
    var quiet = false
    var crushTick: Long? = null
    /** [crushTick] came from Storm's chat line (the pillar may correct its check). */
    var crushFromLine = false
    val checks = ArrayList<CheckRecord>()
    var lastEvaluated = Long.MIN_VALUE
    var minDist = Double.MAX_VALUE
    var sawStorm = false
    var allBusy = true
    var lastInsideBeforeChecks: Long? = null
    /** First and last tick Storm was inside the footprint from the first check on (never at a check: that crushes or is an edge). */
    var insideFrom: Long? = null
    var insideTo: Long? = null
    /** Last tick Storm was inside the footprint, any time (for "passed before the step"). */
    var lastInside: Long? = null
    /** Ticks Storm had left the footprint when this window's first layer moved (he passed before the step). */
    var passedBefore: Long? = null
    /** A settled miss is replaced by "Late by" if Storm still enters up to this tick. */
    var lateUntil: Long? = null
    var result: CrushResult? = null

    fun armed(now: Long): Boolean = open && !quiet && crushTick == null && windowEnd != null && now <= windowEnd!!

    /** Check ticks of the open window still ahead of [now]. */
    fun checksAhead(now: Long, period: Int): List<Long> {
        val ldp = lastDownPoll ?: return emptyList()
        val end = windowEnd ?: return emptyList()
        return (1..((end - ldp) / period).toInt()).map { ldp + it * period }.filter { it > now }
    }
}

/**
 * The experimental "arm and bait" hint for one high phase (§10, option 1): be on [pillar]'s pad for
 * the check at [armPoll] (inside A+160..A+190), then stand at [spot], 1-3 b outside the footprint on
 * the side away from Storm's hover point. [stage] [ARM] before the step, [BAIT] while that window runs.
 */
class BaitView(val pillar: Pillar, val a: Long, val armPoll: Long, val spot: DoubleArray, val stage: Int, val loaded: Boolean, val playerToSpot: Double?) {
    companion object {
        const val ARM = 0
        const val BAIT = 1
    }
}

/**
 * The F7/M7 boss fight as the helpers see it, on server ticks only: phases from the boss lines,
 * the pad poll grid, each pillar's crush window from its piston sounds, Storm's state from his
 * position, Giga Lightning from the countdown titles and Goldor's death-zone grid. Pure (no
 * Minecraft types): the live glue ([F7Boss]) and the recording fixtures in the tests feed it the
 * same events. Every timing constant comes from [timings] (per floor).
 *
 * Events must arrive in tick order. A Storm position sample at tick t counts for checks at t and
 * later, so the model evaluates every check before t first (§1.3: the server checks Storm's
 * position at the poll tick).
 */
class StormModel(var timings: BossTimings, private val log: (String) -> Unit = {}) {
    var edgeMargin = 1.0

    var phase = BossPhase.NONE
        private set
    var pathetic: Long? = null
        private set
    var padAnchor: Long? = null
        private set
    /** A pillar step has landed on the grid (or set it), so [padAnchor] is confirmed on this floor. */
    var padCalibrated = false
        private set
    private var offGrid: Long? = null

    var whoDares: Long? = null
        private set
    var goldorAnchor: Long? = null
        private set
    /** Where [goldorAnchor] comes from: [GoldorSource.WHO_DARES] .. [GoldorSource.DIRECT]. */
    var goldorSource = GoldorSource.NONE
        private set
    /** Goldor's phase start T (the same rule the device helpers use): the death-zone grid's anchor and the terminals countdown. */
    val phase3 = GoldorPhaseModel()
    /** The P2→P3 floor collapse: the first falling block at the Storm pad after Storm's death. */
    val collapseAt: Long? get() = phase3.collapse
    private var lastDirect: Long? = null
    /** The grid (anchor, source) the last direct check moved away from, until its re-timing confirms the move. */
    private var movedFrom: LongArray? = null
    /** Direct death-zone checks seen (tick, ticks off the grid it was predicted on), for the log and the tests. */
    val goldorChecks = ArrayList<LongArray>()

    var crushes = 0
        private set
    /** The last crush's signal tick (line, bar step or pillar), so one crush is never counted twice. */
    private var lastCrushSignal: Long? = null
    /** A crush read from the bar or the pillar whose line has not come yet (it may come late or never). */
    private var unconfirmed: Long? = null
    /** The Storm boss bar's last progress (null before one was seen). */
    private var bar: Float? = null
    var stormEnded: Long? = null
        private set

    val pillars = StormGeometry.ALL.map { PillarState(it) }

    // Storm
    var stormId: Int? = null
        private set
    var stormTracked = false
        private set
    var stormPos: DoubleArray? = null
        private set
    var stormSeenAt: Long? = null
        private set
    var state = StormState.UNTRACKED
        private set
    private var wasParked = false
    private var leftPark = false
    /** Storm's recent positions (tick, x, y, z) for his approach speed. */
    private val samples = ArrayDeque<DoubleArray>()
    /** Recent distinct track points (tick, x, y, z), newest last: Storm's track updates every 2-3 ticks. */
    private val points = ArrayList<DoubleArray>()

    // High (fireball) phase, §2.3: A = ascent start, the top is left at A+162, the chase resumes at A+190.
    var highA: Long? = null
        private set
    private var highHover: DoubleArray? = null
    private var leftTopLogged = false
    private var gigaSix: Long? = null
    var lastFireball: Long? = null
        private set

    // Giga Lightning
    var gigaStrike: Long? = null
        private set
    var gigaFromTitles = false
        private set
    var gigaBusyUntil: Long? = null
        private set
    private var lastTitleN = 0
    private var lastTitleTick = -1_000_000L
    private var titleSpacing = 25.0
    private var strikeSeen = false

    // Purple pad (crush 1)
    var eStepAt: Long? = null
        private set
    /** Actual departure from Purple after its opening crush attempt, never the initial park departure. */
    var leftPurpleAt: Long? = null
        private set
    private var sawPurpleAfterStep = false

    // Player
    var player: DoubleArray? = null
        private set
    var lastPad: Pillar? = null
        private set
    var onPadSince: Long? = null
        private set
    var onPad: Pillar? = null
        private set
    /** The player's last finished stand on a pad: pad, first and first-off tick. */
    var lastStand: Stand? = null
        private set

    var latestResult: CrushResult? = null
        private set
    var now = Long.MIN_VALUE
        private set

    private fun rel(t: Long): String = pathetic?.let { "P+${t - it}" } ?: "t$t"

    private fun say(msg: String) = log("${timings.floor} $msg")

    // ------------------------------------------------------------------ boss lines

    /** A named live boss bar is also a phase anchor, without inventing a chat line or a clock origin. */
    fun observeBossPhase(observed: Int) {
        if (observed > phase) phase = observed
    }

    /** A chat line, color codes stripped. */
    fun chat(line: String, tick: Long) {
        advance(tick)
        val s = line.trim()
        phase3.chat(s, tick)
        if (s == PATHETIC) {
            phase = BossPhase.STORM
            pathetic = tick
            if (!padCalibrated || padAnchor == null) padAnchor = tick
            gigaStrike = tick + timings.giga1Strike
            gigaFromTitles = false
            say("P2 start tick $tick (pad grid and Giga 1 from here)")
            return
        }
        if (s.startsWith("[BOSS] Maxor:") && phase == BossPhase.NONE) {
            phase = BossPhase.MAXOR
            return
        }
        if (s.startsWith("[BOSS] Storm:") && phase < BossPhase.STORM) {
            phase = BossPhase.STORM
            say("P2 joined without `Pathetic Maxor`: the pad grid waits for a pillar step")
        }
        if (s == STORM_END) {
            phase = BossPhase.STORM_DONE
            stormEnded = tick
            say("P2 end ${rel(tick)}")
            return
        }
        if (s == WHO_DARES) {
            phase = BossPhase.GOLDOR
            whoDares = tick
            takeStart(tick)
            return
        }
        if (s == CORE_OPEN) {
            if (phase == BossPhase.GOLDOR) phase = BossPhase.CORE
            say("core entrance WD+${whoDares?.let { tick - it }}")
            return
        }
        if (s.startsWith("[BOSS] Necron:") && phase < BossPhase.NECRON) {
            phase = BossPhase.NECRON
            return
        }
        if (phase == BossPhase.GOLDOR) {
            if (s == DEATH_ZONE) {
                if (lastDirect.let { it == null || abs(tick - it) > DIRECT_SAME }) goldorCheck(tick, "death-zone line")
                return
            }
            // A mask or pet save alone is no check: Goldor's Frenzy (touching him) procs them too (F7 solo, 20+ times);
            // a death-zone save brings the Goldor line in the same tick (M7 9/9).
            if (DEATH_LINE.matches(s)) {
                goldorCheck(tick, s)
                return
            }
            if (s.startsWith(LITTLE_ANTS) && goldorSource < GoldorSource.ANTS) {
                goldorAnchor = tick - ANTS_AFTER_CHECK
                goldorSource = GoldorSource.ANTS
                say("Goldor grid from `Little ants` WD+${tick - (whoDares ?: tick)}")
                return
            }
        }
        if (phase != BossPhase.STORM) return
        if (s == CRUSH_1 || s == CRUSH_2) {
            val u = unconfirmed
            if (u != null && tick - u <= LINE_LATE) {
                // Already read from the bar or the pillar: the line is late (M7: +26…48 behind Storm's dialogue queue).
                unconfirmed = null
                say("crush line ${rel(tick)}: confirms crush $crushes, ${tick - u} after its first signal")
                return
            }
            crush(tick)
            return
        }
        if (s == CUE_1 || s == CUE_2) say("strike line ${rel(tick)}")
        if (s.contains("Giga Lightning hit you") && !strikeSeen) {
            strikeSeen = true
            say("Giga strike ${rel(tick)} (expected ${gigaStrike?.let { rel(it) }})")
        }
        if (s.contains("Storm is enraged")) say("enraged ${rel(tick)}")
    }

    /** A title text, color codes stripped. Countdown digits drive the Giga timer on any floor. */
    fun title(text: String, tick: Long) {
        advance(tick)
        if (phase != BossPhase.STORM) return
        val n = text.trim().toIntOrNull() ?: return
        if (n !in 1..9) return
        val continues = n < lastTitleN && tick - lastTitleTick in 1..(3L * timings.titleSpacing)
        if (continues) {
            val measured = (tick - lastTitleTick).toDouble() / (lastTitleN - n)
            if (measured in 15.0..40.0) titleSpacing = measured
        } else {
            titleSpacing = timings.titleSpacing.toDouble()
            strikeSeen = false
        }
        lastTitleN = n
        lastTitleTick = tick
        if (!continues && n == 6) gigaSix = tick
        val strike = tick + ((n - 1) * titleSpacing).roundToLong() + timings.strikeAfterOne
        gigaStrike = strike
        gigaFromTitles = true
        gigaBusyUntil = strike + timings.busyAfterStrike
        say("Giga title $n ${rel(tick)} → strike ${rel(strike)}")
    }

    /**
     * A direct death-zone check at [tick]: a `died and became a ghost` line (anyone's) or the Goldor line itself (the
     * same tick as the save or death it answers). Within [GOLDOR_RESYNC] of the
     * grid it only logs its offset (M7: the collapse grid is as exact as a chat-timed check, −2.2…+3.4 over 14 checks,
     * re-syncing on each made it −0.7…+4.2); further off, the grid was wrong (a missed collapse) and moves onto it.
     */
    fun goldorCheck(tick: Long, what: String) {
        if (phase != BossPhase.GOLDOR) return
        if (lastDirect.let { it != null && abs(tick - it) <= DIRECT_SAME }) return
        val period = timings.goldorPeriod.toLong()
        val a = goldorAnchor
        val dev = a?.let { Math.floorMod(tick - it, period).let { off -> if (off > period / 2) off - period else off } }
        val was = goldorSource
        lastDirect = tick
        if (dev != null) goldorChecks += longArrayOf(tick, dev, was.toLong())
        val moved = dev == null || abs(dev) > GOLDOR_RESYNC
        if (moved) {
            movedFrom = a?.let { longArrayOf(it, was.toLong()) }
            goldorAnchor = tick
            goldorSource = GoldorSource.DIRECT
        }
        say(
            "Goldor check WD+${tick - (whoDares ?: tick)} ($what): " +
                (dev?.let { "${if (it >= 0) "+" else ""}$it off the ${GoldorSource.name(was)} grid${if (moved) ", grid moved" else ""}" } ?: "first grid"),
        )
    }

    /**
     * A tick read again by two-sided interpolation once the next `set_time` arrived ([line] as it was fed at [tick]):
     * the live clock extrapolates and runs 4-6 ticks off after server stalls (M7). Moves the P anchor and the Goldor grid.
     */
    fun retime(line: String, tick: Long, exact: Long) {
        if (exact == tick) return
        val s = line.trim()
        if (s == PATHETIC && pathetic == tick) {
            pathetic = exact
            if (!padCalibrated) padAnchor = exact
            if (!gigaFromTitles) gigaStrike = exact + timings.giga1Strike
            say("P2 start re-timed ${if (exact > tick) "+" else ""}${exact - tick} to tick $exact")
            return
        }
        if (phase == BossPhase.GOLDOR && lastDirect == tick) {
            lastDirect = exact
            val check = goldorChecks.lastOrNull()?.takeIf { it[0] == tick }?.also {
                it[1] += exact - tick
                it[0] = exact
            }
            val from = movedFrom
            if (goldorAnchor == tick) {
                if (from != null && check != null && abs(check[1]) <= GOLDOR_RESYNC) {
                    // Only the live clock was off (a stall): the check sits on the old grid after all.
                    goldorAnchor = from[0]
                    goldorSource = from[1].toInt()
                    say("Goldor check re-timed ${if (exact > tick) "+" else ""}${exact - tick}: ${check[1]} off the ${GoldorSource.name(goldorSource)} grid, grid kept")
                    return
                }
                goldorAnchor = exact
            }
            say("Goldor check re-timed ${if (exact > tick) "+" else ""}${exact - tick} to WD+${exact - (whoDares ?: exact)}")
        }
    }

    /**
     * A falling block added at the Storm pad: the first after Storm's death is the floor collapse into P3. Goldor's
     * phase starts one tick later when the player already stands in his area ([GoldorPhaseModel]).
     */
    fun falling(x: Double, y: Double, z: Double, tick: Long) {
        advance(tick)
        if (collapseAt != null || !GoldorPhaseModel.atPad(x, y, z)) return
        val inArea = player?.let { GoldorPhaseModel.inArea(it[1]) } == true
        phase3.collapse(tick, inArea)
        val x0 = collapseAt ?: return
        say("floor collapse tick $x0${whoDares?.let { " (WD+${x0 - it})" } ?: ""}${if (inArea) ", you stand in Goldor's area" else ""}")
        takeStart(tick)
    }

    /** Goldor's phase start T, once [phase3] knows it, anchors the death-zone grid (T + 60k on both floors). */
    private fun takeStart(tick: Long) {
        val t = phase3.start ?: return
        val source = if (phase3.fromCollapse) GoldorSource.COLLAPSE else GoldorSource.WHO_DARES
        if (goldorSource >= source) return
        goldorAnchor = t
        goldorSource = source
        val wd = whoDares
        say(
            "P3 start tick $t from the ${GoldorSource.name(source)}" +
                (collapseAt?.let { " (collapse ${if (wd != null) "WD-${wd - it}" else "tick $it"})" } ?: " (no floor collapse seen)") +
                if (wd == null) ", before `Who dares`" else if (tick == wd) ", at `Who dares`" else "",
        )
    }

    /**
     * The Storm boss bar's progress. Leaving a plateau (1.0 before crush 1, 0.45 before crush 2) is a crush, 3-18 ticks
     * after its check on M7 (16/16); the later steps are the bar's own animation.
     */
    fun bar(progress: Float, tick: Long) {
        advance(tick)
        val was = bar
        bar = progress
        if (phase != BossPhase.STORM || was == null) return
        val left = (was >= BAR_FULL && progress < BAR_FULL) || (was in BAR_HALF_LOW..BAR_HALF_HIGH && progress < BAR_HALF_LOW)
        if (left) barStep(tick)
    }

    private fun barStep(tick: Long) {
        if (lastCrushSignal.let { it != null && tick - it <= SAME_CRUSH }) return
        val period = timings.padPeriod.toLong()
        var best: PillarState? = null
        var bestCheck = 0L
        var bestK = 0
        var bestDepth = Double.NEGATIVE_INFINITY
        for (p in pillars) {
            val ldp = p.lastDownPoll ?: continue
            if (p.crushTick != null || p.quiet || p.retired) continue
            for (k in 1..timings.crushChecks) {
                val c = ldp + k * period
                if (c < tick - BAR_AFTER_MAX || c > tick + BAR_EARLY) continue
                val rec = p.checks.firstOrNull { it.tick == c }
                val d = rec?.depth ?: stormPos?.takeIf { stormTracked }?.let { crushDepth(p.pillar, it) } ?: continue
                if (d > bestDepth) {
                    best = p
                    bestCheck = c
                    bestK = k
                    bestDepth = d
                }
            }
        }
        val p = best
        if (p == null || bestDepth < -BAR_EDGE) {
            say("Storm bar step ${rel(tick)} with Storm under no checked pillar: waits for the pillar")
            return
        }
        crushAt(p, bestCheck, bestK, "bar step", tick)
    }

    // ------------------------------------------------------------------ pillars

    /**
     * A sound packet: [id] without namespace, block coordinates. [fresh] = the clock ran on time
     * when it arrived (no stall), so its tick may move the pad grid.
     */
    fun sound(id: String, x: Double, y: Double, z: Double, volume: Float, tick: Long, fresh: Boolean = true) {
        advance(tick)
        if (id == GoldorPhaseModel.THUNDER) {
            if (volume >= GoldorPhaseModel.STRIKE_VOLUME && y >= GoldorPhaseModel.STRIKE_MIN_Y && phase3.stormDeath == null) {
                phase3.strike(tick)
                phase3.stormDeath?.let { say("Storm's death strike ${rel(it)}: terminals open about ${rel(it + GoldorPhaseModel.STRIKE_TO_START)}") }
            }
            return
        }
        if (phase != BossPhase.STORM) return
        val down = id == PISTON_DOWN
        if (down || id == PISTON_UP) {
            if (volume < 5f) return
            val p = stateAt(x, z) ?: return
            pistonStep(p, down, floor(y).toInt(), tick, fresh)
            return
        }
        if (id == GOLEM) {
            val p = stateAt(x, z) ?: return
            p.lastGolem = tick
            p.bottom = if (y < 175) StormGeometry.BOTTOM_FLOOR else StormGeometry.BOTTOM_TOP
            return
        }
        if (id == BREAK_DOOR) {
            // Only the loud one (volume 3) is a pillar breaking; quiet ones (0.25) play near the pillars too (R6).
            if (volume < DESTROY_VOLUME) return
            val hit = pillars.firstOrNull { p ->
                !p.retired && x >= p.pillar.x0 - DESTROY_SLACK && x <= p.pillar.x1 + DESTROY_SLACK && z >= p.pillar.z0 - DESTROY_SLACK && z <= p.pillar.z1 + DESTROY_SLACK
            }
            if (hit != null) {
                retire(hit, tick)
                destroyed(hit, tick)
            }
        }
    }

    private fun stateAt(x: Double, z: Double): PillarState? {
        val pillar = StormGeometry.pillarAtCorner(floor(x).toInt(), floor(z).toInt()) ?: return null
        return pillars.first { it.pillar === pillar }
    }

    private fun pistonStep(p: PillarState, down: Boolean, layer: Int, tick: Long, fresh: Boolean) {
        if (p.retired) return
        val newRun = p.lastSound.let { it == null || tick - it > BATCH_GAP } || (p.dir == -1) != down
        val prevDir = p.dir
        p.lastSound = tick
        p.dir = if (down) -1 else 1
        p.bottom = if (down) layer - 1 else layer + 1
        if (newRun) {
            val grid = padAnchor?.let { Math.floorMod(tick - it, timings.padPeriod.toLong()) }
            say("step ${p.pillar.id} ${if (down) "down" else "up"} ${rel(tick)}${grid?.let { " (grid +$it)" } ?: ""}")
            // Only a pad-started downward batch tells the grid (§5.0); reversals at the floor/top start 8 after a poll.
            val reversal = prevDir == 1 || p.lastGolem.let { it != null && tick - it < 60 }
            if (down && !reversal && fresh) selfCheck(tick)
        }
        if (!down) return
        // The Purple step for crush 1 only: a mage cycling E in the opening (M7, P+43…267) is not it.
        if (p.pillar === StormGeometry.E && eStepAt == null && pathetic.let { it != null && tick >= it + timings.pyEarliest }) eStepAt = tick
        val anchor = padAnchor ?: return
        if (newRun) {
            p.runStart = tick
            p.runPoll = pollAtOrBefore(anchor, tick + SOUND_SLACK)
            p.runLayers = 0
        }
        if (!p.open || p.windowEnd.let { it == null || tick > it + CRUSH_GRACE }) startEpisode(p, tick)
        p.lastDownSound = tick
        p.lastDownPoll = runLayerPoll(p, p.runLayers)
        p.runLayers++
        p.windowEnd = p.lastDownPoll!! + timings.crushChecks * timings.padPeriod
        p.quiet = pathetic.let { it != null && p.windowEnd!! < it + timings.parkLeave }
    }

    /**
     * The poll layer [i] of the current downward run belongs to ("the latest poll at or before the
     * layer", §5.1), counted in layers rather than from arrival ticks: sounds after a stall arrive in a
     * burst, but every layer still sends one. Layers are [LAYER_TICKS] apart from the run's start; a pad
     * run starts on the poll (+0/+1, read up to 5 late), a reversal 8 after it.
     */
    private fun runLayerPoll(p: PillarState, i: Int): Long {
        val poll = p.runPoll!!
        val runStart = p.runStart!!
        val d = runStart - poll
        val period = timings.padPeriod.toLong()
        // A run read 1-2 ticks before the grid poll sits on a grid that runs early here: use the run's own tick (R6 `grid +18`).
        if (d < 0) return runStart + Math.floorDiv(LAYER_TICKS * i, period) * period
        val start = if (d <= 5) 1L else d
        return poll + Math.floorDiv(start + LAYER_TICKS * i, period) * period
    }

    private fun pollAtOrBefore(anchor: Long, t: Long): Long {
        val period = timings.padPeriod.toLong()
        return anchor + Math.floorDiv(t - anchor, period) * period
    }

    private fun startEpisode(p: PillarState, tick: Long) {
        if (p.open) finalize(p, tick)
        p.open = true
        p.crushTick = null
        p.checks.clear()
        p.lastEvaluated = Long.MIN_VALUE
        p.minDist = Double.MAX_VALUE
        p.sawStorm = false
        p.allBusy = true
        p.lastInsideBeforeChecks = null
        p.insideFrom = null
        p.insideTo = null
        p.lateUntil = null
        p.result = null
        p.firstCheck = runLayerPoll(p, 0) + timings.padPeriod
        val s = stormPos
        val outNow = !stormTracked || s == null || crushDepth(p.pillar, s) <= 0
        p.passedBefore = p.lastInside?.let { tick - it }?.takeIf { outNow && it in 0..PASSED_LOOK }
    }

    /** §5.0 self-check: a pad batch must start on the grid; two batches agreeing off it move the grid. */
    private fun selfCheck(tick: Long) {
        val anchor = padAnchor
        val period = timings.padPeriod.toLong()
        if (anchor == null) {
            padAnchor = tick - STEP_LEAD
            padCalibrated = true
            say("pad grid from a step at tick $tick")
            return
        }
        val off = Math.floorMod(tick - anchor, period)
        if (off <= GRID_LATE || off >= period - GRID_EARLY) {
            padCalibrated = true
            offGrid = null
            return
        }
        val prev = offGrid
        if (prev != null) {
            val d = Math.floorMod(tick - prev, period)
            if (d <= 2 || d >= period - 2) {
                padAnchor = tick - STEP_LEAD
                padCalibrated = true
                offGrid = null
                say("pad grid moved by ${if (off > period / 2) off - period else off} ticks (two steps off the grid) at ${rel(tick)}")
                return
            }
        }
        offGrid = tick
        say("step off the pad grid by $off at ${rel(tick)} (once: kept)")
    }

    /** A crush line on time (F7; M7 11/16): the pillar whose footprint holds Storm (§5.1), an armed one first. Never "the only pillar left". */
    private fun crush(tick: Long) {
        val pos = stormPos?.takeIf { stormTracked }
        val inside = if (pos == null) emptyList() else pillars.filter { crushDepth(it.pillar, pos) > 0 }.sortedByDescending { crushDepth(it.pillar, pos) }
        val armed = pillars.filter { p ->
            p.open && p.crushTick == null && p.lastDownPoll != null && tick >= p.lastDownPoll!! + timings.padPeriod - 2 && tick <= p.windowEnd!! + 2
        }
        val pick = inside.firstOrNull { it in armed } ?: inside.firstOrNull() ?: armed.maxByOrNull { it.lastDownPoll!! }
        if (pick == null) {
            crushes++
            lastCrushSignal = tick
            say("crush $crushes ${rel(tick)} on ? (Storm in no footprint)${pos?.let { " Storm at ${fmt(it[0])}, ${fmt(it[2])}" } ?: ""}")
            return
        }
        val ldp = pick.lastDownPoll
        val n = if (ldp != null && pick in armed) ((tick + 1 - ldp + timings.padPeriod / 2) / timings.padPeriod).toInt().coerceIn(1, timings.crushChecks) else null
        crushAt(pick, tick, n, "line", tick)
    }

    /**
     * A crush of [p] at [check] (its [k]th check) read from [source] at [signal]: the result shows from [signal].
     * Replaces whatever verdict the window had ("Inside … no crush" when the line never came).
     */
    private fun crushAt(p: PillarState, check: Long, k: Int?, source: String, signal: Long) {
        if (p.crushTick != null) return
        if (p.pillar === StormGeometry.E) sawPurpleAfterStep = true
        p.crushFromLine = source == "line"
        crushes++
        lastCrushSignal = signal
        unconfirmed = if (source == "line") null else signal
        p.crushTick = check
        val pos = stormPos?.takeIf { stormTracked }
        say(
            "crush $crushes ${rel(check)} on ${p.pillar.id}${k?.let { " (check $it)" } ?: ""} from the $source${if (signal != check) " at ${rel(signal)}" else ""}" +
                (pos?.let { " Storm at ${fmt(it[0])}, ${fmt(it[1])}, ${fmt(it[2])}" } ?: ""),
        )
        decide(p, CrushResult(p.pillar.id, CrushResult.CRUSHED, if (k != null) "Crushed (check $k)" else "Crushed", signal).also { it.check = k })
    }

    /**
     * A pillar destroyed at [tick] (volume-3 `break_wooden_door`): it was crushed at its check nearest to
     * tick − [DESTROY_AFTER] (M7 +19…22 in 15/16, +26.6 inside a stall). Records a crush no line or bar gave,
     * and corrects the check of one taken from a late line.
     */
    private fun destroyed(p: PillarState, tick: Long) {
        val est = tick - DESTROY_AFTER
        val period = timings.padPeriod.toLong()
        val ldp = p.lastDownPoll
        var check: Long? = null
        var k: Int? = null
        if (ldp != null) {
            val kk = Math.round((est - ldp).toDouble() / period).toInt().coerceIn(1, timings.crushChecks)
            val c = ldp + kk * period
            if (abs(c - est) <= DESTROY_SNAP) {
                check = c
                k = kk
            }
        }
        val known = p.crushTick
        if (known == null) {
            crushAt(p, check ?: est, k, "pillar", tick)
            return
        }
        val r = p.result
        // Only a line's check can be off (it may come late); the bar step sits 3-18 after its check, the pillar +19…27.
        if (p.crushFromLine && check != null && k != r?.check && r != null && r.kind == CrushResult.CRUSHED) {
            p.crushTick = check
            say("crush on ${p.pillar.id} moved from ${rel(known)} to ${rel(check)} (check $k) by the pillar at ${rel(tick)}")
            decide(p, CrushResult(p.pillar.id, CrushResult.CRUSHED, "Crushed (check $k)", r.tick).also { it.check = k })
        }
    }

    private fun retire(p: PillarState, tick: Long) {
        if (p.retired) return
        p.retired = true
        say("pillar ${p.pillar.id} retired ${rel(tick)}")
    }

    private fun decide(p: PillarState, r: CrushResult) {
        if (p.quiet && r.kind != CrushResult.CRUSHED) {
            // Storm still on his opening path: no pillar can crush him, so the window says nothing (M7: a mage cycling E).
            if (p.open) say("window ${p.pillar.id} ends ${rel(p.windowEnd ?: r.tick)} before Storm leaves the park: no result")
            p.open = false
            p.lateUntil = null
            return
        }
        val replaced = p.result?.takeIf { !p.open }
        r.windowEnd = p.windowEnd
        r.passedBefore = p.passedBefore
        p.result = r
        p.open = false
        p.lateUntil = if (r.kind == CrushResult.CRUSHED || r.kind == CrushResult.EDGE || r.kind == CrushResult.LATE) null else p.windowEnd?.plus(LATE_LOOK)
        latestResult = r
        val ldp = p.lastDownPoll
        say(
            "window ${p.pillar.id}: ${r.text}${ldp?.let { " (last down poll ${rel(it)}, end ${rel(p.windowEnd ?: it)})" } ?: ""} at ${rel(r.tick)}" +
                (replaced?.let { " (replaces: ${it.text})" } ?: ""),
        )
    }

    // ------------------------------------------------------------------ Storm

    /** A wither at its server position (any wither; the model picks Storm by §5.0). */
    fun wither(id: Int, x: Double, y: Double, z: Double, tick: Long) {
        advance(tick - 1)
        if (stormId == null) {
            val nearSpawn = hypot(x - StormGeometry.SPAWN[0], z - StormGeometry.SPAWN[2]) <= 2.0 && y in 180.0..196.0
            val lateJoin = phase == BossPhase.STORM && pathetic.let { it == null || tick - it > ADOPT_ANY_AFTER }
            if ((nearSpawn && (phase == BossPhase.MAXOR || phase == BossPhase.STORM)) || lateJoin) {
                stormId = id
                say("Storm is wither $id at ${fmt(x)}, ${fmt(y)}, ${fmt(z)} ${rel(tick)}")
            }
        }
        if (id != stormId) return
        stormTracked = true
        stormPos = doubleArrayOf(x, y, z)
        stormSeenAt = tick
        if (phase == BossPhase.STORM && leftPurpleAt == null) {
            val purple = stateAt(StormGeometry.E.id)
            val attempted = eStepAt != null && purple?.firstCheck.let { it != null && tick >= it }
            val depth = StormGeometry.E.depth(x, z)
            if (attempted && depth > 0) sawPurpleAfterStep = true
            if (sawPurpleAfterStep && depth < -2.0) leftPurpleAt = tick
        }
        samples.addLast(doubleArrayOf(tick.toDouble(), x, y, z))
        while (samples.size > 2 && samples.peekFirst()[0] < tick - SAMPLE_TICKS) samples.removeFirst()
        val prev = points.lastOrNull()
        if (prev == null || abs(prev[1] - x) > 1e-4 || abs(prev[2] - y) > 1e-4 || abs(prev[3] - z) > 1e-4) {
            points += doubleArrayOf(tick.toDouble(), x, y, z)
            if (points.size > POINTS_KEPT) points.removeAt(0)
            trackPoint(tick)
        }
        updateState(tick)
        for (p in pillars) observe(p, x, y, z, tick)
        advance(tick)
    }

    /**
     * §2.3 on each new track point: the ascent (y up by >= 0.6 between track points at >= 0.6 b/tick
     * with < 0.2 b sideways, twice in a row so a climbing chase is not one, from y <= 181) starts a high phase at
     * A = the start of the rise (which begins while he still slows down; §2.3's table and its
     * L = A+162 use that tick). Also
     * notes when he leaves the top (for the log: L should be A+162).
     */
    private fun trackPoint(tick: Long) {
        if (phase != BossPhase.STORM || points.size < 2) return
        val cur = points[points.size - 1]
        val prev = points[points.size - 2]
        val a = highA
        val inPhase = a != null && tick < a + timings.highReturn
        if (!inPhase && pathetic.let { it == null || tick - it > OPENING_TICKS } && climb(points.size - 1) && climb(points.size - 2)) {
            var first = points.size - 2
            while (first > 1 && points[first - 1][2] - points[first - 2][2] > RISE_MIN) first--
            if (points[first - 1][2] <= ASCENT_FROM_Y) {
                // The rise began within one track update (2 ticks) before its first point; he may have stood still long before (R6 P+3714..3757).
                val start = max(points[first - 1][0], points[first][0] - TRACK_STEP).toLong()
                highA = start
                highHover = null
                leftTopLogged = false
                val six = gigaSix?.let { start - it }?.takeIf { it in 0..400 }
                say(
                    "high phase A=${rel(start)} from y${fmt(points[first - 1][2])}: top left at ${rel(start + timings.highLength)}, chase from ~${rel(start + timings.highReturn)}" +
                        (six?.let { " (Giga 6 + $it)" } ?: ""),
                )
                return
            }
        }
        if (a == null || !inPhase) return
        val side = hypot(cur[1] - prev[1], cur[3] - prev[3])
        if (tick - a in 15..timings.highLength.toLong() && cur[2] >= prev[2] - 0.05 && side < 0.1) highHover = doubleArrayOf(cur[1], cur[2], cur[3])
        if (!leftTopLogged && tick - a > 40 && (prev[2] - cur[2] >= 0.3 || side >= 0.3)) {
            leftTopLogged = true
            say("left the top ${rel(tick)} (A+${tick - a}, expected A+${timings.highLength})")
        }
    }

    /** The interval ending at track point [i] is a straight climb. */
    private fun climb(i: Int): Boolean {
        if (i < 1) return false
        val p = points[i - 1]
        val c = points[i]
        val dy = c[2] - p[2]
        return dy >= ASCENT_DY && dy / max(c[0] - p[0], 1.0) >= ASCENT_RATE && hypot(c[1] - p[1], c[3] - p[3]) < ASCENT_SIDE
    }

    fun witherGone(id: Int, tick: Long) {
        advance(tick)
        if (id != stormId) return
        stormTracked = false
        points.clear()
        samples.clear()
        updateState(tick)
    }

    private fun observe(p: PillarState, x: Double, y: Double, z: Double, tick: Long) {
        val depth = crushDepth(p.pillar, doubleArrayOf(x, y, z))
        if (depth > 0) p.lastInside = tick
        val end = p.windowEnd ?: return
        if (!p.open) {
            val until = p.lateUntil ?: return
            if (tick > until) {
                p.lateUntil = null
            } else if (tick > end && depth > 0) {
                decide(p, CrushResult(p.pillar.id, CrushResult.LATE, "Late by ${secs(tick - end)}", tick).also { it.lateTicks = tick - end })
            }
            return
        }
        if (tick <= end) {
            p.sawStorm = true
            p.minDist = min(p.minDist, max(0.0, -depth))
            if (state != StormState.HIGH && state != StormState.GIGA) p.allBusy = false
            if (depth > 0 && p.firstCheck.let { it != null && tick < it }) p.lastInsideBeforeChecks = tick
            if (depth > 0 && p.firstCheck.let { it != null && tick >= it }) {
                if (p.insideFrom == null) p.insideFrom = tick
                p.insideTo = tick
            }
        } else if (tick <= end + LATE_LOOK && depth > 0 && p.checks.none { (it.depth ?: -1.0) > 0 }) {
            decide(p, CrushResult(p.pillar.id, CrushResult.LATE, "Late by ${secs(tick - end)}", tick).also { it.lateTicks = tick - end })
        }
    }

    private fun updateState(tick: Long) {
        val s = classify(tick)
        if (s != state) {
            if (state == StormState.PARKED && !leftPark && wasParked) {
                leftPark = true
                say("Storm leaves his park spot ${rel(tick)}")
            }
            state = s
        }
        if (s == StormState.PARKED) wasParked = true
    }

    private fun classify(tick: Long): Int {
        val a = highA
        if (a != null && tick >= a && tick < a + timings.highLength) return StormState.HIGH
        if (a != null && tick >= a && tick < a + timings.highReturn) return StormState.RETURNING
        val p = stormPos
        if (!stormTracked || p == null) return StormState.UNTRACKED
        if (hypot(p[0] - StormGeometry.GIGA[0], p[2] - StormGeometry.GIGA[2]) < 1.5 && abs(p[1] - StormGeometry.GIGA[1]) < 3) return StormState.GIGA
        if (hypot(p[0] - StormGeometry.PARK[0], p[2] - StormGeometry.PARK[2]) < 1.5 && abs(p[1] - StormGeometry.PARK[1]) < 2) return StormState.PARKED
        return StormState.CHASE
    }

    // ------------------------------------------------------------------ player

    fun player(x: Double, y: Double, z: Double, tick: Long) {
        player = doubleArrayOf(x, y, z)
        val pad = StormGeometry.ALL.firstOrNull { it.onPad(x, y, z) }
        if (pad != onPad) {
            val was = onPad
            val since = onPadSince
            if (was != null && since != null) lastStand = Stand(was, since, tick)
            onPad = pad
            onPadSince = if (pad != null) tick else null
            if (pad != null) {
                lastPad = pad
                if (phase == BossPhase.STORM) say("on the ${pad.padName} pad ${rel(tick)}")
            }
        }
    }

    // ------------------------------------------------------------------ time

    /** Evaluates every crush check up to [tick] and settles windows that are over. */
    fun advance(tick: Long) {
        if (tick < now) return
        now = tick
        phase3.advance(tick)
        if (phase != BossPhase.STORM) return
        if (highA != null) updateState(tick)
        val period = timings.padPeriod.toLong()
        for (p in pillars) {
            if (!p.open) continue
            val ldp = p.lastDownPoll ?: continue
            val end = p.windowEnd ?: continue
            var c = max(ldp + period, p.lastEvaluated + period)
            while (c <= min(tick, end)) {
                val pos = stormPos?.takeIf { stormTracked }
                p.checks += CheckRecord(c, ((c - ldp) / period).toInt(), pos?.let { crushDepth(p.pillar, it) }, pos != null && below(p.pillar, pos))
                p.lastEvaluated = c
                c += period
            }
            if (p.crushTick != null) continue
            val inside = p.checks.filter { (it.depth ?: -1.0) > 0 }
            // Storm inside at a check without a crush line yet: the bar (+3…18) or the pillar (+19…22) may still say crush.
            if (tick > end + (if (inside.isNotEmpty()) DESTROY_WAIT else CRUSH_GRACE)) {
                val under = p.checks.lastOrNull { it.below }
                if (under != null && inside.isNotEmpty() && under.tick > inside.last().tick) {
                    // He sank under the floor after an inside check: that is why no crush came.
                    decide(p, CrushResult(p.pillar.id, CrushResult.BELOW, "Storm below the floor at check ${under.index}, no crush", tick))
                    continue
                }
                if (inside.isNotEmpty()) {
                    val last = inside.last()
                    val text = if (last.depth!! < edgeMargin) {
                        "Edge: ${fmt(last.depth)} b inside at the last check"
                    } else {
                        "Inside by ${fmt(last.depth)} b at check ${last.index}, no crush"
                    }
                    decide(p, CrushResult(p.pillar.id, CrushResult.EDGE, text, tick))
                    continue
                }
                // Settled at the window's end; Storm still entering within LATE_LOOK turns it into "Late by".
                finalize(p, tick)
            }
        }
    }

    private fun finalize(p: PillarState, tick: Long) {
        if (!p.open) return
        val first = p.firstCheck
        val r =
            if (p.checks.any { it.below }) {
                val c = p.checks.last { it.below }
                CrushResult(p.pillar.id, CrushResult.BELOW, "Storm below the floor at check ${c.index}, no crush", tick)
            } else if (p.lastInsideBeforeChecks != null && first != null) {
                CrushResult(p.pillar.id, CrushResult.EARLY, "Storm passed ${secs(first - p.lastInsideBeforeChecks!!)} too early", tick)
            } else if (p.passedBefore != null) {
                CrushResult(p.pillar.id, CrushResult.EARLY, "Storm passed ${secs(p.passedBefore!!)} before the step", tick)
            } else if (!p.sawStorm) {
                CrushResult(p.pillar.id, CrushResult.UNTRACKED, "Storm out of range", tick)
            } else if (p.allBusy) {
                CrushResult(p.pillar.id, CrushResult.BUSY, "Storm in high/Giga phase", tick)
            } else if (p.insideFrom != null || p.minDist <= 0.0) {
                between(p, tick)
            } else {
                CrushResult(p.pillar.id, CrushResult.FAR, "Storm never closer than ${fmt(p.minDist)} b", tick)
                    .also { it.closest = p.minDist }
            }
        decide(p, r)
    }

    /**
     * Storm was inside the footprint during the window but at none of its checks: the result names the check
     * nearest to his time inside and how far off it was ([CrushResult.offCheck] < 0 = before it).
     */
    private fun between(p: PillarState, tick: Long): CrushResult {
        val from = p.insideFrom
        val to = p.insideTo
        var best: CheckRecord? = null
        var off = 0L
        if (from != null && to != null) {
            for (c in p.checks) {
                val d = if (c.tick < from) from - c.tick else if (c.tick > to) to - c.tick else 0L
                if (best == null || abs(d) < abs(off)) {
                    best = c
                    off = d
                }
            }
        }
        val text =
            if (best == null) {
                "Storm passed between checks"
            } else {
                "Storm passed between checks, ${secs(abs(off))} ${if (off < 0) "before" else "after"} check ${best.index}"
            }
        return CrushResult(p.pillar.id, CrushResult.BETWEEN, text, tick).also {
            it.offCheck = if (best == null) null else off
            it.nearestCheck = best?.index
        }
    }

    /** Storm at [pos] is inside [pillar]'s square but under its floor level: never crushed (M7 run 7, y168.0–168.8 at 7 checks). */
    fun below(pillar: Pillar, pos: DoubleArray): Boolean = pos[1] < MIN_CRUSH_Y && pillar.depth(pos[0], pos[2]) > 0

    /** [Pillar.depth] with the height: under the floor level counts as outside by the height gap. */
    fun crushDepth(pillar: Pillar, pos: DoubleArray): Double = if (below(pillar, pos)) pos[1] - MIN_CRUSH_Y else pillar.depth(pos[0], pos[2])

    /** Storm's approach speed toward [pillar] in blocks per tick (> 0 = closing in), from his last ~5 ticks. */
    fun approachSpeed(pillar: Pillar): Double? {
        val p = stormPos ?: return null
        val last = samples.peekLast() ?: return null
        val then = samples.lastOrNull { it[0] <= last[0] - 5 } ?: return null
        val dt = last[0] - then[0]
        if (dt <= 0) return null
        val dNow = max(0.0, -pillar.depth(p[0], p[2]))
        val dThen = max(0.0, -pillar.depth(then[1], then[3]))
        return (dThen - dNow) / dt
    }

    // ------------------------------------------------------------------ readouts

    /** Ticks (with fraction) from [t] to the next pad poll, or null without a grid. */
    fun toNextPoll(t: Double): Double? {
        val a = padAnchor ?: return null
        val period = timings.padPeriod
        val off = ((t - a) % period + period) % period
        return period - off
    }

    /** Ticks from [t] to Goldor's next death-zone check, or null outside P3. */
    fun toNextGoldor(t: Double): Double? {
        if (phase != BossPhase.GOLDOR) return null
        val a = goldorAnchor ?: return null
        val period = timings.goldorPeriod
        val off = ((t - a) % period + period) % period
        return period - off
    }

    /** The armed pillars at [t], soonest-ending first. */
    fun armed(t: Long): List<PillarState> = pillars.filter { it.armed(t) }.sortedBy { it.windowEnd }

    /** The result to show at [t] (the latest, for [RESULT_TICKS]). */
    fun resultAt(t: Long): CrushResult? = latestResult?.takeIf { t - it.tick in 0..RESULT_TICKS }

    fun stateAt(id: String): PillarState? = pillars.firstOrNull { it.pillar.id == id }

    /** The pillar steps down when armed: not at the floor, and its last move was down (or untouched at the start height). */
    fun loaded(p: PillarState): Boolean =
        !p.retired && p.bottom > StormGeometry.BOTTOM_FLOOR && (p.dir == -1 || (p.dir == 0 && p.bottom == StormGeometry.BOTTOM_START))

    /** A `minecraft:fireball` entity added. Only high-phase ones count: the opening flight's sit on another grid. */
    fun fireball(x: Double, y: Double, z: Double, tick: Long) {
        advance(tick)
        val a = highA ?: return
        if (tick < a || tick >= a + timings.highLength) return
        val prev = lastFireball
        lastFireball = tick
        say("fireball ${rel(tick)} (A+${tick - a}${prev?.let { ", ${tick - it} after the last" } ?: ""}) at ${fmt(x)}, ${fmt(y)}, ${fmt(z)}")
    }

    /** Ascent tick A of the current high phase while it lasts (until A + [BossTimings.highReturn]), else null. */
    fun highPhase(t: Long): Long? = highA?.takeIf { t >= it && t < it + timings.highReturn }

    /** The next fireball on the run-wide grid, while it falls before Storm leaves the top; null before the run's first. */
    fun nextFireball(t: Long): Long? {
        val a = highPhase(t) ?: return null
        val last = lastFireball ?: return null
        val period = timings.fireballPeriod.toLong()
        val next = last + (Math.floorDiv(t - last, period) + 1) * period
        return next.takeIf { it < a + timings.highLength }
    }

    /**
     * §10 option 1 for the current high phase: arm the loaded pillar nearest Storm's hover point for
     * the first poll in A + [BossTimings.armFrom]..[BossTimings.armTo], then stand at the bait spot
     * until that window ends. Null outside, without a grid, or with no loaded pillar.
     */
    fun bait(t: Long): BaitView? {
        val a = highA ?: return null
        if (t < a || phase != BossPhase.STORM) return null
        val anchor = padAnchor ?: return null
        val hover = highHover ?: stormPos ?: return null
        val period = timings.padPeriod.toLong()
        val armPoll = anchor + Math.floorDiv(a + timings.armFrom - anchor + period - 1, period) * period
        if (armPoll > a + timings.armTo) return null
        val target =
            // Red (EN) never moves: never a target.
            pillars.filter { it.pillar !== StormGeometry.EN && !it.retired && (loaded(it) || it.armed(t)) }
                .minByOrNull { hypot(it.pillar.x0 + Pillar.SIZE / 2.0 - hover[0], it.pillar.z0 + Pillar.SIZE / 2.0 - hover[2]) } ?: return null
        val stepped = target.armed(t) && target.lastDownPoll.let { it != null && it >= armPoll - period && it <= armPoll + 2 * period }
        val stage = if (stepped) BaitView.BAIT else if (t <= armPoll + 2) BaitView.ARM else return null
        val spot = baitSpot(target.pillar, hover)
        val me = player
        return BaitView(target.pillar, a, armPoll, spot, stage, loaded(target), me?.let { hypot(it[0] - spot[0], it[2] - spot[2]) })
    }

    companion object {
        const val PATHETIC = "[BOSS] Storm: Pathetic Maxor, just like expected."
        const val STORM_END = "[BOSS] Storm: I should have known that I stood no chance."
        const val WHO_DARES = "[BOSS] Goldor: Who dares trespass into my domain?"
        const val DEATH_ZONE = "[BOSS] Goldor: What do you think you are doing there!"
        const val CORE_OPEN = "The Core entrance is opening!"
        const val CRUSH_1 = "[BOSS] Storm: Ouch, that hurt!"
        const val CRUSH_2 = "[BOSS] Storm: Oof"
        const val CUE_1 = "[BOSS] Storm: ENERGY HEED MY CALL!"
        const val CUE_2 = "[BOSS] Storm: THUNDER LET ME BE YOUR CATALYST!"
        const val LITTLE_ANTS = "[BOSS] Goldor: Little ants"
        /** A death-zone death prints this on the check's tick (teammates too); `was killed by` / `died to a mob` are other deaths. */
        val DEATH_LINE = Regex("☠ \\S+ died and became a ghost\\.")

        /** Lines whose tick anchors a grid: the live glue reads them again between the `set_time` packets around them ([retime]). */
        fun retimed(s: String): Boolean = s == PATHETIC || s == DEATH_ZONE || DEATH_LINE.matches(s)

        const val PISTON_DOWN = "block.piston.extend"
        const val PISTON_UP = "block.piston.contract"
        const val GOLEM = "entity.iron_golem.hurt"
        const val BREAK_DOOR = "entity.zombie.break_wooden_door"

        /** A piston sound within this many ticks of the previous one continues its batch (layers are 4 apart). */
        const val BATCH_GAP = 12L
        const val LAYER_TICKS = 4L
        /** Clock reads may come up to 2 ticks early: a run starting at poll-2..-1 belongs to that poll. */
        const val SOUND_SLACK = 2L
        /** Grid self-check tolerance: a batch starts at poll+0..+3 (M7 +1…+3), the live clock may read 2 off. */
        const val GRID_LATE = 5L
        const val GRID_EARLY = 3L
        /** A batch's first layer reads at poll+0..+2 on the clock (+1 typical): a grid taken from a step sits 1 before it. */
        const val STEP_LEAD = 1L
        /** The crush line may come a tick or two after the check it belongs to. */
        const val CRUSH_GRACE = 3L
        /** How long after the window Storm's entry still counts as "late". */
        const val LATE_LOOK = 60L
        const val RESULT_TICKS = 60L
        /** Storm's opening hover above his spawn is not a high phase. */
        const val OPENING_TICKS = 300L
        /** In P2 without `Pathetic` (joined late) any wither is Storm after this long. */
        const val ADOPT_ANY_AFTER = 100L
        /** Storm under this y inside a footprint is not crushed (M7: no crush at y168.0–168.8, all 16 crushes at y ≥ 169.0). */
        const val MIN_CRUSH_Y = 168.99
        /** The pillar breaks this long after its crush (M7 +19…22 in 15/16); its check is the one nearest, within [DESTROY_SNAP]. */
        const val DESTROY_AFTER = 20L
        const val DESTROY_SNAP = 12L
        /** A window with Storm inside at a check settles this long after its end: the pillar (+22) may still say crush. */
        const val DESTROY_WAIT = 24L
        /** A crush line this long after a bar step or pillar confirms that crush (M7: up to +48 after the crush). */
        const val LINE_LATE = 70L
        /** Signals of one crush (line, bar step, pillar) lie within this. */
        const val SAME_CRUSH = 25L
        /** Storm bar plateaus: full before crush 1, 0.45 before crush 2. */
        const val BAR_FULL = 0.99f
        const val BAR_HALF_LOW = 0.43f
        const val BAR_HALF_HIGH = 0.47f
        /** A bar step comes 3-18 ticks after its check (M7 16/16); the clock may read the step up to 3 before it. */
        const val BAR_AFTER_MAX = 22L
        const val BAR_EARLY = 3L
        /** A bar step goes to a pillar with Storm at most this far outside at the check (M3/M4 crushed at 0.2–0.3 b outside E). */
        const val BAR_EDGE = 1.0
        /** A direct check further than this off the grid moves it (the anchor was wrong); nearer, it is the line's own timing noise. */
        const val GOLDOR_RESYNC = 5L
        /** The Goldor line comes 0-2 ticks after the own save or death it belongs to. */
        const val DIRECT_SAME = 3L
        /** `Little ants…` (the first Goldor line) comes 3.4 after the first check (M7, 8 runs). */
        const val ANTS_AFTER_CHECK = 3L
        /** A pillar breaking plays the door sound at volume 3 inside its footprint; quiet ones (0.25) are not it. */
        const val DESTROY_VOLUME = 2.5f
        const val DESTROY_SLACK = 3.0
        /** Storm inside a footprint this shortly before its first layer moved "passed before the step". */
        const val PASSED_LOOK = 60L
        /** §2.3 ascent: y up by this much between two track points, with less sideways motion, from at most this y. */
        const val ASCENT_DY = 0.6
        const val ASCENT_SIDE = 0.2
        const val ASCENT_FROM_Y = 181.0
        /** He climbs 0.8 b/tick; a chase rising off the floor (R4 P+1139) does about 0.5. */
        const val ASCENT_RATE = 0.6
        /** A track point higher than the one before by more than this belongs to the rise. */
        const val RISE_MIN = 0.05
        const val POINTS_KEPT = 12
        /** Storm's track updates every 2 ticks. */
        const val TRACK_STEP = 2.0
        const val SAMPLE_TICKS = 40L
        /** The bait spot lies this far outside the footprint, away from Storm's hover point. */
        const val BAIT_OUTSIDE = 2.0

        /** On the line from [hover] through [pillar]'s center, [BAIT_OUTSIDE] beyond the far edge. */
        fun baitSpot(pillar: Pillar, hover: DoubleArray): DoubleArray {
            val cx = pillar.x0 + Pillar.SIZE / 2.0
            val cz = pillar.z0 + Pillar.SIZE / 2.0
            var dx = cx - hover[0]
            var dz = cz - hover[2]
            val len = hypot(dx, dz)
            if (len < 1e-6) {
                dx = 1.0
                dz = 0.0
            } else {
                dx /= len
                dz /= len
            }
            val toEdge = (Pillar.SIZE / 2.0) / max(abs(dx), abs(dz))
            val d = toEdge + BAIT_OUTSIDE
            return doubleArrayOf(cx + dx * d, StormGeometry.BOTTOM_FLOOR.toDouble(), cz + dz * d)
        }

        fun secs(ticks: Long): String = String.format(Locale.ROOT, "%.2f s", ticks / 20.0)

        fun fmt(v: Double): String = String.format(Locale.ROOT, "%.1f", v)
    }
}
