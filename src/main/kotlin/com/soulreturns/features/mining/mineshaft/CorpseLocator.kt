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
import com.soulreturns.util.MobSpotter
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundEvents
import net.minecraft.sounds.SoundSource
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import java.util.Locale

/**
 * Corpse locator (rule://mineshaft): decodes the Organ Donor Talisman's ding against the layout's
 * fixed corpse spots ([CorpseLocatorSolver]) and draws a waypoint on each confirmed spot.
 *
 * Locating uses only what the game tells the player: the harp, the `<TYPE> CORPSE LOOT!` line, the
 * tab's corpse rows and the sidebar layout id. Corpse armor stands are on the client through walls, so
 * an entity is read only to color an already located spot, and only once the player can see it
 * ([MobSpotter], ToS, rule://mineshaft).
 */
object CorpseLocator {
    private const val LINE_WIDTH = 3f

    /** About 1.5 s of client positions: the ding is the server's position, a ping or two behind ours. */
    private const val HISTORY_TICKS = 30
    private const val PENDING_MAX = 40

    /** Cadence of the line-of-sight type check and the key scan. */
    private const val SCAN_TICKS = 10
    private const val DIM_ALPHA = 0.3f
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

    /** Corpse type per spot, from the fixed layout or a stand seen with line of sight; sticky for the stay. */
    private val types = HashMap<CorpseSpot, String>()

    /** SkyBlock ids in the player's inventory, as of the last scan. */
    private var inventory: Set<String> = emptySet()

    /** Dings heard before the sidebar named the layout, replayed once it does. */
    private val pending = ArrayList<IntArray>()
    private val history = ArrayDeque<DoubleArray>()
    private var dings = 0
    private var muted = 0
    private var ticks = 0
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

    /**
     * Called from `ClientPacketListenerMixin` on both passes; work happens on the render-thread pass
     * only, which is also the pass that plays the sound. True = cancel playback (this ding only).
     */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket): Boolean {
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread) return false
        if (packet.source != SoundSource.BLOCKS) return false
        if (packet.sound.value().location() != SoundEvents.NOTE_BLOCK_HARP.value().location()) return false
        if (!active()) return false
        val n = CorpseLocatorSolver.pitchByte(packet.pitch) ?: return false
        val p = mc.player ?: return false
        if (!CorpseLocatorSolver.isOwnDing(packet.x, packet.y, packet.z, history + doubleArrayOf(p.x, p.y, p.z))) return false
        // Raw packet coordinates are position * 8 as ints (getX is int / 8.0, exact in a double).
        val ix = Math.round(packet.x * 8).toInt()
        val iy = Math.round(packet.y * 8).toInt()
        val iz = Math.round(packet.z * 8).toInt()
        dings++
        val s = solver
        if (s == null) {
            if (layout == null && pending.size < PENDING_MAX) pending += intArrayOf(ix, iy, iz, n)
            return false
        }
        val fit = s.ding(ix, iy, iz, n)
        val mute = !tabAllLooted() && CorpseLocatorSolver.mutes(fit, active(), cfg.mining.mineshaft.corpseLocatorMute)
        if (mute) muted++
        return mute
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
        if (++ticks % SCAN_TICKS != 0) return
        identify(s)
        inventory = buildSet {
            val inv = p.inventory
            for (i in 0 until inv.containerSize) SkyblockItemUtils.getSkyblockId(inv.getItem(i))?.let { add(it) }
        }
    }

    /**
     * Types a located spot from the stand standing in it, only once the player can see that stand.
     * The entity never places, confirms or counts anything; the spot was located from the ding.
     */
    private fun identify(s: CorpseLocatorSolver) {
        for (spot in s.shown()) {
            if (spot in types) continue
            val cell = AABB(spot.x.toDouble(), spot.y.toDouble(), spot.z.toDouble(), spot.x + 1.0, spot.y + 2.0, spot.z + 1.0)
            val stand =
                MobSpotter.findVisibleIn(cell) {
                    it is ArmorStand && !it.getItemBySlot(EquipmentSlot.HEAD).isEmpty &&
                        it.blockX == spot.x && it.blockZ == spot.z && it.blockY - spot.y in 0..1
                } ?: continue
            val type = CorpseLook.typeOf(SkyblockItemUtils.getSkyblockId((stand as ArmorStand).getItemBySlot(EquipmentSlot.HEAD)))
            types[spot] = type
            DebugLogger.logFeatureEvent("Corpse locator: saw $type corpse at $spot")
        }
    }

    private fun start(id: String) {
        layout = id
        val spots = data.spots(id)
        if (spots == null) {
            pending.clear()
            DebugLogger.logFeatureEvent("Corpse locator: no spot data for layout $id")
            return
        }
        val fixedType = data.fixed[id]
        if (fixedType != null) for (spot in spots) types[spot] = fixedType
        val s = CorpseLocatorSolver(spots, fixed = fixedType != null)
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
        types.clear()
        inventory = emptySet()
        pending.clear()
        history.clear()
        dings = 0
        muted = 0
        ticks = 0
        tabBaseline = -1
        tabHandled = -1
    }

    private fun render() {
        if (!active()) return
        val shown = solver?.shown() ?: return
        if (shown.isEmpty() || tabAllLooted()) return
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val m = cfg.mining.mineshaft
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (s in shown) {
                val look = CorpseLook.decide(types[s], inventory, m)
                val color = look.color.effective().let { c -> if (look.dimmed) TargetWaypoint.alpha(c, DIM_ALPHA) else c }
                // The stand's block and the one above: the corpse body stands about two blocks tall.
                val box = AABB(s.x.toDouble(), s.y.toDouble(), s.z.toDouble(), s.x + 1.0, s.y + 2.0, s.z + 1.0)
                val c = box.center
                val dist = Math.sqrt(p.distanceToSqr(c.x, s.y + 0.5, c.z))
                TargetWaypoint.draw(box, color, LINE_WIDTH, look.label + " " + String.format(Locale.ROOT, "%.1fm", dist), look.line)
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
            "spots" to s?.spots?.map(::xyz),
            "confirmed" to s?.confirmed?.map(::xyz),
            "looted" to s?.looted?.map(::xyz),
            "emptied" to s?.emptied?.map(::xyz),
            "shown" to s?.shown()?.map(::xyz),
            "types" to types.entries.associate { (k, v) -> "${k.x},${k.y},${k.z}" to v },
            "keys" to inventory.filter { it.endsWith("_KEY") },
            "dings" to dings,
            "dings_muted" to muted,
            "dings_audible" to dings - muted,
            "pending" to pending.size,
            "last_ding" to ding?.let {
                linkedMapOf(
                    "pos" to listOf(it.ix / 8.0, it.iy / 8.0, it.iz / 8.0),
                    "byte" to it.pitchByte,
                    "lo" to it.lo,
                    "hi" to it.hi,
                    "fitting" to it.fitting.map(::xyz),
                    "explained" to it.explained,
                )
            },
            "tab_all_looted" to tabAllLooted(),
        )
    }
}
