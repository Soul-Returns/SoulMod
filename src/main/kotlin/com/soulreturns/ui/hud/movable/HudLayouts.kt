package com.soulreturns.ui.hud.movable

import com.soulreturns.ui.SoulUi
import net.minecraft.client.Minecraft

/**
 * [HudLayout]s over the running game: the window in Soul px, the vanilla HUD and the registered
 * [MovableHud]s. One layout per HUD pass ([frame]) so a target's size is measured once a frame.
 */
object HudLayouts {
    private var framing = false
    private var current: HudLayout? = null

    /** Run one HUD pass; [live] inside it returns the same layout. Client thread only. */
    fun <T> frame(block: () -> T): T {
        val outer = framing
        framing = true
        if (!outer) current = null
        try {
            return block()
        } finally {
            if (!outer) {
                framing = false
                current = null
            }
        }
    }

    /** The layout the live HUD draws with: shown = [MovableHud.isEnabled], live content sizes. */
    fun live(): HudLayout {
        if (framing) current?.let { return it }
        val layout = build(LiveSource())
        if (framing) current = layout
        return layout
    }

    /** A layout over [source] at the current window (the editor's). */
    fun build(source: HudSource, overrides: Map<String, HudBox> = emptyMap()): HudLayout {
        val window = Minecraft.getInstance().window
        val f = SoulUi.factor()
        val guiW = window.guiScaledWidth
        val guiH = window.guiScaledHeight
        return HudLayout(guiW / f, guiH / f, VanillaHudLayout.elements(guiW, guiH, f), source, overrides)
    }

    private class LiveSource : HudSource {
        private val huds = MovableHudRegistry.all().associateBy { it.id }
        private val sizes = HashMap<String, FloatArray>()

        override fun transform(id: String): HudTransform? = huds[id]?.readTransform()

        override fun size(id: String): FloatArray =
            sizes.getOrPut(id) { huds.getValue(id).contentSize(false).let { floatArrayOf(it.first.toFloat(), it.second.toFloat()) } }

        override fun shown(id: String): Boolean = huds[id]?.isDrawn() == true
    }
}
