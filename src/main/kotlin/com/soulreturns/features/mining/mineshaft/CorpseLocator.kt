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
 * corpse spots ([CorpseLocatorSolver]), locates any corpse stand the player can see, names corpses by
 * elimination against the tab ([CorpseElimination]) and shares located corpses with the party
 * ([CorpseShare]); draws a waypoint on each located corpse.
 *
 * Corpse armor stands are on the client through walls, so an entity is read only once the player
 * can see it ([MobSpotter], ToS, rule://mineshaft).
 */
object CorpseLocator {
    private const val LINE_WIDTH = 3f

    /** About 1.5 s of client positions: the ding is the server's position, a ping or two behind ours. */
    private const val HISTORY_TICKS = 30
    private const val PENDING_MAX = 40

    /** Cadence of the sight scan, the elimination and the key scan. */
    private const val SCAN_TICKS = 10

    /** Sight scan: corpse stands within this many blocks of the player, at most [SCAN_LIMIT] sight tests per scan. */
    private const val SCAN_RADIUS = 32.0
    private const val SCAN_LIMIT = 8

    /** Added to a non-corpse helmet's rank: beyond any squared distance in the scan box. */
    private const val OTHER_RANK = 1.0e6
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

    /** Null while the layout is unknown; a layout without spot data gets one with no spots (sight and party only). */
    var solver: CorpseLocatorSolver? = null
        private set

    private var hasData = false

    /** Types by source and who shared what; per stay. */
    private val book = CorpseBook()

    /** Corpses whose stand was seen (position and type from it); sticky for the stay. */
    private val seen = HashSet<CorpseSpot>()

    /** Corpses the player located (a ding fit only it, or its stand was seen): what [ownCorpses] shares. */
    private val own = LinkedHashSet<CorpseSpot>()

    /** Stand entity ids already turned into a located (or looted) corpse this stay: no sight test again. */
    private val resolvedStands = HashSet<Int>()

    /** Types named by this stay's loot lines. */
    private val lootTypes = ArrayList<String>()

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
        CorpseShare.register()
    }

    fun enabled(): Boolean = cfg.mining.mineshaft.corpseLocator

    private fun active(): Boolean = enabled() && SkyblockLocation.isInArea(MineshaftState.AREA)

    fun typeOf(spot: CorpseSpot): String? = book.typeOf(spot)

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
        if (fit != null && fit.fitting.size == 1) own += fit.fitting[0]
        val mute = !tabAllLooted() && CorpseLocatorSolver.mutes(fit, active(), cfg.mining.mineshaft.corpseLocatorMute)
        if (mute) muted++
        return mute
    }

    private fun onChat(raw: String) {
        val s = solver ?: return
        if (!active()) return
        val type = LOOT.matchEntire(MessageDetector.stripColorCodes(raw))?.groupValues?.get(1) ?: return
        val p = Minecraft.getInstance().player ?: return
        lootTypes += type
        val spot = s.loot(p.x, p.y, p.z, type)
        DebugLogger.logFeatureEvent("Corpse locator: $type loot -> ${spot ?: "no corpse within ${CorpseLocatorSolver.LOOT_RADIUS}, kept for its stand"}")
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
        if (++ticks % SCAN_TICKS == 0) {
            scan(s, p.x, p.y, p.z)
            eliminate(s)
            inventory = buildSet {
                val inv = p.inventory
                for (i in 0 until inv.containerSize) SkyblockItemUtils.getSkyblockId(inv.getItem(i))?.let { add(it) }
            }
        }
        CorpseShare.tick(own.map { Triple(it, typeOf(it), s.ownEstimate(it) != null) })
    }

    /**
     * Locates every corpse stand the player can see within [SCAN_RADIUS]: helmeted armor stands,
     * best-ranked first, sight-tested ([MobSpotter.findAllVisibleIn]); the helmet is read only after
     * that. A corpse helmet locates the corpse at once (its spot, or an extra outside every box); a
     * helmet no corpse wears only types a corpse already located there and not yet seen (`other`),
     * since mineshafts also hold decor stands (`WILL_O_WISP`, `GLACITE_HELMET` in the recordings).
     */
    private fun scan(s: CorpseLocatorSolver, px: Double, py: Double, pz: Double) {
        val r = SCAN_RADIUS
        val box = AABB(px - r, py - r, pz - r, px + r, py + r, pz + r)
        // Corpse helmets rank ahead of decor (Will-o'-wisp, Glacite helmets) so those never take the sight-test budget.
        val visible =
            MobSpotter.findAllVisibleIn(box, SCAN_LIMIT) {
                if (it !is ArmorStand || it.id in resolvedStands) return@findAllVisibleIn null
                val head = it.getItemBySlot(EquipmentSlot.HEAD)
                if (head.isEmpty) return@findAllVisibleIn null
                val d = it.distanceToSqr(px, py, pz)
                if (CorpseLook.typeOf(SkyblockItemUtils.getSkyblockId(head)) == CorpseLook.OTHER) d + OTHER_RANK else d
            }
        val typed = visible.map { it to CorpseLook.typeOf(SkyblockItemUtils.getSkyblockId((it as ArmorStand).getItemBySlot(EquipmentSlot.HEAD))) }
        for ((stand, type) in typed.sortedBy { it.second == CorpseLook.OTHER }) {
            val known = s.match(stand.x, stand.y, stand.z)
            if (type == CorpseLook.OTHER && (known == null || known !in s.confirmed || known in seen)) continue
            val spot = s.locate(stand.x, stand.y, stand.z, type)
            resolvedStands += stand.id
            if (spot == null || (spot in seen && book.seenTypes[spot] != CorpseLook.OTHER)) continue
            seen += spot
            own += spot
            book.see(spot, type)?.let { DebugLogger.logFeatureEvent("Corpse locator: $spot seen as $type, was $it") }
            DebugLogger.logFeatureEvent("Corpse locator: saw $type corpse at ${"%.2f %.2f %.2f".format(Locale.ROOT, stand.x, stand.y, stand.z)} -> $spot")
        }
    }

    /** Names located corpses without a type when the tab leaves only one type for them. */
    private fun eliminate(s: CorpseLocatorSolver) {
        val shown = s.shown()
        val unknown = shown.filter { typeOf(it) == null }
        if (unknown.isEmpty()) return
        val rows = MineshaftCorpses.byType.mapValues { (_, c) -> c.total to c.looted }
        val type = CorpseElimination.typeOfRest(rows, lootTypes, shown.mapNotNull { typeOf(it) }, unknown.size) ?: return
        for (spot in unknown) book.inferredTypes[spot] = type
        DebugLogger.logFeatureEvent("Corpse locator: $unknown are $type by elimination")
    }

    /** A party member's located corpses ([CorpseBook.receive]). Client thread. */
    fun receive(from: String, corpses: List<SharedCorpse>) {
        val s = solver ?: return
        book.receive(s, from, corpses)
    }

    /** What [CorpseShare] sends: the corpses the player located, looted ones included (looting is per player). */
    fun ownCorpses(): List<SharedCorpse> {
        val s = solver ?: return emptyList()
        return own.filter { it !in s.spots || data.fixed[layout] == null }.map { spot ->
            SharedCorpse(spot.takeIf { it in s.spots }, s.ownEstimate(spot) ?: s.center(spot), typeOf(spot))
        }
    }

    private fun start(id: String) {
        layout = id
        val spots = data.spots(id)
        hasData = spots != null
        if (spots == null) DebugLogger.logFeatureEvent("Corpse locator: no spot data for layout $id, sight and party only")
        val fixedType = data.fixed[id]
        val s = CorpseLocatorSolver(spots ?: emptyList(), fixed = fixedType != null)
        if (fixedType != null) for (spot in s.spots) book.seenTypes[spot] = fixedType
        for (d in pending) s.ding(d[0], d[1], d[2], d[3])?.let { if (it.fitting.size == 1) own += it.fitting[0] }
        pending.clear()
        solver = s
        CorpseShare.onStay(id)
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
        hasData = false
        book.clear()
        seen.clear()
        own.clear()
        resolvedStands.clear()
        lootTypes.clear()
        inventory = emptySet()
        pending.clear()
        history.clear()
        dings = 0
        muted = 0
        ticks = 0
        tabBaseline = -1
        tabHandled = -1
        CorpseShare.reset()
    }

    private fun render() {
        if (!active()) return
        val sv = solver ?: return
        val shown = sv.shown()
        if (shown.isEmpty() || tabAllLooted()) return
        val mc = Minecraft.getInstance()
        val p = mc.player ?: return
        val m = cfg.mining.mineshaft
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (s in shown) {
                val look = CorpseLook.decide(typeOf(s), inventory, m)
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
        val s = solver
        fun xyz(c: CorpseSpot) = listOf(c.x, c.y, c.z)
        fun key(c: CorpseSpot) = "${c.x},${c.y},${c.z}"
        val ding = s?.lastDing
        return linkedMapOf(
            "enabled" to enabled(),
            "active" to active(),
            "layout" to layout,
            "has_data" to hasData,
            "spots" to s?.spots?.map(::xyz),
            "extras" to s?.extras?.map(::xyz),
            "confirmed" to s?.confirmed?.map(::xyz),
            "looted" to s?.looted?.map(::xyz),
            "shown" to s?.shown()?.map(::xyz),
            "types" to s?.shown()?.associate { key(it) to typeOf(it) },
            "seen_types" to book.seenTypes.entries.associate { (k, v) -> key(k) to v },
            "shared_types" to book.sharedTypes.entries.associate { (k, v) -> key(k) to v },
            "inferred_types" to book.inferredTypes.entries.associate { (k, v) -> key(k) to v },
            "seen" to seen.map(::xyz),
            "own" to own.map(::xyz),
            "shared_by" to book.sharedBy.entries.associate { (k, v) -> key(k) to v },
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
