package com.soulreturns.features.profileviewer.gui

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The shell's geometry, ported 1:1 from `mocks/profile-viewer/mock.js` (constants of the same names)
 * and `mock.css` (`.pv-head`, `.pv-rail`, `.rg`, `.ri`, `.rsep`, `.legend`, `.sec-h`). Pure, so
 * `ProfileViewerLayoutTest` pins it against the numbers read off the mock.
 */
object ProfileViewerLayout {
    // mock.js
    const val CARD_FW = 0.92f
    const val CARD_FH = 0.9f
    const val CARD_MAX_W = 1200
    const val CARD_MAX_H = 800
    const val CARD_MARGIN = 8
    const val HEAD_H = 52
    const val RAIL_W = 176
    const val RAIL_MINI_W = 44
    const val RAIL_COLLAPSE_BELOW = 820
    const val SEARCH_W = 210
    const val SEARCH_W_NARROW = 150
    const val SEARCH_COLLAPSE_BELOW = 700

    // .pv-head
    const val HEAD_PAD_L = 12
    const val HEAD_PAD_R = 10
    const val HEAD_GAP = 10
    const val HEAD_SHADOW = 6
    const val FACE = 26
    const val CONTROL_H = 26

    // .pv-rail / .rg / .ri / .rsep / .legend
    const val RAIL_PAD_Y = 8
    const val RAIL_PAD_X = 6
    const val RAIL_PAD_X_MINI = 5
    const val RAIL_GAP = 1
    const val RAIL_ITEM_H = 26
    const val RAIL_GROUP_H = 26
    const val RAIL_SEP_H = 13
    const val RAIL_LEGEND_H = 33

    // .sec-h / .pv-scroll / .banner
    const val SEC_HEAD_PAD_TOP = 12
    const val SEC_HEAD_PAD_BOTTOM = 8
    const val SEC_HEAD_H = 14
    const val BODY_PAD_X = 16
    const val BODY_PAD_TOP = 2
    const val BODY_PAD_BOTTOM = 16
    const val BANNER_H = 28
    const val BANNER_GAP = 8

    /** The card and the responsive switches it implies. Soul px. */
    data class Card(
        val x0: Int,
        val y0: Int,
        val w: Int,
        val h: Int,
        /** Icon-only rail (`.pv-rail.mini`). */
        val miniRail: Boolean,
        /** Search is a 26 px icon button until opened. */
        val searchCollapsed: Boolean,
        val searchW: Int,
    ) {
        val x1: Int get() = x0 + w
        val y1: Int get() = y0 + h
        val railW: Int get() = if (miniRail) RAIL_MINI_W else RAIL_W
    }

    /** `draw()` in mock.js: the card for a [uiW]×[uiH] Soul-px viewport. */
    fun card(uiW: Float, uiH: Float): Card {
        val w = min(min(floor(uiW * CARD_FW).toInt(), CARD_MAX_W), floor(uiW).toInt() - CARD_MARGIN)
        val h = min(min(floor(uiH * CARD_FH).toInt(), CARD_MAX_H), floor(uiH).toInt() - CARD_MARGIN)
        val x0 = floor((uiW - w) / 2f).toInt()
        val y0 = floor((uiH - h) / 2f).toInt()
        val narrow = w < SEARCH_COLLAPSE_BELOW
        return Card(x0, y0, w, h, w < RAIL_COLLAPSE_BELOW, narrow, if (narrow) SEARCH_W_NARROW else SEARCH_W)
    }

    /** One rail row: a section button, a group label or a separator. */
    data class RailRow(val kind: Int, val y: Int, val h: Int, val sectionId: String?, val label: String?)

    const val ROW_ITEM = 0
    const val ROW_GROUP = 1
    const val ROW_SEP = 2

    /**
     * Rail rows top to bottom from content y 0 (`rail()` in mock.js): a group label where the group
     * changes (a separator in the mini rail), a separator before the Soul group, 1 px between rows.
     * [sections] are (id, group) in rail order. Returns the rows and the content height including
     * the bottom padding, without the legend (the screen puts it after the rows).
     */
    fun railRows(sections: List<Pair<String, String>>, mini: Boolean): Pair<List<RailRow>, Int> {
        val rows = ArrayList<RailRow>()
        var y = RAIL_PAD_Y
        var last: String? = null
        fun add(kind: Int, h: Int, id: String?, label: String?) {
            if (rows.isNotEmpty()) y += RAIL_GAP
            rows += RailRow(kind, y, h, id, label)
            y += h
        }
        for ((id, group) in sections) {
            if (group != last) {
                last = group
                if (group == ProfileSection.GROUP_SOUL || (group.isNotEmpty() && mini)) {
                    add(ROW_SEP, RAIL_SEP_H, null, null)
                } else if (group.isNotEmpty()) {
                    add(ROW_GROUP, RAIL_GROUP_H, null, group)
                }
            }
            add(ROW_ITEM, RAIL_ITEM_H, id, null)
        }
        return rows to y + RAIL_PAD_Y
    }

    /** Where the scrolled body starts below the header: section header, then an optional banner. */
    fun bodyTop(cardY0: Int, banner: Boolean): Int =
        cardY0 + HEAD_H + SEC_HEAD_PAD_TOP + SEC_HEAD_H + SEC_HEAD_PAD_BOTTOM + if (banner) BANNER_H + BANNER_GAP else 0

    /** Popover x: left-aligned to its anchor (profiles) or right-aligned (search, keys), kept 6 px inside the card. */
    fun popoverX(anchorX0: Int, anchorX1: Int, popW: Int, cardX0: Int, cardW: Int, alignLeft: Boolean): Int {
        val x = if (alignLeft) anchorX0 else anchorX1 - popW
        return max(cardX0 + 6, min(x, cardX0 + cardW - popW - 6))
    }
}
