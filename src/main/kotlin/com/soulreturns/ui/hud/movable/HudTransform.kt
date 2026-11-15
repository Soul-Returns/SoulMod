package com.soulreturns.ui.hud.movable

import kotlinx.serialization.Serializable

/**
 * Position + size of a movable HUD, resolved by [HudLayout].
 *
 * **Screen anchor**: the HUD keeps a pixel distance ([offsetX]/[offsetY]) from one screen region per
 * axis: left / center / right ([hAnchor]) and top / middle / bottom ([vAnchor]), so its margin stays
 * the same at any window size. With [autoAnchor] every drop re-picks the region nearest to the HUD.
 * The resolved box is always clamped on screen; the stored values are kept for when there is room.
 *
 * **Link** (optional): the HUD follows another HUD or a vanilla element instead ([HudLink]); the
 * screen anchor is then only its fallback (target hidden, link cycle) and the base of a fixed axis.
 *
 * [scale] multiplies the global [com.soulreturns.ui.SoulUi] scale. Anchors and sides are ints, not
 * enums: the project avoids `when (enumValue)` (synthetic `$WhenMappings` fails under Fabric's
 * classloader) and ints keep the JSON stable.
 *
 * Everything is in Soul-logical px, the space HUDs and the Move GUI lay out in.
 */
@Serializable
data class HudTransform(
    /** Screen region on x: 0 = left, 1 = center, 2 = right. See [HudAnchor]. */
    val hAnchor: Int = HudAnchor.START,
    /** Screen region on y: 0 = top, 1 = middle, 2 = bottom. */
    val vAnchor: Int = HudAnchor.START,
    /** Distance from the region: box left − region's placement of the box (negative from the right). */
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    /** Re-pick the nearest region on every drop (off once the player picks one in the anchor grid). */
    val autoAnchor: Boolean = true,
    val scale: Float = 1f,
    /**
     * Per-HUD font-weight override: 0 = Default (follow `general.hudBoldFont`), 1 = Normal, 2 = Bold.
     * See [HudFontMode]; resolved by [com.soulreturns.ui.hud.movable.MovableHud.boldFont].
     */
    val fontMode: Int = HudFontMode.DEFAULT,
    /**
     * Per-HUD font-family override: 0 = Default (follow `general.fontChoice`), else 1 = Baloo 2,
     * 2 = Nunito, 3 = Inter, 4 = Minecraft. See [HudFontChoice].
     */
    val fontChoice: Int = HudFontChoice.DEFAULT,
    /** Per-HUD text-shadow override: 0 = Default (follow `general.hudTextShadow`), 1 = On, 2 = Off. */
    val textShadowMode: Int = HudTextShadowMode.DEFAULT,
    /** The element this HUD is attached to, or null for the screen anchor alone. */
    val link: HudLink? = null,
)

/**
 * "Attached to [target]": the HUD sits on side [h]/[v] of the target's rect (see [HudPlace]: one of
 * them outside = left/right of it or above/below it; both inside = on top of it) plus the link
 * offsets. Its **side's axis** follows the target's position and size. The other axis:
 * - [follow] (glued to an edge or the center, set by the auto rule when lined up on drop): placed
 *   against that edge too, so it follows the target's position and size;
 * - otherwise it keeps its distance to the target's anchor point (the point that stays put when the
 *   target grows), following its moves but not its growth;
 * - [fixed]: that axis ignores the target and uses the HUD's own screen anchor.
 *
 * A linked HUD never overlaps its target: with no room on its side it shows on the opposite side
 * (same gap) until there is room again. A cycle is broken at the HUD that closes it.
 */
@Serializable
data class HudLink(
    /** `hud:<id>` or `vanilla:<id>` (see [HudTargets]). */
    val target: String,
    val h: Int = HudPlace.OUT_END,
    val v: Int = HudPlace.START,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val follow: Boolean = false,
    /** The glue rule decides [follow] on each drop ("Auto" in the inspector). */
    val auto: Boolean = true,
    val fixed: Boolean = false,
    /** What happens while the target is hidden; see [HudLinkHidden]. */
    val whenHidden: Int = HudLinkHidden.SCREEN,
) {
    /** The axis the side sits on: [HudAxis.X] left/right of the target, [HudAxis.Y] above/below, [HudAxis.NONE] on top. */
    val mainAxis: Int get() = if (HudPlace.isOut(h)) HudAxis.X else if (HudPlace.isOut(v)) HudAxis.Y else HudAxis.NONE

    /** Whether axis [ax] is placed against the target's edges (follows its position AND size). */
    fun follows(ax: Int): Boolean {
        val m = mainAxis
        return if (ax == HudAxis.X) m != HudAxis.Y || follow else m != HudAxis.X || follow
    }

    /** Distance to the target on the side's axis; null on top of it. */
    val gap: Float?
        get() =
            if (mainAxis == HudAxis.X) kotlin.math.abs(offsetX) else if (mainAxis == HudAxis.Y) kotlin.math.abs(offsetY) else null

    fun withGap(g: Float): HudLink {
        val gap = g.coerceAtLeast(0f)
        return if (mainAxis == HudAxis.X) copy(offsetX = if (h == HudPlace.OUT_START) -gap else gap)
        else if (mainAxis == HudAxis.Y) copy(offsetY = if (v == HudPlace.OUT_START) -gap else gap)
        else this
    }

    /** The offset on the side's axis only points away from the target, so it never overlaps it. */
    fun sanitized(): HudLink =
        if (mainAxis == HudAxis.X) copy(offsetX = if (h == HudPlace.OUT_START) minOf(0f, offsetX) else maxOf(0f, offsetX))
        else if (mainAxis == HudAxis.Y) copy(offsetY = if (v == HudPlace.OUT_START) minOf(0f, offsetY) else maxOf(0f, offsetY))
        else this
}

/** Axis indices. */
object HudAxis {
    const val NONE = -1
    const val X = 0
    const val Y = 1
}

/**
 * Placement of a span on a target span [t0, t1]: outside before it, its start, center, end, outside
 * after it. 0/1/2 double as the screen regions of [HudTransform.hAnchor]/[HudTransform.vAnchor].
 */
object HudPlace {
    const val OUT_START = -1
    const val START = 0
    const val CENTER = 1
    const val END = 2
    const val OUT_END = 3

    fun isOut(k: Int): Boolean = k == OUT_START || k == OUT_END

    fun place(k: Int, t0: Float, t1: Float, len: Float): Float =
        when (k) {
            OUT_START -> t0 - len
            START -> t0
            CENTER -> (t0 + t1) / 2f - len / 2f
            END -> t1 - len
            else -> t1
        }
}

/** Values of [HudLink.whenHidden]. */
object HudLinkHidden {
    /** Use the HUD's own screen anchor. */
    const val SCREEN = 0

    /** Take the target's place: its anchor (a hidden vanilla element collapses to where it would be). */
    const val TAKE_PLACE = 1

    val LABELS = listOf("Use its screen anchor", "Take the target's place")
}

/** Link target ids: `hud:<MovableHud.id>` or `vanilla:<VanillaHudLayout id>`. */
object HudTargets {
    const val HUD = "hud:"
    const val VANILLA = "vanilla:"

    fun hud(id: String): String = HUD + id

    fun vanilla(id: String): String = VANILLA + id

    /** The HUD id of a `hud:` target, else null. */
    fun hudId(target: String): String? = if (target.startsWith(HUD)) target.substring(HUD.length) else null

    /** The vanilla element id of a `vanilla:` target, else null. */
    fun vanillaId(target: String): String? = if (target.startsWith(VANILLA)) target.substring(VANILLA.length) else null
}

/** Named values for [HudTransform.textShadowMode]. */
object HudTextShadowMode {
    const val DEFAULT = 0 // follow the global general.hudTextShadow setting
    const val ON = 1
    const val OFF = 2

    val LABELS = listOf("Default", "On", "Off")
}

/** Named values for [HudTransform.fontMode]. */
object HudFontMode {
    const val DEFAULT = 0 // follow the global general.hudBoldFont setting
    const val NORMAL = 1
    const val BOLD = 2

    val LABELS = listOf("Default", "Normal", "Bold")
}

/**
 * Named values for [HudTransform.fontChoice]. 0 = Default (follow `general.fontChoice`); 1..4 map to
 * the MSDF families offset by one (see [com.soulreturns.config.gui.render.MsdfFonts]).
 */
object HudFontChoice {
    const val DEFAULT = 0

    val LABELS = listOf("Default", "Baloo 2", "Nunito", "Inter", "Minecraft")
}

/** Screen regions for [HudTransform.hAnchor]/[HudTransform.vAnchor]. */
object HudAnchor {
    const val START = 0 // left / top
    const val CENTER = 1
    const val END = 2 // right / bottom
}
