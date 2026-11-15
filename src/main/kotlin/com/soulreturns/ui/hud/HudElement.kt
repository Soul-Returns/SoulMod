package com.soulreturns.ui.hud

import net.minecraft.client.gui.GuiGraphicsExtractor

/**
 * A Soul HUD overlay. Drawn by [SoulHud] with the [com.soulreturns.ui.SoulUi] scale pose already
 * pushed — implementations lay out in Soul-logical coordinates and read their own position from
 * config inside [extract] (never cache `cfg`).
 */
abstract class HudElement(val id: String) {
    /** Above ordinary HUDs, still beneath chat and screens. */
    open val foreground: Boolean get() = false

    abstract fun isEnabled(): Boolean

    abstract fun extract(g: GuiGraphicsExtractor)
}
