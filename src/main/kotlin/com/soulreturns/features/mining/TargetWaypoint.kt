package com.soulreturns.features.mining

import net.minecraft.client.Minecraft
import net.minecraft.gizmos.GizmoStyle
import net.minecraft.gizmos.Gizmos
import net.minecraft.gizmos.TextGizmo
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

/**
 * The mining solvers' target look (Metal Detector treasure, corpse locator): a filled box, a line from
 * the crosshair to its center and a billboard label above it, all vanilla gizmos drawn on top of the
 * world. Call inside `levelRenderer.collectPerFrameRenderThreadGizmos()` from Fabric `BEFORE_GIZMOS`.
 */
object TargetWaypoint {
    /** The fill is the stroke color at this fraction of its alpha. */
    const val FILL_ALPHA = 0.3f
    private const val LINE_START = 0.5

    fun draw(box: AABB, color: Int, lineWidth: Float, text: String, line: Boolean = true) {
        val camera = Minecraft.getInstance().gameRenderer.mainCamera()
        val eye = camera.position()
        Gizmos.cuboid(box, GizmoStyle.strokeAndFill(color, lineWidth, alpha(color, FILL_ALPHA))).setAlwaysOnTop()
        if (line) {
            val fwd = camera.forwardVector()
            val start = eye.add(fwd.x() * LINE_START, fwd.y() * LINE_START, fwd.z() * LINE_START)
            Gizmos.line(start, box.center, color, lineWidth).setAlwaysOnTop()
        }
        label(text, box, eye, color)
    }

    /** Above the box, scaled with eye distance so it stays readable from afar. */
    fun label(text: String, box: AABB, eye: Vec3, color: Int) {
        val center = box.center
        val at = Vec3(center.x, box.maxY + 0.6, center.z)
        val scale = maxOf(0.5f, (eye.distanceTo(center) * 0.1).toFloat())
        Gizmos.billboardText(text, at, TextGizmo.Style.forColorAndCentered(color).withScale(scale)).setAlwaysOnTop()
    }

    fun alpha(argb: Int, factor: Float): Int {
        val a = ((argb ushr 24) * factor).toInt().coerceIn(0, 255)
        return (a shl 24) or (argb and 0xFFFFFF)
    }
}
