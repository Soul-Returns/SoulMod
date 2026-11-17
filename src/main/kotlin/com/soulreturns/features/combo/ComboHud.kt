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
 *  - **Full** (0): `Current Combo: +N` header + a line per stacked bonus (Combat Wisdom / Coins per
 *    Kill; a bonus the pet does not give, or has not reached yet, has no line),
 *  - **Compact** (1): a single `+N +12☯ +16` line (the ☯ is a tinted texture, [WisdomIcon]).
 *
 * A [MovableHud], so position/anchor/scale come from [ComboConfig.hud] and are edited in the Move
 * GUI. The totals are [ComboTracker.bonuses]; colors follow [ComboData].
 */
object ComboHud : MovableHud("combo", "Combo Overlay", "general") {
    private const val SIZE = 10f
    private const val PAD = 4

    private fun step(): Int = (SIZE * 1.3f).toInt()

    /**
     * Sign size matched to the active font's cap height, so the ☯ reads the same weight as the digits
     * beside it. Minecraft's font has tall caps (~0.9 em); the vector fonts are shorter (~0.7 em).
     */
    private fun signSize(choice: Int): Float =
        if (choice == MsdfFonts.MINECRAFT) SIZE * 0.9f else fontFor(false, choice).capHeightPx(SIZE)

    private sealed interface Seg

    private class Txt(val s: String, val color: Int, val bold: Boolean) : Seg

    /** The Combat Wisdom sign (☯) Hypixel prints after the amount, tinted [ComboData.COMBAT_WISDOM]. */
    private object WisdomSign : Seg

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
                } else {
                    // Center the sign on the line's font-aware visual center so it lines up with the
                    // text regardless of font (the vector fonts sit lower in the em box than Minecraft's).
                    val size = signSize(choice)
                    WisdomIcon.draw(g, x.toFloat() + 1f, y + lineCenterOffset(choice) - size / 2f, size, ComboData.COMBAT_WISDOM)
                    x += size.toInt() + 1
                }
            }
        }
    }

    // ------------------------------------------------------------ layout

    private fun buildLines(preview: Boolean): List<List<Seg>> {
        val c = if (preview) 25 else ComboTracker.displayCombo()
        val color = ComboData.colorFor(c)
        val wisdom = if (preview) 12.0 else ComboTracker.bonuses.combatWisdom
        val coins = if (preview) 16.0 else ComboTracker.bonuses.coinsPerKill
        val showCount = cfg.combo.showCount
        // A bonus the pet does not give (or that has not shown yet) has nothing to show.
        val showWisdom = cfg.combo.showCombatWisdom && wisdom > 0.0
        val showCoins = cfg.combo.showCoins && coins > 0.0
        return if (cfg.combo.mode == 1) {
            // Compact: one line of "+N +CW☯ +C"; each element is optional, so the "+" separators
            // and the timer are placed relative to what's actually shown (no leading/dangling "+").
            val line = buildList {
                var first = true
                fun plus() = Txt(if (first) "+" else " +", ComboData.PLUS, false).also { first = false }
                if (showCount) { add(Txt("+$c", color, true)); first = false }
                if (showWisdom) { add(plus()); add(Txt(amount(wisdom), ComboData.COMBAT_WISDOM, false)); add(WisdomSign) }
                if (showCoins) { add(plus()); add(Txt(amount(coins), ComboData.COINS, false)) }
                appendTimer(this, preview)
            }
            if (line.isEmpty()) emptyList() else listOf(line)
        } else {
            // Full: the count + timer share the header line; each bonus is its own line.
            buildList {
                val header = buildList {
                    if (showCount) {
                        add(Txt("Current Combo: ", ComboData.LABEL, false))
                        add(Txt("+$c", color, true))
                    }
                    appendTimer(this, preview)
                }
                if (header.isNotEmpty()) add(header)
                if (showWisdom) {
                    add(
                        listOf(
                            Txt("+", ComboData.PLUS, false),
                            Txt(amount(wisdom), ComboData.COMBAT_WISDOM, false),
                            WisdomSign,
                            Txt(" Combat Wisdom", ComboData.COMBAT_WISDOM, false),
                        ),
                    )
                }
                if (showCoins) {
                    add(
                        listOf(
                            Txt("+", ComboData.PLUS, false),
                            Txt("${amount(coins)} ", ComboData.COINS, false),
                            Txt("Coins per Kill", ComboData.LABEL, false),
                        ),
                    )
                }
            }
        }
    }

    /** A bonus total as Hypixel prints it: whole numbers plain, anything else with one decimal. */
    private fun amount(v: Double): String =
        if (v == Math.rint(v)) v.toLong().toString() else String.format(Locale.ROOT, "%.1f", v)

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
            signSize(choice).toInt() + 2
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
