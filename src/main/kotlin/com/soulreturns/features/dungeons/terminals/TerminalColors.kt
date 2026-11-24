package com.soulreturns.features.dungeons.terminals

/** Terminal solver palette: one "click this" green everywhere, high contrast on dark cells. */
object TerminalColors {
    val TARGET_FILL = 0xFF15803D.toInt()
    val TARGET_EDGE = 0xFF4ADE80.toInt()
    val TARGET_HOVER = 0xFFFFFFFF.toInt()
    val IDLE_FILL = 0xFF2A2A2A.toInt()
    val HOVER_FILL = 0xFF333333.toInt()
    val IDLE_DIM = 0x99181818.toInt()
    val DONE_FILL = 0xFF151515.toInt()
    val DONE_DIM = 0xCC151515.toInt()
    val CHECK = 0xFF4ADE80.toInt()

    /** Blocked click: thin red edge plus a faint tint, both scaled by the fade [f] (1 → 0). */
    fun flashEdge(f: Float): Int = ((0xCC * f).toInt() shl 24) or 0xEF4444
    fun flashFill(f: Float): Int = ((0x30 * f).toInt() shl 24) or 0xEF4444

    val NOW = 0xFFA3E635.toInt()

    /** Click in order: the next click's fill (inside a solid [TARGET_EDGE] ring), then the dimmed fills of the second (inside a dashed ring) and third. */
    val ORDER_NEXT_FILL = 0xFF1A6B3B.toInt()
    val UPCOMING_FILL = intArrayOf(0xFF1E5935.toInt(), 0xFF244430.toInt())
    val UPCOMING_DASH = 0xCC4ADE80.toInt()
    val UPCOMING_TEXT = intArrayOf(0xF2FFFFFF.toInt(), 0xD9FFFFFF.toInt(), 0x59FFFFFF)

    /**
     * Rubix actions, never the pane colors: blues for left clicks, ambers for right clicks (apart for red-green
     * color blindness), the lighter shade for one click, the deeper one for two.
     */
    val RUBIX_LEFT = intArrayOf(0xFF3B82F6.toInt(), 0xFF1E40AF.toInt())
    val RUBIX_RIGHT = intArrayOf(0xFFD97706.toInt(), 0xFF92400E.toInt())

    fun rubixAction(clicks: Int): Int {
        val shade = if (Math.abs(clicks) >= 2) 1 else 0
        return if (clicks < 0) RUBIX_RIGHT[shade] else RUBIX_LEFT[shade]
    }

    // Melody lanes.
    val LANE_IDLE = 0xFF1E1E1E.toInt()
    val LANE_ACTIVE = 0xFF3A1414.toInt()
    val LANE_MOVING = 0xFF84CC16.toInt()
    val TARGET_COLUMN = 0xFFD946EF.toInt()
    val TARGET_COLUMN_TINT = 0x33D946EF
    val TARGET_COLUMN_EDGE = 0x80D946EF.toInt()

    /** Vanilla-screen mode: the fill drawn over a slot (0 = none). */
    fun overlay(cell: TerminalTracker.Cell, type: TerminalType): Int {
        if (type === TerminalType.Melody) return 0
        if (cell.state == TerminalTracker.TARGET) return if (type === TerminalType.Rubix) (0xD8 shl 24) or (rubixAction(cell.clicks) and 0xFFFFFF) else 0x5522C55E
        if (cell.state == TerminalTracker.UPCOMING) return when (cell.rank) {
            1 -> 0x60101010
            2 -> 0x80101010.toInt()
            3 -> 0x98101010.toInt()
            else -> 0xA8101010.toInt()
        }
        return 0xB0101010.toInt()
    }
}
