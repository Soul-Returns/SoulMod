package com.soulreturns.ui.hud

import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.movable.HudLayouts
import com.soulreturns.ui.hud.movable.HudMoveScreen
import com.soulreturns.ui.hud.movable.MovableHud
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements
import net.minecraft.client.Minecraft
import net.minecraft.resources.Identifier

/**
 * Minimal HUD host: one Fabric HUD layer that draws every registered [HudElement] through the
 * Soul render layer (SDF rects + MSDF text) at [SoulUi.scale], bypassing MC's GUI Scale exactly
 * like [com.soulreturns.config.gui.SoulConfigScreen] does.
 */
object SoulHud {
    private val logger = SoulLogger("Soul/Hud")
    private val elements = mutableListOf<HudElement>()
    private val crashedElements = mutableSetOf<String>()
    private var registered = false

    fun add(element: HudElement) {
        val firstForeground = if (element.foreground) -1 else elements.indexOfFirst { it.foreground }
        elements.add(if (firstForeground < 0) elements.size else firstForeground, element)
    }

    fun register() {
        if (registered) return
        registered = true

        // Before CHAT, not addLast: with an in-game screen open, vanilla defers the subtitles element
        // (and Fabric's elements around it) until after the screen, so the HUD drew over the
        // inventory's dim/blur. Here it sits with the rest of the HUD; chat and tab list stay on top.
        HudElementRegistry.attachElementBefore(VanillaHudElements.CHAT, Identifier.fromNamespaceAndPath("soul", "hud")) { g, _ ->
            // No explicit F1/hide-GUI check: vanilla skips the whole HUD layer pass (including
            // Fabric-attached elements) when the GUI is hidden.
            val mc = Minecraft.getInstance()
            if (mc.player == null) return@attachElementBefore

            val factor = SoulUi.factor()
            // The Move GUI draws its own previews; live copies would double them.
            val editing = mc.gui.screen() is HudMoveScreen
            g.pose().pushMatrix()
            g.pose().scale(factor, factor)
            HudLayouts.frame {
                for (element in elements) {
                    if (crashedElements.contains(element.id)) continue
                    if (editing && element is MovableHud) continue
                    try {
                        if (element.isEnabled()) {
                            if (element.foreground) g.nextStratum()
                            element.extract(g)
                        }
                    } catch (t: Throwable) {
                        // Disable a throwing element for the session instead of spamming every frame.
                        crashedElements.add(element.id)
                        logger.error("HUD element '${element.id}' threw — disabled for this session", t)
                    }
                }
            }
            g.pose().popMatrix()
        }
    }
}
