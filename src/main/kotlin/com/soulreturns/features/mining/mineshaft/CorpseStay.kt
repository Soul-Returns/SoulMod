package com.soulreturns.features.mining.mineshaft

/**
 * One stay's corpse locator state above the [solver] (pure, rule://mineshaft): seen stands placed on
 * spots, loot lines and the tab's LOOTED backup, party corpses, types, and what to draw ([shown]).
 * [CorpseLocator] feeds it from the game; replays feed it from recordings.
 */
class CorpseStay(val solver: CorpseLocatorSolver, private val log: (String) -> Unit = {}) {
    /** Types by source and who shared what. */
    val book = CorpseBook()

    /** Corpses whose stand the player saw (position and type from it); sticky for the stay. */
    val seen = HashSet<CorpseSpot>()

    /** Corpses the player located (a ding fit only it, or its stand was seen): what [ownCorpses] shares. */
    val own = LinkedHashSet<CorpseSpot>()

    /** Types named by this stay's loot lines. */
    val lootTypes = ArrayList<String>()

    private var tabBaseline = -1
    private var tabHandled = -1

    /** How often [shown] had to hold back corpses, and the last ones it held back (spot -> why). */
    var capEvents = 0
        private set
    var capped: Map<CorpseSpot, String> = emptyMap()
        private set

    fun typeOf(spot: CorpseSpot): String? = book.typeOf(spot)

    private var lastDingTick: Long? = null
    private val emptyLooks = HashMap<CorpseSpot, Int>()

    /**
     * One own ding at client [tick]. A pause over [RUN_PAUSE_TICKS] since the last one ends the
     * talisman's run (it may have gone silent beyond 20 and taken another corpse since).
     */
    fun ding(ix: Int, iy: Int, iz: Int, pitchByte: Int, tick: Long): DingFit? {
        lastDingTick?.let { if (tick - it > RUN_PAUSE_TICKS) solver.endRun() }
        lastDingTick = tick
        val fit = solver.ding(ix, iy, iz, pitchByte) ?: return null
        fit.withdrawn?.let {
            forget(it)
            log("Corpse locator: withdrew ${solver.lastWithdrawal}")
        }
        fit.placed?.let { log("Corpse locator: placed $it from the dings") }
        solver.runTarget?.let { own += it }
        return fit
    }

    /**
     * One sight scan: [lookedEmpty] = the ding-only corpses whose whole box the player saw from close
     * by with no corpse stand on it. One that stays so for [EMPTY_SCANS] scans in a row is withdrawn.
     */
    fun look(lookedEmpty: Set<CorpseSpot>) {
        emptyLooks.keys.retainAll(lookedEmpty)
        for (spot in lookedEmpty) {
            val n = (emptyLooks[spot] ?: 0) + 1
            if (n < EMPTY_SCANS) {
                emptyLooks[spot] = n
                continue
            }
            emptyLooks -= spot
            if (solver.seenEmpty(spot)) {
                forget(spot)
                log("Corpse locator: withdrew ${solver.lastWithdrawal}")
            }
        }
    }

    /** A withdrawn corpse: not the player's to share, and its type by elimination no longer holds. */
    private fun forget(spot: CorpseSpot) {
        own -= spot
        book.inferredTypes -= spot
    }

    /** The last loot line's attribution, for DevState. */
    var lastLoot: String? = null
        private set

    /** `<TYPE> CORPSE LOOT!` with the player at (x, y, z); [aimed]: the corpse stand under the crosshair then, if any. */
    fun loot(x: Double, y: Double, z: Double, type: String, aimed: DoubleArray? = null): CorpseSpot? {
        lootTypes += type
        val spot = solver.loot(x, y, z, type, aimed)
        val how = if (aimed != null) "the stand under the crosshair" else "guessed from the position"
        lastLoot = "$type -> ${spot ?: "no corpse within ${CorpseLocatorSolver.LOOT_RADIUS}, kept for its stand"} ($how)"
        log("Corpse locator: $type loot $lastLoot")
        return spot
    }

    /**
     * The tab's LOOTED count ([looted], all types) backs up the loot line: a rise the chat did not
     * account for loots the spot at the player (x, y, z) now. Corpses looted before the first read
     * stay unplaced (no position then).
     */
    fun tab(looted: Int, x: Double, y: Double, z: Double) {
        if (tabBaseline < 0) {
            tabBaseline = looted
            tabHandled = looted
            return
        }
        if (looted <= tabHandled) return
        tabHandled = looted
        if (looted - tabBaseline > solver.looted.size) {
            val spot = solver.loot(x, y, z)
            log("Corpse locator: tab LOOTED $looted without a loot line -> ${spot ?: "no spot near"}")
        }
    }

    /** A corpse stand the player saw: its spot, a learned spot or an extra outside every box. */
    fun see(x: Double, y: Double, z: Double, type: String) {
        place(solver.locate(x, y, z, type), type, x, y, z)
    }

    /**
     * A helmeted stand the player saw whose helmet no corpse wears: only types a corpse already
     * located there and not yet seen (mineshafts also hold decor stands). True when it did.
     */
    fun seeOther(x: Double, y: Double, z: Double): Boolean {
        val known = solver.match(x, y, z)
        if (known == null || known !in solver.confirmed || known in seen) return false
        place(solver.locate(x, y, z, CorpseLook.OTHER), CorpseLook.OTHER, x, y, z)
        return true
    }

    private fun place(spot: CorpseSpot?, type: String, x: Double, y: Double, z: Double) {
        if (spot == null || (spot in seen && book.seenTypes[spot] != CorpseLook.OTHER)) return
        seen += spot
        own += spot
        book.see(spot, type)?.let { log("Corpse locator: $spot seen as $type, was $it") }
        val learned = if (spot in solver.learnedSpots) " (learned spot)" else ""
        log("Corpse locator: saw $type corpse at ${"%.2f %.2f %.2f".format(java.util.Locale.ROOT, x, y, z)} -> $spot$learned")
    }

    /** Names located corpses without a type when the tab ([rows]: type -> total, LOOTED) leaves one type for them. */
    fun eliminate(rows: Map<String, Pair<Int, Int>>) {
        val shown = solver.shown()
        val unknown = shown.filter { typeOf(it) == null }
        if (unknown.isEmpty()) return
        val type = CorpseElimination.typeOfRest(rows, lootTypes, shown.mapNotNull { typeOf(it) }, unknown.size) ?: return
        for (spot in unknown) book.inferredTypes[spot] = type
        log("Corpse locator: $unknown are $type by elimination")
    }

    /** A party member's located corpses ([CorpseBook.receive]). */
    fun receive(from: String, corpses: List<SharedCorpse>) {
        book.receive(solver, from, corpses)
    }

    /** What the party share sends: the corpses the player located, looted ones included (looting is per player); a [fixed] shaft's own spot is never sent. */
    fun ownCorpses(fixed: Boolean): List<SharedCorpse> =
        own.filter { it !in solver.spots || !fixed }.map { spot ->
            SharedCorpse(spot.takeIf { it in solver.spots }, solver.ownEstimate(spot) ?: solver.center(spot), typeOf(spot))
        }

    /**
     * The corpses to draw: [CorpseLocatorSolver.shown], never more than the tab lists unlooted
     * ([unlooted]: type -> NOT LOOTED rows; null or empty = the tab has not listed corpses yet). Per
     * type first (corpses of a known type beyond that type's unlooted rows), then in total. What is
     * held back goes in this order: corpses only the dings point at, weakest [CorpseLocatorSolver.support]
     * first (a ding now also explained by a seen stand no longer counts, so a spot contradicted by a
     * seen corpse elsewhere goes first), then party corpses the player has not seen, then seen stands
     * with the least ding support (the talisman only measures unlooted corpses; a looted corpse's stand
     * stays in the world). Fixed spots are certain and kept. Ties: the later located goes.
     */
    fun shown(unlooted: Map<String, Int>?): List<CorpseSpot> {
        val all = solver.shown()
        if (unlooted.isNullOrEmpty()) return all.also { capped = emptyMap() }
        fun rows(type: String) = unlooted.entries.firstOrNull { it.key.equals(type, ignoreCase = true) }?.value ?: 0
        val byType = all.groupBy { typeOf(it)?.takeIf { t -> t != CorpseLook.OTHER } }
        val total = unlooted.values.sum()
        if (all.size <= total && byType.all { (type, spots) -> type == null || spots.size <= rows(type) }) return all.also { capped = emptyMap() }
        val held = LinkedHashMap<CorpseSpot, String>()
        val order = all.withIndex().sortedWith(compareBy({ rank(it.value) }, { -solver.support(it.value) }, { it.index })).map { it.value }
        for (type in byType.keys.filterNotNull()) {
            val n = rows(type)
            for (spot in order.filter { typeOf(it) == type }.drop(n)) held[spot] = "${describe(spot)}; the tab lists $n unlooted $type"
        }
        for (spot in order.filter { it !in held }.drop(total)) held[spot] = "${describe(spot)}; the tab lists $total unlooted"
        if (held.keys != capped.keys && held.isNotEmpty()) {
            capEvents++
            log("Corpse locator: ${all.size} located but the tab lists $unlooted unlooted; not drawing ${held.entries.joinToString { (k, v) -> "$k ($v)" }}")
        }
        capped = held
        return all.filter { it !in held }
    }

    /** Lower is more certain: fixed, seen, shared by the party, dings only. */
    private fun rank(spot: CorpseSpot): Int =
        if (solver.isFixed(spot)) {
            0
        } else if (spot in seen) {
            1
        } else if (spot in book.sharedBy) {
            2
        } else {
            3
        }

    private fun describe(spot: CorpseSpot): String {
        val support = solver.support(spot)
        return if (solver.isFixed(spot)) {
            "fixed spot"
        } else if (spot in seen) {
            "seen, $support ding(s) point at it"
        } else if (spot in book.sharedBy) {
            "from ${book.sharedBy[spot]}, not seen, $support ding(s)"
        } else {
            "dings only, $support ding(s) point only at it"
        }
    }

    companion object {
        /** Dings come every 10-15 ticks (recordings: 1,076 of 1,133 gaps under 25 ticks); a longer pause ends the run. */
        const val RUN_PAUSE_TICKS = 32

        /** Sight scans in a row that must see a ding-only spot empty before it is withdrawn. */
        const val EMPTY_SCANS = 2
    }
}
