package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.CollectionProgress
import com.soulreturns.data.hypixel.MinionProgress
import com.soulreturns.data.hypixel.collectionGroups
import com.soulreturns.data.hypixel.craftedMinions
import com.soulreturns.data.hypixel.hasCollections
import com.soulreturns.data.hypixel.uniqueMinionCrafts
import com.soulreturns.data.items.CollectionIcons
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.features.profileviewer.gui.ProfileContext
import com.soulreturns.features.profileviewer.gui.ProfileSection
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.CustomNode
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.Ui
import java.util.Locale

/**
 * Collections grouped by category and crafted minions (mock `collectionsSec`).
 *
 * Counts and unlocked tiers are what the response states: no max-tier table is bundled. Names come from
 * [com.soulreturns.data.hypixel.CollectionTable] for the listed collections; an id outside it is named
 * by the item catalog (`GET /items`) when that knows it and by its prettified id otherwise.
 */
object CollectionsSection : ProfileSection {
    override val id = "collections"
    override val title = "Collections"
    override val group = ProfileSection.GROUP_PROGRESS
    override val icon = "pv_boxes"
    override val subtitle = "Collections and minions"

    /** Mock `.cr` row height (min-height 17 with a 1 px line) and the tier column's width. */
    private const val ROW_H = S.TD_H
    private const val TIER_W = 26

    /** Mock: a minion at tier XI or above is drawn gold (`.perk.max`). */
    private const val MAX_MINION_TIER = 11

    override fun locked(ctx: ProfileContext): String? = if (!ctx.member.hasCollections) "Collections API off" else null

    override fun build(ctx: ProfileContext, ui: Ui) {
        val member = ctx.member
        if (!member.hasCollections) {
            ui.apiOff(ctx.name, ctx.isSelf, "Collections API", "collections")
            return
        }
        val tab = ui.value("tab", "coll")
        ui.row {
            seg("tab", "coll", listOf("coll" to "Collections", "min" to "Minions"))
            search("query", if (tab == "coll") "Filter collections" else "Filter minions")
        }
        val q = ui.query("query")
        if (tab == "coll") {
            val groups = member.collectionGroups { ItemCatalogClient.byId(it)?.displayName }.orEmpty()
            val shown = groups.map { g -> g to g.entries.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) } }.filter { it.second.isNotEmpty() }
            if (shown.isEmpty()) {
                ui.empty("pv_boxes", "No collection matches", "Nothing is called \"$q\".")
                return
            }
            ui.masonry(240) {
                for ((g, entries) in shown) {
                    card(g.category, sub = "${g.unlocked} of ${g.entries.size} unlocked") { add(collectionRows(entries)) }
                }
            }
        } else {
            val all = member.craftedMinions.orEmpty()
            if (all.isEmpty()) {
                ui.empty("pv_boxes", "No minions crafted", "${ctx.name} has not crafted a minion on ${ctx.profile.cuteName}.")
                return
            }
            ui.tiles {
                tile("Unique minions", Format.num(member.uniqueMinionCrafts ?: 0), sub = "tiers crafted")
                tile("Types", "${all.size}", sub = "with at least tier I")
                tile("At tier XI+", "${all.count { it.tier >= MAX_MINION_TIER }}", sub = "types")
            }
            val shown = all.filter { q.isEmpty() || it.name.lowercase(Locale.ROOT).contains(q) }
            ui.card("Minions", span = Node.FULL, sub = "highest tier crafted") {
                if (shown.isEmpty()) text("No minion is called \"$q\".", color = S.TEXT_DIM) else add(minionPills(shown))
            }
        }
    }

    /** Item icon size in a `.cr` row and its gap to the name. */
    private const val ICON = 16
    private const val ICON_GAP = 4

    /**
     * `.cr` rows: the collection item's icon, name, count (short) and tier, a started-but-untiered or unstarted row
     * greyed. Names stay aligned: the icon column exists when any row of the card has an icon.
     */
    private fun collectionRows(rows: List<CollectionProgress>): Node =
        CustomNode({ _, _ -> rows.size * ROW_H }) { c, x, y, w, _ ->
            val icons = rows.map { CollectionIcons.collection(it.id, it.name) }
            val nx = if (icons.any { it != null }) x + ICON + ICON_GAP else x
            for ((i, r) in rows.withIndex()) {
                val top = y + i * ROW_H
                if (top + ROW_H < c.viewTop || top > c.viewBottom) continue
                val bottom = top + ROW_H - 1
                val none = r.tier <= 0
                val tier = if (none) "—" else Format.roman(r.tier)
                c.text(tier, x + w - c.width(tier, S.SMALL, true), top, bottom, if (none) S.TEXT_GHOST else S.TEXT, S.SMALL, true)
                val count = if (r.count > 0) Format.short(r.count) else "—"
                val cx = x + w - TIER_W - 8 - c.width(count, S.CAPTION)
                c.text(count, cx, top, bottom, S.TEXT_DIM, S.CAPTION)
                icons[i]?.let { c.item(it, x, top + (ROW_H - 1 - ICON) / 2, ICON, count = false) }
                Rich.of(r.name).ellipsize(c, S.SMALL, cx - 8 - nx).draw(c, nx, top, bottom, S.SMALL, if (none) S.TEXT_GHOST else S.TEXT_SOFT)
                if (i < rows.lastIndex) c.rect(x, bottom, x + w, bottom + 1, S.TD_LINE, Corner.SQUARE)
            }
        }

    // `.perk`: the minion's head, name, then the tier in a darker box; padding 2 4 2 8, 6 between, 4 between pills.
    private const val PILL_H = 18
    private const val PILL_GAP = 4
    private const val HEAD = 14
    private val PILL_BOX_BG = 0xFF1A1A1A.toInt()

    private fun tierBoxW(m: KitMeasure, p: MinionProgress) = m.width(Format.roman(p.tier), S.SMALL, true) + 8

    /** Left padding up to the name: 8, or the head (3 + 14 + 4) when one is known. */
    private fun nameX(p: MinionProgress) = if (CollectionIcons.minion(p.id, p.tier) != null) 3 + HEAD + 4 else 8

    private fun pillW(m: KitMeasure, p: MinionProgress) = nameX(p) + m.width(p.name, S.SMALL) + 6 + tierBoxW(m, p) + 4

    /** Where each pill goes: its left x and line, plus the line count, wrapping like `flex-wrap`. */
    private class PillFlow(val x: IntArray, val line: IntArray, val lines: Int) {
        val height: Int get() = if (lines == 0) 0 else lines * PILL_H + (lines - 1) * PILL_GAP
    }

    private fun flow(m: KitMeasure, w: Int, pills: List<MinionProgress>): PillFlow {
        val xs = IntArray(pills.size)
        val lines = IntArray(pills.size)
        var x = 0
        var line = 0
        for ((i, p) in pills.withIndex()) {
            val pw = pillW(m, p)
            if (x > 0 && x + pw > w) {
                x = 0
                line++
            }
            xs[i] = x
            lines[i] = line
            x += pw + PILL_GAP
        }
        return PillFlow(xs, lines, if (pills.isEmpty()) 0 else line + 1)
    }

    private fun minionPills(pills: List<MinionProgress>): Node =
        CustomNode({ m, w -> flow(m, w, pills).height }) { c, x, y, w, _ ->
            val f = flow(c, w, pills)
            for ((i, p) in pills.withIndex()) {
                val px = x + f.x[i]
                val py = y + f.line[i] * (PILL_H + PILL_GAP)
                if (py + PILL_H < c.viewTop || py > c.viewBottom) continue
                val pw = pillW(c, p)
                c.rect(px, py, px + pw, py + PILL_H, S.TIER_BG, Corner.SMALL)
                CollectionIcons.minion(p.id, p.tier)?.let { c.item(it, px + 3, py + (PILL_H - HEAD) / 2, HEAD, count = false) }
                val nx = px + nameX(p)
                c.text(p.name, nx, py, py + PILL_H, S.TEXT_MID, S.SMALL)
                val bx = nx + c.width(p.name, S.SMALL) + 6
                val bw = tierBoxW(c, p)
                c.rect(bx, py + 2, bx + bw, py + PILL_H - 2, PILL_BOX_BG, Corner.SMALL)
                c.text(Format.roman(p.tier), bx + 4, py, py + PILL_H, if (p.tier >= MAX_MINION_TIER) S.MAXED else S.TEXT, S.SMALL, true)
            }
        }
}
