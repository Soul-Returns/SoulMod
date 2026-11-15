package com.soulreturns.features.mining.metaldetector

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ActionBarMessage
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.dev.DevState
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.network.protocol.game.ClientboundBlockUpdatePacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.level.block.Blocks
import com.soulreturns.platform.concurrent.SoulScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.decoration.ArmorStand
import kotlin.math.abs

/**
 * Mines of Divan Metal Detector solver. Mechanics and pairing rules: rule://metaldetector.
 *
 * Inputs: the server plays `block.note_block.harp` (source BLOCKS) at the player's server-side
 * position, then sends `TREASURE: X.Xm` on the action bar. Each bar is paired with the latest harp
 * position; [MetalDetectorSolver] does the rest. The center comes from the keeper armor stands
 * ([MetalDetectorKeepers]): a `Keeper of <X>` name, or their layout while unnamed.
 */
object MetalDetector {
    const val AREA = "Crystal Hollows"
    const val SUBLOCATION = "Mines of Divan"
    const val DETECTOR_ID = "DWARVEN_METAL_DETECTOR"
    private const val PAIR_WINDOW_MS = 1_000L
    private const val FOUND_RADIUS = 8.0
    /** Keeper stands load from afar and names last only ~4 s near one, so look often until the center is known. */
    private const val SCAN_INTERVAL_TICKS = 5
    private val TREASURE = Regex("""TREASURE: (\d+(?:\.\d+)?)m""")
    private val FOUND = Regex("""^You found .+ with your Metal Detector!$""")

    /** Mines of Divan center, from the keepers; null until found in this world, then kept until world change. */
    var center: IntArray? = null
        private set

    /** This round's samples, oldest first. */
    val samples = ArrayList<DetectorSample>()

    /** Best answer over the 42-spot list (+ learned spots). */
    var listSolution: DetectorSolution? = null
        private set

    /** Center-free search (absolute positions), run off-thread only while the center is unknown. */
    var freeSolution: DetectorSolution? = null
        private set
    var freeTookMs = 0L
        private set
    private var freeRunning = false
    private var freeDirty = false
    private var roundId = 0

    /** Last chest this round solved to (absolute); kept until another one is solved by the rules. */
    private var stickyChest: IntArray? = null

    /**
     * With a center, the list solve (the list is trusted). Without one, the center-free search, as a
     * silent fallback: only its solved chest, never its possibles (neighbour spots on one shell). Offsets
     * are relative to [solutionBase].
     */
    private fun currentSolution(): DetectorSolution? {
        if (center != null) return listSolution
        return freeSolution?.let { if (it.solved != null) it else it.copy(possible = emptyList()) }
    }

    /** What is shown: [currentSolution], but a solved round never falls back to "possible". */
    val solution: DetectorSolution?
        get() {
            val current = currentSolution() ?: return null
            val kept = stickyChest?.let { val b = solutionBase(); ChestOffset(it[0] - b[0], it[1] - b[1], it[2] - b[2]) }
            return MetalDetectorSolver.keepSolved(kept, current)
        }

    /** Which path produced [solution]: `list`, `no-center search`, `kept`, or null while unsolved. */
    val solvedBy: String?
        get() {
            val s = currentSolution() ?: return null
            if (s.solved == null) return if (stickyChest != null) "kept (earlier solve)" else null
            return if (center == null) "no-center search" else "list"
        }

    /** How the center was found: `name` or `layout`; null while unknown. */
    var centerSource: String? = null
        private set

    private fun rememberSolved() {
        val solved = currentSolution()?.solved ?: return
        val b = solutionBase()
        stickyChest = intArrayOf(b[0] + solved.x, b[1] + solved.y, b[2] + solved.z)
    }

    /** Chest `block_update` positions (packed) -> last seen ms. */
    private val recentChests = HashMap<Long, Long>()
    private val ORIGIN = intArrayOf(0, 0, 0)
    private const val CHEST_MEMORY_MS = 120_000L

    private var pending: DetectorSample? = null
    private var pendingAtMs = 0L
    private var freshHarp = false
    private var lastShown: Double? = null
    private var tick = 0

    fun register() {
        Events.subscribe<ActionBarMessage> { onActionBar(it.text) }
        Events.subscribe<ChatMessage> { onChat(it.raw) }
        Events.subscribe<AreaChanged> { resetAll() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> resetAll() }
        ClientTickEvents.END_CLIENT_TICK.register { onTick(it) }
        LevelRenderEvents.BEFORE_GIZMOS.register { MetalDetectorWaypoints.render() }
        DevState.register("metal_detector") { MetalDetectorDebug.state() }
    }

    /** Built-in spots plus learned ones. The next chest can land on any of them, the one just dug up included. */
    fun candidates(): List<ChestOffset> = MetalDetectorSolver.OFFSETS + MetalDetectorSpots.extras()

    /**
     * In the Mines of Divan: the sidebar sublocation, or (once the center is known) a box around the
     * center (every chest spot lies within 44 blocks horizontally), so digging the gold floor down into
     * the area Hypixel already calls Magma Fields still counts. The sidebar always wins, so a wrong
     * center can never switch the solver off inside the mines.
     */
    fun isActive(): Boolean {
        if (!cfg.mining.metalDetector.enabled) return false
        if (SkyblockLocation.isInSubLocation(SUBLOCATION)) return true
        val c = center ?: return false
        val p = Minecraft.getInstance().player ?: return false
        if (!SkyblockLocation.isInArea(AREA)) return false
        return abs(p.x - c[0]) <= ZONE_HALF_WIDTH && abs(p.z - c[2]) <= ZONE_HALF_WIDTH &&
            p.y >= c[1] - ZONE_BELOW && p.y <= c[1] + ZONE_ABOVE
    }

    /** Readings were taken up to 49.4 blocks out (2026-11-08 capture); a few blocks of margin beyond that. */
    private const val ZONE_HALF_WIDTH = 56.0
    private const val ZONE_BELOW = 45.0
    private const val ZONE_ABOVE = 20.0
    private var wasActive = false

    private fun holdingDetector(): Boolean =
        SkyblockItemUtils.getSkyblockId(Minecraft.getInstance().player?.mainHandItem) == DETECTOR_ID

    /** Called from `ClientPacketListenerMixin` on both passes; work happens on the render-thread pass only. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!Minecraft.getInstance().isSameThread) return
        if (packet.source != SoundSource.BLOCKS) return
        if (packet.sound.value().location() != SoundEvents.NOTE_BLOCK_HARP.value().location()) return
        if (!isActive() || !holdingDetector()) return
        // Raw packet coordinates are position * 8 as ints (getX is int / 8.0, exact in a double).
        val player = Minecraft.getInstance().player?.let { doubleArrayOf(it.xLast, it.yLast, it.zLast) }
        pending = MetalDetectorSolver.harpSample(Math.round(packet.x * 8).toInt(), Math.round(packet.y * 8).toInt(), Math.round(packet.z * 8).toInt(), player)
        pendingAtMs = System.currentTimeMillis()
        freshHarp = true
    }

    private fun onActionBar(text: String) {
        val harp = pending ?: return
        if (!isActive()) return
        val shown = TREASURE.find(MessageDetector.stripColorCodes(text))?.groupValues?.get(1)?.toDoubleOrNull() ?: return
        if (System.currentTimeMillis() - pendingAtMs > PAIR_WINDOW_MS) return
        if (!freshHarp && shown == lastShown) return
        freshHarp = false
        lastShown = shown
        samples += harp.copy(shown = shown)
        resolve()
    }

    private fun onChat(raw: String) {
        if (!FOUND.matches(MessageDetector.stripColorCodes(raw).trim())) return
        foundChest()
        resetRound()
    }

    /**
     * Records the chest just dug up as a learned spot (needs the center): the server's chest
     * `block_update` near the player confirms it; else the solved chest.
     */
    private fun foundChest() {
        val c = center ?: return
        val p = Minecraft.getInstance().player ?: return
        val now = System.currentTimeMillis()
        recentChests.entries.removeIf { now - it.value > CHEST_MEMORY_MS }
        val confirmed =
            recentChests.keys
                .map { BlockPos.of(it) }
                .filter { it.y - c[1] in MetalDetectorSolver.CHEST_Y_MIN..MetalDetectorSolver.CHEST_Y_MAX }
                .map { it to MetalDetectorSolver.distance(p.x, p.y, p.z, it.x, it.y, it.z) }
                .filter { it.second <= FOUND_RADIUS }
                .minByOrNull { it.second }
                ?.first
        val found = confirmed?.let { ChestOffset(it.x - c[0], it.y - c[1], it.z - c[2]) } ?: solution?.solved ?: return
        MetalDetectorSpots.record(found)
    }

    /** Chest block updates near the treasure arrive dozens of times while digging; only used to confirm the found spot. */
    @JvmStatic
    fun onBlockUpdate(packet: ClientboundBlockUpdatePacket) {
        if (!Minecraft.getInstance().isSameThread) return
        if (!packet.blockState.`is`(Blocks.CHEST) || !isActive()) return
        recentChests[packet.pos.asLong()] = System.currentTimeMillis()
    }

    /** Offsets in [solution] are relative to this: the center, or 0 for a center-free (absolute) result. */
    fun solutionBase(): IntArray = center ?: ORIGIN

    private fun resolve() {
        val c = center
        if (c == null) {
            listSolution = null
            if (samples.isNotEmpty()) scheduleFreeSearch()
            return
        }
        listSolution = MetalDetectorSolver.solve(c[0], c[1], c[2], samples, candidates())
        rememberSolved()
    }

    /**
     * Center-free search, one in flight; samples that arrive meanwhile trigger one more run when it
     * lands. A result that lands after the center was found is dropped (the list takes over).
     */
    private fun scheduleFreeSearch() {
        if (freeRunning) {
            freeDirty = true
            return
        }
        freeRunning = true
        val snapshot = samples.toList()
        val round = roundId
        val mc = Minecraft.getInstance()
        SoulScope.io.launch(Dispatchers.Default) {
            val started = System.nanoTime()
            val result = MetalDetectorSolver.freeSearchNoCenter(snapshot)
            val tookMs = (System.nanoTime() - started) / 1_000_000
            mc.execute {
                freeRunning = false
                if (round != roundId || center != null) return@execute
                freeSolution = result
                freeTookMs = tookMs
                rememberSolved()
                if (freeDirty) {
                    freeDirty = false
                    resolve()
                }
            }
        }
    }

    private fun resetRound() {
        samples.clear()
        listSolution = null
        freeSolution = null
        stickyChest = null
        freeDirty = false
        roundId++
        pending = null
        freshHarp = false
        lastShown = null
    }

    private fun resetAll() {
        resetRound()
        center = null
        centerSource = null
        recentChests.clear()
    }

    private fun onTick(mc: Minecraft) {
        if (++tick % SCAN_INTERVAL_TICKS != 0) return
        // Leaving the Mines of Divan ends the search: the waypoint must not wait there for a next visit.
        val active = isActive()
        if (wasActive && !active) resetRound()
        wasActive = active
        if (!SkyblockLocation.isInArea(AREA)) return
        if (cfg.mining.metalDetector.enabled && center == null) findCenter(mc)
    }

    private fun findCenter(mc: Minecraft) {
        val level = mc.level ?: return
        val stands = ArrayList<DoubleArray>()
        for (e in level.entitiesForRendering()) {
            if (e !is ArmorStand) continue
            val name = e.customName?.string?.let { MessageDetector.stripColorCodes(it).trim() }
            if (name != null && name.startsWith("Keeper of ")) {
                val c = MetalDetectorKeepers.centerFromNamed(name.removePrefix("Keeper of "), e.x, e.y, e.z) ?: continue
                return useCenter(c, "name")
            }
            stands += doubleArrayOf(e.x, e.y, e.z)
        }
        MetalDetectorKeepers.centerFromLayout(stands)?.let { useCenter(it, "layout") }
    }

    private fun useCenter(c: IntArray, source: String) {
        center = c
        centerSource = source
        // A center-free result is in absolute coordinates; the list takes over.
        freeSolution = null
        resolve()
    }
}
