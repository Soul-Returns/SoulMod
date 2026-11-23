package com.soulreturns.features.profileviewer.gui.sections

import com.soulreturns.data.hypixel.OwnedSack
import com.soulreturns.data.hypixel.SackLine
import com.soulreturns.data.items.ItemIcons
import com.soulreturns.data.sacks.SackCatalogIndex
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.bar
import com.soulreturns.features.profileviewer.gui.kit.framed
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.max
import kotlin.math.min

/** One owned sack (or the "Other items" fallback) and the sack lines that belong to it. */
internal class SackGroup(val key: String, val name: String, val owned: OwnedSack?, val lines: List<SackLine>)

/** [groups] in owned order, [other] = lines in no known sack, [unknown] = owned sacks the catalog has no entry for. */
internal class SackGrouping(val groups: List<SackGroup>, val other: SackGroup?, val unknown: List<OwnedSack>)

internal object SackView {
    const val OTHER_KEY = "OTHER"

    /**
     * Sorts every sack line into the owned sacks whose catalog entry lists it. An item that several owned sacks list
     * is in each of them (totals count it once, see [totals]); an item in no owned, catalogued sack lands in
     * [SackGrouping.other], so nothing disappears (an empty catalog puts everything there). A catalogued item the
     * profile has no count for (`sacks_counts` omits some, e.g. baits) still shows in its sack with 0.
     */
    fun group(owned: List<OwnedSack>, catalog: SackCatalogIndex, lines: List<SackLine>): SackGrouping {
        val groups = ArrayList<SackGroup>()
        val unknown = ArrayList<OwnedSack>()
        val covered = HashSet<String>()
        val byId = lines.associateBy { it.id }
        for (sack in owned) {
            val entry = catalog.byKey(sack.key) ?: catalog.byName(sack.name)
            val runes = if (sack.key == RUNE_SACK) lines.map { it.id }.filter(::isRune).takeIf { it.isNotEmpty() } else null
            val ids = entry?.items?.distinct() ?: runes
            if (ids == null) {
                unknown += sack
                continue
            }
            covered += ids
            val members = ids.map { byId[it] ?: SackLine(it, 0, false) }
            groups += SackGroup(sack.key, sack.name, sack, members.sortedWith(compareByDescending<SackLine> { it.count }.thenBy { it.id }))
        }
        val rest = lines.filter { it.id !in covered }
        return SackGrouping(groups, if (rest.isEmpty()) null else SackGroup(OTHER_KEY, "Other items", null, rest), unknown)
    }

    /**
     * The Rune Sack's rows carry no SkyBlock ids (live 2026-11-23: player heads named "◆ Blood Rune" with I/II/III
     * counts), so the reporter learns nothing there; `sacks_counts` keys its runes `RUNE_<NAME>_<tier>` with Hypixel's
     * internal names (`RUNE_ZOMBIE_SLAYER_1` is the Pestilence Rune), and those make up the Rune Sack.
     */
    private const val RUNE_SACK = "RUNE_SACK"
    private val RUNE_ID = Regex("""^RUNE_([A-Z0-9_]+)_(\d+)$""")

    fun isRune(id: String): Boolean = RUNE_ID.matches(id)

    /** `RUNE_ZOMBIE_SLAYER_1` = "Zombie Slayer Rune I"; null for any other id. */
    fun runeName(id: String): String? {
        val m = RUNE_ID.matchEntire(id) ?: return null
        val name = m.groupValues[1].split('_').joinToString(" ") { w -> w.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) } }
        val tier = m.groupValues[2].toInt()
        return "$name Rune ${ROMAN.getOrElse(tier) { tier.toString() }}"
    }

    /** The market key of a sack id: runes are `<NAME>_RUNE;<tier>` there (rule://catalogs-prices), the rest unchanged. */
    fun marketKey(id: String): String {
        val m = RUNE_ID.matchEntire(id) ?: return id
        return "${m.groupValues[1]}_RUNE;${m.groupValues[2]}"
    }

    private val ROMAN = listOf("", "I", "II", "III", "IV", "V")

    /** Whole-profile figures over each distinct item once: value, count, kinds, most valuable item. */
    class Totals(val value: Long, val items: Long, val kinds: Int, val top: SackLine?, val topValue: Long)

    fun totals(lines: List<SackLine>, price: (String) -> Long): Totals {
        var value = 0L
        var items = 0L
        var kinds = 0
        var top: SackLine? = null
        var topValue = 0L
        for (line in lines) {
            if (line.count <= 0) continue
            val v = price(line.id) * line.count
            value += v
            items += line.count
            kinds++
            if (top == null || v > topValue) {
                top = line
                topValue = v
            }
        }
        return Totals(value, items, kinds, top, topValue)
    }

    class Row(val id: String, val name: String, val count: Long, val cap: Long, val price: Long, val soul: Boolean) {
        val value: Long get() = price * count
        val fraction: Float get() = if (cap > 0) min(1f, count.toFloat() / cap) else 0f
        val full: Boolean get() = cap > 0 && count > 0 && fraction >= FULL_AT
    }

    class Tile(val key: String, val name: String, val color: Int, val sub: String, val owned: OwnedSack?, val rows: List<Row>) {
        val value: Long = rows.sumOf { it.value }
        val count: Long = rows.sumOf { it.count }
        val full: Int = rows.count { it.full }
        val soul: Boolean = rows.any { it.soul }
    }

    const val FULL_AT = 0.95f

    private val RARITY_CODES = mapOf(
        "COMMON" to 'f', "UNCOMMON" to 'a', "RARE" to '9', "EPIC" to '5', "LEGENDARY" to '6',
        "MYTHIC" to 'd', "DIVINE" to 'b', "SPECIAL" to 'c', "VERY SPECIAL" to 'c',
    )

    fun rarityColor(rarity: String?): Int = RARITY_CODES[rarity]?.let(Rich::mcColor) ?: S.TEXT

    /** `Large + Medium`, `×2`, `3× stitched`: the line under a sack's name, empty when the sack has none of them. */
    fun sub(sack: OwnedSack): String =
        listOfNotNull(
            sack.sizes.joinToString(" + ").takeIf { it.isNotEmpty() },
            if (sack.copies > 1) "×${sack.copies}" else null,
            if (sack.stitches > 0) "${sack.stitches}× stitched" else null,
        ).joinToString(" · ")

    /**
     * The tiles to draw: [shown] filters a line (search, Hide empty); a sack with no shown line is dropped while a
     * filter is on ([filtering]) and kept (empty) otherwise. Tiles and their rows follow [sort] (`value`, `count`
     * or `name`); the "Other items" tile always goes last.
     */
    fun tiles(
        grouping: SackGrouping,
        shown: (SackLine) -> Boolean,
        filtering: Boolean,
        sort: String,
        name: (String) -> String,
        price: (String) -> Long,
    ): List<Tile> {
        val rowOrder: Comparator<Row> = when (sort) {
            "count" -> compareByDescending<Row> { it.count }.thenBy { it.name.lowercase(Locale.ROOT) }
            "name" -> compareBy { it.name.lowercase(Locale.ROOT) }
            else -> compareByDescending<Row> { it.value }.thenBy { it.name.lowercase(Locale.ROOT) }
        }
        fun build(group: SackGroup): Tile? {
            val cap = group.owned?.capacity ?: 0L
            val rows = group.lines.filter(shown).map { Row(it.id, name(it.id), it.count, cap, price(it.id), it.fromSoul) }.sortedWith(rowOrder)
            if (rows.isEmpty() && filtering) return null
            val owned = group.owned
            return Tile(
                group.key, group.name, rarityColor(owned?.rarity),
                if (owned != null) sub(owned) else "Not in a known sack", owned, rows,
            )
        }
        val tiles = grouping.groups.mapNotNull(::build)
        val ordered = when (sort) {
            "count" -> tiles.sortedWith(compareByDescending<Tile> { it.count }.thenBy { it.name })
            "name" -> tiles.sortedBy { it.name.lowercase(Locale.ROOT) }
            else -> tiles.sortedWith(compareByDescending<Tile> { it.value }.thenBy { it.name })
        }
        return ordered + listOfNotNull(grouping.other?.let(::build))
    }
}

/**
 * Design b of `mocks/pv-sacks/`: sack tiles, three to a row, and a drawer under the selected tile's row listing the
 * sack's items. A tile click toggles [stateKey] (the selected tile's key). One-off visual, so it lives here and not
 * in the kit.
 */
internal class SackTilesNode(
    private val tiles: List<SackView.Tile>,
    private val selected: String,
    private val stateKey: String,
    private val icon: (SackView.Tile) -> ItemStack?,
) : Node() {
    private fun cols(w: Int) = ((w + TILE_GAP) / (TILE_MIN_W + TILE_GAP)).coerceIn(1, 3)

    private fun selectedIndex(): Int = tiles.indexOfFirst { it.key == selected }

    private fun drawerCols(w: Int) = ((w - DRAWER_PAD_X * 2 + COL_GAP) / (DRAWER_COL_MIN + COL_GAP)).coerceIn(1, 3)

    private fun drawerHeight(tile: SackView.Tile, w: Int): Int {
        val body = if (tile.rows.isEmpty()) ROW_H else ((tile.rows.size + drawerCols(w) - 1) / drawerCols(w)) * ROW_H
        return DRAWER_PAD_Y + HEAD_H + HEAD_GAP + body + DRAWER_PAD_Y
    }

    override fun measure(m: KitMeasure, w: Int): Int {
        if (tiles.isEmpty()) return 0
        val cols = cols(w)
        val rows = (tiles.size + cols - 1) / cols
        var h = rows * TILE_H + (rows - 1) * TILE_GAP
        val sel = selectedIndex()
        if (sel >= 0) h += TILE_GAP + drawerHeight(tiles[sel], w)
        return h
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (tiles.isEmpty()) return
        c.layer()
        val cols = cols(w)
        val tileW = (w - TILE_GAP * (cols - 1)) / cols
        val sel = selectedIndex()
        val drawerRow = if (sel < 0) -1 else sel / cols
        var ry = y
        var row = 0
        while (row * cols < tiles.size) {
            if (ry + TILE_H >= c.viewTop && ry <= c.viewBottom) {
                for (k in 0 until cols) {
                    val i = row * cols + k
                    if (i >= tiles.size) break
                    paintTile(c, tiles[i], x + k * (tileW + TILE_GAP), ry, tileW, i == sel)
                }
            }
            ry += TILE_H + TILE_GAP
            if (row == drawerRow) {
                val dh = drawerHeight(tiles[sel], w)
                if (ry + dh >= c.viewTop && ry <= c.viewBottom) paintDrawer(c, tiles[sel], x, ry, w, dh)
                ry += dh + TILE_GAP
            }
            row++
        }
    }

    private fun paintTile(c: KitCanvas, t: SackView.Tile, x: Int, y: Int, w: Int, on: Boolean) {
        val hover = c.hovered(x, y, x + w, y + TILE_H)
        if (on) c.framed(x, y, x + w, y + TILE_H, ON_BG, S.ACCENT, Corner.MEDIUM) else c.rect(x, y, x + w, y + TILE_H, if (hover) HOVER_BG else BG, Corner.MEDIUM)
        val ix = x + PAD_X
        val top = y + PAD_Y
        val stack = icon(t)
        if (stack != null && !stack.isEmpty) c.item(stack, ix, top, ICON, count = false) else c.icon("pv_chest", ix + 4, top + 4, ICON - 8, S.TEXT_FAINT)
        // The right column: the value over a FULL chip.
        val value = if (t.value > 0) coins(t.value) else ""
        val chip = if (t.full > 0) Chip.warn("${t.full} FULL") else null
        val rightW = max(if (value.isEmpty()) 0 else c.width(value, S.SMALL, true), chip?.width(c) ?: 0)
        if (value.isNotEmpty()) c.text(value, x + w - PAD_X - c.width(value, S.SMALL, true), top, top + 14, S.COIN, S.SMALL, true)
        chip?.paint(c, x + w - PAD_X - chip.width(c), top + 14 + CHIP_H / 2 + 1)
        val tx = ix + ICON + 7
        val textW = x + w - PAD_X - rightW - 6 - tx
        val name = Rich.of(t.name).ellipsize(c, NAME_SIZE, textW - if (t.soul) DOT + 4 else 0, true)
        val end = name.draw(c, tx, top, top + 15, NAME_SIZE, t.color, true)
        if (t.soul) dot(c, end + 4, top + 5)
        if (t.sub.isNotEmpty()) Rich.of(t.sub).ellipsize(c, S.CAPTION, textW).draw(c, tx, top + 15, top + 27, S.CAPTION, S.TEXT_DIM)
        // Top items.
        val py = top + ICON + 6
        var px = ix
        val limit = x + w - PAD_X
        if (t.rows.isEmpty()) {
            c.text("Empty", px, py, py + 16, S.TEXT_FAINT, S.TINY)
        } else {
            var shown = 0
            for (r in t.rows.take(MINIS)) {
                val label = Format.short(r.count)
                val mw = MINI_ICON + 2 + c.width(label, S.TINY)
                if (px + mw > limit) break
                ItemIcons.icon(r.id)?.let { c.item(it, px, py, MINI_ICON, count = false) }
                c.text(label, px + MINI_ICON + 2, py, py + MINI_ICON, S.TEXT_MID, S.TINY)
                c.tooltip(px, py, px + mw, py + MINI_ICON, rowTip(r, t), "sk:${t.key}:${r.id}")
                px += mw + 6
                shown++
            }
            val more = t.rows.size - shown
            if (more > 0 && px + c.width("+$more", S.TINY) <= limit) c.text("+$more", px, py, py + MINI_ICON, S.TEXT_FAINT, S.TINY)
        }
        c.onClick(x, y, x + w, y + TILE_H) { c.state.set(stateKey, if (on) "" else t.key) }
    }

    private fun paintDrawer(c: KitCanvas, t: SackView.Tile, x: Int, y: Int, w: Int, h: Int) {
        c.framed(x, y, x + w, y + h, DRAWER_BG, DRAWER_EDGE, Corner.MEDIUM)
        val ix = x + DRAWER_PAD_X
        val iw = w - DRAWER_PAD_X * 2
        val top = y + DRAWER_PAD_Y
        val cap = t.owned?.capacity ?: 0L
        val worth = if (t.value > 0) coins(t.value) else "—"
        val info = buildString {
            if (t.owned != null && cap > 0) {
                append("holds ${Format.num(cap)} of each")
                if (t.owned.copies > 1) append(" (${t.owned.copies} sacks summed)")
                append(" · ")
            }
            append("${t.rows.size} item${if (t.rows.size == 1) "" else "s"} · ")
        }
        val right = Rich.join(Rich.of(info), Rich.colored(worth, S.COIN))
        val rw = right.width(c, S.CAPTION)
        right.draw(c, ix + iw - rw, top, top + HEAD_H, S.CAPTION, S.TEXT_DIM)
        Rich.of(t.name).ellipsize(c, S.BODY, iw - rw - 10, true).draw(c, ix, top, top + HEAD_H, S.BODY, t.color, true)
        val by = top + HEAD_H + HEAD_GAP
        if (t.rows.isEmpty()) {
            c.text("Nothing stored in this sack.", ix, by, by + ROW_H, S.TEXT_FAINT, S.TINY)
            return
        }
        val cols = drawerCols(w)
        val colW = (iw - COL_GAP * (cols - 1)) / cols
        for ((i, r) in t.rows.withIndex()) {
            val ry = by + (i / cols) * ROW_H
            if (ry + ROW_H < c.viewTop || ry > c.viewBottom) continue
            paintRow(c, t, r, ix + (i % cols) * (colW + COL_GAP), ry, colW)
        }
    }

    private fun paintRow(c: KitCanvas, t: SackView.Tile, r: SackView.Row, x: Int, y: Int, w: Int) {
        val zero = r.count == 0L
        if (c.hovered(x, y, x + w, y + ROW_H)) c.rect(x, y, x + w, y + ROW_H, HOVER_BG, Corner.SMALL)
        ItemIcons.icon(r.id)?.let { c.item(it, x + 3, y + (ROW_H - MINI_ICON) / 2, MINI_ICON, count = false) }
        var rx = x + w - 3
        val value = if (r.value > 0) coins(r.value) else if (zero) "0" else "—"
        rx -= VALUE_W
        c.text(value, rx + VALUE_W - c.width(value, S.CAPTION), y, y + ROW_H, if (zero) S.TEXT_GHOST else S.COIN, S.CAPTION)
        rx -= 6
        if (r.cap > 0) {
            rx -= FILL_W
            val bgFill = S.BAR_TRACK
            c.bar(rx, y + (ROW_H - 3) / 2, FILL_W, r.fraction, r.full, h = 3, track = bgFill)
            rx -= 6
        }
        val count = Format.num(r.count)
        val cw = c.width(count, S.SMALL, true)
        c.text(count, rx - cw, y, y + ROW_H, if (zero) S.TEXT_GHOST else if (r.soul) S.ACCENT_LIGHT else S.TEXT, S.SMALL, true)
        val nx = x + 3 + MINI_ICON + 6
        val nameW = rx - cw - 8 - nx - if (r.soul) DOT + 4 else 0
        val end = Rich.of(r.name).ellipsize(c, S.SMALL, nameW).draw(c, nx, y, y + ROW_H, S.SMALL, if (zero) S.TEXT_GHOST else S.TEXT, false)
        if (r.soul) dot(c, end + 4, y + (ROW_H - DOT) / 2)
        c.tooltip(x, y, x + w, y + ROW_H, rowTip(r, t), "sr:${t.key}:${r.id}")
    }

    private fun dot(c: KitCanvas, x: Int, y: Int) = c.rect(x, y, x + DOT, y + DOT, S.ACCENT, Corner.PILL)

    private fun rowTip(r: SackView.Row, t: SackView.Tile): List<String> = buildList {
        add("§f${r.name}")
        add("§7Stored: §e${Format.num(r.count)}" + if (r.cap > 0) "§7/${Format.num(r.cap)}" else "")
        if (r.price > 0) {
            add("§7Each: §6${coins(r.price)} coins")
            add("§7Value: §6${coins(r.value)} coins")
        } else {
            add("§7No price known")
        }
        add("§8${if (t.owned != null) t.name else "Not in a known sack"}")
        if (r.soul) {
            add("")
            add("§9Read from your sack menu, newer than Hypixel's")
        }
    }

    private fun coins(v: Long): String = if (v >= 10_000) Format.short(v) else Format.num(v)

    private companion object {
        const val TILE_H = 64
        const val TILE_GAP = 6
        const val TILE_MIN_W = 200
        const val PAD_X = 8
        const val PAD_Y = 7
        const val ICON = 28
        const val MINI_ICON = 16
        const val MINIS = 4
        const val DOT = 5
        const val CHIP_H = 12
        val NAME_SIZE = S.px(12f)
        const val DRAWER_PAD_X = 10
        const val DRAWER_PAD_Y = 8
        const val HEAD_H = 14
        const val HEAD_GAP = 4
        const val ROW_H = 19
        const val COL_GAP = 16
        const val DRAWER_COL_MIN = 230
        const val VALUE_W = 44
        const val FILL_W = 34
        val BG = 0xFF242424.toInt()
        val HOVER_BG = 0xFF2C2C2C.toInt()
        val ON_BG = 0xFF1E2A40.toInt()
        val DRAWER_BG = 0xFF1B2232.toInt()
        val DRAWER_EDGE = 0xFF2B3D5E.toInt()
    }
}
