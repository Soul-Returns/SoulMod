package com.soulreturns.config.gui

/**
 * Flat ARGB palette for the config screen, ported from the legacy `SoulTheme` so the menu reads
 * the same as the old one. Plain `Int`s (no NanoVG/engine deps) — drawn via `GuiGraphicsExtractor.fill`.
 *
 * `val` (not `const`) because `.toInt()` on a >Int hex literal isn't a compile-time constant.
 */
object Theme {
    val DIM: Int = 0x66000000 // overlay behind the card, over the framework blur (legacy 0x66000000)
    val PANEL: Int = 0xD81A1A1A.toInt() // card body — translucent (legacy panel)
    val TOP_NAV: Int = 0xFF111111.toInt()
    val SIDEBAR: Int = 0xFF161616.toInt()
    val SECTION: Int = 0xFF1E1E1E.toInt() // section card (legacy panelInset)
    val PANEL_HOVER: Int = 0xE0242424.toInt() // toggle/track off, field bg, move-gui idle, reset hover
    val SEPARATOR: Int = 0xFF2A2A2A.toInt()
    val CONTROL: Int = 0xFF2A2A2A.toInt()
    val CONTROL_HOVER: Int = 0xFF353535.toInt()
    val ACCENT: Int = 0xFF3B82F6.toInt()
    val ACCENT_DIM: Int = 0xFF2563EB.toInt()
    val TEXT: Int = 0xFFEEEEEE.toInt()
    val TEXT_DIM: Int = 0xFF888888.toInt()
    val TEXT_FAINT: Int = 0xFF555555.toInt()
    val FIELD_BG: Int = 0xE0242424.toInt() // legacy text field idle bg (panelHover)
    val FIELD_BORDER: Int = 0xFF333333.toInt()
    /** [PANEL] as it showed stacked on a [FIELD_BORDER] rect (`SoulPanel.over`): the opaque body of popups, tooltips and Move GUI panels. */
    val PANEL_FLOATING: Int = 0xFF1E1E1E.toInt()

    // Config menu (spec `mocks/config-menu/mock.css`).
    val ACCENT_LIGHT: Int = 0xFF7FB0FF.toInt() // icons of cards that are on, NEW chip text, right-now headers
    val ACCENT_TINT: Int = 0x243B82F6 // icon box of a card that is on (accent 14 %)
    val NEW_BG: Int = 0x293B82F6 // NEW chip (accent 16 %)
    val NEW_DOT: Int = 0xFF93C5FD.toInt()
    val BETA: Int = 0xFFF0A640.toInt()
    val BETA_BG: Int = 0xFF3A2A12.toInt()
    val CARD_SELECTED: Int = 0xFF1C2331.toInt()
    val ICON_BOX: Int = 0xFF262626.toInt()
    val ICON: Int = 0xFF999999.toInt()
    val CHIP: Int = 0xFF222222.toInt()
    val CHIP_TEXT: Int = 0xFFAAAAAA.toInt()
    val CHIP_HERE: Int = 0x333B82F6 // accent 20 %
    val CHIP_HERE_TEXT: Int = 0xFFCFE0FF.toInt()
    val OUTLINE: Int = 0xFF3A3A3A.toInt()
    val PALETTE: Int = 0xFF181818.toInt()
    val PALETTE_FIELD: Int = 0xFF121212.toInt()
    val PALETTE_SELECTED: Int = 0xFF1E2A40.toInt()
}
