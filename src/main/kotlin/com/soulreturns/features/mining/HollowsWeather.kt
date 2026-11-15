package com.soulreturns.features.mining

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockLocation
import net.minecraft.core.particles.ParticleTypes
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import net.minecraft.network.protocol.game.ClientboundSoundPacket

/**
 * The Crystal Hollows weather, split into its two halves ("Mute Crystal Hollows weather" and "Hide
 * Crystal Hollows weather visuals", Mining → Weather). Hypixel's own toggle switches audio and visuals
 * off together. Findings and captures: rule://sounds ("Crystal Hollows weather").
 *
 * **Gate: area + packet signature, not the sidebar icon.** The time line's weather icon (Rockfall
 * `U+E09C`) is a private-use glyph Hypixel can retune; the sound and particle packets are exact on
 * their own, and nothing else plays them in the Hollows (no Creakings live there).
 * - Sounds: [mutes], asked by `SoundReplacer` for every sound before its rules.
 * - Particles: [onParticle], from `ClientPacketListenerMixin`, drops the Rockfall's packets, but only
 *   while a weather sound arrived within [ACTIVE_MS]: the particle signature alone is not unique
 *   (falling dust with a block state is a common shape), so the creaking marks the weather as running.
 *   Mining particles are client-side (level event 2001), never `level_particles`, and real explosions
 *   differ in count and spread.
 */
object HollowsWeather {
    const val AREA = "Crystal Hollows"

    /** The weather plays a sound about every 0.7 s (Rockfall) or 0.1 s (Breeze); 5 s bridges any gap. */
    private const val ACTIVE_MS = 5_000L

    /**
     * Weather sounds as `id` + `SoundSource` name, exactly what Hypixel's sound packet carries. Add a pair
     * only with a capture that shows it during the weather and nowhere else in the Hollows.
     *  - Rockfall (EXTREME, 20 min from :35 UTC every 3 hours): `minecraft:entity.creaking.ambient`,
     *    `master`, volume 0.7, about every 0.7 s at random block centers around the player.
     *  - Breeze (mild, 20 min from :35 UTC otherwise): `minecraft:entity.breeze.idle_ground`, `weather`,
     *    volume 0.05, pitch 1.0 to 1.19, about every 0.1 s, with the icon `U+E094` on the time line
     *    (captured in the Hollows, 2026-11-08; the wiki lists the same Breeze for the Dwarven Mines and Glacite Tunnels too).
     */
    private val SOUNDS: Set<Pair<String, String>> =
        setOf(
            "minecraft:entity.creaking.ambient" to "master",
            "minecraft:entity.breeze.idle_ground" to "weather",
        )

    @Volatile
    private var lastWeatherSoundMs = 0L

    fun mutes(
        id: String,
        source: String,
    ): Boolean = cfg.mining.muteHollowsWeather && SkyblockLocation.isInArea(AREA) && (id to source) in SOUNDS

    /** Both packet passes (network thread, then render thread). Only records that the weather is running. */
    @JvmStatic
    fun onSound(packet: ClientboundSoundPacket) {
        if (!cfg.mining.hideHollowsWeatherVisuals) return
        if ((packet.sound.value().location().toString() to packet.source.getName()) in SOUNDS) {
            lastWeatherSoundMs = System.currentTimeMillis()
        }
    }

    /**
     * True drops the particle packet. Captured Rockfall packets (2026-11-08, ~400 of them): `falling_dust`
     * and `block` with a block state (stone, cobblestone, coal_ore, gold_block, gold_ore, ...), count 3,
     * spread 0.5 / 0 / 0.5, speed 1.0; `explosion`, count 5, spread 0.7, speed 0.3, override limiter.
     */
    @JvmStatic
    fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
        if (!cfg.mining.hideHollowsWeatherVisuals) return false
        if (System.currentTimeMillis() - lastWeatherSoundMs > ACTIVE_MS) return false
        if (!SkyblockLocation.isInArea(AREA)) return false
        val type = packet.particle.type
        return if (type === ParticleTypes.FALLING_DUST || type === ParticleTypes.BLOCK) {
            packet.count == 3 && packet.xDist == 0.5f && packet.yDist == 0f && packet.zDist == 0.5f && packet.maxSpeed == 1f
        } else {
            type === ParticleTypes.EXPLOSION &&
                packet.count == 5 && packet.xDist == 0.7f && packet.yDist == 0.7f && packet.zDist == 0.7f && packet.maxSpeed == 0.3f
        }
    }
}
