package com.soulreturns.features.dungeons.boss

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.platform.ServerTime
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.world.entity.EntityTypes
import net.minecraft.world.phys.Vec3
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * M7 P5: each dragon's spawn countdown ([DragonSpawns], server ticks from the netty arrival of its first spawn
 * particle) as a label at its spawn, through blocks, and the soonest one in the boss timers' phase place
 * ([CrosshairHud]). Display only.
 */
object DragonCountdown {
    private class Signal(val pulse: Boolean, val x: Double, val y: Double, val z: Double, val tick: Long, val nanos: Long)

    /** A first pulse whose tick is read again once the next `set_time` arrived. */
    private class Retime(val dragon: Dragon, val tick: Double, val nanos: Long)

    private val retimes = ArrayList<Retime>()

    val spawns = DragonSpawns()
    private val inbox = ConcurrentLinkedQueue<Signal>()

    @Volatile
    private var listening = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        LevelRenderEvents.BEFORE_GIZMOS.register { render() }
    }

    private fun on(): Boolean = cfg.dungeons.boss.dragonCountdown && LocationApi.inDungeon

    private fun reset() {
        spawns.clear()
        inbox.clear()
        retimes.clear()
    }

    private fun tick() {
        listening = on()
        if (!listening) {
            if (inbox.isNotEmpty()) reset()
            return
        }
        while (true) {
            val s = inbox.poll() ?: break
            if (!s.pulse) {
                spawns.dragon(s.x, s.z)
                continue
            }
            val before = spawns.pending(s.tick.toDouble()).map { it.dragon }
            spawns.particle(true, DragonSpawns.PULSE_COUNT, s.x, s.y, s.z, s.tick.toDouble())
            val d = Dragon.at(s.x, s.z, 0.01)
            if (d != null && d !in before) retimes += Retime(d, s.tick.toDouble(), s.nanos)
        }
        val it = retimes.iterator()
        while (it.hasNext()) {
            val r = it.next()
            val exact = ServerTime.between(r.nanos)
            if (exact != null) {
                spawns.retime(r.dragon, r.tick, exact)
                it.remove()
            } else if (System.nanoTime() - r.nanos > RETIME_GIVE_UP) {
                it.remove()
            }
        }
    }

    /** `ClientPacketListenerMixin.handleParticleEvent` HEAD; the netty pass stamps the pulse with its arrival tick. */
    @JvmStatic
    fun onParticle(packet: ClientboundLevelParticlesPacket) {
        if (!listening || Minecraft.getInstance().isSameThread) return
        if (packet.count != DragonSpawns.PULSE_COUNT || packet.particle.type !== ParticleTypes.FLAME) return
        if (Dragon.at(packet.x, packet.z, 0.01) == null) return
        val tick = ServerTime.tick() ?: return
        inbox.add(Signal(true, packet.x, packet.y, packet.z, tick, System.nanoTime()))
    }

    /** [EntityAdds], netty thread: a dragon (always inside a bundle on Hypixel) ends its countdown. */
    @JvmStatic
    fun onAddEntity(packet: ClientboundAddEntityPacket) {
        if (!listening || packet.type !== EntityTypes.ENDER_DRAGON || Minecraft.getInstance().isSameThread) return
        inbox.add(Signal(false, packet.x, packet.y, packet.z, 0, 0))
    }

    private fun now(): Double? = ServerTime.exact() ?: ServerTime.tick()?.toDouble()

    /** The soonest pending spawn and its ticks to go, for the phase place. */
    fun next(): Pair<Dragon, Double>? {
        if (!listening) return null
        val now = now() ?: return null
        val p = spawns.pending(now).firstOrNull() ?: return null
        return p.dragon to (p.spawnAt - now).coerceAtLeast(0.0)
    }

    private fun render() {
        if (!listening) return
        val now = now() ?: return
        val list = spawns.pending(now)
        if (list.isEmpty()) return
        val mc = Minecraft.getInstance()
        val eye = mc.gameRenderer.mainCamera().position()
        val o = CrosshairOptions(ticks = cfg.dungeons.boss.units == 1)
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            for (p in list) {
                val at = Vec3(p.dragon.x, LABEL_Y, p.dragon.z)
                val left = (p.spawnAt - now).coerceAtLeast(0.0)
                val text = "${p.dragon.name} ${CrosshairTimers.number(left, o)}"
                val scale = maxOf(MIN_SCALE, (eye.distanceTo(at) * SCALE_PER_BLOCK).toFloat())
                Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(p.dragon.color).withScale(scale)).setAlwaysOnTop()
            }
        }
    }

    private const val LABEL_Y = 21.0
    private const val RETIME_GIVE_UP = 3_000_000_000L
    private const val MIN_SCALE = 0.8f
    private const val SCALE_PER_BLOCK = 0.1
}
