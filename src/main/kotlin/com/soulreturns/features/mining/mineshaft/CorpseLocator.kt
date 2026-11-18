package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.features.dev.DevState
import com.soulreturns.features.mining.TargetWaypoint
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.phys.AABB
import java.util.Locale

/**
 * Corpse locator (rule://mineshaft): decodes the Organ Donor Talisman's ding against the layout's
 * fixed corpse spots ([CorpseLocatorSolver]) and draws a waypoint on each confirmed spot.
 *
 * Inputs are only what the game tells the player: the harp, the `<TYPE> CORPSE LOOT!` line, the tab's
 * corpse rows and the sidebar layout id. Corpse armor stands are on the client through walls, so
 * nothing here may read an entity (ToS, rule://mineshaft).
 */
object CorpseLocator {
    private const val LABEL = "Corpse"
    private const val LINE_WIDTH = 3f

    /** About 1.5 s of client positions: the ding is the server's position, a ping or two behind ours. */
    private const val HISTORY_TICKS = 30
    private const val PENDING_MAX = 40
    private val LOOT = Regex("""^\s*(\w+) CORPSE LOOT!\s*$""")

    /** Spawn spots copied from meowdding-repo (MIT), keyed by sidebar layout id. */
    private val data: CorpseSpotData by lazy {
        try {
            val text = CorpseLocator::class.java.getResourceAsStream("/assets/soul/mineshaft/corpse_spots.json")!!.use { it.readBytes().decodeToString() }
            CorpseSpotData.parse(text)
        } catch (e: Exception) {
            DebugLogger.logFeatureEvent("Corpse locator: spot data unreadable: $e")
            CorpseSpotData()
        }
    }

    /** Layout id of this stay, once the sidebar showed it; null before. */
    var layout: String? = null
        private set

    /** Null while the layout is unknown or has no spot data. */
    var solver: CorpseLocatorSolver? = null
        private set
    private var fixedLabel: String? = null

    /** Dings heard before the sidebar named the layout, replayed once it does. */
    private val pending = ArrayList<IntArray>()
    private val history = ArrayDeque<DoubleArray>()
    private var dings = 0
    private var tabBaseline = -1
    private var tabHandled = -1

    fun register() {
        Events.subscribe<ChatMessage> { if (it.source == ChatMessage.Source.SERVER) onChat(it.raw) }
        Events.subscribe<AreaChanged> { reset() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
        DevState.register("corpse_locator") { state() }
    }

    fun enabled(): Boolean = cfg.mining.mineshaft.corpseLocator

    private fun active(): Boolean = enabled() && SkyblockLocation.isInArea(MineshaftState.AREA)

    /** Called from `ClientPacketListenerMixin` on both passes; work happens on the render-thread pass only. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) return
        if (packet.source != SoundSource.BLOCKS) return
        if (packet.sound.value().location() != SoundEvents.NOTE_BLOCK_HARP.value().location()) return
        if (!active()) return
        val n = CorpseLocatorSolver.pitchByte(packet.pitch) ?: return
        val p = mc.player ?: return
        if (!CorpseLocatorSolver.isOwnDing(packet.x, packet.y, packet.z, history + doubleArrayOf(p.x, p.y, p.z))) return
        // Raw packet coordinates are position * 8 as ints (getX is int / 8.0, exact in a double).
        val ix = Math.round(packet.x * 8).toInt()
        val iy = Math.round(packet.y * 8).toInt()
        val iz = Math.round(packet.z * 8).toInt()
        dings++
        val s = solver
        if (s != null) s.ding(ix, iy, iz, n)
        else if (layout == null && pending.size < PENDING_MAX) pending += intArrayOf(ix, iy, iz, n)
    }

    private fun onChat(raw: String) {
        val s = solver ?: return
        if (!active()) return
        val type = LOOT.matchEntire(MessageDetector.stripColorCodes(raw))?.groupValues?.get(1) ?: return
        val p = Minecraft.getInstance().player ?: return
        val spot = s.loot(p.x, p.y, p.z)
        DebugLogger.logFeatureEvent("Corpse locator: $type loot -> ${spot ?: "no spot within ${CorpseLocatorSolver.LOOT_RADIUS}"}")
    }

    /** From [MineshaftTick], inside the mineshaft area, after the visit tracker read the layout. */
    fun tick() {
        if (!enabled()) return
        val p = Minecraft.getInstance().player ?: return
        history.addLast(doubleArrayOf(p.x, p.y, p.z))
        while (history.size > HISTORY_TICKS) history.removeFirst()
        if (layout == null) MineshaftState.serverInstance?.let { start(it) }
        val s = solver ?: return
        crossCheckTab(s, p.x, p.y, p.z)
    }

    private fun start(id: String) {
        layout = id
        val spots = data.spots(id)
        if (spots == null) {
            pending.clear()
            DebugLogger.logFeatureEvent("Corpse locator: no spot data for layout $id")
            return
        }
        fixedLabel = data.fixed[id]
        val s = CorpseLocatorSolver(spots, fixed = fixedLabel != null)
        for (d in pending) s.ding(d[0], d[1], d[2], d[3])
        pending.clear()
        solver = s
    }

    /**
     * The tab's LOOTED count backs up the loot line: a rise the chat did not account for loots the
     * spot at the player now. Corpses looted before the first read stay unplaced (no position then).
     */
    private fun crossCheckTab(s: CorpseLocatorSolver, x: Double, y: Double, z: Double) {
        val counts = MineshaftCorpses.byType.values
        if (counts.isEmpty()) return
        val looted = counts.sumOf { it.looted }
        if (tabBaseline < 0) {
            tabBaseline = looted
            tabHandled = looted
            return
        }
        if (looted <= tabHandled) return
        tabHandled = looted
        if (looted - tabBaseline > s.looted.size) {
            val spot = s.loot(x, y, z)
            DebugLogger.logFeatureEvent("Corpse locator: tab LOOTED $looted without a loot line -> ${spot ?: "no spot near"}")
        }
    }

    /** The tab lists corpses and none is unlooted: nothing can be left to point at. */
    private fun tabAllLooted(): Boolean {
        val counts = MineshaftCorpses.byType.values
        return counts.isNotEmpty() && counts.sumOf { it.unlooted } == 0
    }

    private fun reset() {
        layout = null
        solver = null
        fixedLabel = null
        pending.clear()
        history.clear()
        dings = 0
        tabBaseline = -1
        tabHandled = -1
    }

    private fun render() {
        if (!active()) return
        val shown = solver?.shown() ?: return
        if (shown.isEmpty() || tabAllLooted()) return
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val color = cfg.mining.mineshaft.corpseLocatorColor.effective()
        val name = fixedLabel ?: LABEL
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (s in shown) {
                // The stand's block and the one above: the corpse body stands about two blocks tall.
                val box = AABB(s.x.toDouble(), s.y.toDouble(), s.z.toDouble(), s.x + 1.0, s.y + 2.0, s.z + 1.0)
                val c = box.center
                val dist = Math.sqrt(p.distanceToSqr(c.x, s.y + 0.5, c.z))
                TargetWaypoint.draw(box, color, LINE_WIDTH, name + " " + String.format(Locale.ROOT, "%.1fm", dist))
            }
        }
    }

    private fun state(): Map<String, Any?> {
        val s = solver
        fun xyz(c: CorpseSpot) = listOf(c.x, c.y, c.z)
        val ding = s?.lastDing
        return linkedMapOf(
            "enabled" to enabled(),
            "active" to active(),
            "layout" to layout,
            "has_data" to (s != null),
            "fixed_label" to fixedLabel,
            "spots" to s?.spots?.map(::xyz),
            "confirmed" to s?.confirmed?.map(::xyz),
            "looted" to s?.looted?.map(::xyz),
            "emptied" to s?.emptied?.map(::xyz),
            "shown" to s?.shown()?.map(::xyz),
            "dings" to dings,
            "pending" to pending.size,
            "last_ding" to ding?.let {
                linkedMapOf(
                    "pos" to listOf(it.ix / 8.0, it.iy / 8.0, it.iz / 8.0),
                    "byte" to it.pitchByte,
                    "lo" to it.lo,
                    "hi" to it.hi,
                    "fitting" to it.fitting.map(::xyz),
                )
            },
            "tab_all_looted" to tabAllLooted(),
        )
    }
}
