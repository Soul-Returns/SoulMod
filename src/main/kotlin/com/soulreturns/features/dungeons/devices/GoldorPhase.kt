package com.soulreturns.features.dungeons.devices

import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.boss.GoldorSections
import com.soulreturns.platform.ServerTime
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.network.protocol.game.ClientboundSystemChatPacket
import net.minecraft.world.entity.EntityTypes
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * Goldor's phase (F7/M7 P3) and the server tick it began, T: from T on terminals, levers and devices take clicks and
 * the death-zone loop runs (checks at T + 60k). Pure: fed with the stripped boss lines, Storm's death strike, the floor
 * collapse and whether this player stood in Goldor's area then (docs/research/m7-boss-2026-11-13.md, "Terminal phase
 * start"; Odin's "Start" timer, Storm's end line + 64 pings, is the reference for the idea).
 *
 * - Storm dies with a thunder strike at volume 10000 over his arena (D, on time; his end line comes in the same tick
 *   unless it queues behind his previous line: M7 3–18 ticks late).
 * - The floor under the Storm pad collapses at D + 59…60 (X; lone-ping clock, M7 2026-11-13 6/6).
 * - Goldor's phase begins at D + 61 when a player is already in his area: on the lone-ping clock `Who dares` came at
 *   D + 62 and the death-zone checks at D + 61 + 60k (7 of 9 exact, 2 one tick early) in 6/6 M7 party runs, while the
 *   collapse + 1 was D + 60 in 4 of them. With nobody down it waits for the first player to drop in and `Who dares`
 *   comes a tick later (F7 solo 11/11: the line as the falling player passed y 134.7–138.2, X + 24.4…59; its checks
 *   sit at `Who dares` − 1 + 60k).
 *
 * From one client: T = D + 61 when this player stands in the area at the collapse, or `Who dares` follows the collapse
 * within [COLLAPSE_LOOK] (someone else was down), X + 1 if the strike was missed; else `Who dares` − 1. Before = a Maxor
 * or Storm line was seen and T not reached. Anything else (joined mid-fight, no line seen) is unknown, and unknown holds
 * nothing.
 */
class GoldorPhaseModel {
    var sawBoss = false
        private set
    private var sawStorm = false
    private var goldorLine = false

    /** Storm's death strike (the first thunder at volume 10000 over his arena), his end line, the floor collapse, `Who dares`. */
    var stormDeath: Long? = null
        private set
    var stormEnd: Long? = null
        private set
    var collapse: Long? = null
        private set
    var whoDares: Long? = null
        private set

    /** T, once known. */
    var start: Long? = null
        private set
    /** [start] was decided at the collapse (someone was in Goldor's area), not by `Who dares`. */
    var fromCollapse = false
        private set
    var now = Long.MIN_VALUE
        private set

    /** Known to be before Goldor's phase. */
    val before: Boolean get() = sawBoss && !active

    /** Known to have started. */
    val active: Boolean get() = began(now.toDouble())

    /** Started at [t] (server ticks with fraction): T reached, or a Goldor / Necron line seen without one (joined late). */
    fun began(t: Double): Boolean = start?.let { t >= it } ?: goldorLine

    /** When terminals open, for the countdown: T once known, else Storm's death + 61, the collapse + 1 or his end line + 61. */
    fun expected(): Long? =
        start ?: stormDeath?.let { it + STRIKE_TO_START } ?: collapse?.let { it + COLLAPSE_TO_START } ?: stormEnd?.let { it + END_TO_START }

    /** T when someone is in Goldor's area at the collapse [x]: Storm's death + 61, never before the collapse + 1. */
    private fun startAt(x: Long): Long = maxOf(stormDeath?.let { it + STRIKE_TO_START } ?: Long.MIN_VALUE, x + COLLAPSE_TO_START)

    fun advance(tick: Long) {
        if (tick > now) now = tick
    }

    fun chat(line: String, tick: Long) {
        advance(tick)
        if (line.startsWith("[BOSS] Goldor:") || line.startsWith("[BOSS] Necron:")) {
            goldorLine = true
            if (line == WHO_DARES && whoDares == null) {
                whoDares = tick
                if (start == null) {
                    val x = collapse?.takeIf { tick - it in 0..COLLAPSE_LOOK }
                    start = x?.let { startAt(it) } ?: (tick + WD_TO_START)
                    fromCollapse = x != null
                }
            }
        } else if (line.startsWith("[BOSS] Maxor:") || line.startsWith("[BOSS] Storm:")) {
            sawBoss = true
            if (line.startsWith("[BOSS] Storm:")) sawStorm = true
            if (line == STORM_END && stormEnd == null) stormEnd = tick
        }
    }

    /** A thunder at volume [STRIKE_VOLUME] or more over Storm's arena: the first after a Storm line is his death. */
    fun strike(tick: Long) {
        advance(tick)
        if (sawStorm && !goldorLine && stormDeath == null && collapse == null) stormDeath = tick
    }

    /**
     * A falling block at the Storm pad after his death or end line and before any Goldor line: the first is the floor
     * collapse; [inArea] = this player stood in Goldor's area.
     */
    fun collapse(tick: Long, inArea: Boolean) {
        advance(tick)
        if (collapse != null || goldorLine || (stormDeath == null && stormEnd == null)) return
        collapse = tick
        if (inArea) {
            start = startAt(tick)
            fromCollapse = true
        }
    }

    fun reset() {
        sawBoss = false
        sawStorm = false
        goldorLine = false
        stormDeath = null
        stormEnd = null
        collapse = null
        whoDares = null
        start = null
        fromCollapse = false
        now = Long.MIN_VALUE
    }

    companion object {
        const val STORM_END = "[BOSS] Storm: I should have known that I stood no chance."
        const val WHO_DARES = "[BOSS] Goldor: Who dares trespass into my domain?"

        /**
         * Storm's death strike to T (lone-ping clock, M7 2026-11-13, 6/6): `Who dares` at D + 62, the death-zone checks at
         * D + 61 + 60k; the collapse comes at D + 59…60, so the collapse + 1 is a tick early in 4 of 6.
         */
        const val STRIKE_TO_START = 61L
        /** His end line on time is the strike's tick (F7 11/11); it can queue later (M7), so it only stands in for a missed strike. */
        const val END_TO_START = 61L
        /** T from the collapse when the strike was missed (it is D + 59…60). */
        const val COLLAPSE_TO_START = 1L
        /** F7 solo on the lone-ping clock: every death-zone line came at `Who dares` + 60k − 1 (5/5). */
        const val WD_TO_START = -1L
        /** `Who dares` this soon after the collapse means someone was already down (M7: 2–3 on the lone-ping clock, 6/6; F7 solo 24.4–59, 11/11). */
        const val COLLAPSE_LOOK = 20L

        /** Goldor's area: the sections' floors and walkways (y 100–150), below the Storm floor (y 165–169). */
        const val AREA_Y0 = 100.0
        const val AREA_Y1 = 150.0

        /** Storm's death strike: `entity.lightning_bolt.thunder` at volume 10000 over his arena (Giga strikes use 2–50). */
        const val THUNDER = "entity.lightning_bolt.thunder"
        const val STRIKE_VOLUME = 1000f
        const val STRIKE_MIN_Y = 150.0

        /** The floor collapse: falling blocks over the Storm pad. */
        const val COLLAPSE_X0 = 90.0
        const val COLLAPSE_X1 = 112.0
        const val COLLAPSE_Z0 = 30.0
        const val COLLAPSE_Z1 = 52.0
        const val COLLAPSE_MIN_Y = 160.0

        fun inArea(y: Double): Boolean = y in AREA_Y0..AREA_Y1

        fun atPad(x: Double, y: Double, z: Double): Boolean = y >= COLLAPSE_MIN_Y && x in COLLAPSE_X0..COLLAPSE_X1 && z in COLLAPSE_Z0..COLLAPSE_Z1
    }
}

/** A blocking device, each with its own "Override blocking while" setting. */
enum class Device(val mode: (com.soulreturns.config.DeviceSolverConfig) -> String) {
    SIMON_SAYS({ it.simonSaysOverride }),
    LIGHTS({ it.lightsOverride }),
    ARROW_ALIGN({ it.arrowAlignOverride }),
}

/** The "Override blocking while" setting: [active] for the stored mode and the player's sneak state. */
object BlockOverride {
    const val NEVER = "never"
    const val STANDING = "standing"
    const val SNEAKING = "sneaking"
    val MODES = listOf(NEVER, STANDING, SNEAKING)

    fun active(mode: String, sneaking: Boolean): Boolean = (mode == STANDING && !sneaking) || (mode == SNEAKING && sneaking)
}

/**
 * The live [GoldorPhaseModel] every F7/M7 helper shares: Arrow Align's held last click and its "Click now", Lights'
 * "flip any lever", the section warning. Boss lines, the thunder and the falling blocks are stamped with the server tick
 * of their netty arrival (`ClientPacketListenerMixin`; the falling blocks come in bundles, through
 * [com.soulreturns.features.dungeons.boss.EntityAdds]) and fed in arrival order on the client tick.
 */
object GoldorPhase {
    const val WAIT = "Wait for Goldor"
    const val NOW = "Click now"

    val model = GoldorPhaseModel()
    val sections = GoldorSections()

    /** A boss line, the death strike (no text, not falling) or the collapse, with its arrival tick. */
    private class Signal(val line: String?, val falling: Boolean, val tick: Long)

    private val inbox = ConcurrentLinkedQueue<Signal>()

    @Volatile
    private var listening = false

    /** The tick of the last falling block passed on: the collapse adds ~2000 in one tick. */
    @Volatile
    private var lastFallingTick = Long.MIN_VALUE

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
    }

    private fun reset() {
        model.reset()
        sections.reset()
        lastFallingTick = Long.MIN_VALUE
        inbox.clear()
    }

    private fun tick() {
        listening = DeviceSolvers.inF7Boss()
        if (!listening) {
            reset()
            return
        }
        val tick = ServerTime.tick() ?: return
        val player = Minecraft.getInstance().player
        while (true) {
            val s = inbox.poll() ?: break
            if (s.line != null) {
                model.chat(s.line, s.tick)
                if (model.start != null && model.active) sections.begin()
                sections.chat(s.line)
            } else if (s.falling) model.collapse(s.tick, player != null && GoldorPhaseModel.inArea(player.y))
            else model.strike(s.tick)
        }
        model.advance(tick)
        if (model.start != null && model.active) sections.begin()
    }

    /** `ClientPacketListenerMixin.handleSystemChat` HEAD, netty pass: boss lines with their arrival tick. */
    @JvmStatic
    fun onSystemChat(packet: ClientboundSystemChatPacket) {
        if (!listening || packet.overlay() || Minecraft.getInstance().isSameThread) return
        val line = MessageDetector.stripColorCodes(packet.content().string).trim()
        if (MessageDetector.isPlayerMessage(line)) return
        if (!line.startsWith("[BOSS] ") && line != GoldorSections.GATE_DOWN &&
            line != com.soulreturns.features.dungeons.boss.StormModel.CORE_OPEN && !GoldorSections.COUNTER.matches(line)
        ) return
        val tick = ServerTime.tick() ?: return
        inbox.add(Signal(line, false, tick))
    }

    /** `ClientPacketListenerMixin.handleSoundEvent` HEAD, netty pass: Storm's death strike. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!listening || Minecraft.getInstance().isSameThread) return
        if (packet.volume < GoldorPhaseModel.STRIKE_VOLUME || packet.y < GoldorPhaseModel.STRIKE_MIN_Y) return
        if (packet.sound.value().location().path != GoldorPhaseModel.THUNDER) return
        val tick = ServerTime.tick() ?: return
        inbox.add(Signal(null, false, tick))
    }

    /** [com.soulreturns.features.dungeons.boss.EntityAdds], netty thread: the floor collapse (falling blocks at the Storm pad). */
    @JvmStatic
    fun onAddEntity(packet: ClientboundAddEntityPacket) {
        if (!listening || packet.type !== EntityTypes.FALLING_BLOCK || Minecraft.getInstance().isSameThread) return
        if (!GoldorPhaseModel.atPad(packet.x, packet.y, packet.z) || model.collapse != null) return
        val tick = ServerTime.tick() ?: return
        if (tick == lastFallingTick) return
        lastFallingTick = tick
        inbox.add(Signal(null, true, tick))
    }

    fun before(): Boolean = model.before

    /** Arrow Align's toggle is on, Goldor's phase is known not to have started and nothing overrides blocking right now. */
    fun holding(): Boolean = cfg.dungeons.devices.arrowHoldLast && model.before && !DeviceSolvers.blockOverridden(Device.ARROW_ALIGN)

    /** Goldor's phase is known to be active (hold on or off): a board one click short shows "Click now". */
    fun showNow(): Boolean = model.active
}

/** Shared by every device solver's click path. Read at press time. */
fun DeviceSolvers.blockOverridden(device: Device): Boolean =
    BlockOverride.active(device.mode(cfg.dungeons.devices), Minecraft.getInstance().player?.isShiftKeyDown == true)

/** Wrong-click blocking applies to [device]: the toggle is on and its override is not active. */
fun DeviceSolvers.blocking(device: Device): Boolean = cfg.dungeons.devices.blockWrongClicks && !blockOverridden(device)
