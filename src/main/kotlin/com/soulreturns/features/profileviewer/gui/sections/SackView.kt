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
     * profile has no count for (`sacks_counts` omits some, e.g. baits) still shows in its sack with 0. Other items
     * skips zero counts: `sacks_counts` keeps 0 under ids no sack holds any more (`ENCHANTED_HAY_BLOCK`,
     * `PERFECT_OPAL_GEM`), so a 0 there is noise.
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
        val rest = lines.filter { it.id !in covered && it.count > 0 }
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

    /**
     * The in-game name of a `RUNE_<NAME>_<tier>` id: the one the backend's auction scan saw for its [marketKey]
     * (`RUNE_ZOMBIE_SLAYER_1` = "Pestilence Rune I", `/prices` `names`), else derived from the id ("Zombie Slayer
     * Rune I"). Null for any other id.
     */
    fun runeName(id: String): String? {
        val m = RUNE_ID.matchEntire(id) ?: return null
        InventoryLookups.marketName(marketKey(id))?.takeIf { it.isNotBlank() }?.let { return it }
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

    /** [gems] = the Gemstone Sack's rows grouped per type, null for any other sack; [loose] = rows in no group. */
    class Tile(val key: String, val name: String, val color: Int, val sub: String, val owned: OwnedSack?, val rows: List<Row>, val gems: List<Gem>? = null) {
        val value: Long = rows.sumOf { it.value }
        val count: Long = rows.sumOf { it.count }
        val full: Int = rows.count { it.full } + (gems?.count { it.full } ?: 0)
        val soul: Boolean = rows.any { it.soul }
        val loose: List<Row> = if (gems == null) rows else rows.filter { !GEM_ID.matches(it.id) }
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
            val gemSack = isGemSack(group)
            // A Gemstone Sack's capacity holds per gemstone in Rough equivalents, so it is no tier's own limit.
            val rows = group.lines.filter(shown).map {
                Row(it.id, name(it.id), it.count, if (gemSack && GEM_ID.matches(it.id)) 0L else cap, price(it.id), it.fromSoul)
            }.sortedWith(rowOrder)
            if (rows.isEmpty() && filtering) return null
            val owned = group.owned
            return Tile(
                group.key, group.name, rarityColor(owned?.rarity),
                if (owned != null) sub(owned) else "Not in a known sack", owned, rows,
                if (gemSack) gems(rows, sort, cap) else null,
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

    /**
     * The Gemstone Sack holds every gemstone in three tiers (`ROUGH_/FLAWED_/FINE_<TYPE>_GEM`, 36 rows); its drawer
     * shows one entry per type instead. Like the game's sack, a type counts in Rough: 80 Rough = 1 Flawed, 80 Flawed
     * = 1 Fine, and the sack's capacity is per type in those Rough equivalents.
     */
    private const val GEMSTONE_SACK = "GEMSTONE_SACK"
    private val GEM_ID = Regex("""^(ROUGH|FLAWED|FINE)_([A-Z]+)_GEM$""")
    private val GEM_TIERS = listOf("ROUGH", "FLAWED", "FINE")
    private const val GEM_STEP = 80L

    /** One shown tier of a [Gem]: `Rough`/`Flawed`/`Fine`, its row and how many Rough one of it is worth. */
    class GemTier(val label: String, val row: Row, val rough: Long) {
        val roughEq: Long get() = row.count * rough
    }

    /** One gemstone type with its shown tiers; [roughEq] = the type's total in Rough, [cap] = the sack capacity (0 = unknown). */
    class Gem(val type: String, val name: String, rough: Row?, flawed: Row?, fine: Row?, val cap: Long) {
        val tiers: List<GemTier> = listOfNotNull(
            rough?.let { GemTier("Rough", it, 1L) },
            flawed?.let { GemTier("Flawed", it, GEM_STEP) },
            fine?.let { GemTier("Fine", it, GEM_STEP * GEM_STEP) },
        )
        val value: Long = tiers.sumOf { it.row.value }
        val roughEq: Long = tiers.sumOf { it.roughEq }
        val soul: Boolean = tiers.any { it.row.soul }
        val full: Boolean = cap > 0 && roughEq > 0 && roughEq.toFloat() / cap >= FULL_AT
    }

    fun isGemSack(group: SackGroup): Boolean =
        group.key == GEMSTONE_SACK || (group.lines.isNotEmpty() && group.lines.all { GEM_ID.matches(it.id) })

    /**
     * [rows] grouped per gemstone type, tiers Rough/Flawed/Fine; rows of other ids are left out (see [Tile.loose]).
     * A type exists when at least one of its tiers is in [rows], so a filter hides a type only with all its tiers.
     * Order: [sort] `value` = summed tier value, `count` = [Gem.roughEq], `name` = type name.
     */
    fun gems(rows: List<Row>, sort: String, cap: Long): List<Gem> {
        val byType = LinkedHashMap<String, Array<Row?>>()
        for (r in rows) {
            val m = GEM_ID.matchEntire(r.id) ?: continue
            byType.getOrPut(m.groupValues[2]) { arrayOfNulls(3) }[GEM_TIERS.indexOf(m.groupValues[1])] = r
        }
        val gems = byType.map { (type, t) -> Gem(type, type.lowercase(Locale.ROOT).replaceFirstChar { it.titlecase(Locale.ROOT) }, t[0], t[1], t[2], cap) }
        return when (sort) {
            "count" -> gems.sortedWith(compareByDescending<Gem> { it.roughEq }.thenBy { it.name })
            "name" -> gems.sortedBy { it.name }
            else -> gems.sortedWith(compareByDescending<Gem> { it.value }.thenBy { it.name })
        }
    }

    /** The game's tooltip lines for a type: `Rough: 1 (1)` per shown tier (count, Rough equivalent), then `Stored: 1,917,681 / 18.6M`. */
    fun gemStored(g: Gem): List<String> =
        g.tiers.map { "§7${it.label}: §f${Format.num(it.row.count)} §8(${Format.num(it.roughEq)})" } +
            ("§7Stored: §e${Format.num(g.roughEq)}" + if (g.cap > 0) "§7 / ${Format.short(g.cap)}" else "")

    /**
     * A summary tile's click ("Top sack", "Most valuable"): select [tile], highlight [row] (an item id; for a gemstone
     * tier the drawer highlights its gemstone entry) and scroll there. [at] = the click's clock, for the pulse.
     */
    class Jump(val tile: String, val row: String?, val at: Long) {
        fun encode(): String = "$tile|${row.orEmpty()}|$at"
    }

    fun parseJump(s: String): Jump? {
        val p = s.split('|')
        if (p.size != 3 || p[0].isEmpty()) return null
        return Jump(p[0], p[1].ifEmpty { null }, p[2].toLongOrNull() ?: return null)
    }

    /**
     * "Most valuable": the first tile in [tiles] (unfiltered, in the current order) that lists [id], so of several
     * owned sacks the one shown first; an item in no owned sack is under Other items, which is last.
     */
    fun jumpToItem(tiles: List<Tile>, id: String, at: Long): Jump? =
        tiles.firstOrNull { t -> t.rows.any { it.id == id } }?.let { Jump(it.key, id, at) }

    /**
     * Whether the filtered [shown] tiles lack the jump's tile or row. A jump clears the search then; Hide empty
     * never hides a target (both summary tiles point at items with a count), so it stays as the player set it.
     */
    fun hides(shown: List<Tile>, jump: Jump): Boolean {
        val tile = shown.firstOrNull { it.key == jump.tile } ?: return true
        return jump.row != null && tile.rows.none { it.id == jump.row }
    }

    /** The jump highlight's strength [elapsed] ms after the click: full for 300 ms, then fading out until 1.5 s. */
    fun pulse(elapsed: Long): Float = when {
        elapsed < 0 || elapsed >= 1500 -> 0f
        elapsed < 300 -> 1f
        else -> 1f - (elapsed - 300).toFloat() / 1200f
    }
}

/**
 * Design b of `mocks/pv-sacks/`: sack tiles, three to a row, and a drawer under the selected tile's row listing the
 * sack's items. A tile click toggles [stateKey] (the selected tile's key). [jump] (a summary tile's click) pulses its
 * tile and row and scrolls them into view once. One-off visual, so it lives here and not in the kit.
 */
internal class SackTilesNode(
    private val tiles: List<SackView.Tile>,
    private val selected: String,
    private val stateKey: String,
    private val jump: SackView.Jump?,
    private val icon: (SackView.Tile) -> ItemStack?,
) : Node() {
    private fun cols(w: Int) = ((w + TILE_GAP) / (TILE_MIN_W + TILE_GAP)).coerceIn(1, 3)

    private fun selectedIndex(): Int = tiles.indexOfFirst { it.key == selected }

    private fun drawerCols(w: Int) = ((w - DRAWER_PAD_X * 2 + COL_GAP) / (DRAWER_COL_MIN + COL_GAP)).coerceIn(1, 3)

    private fun lines(n: Int, cols: Int) = (n + cols - 1) / cols

    /** A gem sack's drawer: [SackView.Tile.gems] blocks ([GEM_H]) first, then its [SackView.Tile.loose] rows. */
    private fun drawerHeight(tile: SackView.Tile, w: Int): Int {
        val cols = drawerCols(w)
        val body = if (tile.rows.isEmpty()) ROW_H else lines(tile.gems.orEmpty().size, cols) * GEM_H + lines(tile.loose.size, cols) * ROW_H
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
        if (jump != null && sel >= 0 && tiles[sel].key == jump.tile && SackView.pulse(c.nowMs - jump.at) > 0f) reveal(c, y, w, sel / cols, tiles[sel])
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

    /**
     * Scrolls the jump's tile (in tile row [tileRow]) and its drawer entry into view, the tile and the drawer's head
     * for a sack alone; when both do not fit, the entry wins.
     */
    private fun reveal(c: KitCanvas, y: Int, w: Int, tileRow: Int, t: SackView.Tile) {
        val tileTop = y + tileRow * (TILE_H + TILE_GAP)
        val drawerTop = tileTop + TILE_H + TILE_GAP
        val entry = jump?.row?.let { entryAt(t, it, w) }
        if (entry == null) {
            c.reveal("sack:${jump?.encode()}", tileTop, drawerTop + min(drawerHeight(t, w), DRAWER_PAD_Y + HEAD_H + HEAD_GAP + 3 * ROW_H))
            return
        }
        val entryTop = drawerTop + entry[0]
        val entryBottom = entryTop + entry[1]
        val fits = entryBottom - tileTop <= c.viewBottom - c.viewTop - 24
        c.reveal("sack:${jump?.encode()}", if (fits) tileTop else entryTop - 2 * ROW_H, entryBottom)
    }

    /** Top (from the drawer's top) and height of the drawer entry showing item [id]: its gemstone entry for a tier. */
    private fun entryAt(t: SackView.Tile, id: String, w: Int): IntArray? {
        val cols = drawerCols(w)
        val by = DRAWER_PAD_Y + HEAD_H + HEAD_GAP
        val gems = t.gems.orEmpty()
        val gi = gems.indexOfFirst { g -> g.tiers.any { it.row.id == id } }
        if (gi >= 0) return intArrayOf(by + gi / cols * GEM_H, GEM_H)
        val li = t.loose.indexOfFirst { it.id == id }
        if (li >= 0) return intArrayOf(by + lines(gems.size, cols) * GEM_H + li / cols * ROW_H, ROW_H)
        return null
    }

    /** The jump pulse's strength for tile [key] (and, with [ids], only for an entry holding the jump's row). */
    private fun pulse(c: KitCanvas, key: String, ids: List<String>? = null): Float {
        val j = jump ?: return 0f
        if (j.tile != key || (ids != null && j.row !in ids)) return 0f
        return SackView.pulse(c.nowMs - j.at)
    }

    /** The pulse ring: an accent halo [ring] px around the box fading with [f]; the box's own fill [bg] goes on top. */
    private fun ring(c: KitCanvas, x0: Int, y0: Int, x1: Int, y1: Int, f: Float, ring: Int, bg: Int, corner: Int) {
        c.halo(x0, y0, x1, y1, alpha(S.ACCENT_LIGHT, f), ring, corner)
        c.rect(x0, y0, x1, y1, bg, corner)
    }

    private fun alpha(color: Int, a: Float): Int = ((a.coerceIn(0f, 1f) * 255).toInt() shl 24) or (color and 0xFFFFFF)

    private fun paintTile(c: KitCanvas, t: SackView.Tile, x: Int, y: Int, w: Int, on: Boolean) {
        val hover = c.hovered(x, y, x + w, y + TILE_H)
        val f = pulse(c, t.key)
        if (f > 0f) ring(c, x, y, x + w, y + TILE_H, f, 2, if (on) ON_BG else BG, Corner.MEDIUM)
        if (on) c.framed(x, y, x + w, y + TILE_H, ON_BG, S.ACCENT, Corner.MEDIUM) else c.rect(x, y, x + w, y + TILE_H, if (hover) HOVER_BG else BG, Corner.MEDIUM)
        if (f > 0f) c.rect(x, y, x + w, y + TILE_H, alpha(S.ACCENT, 0.25f * f), Corner.MEDIUM)
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
            val n = t.gems?.size ?: t.rows.size
            append("$n ${if (t.gems != null) "gemstone" else "item"}${if (n == 1) "" else "s"} · ")
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
        val gems = t.gems.orEmpty()
        for ((i, g) in gems.withIndex()) {
            val gy = by + (i / cols) * GEM_H
            if (gy + GEM_H < c.viewTop || gy > c.viewBottom) continue
            paintGem(c, t, g, ix + (i % cols) * (colW + COL_GAP), gy, colW)
        }
        val ly = by + lines(gems.size, cols) * GEM_H
        for ((i, r) in t.loose.withIndex()) {
            val ry = ly + (i / cols) * ROW_H
            if (ry + ROW_H < c.viewTop || ry > c.viewBottom) continue
            paintRow(c, t, r, ix + (i % cols) * (colW + COL_GAP), ry, colW)
        }
    }

    /**
     * One gemstone type: icon of its best shown tier, name, the Rough-equivalent total (amber at ≥95 % of the sack's
     * capacity; `1.9M` when the full number does not fit) and value on the first line; `Rough 160  Flawed 1  Fine 2`
     * under the name, each tier with its own row tooltip.
     */
    private fun paintGem(c: KitCanvas, t: SackView.Tile, g: SackView.Gem, x: Int, y: Int, w: Int) {
        val zero = g.roughEq == 0L
        val f = pulse(c, t.key, g.tiers.map { it.row.id })
        if (f > 0f) {
            ring(c, x, y, x + w, y + GEM_H, f, 1, DRAWER_BG, Corner.SMALL)
            c.rect(x, y, x + w, y + GEM_H, alpha(S.ACCENT, 0.25f * f), Corner.SMALL)
        }
        if (c.hovered(x, y, x + w, y + GEM_H)) c.rect(x, y, x + w, y + GEM_H, HOVER_BG, Corner.SMALL)
        ItemIcons.icon(g.tiers.last().row.id)?.let { c.item(it, x + 3, y + (ROW_H - MINI_ICON) / 2, MINI_ICON, count = false) }
        var rx = x + w - 3
        val value = if (g.value > 0) coins(g.value) else if (zero) "0" else "—"
        rx -= VALUE_W
        c.text(value, rx + VALUE_W - c.width(value, S.CAPTION), y, y + ROW_H, if (zero) S.TEXT_GHOST else S.COIN, S.CAPTION)
        rx -= 6
        val nx = x + 3 + MINI_ICON + 6
        val dotW = if (g.soul) DOT + 4 else 0
        val exact = Format.num(g.roughEq)
        val total = if (c.width(exact, S.SMALL, true) <= rx - nx - c.width(g.name, S.SMALL) - dotW - 8) exact else Format.short(g.roughEq)
        val tw = c.width(total, S.SMALL, true)
        c.text(total, rx - tw, y, y + ROW_H, if (zero) S.TEXT_GHOST else if (g.full) S.MAXED else if (g.soul) S.ACCENT_LIGHT else S.TEXT, S.SMALL, true)
        val end = Rich.of(g.name).ellipsize(c, S.SMALL, rx - tw - 8 - nx - dotW).draw(c, nx, y, y + ROW_H, S.SMALL, if (zero) S.TEXT_GHOST else S.TEXT, false)
        if (g.soul) dot(c, end + 4, y + (ROW_H - DOT) / 2)
        c.tooltip(x, y, x + w, y + ROW_H, gemTip(g, t), "sg:${t.key}:${g.type}")
        val ty = y + ROW_H - 2
        var px = nx
        for (tier in g.tiers) {
            val r = tier.row
            val count = Format.num(r.count)
            val lw = c.width(tier.label, S.TINY)
            val sw = lw + 3 + c.width(count, S.TINY)
            if (px + sw > x + w - 3) break
            c.text(tier.label, px, ty, ty + TIER_H, S.TEXT_DIM, S.TINY)
            c.text(count, px + lw + 3, ty, ty + TIER_H, if (r.count == 0L) S.TEXT_GHOST else if (r.soul) S.ACCENT_LIGHT else S.TEXT_SOFT, S.TINY)
            c.tooltip(px, ty, px + sw, ty + TIER_H, rowTip(r, t), "sr:${t.key}:${r.id}")
            px += sw + TIER_GAP
        }
    }

    /** Mirrors the game's Gemstone Sack row ([SackView.gemStored]), then value and sack. */
    private fun gemTip(g: SackView.Gem, t: SackView.Tile): List<String> = buildList {
        add("§f${g.name} Gemstones")
        addAll(SackView.gemStored(g))
        add(if (g.value > 0) "§7Value: §6${coins(g.value)} coins" else "§7No price known")
        add("§8${t.name}")
        if (g.soul) {
            add("")
            add("§9Read from your sack menu, newer than Hypixel's")
        }
    }

    private fun paintRow(c: KitCanvas, t: SackView.Tile, r: SackView.Row, x: Int, y: Int, w: Int) {
        val zero = r.count == 0L
        val f = pulse(c, t.key, listOf(r.id))
        if (f > 0f) {
            ring(c, x, y, x + w, y + ROW_H, f, 1, DRAWER_BG, Corner.SMALL)
            c.rect(x, y, x + w, y + ROW_H, alpha(S.ACCENT, 0.25f * f), Corner.SMALL)
        }
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
        const val TIER_H = 13
        const val TIER_GAP = 10
        const val GEM_H = ROW_H + TIER_H + 2
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
