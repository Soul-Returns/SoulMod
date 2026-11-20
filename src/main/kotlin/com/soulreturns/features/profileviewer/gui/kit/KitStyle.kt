package com.soulreturns.features.profileviewer.gui.kit

import com.soulreturns.config.gui.render.MsdfFont

/**
 * The Profile Viewer's look, ported 1:1 from `mocks/profile-viewer/mock.css` (Soul px). The CSS
 * selector each group comes from is named, so a mock change maps to one constant here. Colors are
 * `val` because `.toInt()` on a hex literal above Int range is not a compile-time constant.
 */
object KitStyle {
    /** CSS px font size to a SoulText nominal size (the legacy em every Soul screen uses). */
    fun px(size: Float): Float = size * MsdfFont.LEGACY_EM

    // Type sizes (mock.css header: title 16, heading 13, body 11, caption 10, chip 8.5).
    val TITLE = px(16f)
    val HEADING = px(13f)
    val BODY = px(11f)
    val SMALL = px(10.5f)
    val CAPTION = px(10f)
    val TINY = px(9.5f)
    val LABEL = px(9f)
    val CHIP = px(8.5f)
    val BIG = px(26f)
    val VALUE = px(16f)
    val HERO = px(20f)

    // Colors (Theme.kt plus the mock's own greys).
    val TEXT = 0xFFEEEEEE.toInt()
    val TEXT_SOFT = 0xFFCCCCCC.toInt()
    val TEXT_MID = 0xFFAAAAAA.toInt()
    val TEXT_DIM = 0xFF888888.toInt()
    val TEXT_FAINT = 0xFF555555.toInt()
    val TEXT_GHOST = 0xFF444444.toInt()
    val ACCENT = 0xFF3B82F6.toInt()
    val ACCENT_DIM = 0xFF2563EB.toInt()
    val ACCENT_LIGHT = 0xFF7FB0FF.toInt()
    val MAXED = 0xFFF0A640.toInt()
    val GOOD = 0xFF55C27A.toInt()
    val ERROR = 0xFFE05252.toInt()
    val COIN = 0xFFFFAA00.toInt()

    // .c (card) and its variants.
    val CARD_BG = 0xFF1E1E1E.toInt()
    val CARD_SOUL_BG = 0xFF1C1F26.toInt()
    val CARD_EMPTY_BG = 0xFF1B1B1B.toInt()
    val CARD_EMPTY_EDGE = 0xFF262626.toInt()
    val CARD_LATER_EDGE = 0xFF2E2E2E.toInt()
    const val CARD_GENERIC_EDGE = 0x403B82F6
    const val CARD_PAD_X = 12
    const val CARD_PAD_TOP = 9
    const val CARD_PAD_BOTTOM = 11
    const val CARD_HEAD_H = 18
    const val CARD_HEAD_GAP = 6
    const val SOUL_EDGE_W = 2

    // .grid, .tiles, .cols3, `.pv-scroll > * + *`
    const val GAP = 8
    const val TILE_MIN = 132
    const val COLUMN_BASIS = 250
    const val MASONRY_MAX_COLS = 3

    // .tile
    const val TILE_PAD_X = 11
    const val TILE_PAD_TOP = 8
    const val TILE_PAD_BOTTOM = 9
    const val TILE_K_H = 12
    const val TILE_V_H = 20
    const val TILE_V_TOP = 2
    const val TILE_SUB_H = 12
    const val TILE_SUB_TOP = 1
    const val TILE_BAR_TOP = 6
    val TILE_HOVER = 0xFF242424.toInt()

    // .bar
    const val BAR_H = 4
    val BAR_TRACK = 0xFF2A2A2A.toInt()

    // .kv / .kvs
    const val KV_H = 18
    val KV_LINE = 0xFF252525.toInt()
    const val KV_COLS_GAP = 14

    // .tbl
    const val TH_H = 16
    const val TD_H = 17
    const val TD_PAD_R = 6
    val TH_LINE = 0xFF2A2A2A.toInt()
    val TD_LINE = 0xFF232323.toInt()
    const val TH_TRACKING = 0.06f

    // .chip
    const val CHIP_H = 12
    const val CHIP_PAD_X = 4
    const val CHIP_GAP = 5
    const val CHIP_TRACKING = 0.08f
    val CHIP_BG = 0xFF2A2A2A.toInt()
    val CHIP_TEXT = 0xFFAAAAAA.toInt()
    const val CHIP_SOUL_BG = 0x293B82F6
    val CHIP_ONLY_BG = 0xFF262626.toInt()
    val CHIP_ONLY_TEXT = 0xFFC8C8C8.toInt()
    val CHIP_ONLY_EDGE = 0xFF3A3A3A.toInt()
    val CHIP_YOU_BG = 0xFF2563EB.toInt()
    const val CHIP_ACC_BG = 0x333B82F6
    val CHIP_ACC_TEXT = 0xFFCFE0FF.toInt()
    val CHIP_WARN_BG = 0xFF3A2A12.toInt()

    // .seg
    const val SEG_H = 20
    const val SEG_PAD = 2
    const val SEG_BTN_PAD_X = 8
    const val SEG_BG = 0x57000000
    const val SEG_EDGE = 0x0DFFFFFF

    // .fld.sm
    const val FIELD_H = 22
    const val FIELD_W = 190
    val FIELD_BG = 0xE0242424.toInt()
    val FIELD_EDGE = 0xFF333333.toInt()

    // .empty, .apioff, .soulnote, .foot
    const val EMPTY_PAD_Y = 12
    const val EMPTY_ICON = 22
    val APIOFF_BG = 0xFF1B1B1B.toInt()
    val APIOFF_EDGE = 0xFF2A2A2A.toInt()
    val NOTE_TEXT = 0xFFAAAABB.toInt()

    // .sl (slots: 16 px items in 20 px Soul-drawn frames)
    const val SLOT = 20
    const val SLOT_SM = 18
    const val SLOT_LG = 36
    const val SLOT_GAP = 1
    val SLOT_BG = 0xFF2B2B2B.toInt()
    val SLOT_EDGE = 0xFF383838.toInt()
    val SLOT_HOVER_BG = 0xFF33373F.toInt()
    val SLOT_HOVER_EDGE = 0xFF777777.toInt()
    val SLOT_NIL_BG = 0xFF242424.toInt()
    val SLOT_NIL_EDGE = 0xFF2E2E2E.toInt()
    val SLOT_MATCH_BG = 0xFF253B57.toInt()
    val SLOT_MATCH_EDGE = 0xFF72BBFF.toInt()

    // .br / .hb (bar lists), .hist
    const val HB_H = 6
    val HB_TRACK = 0xFF262626.toInt()
    const val BR_H = 15
    const val HIST_H = 34

    // .cl (level rows), .tier
    const val CL_H = 30
    val TIER_BG = 0xFF252525.toInt()
}
