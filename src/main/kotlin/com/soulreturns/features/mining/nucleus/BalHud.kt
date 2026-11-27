package com.soulreturns.features.mining.nucleus

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.NucleusConfig
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f
import java.util.Locale

private fun updateNucleus(block: NucleusConfig.() -> NucleusConfig) {
    ConfigManager.update { copy(mining = mining.copy(nucleus = mining.nucleus.block())) }
}

/**
 * One line: `Bal down 1:23 · Topaz ready` / `Bal up · Topaz locked` / `Bal spawning` / `Bal unknown`.
 * A "down" Bal turns yellow past the shortest respawn seen ([BalState.MIN_RESPAWN_MS]).
 */
object BalHud : MovableHud("nucleus_bal", "Bal Status", "mining") {
    private const val SIZE = 10f
    private const val PAD = 5

    private const val COLOR_LABEL = 0xFFE0E0E0.toInt()
    private const val COLOR_DOWN = 0xFF55FF55.toInt()
    private const val COLOR_MAYBE = 0xFFFFFF55.toInt()
    private const val COLOR_UP = 0xFFFF5555.toInt()
    private const val COLOR_SPAWNING = 0xFFFFAA00.toInt()
    private const val COLOR_UNKNOWN = 0xFF909090.toInt()
    private const val COLOR_DIM = 0xFF909090.toInt()

    override fun isEnabled(): Boolean = BalState.enabled() && NucleusRuns.inHollows()

    override fun configuredOn(): Boolean = BalState.enabled()

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "bal")

    override fun readTransform(): HudTransform = cfg.mining.nucleus.balTransform

    override fun writeTransform(transform: HudTransform) = updateNucleus { copy(balTransform = transform) }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.nucleus.balTransform

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.mining.nucleus.balBackground }) { updateNucleus { copy(balBackground = !balBackground) } },
        HudMenuToggle("Khazad-dûm warning", { cfg.mining.nucleus.balWarning }) { updateNucleus { copy(balWarning = !balWarning) } },
    )

    /** The line as colored runs; pure over [BalState] (or a sample in the Move GUI). */
    private fun segments(preview: Boolean): List<Pair<String, Int>> {
        if (preview) return listOf("Bal " to COLOR_LABEL, "down 1:23" to COLOR_DOWN, " · Topaz ready" to COLOR_DIM)
        val since = System.currentTimeMillis() - BalState.since
        val phase = BalState.phase
        return if (phase == BalState.Phase.DOWN) {
            val maybe = since >= BalState.MIN_RESPAWN_MS
            listOf(
                "Bal " to COLOR_LABEL,
                "down " + (if (BalState.approximate) "~" else "") + clock(since) to if (maybe) COLOR_MAYBE else COLOR_DOWN,
                (if (maybe) " · may be back" else " · Topaz ready") to COLOR_DIM,
            )
        } else if (phase == BalState.Phase.UP) {
            listOf("Bal " to COLOR_LABEL, "up" to COLOR_UP, " · Topaz locked" to COLOR_DIM)
        } else if (phase == BalState.Phase.SPAWNING) {
            listOf("Bal " to COLOR_LABEL, "spawning " + clock(since) to COLOR_SPAWNING)
        } else {
            listOf("Bal " to COLOR_LABEL, "unknown" to COLOR_UNKNOWN)
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val c = fontChoiceIndex()
        val font = font(c)
        val w = segments(preview).sumOf { SoulText.width(it.first, SIZE, font, fontChoice = c) }
        return (w + PAD * 2) to ((SIZE * 1.4f).toInt() + PAD * 2)
    }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val c = fontChoiceIndex()
        val font = font(c)
        val (w, h) = contentSize(preview)
        if (cfg.mining.nucleus.balBackground) {
            g.guiRenderState.addGuiElement(
                RoundRectRenderState(
                    SoulRenderPipelines.ROUND_SMALL, TextureSetup.noTexture(), Matrix3x2f(g.pose()), 0, 0, w, h,
                    cfg.mining.nucleus.checklistBackgroundColor.effective(), null,
                ),
            )
        }
        val line = SoulText.Line(g, PAD, PAD, SIZE, fontChoice = c)
        for ((text, color) in segments(preview)) line.text(text, color, font, textShadow())
    }

    private fun font(choice: Int) = if (boldFont()) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)
}
