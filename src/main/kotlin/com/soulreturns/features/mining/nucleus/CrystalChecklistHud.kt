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
import kotlin.math.abs

/**
 * Crystal checklist + splits: one row per crystal (missing / found with the split since the run start /
 * placed), the delta of that leg against the player's median for the crystal, the run timer, and a
 * "go place" cue once all are found. A pure view over [NucleusRuns]' run in progress.
 */
object CrystalChecklistHud : MovableHud("nucleus_checklist", "Crystal Checklist", "mining") {
    private const val SIZE = 10f
    private const val PAD = 5
    private const val GAP = 8
    private const val DOT = 6
    private const val TITLE = "Crystal Run"

    private const val COLOR_TITLE = 0xFFFFFFFF.toInt()
    private const val COLOR_DIM = 0xFF808080.toInt()
    private const val COLOR_TEXT = 0xFFE0E0E0.toInt()
    private const val COLOR_PLACED = 0xFF55FF55.toInt()
    private const val COLOR_FASTER = 0xFF55FF55.toInt()
    private const val COLOR_SLOWER = 0xFFFF6666.toInt()
    private const val COLOR_CUE = 0xFFFFAA00.toInt()

    private class Line(val crystal: String, val status: String, val statusColor: Int, val split: String, val delta: String, val deltaColor: Int)

    private val PREVIEW_RUN =
        NucleusRun(
            startClock = 0L,
            foundCount = 3,
            crystals = mapOf(
                "Topaz" to CrystalProgress(CrystalState.PLACED, 41_700L, -2_100L),
                "Amber" to CrystalProgress(CrystalState.FOUND, 55_100L, 1_300L),
                "Amethyst" to CrystalProgress(CrystalState.FOUND, 98_000L, 4_200L),
            ),
        )

    override fun isEnabled(): Boolean = cfg.mining.nucleus.checklist && NucleusRuns.inHollows()

    override fun configuredOn(): Boolean = cfg.mining.nucleus.checklist

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "checklist")

    override fun readTransform(): HudTransform = cfg.mining.nucleus.checklistTransform

    override fun writeTransform(transform: HudTransform) = update { copy(checklistTransform = transform) }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.nucleus.checklistTransform

    override fun menuToggles(): List<HudMenuToggle> = listOf(
        HudMenuToggle("Background", { cfg.mining.nucleus.checklistBackground }) { update { copy(checklistBackground = !checklistBackground) } },
    )

    private fun update(block: NucleusConfig.() -> NucleusConfig) {
        ConfigManager.update { copy(mining = mining.copy(nucleus = mining.nucleus.block())) }
    }

    // ------------------------------------------------------------------ content

    private fun run(preview: Boolean): NucleusRun = if (preview) PREVIEW_RUN else NucleusRuns.handle.read().run

    private fun timer(preview: Boolean): String {
        if (preview) return "1:52"
        val run = run(false)
        val elapsed = NucleusRuns.runElapsedMs() ?: return "-"
        return (if (run.partial) "~" else "") + clock(elapsed)
    }

    private fun lines(preview: Boolean): List<Line> {
        val run = run(preview)
        val names = LinkedHashSet(NucleusCrystals.ORDER)
        names.addAll(run.crystals.keys)
        return names.map { name ->
            val p = run.crystals[name] ?: CrystalProgress()
            val status =
                if (p.state == CrystalState.PLACED) "placed" else if (p.state == CrystalState.FOUND) "found" else "missing"
            val statusColor =
                if (p.state == CrystalState.PLACED) COLOR_PLACED else if (p.state == CrystalState.FOUND) COLOR_TEXT else COLOR_DIM
            val split = if (p.state == CrystalState.MISSING) "" else p.splitMs?.let(::clock) ?: "-"
            val d = p.deltaMs
            val delta = if (p.state == CrystalState.MISSING || d == null) "" else signed(d)
            Line(name, status, statusColor, split, delta, if (d != null && d <= 0) COLOR_FASTER else COLOR_SLOWER)
        }
    }

    /** The bottom line: the cue once everything is found, else nothing. */
    private fun cue(preview: Boolean): String? {
        if (preview) return null
        val run = run(false)
        if (!run.readyToPlace()) return null
        val placeMedian = NucleusRuns.medianLeg(NucleusRuns.PLACE_LEG)
        val placed = run.count(CrystalState.PLACED)
        val found = placed + run.count(CrystalState.FOUND)
        return "Go place! $placed/$found" + (placeMedian?.let { "  (usual ${seconds(it)})" } ?: "")
    }

    private class Layout(val nameW: Int, val statusW: Int, val splitW: Int, val deltaW: Int, val width: Int, val height: Int)

    private fun layout(preview: Boolean): Layout {
        val c = fontChoiceIndex()
        val bold = boldFont()
        val lines = lines(preview)
        val nameW = lines.maxOf { width(it.crystal, bold, c) } + DOT + 4
        val statusW = maxOf(width("missing", bold, c), width("placed", bold, c))
        val splitW = maxOf(width("0:00", bold, c), lines.maxOf { width(it.split, bold, c) })
        val deltaW = maxOf(width("+0.0s", bold, c), lines.maxOf { width(it.delta, bold, c) })
        val rowsW = nameW + GAP + statusW + GAP + splitW + GAP + deltaW
        val titleW = width(TITLE, true, c) + GAP + width(timer(preview), true, c)
        val cueW = cue(preview)?.let { width(it, true, c) } ?: 0
        val w = maxOf(rowsW, titleW, cueW) + PAD * 2
        val rows = 1 + lines.size + if (cue(preview) != null) 1 else 0
        return Layout(nameW, statusW, splitW, deltaW, w, PAD * 2 + rows * step())
    }

    override fun contentSize(preview: Boolean): Pair<Int, Int> = layout(preview).let { it.width to it.height }

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val l = layout(preview)
        val c = fontChoiceIndex()
        val bold = boldFont()
        val shadow = textShadow()
        if (cfg.mining.nucleus.checklistBackground) roundRect(g, l.width, l.height, cfg.mining.nucleus.checklistBackgroundColor.effective())

        draw(g, TITLE, PAD, PAD, COLOR_TITLE, true, c, shadow)
        val t = timer(preview)
        draw(g, t, l.width - PAD - width(t, true, c), PAD, COLOR_TEXT, true, c, shadow)

        var y = PAD + step()
        for (line in lines(preview)) {
            val dotY = y + (step() - DOT) / 2 - 1
            g.fill(PAD, dotY, PAD + DOT, dotY + DOT, NucleusCrystals.color(line.crystal))
            draw(g, line.crystal, PAD + DOT + 4, y, NucleusCrystals.color(line.crystal), bold, c, shadow)
            var x = PAD + l.nameW + GAP
            draw(g, line.status, x, y, line.statusColor, bold, c, shadow)
            x += l.statusW + GAP
            draw(g, line.split, x + l.splitW - width(line.split, bold, c), y, COLOR_TEXT, bold, c, shadow)
            x += l.splitW + GAP
            draw(g, line.delta, x + l.deltaW - width(line.delta, bold, c), y, line.deltaColor, bold, c, shadow)
            y += step()
        }
        cue(preview)?.let { draw(g, it, PAD, y, COLOR_CUE, true, c, shadow) }
    }

    private fun step(): Int = (SIZE * 1.4f).toInt()

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1fs", ms / 1000.0)

    /** `-2.1s`, `+0:12` past a minute. */
    private fun signed(ms: Long): String {
        val sign = if (ms < 0) "-" else "+"
        val a = abs(ms)
        return if (a < 60_000) sign + String.format(Locale.ROOT, "%.1fs", a / 1000.0) else sign + clock(a)
    }

    private fun width(text: String, bold: Boolean, choice: Int): Int =
        if (text.isEmpty()) 0 else SoulText.width(text, SIZE, fontFor(bold, choice), fontChoice = choice)

    private fun draw(g: GuiGraphicsExtractor, text: String, x: Int, y: Int, color: Int, bold: Boolean, choice: Int, shadow: Boolean) {
        if (text.isEmpty()) return
        SoulText.draw(g, text, x, y, SIZE, color, font = fontFor(bold, choice), forceCustom = false, shadow = shadow, fontChoice = choice)
    }

    private fun fontFor(bold: Boolean, choice: Int) = if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)

    private fun roundRect(g: GuiGraphicsExtractor, w: Int, h: Int, color: Int) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(SoulRenderPipelines.ROUND_SMALL, TextureSetup.noTexture(), Matrix3x2f(g.pose()), 0, 0, w, h, color, null),
        )
    }
}
