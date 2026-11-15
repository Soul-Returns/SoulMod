package com.soulreturns.features.mining.metaldetector

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import kotlin.math.max

/**
 * `Scavenged Tools` / `N/4`: how full the Divan tool set in the inventory is, centered under the
 * action bar. Reads [MetalDetectorLoot.toolCount] (its 10-tick inventory read); no per-frame scan.
 * Shown in the Mines of Divan sublocation (the loot tracker's gate, so it needs no solver toggle).
 */
object MetalDetectorToolsHud : MovableHud("metal_detector_tools", "Scavenged Tools", "mining") {
    private const val TITLE_SIZE = 8f
    private const val COUNT_SIZE = 11f
    private const val PAD = 2

    private const val COLOR_TITLE = 0xFFB0B0B0.toInt()
    private const val COLOR_NONE = 0xFF808080.toInt()
    private const val COLOR_SOME = 0xFFFFFFFF.toInt()
    private const val COLOR_FULL = 0xFF55FF55.toInt()

    override fun isEnabled(): Boolean =
        cfg.mining.metalDetector.toolsCounter && SkyblockLocation.isInSubLocation(MetalDetector.SUBLOCATION)

    override fun configuredOn(): Boolean = cfg.mining.metalDetector.toolsCounter

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "toolscounter")

    override fun readTransform(): HudTransform = cfg.mining.metalDetector.toolsCounterTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(mining = mining.copy(metalDetector = mining.metalDetector.copy(toolsCounterTransform = transform))) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.metalDetector.toolsCounterTransform

    private fun count(preview: Boolean): Int = if (preview) 2 else MetalDetectorLoot.toolCount

    private fun font(c: Int) = if (boldFont()) MsdfFonts.semiboldFor(c) else MsdfFonts.regularFor(c)

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val c = fontChoiceIndex()
        val f = font(c)
        val w = max(SoulText.width(TITLE, TITLE_SIZE, f, fontChoice = c), SoulText.width("4/4", COUNT_SIZE, f, fontChoice = c))
        return (w + PAD * 2) to ((TITLE_SIZE * 1.3f).toInt() + (COUNT_SIZE * 1.3f).toInt() + PAD * 2)
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val c = fontChoiceIndex()
        val f = font(c)
        val (w, _) = contentSize(preview)
        val n = count(preview)
        val text = "$n/4"
        val color = if (n >= 4) COLOR_FULL else if (n == 0) COLOR_NONE else COLOR_SOME
        val titleW = SoulText.width(TITLE, TITLE_SIZE, f, fontChoice = c)
        val countW = SoulText.width(text, COUNT_SIZE, f, fontChoice = c)
        SoulText.draw(g, TITLE, (w - titleW) / 2, PAD, TITLE_SIZE, COLOR_TITLE, font = f, forceCustom = false, shadow = textShadow(), fontChoice = c)
        SoulText.draw(
            g, text, (w - countW) / 2, PAD + (TITLE_SIZE * 1.3f).toInt(), COUNT_SIZE, color,
            font = f, forceCustom = false, shadow = textShadow(), fontChoice = c,
        )
    }

    private const val TITLE = "Scavenged Tools"
}
