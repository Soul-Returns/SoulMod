package com.soulreturns.features.dungeons.boss

import com.soulreturns.config.cfg
import net.minecraft.client.Minecraft
import net.minecraft.core.Direction
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.Vec3
import kotlin.math.cos
import kotlin.math.sin

/**
 * In-world parts, as vanilla gizmos in the level renderer's per-frame collector (Fabric
 * `BEFORE_GIZMOS`): while a crush window is armed its pillar's 7×7 area on the floor, outlined in
 * the pillar's color with a faint tint (mocks/boss-timers/ §7), and the experimental bait spot
 * (ring, beam, label). Fixed areas only; nothing targets an entity.
 */
object StormWorld {
    private const val FLOOR_Y = 169.03
    private const val RING_R = 1.5
    private const val BEAM_TOP = 182.0
    private const val MARK_WIDTH = 3f
    /** The tint's opacity (the mock's fill-opacity .12). */
    private const val TINT = 0x1F

    fun render() {
        val m = F7Boss.model ?: return
        if (m.phase != BossPhase.STORM) return
        val b = cfg.dungeons.boss
        val mc = Minecraft.getInstance()
        val now = m.now
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            if (b.crushWindow && b.showWindow && b.markPillar) {
                for (p in m.armed(now)) mark(p.pillar)
            }
            if (b.crushWindow && b.showArm) m.bait(now)?.let { baitMarker(it) }
        }
    }

    private fun mark(p: Pillar) {
        val color = CrosshairTimers.pillarColor(p)
        val x0 = p.x0.toDouble()
        val z0 = p.z0.toDouble()
        val x1 = p.x1.toDouble()
        val z1 = p.z1.toDouble()
        Gizmos.rect(Vec3(x0, FLOOR_Y, z0), Vec3(x1, FLOOR_Y, z1), Direction.UP, GizmoStyle.fill((TINT shl 24) or (color and 0x00FFFFFF)))
        val corners = listOf(Vec3(x0, FLOOR_Y, z0), Vec3(x1, FLOOR_Y, z0), Vec3(x1, FLOOR_Y, z1), Vec3(x0, FLOOR_Y, z1))
        for (i in corners.indices) Gizmos.line(corners[i], corners[(i + 1) % 4], color, MARK_WIDTH).setAlwaysOnTop()
    }

    /** Dim while you still arm the pillar, bright after its step: "bounce back, stand here". */
    private fun baitMarker(v: BaitView) {
        val spot = v.spot
        val there = v.playerToSpot.let { it != null && it <= RING_R }
        val color = if (v.stage == BaitView.ARM) WAIT else if (there) HERE else SPOT
        val segs = 24
        for (i in 0 until segs) {
            val a0 = i * 2 * Math.PI / segs
            val a1 = (i + 1) * 2 * Math.PI / segs
            Gizmos.line(
                Vec3(spot[0] + RING_R * cos(a0), FLOOR_Y, spot[2] + RING_R * sin(a0)),
                Vec3(spot[0] + RING_R * cos(a1), FLOOR_Y, spot[2] + RING_R * sin(a1)),
                color, 2.5f,
            ).setAlwaysOnTop()
        }
        Gizmos.line(Vec3(spot[0], FLOOR_Y, spot[2]), Vec3(spot[0], BEAM_TOP, spot[2]), color, 2.5f).setAlwaysOnTop()
        val label = if (v.stage == BaitView.ARM) "Bait spot (after the ${v.pillar.name} step)" else "Bait: stand here"
        Gizmos.billboardText(label, Vec3(spot[0], BEAM_TOP + 0.6, spot[2]), TextGizmo.Style.forColorAndCentered(color).withScale(0.6f)).setAlwaysOnTop()
    }

    private const val HERE = 0xFF55FF55.toInt()
    private const val SPOT = 0xFF55DDFF.toInt()
    private const val WAIT = 0xFFFFD255.toInt()
}
