package com.soulreturns.features.profileviewer.gui.kit

import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import net.minecraft.world.item.ItemStack
import kotlin.math.max

/** Slot highlight for search results (`.sl.match` / `.sl.dim`). */
object Mark {
    const val NONE = 0
    const val MATCH = 1
    const val DIM = 2
}

/** Item size inside a slot frame: 16 in 20 (`.sl`), 14 in 18 (`.sm`), 12 in 14 (`.xs`, frameless), 32 in 36 (`.lg`). */
internal fun itemSizeFor(slot: Int): Int = if (slot >= S.SLOT_LG) 32 else if (slot <= 14) 12 else slot - 4

/** Paints one slot frame with its item, the vanilla tooltip on hover. Shared by every slot node. */
internal fun KitCanvas.slot(stack: ItemStack?, x: Int, y: Int, size: Int, mark: Int = Mark.NONE, count: Boolean = true, onClick: (() -> Unit)? = null) {
    val frameless = size <= 14
    val has = stack != null && !stack.isEmpty
    val hover = has && hovered(x, y, x + size, y + size)
    if (!frameless) {
        val bg: Int
        val edge: Int
        if (!has) {
            bg = S.SLOT_NIL_BG
            edge = S.SLOT_NIL_EDGE
        } else if (mark == Mark.MATCH) {
            bg = S.SLOT_MATCH_BG
            edge = S.SLOT_MATCH_EDGE
        } else if (hover) {
            bg = S.SLOT_HOVER_BG
            edge = S.SLOT_HOVER_EDGE
        } else {
            bg = S.SLOT_BG
            edge = S.SLOT_EDGE
        }
        framed(x, y, x + size, y + size, bg, edge, Corner.SQUARE)
    }
    if (!has) return
    val isz = itemSizeFor(size)
    if (mark == Mark.DIM) {
        // `.sl.dim .mc-item { opacity: .22 }`: items cannot be drawn translucent, so a veil goes on top.
        item(stack!!, x + (size - isz) / 2, y + (size - isz) / 2, isz, count)
        rect(x + 1, y + 1, x + size - 1, y + size - 1, 0xC72B2B2B.toInt(), Corner.SQUARE)
    } else {
        item(stack!!, x + (size - isz) / 2, y + (size - isz) / 2, isz, count)
    }
    if (hover) itemTooltip(stack)
    if (onClick != null) onClick(x, y, x + size, y + size, onClick)
}

/** One slot (`.sl`, `.sl.sm`, `.sl.lg`, `.sl.xs`); an empty frame for null. */
class SlotNode(val stack: ItemStack?, val size: Int = S.SLOT, val mark: Int = Mark.NONE, val count: Boolean = true) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = size
    override fun measure(m: KitMeasure, w: Int): Int = size
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = c.slot(stack, x, y + (h - size) / 2, size, mark, count)
}

/**
 * `.slots`: a container grid, [cols] slots per row (9 for chests), or as many as fit when [cols] is 0
 * (`.slots.wide`). [marks] (same length as [stacks]) highlights search results.
 */
class SlotGridNode(val stacks: List<ItemStack?>, val cols: Int = 9, val size: Int = S.SLOT, val marks: IntArray? = null) : Node(), Inline {
    private fun perRow(w: Int) = if (cols > 0) cols else max(1, (w + S.SLOT_GAP) / (size + S.SLOT_GAP))

    override fun inlineWidth(m: KitMeasure): Int = if (cols > 0) cols * (size + S.SLOT_GAP) - S.SLOT_GAP else 0

    override fun measure(m: KitMeasure, w: Int): Int {
        val rows = (stacks.size + perRow(w) - 1) / perRow(w)
        return if (rows == 0) 0 else rows * (size + S.SLOT_GAP) - S.SLOT_GAP
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val n = perRow(w)
        for ((i, st) in stacks.withIndex()) {
            val sx = x + (i % n) * (size + S.SLOT_GAP)
            val sy = y + (i / n) * (size + S.SLOT_GAP)
            if (sy + size < c.viewTop || sy > c.viewBottom) continue
            c.slot(st, sx, sy, size, marks?.getOrNull(i) ?: Mark.NONE)
        }
    }
}

/**
 * An item (or pet) line: slot, a title with chips, a dim second line, an optional bar and a value at
 * the right. [framed] gives it its own `.pet` background (pet lists); [active] the accent outline.
 */
class ItemLineNode(
    val stack: ItemStack?,
    val title: Rich,
    val sub: Rich? = null,
    val right: Rich? = null,
    val rightColor: Int = S.COIN,
    val chips: List<Chip> = emptyList(),
    val fraction: Float? = null,
    val maxed: Boolean = false,
    val framed: Boolean = false,
    val active: Boolean = false,
    val slotSize: Int = S.SLOT,
    val onClick: (() -> Unit)? = null,
) : Node() {
    private val padX get() = if (framed) 10 else 0
    private val padY get() = if (framed) 7 else 2

    override fun measure(m: KitMeasure, w: Int): Int {
        val text = LH_BODY + (if (sub != null) 3 + LH_CAPTION else 0) + (if (fraction != null) 3 + S.BAR_H else 0)
        return padY * 2 + max(slotSize, text)
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (framed) {
            c.framed(x, y, x + w, y + h, S.CARD_BG, if (active) S.ACCENT else 0, Corner.MEDIUM)
        }
        val sx = x + padX
        c.slot(stack, sx, y + (h - slotSize) / 2, slotSize)
        val tx = sx + slotSize + 8
        val rw = right?.width(c, S.SMALL, true) ?: 0
        val tw = x + w - padX - tx - (if (rw > 0) rw + 8 else 0)
        val textH = LH_BODY + (if (sub != null) 3 + LH_CAPTION else 0) + (if (fraction != null) 3 + S.BAR_H else 0)
        var cy = y + (h - textH) / 2
        val chipsW = chips.sumOf { it.width(c) + S.CHIP_GAP }
        val end = title.ellipsize(c, S.BODY, tw - chipsW, true).draw(c, tx, cy, cy + LH_BODY, S.BODY, S.TEXT, true)
        var chx = end
        for (chip in chips) chx += S.CHIP_GAP + chip.paint(c, chx + S.CHIP_GAP, cy + LH_BODY / 2)
        cy += LH_BODY
        if (sub != null) {
            cy += 3
            sub.ellipsize(c, S.CAPTION, tw).draw(c, tx, cy, cy + LH_CAPTION, S.CAPTION, S.TEXT_DIM)
            cy += LH_CAPTION
        }
        if (fraction != null) c.bar(tx, cy + 3, tw, fraction, maxed)
        if (right != null) right.draw(c, x + w - padX - rw, y, y + h, S.SMALL, rightColor, true)
        if (onClick != null) c.onClick(x, y, x + w, y + h, onClick)
    }
}
