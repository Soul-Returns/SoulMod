package com.soulreturns.features.pets

import com.soulreturns.config.PetHudConfig
import kotlin.math.roundToInt

/**
 * What the Pet HUD shows and how big it is, without drawing: the Card and Compact line of
 * `mocks/pet-hud/mock.js` (`cardHtml` / `lineHtml`), ported 1:1. [PetHud] draws exactly these rows.
 */
object PetHudModel {
    // Metrics (Soul px at HUD scale 1), the mock's PET constants. The card shares MobHealthHud's.
    const val PAD = 5
    const val GAP = 3
    const val NAME = 10f
    const val SMALL = 8f
    const val BAR_H = 4
    const val BAR_MIN_W = 90
    const val NAME_LV_GAP = 14
    const val ROW_GAP = 8
    const val ICON = 16
    const val ICON_GAP = 5
    const val BADGE = 7f
    const val LINE_PAD_Y = 3
    const val LINE_PAD_X = 5
    const val LINE_ICON = 10
    const val LINE_GAP = 4
    const val LINE_LV_GAP = 3
    const val LINE_BAR_GAP = 5
    const val LINE_BAR_W = 40
    const val LINE_BAR_H = 3
    const val CHROMA_SPAN = 0.5f

    const val WHITE = 0xFFFFFFFF.toInt()
    const val DIM = 0xFFAAAAAA.toInt()
    const val FAINT = 0xFF777777.toInt()
    const val NUM = 0xFFEEEEEE.toInt()
    const val MAX = 0xFF55FFFF.toInt()
    const val RATE = 0xFF55FF55.toInt()
    const val STAR = 0xFFFF55FF.toInt()
    const val TRACK = 0x60FFFFFF
    const val EMPTY_ICON = 0x47FFFFFF

    /** Line height of a nominal text size: `size * 9 / 8`, rounded (10 → 11, 8 → 9). */
    fun line(size: Float): Int = (size * 9f / 8f).roundToInt()

    /** A run of text in one color; [bold] = the semibold face. */
    data class Run(val text: String, val color: Int, val bold: Boolean = false)

    /** Width of runs at a nominal size (the HUD measures with its font; tests with a stand-in). */
    fun interface Measure {
        fun width(runs: List<Run>, size: Float): Int
    }

    /** A small (8) row: left runs, right runs pushed to the column's right edge. */
    class SmallRow(val left: List<Run>, val right: List<Run>, val id: String)

    /**
     * The card's content. [name] null = no pet ([emptyTitle] + [emptyHint]). [xpRow] is the row under
     * the bar (numbers / percent / MAX LEVEL / overflow); [rows] the rest in order.
     */
    class Card(
        val name: List<Run>?,
        val level: Run?,
        val bar: Boolean,
        val xpRow: SmallRow?,
        val rows: List<SmallRow>,
        val emptyTitle: Run?,
        val emptyHint: Run?,
        val icon: Boolean,
    )

    class CardBox(val width: Int, val height: Int, val columnW: Int, val iconTop: Int, val bodyH: Int)

    fun rarityColor(view: PetView): Int = view.rarity?.color ?: WHITE

    /** The name always shows; rarity color and the skin star are toggles (white name when rarity is off). */
    fun nameRuns(view: PetView, c: PetHudConfig): List<Run> {
        val color = if (c.rarityName) rarityColor(view) else WHITE
        return if (c.skinStar && view.skinned) listOf(Run(view.name, color), Run(" ✦", STAR)) else listOf(Run(view.name, color))
    }

    fun levelRun(view: PetView, c: PetHudConfig): Run? = if (c.level && view.level != null) Run("Lvl ${view.level}", DIM) else null

    /** XP/h and time to level show while XP came in during the last 90 s and the pet is not maxed. */
    fun levelling(view: PetView): Boolean = view.rate != null && view.rate > 0 && !view.maxed

    private fun toNextHours(view: PetView): Double? {
        val rate = view.rate ?: return null
        val into = view.into ?: return null
        val need = view.need ?: return null
        return (need - into) / rate
    }

    /** [state] is the tracker's when [view] is null: unknown (Pet widget never seen) or none. */
    fun card(view: PetView?, state: Int, c: PetHudConfig): Card {
        if (view == null) {
            val unknown = state == PetTracker.UNKNOWN
            return Card(
                name = null, level = null, bar = false, xpRow = null, rows = emptyList(),
                emptyTitle = Run(if (unknown) "Pet unknown" else "No pet", DIM),
                emptyHint = Run(if (unknown) "Turn on the Pet widget in /tab" else "Summon one in /pets", FAINT),
                icon = c.icon,
            )
        }
        val fmt = c.numberFormat
        val bar = c.xpBar && view.fraction != null
        val left = ArrayList<Run>()
        val right = ArrayList<Run>()
        if (view.maxed) {
            if (c.percent || c.xpNumbers) left += Run("MAX LEVEL", MAX, bold = true)
            val overflow = view.overflow
            if (c.overflow && overflow != null && overflow > 0) right += Run("+${PetFormat.xp(overflow, fmt)} XP", DIM)
        } else if (view.into != null && view.need != null) {
            if (c.xpNumbers) left += Run("${PetFormat.xp(view.into, fmt)} / ${PetFormat.xp(view.need, fmt)}", NUM)
            if (c.percent) right += Run(PetFormat.percent(view.into / view.need), DIM)
        }
        val xpRow = if (left.isNotEmpty() || right.isNotEmpty()) SmallRow(left, right, "xp") else null

        val rows = ArrayList<SmallRow>()
        if (view.level != null && PetLevels.isEgg(view.name, view.level)) rows += SmallRow(listOf(Run("Egg · hatches at Lvl 100", DIM)), emptyList(), "egg")
        val held = view.held
        if (c.heldItem && held != null) rows += SmallRow(listOf(Run("Held ", DIM), Run(held.name, legacyColor(held.color))), emptyList(), "held")
        val total = view.total
        if (c.progressToMax && !view.maxed && total != null) {
            val maxXp = PetLevels.levelXp(view.maxLevel, view.rarity, view.name).toDouble()
            val runs = arrayListOf(Run("To Lvl ${view.maxLevel} ", DIM), Run(PetFormat.percent(total / maxXp), NUM))
            val rate = view.rate
            if (rate != null && rate > 0) runs += Run(" · ${PetFormat.duration((maxXp - total) / rate)}", DIM)
            rows += SmallRow(runs, emptyList(), "tomax")
        }
        if (levelling(view) && (c.xpPerHour || c.timeToLevel)) {
            val runs = ArrayList<Run>()
            if (c.xpPerHour) runs += Run("+${PetFormat.short(view.rate!!)} XP/h", RATE)
            val hours = toNextHours(view)
            if (c.timeToLevel && hours != null) {
                if (runs.isNotEmpty()) runs += Run(" · ", DIM)
                runs += Run("Lvl ${(view.level ?: 0) + 1} in ${PetFormat.duration(hours)}", DIM)
            }
            if (runs.isNotEmpty()) rows += SmallRow(runs, emptyList(), "rate")
        }
        return Card(nameRuns(view, c), levelRun(view, c), bar, xpRow, rows, null, null, c.icon)
    }

    fun rowWidth(row: SmallRow, m: Measure): Int {
        val l = if (row.left.isEmpty()) 0 else m.width(row.left, SMALL)
        val r = if (row.right.isEmpty()) 0 else m.width(row.right, SMALL)
        // The mock's flex row: an empty left still leaves its gap before the right part.
        return if (row.right.isEmpty()) l else l + ROW_GAP + r
    }

    fun nameRowWidth(card: Card, m: Measure): Int {
        val name = card.name ?: return 0
        val lv = card.level ?: return m.width(name, NAME)
        return m.width(name, NAME) + NAME_LV_GAP + m.width(listOf(lv), SMALL)
    }

    fun measureCard(card: Card, m: Measure): CardBox {
        val nl = line(NAME)
        val sl = line(SMALL)
        var col: Int
        var body = nl
        var top = nl // the rows the icon centers on: name, bar, xp row
        if (card.name == null) {
            col = maxOf(m.width(listOfNotNull(card.emptyTitle), NAME), m.width(listOfNotNull(card.emptyHint), SMALL))
            body += GAP + sl
            top += GAP + sl
        } else {
            col = nameRowWidth(card, m)
            if (card.bar) {
                col = maxOf(col, BAR_MIN_W)
                body += GAP + BAR_H
                top += GAP + BAR_H
            }
            card.xpRow?.let {
                col = maxOf(col, rowWidth(it, m))
                body += GAP + sl
                top += GAP + sl
            }
            for (row in card.rows) {
                col = maxOf(col, rowWidth(row, m))
                body += GAP + sl
            }
        }
        val iconTop = if (card.icon) maxOf(0, ((top - ICON) / 2f).roundToInt()) else 0
        val inner = maxOf(body, if (card.icon) iconTop + ICON else 0)
        val iconW = if (card.icon) ICON + ICON_GAP else 0
        return CardBox(PAD * 2 + iconW + col, PAD * 2 + inner, col, iconTop, body)
    }

    /** The compact line: icon · Lvl · name · bar · percent (MAX at max) · time to level · XP/h. */
    class Line(val level: Run?, val name: List<Run>?, val empty: Run?, val bar: Boolean, val tail: List<Run>, val icon: Boolean)

    fun line(view: PetView?, state: Int, c: PetHudConfig): Line {
        if (view == null) return Line(null, null, Run(if (state == PetTracker.UNKNOWN) "Pet unknown" else "No pet", DIM), false, emptyList(), c.icon)
        val parts = ArrayList<List<Run>>()
        if (view.maxed) {
            if (c.percent) parts += listOf(Run("MAX", MAX, bold = true))
        } else if (c.percent && view.fraction != null) {
            parts += listOf(Run(PetFormat.percent(view.fraction!!), DIM))
        }
        if (levelling(view) && c.timeToLevel) toNextHours(view)?.let { parts += listOf(Run(PetFormat.duration(it), DIM)) }
        if (levelling(view) && c.xpPerHour) parts += listOf(Run("+${PetFormat.short(view.rate!!)}/h", RATE))
        val tail = ArrayList<Run>()
        parts.forEachIndexed { i, p ->
            if (i > 0) tail += Run(" · ", DIM)
            tail += p
        }
        return Line(levelRun(view, c), nameRuns(view, c), null, c.xpBar && view.fraction != null, tail, c.icon)
    }

    fun measureLine(l: Line, m: Measure): Pair<Int, Int> {
        var w = LINE_PAD_X * 2
        if (l.icon) w += LINE_ICON + LINE_GAP
        if (l.name == null) {
            w += m.width(listOfNotNull(l.empty), NAME)
        } else {
            l.level?.let { w += m.width(listOf(it), SMALL) + LINE_LV_GAP }
            w += m.width(l.name, NAME)
            if (l.bar) w += LINE_BAR_GAP + LINE_BAR_W
            if (l.tail.isNotEmpty()) w += LINE_GAP + m.width(l.tail, SMALL)
        }
        return w to (line(NAME) + LINE_PAD_Y * 2)
    }

    /** Vanilla's ARGB for a legacy color code. */
    fun legacyColor(code: Char): Int {
        val i = "0123456789abcdef".indexOf(code.lowercaseChar())
        return if (i < 0) WHITE else LEGACY[i]
    }

    private val LEGACY = intArrayOf(
        0xFF000000.toInt(), 0xFF0000AA.toInt(), 0xFF00AA00.toInt(), 0xFF00AAAA.toInt(), 0xFFAA0000.toInt(), 0xFFAA00AA.toInt(),
        0xFFFFAA00.toInt(), 0xFFAAAAAA.toInt(), 0xFF555555.toInt(), 0xFF5555FF.toInt(), 0xFF55FF55.toInt(), 0xFF55FFFF.toInt(),
        0xFFFF5555.toInt(), 0xFFFF55FF.toInt(), 0xFFFFFF55.toInt(), 0xFFFFFFFF.toInt(),
    )
}
