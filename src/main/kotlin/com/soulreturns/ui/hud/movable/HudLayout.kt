package com.soulreturns.ui.hud.movable

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** What [HudLayout] reads about the HUDs; the live HUD and the Move GUI each provide one. */
interface HudSource {
    /** The HUD's transform, or null for an unknown id. */
    fun transform(id: String): HudTransform?

    /** Content size at scale 1 (before [HudTransform.scale]), Soul px. */
    fun size(id: String): FloatArray

    /** Whether the HUD shows (a hidden target makes its followers fall back). */
    fun shown(id: String): Boolean
}

/**
 * A vanilla HUD element in Soul px. [hidden] ones (no scoreboard objective, no boss bar) collapse to
 * [collapsed], a zero-size box where they would be, for a follower that takes their place.
 */
class VanillaElement(
    val id: String,
    val name: String,
    val rect: FloatArray,
    val hidden: Boolean,
    val collapsed: FloatArray,
)

/** Notes the resolver leaves on a box; target strings are [HudLink.target]s. */
class HudBoxInfo(
    val linked: Boolean = false,
    /** The rect it is placed against (when [linked]). */
    val target: FloatArray? = null,
    /** The link closes a cycle: on its screen anchor. */
    val cycle: String? = null,
    /** The target is hidden: on its screen anchor. */
    val hiddenTarget: String? = null,
    /** The hidden target's place was taken (its anchor, this HUD's size). */
    val tookPlaceOf: String? = null,
    /** A hidden vanilla target collapsed to where it would be. */
    val collapsed: String? = null,
    /** No room on its side: shown on the opposite side. */
    val flipped: Boolean = false,
    /** Shift applied to keep it on screen. */
    val clampX: Float = 0f,
    val clampY: Float = 0f,
    val dragging: Boolean = false,
) {
    fun pushed(): Float = kotlin.math.hypot(clampX, clampY)

    companion object {
        val NONE = HudBoxInfo()
    }
}

/** A resolved HUD box in Soul px ([w]/[h] include the HUD's scale). */
class HudBox(val x0: Float, val y0: Float, val w: Float, val h: Float, val info: HudBoxInfo = HudBoxInfo.NONE) {
    val x1: Float get() = x0 + w
    val y1: Float get() = y0 + h

    fun rect(): FloatArray = floatArrayOf(x0, y0, x1, y1)

    fun moved(dx: Float, dy: Float): HudBox = HudBox(x0 + dx, y0 + dy, w, h, info)

    fun at(x: Float, y: Float): HudBox = HudBox(x, y, w, h, info)

    fun contains(x: Float, y: Float): Boolean = x >= x0 && x <= x1 && y >= y0 && y <= y1

    fun withInfo(info: HudBoxInfo): HudBox = HudBox(x0, y0, w, h, info)
}

/**
 * The one position resolver behind the live HUD and the Move GUI: a HUD's link target (or the
 * screen) → its placement → offsets → kept on screen. One instance is one frame: boxes are memoized.
 * [overrides] pins boxes (the HUD being dragged), so followers move with it live.
 */
class HudLayout(
    val screenW: Float,
    val screenH: Float,
    val vanilla: Map<String, VanillaElement>,
    private val source: HudSource,
    private val overrides: Map<String, HudBox> = emptyMap(),
) {
    private val memo = HashMap<String, HudBox>()

    /** The resolved box of HUD [id], or null for an unknown id. */
    fun box(id: String): HudBox? = if (source.transform(id) == null) null else resolve(id, emptyList(), null)

    private class Target(
        val rect: FloatArray? = null,
        val hidden: Boolean = false,
        val cycle: Boolean = false,
        /** A hidden HUD whose place a [HudLinkHidden.TAKE_PLACE] follower takes. */
        val inherit: String? = null,
    )

    private fun targetOf(to: String, stack: List<String>): Target {
        HudTargets.vanillaId(to)?.let { vid ->
            val v = vanilla[vid] ?: return Target(hidden = true)
            return Target(rect = if (v.hidden) v.collapsed else v.rect, hidden = v.hidden)
        }
        val tid = HudTargets.hudId(to) ?: return Target(hidden = true)
        if (source.transform(tid) == null) return Target(hidden = true)
        if (tid in stack) return Target(cycle = true)
        if (!source.shown(tid)) return Target(hidden = true, inherit = tid)
        return Target(rect = resolve(tid, stack, null).rect())
    }

    /**
     * [asT]: resolve with another transform (a hidden target's), keeping this HUD's own size; used by
     * [HudLinkHidden.TAKE_PLACE]. A cycle breaks at the HUD that closes it.
     */
    private fun resolve(id: String, stack: List<String>, asT: HudTransform?): HudBox {
        if (asT == null) {
            memo[id]?.let { return it }
            overrides[id]?.let { pinned ->
                val b = pinned.withInfo(HudBoxInfo(dragging = true))
                memo[id] = b
                return b
            }
        }
        val own = source.transform(id)!!
        val t = asT ?: own
        val size = source.size(id)
        val w = size[0] * own.scale
        val h = size[1] * own.scale
        val link = t.link
        var target: FloatArray? = null
        var cycle: String? = null
        var hiddenTarget: String? = null
        var collapsed: String? = null
        if (link != null) {
            val tg = targetOf(link.target, stack + id)
            val inherit = tg.inherit
            if (tg.cycle) {
                cycle = link.target
            } else if (tg.hidden && link.whenHidden != HudLinkHidden.TAKE_PLACE) {
                hiddenTarget = link.target
            } else if (inherit != null) {
                val taken = resolve(id, stack + id + inherit, source.transform(inherit))
                val b = taken.withInfo(copyInfo(taken.info, tookPlaceOf = link.target))
                if (asT == null) memo[id] = b
                return b
            } else if (tg.rect == null) {
                hiddenTarget = link.target
            } else {
                target = tg.rect
                if (tg.hidden) collapsed = link.target
            }
        }

        var x: Float
        var y: Float
        if (link != null && target != null) {
            x = if (link.follows(HudAxis.X)) {
                HudPlace.place(link.h, target[0], target[2], w) + link.offsetX
            } else if (link.fixed) {
                HudPlace.place(t.hAnchor, 0f, screenW, w) + t.offsetX
            } else {
                anchorAxis(link, HudAxis.X, w, target[0], target[2])
            }
            y = if (link.follows(HudAxis.Y)) {
                HudPlace.place(link.v, target[1], target[3], h) + link.offsetY
            } else if (link.fixed) {
                HudPlace.place(t.vAnchor, 0f, screenH, h) + t.offsetY
            } else {
                anchorAxis(link, HudAxis.Y, h, target[1], target[3])
            }
        } else {
            x = HudPlace.place(t.hAnchor, 0f, screenW, w) + t.offsetX
            y = HudPlace.place(t.vAnchor, 0f, screenH, h) + t.offsetY
        }

        var rawX = x
        var rawY = y
        x = x.coerceIn(0f, max(0f, screenW - w))
        y = y.coerceIn(0f, max(0f, screenH - h))
        // No room on its side: keeping it on screen pushed it onto its target, so it goes to the
        // opposite side (same gap) when that one has room. The stored link is untouched.
        var flipped = false
        val m = if (target != null) link!!.mainAxis else HudAxis.NONE
        if (target != null && m != HudAxis.NONE && overlaps(x, y, x + w, y + h, target)) {
            val g = link!!.gap ?: 0f
            if (m == HudAxis.X) {
                val fx = if (link.h == HudPlace.OUT_START) target[2] + g else target[0] - g - w
                if (fx >= 0f && fx + w <= screenW) {
                    x = fx
                    rawX = fx
                    flipped = true
                }
            } else {
                val fy = if (link.v == HudPlace.OUT_START) target[3] + g else target[1] - g - h
                if (fy >= 0f && fy + h <= screenH) {
                    y = fy
                    rawY = fy
                    flipped = true
                }
            }
        }
        val info =
            HudBoxInfo(
                linked = target != null,
                target = target,
                cycle = cycle,
                hiddenTarget = hiddenTarget,
                collapsed = collapsed,
                flipped = flipped,
                clampX = x - rawX,
                clampY = y - rawY,
            )
        val b = HudBox(x, y, w, h, info)
        if (asT == null) memo[id] = b
        return b
    }

    /** Unglued other axis: relative to the target's anchor point, so it follows its moves but not its growth. */
    private fun anchorAxis(link: HudLink, ax: Int, len: Float, t0: Float, t1: Float): Float {
        val k = linkAnchorK(link.target, ax)
        val off = if (ax == HudAxis.X) link.offsetX else link.offsetY
        return t0 + k * (t1 - t0) / 2f + off - k * len / 2f
    }

    private fun copyInfo(i: HudBoxInfo, tookPlaceOf: String?): HudBoxInfo =
        HudBoxInfo(i.linked, i.target, i.cycle, i.hiddenTarget, tookPlaceOf, i.collapsed, i.flipped, i.clampX, i.clampY, i.dragging)

    /**
     * The point of HUD [id] on axis [ax] that stays put when it grows (0 start, 1 center, 2 end): its
     * screen anchor, or for an attached HUD the edge facing its target / its glued edge / its target's point.
     */
    fun anchorK(id: String, ax: Int, depth: Int = 0): Int {
        val t = source.transform(id) ?: return 0
        val link = t.link
        if (link != null && depth < 8) {
            val m = link.mainAxis
            val k = if (ax == HudAxis.X) link.h else link.v
            if (m == ax) return if (k == HudPlace.OUT_START) 2 else 0
            if (m == HudAxis.NONE || link.follow) return if (k == HudPlace.OUT_START) 2 else if (k == HudPlace.OUT_END) 0 else k
            if (!link.fixed) return linkAnchorK(link.target, ax, depth + 1)
        }
        return if (ax == HudAxis.X) t.hAnchor else t.vAnchor
    }

    /** The anchor point (0/1/2) of a link target on [ax]: a vanilla element's pin, a HUD's [anchorK]. */
    fun linkAnchorK(to: String, ax: Int, depth: Int = 0): Int {
        HudTargets.vanillaId(to)?.let { return VANILLA_ANCHOR[it]?.get(ax) ?: 0 }
        val tid = HudTargets.hudId(to) ?: return 0
        return if (source.transform(tid) == null) 0 else anchorK(tid, ax, depth)
    }

    /** The rect a link target has right now, or null when it has none (hidden HUD, cycle, unknown). */
    fun targetRect(to: String, forId: String): FloatArray? {
        val tg = targetOf(to, listOf(forId))
        if (tg.cycle || tg.rect == null) return null
        return tg.rect
    }

    /** Whether the link target is hidden right now. */
    fun targetHidden(to: String, forId: String): Boolean = targetOf(to, listOf(forId)).hidden

    companion object {
        /** Where each vanilla element is pinned (x, y: 0 start, 1 center, 2 end), so it never moves when it grows. */
        val VANILLA_ANCHOR: Map<String, IntArray> =
            mapOf(
                VanillaIds.HOTBAR to intArrayOf(1, 2),
                VanillaIds.STATUS to intArrayOf(1, 2),
                VanillaIds.ACTION_BAR to intArrayOf(1, 2),
                VanillaIds.CHAT to intArrayOf(0, 2),
                VanillaIds.SCOREBOARD to intArrayOf(2, 1),
                VanillaIds.BOSS_BAR to intArrayOf(1, 0),
            )

        fun overlaps(x0: Float, y0: Float, x1: Float, y1: Float, r: FloatArray): Boolean = x0 < r[2] && x1 > r[0] && y0 < r[3] && y1 > r[1]

        fun overlaps(a: FloatArray, b: FloatArray): Boolean = overlaps(a[0], a[1], a[2], a[3], b)

        /** Round to half px (stored offsets). */
        fun r05(v: Float): Float = (v * 2f).roundToInt() / 2f

        /** Nearest screen region per axis: the edge (or the center) the span [a0, a1] is closest to. */
        fun pickRegion(a0: Float, a1: Float, len: Float): Int {
            val ds = a0
            val de = len - a1
            val dc = abs((a0 + a1) / 2f - len / 2f)
            if (dc <= ds && dc <= de) return HudAnchor.CENTER
            return if (ds <= de) HudAnchor.START else HudAnchor.END
        }

        /**
         * [t] re-derived so its screen anchor puts it at [box]; [reanchor] re-picks the region when
         * [HudTransform.autoAnchor]. Also the link fallback, so it is kept current while attached.
         */
        fun screenFromBox(t: HudTransform, box: HudBox, screenW: Float, screenH: Float, reanchor: Boolean): HudTransform {
            var h = t.hAnchor
            var v = t.vAnchor
            if (reanchor && t.autoAnchor) {
                h = pickRegion(box.x0, box.x1, screenW)
                v = pickRegion(box.y0, box.y1, screenH)
            }
            return t.copy(
                hAnchor = h,
                vAnchor = v,
                offsetX = r05(box.x0 - HudPlace.place(h, 0f, screenW, box.w)),
                offsetY = r05(box.y0 - HudPlace.place(v, 0f, screenH, box.h)),
            )
        }
    }
}

/** Ids of the vanilla elements a HUD can attach to. */
object VanillaIds {
    const val HOTBAR = "hotbar"
    const val STATUS = "status"
    const val ACTION_BAR = "actionbar"
    const val CHAT = "chat"
    const val SCOREBOARD = "scoreboard"
    const val BOSS_BAR = "bossbar"

    val ALL = listOf(HOTBAR, STATUS, ACTION_BAR, CHAT, SCOREBOARD, BOSS_BAR)

    fun name(id: String): String =
        when (id) {
            HOTBAR -> "Hotbar"
            STATUS -> "Health / food rows"
            ACTION_BAR -> "Action bar"
            CHAT -> "Chat"
            SCOREBOARD -> "Scoreboard"
            BOSS_BAR -> "Boss bar"
            else -> id
        }
}
