package com.soulreturns.features.search.forge

import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Layout of the Forge tab in Soul px, ported 1:1 from `mocks/search-everywhere/forge.css` / `forge.js`
 * (`FLAYOUT`) and measured in the mock at 1280×720 (panel at 190, 55). The mock's lists use classic
 * scrollbars (10 px, only when they overflow); Soul draws an overlay bar inside the 4 px list padding,
 * so rows here end at the padded edge whether or not the list scrolls.
 */
internal object ForgeLayout {
    const val GOAL_BAR = 64
    const val NOTICE = 26
    const val TREE_ROW = 32
    const val INDENT = 10
    const val NUM_COL = 54
    const val MISS_COL = 58
    const val COL_GAP = 5
    const val SLOT = 44
    const val SLOT_GAP = 6
    const val GATHER_ROW = 40
    const val GATHER_PITCH = 41
    const val RAIL = 164
    const val PREVIEW = 340

    /** `.f-split.tree`: 1.12fr | 1fr. */
    const val SPLIT_LEFT = 1.12f
    const val SPLIT_RIGHT = 1f

    // `.f-left` padding 10 10 0 16, `.f-right` padding 10 14 0.
    const val LEFT_PAD_X = 16
    const val LEFT_PAD_RIGHT = 10
    const val RIGHT_PAD = 14
    const val PANE_TOP = 10
    const val SCROLL_PAD = 4

    /** `.f-ph` 20 + `.f-cols` 26: the tree's rows start 46 below the pane top. */
    const val PANE_HEADER = 20
    const val COLS = 26
    const val TREE_TOP = PANE_HEADER + COLS

    /** `.seg-v` 30 + 8 margin above `.f-scroll`. */
    const val SEG = 30
    const val SEG_GAP = 8

    // Right now (`.sec-h` 23 first / 33 later, `.p-row` 48 or 65 with a fetch line, `.w-row` 44, 2 apart).
    const val SEC_FIRST = 23
    const val SEC_NEXT = 33
    const val SLOT_ROW = SLOT + SLOT_GAP
    const val P_ROW = 48
    const val P_ROW_FETCH = 65
    const val W_ROW = 44
    const val ROW_GAP = 2
    const val READY_CARD = 65
    const val READY_GAP = 10

    // Material details.
    const val D_BACK = 26
    const val D_HEAD = 48
    const val D_SUM = 61
    const val D_GAP = 8
    const val MADE_EYEBROW = 22
    const val MINI_ROW = 24
    const val RECIPE_ROW = 30
    const val MADE_NOTE = 19
    const val USED = 16
    const val NOTE_GAP = 10

    // Picker.
    const val CAT_H = 28
    const val CAT_GAP = 2
    const val FIELD_H = 35
    const val LIST_HEAD = 32
    const val R_ROW = 40
    const val R_PITCH = 42
    const val IN_ROW = 24
    const val IN_GAP = 1

    class Goal(val bar: Rect, val notice: Rect?, val split: Rect, val left: Rect, val right: Rect, val leftInner: Rect, val rightInner: Rect)

    /** Goal bar, optional notice strip, then the split: tree pane left (its right border included), details right. */
    fun goal(body: Rect, notice: Boolean, compact: Boolean = false): Goal {
        val bar = Rect(body.x, body.y, body.right, body.y + GOAL_BAR)
        val strip = if (notice) Rect(body.x, bar.bottom, body.right, bar.bottom + NOTICE) else null
        val top = (strip ?: bar).bottom
        val split = Rect(body.x, top, body.right, max(top, body.bottom))
        if (compact) {
            val left = Rect(split.x, split.y, split.right, split.bottom)
            return Goal(bar, strip, split, left, left,
                Rect(left.x + LEFT_PAD_X, split.y + PANE_TOP, left.right - RIGHT_PAD, split.bottom),
                Rect(left.x + RIGHT_PAD, split.y + PANE_TOP, left.right - RIGHT_PAD, split.bottom))
        }
        val x = body.x + (body.width * SPLIT_LEFT / (SPLIT_LEFT + SPLIT_RIGHT)).roundToInt()
        val left = Rect(split.x, split.y, x, split.bottom)
        val right = Rect(x, split.y, split.right, split.bottom)
        return Goal(bar, strip, split, left, right,
            Rect(left.x + LEFT_PAD_X, split.y + PANE_TOP, left.right - 1 - LEFT_PAD_RIGHT, split.bottom),
            Rect(right.x + RIGHT_PAD, split.y + PANE_TOP, right.right - RIGHT_PAD, split.bottom))
    }

    // ---------------------------------------------------------------- tree

    /** `.t-row` i of the tree pane whose inner rect is [inner]. */
    fun treeRow(inner: Rect, index: Int, scroll: Int = 0): Rect {
        val y = inner.y + TREE_TOP + index * TREE_ROW - scroll
        return Rect(inner.x, y, inner.right - SCROLL_PAD, y + TREE_ROW)
    }

    class Columns(val need: Rect, val have: Rect, val miss: Rect)

    /** Need / Have / Missing columns right-aligned inside [rightEdge] (row right minus its 8 px padding). */
    fun numberColumns(rightEdge: Int, y: Int, h: Int): Columns {
        val missLeft = rightEdge - MISS_COL
        val haveRight = missLeft - COL_GAP
        val needRight = haveRight - NUM_COL - COL_GAP
        return Columns(Rect(needRight - NUM_COL, y, needRight, y + h), Rect(haveRight - NUM_COL, y, haveRight, y + h), Rect(missLeft, y, rightEdge, y + h))
    }

    class TreeCells(val caret: Rect, val icon: Rect, val nameX: Int, val nameRight: Int, val columns: Columns)

    /** Cells of a tree row: caret 10, icon 20, name, then the number columns; 4 px + 10 per depth on the left, 8 on the right. */
    fun treeCells(row: Rect, depth: Int): TreeCells {
        val caretX = row.x + 4 + depth * INDENT
        val iconX = caretX + 10 + COL_GAP
        val columns = numberColumns(row.right - 8, row.y, row.height)
        return TreeCells(Rect(caretX, row.y, caretX + 10, row.bottom), Rect(iconX, row.y + 6, iconX + 20, row.y + 26),
            iconX + 20 + COL_GAP, columns.need.x - COL_GAP, columns)
    }

    /** Header row of the tree: `.f-cols` pads 4 left and 12 right. */
    fun treeHeader(inner: Rect): Columns = numberColumns(inner.right - 12, inner.y + PANE_HEADER, COLS)

    // ---------------------------------------------------------------- right column

    fun segmented(inner: Rect) = Rect(inner.x, inner.y, inner.right, inner.y + SEG)

    /** The right column's scroll area (below the Right now | Still to gather switch). */
    fun sideScroll(inner: Rect) = Rect(inner.x, inner.y + SEG + SEG_GAP, inner.right, max(inner.y + SEG + SEG_GAP, inner.bottom))

    /** Forge slot [index] of the strip whose top-left is ([x], [y]). */
    fun slot(x: Int, y: Int, index: Int) = Rect(x + index * (SLOT + SLOT_GAP), y, x + index * (SLOT + SLOT_GAP) + SLOT, y + SLOT)

    /** `.g-row` cells: 1 px border, 4 px left padding, icon 20, 7 gap; 8 px right padding. */
    class GatherCells(val icon: Rect, val nameX: Int, val nameRight: Int, val columns: Columns)

    fun gatherCells(row: Rect): GatherCells {
        // `column-gap:7px` here (the tree's is 5).
        val missRight = row.right - 9
        val missLeft = missRight - MISS_COL
        val haveRight = missLeft - 7
        val needRight = haveRight - NUM_COL - 7
        val columns = Columns(Rect(needRight - NUM_COL, row.y, needRight, row.bottom), Rect(haveRight - NUM_COL, row.y, haveRight, row.bottom), Rect(missLeft, row.y, missRight, row.bottom))
        return GatherCells(Rect(row.x + 5, row.y + 10, row.x + 25, row.y + 30), row.x + 32, columns.need.x - 7, columns)
    }

    // ---------------------------------------------------------------- picker

    class Picker(val rail: Rect, val recipes: Rect, val preview: Rect)

    fun picker(body: Rect, compact: Boolean = false, railHeight: Int = 0, recipesHeight: Int = 0, previewHeight: Int = 0): Picker {
        if (compact) {
            val rail = Rect(body.x, body.y, body.right, body.y + railHeight)
            val recipes = Rect(body.x, rail.bottom, body.right, rail.bottom + recipesHeight)
            return Picker(rail, recipes, Rect(body.x, recipes.bottom, body.right, recipes.bottom + previewHeight))
        }
        val rail = Rect(body.x, body.y, body.x + RAIL, body.bottom)
        val preview = Rect(body.right - PREVIEW, body.y, body.right, body.bottom)
        return Picker(rail, Rect(rail.right, body.y, preview.x, body.bottom), preview)
    }

    /** `.f-cats` padding 14 8 10 12: category i. */
    fun category(rail: Rect, index: Int) = Rect(rail.x + 12, rail.y + 14 + index * (CAT_H + CAT_GAP), rail.right - 8 - 1, rail.y + 14 + index * (CAT_H + CAT_GAP) + CAT_H)

    /** `.goal-mini` 49 high at the rail's bottom. */
    fun goalMini(rail: Rect) = Rect(rail.x + 12, rail.bottom - 10 - 49, rail.right - 9, rail.bottom - 10)

    /** `.f-recipes` padding 14 12 0 14 (its 1 px border-right excluded). */
    fun searchField(recipes: Rect) = Rect(recipes.x + 14, recipes.y + 14, recipes.right - 1 - 12, recipes.y + 14 + FIELD_H)

    fun recipeList(recipes: Rect): Rect {
        val field = searchField(recipes)
        return Rect(field.x, field.bottom + LIST_HEAD, field.right, max(field.bottom + LIST_HEAD, recipes.bottom))
    }

    fun recipeRow(list: Rect, index: Int, scroll: Int = 0): Rect {
        val y = list.y + index * R_PITCH - scroll
        return Rect(list.x, y, list.right - SCROLL_PAD, y + R_ROW)
    }

    /** `.f-prev` padding 16 18. */
    fun previewInner(preview: Rect) = Rect(preview.x + 18, preview.y + 16, preview.right - 18, preview.bottom)
}
