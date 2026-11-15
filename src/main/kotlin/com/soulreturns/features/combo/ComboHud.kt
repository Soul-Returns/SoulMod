package com.soulreturns.features.combo

import com.soulreturns.config.ConfigManager
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
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.gui.render.TextureSetup
import org.joml.Matrix3x2f
import java.util.Locale

/**
 * The SkyBlock kill-combo overlay (SBCombo port), in two layouts matching the module's Short/Long
 * modes (`cfg.combo.mode`):
 *  - **Full** (0): `Current Combo: +N` header + three bonus lines (Magic Find / Coins per Kill /
 *    Combat Exp),
 *  - **Compact** (1): a single `+N +M✦ +C +X%` line.
 *
 * A [MovableHud], so position/anchor/scale come from [ComboConfig.hud] and are edited in the Move
 * GUI. Colors + text follow the module (see [ComboData]); the ✦ is a tinted texture ([StarIcon]).
 */
object ComboHud : MovableHud("combo", "Combo Overlay", "general") {
    private const val SIZE = 10f
    private const val PAD = 4

    private fun step(): Int = (SIZE * 1.3f).toInt()

    /**
     * Star size matched to the active font's cap height, so the ✦ reads the same weight as the
     * digits beside it. Minecraft's font has tall caps (~0.9 em); the vector fonts are shorter
     * (~0.7 em), so a fixed size looked oversized against them.
     */
    private fun starSize(choice: Int): Float =
        if (choice == MsdfFonts.MINECRAFT) SIZE * 0.9f else fontFor(false, choice).capHeightPx(SIZE)

    private sealed interface Seg

    private class Txt(val s: String, val color: Int, val bold: Boolean) : Seg

    /** The Magic-Find star (✦), always tinted [ComboData.MAGIC_FIND] (light-blue). */
    private object StarSeg : Seg

    override fun isEnabled(): Boolean = cfg.combo.enabled && ComboTracker.displayCombo() >= 5

    override fun configuredOn(): Boolean = cfg.combo.enabled

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "combo")

    override fun readTransform(): HudTransform = cfg.combo.hud

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(combo = combo.copy(hud = transform)) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().combo.hud

    override fun menuToggles(): List<HudMenuToggle> =
        listOf(
            HudMenuToggle("Background", { cfg.combo.background }) {
                ConfigManager.update { copy(combo = combo.copy(background = !combo.background)) }
            },
        )

    override fun contentSize(preview: Boolean): Pair<Int, Int> = measure(buildLines(preview), boldFont(), fontChoiceIndex())

    override fun drawContent(g: GuiGraphicsExtractor, preview: Boolean) {
        val lines = buildLines(preview)
        val bold = boldFont()
        val choice = fontChoiceIndex()
        val (w, h) = measure(lines, bold, choice)

        if (cfg.combo.background) {
            roundRect(g, 0, 0, w, h, cfg.combo.backgroundColor.effective())
        }

        val shadow = textShadow()
        for ((i, line) in lines.withIndex()) {
            var x = PAD
            val y = PAD + i * step()
            for (seg in line) {
                if (seg is Txt) {
                    val font = fontFor(seg.bold || bold, choice)
                    SoulText.draw(g, seg.s, x, y, SIZE, seg.color, font = font, forceCustom = false, shadow = shadow, fontChoice = choice)
                    x += SoulText.width(seg.s, SIZE, font, fontChoice = choice)
                } else if (seg is StarSeg) {
                    // Center the star on the line's font-aware visual center so it lines up with the
                    // text regardless of font (the vector fonts sit lower in the em box than Minecraft's).
                    val cy = y + lineCenterOffset(choice)
                    val ss = starSize(choice)
                    StarIcon.draw(g, x.toFloat() + 1f, cy - ss / 2f, ss, ComboData.MAGIC_FIND)
                    x += ss.toInt() + 2
                }
            }
        }
    }

    // ------------------------------------------------------------ layout

    private fun buildLines(preview: Boolean): List<List<Seg>> {
        val c = if (preview) 25 else ComboTracker.displayCombo()
        val t = ComboData.tierFor(c)
        val showCount = cfg.combo.showCount
        val showMf = cfg.combo.showMagicFind
        val showCoins = cfg.combo.showCoins
        val showXp = cfg.combo.showCombatExp
        return if (cfg.combo.mode == 1) {
            // Compact: one line of "+N +M✦ +C +X%"; each element is optional, so the "+" separators
            // and the timer are placed relative to what's actually shown (no leading/dangling "+").
            val line = buildList {
                var first = true
                fun plus() = Txt(if (first) "+" else " +", ComboData.PLUS, false).also { first = false }
                if (showCount) { add(Txt("+$c", t.color, true)); first = false }
                if (showMf) { add(plus()); add(Txt("${t.magicFind}", ComboData.MAGIC_FIND, false)); add(StarSeg) }
                if (showCoins) { add(plus()); add(Txt("${t.coinsPerKill}", ComboData.COINS, false)) }
                if (showXp) { add(plus()); add(Txt("${t.combatXp}%", ComboData.COMBAT_XP, false)) }
                appendTimer(this, preview)
            }
            if (line.isEmpty()) emptyList() else listOf(line)
        } else {
            // Full: the count + timer share the header line; each bonus is its own line.
            buildList {
                val header = buildList {
                    if (showCount) {
                        add(Txt("Current Combo: ", ComboData.LABEL, false))
                        add(Txt("+$c", t.color, true))
                    }
                    appendTimer(this, preview)
                }
                if (header.isNotEmpty()) add(header)
                if (showMf) {
                    add(
                        listOf(
                            Txt("+", ComboData.PLUS, false),
                            Txt("${t.magicFind} ", ComboData.MAGIC_FIND, false),
                            StarSeg,
                            Txt(" Magic Find", ComboData.MAGIC_FIND, false),
                        ),
                    )
                }
                if (showCoins) {
                    add(
                        listOf(
                            Txt("+", ComboData.PLUS, false),
                            Txt("${t.coinsPerKill} ", ComboData.COINS, false),
                            Txt("Coins per Kill", ComboData.LABEL, false),
                        ),
                    )
                }
                if (showXp) {
                    add(
                        listOf(
                            Txt("+", ComboData.PLUS, false),
                            Txt("${t.combatXp}% ", ComboData.COMBAT_XP, false),
                            Txt("Combat Exp", ComboData.LABEL, false),
                        ),
                    )
                }
            }
        }
    }

    /** Append the red expiration countdown (`5.8s`) to [line] if the timer element is enabled. */
    private fun appendTimer(line: MutableList<Seg>, preview: Boolean) {
        if (!cfg.combo.showExpireTimer) return
        val secs = if (preview) 5.8f else ComboTracker.secondsUntilExpire()
        if (line.isNotEmpty()) line.add(Txt("  ", ComboData.TIMER, false))
        line.add(Txt(String.format(Locale.ROOT, "%.1fs", secs), ComboData.TIMER, false))
    }

    private fun measure(lines: List<List<Seg>>, bold: Boolean, choice: Int): Pair<Int, Int> {
        var maxW = 0
        for (line in lines) {
            var w = 0
            for (seg in line) w += segWidth(seg, bold, choice)
            if (w > maxW) maxW = w
        }
        val h = PAD * 2 + lines.size * step()
        return (maxW + PAD * 2) to h
    }

    private fun segWidth(seg: Seg, bold: Boolean, choice: Int): Int =
        if (seg is Txt) {
            SoulText.width(seg.s, SIZE, fontFor(seg.bold || bold, choice), fontChoice = choice)
        } else {
            starSize(choice).toInt() + 3
        }

    private fun fontFor(bold: Boolean, choice: Int) =
        if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)

    /** Offset from a line's top ([y] passed to [SoulText.draw]) to its font-aware visual center. */
    private fun lineCenterOffset(choice: Int): Float =
        if (choice == MsdfFonts.MINECRAFT) SIZE * 0.5f else fontFor(false, choice).centerOffsetPx(SIZE)

    private fun roundRect(g: GuiGraphicsExtractor, x0: Int, y0: Int, x1: Int, y1: Int, color: Int) {
        g.guiRenderState.addGuiElement(
            RoundRectRenderState(
                SoulRenderPipelines.ROUND_SMALL,
                TextureSetup.noTexture(),
                Matrix3x2f(g.pose()),
                x0, y0, x1, y1, color, null,
            ),
        )
    }
}
