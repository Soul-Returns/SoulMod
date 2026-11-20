package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.TROPHY_TIERS
import com.soulreturns.data.hypixel.TrophyFishEntry
import com.soulreturns.data.hypixel.fishingCounts
import com.soulreturns.data.hypixel.trophyFishing
import com.soulreturns.data.skyblock.SkyblockRarity
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.Inline
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitLayout
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui

/**
 * Trophy fish per fish and tier, items fished and sea creature kills (mock `fishingSec`). Trophy frogs
 * are not built (no captured profile says where they live; `mocks/profile-viewer/DATA.md`).
 */
object FishingSection : ProfileSection {
    override val id = "fishing"
    override val title = "Fishing"
    override val group = ProfileSection.GROUP_ACTIVITIES
    override val icon = "pv_fish"

    /** Mock `tierC`: bronze, silver, gold, diamond. */
    private val TIER_COLORS = intArrayOf(0xFF8A8A8A.toInt(), 0xFFC8C8C8.toInt(), Rich.mcColor('6') ?: KitStyle.TEXT, Rich.mcColor('b') ?: KitStyle.TEXT)
    private val TIER_NAMES = TROPHY_TIERS.map { it.replaceFirstChar { c -> c.uppercase() } }

    override fun build(ctx: ProfileContext, ui: Ui) {
        val fishing = ctx.member.skillsCapped(ctx.caps).firstOrNull { it.def.key == "fishing" }
        val counts = ctx.member.fishingCounts
        val trophies = ctx.member.trophyFishing
        val best = trophies?.bestTier ?: -1
        ui.tiles {
            if (fishing != null) {
                val l = fishing.level
                tile("Fishing", "${l.level}", sub = if (l.maxed) "max level" else "${Format.pct(l.progress, 0)} to ${l.level + 1}", bar = l.progress.toFloat(), maxed = l.maxed)
            } else {
                tile("Fishing", "—", sub = "Skills API off", locked = true)
            }
            tile("Sea creatures killed", counts.seaCreaturesKilled?.let { Format.num(it) } ?: "—", sub = "pet milestone count")
            tile("Items fished", counts.itemsFished?.let { Format.short(it) } ?: "—", sub = counts.treasure?.let { "${Format.num(it)} treasures" })
            tile("Trophy Fishing", if (best >= 0) TIER_NAMES[best] else "—", sub = "best tier caught", color = if (best >= 0) TIER_COLORS[best] else KitStyle.TEXT)
        }
        ui.grid(300) {
            card("Trophy Fish", span = 2, meta = { add(LegendNode(TIER_NAMES, TIER_COLORS)) }) {
                if (trophies == null) {
                    empty("pv_fish", "No trophy fish yet", "")
                } else {
                    add(TrophyGridNode(trophies.fish, TIER_NAMES, TIER_COLORS))
                }
            }
            // Phase 3: the local Soul fishing tracker card (own profile only) goes here, after Trophy Fish.
        }
    }

    /** `.c-s .legend`: a 6 px swatch and the tier name, per tier. */
    private class LegendNode(val names: List<String>, val colors: IntArray) : Node(), Inline {
        override fun inlineWidth(m: KitMeasure): Int = names.indices.sumOf { 6 + 2 + m.width(names[it], KitStyle.TINY) } + 6 * (names.size - 1)

        override fun measure(m: KitMeasure, w: Int): Int = 12

        override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
            var cx = x
            for ((i, name) in names.withIndex()) {
                c.rect(cx, y + h / 2 - 3, cx + 6, y + h / 2 + 3, colors[i], Corner.SMALL)
                cx += 8
                c.text(name, cx, y, y + h, KitStyle.TEXT_DIM, KitStyle.TINY)
                cx += c.width(name, KitStyle.TINY) + 6
            }
        }
    }

    /** `.trophies`: auto-fill columns of at least 230 px (18 px apart); per fish its name and four tier counts. */
    private class TrophyGridNode(val fish: List<TrophyFishEntry>, val tierNames: List<String>, val tierColors: IntArray) : Node() {
        private fun columns(w: Int) = KitLayout.autoFillColumns(w, MIN_COL, COL_GAP)

        override fun measure(m: KitMeasure, w: Int): Int {
            val rows = (fish.size + columns(w) - 1) / columns(w)
            return if (rows == 0) 0 else rows * ROW_H + (rows - 1) * ROW_GAP
        }

        override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
            val n = columns(w)
            val tracks = KitLayout.trackX(w, n, COL_GAP)
            for ((i, f) in fish.withIndex()) {
                val ry = y + (i / n) * (ROW_H + ROW_GAP)
                if (ry + ROW_H < c.viewTop || ry > c.viewBottom) continue
                val cx = x + tracks[i % n]
                val cw = KitLayout.spanWidth(tracks, i % n, 1, COL_GAP)
                paintRow(c, f, cx, ry, cw)
            }
        }

        private fun paintRow(c: KitCanvas, f: TrophyFishEntry, x: Int, y: Int, w: Int) {
            val none = f.bestTier < 0
            val name = SkyblockRarity.colorFor(f.rarity, KitStyle.TEXT).let { if (none) (it and 0x00FFFFFF) or (0x73 shl 24) else it }
            c.text(f.name, x, y, y + ROW_H - 1, name, KitStyle.SMALL)
            for (t in f.tiers.indices) {
                val right = x + w - (f.tiers.size - 1 - t) * (COUNT_W + 4)
                val v = f.tiers[t]
                val label = if (v > 0) Format.short(v.toLong()) else "·"
                c.text(label, right - c.width(label, KitStyle.CAPTION, true), y, y + ROW_H - 1, if (v > 0) tierColors[t] else KitStyle.TEXT_GHOST, KitStyle.CAPTION, true)
            }
            c.rect(x, y + ROW_H - 1, x + w, y + ROW_H, KitStyle.TD_LINE, Corner.SQUARE)
            val lines = listOf(f.name) + f.tiers.indices.map { "${tierNames[it]}: ${Format.num(f.tiers[it])}" } + "Total: ${Format.num(f.total)}"
            c.tooltip(x, y, x + w, y + ROW_H, lines, "trophy:${f.key}")
        }

        private companion object {
            const val MIN_COL = 230
            const val COL_GAP = 18
            const val ROW_H = 18
            const val ROW_GAP = 1
            const val COUNT_W = 30
        }
    }
}
