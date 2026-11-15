package com.soulreturns.features.dungeons.boss

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.features.dungeons.devices.GoldorPhaseModel
import com.soulreturns.platform.ServerPings
import com.soulreturns.platform.ServerTime
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundBossEventPacket
import net.minecraft.network.protocol.game.ClientboundSetTitleTextPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.BossEvent
import net.minecraft.world.entity.EntityTypes
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.world.entity.boss.wither.WitherBoss
import net.minecraft.world.entity.projectile.hurtingprojectile.LargeFireball
import org.slf4j.LoggerFactory
import java.util.Locale
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Feeds the F7/M7 boss fight into a [StormModel] on server ticks ([ServerTime]): boss chat lines,
 * Giga countdown titles and pillar sounds (stamped at their arrival on the netty thread), Storm's
 * position (any wither the client tracks) and the player's. Display only: reads packets and
 * positions, never sends or acts on anything.
 *
 * Log lines (`[Soul/Storm]`) are written whenever a helper is on, debug mode or not: every phase
 * offset, pillar step, crush window result, high phase, fireball and bait try with its server tick, so one real run is
 * enough to check the timings (and to fill in M7's table).
 */
object F7Boss {
    private val logger = LoggerFactory.getLogger("Soul/Storm")

    /** The current F7/M7 run's model. Tracking is independent of display toggles. Client thread. */
    var model: StormModel? = null
        private set
    var maxorProgress: Float? = null
        private set
    var necronProgress: Float? = null
        private set
    var observedBoss = BossPhase.NONE
        private set

    private class Signal(val tick: Long, val fresh: Boolean, val title: String?, val sound: String?, val x: Double, val y: Double, val z: Double, val volume: Float) {
        /** The named boss bar's progress, or NaN. */
        var bar = Float.NaN
        var boss = BossPhase.STORM
        var falling = false
    }

    /** A chat line as it reached the netty thread: its text (codes stripped), arrival and tick. */
    private class NetLine(val text: String, val nanos: Long, val tick: Long?, val alone: Long, val bundled: Long)

    /** Debug: the ping counts and `set_time` tick at P / `Who dares` as Odin starts from them (client-thread chat). */
    private class ClockRef(val line: String, val tick: Long, val alone: Long, val bundled: Long)

    private var clockRef: ClockRef? = null

    /** A line whose tick is read again once the next `set_time` arrived ([StormModel.retime]). */
    private class Retime(val model: StormModel, val line: String, val tick: Long, val nanos: Long)

    private val inbox = ConcurrentLinkedQueue<Signal>()
    private val netLines = ConcurrentLinkedQueue<NetLine>()
    private val retimes = ArrayList<Retime>()
    @Volatile
    private var bossBar: java.util.UUID? = null
    @Volatile
    private var bossKind = BossPhase.NONE
    @Volatile
    private var lastFallingTick = Long.MIN_VALUE
    private val withers = HashSet<Int>()
    private val seen = HashSet<Int>()

    @Volatile
    private var listening = false

    private val fireballs = HashSet<Int>()

    // The bait try of the current high phase, for the log.
    private var baitA: Long? = null
    private var baitPadLogged = false
    private var baitStepLogged = false
    private var baitSpotLogged = false

    private const val AT_SPOT = 1.5
    private const val NET_LINES_KEPT = 64
    private const val RETIME_GIVE_UP = 3_000_000_000L

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        Events.subscribe<ChatMessage> { if (it.source == ChatMessage.Source.SERVER) onChat(it.raw) }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        LevelRenderEvents.BEFORE_GIZMOS.register { StormWorld.render() }
    }

    fun anyOn(): Boolean = cfg.dungeons.boss.crushWindow

    /** This floor's timings while the helpers apply here, else null. */
    fun timings(): BossTimings? {
        if (!LocationApi.inDungeon) return null
        val t = BossTimings.of(DungeonRunState.floor.shortName) ?: return null
        if (t === BossTimings.M7 && !cfg.dungeons.boss.onM7) return null
        return t
    }

    private fun reset() {
        model = null
        listening = false
        inbox.clear()
        netLines.clear()
        retimes.clear()
        bossBar = null
        bossKind = BossPhase.NONE
        observedBoss = BossPhase.NONE
        maxorProgress = null
        necronProgress = null
        lastFallingTick = Long.MIN_VALUE
        clockRef = null
        withers.clear()
        fireballs.clear()
        baitA = null
    }

    private fun write(msg: String) {
        if (anyOn() && timings() != null) logger.info("[Soul/Storm] $msg")
    }

    private fun ensureModel(): StormModel? {
        val t = (if (anyOn()) timings() else null)
            ?: if (cfg.dungeons.roles.enabled && LocationApi.inDungeon) {
                if (DungeonRunState.floor.shortName == "F7") BossTimings.F7
                else if (DungeonRunState.floor.shortName == "M7") BossTimings.M7
                else null
            } else null
        if (t == null) {
            if (model != null) reset()
            return null
        }
        val m = model
        if (m != null && m.timings === t) return m
        write("boss helpers on ${t.floor}: ${t.source}${if (t.unverified.isEmpty()) "" else "; unverified: ${t.unverified}"}")
        return StormModel(t) { write(it) }.also {
            model = it
            listening = true
        }
    }

    // ------------------------------------------------------------------ netty-thread stamps

    /** `ClientPacketListenerMixin.handleSoundEvent` HEAD, both passes; the netty pass stamps the arrival tick. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!listening || Minecraft.getInstance().isSameThread) return
        val path = packet.sound.value().location().path
        val strike = path == GoldorPhaseModel.THUNDER && packet.volume >= GoldorPhaseModel.STRIKE_VOLUME
        if (!strike && path != StormModel.PISTON_DOWN && path != StormModel.PISTON_UP && path != StormModel.GOLEM && path != StormModel.BREAK_DOOR) return
        val tick = ServerTime.tick() ?: return
        inbox.add(Signal(tick, ServerTime.fresh(), null, path, packet.x, packet.y, packet.z, packet.volume))
    }

    /** `ClientPacketListenerMixin.setTitleText` HEAD, both passes; the netty pass stamps the arrival tick. */
    @JvmStatic
    fun onTitle(packet: ClientboundSetTitleTextPacket) {
        if (!listening || Minecraft.getInstance().isSameThread) return
        val tick = ServerTime.tick() ?: return
        inbox.add(Signal(tick, ServerTime.fresh(), MessageDetector.stripColorCodes(packet.text.string), null, 0.0, 0.0, 0.0, 0f))
    }

    /** `ClientPacketListenerMixin.handleSystemChat` HEAD, netty pass: chat lines keep their arrival (the client thread reads them later). */
    @JvmStatic
    fun onSystemChat(packet: ClientboundSystemChatPacket) {
        if (!listening || packet.overlay() || Minecraft.getInstance().isSameThread) return
        netLines.add(NetLine(MessageDetector.stripColorCodes(packet.content().string).trim(), System.nanoTime(), ServerTime.tick(), ServerPings.standalone, ServerPings.bundled))
        while (netLines.size > NET_LINES_KEPT) netLines.poll()
    }

    /** `ClientPacketListenerMixin.handleBossUpdate` HEAD, netty pass: the Storm bar's progress (its crush steps). */
    @JvmStatic
    fun onBossUpdate(packet: ClientboundBossEventPacket) {
        if (!listening || Minecraft.getInstance().isSameThread) return
        packet.dispatch(
            object : ClientboundBossEventPacket.Handler {
                override fun add(id: java.util.UUID, name: Component, progress: Float, color: BossEvent.BossBarColor, overlay: BossEvent.BossBarOverlay, darken: Boolean, music: Boolean, fog: Boolean) {
                    val kind = boss(name)
                    if (kind == BossPhase.NONE) return
                    bossBar = id
                    bossKind = kind
                    bar(progress, kind)
                }

                override fun updateName(id: java.util.UUID, name: Component) {
                    val kind = boss(name)
                    if (kind != BossPhase.NONE) {
                        bossBar = id
                        bossKind = kind
                    } else if (bossBar == id) {
                        bossBar = null
                        bossKind = BossPhase.NONE
                    }
                }

                override fun updateProgress(id: java.util.UUID, progress: Float) {
                    if (bossBar == null) {
                        Minecraft.getInstance().gui.hud.bossOverlay.events[id]?.name?.let {
                            val kind = boss(it)
                            if (kind != BossPhase.NONE) {
                                bossBar = id
                                bossKind = kind
                            }
                        }
                    }
                    if (id == bossBar) bar(progress, bossKind)
                }
            },
        )
    }

    private fun boss(name: Component): Int = when (MessageDetector.stripColorCodes(name.string).trim()) {
        "Maxor" -> BossPhase.MAXOR
        "Storm" -> BossPhase.STORM
        "Goldor" -> BossPhase.GOLDOR
        "Necron" -> BossPhase.NECRON
        else -> BossPhase.NONE
    }

    private fun bar(progress: Float, boss: Int) {
        val tick = ServerTime.tick() ?: return
        inbox.add(Signal(tick, ServerTime.fresh(), null, null, 0.0, 0.0, 0.0, 0f).also {
            it.bar = progress
            it.boss = boss
        })
    }

    /** A reconnect can add the current bar before the floor reader starts listening. */
    private fun adoptBar(m: StormModel, tick: Long) {
        if (bossBar != null) return
        for ((id, event) in Minecraft.getInstance().gui.hud.bossOverlay.events) {
            val kind = boss(event.name)
            if (kind == BossPhase.NONE) continue
            bossBar = id
            bossKind = kind
            observedBoss = kind
            m.observeBossPhase(kind)
            if (kind == BossPhase.MAXOR) maxorProgress = event.progress
            else if (kind == BossPhase.NECRON) necronProgress = event.progress
            else if (kind == BossPhase.STORM) m.bar(event.progress, tick)
            return
        }
    }

    /** [EntityAdds], netty thread: falling blocks at the Storm pad (the P2→P3 floor collapse, ~2000 in one tick, in bundles). */
    @JvmStatic
    fun onAddEntity(packet: ClientboundAddEntityPacket) {
        if (!listening || packet.type !== EntityTypes.FALLING_BLOCK || Minecraft.getInstance().isSameThread) return
        if (!GoldorPhaseModel.atPad(packet.x, packet.y, packet.z)) return
        val tick = ServerTime.tick() ?: return
        if (tick == lastFallingTick) return
        lastFallingTick = tick
        inbox.add(Signal(tick, ServerTime.fresh(), null, null, packet.x, packet.y, packet.z, 0f).also { it.falling = true })
    }

    private fun drain(m: StormModel) {
        while (true) {
            val s = inbox.poll() ?: break
            if (s.title != null) {
                m.title(s.title, s.tick)
            } else if (!s.bar.isNaN()) {
                observedBoss = s.boss
                m.observeBossPhase(s.boss)
                if (s.boss == BossPhase.MAXOR) maxorProgress = s.bar
                else if (s.boss == BossPhase.NECRON) necronProgress = s.bar
                else if (s.boss == BossPhase.STORM) m.bar(s.bar, s.tick)
            } else if (s.falling) {
                m.falling(s.x, s.y, s.z, s.tick)
            } else {
                m.sound(s.sound!!, s.x, s.y, s.z, s.volume, s.tick, s.fresh)
            }
        }
    }

    // ------------------------------------------------------------------ client thread

    private fun onChat(raw: String) {
        var m = ensureModel() ?: return
        val line = MessageDetector.stripColorCodes(raw).trim()
        if (MessageDetector.isPlayerMessage(line)) return
        // The netty arrival of the same line: the client thread reads chat a little later.
        var net: NetLine? = null
        for (n in netLines) {
            if (n.text == line) {
                net = n
                break
            }
        }
        if (net != null) netLines.remove(net)
        val tick = net?.tick ?: ServerTime.tick() ?: return
        if (line == StormModel.PATHETIC && m.pathetic != null) {
            // A second P2 start in one world (only the test server does that): a fresh fight.
            m = StormModel(m.timings) { write(it) }.also { model = it }
        }
        drain(m)
        m.chat(line, tick)
        if (StormModel.retimed(line)) retimes += Retime(m, line, tick, net?.nanos ?: System.nanoTime())
        if (cfg.dev.debugMode) clocks(m, line, tick, net)
    }

    /**
     * Debug (`debugMode`): both clocks at P, `Who dares` and each direct Goldor check. Odin's tick timers count lone pings
     * from the chat line; the line here gives lone and bundled pings since then next to the `set_time` ticks, so a run
     * shows which count steps once per server tick.
     */
    private fun clocks(m: StormModel, line: String, tick: Long, net: NetLine?) {
        if (line == StormModel.PATHETIC || line == StormModel.WHO_DARES) {
            clockRef = ClockRef(line, tick, ServerPings.standalone, ServerPings.bundled)
            write("clocks: ${line.take(24)} at set_time $tick, pings lone ${ServerPings.standalone} bundled ${ServerPings.bundled}")
            return
        }
        if (m.phase != BossPhase.GOLDOR || !(line == StormModel.DEATH_ZONE || StormModel.DEATH_LINE.matches(line))) return
        val ref = clockRef?.takeIf { it.line == StormModel.WHO_DARES } ?: return
        val alone = (net?.alone ?: ServerPings.standalone) - ref.alone
        val bundled = (net?.bundled ?: ServerPings.bundled) - ref.bundled
        val odin = Math.floorMod(alone, 60L).let { if (it > 30) it - 60 else it }
        write(
            "clocks: Goldor check (${line.take(24)}) set_time WD+${tick - ref.tick}, pings since WD lone $alone (Odin ${if (odin >= 0) "+" else ""}$odin off its loop) " +
                "bundled $bundled, all ${alone + bundled}; grid ${m.goldorAnchor?.let { a -> Math.floorMod(tick - a, 60L).let { if (it > 30) it - 60 else it } }}",
        )
    }

    /** Lines that anchor a grid, read again between the `set_time` packets around them once the next one arrived. */
    private fun retime() {
        if (retimes.isEmpty()) return
        val now = System.nanoTime()
        val it = retimes.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val exact = ServerTime.between(r.nanos)
            if (exact != null) {
                r.model.retime(r.line, r.tick, exact.toLong())
                it.remove()
            } else if (now - r.nanos > RETIME_GIVE_UP) {
                it.remove()
            }
        }
    }

    private fun tick() {
        val m = ensureModel() ?: return
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val tick = ServerTime.tick() ?: return
        drain(m)
        adoptBar(m, tick)
        retime()
        mc.player?.let { m.player(it.x, it.y, it.z, tick) }
        if (m.phase == BossPhase.MAXOR || m.phase == BossPhase.STORM) {
            seen.clear()
            for (e in level.entitiesForRendering()) {
                if (e is LargeFireball) {
                    if (fireballs.add(e.id)) m.fireball(e.x, e.y, e.z, tick)
                    continue
                }
                if (e !is WitherBoss) continue
                val h = e.interpolation
                val pos = if (h != null && h.hasActiveInterpolation()) h.position() else e.position()
                seen += e.id
                m.wither(e.id, pos.x, pos.y, pos.z, tick)
            }
            for (id in withers) if (id !in seen) m.witherGone(id, tick)
            withers.clear()
            withers += seen
        }
        m.advance(tick)
        if (anyOn() && cfg.dungeons.boss.showArm) logBait(m, tick)
    }

    /** One log line per stage of each high phase's bait try (§10 option 1): plan, on the pad, step, at the spot. */
    private fun logBait(m: StormModel, tick: Long) {
        val v = m.bait(tick) ?: return
        val p = m.pathetic ?: tick
        if (baitA != v.a) {
            baitA = v.a
            baitPadLogged = false
            baitStepLogged = false
            baitSpotLogged = false
            write(
                String.format(
                    Locale.ROOT, "bait try A=P+%d: arm %s for poll P+%d (A+%d), spot %s, loaded %b",
                    v.a - p, v.pillar.id, v.armPoll - p, v.armPoll - v.a, xyz(v.spot), v.loaded,
                ),
            )
        }
        if (!baitPadLogged && m.onPadSince != null && m.lastPad === v.pillar) {
            baitPadLogged = true
            write("bait on the ${v.pillar.padName} pad P+${tick - p} (${v.armPoll - tick} before the poll)")
        }
        if (!baitStepLogged && v.stage == BaitView.BAIT) {
            baitStepLogged = true
            write("bait step ${v.pillar.id} P+${tick - p}, window ends P+${(m.stateAt(v.pillar.id)?.windowEnd ?: tick) - p}")
        }
        val d = v.playerToSpot
        if (!baitSpotLogged && v.stage == BaitView.BAIT && d != null && d <= AT_SPOT) {
            baitSpotLogged = true
            val s = m.stormPos
            write("bait at the spot P+${tick - p}, Storm ${s?.let { xyz(it) } ?: "?"} (${StormState.name(m.state)})")
        }
    }

    private fun xyz(p: DoubleArray) = String.format(Locale.ROOT, "(%.1f, %.1f, %.1f)", p[0], p[1], p[2])
}
