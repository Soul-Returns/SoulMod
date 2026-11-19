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
import net.minecraft.world.phys.EntityHitResult
import java.util.Locale

/**
 * Corpse locator (rule://mineshaft): decodes the Organ Donor Talisman's ding against the layout's
 * corpse spots ([CorpseLocatorSolver]), places every corpse stand the player saw ([CorpseSightings]),
 * names corpses by elimination against the tab ([CorpseElimination]) and shares located corpses with
 * the party ([CorpseShare]); draws a waypoint on each located corpse.
 *
 * Corpse armor stands are on the client through walls, so an entity is read only once the player
 * can see it (the scan in [CorpseSightings], ToS, rule://mineshaft).
 */
object CorpseLocator {
    private const val LINE_WIDTH = 3f

    /** About 1.5 s of client positions: the ding is the server's position, a ping or two behind ours. */
    private const val HISTORY_TICKS = 30
    private const val PENDING_MAX = 40

    /** Cadence of the elimination and the key scan. */
    private const val SCAN_TICKS = 10

    private const val DIM_ALPHA = 0.3f
    private val LOOT = Regex("""^\s*(\w+) CORPSE LOOT!\s*$""")

    /** Spawn spots copied from meowdding-repo (MIT), keyed by sidebar layout id; also read by [CorpseSpotMemory]. */
    val data: CorpseSpotData by lazy {
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

    /** How spots are known for this stay's layout, and why ([state]): `waiting`, `listed`, `crystal`, `learned` or `sight-only`. */
    private var mode = "waiting"
    private var modeReason = "the sidebar has not named the layout yet"

    /** Null while the layout is unknown; a layout without spot data gets one with no spots (sight and party only). */
    var stay: CorpseStay? = null
        private set

    /** How many of this visit's [CorpseSightings.corpses] were placed already. */
    private var sightingsHandled = 0

    /** SkyBlock ids in the player's inventory, as of the last scan. */
    private var inventory: Set<String> = emptySet()

    /** Dings heard before the sidebar named the layout, replayed once it does. */
    private val pending = ArrayList<IntArray>()
    private val history = ArrayDeque<DoubleArray>()
    private var dings = 0
    private var muted = 0
    private var ticks = 0

    fun register() {
        Events.subscribe<ChatMessage> { if (it.source == ChatMessage.Source.SERVER) onChat(it.raw) }
        Events.subscribe<AreaChanged> { reset() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
        DevState.register("corpse_locator") { state() }
        CorpseShare.register()
    }

    fun enabled(): Boolean = cfg.mining.mineshaft.corpseLocator

    private fun active(): Boolean = enabled() && SkyblockLocation.isInArea(MineshaftState.AREA)

    fun typeOf(spot: CorpseSpot): String? = stay?.typeOf(spot)

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
        val st = stay
        if (st == null) {
            if (layout == null && pending.size < PENDING_MAX) pending += intArrayOf(ix, iy, iz, n)
            return false
        }
        val fit = st.ding(ix, iy, iz, n)
        val mute = !tabAllLooted() && CorpseLocatorSolver.mutes(fit, active(), cfg.mining.mineshaft.corpseLocatorMute)
        if (mute) muted++
        return mute
    }

    private fun onChat(raw: String) {
        val st = stay ?: return
        if (!active()) return
        val type = LOOT.matchEntire(MessageDetector.stripColorCodes(raw))?.groupValues?.get(1) ?: return
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        // The corpse just opened is the one under the crosshair (in sight by definition), if its helmet says that type.
        val aimed = ((mc.hitResult as? EntityHitResult)?.entity as? ArmorStand)
            ?.takeIf { CorpseLook.typeOf(SkyblockItemUtils.getSkyblockId(it.getItemBySlot(EquipmentSlot.HEAD))).equals(type, ignoreCase = true) }
        st.loot(p.x, p.y, p.z, type, aimed?.let { doubleArrayOf(it.x, it.y, it.z) })
    }

    /**
     * From [MineshaftTick], inside the mineshaft area, after the visit tracker read the layout and
     * the sight scan ran; [others] = the non-corpse helmeted stands in sight this scan (null on other ticks).
     */
    fun tick(others: List<OtherStand>?) {
        if (!enabled()) return
        val p = Minecraft.getInstance().player ?: return
        history.addLast(doubleArrayOf(p.x, p.y, p.z))
        while (history.size > HISTORY_TICKS) history.removeFirst()
        if (layout == null) MineshaftState.serverInstance?.let { start(it) }
        val st = stay ?: return
        val counts = MineshaftCorpses.byType.values
        if (counts.isNotEmpty()) st.tab(counts.sumOf { it.looted }, p.x, p.y, p.z)
        placeSightings(st, others.orEmpty())
        if (++ticks % SCAN_TICKS == 0) {
            st.eliminate(MineshaftCorpses.byType.mapValues { (_, c) -> c.total to c.looted })
            inventory = buildSet {
                val inv = p.inventory
                for (i in 0 until inv.containerSize) SkyblockItemUtils.getSkyblockId(inv.getItem(i))?.let { add(it) }
            }
        }
        CorpseShare.tick(st.own.map { Triple(it, st.typeOf(it), st.solver.ownEstimate(it) != null) })
    }

    /** Places the corpse stands seen since the last call, and the decor-helmet stands in sight this scan. */
    private fun placeSightings(st: CorpseStay, others: List<OtherStand>) {
        val seenNow = CorpseSightings.corpses
        while (sightingsHandled < seenNow.size) {
            val c = seenNow[sightingsHandled++]
            st.see(c.x, c.y, c.z, c.type)
        }
        for (o in others) if (st.seeOther(o.x, o.y, o.z)) CorpseSightings.resolve(o.entityId)
    }

    /** A party member's located corpses ([CorpseBook.receive]). Client thread. */
    fun receive(from: String, corpses: List<SharedCorpse>) {
        stay?.receive(from, corpses)
    }

    /** What [CorpseShare] sends: the corpses the player located, looted ones included (looting is per player). */
    fun ownCorpses(): List<SharedCorpse> = stay?.ownCorpses(data.fixed[layout] != null).orEmpty()

    private fun start(id: String) {
        layout = id
        val spots = data.spots(id)
        val learned = CorpseSpotMemory.forLayout(id)
        val fixedType = data.fixed[id]
        val s = CorpseLocatorSolver(spots ?: emptyList(), fixed = fixedType != null, learned = learned)
        val st = CorpseStay(s) { DebugLogger.logFeatureEvent(it) }
        val (m, why) = describe(id, spots != null, s.learnedSpots.size)
        mode = m
        modeReason = why
        DebugLogger.logFeatureEvent("Corpse locator: layout $id, $m: $why")
        if (fixedType != null) for (spot in s.spots) st.book.seenTypes[spot] = fixedType
        for (d in pending) st.ding(d[0], d[1], d[2], d[3])
        pending.clear()
        stay = st
        CorpseShare.onStay(id)
    }

    /** The debug `mode` and its reason for a layout. */
    private fun describe(id: String, listed: Boolean, learned: Int): Pair<String, String> {
        val extra = if (learned > 0) ", plus $learned learned spot(s) confirmed by sight only" else ""
        return when {
            listed && CorpseSpotData.isCrystal(id) && id !in data.layouts -> "crystal" to "the shared crystal spots$extra"
            listed -> "listed" to "bundled spots for $id$extra"
            learned > 0 -> "learned" to "no bundled spots for $id; $learned learned spot(s), confirmed by sight only; dings confirm nothing"
            else -> "sight-only" to "no spot data for $id: corpses are located by sight or from the party; dings confirm nothing"
        }
    }

    /** NOT LOOTED rows per type in the tab (high-water this visit); empty before it listed corpses. */
    private fun unlootedByType(): Map<String, Int> = MineshaftCorpses.byType.mapValues { it.value.unlooted }

    /** The tab lists corpses and none is unlooted: nothing can be left to point at. */
    private fun tabAllLooted(): Boolean {
        val counts = MineshaftCorpses.byType.values
        return counts.isNotEmpty() && counts.sumOf { it.unlooted } == 0
    }

    private fun reset() {
        layout = null
        stay = null
        mode = "waiting"
        modeReason = "the sidebar has not named the layout yet"
        sightingsHandled = 0
        inventory = emptySet()
        pending.clear()
        history.clear()
        dings = 0
        muted = 0
        ticks = 0
        CorpseShare.reset()
    }

    private fun render() {
        if (!active()) return
        val st = stay ?: return
        val sv = st.solver
        val shown = st.shown(unlootedByType())
        if (shown.isEmpty() || tabAllLooted()) return
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val m = cfg.mining.mineshaft
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (s in shown) {
                val look = CorpseLook.decide(st.typeOf(s), inventory, m)
                val color = look.color.effective().let { c -> if (look.dimmed) TargetWaypoint.alpha(c, DIM_ALPHA) else c }
                // The corpse body stands about two blocks tall: around the seen/estimated stand, else the listed block.
                val at = sv.position(s)
                val box =
                    if (at != null) {
                        AABB(at[0] - 0.5, at[1], at[2] - 0.5, at[0] + 0.5, at[1] + 2.0, at[2] + 0.5)
                    } else {
                        AABB(s.x.toDouble(), s.y.toDouble(), s.z.toDouble(), s.x + 1.0, s.y + 2.0, s.z + 1.0)
                    }
                val c = box.center
                val dist = Math.sqrt(p.distanceToSqr(c.x, box.minY + 0.5, c.z))
                TargetWaypoint.draw(box, color, LINE_WIDTH, look.label + " " + String.format(Locale.ROOT, "%.1fm", dist), look.line)
            }
        }
    }

    private fun state(): Map<String, Any?> {
        val st = stay
        val s = st?.solver
        fun xyz(c: CorpseSpot) = listOf(c.x, c.y, c.z)
        fun key(c: CorpseSpot) = "${c.x},${c.y},${c.z}"
        val ding = s?.lastDing
        val drawn = st?.shown(unlootedByType())
        return linkedMapOf(
            "enabled" to enabled(),
            "active" to active(),
            "layout" to layout,
            "mode" to mode,
            "mode_reason" to modeReason,
            "has_data" to (mode == "listed" || mode == "crystal"),
            "spots" to s?.spots?.map(::xyz),
            "learned" to s?.learnedSpots?.map(::xyz),
            "extras" to s?.extras?.map(::xyz),
            "confirmed" to s?.confirmed?.map(::xyz),
            "looted" to s?.looted?.map(::xyz),
            "last_loot" to st?.lastLoot,
            "shown" to s?.shown()?.map(::xyz),
            "drawn" to drawn?.map(::xyz),
            "capped" to st?.capped?.entries?.associate { (k, v) -> key(k) to v },
            "cap_events" to st?.capEvents,
            "support" to s?.shown()?.associate { key(it) to s.support(it) },
            "types" to s?.shown()?.associate { key(it) to st.typeOf(it) },
            "seen_types" to st?.book?.seenTypes?.entries?.associate { (k, v) -> key(k) to v },
            "shared_types" to st?.book?.sharedTypes?.entries?.associate { (k, v) -> key(k) to v },
            "inferred_types" to st?.book?.inferredTypes?.entries?.associate { (k, v) -> key(k) to v },
            "seen" to st?.seen?.map(::xyz),
            "own" to st?.own?.map(::xyz),
            "shared_by" to st?.book?.sharedBy?.entries?.associate { (k, v) -> key(k) to v },
            "positions" to s?.shown()?.associate { sp ->
                key(sp) to linkedMapOf(
                    "drawn" to s.position(sp)?.map { Math.round(it * 100) / 100.0 },
                    "center" to s.center(sp).map { Math.round(it * 100) / 100.0 },
                    "spread" to s.spread(sp)?.let { Math.round(it * 100) / 100.0 },
                )
            },
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
            "share" to CorpseShare.state(),
        )
    }
}
