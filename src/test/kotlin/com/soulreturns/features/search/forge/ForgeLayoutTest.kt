package com.soulreturns.features.search.forge

import com.soulreturns.features.search.SearchEverywhereLayout
import com.soulreturns.features.search.SearchEverywhereLayout.Rect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Expected values are the mock's element rects (`mocks/search-everywhere/`, forge.css; 1280×720 Soul px,
 * panel at 190, 55, so mock x/y + 190/55). The mock's lists carry a 10 px classic scrollbar when they
 * overflow; Soul's overlay bar is inside the 4 px padding, so row right edges are the padded edge.
 */
class ForgeLayoutTest {
    private val frame = SearchEverywhereLayout.frame(1280f, 720f)
    private val goal = ForgeLayout.goal(frame.body, notice = false)

    @Test
    fun goalBarNoticeAndSplitMatchTheMock() {
        assertEquals(Rect(191, 168, 1089, 636), frame.body) // .f-body 1..899 × 113..581
        assertEquals(Rect(191, 168, 1089, 232), goal.bar) // .f-goal 113..177
        assertNull(goal.notice)
        assertEquals(Rect(191, 232, 665, 636), goal.left) // .f-left 1..475.4 (1.12fr of 898 = 474.4)
        assertEquals(Rect(665, 232, 1089, 636), goal.right)
        assertEquals(Rect(207, 242, 654, 636), goal.leftInner) // .f-ph x 17..464.4, y 187
        assertEquals(Rect(679, 242, 1075, 636), goal.rightInner) // .seg-v 489.4..885, y 187
        val noticed = ForgeLayout.goal(frame.body, notice = true)
        assertEquals(Rect(191, 232, 1089, 258), noticed.notice) // .f-notice 177..203
        assertEquals(258, noticed.split.y)
        assertEquals(268, noticed.leftInner.y) // .f-ph y 213
    }

    @Test
    fun treeHeaderRowsAndCellsMatchTheMock() {
        val inner = goal.leftInner
        assertEquals(262, ForgeLayout.treeHeader(inner).need.y) // .f-cols y 207
        assertEquals(Rect(207, 288, 650, 320), ForgeLayout.treeRow(inner, 0)) // .t-row 17,233 (460.4 wide edge, 32 high)
        assertEquals(Rect(207, 320, 650, 352), ForgeLayout.treeRow(inner, 1)) // second row y 265
        val cells = ForgeLayout.treeCells(ForgeLayout.treeRow(inner, 0), 0)
        assertEquals(Rect(211, 288, 221, 320), cells.caret) // caret column 21..31
        assertEquals(226, cells.icon.x) // icon 36..56
        assertEquals(251, cells.nameX) // name 61
        assertEquals(466, cells.columns.need.x) // 276.4..330.4
        assertEquals(520, cells.columns.need.right)
        assertEquals(525, cells.columns.have.x) // 335.4..389.4
        assertEquals(579, cells.columns.have.right)
        assertEquals(584, cells.columns.miss.x) // 394.4..452.4
        assertEquals(642, cells.columns.miss.right)
        assertEquals(461, cells.nameRight) // 271.4
        // 10 px per depth, only on the left.
        val deep = ForgeLayout.treeCells(ForgeLayout.treeRow(inner, 0), 3)
        assertEquals(256, deep.icon.x)
        assertEquals(cells.columns.miss, deep.columns.miss)
    }

    @Test
    fun rightColumnSlotsAndGatherRowsMatchTheMock() {
        val inner = goal.rightInner
        assertEquals(Rect(679, 242, 1075, 272), ForgeLayout.segmented(inner)) // .seg-v 187..217
        val scroll = ForgeLayout.sideScroll(inner)
        assertEquals(280, scroll.y) // .f-scroll 225
        val stripY = scroll.y + ForgeLayout.SEC_FIRST
        assertEquals(303, stripY) // .slots 248
        assertEquals(Rect(679, 303, 723, 347), ForgeLayout.slot(inner.x, stripY, 0)) // slot 489.4..533.4
        assertEquals(Rect(979, 303, 1023, 347), ForgeLayout.slot(inner.x, stripY, 6)) // slot 789.4..833.4
        // Gather: 23 for the header + 26 for the column titles, rows 40 high 41 apart (first row 274, second 315).
        val firstRow = scroll.y + ForgeLayout.SEC_FIRST + ForgeLayout.COLS
        assertEquals(329, firstRow)
        assertEquals(370, firstRow + ForgeLayout.GATHER_PITCH)
        // The mock's row (489.4..871 with its scrollbar): icon 494.4, name 521.4, columns 682 / 743 / 804.
        val cells = ForgeLayout.gatherCells(Rect(679, 329, 1061, 369))
        assertEquals(684, cells.icon.x)
        assertEquals(711, cells.nameX)
        assertEquals(872, cells.columns.need.x)
        assertEquals(926, cells.columns.need.right)
        assertEquals(933, cells.columns.have.x)
        assertEquals(994, cells.columns.miss.x)
        assertEquals(1052, cells.columns.miss.right)
    }

    @Test
    fun pickerColumnsRailRecipesAndPreviewMatchTheMock() {
        val picker = ForgeLayout.picker(frame.body)
        assertEquals(Rect(191, 168, 355, 636), picker.rail) // .f-cats 1..165
        assertEquals(Rect(355, 168, 749, 636), picker.recipes) // .f-recipes 165..559
        assertEquals(Rect(749, 168, 1089, 636), picker.preview) // .f-prev 559..899
        assertEquals(Rect(203, 182, 346, 210), ForgeLayout.category(picker.rail, 0)) // .cat 13,127..156,155
        assertEquals(Rect(203, 212, 346, 240), ForgeLayout.category(picker.rail, 1)) // 157..185
        assertEquals(Rect(203, 577, 346, 626), ForgeLayout.goalMini(picker.rail)) // .goal-mini 522..571
        assertEquals(Rect(369, 182, 736, 217), ForgeLayout.searchField(picker.recipes)) // .field 179,127..546,162
        val list = ForgeLayout.recipeList(picker.recipes)
        assertEquals(249, list.y) // .list 194
        assertEquals(Rect(369, 249, 732, 289), ForgeLayout.recipeRow(list, 0)) // .r-row y 194, 40 high (right edge without the scrollbar)
        assertEquals(291, ForgeLayout.recipeRow(list, 1).y) // 236
        assertEquals(Rect(767, 184, 1071, 636), ForgeLayout.previewInner(picker.preview)) // .f-preview 577..881 × 129
    }

    @Test
    fun compactPanesStackInOneColumn() {
        val compact = SearchEverywhereLayout.frame(600f, 700f)
        val goal = ForgeLayout.goal(compact.body, notice = false, compact = true)
        assertEquals(goal.left, goal.right)
        assertEquals(compact.body.width, goal.split.width)
        val picker = ForgeLayout.picker(compact.body, compact = true, railHeight = 100, recipesHeight = 300, previewHeight = 200)
        assertEquals(picker.rail.bottom, picker.recipes.y)
        assertEquals(picker.recipes.bottom, picker.preview.y)
    }
}
