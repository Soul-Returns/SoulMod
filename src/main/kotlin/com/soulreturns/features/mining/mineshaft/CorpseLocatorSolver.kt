package com.soulreturns.features.mining.mineshaft

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * A corpse spawn spot as the data lists it. The dataset mixes conventions (floored, rounded, ceiled
 * stand position), so the stand lies anywhere in the spot's box: [x - SPREAD, x + SPREAD] per axis.
 */
data class CorpseSpot(val x: Int, val y: Int, val z: Int)

/**
 * What one ding said: the distance interval from its pitch byte and the candidates it fits.
 * [explained]: it fits only corpses that were already located before it (so it points at nothing new).
 * [placed]: the spot this ding's run located; [withdrawn]: the ding-placed spot this ding's run disproved.
 */
data class DingFit(
    val ix: Int,
    val iy: Int,
    val iz: Int,
    val pitchByte: Int,
    val lo: Double,
    val hi: Double,
    val fitting: List<CorpseSpot>,
    val explained: Boolean = false,
    val placed: CorpseSpot? = null,
    val withdrawn: CorpseSpot? = null,
)

/**
 * The bundled spawn spots per mineshaft layout (sidebar id, e.g. `AMBE_1`), from meowdding-repo
 * (MIT, `assets/soul/mineshaft/corpse_spots.json`). Every crystal shaft (id ending in `_C`) uses
 * the one [crystal] list, also ids the file does not name (`RUBY_C`): crystal shafts differ only in
 * gemstone color (owner; the six `_C` entries in the source are identical; `RUBY_C`'s three stands
 * in recording `mineshaft-corpses-4` sit on them). [fixed] layouts always hold a corpse on their
 * spots (the Vanguard shaft), keyed to its type. Any other id not in the file is an unknown layout.
 */
@Serializable
data class CorpseSpotData(
    val layouts: Map<String, List<List<Int>>> = emptyMap(),
    val crystal: List<List<Int>> = emptyList(),
    val fixed: Map<String, String> = emptyMap(),
) {
    fun spots(layout: String): List<CorpseSpot>? =
        (layouts[layout] ?: crystal.takeIf { isCrystal(layout) && it.isNotEmpty() })?.map { CorpseSpot(it[0], it[1], it[2]) }

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** Crystal shafts share one spot list. */
        fun isCrystal(layout: String): Boolean = layout.endsWith("_C")

        fun parse(text: String): CorpseSpotData = json.decodeFromString(serializer(), text)
    }
}

/**
 * Pure solver for the Organ Donor Talisman ding (rule://mineshaft, Corpse locator). The ding is a
 * harp at the player's server-side position whose pitch byte `n = round(pitch * 63)` encodes the 3D
 * distance d from the player's feet to the talisman's **current target**:
 * `n = floor(63 * (2 - (d / 20)^2))`, silent beyond 20 blocks. The target is an unlooted corpse
 * within 20 blocks, but **not always the nearest**: the talisman keeps it until it is beyond 20,
 * then takes the nearest (AMET_1, recording `mineshaft-corpses-2`, rule://mineshaft). So a ding
 * says one corpse is at that distance, never that none is nearer: nothing is ever ruled out by one.
 *
 * Fit test: the player is anywhere in the 1/8-block cell the packet position was truncated from
 * ([cellLo]/[cellHi]), the stand anywhere in what is known of it: its seen position ([pin]), else
 * the region the dings left of its box (confirmed spots), else the whole box. Each ding that fits
 * only one spot narrows that spot's region ([Region]).
 *
 * **Runs** (the sticky target, load-bearing): between two run ends ([endRun]: a loot line, a pause in
 * the dings, a candidate that could now be beyond [RANGE]) every ding measures the same corpse. A
 * run places a spot as soon as one candidate fits every ding of it (so a single ding that fits one
 * spot places it at once); a spot a run placed must fit the run's following dings. One that does not
 * is withdrawn ([DingFit.withdrawn]) when the run agreed with it only once or two dings in a row
 * disagree (one stray ding after two agreeing ones is ignored); then the run is solved again from
 * all its dings, else from the disagreeing ones. Spots certain without the dings (seen, a party
 * member's, fixed) are never withdrawn by dings: a disagreeing ding ends the run.
 *
 * Besides the listed [spots], corpses can be located without data ([extras]): a stand seen with line
 * of sight outside every box (dataset gap, layout without data) or one a party member saw. Extras
 * are keyed by their floored position (never a listed spot: a stand inside a box belongs to it) and
 * are candidates like any spot, always confirmed and pinned.
 *
 * [learned] stand positions (seen on earlier visits, [CorpseSpotMemory]) are an **incomplete** list:
 * a ding fitting only a learned spot is no proof (the target may be a corpse on a spot nobody
 * recorded, e.g. its vertical mirror: JADE_1 has two spots 2.2 apart horizontally, 15 vertically),
 * so a learned spot is confirmed **only by sight**. It is still a candidate, so a ding it fits never
 * fits a listed spot alone (a corpse on a dataset gap cannot confirm the wrong listed spot).
 */
class CorpseLocatorSolver(val spots: List<CorpseSpot>, fixed: Boolean = false, learned: List<DoubleArray> = emptyList()) {
    /** Learned stand positions keyed by floored position, minus any inside a listed spot's box. */
    private val learnedPoints = LinkedHashMap<CorpseSpot, DoubleArray>().also { m ->
        for (p in learned) {
            if (spots.any { standOffset(it, p[0], p[1], p[2]) != null }) continue
            m.putIfAbsent(CorpseSpot(floor(p[0]).toInt(), floor(p[1]).toInt(), floor(p[2]).toInt()), p)
        }
    }
    private val confirmedSet = LinkedHashSet<CorpseSpot>()
    private val lootedSet = LinkedHashSet<CorpseSpot>()
    private val extraList = ArrayList<CorpseSpot>()
    private val fixedSpots: Set<CorpseSpot> = if (fixed) spots.toSet() else emptySet()
    private val regions = HashMap<CorpseSpot, Region>()
    private val pins = HashMap<CorpseSpot, DoubleArray>()
    private val hints = HashMap<CorpseSpot, DoubleArray>()

    /** The dings that fit each spot alone (at most [SUPPORT_MAX]): its evidence when the tab says too many are shown. */
    private val supportDings = HashMap<CorpseSpot, ArrayList<DingFit>>()

    /** Loot lines that matched no candidate (player position, type): a stand of that type seen there later was that corpse. */
    private val unplacedLoots = ArrayList<Pair<DoubleArray, String?>>()

    /** Spots only the player's dings located (not seen, not a party member's, not fixed): what a run may withdraw. */
    private val dingPlaced = HashSet<CorpseSpot>()

    /** Spots the player looked at from close by and saw no corpse on: no candidate until a stand is seen there. */
    private val emptySpots = LinkedHashSet<CorpseSpot>()

    /** This run's dings that agree with its target (all of them while no target is placed). */
    private val runDings = ArrayList<DingFit>()

    /** Consecutive dings that do not fit the run's target. */
    private val runAgainst = ArrayList<DingFit>()

    /** While no target is placed: the candidates every ding of the run fits; null = no run. */
    private var runCandidates: Set<CorpseSpot>? = null

    /** The spot this run measures, once placed or known. */
    var runTarget: CorpseSpot? = null
        private set

    /** This run began against a target its first ding did not fit: it places only on two dings. */
    private var runContested = false

    /** Ding-placed spots withdrawn this stay (a run's later dings, or sight) and the last one with why. */
    var withdrawals = 0
        private set
    var lastWithdrawal: String? = null
        private set

    init {
        for (s in fixedSpots) confirm(s)
    }

    val confirmed: Set<CorpseSpot> get() = confirmedSet
    val looted: Set<CorpseSpot> get() = lootedSet
    val extras: List<CorpseSpot> get() = extraList
    val learnedSpots: Set<CorpseSpot> get() = learnedPoints.keys

    var lastDing: DingFit? = null
        private set

    fun candidates(): List<CorpseSpot> = LinkedHashSet(spots + extraList + learnedPoints.keys).filter { it !in lootedSet && it !in emptySpots }

    /** Spots seen empty from close by this stay ([seenEmpty]). */
    val empty: Set<CorpseSpot> get() = emptySpots

    /** Whether [spot] is shown on the player's own dings alone: a run's later dings, or a look at its box, may withdraw it. */
    fun isDingOnly(spot: CorpseSpot): Boolean = spot in dingPlaced

    /** Feeds one ding (packet ints = position * 8, pitch byte); null when the byte is not a talisman distance. */
    fun ding(ix: Int, iy: Int, iz: Int, pitchByte: Int): DingFit? {
        val (lo, hi) = interval(pitchByte) ?: return null
        val fitting = candidates().filter { fitsNow(ix, iy, iz, it, lo, hi) }
        val explained = fitting.isNotEmpty() && fitting.all { it in confirmedSet }
        val fit = DingFit(ix, iy, iz, pitchByte, lo, hi, fitting, explained)
        if (fitting.size == 1) supportDings.getOrPut(fitting[0]) { ArrayList() }.let { if (it.size < SUPPORT_MAX) it += fit }
        // A corpse the run may be measuring could be out of range here: the talisman may have switched.
        if ((runTarget?.let { listOf(it) } ?: runCandidates.orEmpty()).any { maxReach(ix, iy, iz, it) > RANGE }) endRun()
        val target = runTarget
        var placed: CorpseSpot? = null
        var withdrawn: CorpseSpot? = null
        if (target == null) {
            placed = extendRun(fit)
        } else if (target in fitting) {
            runDings += fit
            runAgainst.clear()
            if (fitting.size == 1 && target !in pins) regions[target]?.cut(ix, iy, iz, lo, hi)
        } else if (target !in dingPlaced) {
            // Seen, a party member's or fixed: certain. A stray, or the talisman switched unnoticed: wait for a second ding.
            endRun()
            runContested = true
            placed = extendRun(fit)
        } else {
            runAgainst += fit
            if (runDings.size < 2 || runAgainst.size >= 2) {
                val all = runDings + runAgainst
                val against = runAgainst.toList()
                withdraw(target, "${against.size} ding(s) of its run do not fit it, ${runDings.size} did")
                withdrawn = target
                if (!startRun(all) && !startRun(against)) endRun()
                // One ding against one: either may be the stray, so the next ding decides.
                runContested = true
                placed = decideRun()
            }
        }
        return fit.copy(placed = placed, withdrawn = withdrawn).also { lastDing = it }
    }

    /** The talisman may measure another corpse from the next ding on (a loot line, a pause in the dings). */
    fun endRun() {
        runDings.clear()
        runAgainst.clear()
        runCandidates = null
        runTarget = null
        runContested = false
    }

    /**
     * The player is at (x, y, z) (client position, every tick): where a corpse the run may be
     * measuring could be out of the talisman's range ([RANGE] less [MOVE_MARGIN] for the server's
     * position lagging ours), it may go silent and take another corpse without a ding between, so the
     * run ends.
     */
    fun moved(x: Double, y: Double, z: Double) {
        val watched = runTarget?.let { listOf(it) } ?: runCandidates ?: return
        if (watched.any { farthest(x, y, z, it) > RANGE - MOVE_MARGIN }) endRun()
    }

    /** Farthest the stand of [s] can be from (x, y, z): its pin, else its region, else its box. */
    private fun farthest(x: Double, y: Double, z: Double, s: CorpseSpot): Double =
        (pins[s] ?: learnedPoints[s])?.let { norm(x - it[0], y - it[1], z - it[2]) }
            ?: regions[s]?.farthest(x, y, z)
            ?: norm(max(abs(x - (s.x - SPREAD)), abs(x - (s.x + SPREAD))), max(abs(y - (s.y - SPREAD)), abs(y - (s.y + SPREAD))), max(abs(z - (s.z - SPREAD)), abs(z - (s.z + SPREAD))))

    /** Adds a ding to a run without a target: the candidates fitting all its dings; none left starts the run anew from this ding. */
    private fun extendRun(fit: DingFit): CorpseSpot? {
        val prev = runCandidates
        val both = prev?.let { p -> fit.fitting.filter { it in p } }
        if (prev != null && both.isNullOrEmpty()) endRun()
        runCandidates = if (both.isNullOrEmpty()) fit.fitting.toSet() else both.toSet()
        runDings += fit
        return decideRun()
    }

    /** A run from [dings] (one corpse measured by all of them): false when no candidate fits every one. */
    private fun startRun(dings: List<DingFit>): Boolean {
        val fitAll = candidates().filter { s -> dings.all { fitsNow(it.ix, it.iy, it.iz, s, it.lo, it.hi) } }
        if (fitAll.isEmpty()) return false
        endRun()
        runDings += dings
        runCandidates = fitAll.toSet()
        return true
    }

    /** One candidate left: the run's target; placed (returned) unless already shown. A learned spot is placed only by sight. */
    private fun decideRun(): CorpseSpot? {
        val only = runCandidates?.singleOrNull() ?: return null
        if (runContested && runDings.size < 2) return null
        if (only in learnedPoints && only !in confirmedSet) return null
        runCandidates = null
        runTarget = only
        val placed = only !in confirmedSet
        if (placed) {
            confirm(only)
            dingPlaced += only
        }
        if (only !in pins) for (d in runDings) if (d.fitting.singleOrNull() == only) regions[only]?.cut(d.ix, d.iy, d.iz, d.lo, d.hi)
        return only.takeIf { placed }
    }

    private fun withdraw(spot: CorpseSpot, why: String) {
        confirmedSet -= spot
        dingPlaced -= spot
        regions -= spot
        hints -= spot
        supportDings -= spot
        withdrawals++
        lastWithdrawal = "$spot: $why"
    }

    /**
     * The player looked at all of [spot]'s box from close by and no corpse stand stood there: a corpse
     * the dings alone placed there is withdrawn, and the spot is no candidate until a stand is seen on
     * it. Seen, party and fixed corpses stay (the stand, or the data, beats a look at the box).
     */
    fun seenEmpty(spot: CorpseSpot): Boolean {
        if (spot !in dingPlaced) return false
        withdraw(spot, "seen empty from close by")
        emptySpots += spot
        if (runTarget == spot) endRun()
        return true
    }

    /** Farthest the stand of [s] can be from the packet's cell: its pin, else its region, else its box. */
    private fun maxReach(ix: Int, iy: Int, iz: Int, s: CorpseSpot): Double {
        (pins[s] ?: learnedPoints[s])?.let { return cellPointMax(ix, iy, iz, it[0], it[1], it[2]) }
        regions[s]?.let { r ->
            val half = norm((cellHi(ix) - cellLo(ix)) / 2, (cellHi(iy) - cellLo(iy)) / 2, (cellHi(iz) - cellLo(iz)) / 2)
            return r.farthest((cellLo(ix) + cellHi(ix)) / 2, (cellLo(iy) + cellHi(iy)) / 2, (cellLo(iz) + cellHi(iz)) / 2) + half
        }
        return maxDistance(ix, iy, iz, s)
    }

    /**
     * How many of the dings that fit shown [spot] alone still point only at it: a ding that now also
     * fits another shown corpse's seen stand (pinned later, or an extra seen later) is explained by
     * that corpse instead. The talisman only measures unlooted corpses, so this is the dings' vote
     * that a corpse is there.
     */
    fun support(spot: CorpseSpot): Int {
        val dings = supportDings[spot] ?: return 0
        val others = shown().filter { it != spot && it in pins }
        return dings.count { d -> others.none { o -> fitsNow(d.ix, d.iy, d.iz, o, d.lo, d.hi) } }
    }

    private fun confirm(s: CorpseSpot) {
        confirmedSet += s
        if (s in spots) regions[s] = Region(s)
    }

    private fun fitsNow(ix: Int, iy: Int, iz: Int, s: CorpseSpot, lo: Double, hi: Double): Boolean {
        (pins[s] ?: learnedPoints[s])?.let { return cellPointMin(ix, iy, iz, it[0], it[1], it[2]) <= hi + EPS && cellPointMax(ix, iy, iz, it[0], it[1], it[2]) >= lo - EPS }
        regions[s]?.let { return it.fits(ix, iy, iz, lo, hi) }
        return fits(ix, iy, iz, s, lo, hi)
    }

    /**
     * The listed spot, extra or learned spot a stand at (x, y, z) is: the spot whose box (plus
     * [STAND_MARGIN]) holds it, nearest its box, else an extra or learned spot within [SAME_CORPSE];
     * null when none (a new corpse). Looted ones count: their stand stays in the world.
     */
    fun match(x: Double, y: Double, z: Double): CorpseSpot? =
        spots.filter { standOffset(it, x, y, z) != null }.minByOrNull { pointDistance(x, y, z, it) + norm(x - it.x, y - it.y, z - it.z) * 1e-3 }
            ?: (extraList + learnedPoints.keys).distinct()
                .mapNotNull { e -> (pins[e] ?: learnedPoints[e])?.let { e to norm(x - it[0], y - it[1], z - it[2]) } }
                .filter { it.second <= SAME_CORPSE }
                .minByOrNull { it.second }?.first

    /**
     * A corpse stand at (x, y, z) is known for certain (seen with line of sight, or seen by a party
     * member): it is located at once and its position pinned ([own] false: a pin the player already
     * has wins). Null when it is one the player looted (the stand outlives the loot), including a
     * corpse looted before it was located, recognized by an [unplacedLoots] entry of the same [type]
     * within [LOOT_RADIUS]. Otherwise the spot or extra it now is.
     */
    fun locate(x: Double, y: Double, z: Double, type: String? = null, own: Boolean = true): CorpseSpot? {
        val known = match(x, y, z)
        if (known != null && known in lootedSet) return null
        val key = known ?: CorpseSpot(floor(x).toInt(), floor(y).toInt(), floor(z).toInt())
        val at = doubleArrayOf(x, y, z)
        // A stand stands there: certain, whatever the dings or an earlier look said.
        emptySpots -= key
        dingPlaced -= key
        if (key !in confirmedSet) {
            val loot = unplacedLoots.firstOrNull { (p, t) -> norm(x - p[0], y - p[1], z - p[2]) <= LOOT_RADIUS && (t == null || type == null || t.equals(type, true)) }
            if (loot != null) {
                unplacedLoots.remove(loot)
                if (key !in spots && key !in extraList) extraList += key
                pins[key] = at
                lootedSet += key
                return null
            }
        }
        if (key !in spots && key !in extraList) extraList += key
        if (key !in confirmedSet) confirm(key)
        if (own || key !in pins) pins[key] = at
        return key
    }

    /** A party member located listed [spot]: shown like one of the player's own, unless the player looted it or saw it empty. */
    fun locateSpot(spot: CorpseSpot): Boolean {
        if (spot !in spots || spot in lootedSet || spot in emptySpots) return false
        if (spot !in confirmedSet) confirm(spot)
        dingPlaced -= spot
        return true
    }

    /** The stand of confirmed [spot] was seen at ([x], [y], [z]): from now on that is its position. */
    fun pin(spot: CorpseSpot, x: Double, y: Double, z: Double) {
        if (spot in confirmedSet) pins[spot] = doubleArrayOf(x, y, z)
    }

    fun isPinned(spot: CorpseSpot): Boolean = spot in pins

    /** The data's fixed spots (the Vanguard shaft): certain without a ding or a sight. */
    fun isFixed(spot: CorpseSpot): Boolean = spot in fixedSpots

    /** A party member's position for [spot]: drawn only while the player has neither seen nor narrowed it to [TIGHT]. */
    fun hint(spot: CorpseSpot, x: Double, y: Double, z: Double) {
        if (spot in confirmedSet) hints[spot] = doubleArrayOf(x, y, z)
    }

    /** Where to draw a shown corpse's stand: seen, else the ding estimate once tight, else a party member's; null = the listed block. */
    fun position(spot: CorpseSpot): DoubleArray? = pins[spot] ?: ownEstimate(spot) ?: hints[spot]

    /** The player's own position for [spot]: seen, or the dings' estimate once tight; null otherwise. */
    fun ownEstimate(spot: CorpseSpot): DoubleArray? = pins[spot] ?: regions[spot]?.takeIf { it.spread() <= TIGHT }?.centroid()

    /** Best guess of the stand (seen, else the region's centroid, else the learned position, else the box center), for ranking candidates. */
    fun center(spot: CorpseSpot): DoubleArray =
        pins[spot] ?: regions[spot]?.centroid() ?: learnedPoints[spot] ?: doubleArrayOf(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble())

    /** Radius of the region the dings left for [spot] (0 once seen); null when not confirmed. */
    fun spread(spot: CorpseSpot): Double? = if (spot in pins) 0.0 else regions[spot]?.spread()

    /** Distance from (x, y, z) to the nearest stand position still possible for [spot] (tests and loot). */
    fun possibleDistance(spot: CorpseSpot, x: Double, y: Double, z: Double): Double =
        (pins[spot] ?: learnedPoints[spot])?.let { norm(x - it[0], y - it[1], z - it[2]) } ?: regions[spot]?.nearest(x, y, z) ?: pointDistance(x, y, z, spot)

    /**
     * `<TYPE> CORPSE LOOT!` with the player at (px, py, pz). [aimed]: the corpse stand under the
     * crosshair then (the one just opened, seen by definition): that corpse is looted, located first
     * if it was not. Otherwise the candidate whose possible stand positions come within [LOOT_RADIUS]
     * (confirmed ones first, then the one whose best guess, [center], is nearest: the boxes of two
     * spots 6 apart both come within reach) becomes looted. Null when none is that close; the position
     * is kept so the stand, seen later, stays hidden.
     */
    fun loot(px: Double, py: Double, pz: Double, type: String? = null, aimed: DoubleArray? = null): CorpseSpot? {
        endRun()
        if (aimed != null) {
            val key = locate(aimed[0], aimed[1], aimed[2], type) ?: return null
            markLooted(key)
            return key
        }
        val near = candidates().map { it to possibleDistance(it, px, py, pz) }.filter { it.second <= LOOT_RADIUS }
        val spot = near.minWithOrNull(compareBy<Pair<CorpseSpot, Double>>({ it.first !in confirmedSet }, { c -> center(c.first).let { norm(px - it[0], py - it[1], pz - it[2]) } }))?.first
        if (spot == null) {
            unplacedLoots += doubleArrayOf(px, py, pz) to type
            return null
        }
        markLooted(spot)
        return spot
    }

    private fun markLooted(spot: CorpseSpot) {
        lootedSet += spot
        dingPlaced -= spot
        confirmedSet -= spot
        regions -= spot
        hints -= spot
        // An extra keeps its pin: its stand stays in the world and must not come back.
        if (spot in spots) pins -= spot
    }

    /** Corpses to draw: confirmed and not looted. */
    fun shown(): List<CorpseSpot> = confirmedSet.filter { it !in lootedSet }

    /**
     * The stand positions of one confirmed spot still consistent with every ding attributed to it alone:
     * a grid over its box ([STEP]), each point standing for the cube around it (slack [SLACK]) so the
     * point nearest the true stand is never cut. Work per ding is bounded by the grid ([N]^3 points,
     * less once the alive bounds shrink). A ding that would cut every point is ignored.
     */
    private class Region(spot: CorpseSpot) {
        private val xs = DoubleArray(N) { spot.x - SPREAD + it * STEP }
        private val ys = DoubleArray(N) { spot.y - SPREAD + it * STEP }
        private val zs = DoubleArray(N) { spot.z - SPREAD + it * STEP }
        private val alive = BooleanArray(N * N * N) { true }
        private val min = intArrayOf(0, 0, 0)
        private val max = intArrayOf(N - 1, N - 1, N - 1)
        private var centroid: DoubleArray? = null
        private var spread = -1.0

        private inline fun forAlive(action: (Int, Double, Double, Double) -> Boolean) {
            for (i in min[0]..max[0]) for (j in min[1]..max[1]) for (k in min[2]..max[2]) {
                val idx = (i * N + j) * N + k
                if (alive[idx] && !action(idx, xs[i], ys[j], zs[k])) return
            }
        }

        private fun passes(ix: Int, iy: Int, iz: Int, x: Double, y: Double, z: Double, lo: Double, hi: Double): Boolean =
            cellPointMin(ix, iy, iz, x, y, z) - SLACK <= hi + EPS && cellPointMax(ix, iy, iz, x, y, z) + SLACK >= lo - EPS

        fun fits(ix: Int, iy: Int, iz: Int, lo: Double, hi: Double): Boolean {
            var any = false
            forAlive { _, x, y, z ->
                any = passes(ix, iy, iz, x, y, z, lo, hi)
                !any
            }
            return any
        }

        fun cut(ix: Int, iy: Int, iz: Int, lo: Double, hi: Double) {
            val drop = ArrayList<Int>()
            var keep = 0
            forAlive { idx, x, y, z ->
                if (passes(ix, iy, iz, x, y, z, lo, hi)) keep++ else drop += idx
                true
            }
            if (keep == 0 || drop.isEmpty()) return
            for (idx in drop) alive[idx] = false
            val lo3 = intArrayOf(N, N, N)
            val hi3 = intArrayOf(-1, -1, -1)
            forAlive { idx, _, _, _ ->
                val i = idx / (N * N)
                val j = idx / N % N
                val k = idx % N
                lo3[0] = minOf(lo3[0], i)
                lo3[1] = minOf(lo3[1], j)
                lo3[2] = minOf(lo3[2], k)
                hi3[0] = maxOf(hi3[0], i)
                hi3[1] = maxOf(hi3[1], j)
                hi3[2] = maxOf(hi3[2], k)
                true
            }
            lo3.copyInto(min)
            hi3.copyInto(max)
            centroid = null
            spread = -1.0
        }

        fun centroid(): DoubleArray {
            centroid?.let { return it }
            var sx = 0.0
            var sy = 0.0
            var sz = 0.0
            var n = 0
            forAlive { _, x, y, z ->
                sx += x
                sy += y
                sz += z
                n++
                true
            }
            return doubleArrayOf(sx / n, sy / n, sz / n).also { centroid = it }
        }

        /**
         * Farthest alive point from the centroid **horizontally**, plus the cube slack. Height stays
         * loose longer (the player dings from about one height) but the drawn box is two blocks tall.
         */
        fun spread(): Double {
            if (spread >= 0) return spread
            val c = centroid()
            var far = 0.0
            forAlive { _, x, _, z ->
                far = max(far, norm(x - c[0], 0.0, z - c[2]))
                true
            }
            spread = far + SLACK
            return spread
        }

        fun nearest(x: Double, y: Double, z: Double): Double {
            var best = Double.MAX_VALUE
            forAlive { _, px, py, pz ->
                best = minOf(best, norm(x - px, y - py, z - pz))
                true
            }
            return best
        }

        /** Farthest a stand still possible here can be from (x, y, z), each point counted as its cube ([SLACK]). */
        fun farthest(x: Double, y: Double, z: Double): Double {
            var best = 0.0
            forAlive { _, px, py, pz ->
                best = maxOf(best, norm(x - px, y - py, z - pz))
                true
            }
            return best + SLACK
        }
    }

    companion object {
        const val RANGE = 20.0

        /** Client position against the server's at a ding: within 0.9 over 1 s, up to 3 for one frame when falling (recording 02-55-36). */
        const val MOVE_MARGIN = 3.0
        const val MIN_BYTE = 63
        const val MAX_BYTE = 126

        /** Slack on the pitch interval for float rounding; the geometry is exact. */
        const val EPS = 0.02

        /**
         * Half-size of a spot's box: the data's spot is the stand position floored (AMBE_1, JADE_1),
         * rounded or ceiled (CITR_2: stand -117.5, 3.7, -168.5 listed as -117, 4, -168), so the stand
         * is within one block of it on every axis.
         */
        const val SPREAD = 1.0

        /** Region grid: 17 points per axis over the 2-block box. */
        const val STEP = 0.125
        private const val N = 17

        /** Half the diagonal of a grid cube: the point nearest the true stand is at most this far from it. */
        val SLACK = STEP / 2 * sqrt(3.0)

        /** A region whose points all lie this close to their centroid is drawn there. */
        const val TIGHT = 0.5

        /** How far outside its spot's box a seen stand may stand (entity positions sit on block edges). */
        const val STAND_MARGIN = 0.5

        /** Player feet to the looted stand's possible positions at the loot line; reach is ~4.5 from the eyes. */
        const val LOOT_RADIUS = 6.0

        /** A ding farther than this from every recent client position is not ours (party member, lag spike). */
        const val OWN_RADIUS = 1.5

        /** Two sightings of a corpse outside every box this close together are the same corpse. */
        const val SAME_CORPSE = 1.5

        /** Dings kept per spot as its [support]. */
        const val SUPPORT_MAX = 64

        /**
         * Whether to cancel this ding's playback: only one fully explained by already located,
         * unlooted corpses. A ding that could be a new corpse (fits an unconfirmed candidate), the one
         * that confirms, one that fits nothing and any ding while the locator is off stay audible.
         */
        fun mutes(fit: DingFit?, locatorOn: Boolean, muteOn: Boolean): Boolean = locatorOn && muteOn && fit != null && fit.explained

        /** `round(pitch * 63)` when the pitch is a talisman byte (n / 63 within float error), else null. */
        fun pitchByte(pitch: Float): Int? {
            val scaled = pitch * 63.0
            val n = scaled.roundToInt()
            if (abs(scaled - n) > 0.01 || n !in MIN_BYTE..MAX_BYTE) return null
            return n
        }

        /** What the server would send at distance [d]; null beyond the range. */
        fun byteFor(d: Double): Int? {
            if (d > RANGE) return null
            return floor(63 * (2 - (d / RANGE) * (d / RANGE))).toInt().coerceAtMost(MAX_BYTE)
        }

        /** The distance interval `(lo, hi]` a pitch byte stands for; null outside 63..126. */
        fun interval(n: Int): Pair<Double, Double>? {
            if (n !in MIN_BYTE..MAX_BYTE) return null
            val lo = RANGE * sqrt(max(0.0, 2 - (n + 1) / 63.0))
            val hi = RANGE * sqrt(max(0.0, 2 - n / 63.0))
            return lo to hi
        }

        fun fits(ix: Int, iy: Int, iz: Int, spot: CorpseSpot, lo: Double, hi: Double): Boolean =
            minDistance(ix, iy, iz, spot) <= hi + EPS && maxDistance(ix, iy, iz, spot) >= lo - EPS

        /**
         * The packet holds `(int) (pos * 8)`, truncated toward zero (vanilla's d2i; Hypixel's 135 dings
         * of the 2026-11-18 recording fit the stands only that way), so pos lies in [cellLo, cellHi].
         */
        fun cellLo(i: Int): Double = (if (i <= 0) i - 1 else i) / 8.0

        fun cellHi(i: Int): Double = (if (i >= 0) i + 1 else i) / 8.0

        /** Nearest distance between the packet's cell and the spot's box. */
        fun minDistance(ix: Int, iy: Int, iz: Int, spot: CorpseSpot): Double =
            norm(gap(cellLo(ix), cellHi(ix), spot.x), gap(cellLo(iy), cellHi(iy), spot.y), gap(cellLo(iz), cellHi(iz), spot.z))

        /** Farthest distance between the packet's cell and the spot's box. */
        fun maxDistance(ix: Int, iy: Int, iz: Int, spot: CorpseSpot): Double =
            norm(span(cellLo(ix), cellHi(ix), spot.x), span(cellLo(iy), cellHi(iy), spot.y), span(cellLo(iz), cellHi(iz), spot.z))

        /** Distance from a point to the spot's box. */
        fun pointDistance(px: Double, py: Double, pz: Double, spot: CorpseSpot): Double =
            norm(gap(px, px, spot.x), gap(py, py, spot.y), gap(pz, pz, spot.z))

        /** Distance from a seen stand at (x, y, z) to [spot]'s box (0 inside); null beyond [STAND_MARGIN]. */
        fun standOffset(spot: CorpseSpot, x: Double, y: Double, z: Double): Double? = pointDistance(x, y, z, spot).takeIf { it <= STAND_MARGIN }

        private fun cellPointMin(ix: Int, iy: Int, iz: Int, x: Double, y: Double, z: Double): Double =
            norm(gapTo(cellLo(ix), cellHi(ix), x), gapTo(cellLo(iy), cellHi(iy), y), gapTo(cellLo(iz), cellHi(iz), z))

        private fun cellPointMax(ix: Int, iy: Int, iz: Int, x: Double, y: Double, z: Double): Double =
            norm(max(abs(x - cellLo(ix)), abs(x - cellHi(ix))), max(abs(y - cellLo(iy)), abs(y - cellHi(iy))), max(abs(z - cellLo(iz)), abs(z - cellHi(iz))))

        private fun norm(dx: Double, dy: Double, dz: Double): Double = sqrt(dx * dx + dy * dy + dz * dz)

        private fun gapTo(a0: Double, a1: Double, v: Double): Double = max(0.0, max(v - a1, a0 - v))

        private fun gap(a0: Double, a1: Double, b: Int): Double = max(0.0, max(b - SPREAD - a1, a0 - (b + SPREAD)))

        private fun span(a0: Double, a1: Double, b: Int): Double = max(a1 - (b - SPREAD), (b + SPREAD) - a0)

        /** True when the ding at (sx, sy, sz) is within [OWN_RADIUS] of any of the client's recent positions. */
        fun isOwnDing(sx: Double, sy: Double, sz: Double, positions: Iterable<DoubleArray>): Boolean =
            positions.any { p ->
                val dx = p[0] - sx
                val dy = p[1] - sy
                val dz = p[2] - sz
                dx * dx + dy * dy + dz * dz <= OWN_RADIUS * OWN_RADIUS
            }
    }
}
