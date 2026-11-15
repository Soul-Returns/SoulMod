package com.soulreturns.ui

import com.soulreturns.config.cfg
import net.minecraft.client.Minecraft

/**
 * Global Soul UI scale. Soul renders everything (screens, and HUDs in future) at this scale
 * REGARDLESS of Minecraft's GUI Scale setting — MC's native scaling is fully bypassed.
 *
 * Mechanism: MC maps a widget's logical coords to physical pixels by multiplying by the GUI Scale.
 * To render at our own [scale] instead, we push a pose transform of [factor] = `scale / guiScale`
 * and lay out in "Soul-logical" coords ( = physicalPixels / scale ). After the pose multiplies by
 * `factor` and MC multiplies by `guiScale`, the net is `scale` — independent of the user's setting.
 *
 * [scale] is the stored `general.uiScale`. A new config picks it once for the player's monitor
 * ([AutoUiScale]); until that happens it reads [AutoUiScale.FALLBACK].
 */
object SoulUi {
    const val MIN_SCALE = 1f
    const val MAX_SCALE = 6f

    /** Effective on-screen scale, driven by the config slider (like a fixed "GUI Scale"). */
    val scale: Float get() = (cfg.general.uiScale ?: AutoUiScale.FALLBACK).coerceIn(MIN_SCALE, MAX_SCALE)

    /** Pose scale factor to apply so Soul content renders at [scale] regardless of MC's GUI Scale. */
    fun factor(): Float {
        val guiScale = Minecraft.getInstance().window.guiScale.toFloat().coerceAtLeast(1f)
        return scale / guiScale
    }
}
