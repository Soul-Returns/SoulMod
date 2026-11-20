package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.filterAccessorySlots
import com.soulreturns.data.hypixel.ItemBlobs
import com.soulreturns.data.hypixel.MagicalPower
import com.soulreturns.data.hypixel.accessoryBagBlob
import com.soulreturns.data.hypixel.abiphoneContacts
import com.soulreturns.data.hypixel.activeTuning
import com.soulreturns.data.hypixel.bagUpgradesPurchased
import com.soulreturns.data.hypixel.magicalPower
import com.soulreturns.data.hypixel.powerDisplayName
import com.soulreturns.data.hypixel.riftPrismConsumed
import com.soulreturns.data.hypixel.selectedPower
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui
import kotlin.math.max

/** Magical Power and the accessory bag (mock `accessoriesSec`). */
object AccessoriesSection : ProfileSection {
    override val id = "accessories"
    override val title = "Accessories"
    override val group = ProfileSection.GROUP_ITEMS
    override val icon = "pv_ring"
    override val subtitle = "Magical Power and the accessory bag"

    override fun locked(ctx: ProfileContext): String? = if (ctx.member.apiAccessDisabled) "Inventory API off" else null

    override fun badge(ctx: ProfileContext): String? = ctx.member.magicalPower?.let { Format.num(it) }

    /** Mock `RAR`: the chat color code of each rarity. */
    private val RARITY_CODE = mapOf(
        "COMMON" to 'f', "UNCOMMON" to 'a', "RARE" to '9', "EPIC" to '5', "LEGENDARY" to '6',
        "MYTHIC" to 'd', "SPECIAL" to 'c', "VERY SPECIAL" to 'c', "DIVINE" to 'b',
    )

    override fun build(ctx: ProfileContext, ui: Ui) {
        val m = ctx.member
        val blob = m.accessoryBagBlob
        if (blob == null) {
            ui.apiOff(ctx.name, ctx.isSelf, "Inventory API", "the accessory bag and Magical Power")
            return
        }
        val parsed = ItemBlobs.parse(blob)
        val decoded = ctx.items.get(blob)
        if (parsed == null) {
            ui.empty("pv_ring", "The accessory bag could not be read", "Hypixel's data for it is damaged or in a format Soul does not know.")
            return
        }
        val items = parsed.filterNotNull()
        val breakdown = MagicalPower.breakdown(items, m.riftPrismConsumed, m.abiphoneContacts ?: 0)
        val reported = m.magicalPower
        val recombs = items.count { it.recombobulated }
        val upgrades = m.bagUpgradesPurchased
        val tuning = m.activeTuning

        ui.tiles {
            tile("Magical Power", if (reported != null) Format.num(reported) else Format.num(breakdown.total), sub = if (reported != null) "as Hypixel reports it" else "counted from the bag", tip = "accessory_bag_storage.highest_magical_power")
            tile("Power", m.selectedPower?.takeIf { it.isNotBlank() }?.let(::powerDisplayName) ?: "—", sub = "selected at Maxwell")
            tile("Accessories", Format.num(items.size), sub = "$recombs recombobulated" + if (breakdown.duplicates > 0) " · ${breakdown.duplicates} repeated" else "")
            tile("Bag slots", Format.num(parsed.size), sub = if (upgrades != null) "$upgrades upgrades bought" else "upgrades not reported")
            tile(
                "Tuning",
                if (tuning.isEmpty()) Rich.of("—") else Rich.join(*tuning.flatMap { listOf(Rich.of("+${it.points} "), Rich.colored(it.stat, S.TEXT_DIM), Rich.of("  ")) }.toTypedArray()),
                sub = "points from Magical Power",
                tip = tuning.takeIf { it.isNotEmpty() }?.joinToString(" · ") { "${it.stat} +${it.points}" },
            )
        }

        val filter = ui.value("rarity", "ALL")
        val q = ui.query("query")
        val shown = filterAccessorySlots(parsed, filter, q)

        ui.grid(290) {
            card("Magical Power", sub = "where it comes from") {
                add(MpRowsNode(breakdown.rows, breakdown.total))
                if (reported != null && reported != breakdown.total) {
                    foot("Hypixel reports ${Format.num(reported)}: it counts only the best accessory of an upgrade family.")
                }
            }
            card(
                "Accessory bag", sub = "${shown.size} shown", span = 2,
                tools = {
                    seg("rarity", "ALL", listOf("ALL" to "All", "LEGENDARY" to "Legendary", "EPIC" to "Epic", "RARE" to "Rare", "UNCOMMON" to "Uncommon", "COMMON" to "Common"))
                    search("query", "Filter")
                },
            ) {
                if (decoded.failed) {
                    text("The accessory items could not be drawn.", S.SMALL, S.TEXT_DIM)
                } else if (!decoded.ready) {
                    skeleton(3)
                } else if (shown.isEmpty()) {
                    empty("pv_search", "No accessory matches", "Try another rarity or word.")
                } else {
                    slots(shown.map { decoded.stacks.getOrNull(it) }, cols = 0)
                }
            }
        }
    }

    /** `.mprows` and `.mpsum`: one row per rarity (label, count × each, a bar, the power) and the total below a rule. */
    private class MpRowsNode(val rows: List<MagicalPower.Row>, val total: Int) : Node() {
        override fun measure(m: KitMeasure, w: Int): Int =
            (if (rows.isEmpty()) 0 else rows.size * ROW_H + (rows.size - 1) * ROW_GAP + SUM_GAP) + SUM_PAD + SUM_H

        override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
            val maxPower = max(1, rows.maxOfOrNull { it.power } ?: 1)
            val barX = x + LABEL_W + COL_GAP + COUNT_W + COL_GAP
            val barW = max(0, w - LABEL_W - COUNT_W - VALUE_W - COL_GAP * 3)
            var cy = y
            for (row in rows) {
                val top = cy
                val bottom = cy + ROW_H
                val color = row.rarity?.let { RARITY_CODE[it] }?.let(Rich::mcColor) ?: S.TEXT_SOFT
                c.text(row.label, x, top, bottom, color, S.SMALL)
                val count = if (row.each != null) "${row.count} × ${row.each}" else if (row.count > 1) "${row.count}" else ""
                if (count.isNotEmpty()) c.text(count, x + LABEL_W + COL_GAP, top, bottom, S.TEXT_DIM, S.CAPTION)
                val by = top + (ROW_H - S.HB_H) / 2
                c.rect(barX, by, barX + barW, by + S.HB_H, S.HB_TRACK, Corner.PILL)
                val fill = (barW * row.power.toFloat() / maxPower).toInt()
                if (fill > 0) c.rect(barX, by, barX + max(fill, S.HB_H), by + S.HB_H, S.ACCENT, Corner.PILL)
                val value = "${row.power}"
                c.text(value, x + w - c.width(value, S.SMALL, true), top, bottom, S.TEXT, S.SMALL, true)
                cy += ROW_H + ROW_GAP
            }
            if (rows.isNotEmpty()) cy += SUM_GAP - ROW_GAP
            c.rect(x, cy, x + w, cy + 1, S.TH_LINE, Corner.SQUARE)
            cy += SUM_PAD
            c.text("Total", x, cy, cy + SUM_H, S.TEXT_DIM, S.SMALL)
            val value = Format.num(total)
            c.text(value, x + w - c.width(value, S.SMALL, true), cy, cy + SUM_H, S.TEXT, S.SMALL, true)
        }

        private companion object {
            const val ROW_H = 16
            const val ROW_GAP = 4
            const val LABEL_W = 108
            const val COUNT_W = 44
            const val VALUE_W = 30
            const val COL_GAP = 8
            const val SUM_GAP = 6
            const val SUM_PAD = 6
            const val SUM_H = 13
        }
    }
}
