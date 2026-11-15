package com.soulreturns.ui.hud.movable

/**
 * The set of [MovableHud]s the [HudMoveScreen] can drag/scale/anchor. A HUD registers here (for the
 * editor) and separately with [com.soulreturns.ui.hud.SoulHud] (for live rendering).
 */
object MovableHudRegistry {
    private val huds = mutableListOf<MovableHud>()

    fun register(hud: MovableHud) {
        if (huds.none { it.id == hud.id }) huds.add(hud)
    }

    fun all(): List<MovableHud> = huds
}
