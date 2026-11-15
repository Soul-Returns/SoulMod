package com.soulreturns.features.mining

import com.soulreturns.config.cfg
import com.soulreturns.data.skyblock.SkyblockState
import net.minecraft.client.Minecraft
import net.minecraft.core.particles.DustParticleOptions
import net.minecraft.network.protocol.game.ClientboundLevelParticlesPacket
import kotlin.math.roundToInt

/**
 * "Hide Powder Coating particles" (Mining › Powder & pickaxe): drops the dust Divan's Powder Coating
 * puts around whoever wears coated Armor of Divan, yours and other players'.
 *
 * Capture (Dwarven Mines, 2026-11-15, `docs/research/commissions-2026-11-15.md`): every 4 ticks a burst
 * of 4 white and 4 gold (`0xFF9900`) `dust` packets, scale 1, count 0, speed 1, override limiter and
 * always-show set; the spread fields repeat the color (white 1/1/1, gold 1/0.6/0), all within 0.55
 * blocks of the wearer horizontally and 0.25-1.25 above
 * their feet. Match = that exact signature **and** a loaded player whose box, grown by [REACH_H] /
 * [REACH_V], holds the particle. The reach covers the server placing the dust at its (older) idea of a
 * moving player. No armor check: other players' equipment carries no SkyBlock id or coating marker.
 * False positives: another source sending this exact white/gold dust shape within a block of a player
 * (none seen) would be hidden too.
 */
object PowderCoatingParticles {
    private const val WHITE = 0xFFFFFF
    private const val GOLD = 0xFF9900
    const val REACH_H = 1.0
    const val REACH_V = 0.5

    /** True drops the packet. Decides on the render-thread pass only, where reading entities is safe. */
    @JvmStatic
    fun onParticle(packet: ClientboundLevelParticlesPacket): Boolean {
        if (!cfg.mining.hidePowderCoatingParticles) return false
        val dust = packet.particle as? DustParticleOptions ?: return false
        val c = dust.color
        val rgb = (c.x() * 255f).roundToInt() shl 16 or ((c.y() * 255f).roundToInt() shl 8) or (c.z() * 255f).roundToInt()
        if (!isCoatingDust(rgb, dust.scale, packet.count, packet.xDist, packet.yDist, packet.zDist, packet.maxSpeed, packet.isOverrideLimiter, packet.alwaysShow())) return false
        val mc = Minecraft.getInstance()
        if (!mc.isSameThread || !SkyblockState.onSkyblock) return false
        val level = mc.level ?: return false
        return level.players().any { p ->
            val b = p.boundingBox
            nearBody(b.minX, b.minY, b.minZ, b.maxX, b.maxY, b.maxZ, packet.x, packet.y, packet.z)
        }
    }

    fun isCoatingDust(
        rgb: Int,
        scale: Float,
        count: Int,
        xDist: Float,
        yDist: Float,
        zDist: Float,
        maxSpeed: Float,
        overrideLimiter: Boolean,
        alwaysShow: Boolean,
    ): Boolean =
        scale == 1f && count == 0 && maxSpeed == 1f && overrideLimiter && alwaysShow &&
            ((rgb == WHITE && xDist == 1f && yDist == 1f && zDist == 1f) ||
                (rgb == GOLD && xDist == 1f && yDist == 0.6f && zDist == 0f))

    fun nearBody(minX: Double, minY: Double, minZ: Double, maxX: Double, maxY: Double, maxZ: Double, x: Double, y: Double, z: Double): Boolean =
        x >= minX - REACH_H && x <= maxX + REACH_H &&
            z >= minZ - REACH_H && z <= maxZ + REACH_H &&
            y >= minY - REACH_V && y <= maxY + REACH_V
}
