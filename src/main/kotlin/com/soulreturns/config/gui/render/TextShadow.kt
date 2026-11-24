package com.soulreturns.config.gui.render

import kotlin.math.max

/**
 * The drop shadow under Soul text ([SoulText.draw]): a hard copy in black, offset in proportion to the text as it
 * is drawn, so text looks the same at every size (a HUD scaled in Move GUI, a title, a small label), and never
 * sized by Minecraft's GUI Scale. Semibold body text ([BODY_SIZE] at Soul's scale) gets [PX_PER_SCALE] px per
 * Soul scale unit: 3 px at scale 2. At least 1 px, or the shadow of very small text would vanish.
 * Not rounded to whole pixels: a HUD scaled in small steps would jump from a 2 px to a 3 px shadow between two
 * steps, which on small letters is a visible change of weight.
 */
object TextShadow {
    /** Soul's body text size, in Soul units: the size the shadow is tuned for. */
    const val BODY_SIZE = 14f

    /** Physical px per Soul scale unit for semibold body text. */
    const val PX_PER_SCALE = 1.5f

    /**
     * Regular weight's share of the semibold offset: a shadow further out than the stroke is thick separates
     * from thin letters and makes them float over it, so it shrinks with the stroke.
     */
    const val REGULAR_WEIGHT = 0.65f

    /** The shadow's share of the text's alpha. */
    const val ALPHA = 0.7f

    /** The offset in physical px for text [textPx] physical px in size, [semibold] or regular weight. */
    fun physicalPx(textPx: Float, semibold: Boolean = true): Float =
        max(1f, textPx * PX_PER_SCALE / BODY_SIZE * (if (semibold) 1f else REGULAR_WEIGHT))

    /**
     * The offset in pose units for text of nominal [size] under a pose of [poseScale] GUI px per unit at GUI
     * scale [guiScale] (`poseScale × guiScale` physical px per unit), so it comes out at [physicalPx] on screen.
     */
    fun poseOffset(size: Float, poseScale: Float, guiScale: Float, semibold: Boolean = true): Float {
        val scale = (poseScale * guiScale).coerceAtLeast(0.0001f)
        return physicalPx(size * scale, semibold) / scale
    }

    /** Black at [ALPHA] of the text's alpha. */
    fun color(textColor: Int): Int = ((textColor ushr 24 and 0xFF) * ALPHA).toInt().coerceIn(0, 255) shl 24
}
