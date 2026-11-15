package com.soulreturns.features.mining.metaldetector

import net.minecraft.client.Minecraft
import com.soulreturns.config.cfg
import net.minecraft.core.BlockPos
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.Vec3
import java.util.Locale

/**
 * Draws the solver's answer with vanilla gizmos, collected into the level renderer's per-frame
 * collector right before it is finalized (Fabric `BEFORE_GIZMOS`). Always on top, so the chest is
 * visible through the rock it is buried in.
 */
object MetalDetectorWaypoints {
    /** Fill and "possible" boxes are the configured color at these fractions of its alpha. */
    private const val FILL_ALPHA = 0.3f
    private const val POSSIBLE_ALPHA = 0.5f
    private const val LINE_START = 0.5

    fun render() {
        if (!MetalDetector.isActive()) return
        val sol = MetalDetector.solution ?: return
        val c = MetalDetector.solutionBase()
        val mc = Minecraft.getInstance()
        val camera = mc.gameRenderer.mainCamera()
        val eye = camera.position()

        val md = cfg.mining.metalDetector
        val color = md.waypointColor.effective()
        mc.levelRenderer.collectPerFrameRenderThreadGizmos().use {
            val solved = sol.solved
            if (solved != null) {
                val pos = BlockPos(c[0] + solved.x, c[1] + solved.y, c[2] + solved.z)
                Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(color, md.lineWidth, alpha(color, FILL_ALPHA))).setAlwaysOnTop()
                val target = Vec3.atCenterOf(pos)
                val fwd = camera.forwardVector()
                val start = eye.add(fwd.x() * LINE_START, fwd.y() * LINE_START, fwd.z() * LINE_START)
                Gizmos.line(start, target, color, md.lineWidth).setAlwaysOnTop()
                label("Treasure", pos, eye, color)
            } else {
                val faint = alpha(color, POSSIBLE_ALPHA)
                for (o in sol.possible) {
                    val pos = BlockPos(c[0] + o.x, c[1] + o.y, c[2] + o.z)
                    Gizmos.cuboid(pos, GizmoStyle.strokeAndFill(faint, 1f, alpha(color, FILL_ALPHA * POSSIBLE_ALPHA))).setAlwaysOnTop()
                    label("Possible", pos, eye, faint)
                }
            }
        }
    }

    private fun alpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }

    /**
     * Scaled with eye distance so it stays readable from across the mines. The number is the detector's
     * own measure (player position to the chest's top corner, as in the action bar), so it compares
     * directly with the `TREASURE: Xm` reading.
     */
    private fun label(name: String, pos: BlockPos, eye: Vec3, color: Int) {
        val at = Vec3(pos.x + 0.5, pos.y + 1.6, pos.z + 0.5)
        val scale = maxOf(0.5f, (eye.distanceTo(Vec3.atCenterOf(pos)) * 0.1).toFloat())
        val p = Minecraft.getInstance().player ?: return
        val dist = MetalDetectorSolver.distance(p.x, p.y, p.z, pos.x, pos.y, pos.z)
        val text = name + " " + String.format(Locale.ROOT, "%.1fm", dist)
        Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop()
    }
}
