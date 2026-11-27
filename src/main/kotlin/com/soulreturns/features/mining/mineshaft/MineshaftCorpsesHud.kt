package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.ChromaColor
import com.soulreturns.config.ConfigManager
import com.soulreturns.config.SoulConfig
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.SoulConfigScreen
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.ui.hud.movable.HudMenuToggle
import com.soulreturns.ui.hud.movable.HudTransform
import com.soulreturns.ui.hud.movable.MovableHud
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.gui.screens.Screen
import org.joml.Matrix3x2f

/**
 * Frozen Corpse overlay — one row per corpse type currently in the mineshaft, as
 * `Lapis: <looted>/<total>` with the looted count green and the total red. Only drawn while inside a
 * Glacite Mineshaft, which is also why it can sit top-centre without fighting any other HUD.
 *
 * A pure view over [MineshaftCorpses]; it owns no scan and no state.
 *
 * The legacy version was written against the Compose-style `ui/composer` framework, which does not
 * exist in this baseline, so only the information design carried over: the layout, the ordering
 * (most corpses first, then alphabetical, so the row you care about is at the top and does not jump
 * around as counts change) and the colours. Everything is drawn on the SDF/MSDF layer through
 * [MovableHud], which brings position, anchor, scale, font and text-shadow with it.
 *
 * Types are looked up by name and an unknown one falls back to
 * [com.soulreturns.config.MineshaftCorpseColors.other] — never dropped. Hypixel adds corpse types
 * without warning and a HUD that silently omits one is worse than a HUD that draws it in grey.
 */
object MineshaftCorpsesHud : MovableHud("mineshaft_corpses", "Mineshaft Corpses", "mining") {
    private const val SIZE = 10f
    private const val PAD = 4

    /** Sample content for the Move GUI, where there is no live mineshaft to read. */
    private val PREVIEW = linkedMapOf(
        "Lapis" to MineshaftCorpses.Counts(unlooted = 2, looted = 3),
        "Tungsten" to MineshaftCorpses.Counts(unlooted = 1, looted = 0),
        "Vanguard" to MineshaftCorpses.Counts(unlooted = 1, looted = 0),
    )

    private fun step(): Int = (SIZE * 1.3f).toInt()

    override fun isEnabled(): Boolean = cfg.mining.mineshaft.corpsesHud && SkyblockLocation.isInArea(MineshaftState.AREA)

    override fun configuredOn(): Boolean = cfg.mining.mineshaft.corpsesHud

    override fun settingsScreen(parent: Screen?): Screen = SoulConfigScreen.forFeature(parent, "corpses")

    override fun readTransform(): HudTransform = cfg.mining.mineshaft.corpsesHudTransform

    override fun writeTransform(transform: HudTransform) {
        ConfigManager.update { copy(mining = mining.copy(mineshaft = mining.mineshaft.copy(corpsesHudTransform = transform))) }
    }

    override fun defaultTransform(): HudTransform = SoulConfig().mining.mineshaft.corpsesHudTransform

    override fun menuToggles(): List<HudMenuToggle> =
        listOf(
            HudMenuToggle("Background", { cfg.mining.mineshaft.corpsesHudBackground }) {
                ConfigManager.update {
                    copy(mining = mining.copy(mineshaft = mining.mineshaft.copy(corpsesHudBackground = !mining.mineshaft.corpsesHudBackground)))
                }
            },
        )

    override fun contentSize(preview: Boolean): Pair<Int, Int> {
        val rows = rows(preview)
        val bold = boldFont()
        val choice = fontChoiceIndex()
        var maxW = width(TITLE, bold = true, choice)
        for (row in rows) maxW = maxOf(maxW, rowWidth(row, bold, choice))
        return (maxW + PAD * 2) to (PAD * 2 + (rows.size + 1) * step())
    }

    override fun drawContent(
        g: GuiGraphicsExtractor,
        preview: Boolean,
    ) {
        val colors = cfg.mining.mineshaft.corpsesHudColors
        val rows = rows(preview)
        val bold = boldFont()
        val choice = fontChoiceIndex()
        val shadow = textShadow()
        val (w, h) = contentSize(preview)

        if (cfg.mining.mineshaft.corpsesHudBackground) {
            roundRect(g, 0, 0, w, h, cfg.mining.mineshaft.corpsesHudBackgroundColor.effective())
        }

        draw(g, TITLE, PAD, PAD, colors.title, SIZE, bold = true, choice = choice, shadow = shadow)

        for ((index, row) in rows.withIndex()) {
            val line = SoulText.Line(g, PAD, PAD + (index + 1) * step(), SIZE, fontChoice = choice)
            for ((text, color) in segments(row)) line.text(text, color.effective(), fontFor(bold, choice), shadow)
        }
    }

    // ------------------------------------------------------------------ layout

    private const val TITLE = "Corpses"

    /** `null` marks the "None" row so it renders in its own colour without a special case above. */
    private fun rows(preview: Boolean): List<Pair<String, MineshaftCorpses.Counts>?> {
        val source = if (preview) PREVIEW else MineshaftCorpses.byType
        if (source.isEmpty()) return listOf(null)
        return source.entries
            .sortedWith(compareByDescending<Map.Entry<String, MineshaftCorpses.Counts>> { it.value.total }.thenBy { it.key })
            .map { it.key to it.value }
    }

    /** A row as coloured text runs. Split out so measuring and drawing cannot disagree. */
    private fun segments(row: Pair<String, MineshaftCorpses.Counts>?): List<Pair<String, ChromaColor>> {
        val colors = cfg.mining.mineshaft.corpsesHudColors
        if (row == null) return listOf("None" to colors.none)
        val (type, counts) = row
        return listOf(
            "$type: " to colorFor(type),
            "${counts.looted}" to colors.looted,
            "/" to colors.none,
            "${counts.total}" to colors.total,
        )
    }

    /** Colour for a corpse type; a type Hypixel added gets `other` (shared with the corpse locator). */
    private fun colorFor(type: String): ChromaColor = cfg.mining.mineshaft.corpsesHudColors.of(type)

    private fun rowWidth(
        row: Pair<String, MineshaftCorpses.Counts>?,
        bold: Boolean,
        choice: Int,
    ): Int = segments(row).sumOf { width(it.first, bold, choice) }

    private fun width(
        text: String,
        bold: Boolean,
        choice: Int,
    ): Int = SoulText.width(text, SIZE, fontFor(bold, choice), fontChoice = choice)

    private fun draw(
        g: GuiGraphicsExtractor,
        text: String,
        x: Int,
        y: Int,
        color: ChromaColor,
        size: Float,
        bold: Boolean,
        choice: Int,
        shadow: Boolean,
    ) {
        SoulText.draw(
            g, text, x, y, size, color.effective(),
            font = fontFor(bold, choice), forceCustom = false, shadow = shadow, fontChoice = choice,
        )
    }

    private fun fontFor(
        bold: Boolean,
        choice: Int,
    ) = if (bold) MsdfFonts.semiboldFor(choice) else MsdfFonts.regularFor(choice)

    private fun roundRect(
        g: GuiGraphicsExtractor,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        color: Int,
    ) {
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
