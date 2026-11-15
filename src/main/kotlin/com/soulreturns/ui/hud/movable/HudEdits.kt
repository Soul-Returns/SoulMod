package com.soulreturns.ui.hud.movable

import com.soulreturns.ui.hud.movable.HudLayout.Companion.r05
import com.soulreturns.ui.hud.movable.HudLayout.Companion.screenFromBox
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sign

/** A writable [HudSource] the Move GUI edits through (the real HUDs in game, a map in tests). */
interface HudEditStore : HudSource {
    /** Every known HUD id, in draw order. */
    fun ids(): List<String>

    fun set(id: String, transform: HudTransform)

    /** A fresh resolve of the current transforms. */
    fun layout(): HudLayout
}

/**
 * Every position edit the Move GUI makes, independent of the screen so it is unit-tested. Each one
 * keeps the HUD where the player put it and re-derives the stored values (link offsets, screen
 * anchor) from that box.
 */
class HudEdits(private val store: HudEditStore, private val linkGap: () -> Float) {
    private fun t(id: String): HudTransform = store.transform(id)!!

    /** Link offset on axis [ax] that puts the HUD at [box] for target rect [target], per that axis's mode. */
    private fun linkOffset(link: HudLink, box: HudBox, target: FloatArray, ax: Int, layout: HudLayout): HudLink {
        val a0 = if (ax == HudAxis.X) box.x0 else box.y0
        val len = if (ax == HudAxis.X) box.w else box.h
        val t0 = if (ax == HudAxis.X) target[0] else target[1]
        val t1 = if (ax == HudAxis.X) target[2] else target[3]
        val off =
            if (link.follows(ax)) {
                r05(a0 - HudPlace.place(if (ax == HudAxis.X) link.h else link.v, t0, t1, len))
            } else if (!link.fixed) {
                // Same-side points: the HUD's point at the target's anchor fraction, measured from the target's anchor point.
                val k = layout.linkAnchorK(link.target, ax)
                r05(a0 + k * len / 2f - (t0 + k * (t1 - t0) / 2f))
            } else {
                return link
            }
        return if (ax == HudAxis.X) link.copy(offsetX = off) else link.copy(offsetY = off)
    }

    /** The rect [link]'s target has for [id] now, or null when there is nothing to measure against. */
    private fun liveTarget(id: String, link: HudLink, layout: HudLayout): FloatArray? {
        val rect = layout.targetRect(link.target, id) ?: return null
        if (layout.targetHidden(link.target, id) && link.whenHidden != HudLinkHidden.TAKE_PLACE) return null
        return rect
    }

    /** Keep [id] at [box]: the link offsets (if it follows something) and the screen anchor follow. */
    fun placeAt(id: String, box: HudBox, reanchor: Boolean) {
        val layout = store.layout()
        var t = t(id)
        val link = t.link
        if (link != null) {
            val rect = liveTarget(id, link, layout)
            if (rect != null) {
                var l = linkOffset(link, box, rect, HudAxis.X, layout)
                l = linkOffset(l, box, rect, HudAxis.Y, layout)
                t = t.copy(link = l.sanitized())
            }
        }
        store.set(id, screenFromBox(t, box, layout.screenW, layout.screenH, reanchor))
    }

    /** Move [id]'s resolved box by ([dx], [dy]) and keep it there (nudge, typed position). */
    fun moveBy(id: String, dx: Float, dy: Float, reanchor: Boolean) {
        val b = store.layout().box(id) ?: return
        placeAt(id, b.moved(dx, dy), reanchor)
    }

    /**
     * The default ("auto") glue rule: an attached HUD lined up exactly with one of the target's edges
     * on the other axis follows that edge too (glued at the corner, growing away from it); lined up
     * with the target's center it follows the center; otherwise only the side's axis follows.
     */
    fun autoGlue(link: HudLink, box: HudBox, target: FloatArray): HudLink {
        val m = link.mainAxis
        if (m == HudAxis.NONE) return link
        val a0 = if (m == HudAxis.X) box.y0 else box.x0
        val a1 = if (m == HudAxis.X) box.y1 else box.x1
        val t0 = if (m == HudAxis.X) target[1] else target[0]
        val t1 = if (m == HudAxis.X) target[3] else target[2]
        val k =
            if (abs(a0 - t0) <= 0.5f) HudPlace.START
            else if (abs(a1 - t1) <= 0.5f) HudPlace.END
            else if (abs(a0 + a1 - t0 - t1) <= 1f) HudPlace.CENTER
            else null
        if (k == null) return link.copy(follow = false)
        return if (m == HudAxis.X) link.copy(follow = true, v = k, offsetY = 0f) else link.copy(follow = true, h = k, offsetX = 0f)
    }

    /** Attach [id] to [to] (rect [rect]) at [box] on side [h]/[v], like a drop: auto glue, default rules. */
    fun attachTo(id: String, to: String, rect: FloatArray, h: Int, v: Int, box: HudBox) {
        val t = t(id)
        val prev = t.link?.takeIf { it.target == to }
        val layout = store.layout()
        var link = HudLink(target = to, h = h, v = v, whenHidden = prev?.whenHidden ?: HudLinkHidden.SCREEN)
        link = autoGlue(link, box, rect)
        link = linkOffset(link, box, rect, HudAxis.X, layout)
        link = linkOffset(link, box, rect, HudAxis.Y, layout)
        store.set(id, screenFromBox(t.copy(link = link.sanitized()), box, layout.screenW, layout.screenH, true))
    }

    /** Detach: the HUD stays where it is, on its own screen anchor. */
    fun detach(id: String) {
        val t = t(id)
        if (t.link == null) return
        val layout = store.layout()
        val b = layout.box(id) ?: return
        store.set(id, screenFromBox(t.copy(link = null), b, layout.screenW, layout.screenH, true))
    }

    /**
     * Advanced target pick (dropdown or crosshair): flush outside the side needing the shortest move
     * that stays on screen, plus the default gap. On the other axis it keeps its place if it overlaps
     * the target's span, else aligns to the nearest edge (and then glues there, like a drop).
     * Returns false when the target had nothing to measure against (the link waits for it to show).
     */
    fun setLinkTarget(id: String, to: String?): Boolean {
        if (to == null) {
            detach(id)
            return false
        }
        val layout = store.layout()
        val t = t(id)
        val b = layout.box(id) ?: return false
        val r = layout.targetRect(to, id)
        val gap = linkGap()
        if (r == null || layout.targetHidden(to, id)) {
            store.set(id, t.copy(link = HudLink(target = to, h = HudPlace.OUT_END, v = HudPlace.START, offsetX = gap)))
            return false
        }
        val ovX = minOf(b.x1, r[2]) - maxOf(b.x0, r[0])
        val ovY = minOf(b.y1, r[3]) - maxOf(b.y0, r[1])
        class Cand(val h: Int, val v: Int, val x0: Float, val y0: Float, val cost: Float)
        val cands = ArrayList<Cand>(4)
        for (side in listOf(HudPlace.OUT_START, HudPlace.OUT_END)) {
            val x0 = if (side == HudPlace.OUT_START) r[0] - gap - b.w else r[2] + gap
            val v = HudSnap.alignAxis(b.y0, b.y1, r[1], r[3])
            val y0 = if (ovY <= 0f) HudPlace.place(v, r[1], r[3], b.h) else b.y0
            cands += Cand(side, v, x0, y0, cost(x0, y0, b, layout))
        }
        for (side in listOf(HudPlace.OUT_START, HudPlace.OUT_END)) {
            val y0 = if (side == HudPlace.OUT_START) r[1] - gap - b.h else r[3] + gap
            val h = HudSnap.alignAxis(b.x0, b.x1, r[0], r[2])
            val x0 = if (ovX <= 0f) HudPlace.place(h, r[0], r[2], b.w) else b.x0
            cands += Cand(h, side, x0, y0, cost(x0, y0, b, layout))
        }
        val c = cands.minBy { it.cost }
        attachTo(id, to, r, c.h, c.v, b.at(c.x0, c.y0))
        return true
    }

    private fun cost(x0: Float, y0: Float, b: HudBox, layout: HudLayout): Float {
        val onScreen = x0 >= -0.01f && y0 >= -0.01f && x0 + b.w <= layout.screenW + 0.01f && y0 + b.h <= layout.screenH + 0.01f
        return hypot(x0 - b.x0, y0 - b.y0) + if (onScreen) 0f else 1e6f
    }

    /** Advanced "Along x/y": auto (glue rule), follow (position and growth) or fixed; the HUD stays where it is. */
    fun setFollowMode(id: String, mode: Int) {
        val layout = store.layout()
        val t = t(id)
        var link = t.link ?: return
        val b = layout.box(id) ?: return
        val r = layout.targetRect(link.target, id)
        val ax = if (link.mainAxis == HudAxis.X) HudAxis.Y else HudAxis.X
        link = link.copy(auto = mode == FOLLOW_AUTO, fixed = mode == FOLLOW_FIXED)
        if (mode == FOLLOW_AUTO) {
            if (r != null) link = autoGlue(link, b, r)
        } else if (mode == FOLLOW_FIXED) {
            link = link.copy(follow = false)
        } else {
            link = link.copy(follow = true)
            if (r != null && ax == HudAxis.Y) link = link.copy(v = HudSnap.alignAxis(b.y0, b.y1, r[1], r[3]))
            else if (r != null) link = link.copy(h = HudSnap.alignAxis(b.x0, b.x1, r[0], r[2]))
        }
        if (r != null) link = linkOffset(link, b, r, ax, layout)
        store.set(id, screenFromBox(t.copy(link = link), b, layout.screenW, layout.screenH, true))
    }

    /** An attached HUD moved along its edge to [box]: only the other axis changes (glue, shift or screen anchor). */
    fun placeAlong(id: String, box: HudBox, target: FloatArray) {
        val layout = store.layout()
        val t = t(id)
        var link = t.link ?: return
        if (link.auto) link = autoGlue(link, box, target)
        link = linkOffset(link, box, target, if (link.mainAxis == HudAxis.X) HudAxis.Y else HudAxis.X, layout)
        store.set(id, screenFromBox(t.copy(link = link), box, layout.screenW, layout.screenH, true))
    }

    /** Arrows on an attached HUD: along the edge they shift it, toward / away they change the gap (≥ 0). */
    fun nudgeLinked(id: String, dx: Float, dy: Float): Boolean {
        val layout = store.layout()
        val b = layout.box(id) ?: return false
        val link = t(id).link
        val target = b.info.target
        val m = if (link != null && b.info.linked) link.mainAxis else HudAxis.NONE
        if (link == null || target == null || m == HudAxis.NONE) return false
        val across = if (m == HudAxis.X) dx else dy
        val along = if (m == HudAxis.X) dy else dx
        if (across != 0f) {
            // Measured on screen, so a HUD shown on the opposite side (no room) still moves the way you press.
            val away =
                if (m == HudAxis.X) sign(b.x0 + b.x1 - target[0] - target[2]) else sign(b.y0 + b.y1 - target[1] - target[3])
            store.set(id, t(id).copy(link = link.withGap((link.gap ?: 0f) + across * (if (away == 0f) 1f else away))))
        }
        if (along != 0f) placeAlong(id, if (m == HudAxis.X) b.moved(0f, along) else b.moved(along, 0f), target)
        return true
    }

    /** Advanced screen-anchor grid: re-anchor keeping the HUD where it is; auto re-picking turns off. */
    fun setScreenAnchor(id: String, h: Int, v: Int) {
        val layout = store.layout()
        val b = layout.box(id) ?: return
        val t = t(id).copy(hAnchor = h, vAnchor = v, autoAnchor = false)
        store.set(id, screenFromBox(t, b, layout.screenW, layout.screenH, false))
    }

    fun toggleAutoAnchor(id: String) {
        val layout = store.layout()
        val t = t(id).let { it.copy(autoAnchor = !it.autoAnchor) }
        val b = layout.box(id)
        store.set(id, if (t.autoAnchor && b != null) screenFromBox(t, b, layout.screenW, layout.screenH, true) else t)
    }

    /** Inspector link fields: [GAP], [SHIFT] (the glued axis's offset), [LINK_X]/[LINK_Y] (on top of it). */
    fun setLinkField(id: String, field: Int, value: Float) {
        val t = t(id)
        var link = t.link ?: return
        link =
            when (field) {
                GAP -> link.withGap(value)
                // An exact shift is an override: the auto glue rule no longer decides this link.
                SHIFT -> if (link.mainAxis == HudAxis.X) link.copy(auto = false, offsetY = value) else link.copy(auto = false, offsetX = value)
                LINK_X -> link.copy(offsetX = value)
                else -> link.copy(offsetY = value)
            }
        store.set(id, t.copy(link = link.sanitized()))
        val layout = store.layout()
        val b = layout.box(id) ?: return
        store.set(id, screenFromBox(t(id), b, layout.screenW, layout.screenH, true))
    }

    /** Every HUD's anchor point per axis, taken before an edit for [rebaseFollowers]. */
    fun anchorKs(): Map<String, IntArray> {
        val layout = store.layout()
        return store.ids().associateWith { intArrayOf(layout.anchorK(it, HudAxis.X), layout.anchorK(it, HudAxis.Y)) }
    }

    /**
     * After an edit: a target whose anchor point changed (re-picked edge on drop, attached, detached,
     * glued…) would make its position-following HUDs jump, so their offsets are rebased onto the new
     * point. Both ends are measured at the same fraction k, hence the shift (k1 − k0)·(own − target)/2.
     */
    fun rebaseFollowers(before: Map<String, IntArray>) {
        val layout = store.layout()
        for (id in store.ids()) {
            val t = store.transform(id) ?: continue
            var link = t.link ?: continue
            val tid = HudTargets.hudId(link.target) ?: continue
            val ks = before[tid] ?: continue
            if (store.transform(tid) == null) continue
            var changed = false
            for (ax in intArrayOf(HudAxis.X, HudAxis.Y)) {
                if (link.follows(ax) || link.fixed) continue
                val k0 = ks[ax]
                val k1 = layout.anchorK(tid, ax)
                if (k0 == k1) continue
                val own = store.size(id)[ax] * t.scale
                val other = store.size(tid)[ax] * t(tid).scale
                val shift = (k1 - k0) * (own - other) / 2f
                link = if (ax == HudAxis.X) link.copy(offsetX = link.offsetX + shift) else link.copy(offsetY = link.offsetY + shift)
                changed = true
            }
            if (changed) store.set(id, t.copy(link = link))
        }
    }

    /** Does [id] follow [target], directly or through a chain? */
    fun dependsOn(id: String, target: String): Boolean {
        var cur: String? = id
        val seen = HashSet<String>()
        while (cur != null && seen.add(cur)) {
            val next = store.transform(cur)?.link?.let { HudTargets.hudId(it.target) } ?: return false
            if (next == target) return true
            cur = next
        }
        return false
    }

    /** HUDs attached to [id] directly. */
    fun directFollowers(id: String, among: List<String>): List<String> =
        among.filter { store.transform(it)?.link?.target == HudTargets.hud(id) }

    companion object {
        const val FOLLOW_AUTO = 0
        const val FOLLOW_ON = 1
        const val FOLLOW_FIXED = 2

        const val GAP = 0
        const val SHIFT = 1
        const val LINK_X = 2
        const val LINK_Y = 3

        fun followMode(link: HudLink): Int = if (link.auto) FOLLOW_AUTO else if (link.follow) FOLLOW_ON else FOLLOW_FIXED
    }
}

/** Snapping and drag-to-attach math of the Move GUI (pure). */
object HudSnap {
    /** Within this many px of the default gap (or overlapping by up to it) a drop attaches. */
    const val LINK_PX = 6f

    /** An attached HUD dragged this far off its target's edge detaches. */
    const val UNLINK_PX = 12f

    /** Magnet distance for alignment guides and edge glue. */
    const val SNAP_PX = 5f

    /** Which of the target span's points (0 start, 1 center, 2 end) [a0, a1] lines up with best. */
    fun alignAxis(a0: Float, a1: Float, t0: Float, t1: Float): Int {
        val ds = abs(a0 - t0)
        val dc = abs((a0 + a1) / 2f - (t0 + t1) / 2f)
        val de = abs(a1 - t1)
        return if (ds <= dc && ds <= de) 0 else if (dc <= de) 1 else 2
    }

    /**
     * The shift that puts the span's start, center or end on the nearest target within [SNAP_PX], or
     * null. Every target the snapped span then touches goes into [guides].
     */
    fun snapAxis(start: Float, len: Float, targets: List<Float>, guides: MutableList<Float>): Float? {
        val edges = floatArrayOf(start, start + len / 2f, start + len)
        var best: Float? = null
        for (e in edges) for (t in targets) {
            val d = t - e
            if (abs(d) <= SNAP_PX && (best == null || abs(d) < abs(best))) best = d
        }
        val shift = best ?: return null
        for (e in edges) for (t in targets) if (abs(t - (e + shift)) < 0.5f && t !in guides) guides.add(t)
        return shift
    }

    /**
     * [a0, a0+len] within [SNAP_PX] of the target span's start edge, center or end edge →
     * (new a0, 0|1|2, guide position). The nearest wins; on a tie (same size: all three line up at
     * once) an edge beats the center, so equal-width HUDs glue at the corner.
     */
    fun glueSnap(a0: Float, len: Float, t0: Float, t1: Float): FloatArray? {
        val mid = (t0 + t1) / 2f
        val c =
            listOf(
                floatArrayOf(abs(a0 - t0), 0f, t0, t0),
                floatArrayOf(abs(a0 + len - t1), 2f, t1 - len, t1),
                floatArrayOf(abs(a0 + len / 2f - mid), 1f, mid - len / 2f, mid),
            ).filter { it[0] <= SNAP_PX }
                .sortedWith { p, q ->
                    if (abs(p[0] - q[0]) < 0.01f) (if (p[1] == 1f) 1 else 0) - (if (q[1] == 1f) 1 else 0) else p[0].compareTo(q[0])
                }
        val best = c.firstOrNull() ?: return null
        return floatArrayOf(best[2], best[1], best[3])
    }

    /** A drop target for drag-to-attach. */
    class Candidate(val to: String, val rect: FloatArray)

    /** The side a dragged box would attach on: [h]/[v] as in [HudLink], [gap] measured now. */
    class Attach(val to: String, val rect: FloatArray, var h: Int, var v: Int, val gap: Float, val score: Float) {
        /** 0/1/2 when the other axis glues (edge or center), else null. */
        var glue: Int? = null
    }

    /**
     * The element [b] sits next to: within [LINK_PX] of the default gap [gap] (or overlapping it by
     * up to [LINK_PX]), overlapping on the other axis. The drop then puts it exactly one gap away.
     */
    fun findLink(b: HudBox, candidates: List<Candidate>, gap: Float): Attach? {
        var best: Attach? = null
        fun consider(c: Candidate, h: Int, v: Int, g: Float) {
            if (g < -LINK_PX || g > gap + LINK_PX) return
            val score = abs(g - gap)
            if (best == null || score < best!!.score) best = Attach(c.to, c.rect, h, v, g, score)
        }
        for (c in candidates) {
            val r = c.rect
            val ovX = minOf(b.x1, r[2]) - maxOf(b.x0, r[0])
            val ovY = minOf(b.y1, r[3]) - maxOf(b.y0, r[1])
            if (ovX > 2f) {
                consider(c, alignAxis(b.x0, b.x1, r[0], r[2]), HudPlace.OUT_END, b.y0 - r[3])
                consider(c, alignAxis(b.x0, b.x1, r[0], r[2]), HudPlace.OUT_START, r[1] - b.y1)
            }
            if (ovY > 2f) {
                consider(c, HudPlace.OUT_END, alignAxis(b.y0, b.y1, r[1], r[3]), b.x0 - r[2])
                consider(c, HudPlace.OUT_START, alignAxis(b.y0, b.y1, r[1], r[3]), r[0] - b.x1)
            }
        }
        return best
    }

    /** Whole Soul px for an unsnapped axis. */
    fun whole(v: Float): Float = v.roundToInt().toFloat()
}
