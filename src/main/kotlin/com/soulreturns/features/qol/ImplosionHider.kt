package com.soulreturns.features.qol

import com.soulreturns.config.cfg
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.sounds.SoundSource
import java.util.Collections
import java.util.IdentityHashMap

/**
 * Hides Implosion (Hyperion, Astraea, Scylla, Valkyrie): its explosion particles, and either part
 * of its sound. `ClientPacketListenerMixin` hands both packet kinds here on the network thread.
 *
 * Packet log on the Crimson Isle (2026-11-07): every Implosion, the player's own and other players',
 * is one `level_particles` with `minecraft:explosion`, count 8, max speed 8 and zero spread at the
 * target block. Nothing else in the log matched that: ambient Crimson Isle explosions are count 3
 * with spread 1, and a real `explode` packet is a different packet entirely.
 *
 * The sound (only the caster gets it) is `entity.generic.explode` (BLOCKS) plus
 * `entity.enderman.teleport` (HOSTILE), both volume 1 pitch 1 at the particle's exact position, sent
 * **before** the particle in the same tick (0-48 ms apart in the log). The same teleport sound also
 * plays for the Aspect of the Void and blocked Hyperion swings, so a sound is not dropped on its own
 * signature: a matching sound is held back up to [HOLD_MS] and dropped only if the Implosion
 * particle follows at its position; otherwise it plays, slightly late. Held only while that part's
 * toggle is on.
 */
object ImplosionHider {
    private const val HOLD_MS = 60L
    private const val MATCH_DIST_SQ = 2.0 * 2.0

    private enum class Part { EXPLOSION, TELEPORT }

    private class Held(val packet: ClientboundSoundPacket, val part: Part, val at: Long)

    private val held = ArrayList<Held>()

    /** Packets [release] re-dispatches; their second pass through the mixin must not be held again. */
    private val released: MutableSet<ClientboundSoundPacket> = Collections.synchronizedSet(Collections.newSetFromMap(IdentityHashMap()))

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { release(System.currentTimeMillis()) })
    }

    /** True drops the particle packet. Also settles any held sounds this Implosion explains. */
    @JvmStatic
    fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
        if (!isImplosion(packet)) return false
        synchronized(held) {
            held.removeIf { h ->
                val dx = h.packet.x - packet.x
                val dy = h.packet.y - packet.y
                val dz = h.packet.z - packet.z
                dx * dx + dy * dy + dz * dz <= MATCH_DIST_SQ
            }
        }
        return cfg.general.implosionParticlesHidden
    }

    /** True cancels vanilla handling: the sound is held for now (or was dropped). */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket): Boolean {
        if (released.remove(packet)) return false
        val part = part(packet) ?: return false
        val enabled = if (part == Part.EXPLOSION) cfg.general.implosionExplosionMuted else cfg.general.implosionTeleportMuted
        if (!enabled) return false
        synchronized(held) { held += Held(packet, part, System.currentTimeMillis()) }
        return true
    }

    private fun release(now: Long) {
        val due = ArrayList<ClientboundSoundPacket>()
        synchronized(held) {
            if (held.isEmpty()) return
            held.removeIf { h ->
                (now - h.at >= HOLD_MS).also { if (it) due += h.packet }
            }
        }
        val connection = Minecraft.getInstance().connection ?: return
        for (packet in due) {
            released += packet
            connection.handleSoundEvent(packet)
        }
    }

    private fun isImplosion(packet: ClientboundLevelParticlesPacket): Boolean =
        packet.particle === ParticleTypes.EXPLOSION &&
            packet.count == 8 &&
            packet.maxSpeed == 8f &&
            packet.xDist == 0f && packet.yDist == 0f && packet.zDist == 0f

    private fun part(packet: ClientboundSoundPacket): Part? {
        if (packet.volume != 1f || packet.pitch != 1f) return null
        val id = packet.sound.value().location().toString()
        return if (id == "minecraft:entity.generic.explode" && packet.source === SoundSource.BLOCKS) {
            Part.EXPLOSION
        } else if (id == "minecraft:entity.enderman.teleport" && packet.source === SoundSource.HOSTILE) {
            Part.TELEPORT
        } else {
            null
        }
    }
}
