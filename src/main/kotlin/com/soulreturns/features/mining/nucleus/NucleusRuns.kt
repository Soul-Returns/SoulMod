package com.soulreturns.features.mining.nucleus

import com.soulreturns.api.HypixelModApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.data.skyblock.TabWidgets
import com.soulreturns.features.dev.DevState
import com.soulreturns.features.profit.dragon.DragonProfitAnnouncer
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import com.soulreturns.stats.TrackerSessions
import com.soulreturns.util.soulChat
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

/**
 * One scope's run counters (Session or Total). Item maps are keyed by the name Hypixel prints;
 * values are computed at render from prices, never stored.
 *
 * @property runTimes cycle times (loot bundle to loot bundle) of runs with a known start, oldest first,
 *   capped at [NucleusRuns.MAX_RUN_TIMES] for the median.
 * @property timeMs time in the Crystal Hollows while tracking: the per-hour denominator.
 * @property lootRuns per item, the number of runs whose bundle had it (drop rate = lootRuns / runs).
 * @property kit consumed kit items by name (Apparatus or its six parts, Jungle Key, the Goblin Egg kind spent; the Scavenged tools cost nothing).
 */
@Serializable
data class NucleusCounts(
    val runs: Long = 0L,
    val runTimes: List<Long> = emptyList(),
    val lastRunMs: Long = 0L,
    val bestRunMs: Long = 0L,
    val timeMs: Long = 0L,
    val loot: Map<String, Long> = emptyMap(),
    val lootRuns: Map<String, Long> = emptyMap(),
    val kit: Map<String, Long> = emptyMap(),
) {
    fun withLoot(items: List<NucleusLoot>): NucleusCounts {
        val amounts = HashMap(loot)
        val seen = HashMap(lootRuns)
        for (item in items) amounts[item.name] = (amounts[item.name] ?: 0L) + item.amount
        for (name in items.mapTo(HashSet()) { it.name }) seen[name] = (seen[name] ?: 0L) + 1
        return copy(runs = runs + 1, loot = amounts, lootRuns = seen)
    }

    fun withRunTime(ms: Long): NucleusCounts =
        copy(
            runTimes = (runTimes + ms).takeLast(NucleusRuns.MAX_RUN_TIMES),
            lastRunMs = ms,
            bestRunMs = if (bestRunMs == 0L) ms else minOf(bestRunMs, ms),
        )

    fun withKit(name: String): NucleusCounts = copy(kit = kit + (name to (kit[name] ?: 0L) + 1))

    fun withKit(names: Collection<String>): NucleusCounts = names.fold(this) { c, name -> c.withKit(name) }

    fun medianRunMs(): Long = NucleusRuns.median(runTimes)
}

enum class CrystalState { MISSING, FOUND, PLACED }

/**
 * One crystal of the run in progress.
 *
 * @property splitMs found this long after the run start; null when unknown (partial start, tab restore).
 * @property deltaMs this crystal's leg (previous crystal or run start -> found) minus the player's median
 *   leg for it, taken before this leg joined the history; null with fewer than [NucleusRuns.MIN_LEGS].
 */
@Serializable
data class CrystalProgress(
    val state: CrystalState = CrystalState.MISSING,
    val splitMs: Long? = null,
    val deltaMs: Long? = null,
)

/**
 * The run in progress.
 *
 * @property startClock [TrackerSessions.clock] at the run start (the previous loot bundle); null =
 *   unknown, e.g. the first run after entering the Crystal Hollows.
 * @property partial the start was only taken at the first crystal found, so this run's time is not
 *   recorded (it would understate the cycle).
 * @property foundCount the `(n/5)` of the last found header; [foundTotal] its denominator.
 * @property lastFoundClock clock of the last found crystal: where the next leg starts.
 */
@Serializable
data class NucleusRun(
    val startClock: Long? = null,
    val partial: Boolean = false,
    val foundCount: Int = 0,
    val foundTotal: Int = 5,
    val crystals: Map<String, CrystalProgress> = emptyMap(),
    val lastFoundClock: Long? = null,
) {
    fun count(state: CrystalState): Int = crystals.values.count { it.state == state }

    /** Every crystal found (placed ones included) and not all placed yet: time to go place. */
    fun readyToPlace(): Boolean {
        val placed = count(CrystalState.PLACED)
        val found = count(CrystalState.FOUND) + placed
        return found >= foundTotal && placed < found
    }

    fun with(crystal: String, progress: CrystalProgress): NucleusRun = copy(crystals = crystals + (crystal to progress))

    /** Time of the run a bundle at [now] ends; null when untimed (partial or unknown start). */
    fun runTimeAt(now: Long): Long? = startClock?.takeIf { !partial }?.let { now - it }

    /**
     * Another server (or a game restart): crystals persist on Hypixel so found/placed stay, but the
     * time spent is not run time. The run keeps counting, untimed, with no leg across the change.
     */
    fun afterServerChange(): NucleusRun = when {
        crystals.isEmpty() -> NucleusRun()
        else -> copy(partial = true, lastFoundClock = null)
    }
}

/**
 * The `nucleus` stats namespace, per SkyBlock profile. Session persisted under the shared
 * tracker-session lifetime ([TrackerSessions]).
 *
 * @property legs per crystal (and [NucleusRuns.PLACE_LEG]), the player's last [NucleusRuns.MAX_LEGS]
 *   legs in ms, for the checklist's deltas. Not per session.
 */
@Serializable
data class NucleusStats(
    val session: NucleusCounts = NucleusCounts(),
    val total: NucleusCounts = NucleusCounts(),
    val sessionStarted: Boolean = false,
    val run: NucleusRun = NucleusRun(),
    val legs: Map<String, List<Long>> = emptyMap(),
)

/**
 * Crystal Nucleus run data: parses the run's chat ([NucleusChatParser]) in the Crystal Hollows and
 * keeps the run in progress (crystal checklist, splits), run times, loot and kit use in the `nucleus`
 * namespace. The loot bundle is the run boundary.
 */
object NucleusRuns {
    const val NAMESPACE = "nucleus"
    const val AREA = "Crystal Hollows"
    const val MAX_RUN_TIMES = 1000
    const val MAX_LEGS = 50
    const val MIN_LEGS = 3

    /** [NucleusStats.legs] key for last crystal found -> loot bundle (walking back and placing). */
    const val PLACE_LEG = "Place"

    private val tabRestore = NucleusTabRestore()

    val handle: StatsHandle<NucleusStats> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = NucleusStats.serializer(),
            default = { NucleusStats() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    private val parser = NucleusChatParser()
    private var lastTickMs = 0L
    private var lastEventMs = 0L
    private var tick = 0

    /** Hollows time not yet written to the stats (flushed once a second). */
    private var pendingMs = 0L

    /** Rare-drop lines from the last bundle, sent on the next tick. */
    private val pendingAlerts = ArrayList<String>()

    fun register() {
        TrackerSessions.resetOnFreshStart(handle) { withoutSession() }
        TrackerSessions.onStamp { flushTime() }
        Events.subscribe<ChatMessage> { onChat(it.raw) }
        Events.subscribe<AreaChanged> { onAreaChanged(it) }
        Events.subscribe<HypixelModApi.LocationChangeEvent> { onServer(it.serverName) }
        ClientTickEvents.END_CLIENT_TICK.register { onTick() }
        DevState.register("nucleus") {
            val stats = handle.read()
            linkedMapOf(
                "in_hollows" to inHollows(),
                "tracker" to trackerOn(),
                "run" to stats.run,
                "run_elapsed_ms" to runElapsedMs(),
                "session_runs" to stats.session.runs,
                "total_runs" to stats.total.runs,
                "bal" to BalState.phase.name,
            )
        }
    }

    fun inHollows(): Boolean = SkyblockLocation.isInArea(AREA)

    fun trackerOn(): Boolean = cfg.mining.nucleus.tracker

    /** The run in progress is kept while any run feature is on. */
    private fun runOn(): Boolean = trackerOn() || cfg.mining.nucleus.checklist

    /** Session time including the not yet flushed part, for the panel clock. */
    fun sessionTimeMs(): Long = handle.read().session.timeMs + pendingMs

    fun totalTimeMs(): Long = handle.read().total.timeMs + pendingMs

    /** Run timer: time since the run start, or null when the start is unknown. */
    fun runElapsedMs(): Long? = handle.read().run.startClock?.let { TrackerSessions.clock() - it }

    /** Median of the player's legs for [key], or null with too few samples. */
    fun medianLeg(key: String): Long? = handle.read().legs[key]?.takeIf { it.size >= MIN_LEGS }?.let(::median)

    fun resetSession() {
        pendingMs = 0L
        handle.update { withoutSession() }
    }

    fun median(values: List<Long>): Long {
        if (values.isEmpty()) return 0L
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun NucleusStats.withoutSession(): NucleusStats = copy(session = NucleusCounts(), sessionStarted = false)

    private fun onChat(raw: String) {
        if (!inHollows()) return
        val event = parser.feed(raw) ?: return
        BalState.on(event)
        captureRun(event)
        if (event is NucleusEvent.LootBundle) lastBundleRunMs = null
        if (!runOn()) return
        val now = TrackerSessions.clock()
        lastEventMs = System.currentTimeMillis()
        if (event is NucleusEvent.LootBundle) {
            tabRestore.onBundle(lastEventMs)
            onLoot(event.items, now)
        } else if (event is NucleusEvent.CrystalFound) {
            handle.update { onFound(event, now) }
        } else if (event is NucleusEvent.CrystalPlaced) {
            handle.update {
                val old = run.crystals[event.crystal] ?: CrystalProgress()
                copy(run = run.with(event.crystal, old.copy(state = CrystalState.PLACED)))
            }
        } else if (event is NucleusEvent.KitUsed && trackerOn()) {
            // Which egg kind went to King Yolkar is only known from the inventory a few ticks later.
            if (event.name == NucleusLines.GOBLIN_EGG_ANY) GoblinEggs.onEggLine() else addKit(event.name)
        }
    }

    /** Capture-only run clock (contests); never touches [handle]. */
    private val captureClock = NucleusRunClock()

    /** Wall-clock start of the run in progress for contests (null = unknown), and whether it is untimed. Independent of the tracker toggles. */
    fun contestRunStart(): Long? = captureClock.startedAt

    fun contestRunPartial(): Boolean = captureClock.partial

    /** Publishes [NucleusRunFinished] for every bundle, independent of the tracker toggles. */
    private fun captureRun(event: NucleusEvent) {
        val now = System.currentTimeMillis()
        if (event is NucleusEvent.CrystalFound) {
            captureClock.onFound(event.index, now)
        } else if (event is NucleusEvent.LootBundle) {
            val timing = captureClock.onBundle(now)
            Events.publish(NucleusRunCapture.build(event.items, now, timing, SkyblockProfileState.profileId, NucleusItems::idFor))
        }
    }

    fun addKit(name: String) {
        handle.update { copy(sessionStarted = true, session = session.withKit(name), total = total.withKit(name)) }
    }

    private fun NucleusStats.onFound(event: NucleusEvent.CrystalFound, now: Long): NucleusStats {
        // A count that does not grow means the last bundle was missed: a new run, start unknown.
        val stale = event.index <= run.foundCount
        val current = if (stale) NucleusRun() else run
        val start = current.startClock
        val partial = current.partial || start == null
        val legFrom = current.lastFoundClock ?: start.takeUnless { partial }
        val leg = legFrom?.let { now - it }
        val history = legs[event.crystal].orEmpty()
        val delta = if (leg != null && history.size >= MIN_LEGS) leg - median(history) else null
        val progress = CrystalProgress(CrystalState.FOUND, splitMs = start?.takeUnless { partial }?.let { now - it }, deltaMs = delta)
        return copy(
            sessionStarted = sessionStarted || trackerOn(),
            run = current.copy(
                startClock = start ?: now,
                partial = partial,
                foundCount = event.index,
                foundTotal = event.total,
                lastFoundClock = now,
            ).with(event.crystal, progress),
            legs = if (leg != null) legs + (event.crystal to (history + leg).takeLast(MAX_LEGS)) else legs,
        )
    }

    /** Run time of the run the last bundle ended; null when its start was unknown (compact chat line). */
    @Volatile var lastBundleRunMs: Long? = null
        private set

    private fun onLoot(items: List<NucleusLoot>, now: Long) {
        val tracker = trackerOn()
        handle.update {
            val took = run.runTimeAt(now)
            lastBundleRunMs = took
            val placeLeg = run.lastFoundClock?.takeIf { run.foundCount >= run.foundTotal }?.let { now - it }
            var s = session
            var t = total
            if (tracker) {
                s = s.withLoot(items)
                t = t.withLoot(items)
                if (took != null && took > 0L) {
                    s = s.withRunTime(took)
                    t = t.withRunTime(took)
                }
            }
            val placeHistory = legs[PLACE_LEG].orEmpty()
            copy(
                sessionStarted = sessionStarted || tracker,
                session = s,
                total = t,
                run = NucleusRun(startClock = now),
                legs = if (placeLeg != null) legs + (PLACE_LEG to (placeHistory + placeLeg).takeLast(MAX_LEGS)) else legs,
            )
        }
        if (tracker) announceRareDrops(items)
    }

    private fun announceRareDrops(items: List<NucleusLoot>) {
        if (!cfg.mining.nucleus.rareDropAlert) return
        val threshold = rareThreshold()
        val mode = NucleusHudSettings.priceMode
        for (item in items) {
            val value = NucleusItems.unitValue(item.name, mode) * item.amount
            if (value < threshold || value <= 0L) continue
            val amount = if (item.amount > 1) " x${item.amount}" else ""
            pendingAlerts += "§6§lRARE NUCLEUS DROP! §r§5${item.name}$amount §6(${DragonProfitAnnouncer.formatCoins(value)})"
        }
    }

    /** The configured rare-drop threshold in coins. */
    fun rareThreshold(): Long = (cfg.mining.nucleus.rareDropThresholdM * 1_000_000.0).toLong()

    private var lastServer: String? = null

    /** A different server (first one after a launch included) makes the run in progress untimed. */
    private fun onServer(name: String) {
        if (name == lastServer) return
        lastServer = name
        tabRestore.onServerChange(System.currentTimeMillis())
        captureClock.onServerChange()
        if (handle.read().run.let { it.startClock != null || it.crystals.isNotEmpty() }) {
            handle.update { copy(run = run.afterServerChange()) }
        }
    }

    /** Leaving the Hollows between runs makes the next start unknown (time away is not run time). */
    private fun onAreaChanged(e: AreaChanged) {
        if (e.previous != AREA || e.current == AREA) return
        captureClock.onLeftHollows()
        flushTime()
        val run = handle.read().run
        if (run.crystals.isEmpty() && run.startClock != null) handle.update { copy(run = NucleusRun()) }
    }

    private fun onTick() {
        val now = System.currentTimeMillis()
        val elapsed = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceIn(0L, 1_000L)
        lastTickMs = now
        // Sent a tick after the bundle, so they land under its closing bar and footer.
        if (pendingAlerts.isNotEmpty()) {
            pendingAlerts.forEach(::soulChat)
            pendingAlerts.clear()
        }
        if (!inHollows()) return
        if (trackerOn()) GoblinEggs.tick(now)
        if (++tick % 20 == 0 && runOn()) restoreFromTab(now)
        if (!trackerOn() || !handle.read().sessionStarted) return
        pendingMs += elapsed
        if (pendingMs >= 1_000L) flushTime()
    }

    /**
     * The tab list's `Crystals:` widget (if the player shows it) is the source of truth for the states
     * ([NucleusTabRestore] decides what wins over chat); chat keeps the splits.
     */
    private fun restoreFromTab(now: Long) {
        val run = handle.read().run
        val changed = tabRestore.changes(
            NucleusTabCrystals.parse(TabWidgets.lastLines),
            run.crystals.mapValues { it.value.state },
            now,
            lastEventMs,
        )
        if (changed.isEmpty()) return
        handle.update {
            var r = this.run
            for ((name, state) in changed) {
                val old = r.crystals[name] ?: CrystalProgress()
                r = r.with(name, if (state == CrystalState.MISSING) CrystalProgress() else old.copy(state = state))
            }
            copy(run = r.copy(foundCount = r.count(CrystalState.FOUND) + r.count(CrystalState.PLACED)))
        }
    }

    private fun flushTime() {
        val add = pendingMs
        if (add <= 0L) return
        pendingMs = 0L
        handle.update { copy(session = session.copy(timeMs = session.timeMs + add), total = total.copy(timeMs = total.timeMs + add)) }
    }
}
