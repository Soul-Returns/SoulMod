package com.soulreturns.features.mining.metaldetector

import net.minecraft.client.Minecraft
import com.soulreturns.config.cfg
import com.soulreturns.features.mining.TargetWaypoint
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.world.phys.AABB
import java.util.Locale

/**
 * Draws the solver's answer with vanilla gizmos ([TargetWaypoint]), collected into the level
 * renderer's per-frame collector right before it is finalized (Fabric `BEFORE_GIZMOS`). Always on
 * top, so the chest is visible through the rock it is buried in.
 */
object MetalDetectorWaypoints {
    /** "Possible" boxes are the configured color at this fraction of its alpha. */
    private const val POSSIBLE_ALPHA = 0.5f

    fun render() {
        if (!MetalDetector.isActive()) return
        val sol = MetalDetector.solution ?: return
        val c = MetalDetector.solutionBase()
        val mc = Minecraft.getInstance()
        val eye = mc.gameRenderer.mainCamera().position()

        val md = cfg.mining.metalDetector
        val color = md.waypointColor.effective()
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            val solved = sol.solved
            if (solved != null) {
                val pos = BlockPos(c[0] + solved.x, c[1] + solved.y, c[2] + solved.z)
                TargetWaypoint.draw(AABB(pos), color, md.lineWidth, text("Treasure", pos) ?: return@use)
            } else {
                val faint = TargetWaypoint.alpha(color, POSSIBLE_ALPHA)
                for (o in sol.possible) {
                    val pos = BlockPos(c[0] + o.x, c[1] + o.y, c[2] + o.z)
                    Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(faint, 1f, TargetWaypoint.alpha(color, TargetWaypoint.FILL_ALPHA * POSSIBLE_ALPHA))).setAlwaysOnTop()
                    TargetWaypoint.label(text("Possible", pos) ?: return@use, AABB(pos), eye, faint)
                }
            }
        }
    }

    /**
     * The number is the detector's own measure (player position to the chest's top corner, as in the
     * action bar), so it compares directly with the `TREASURE: Xm` reading.
     */
    private fun text(name: String, pos: BlockPos): String? {
        val p = Minecraft.getInstance().player ?: return null
        val dist = MetalDetectorSolver.distance(p.x, p.y, p.z, pos.x, pos.y, pos.z)
        return name + " " + String.format(Locale.ROOT, "%.1fm", dist)
    }
}
