package com.soulreturns.features.dungeons.roles

import com.soulreturns.features.dungeons.DungeonClass
import com.soulreturns.features.dungeons.leap.LeapMenu

/** Soul-pixel geometry from mocks/archer-role/. */
internal object ArcherRoleLayout {
    const val EYEBROW_SIZE = 9f
    const val EYEBROW_TRACKING = 1.5f
    const val MAIN_SIZE = 20f
    const val DETAIL_SIZE = 11f
    const val GAP = 6f
    const val LINE_HEIGHT = 9f / 8f
    const val FOOTER_Y = 136

    fun cueY(height: Float): Float = minOf(height - 150f, height / 2f + 154f)

    fun showsCue(cue: ArcherCue, showCue: Boolean): Boolean =
        (cue.objective == null || cue.waiting) && (showCue || cue.waiting)

    /** An explicit name never falls back to a different player of the same class. */
    fun targetIndex(cue: ArcherCue, tiles: List<LeapMenu.Tile?>): Int {
        val name = cue.targetName
        val cls = cue.target
        if (name == null && (cls == null || cls == DungeonClass.UNKNOWN)) return -1
        var found = -1
        for (i in tiles.indices) {
            val tile = tiles[i] ?: continue
            if (tile.dead) continue
            val matches = if (name != null) tile.name.equals(name, ignoreCase = true) else tile.cls == cls
            if (!matches) continue
            if (found >= 0) return -1
            found = i
        }
        return found
    }
}
