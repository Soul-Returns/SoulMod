package com.soulreturns.features.contests.gui

import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min

/**
 * The Contests screen's layout numbers, 1:1 from `mocks/contests/mock.js` (constants block, `draw()`,
 * `mainWidth()`) and `mock.css`. Pure, so `ContestsLayoutTest` pins them against the mock.
 */
object ContestsLayout {
    const val CARD_FW = 0.92f
    const val CARD_FH = 0.9f
    const val CARD_MAX_W = 1200
    const val CARD_MAX_H = 800
    const val CARD_MARGIN = 8
    const val HEAD_H = 48
    const val RAIL_W = 176
    const val RAIL_MINI_W = 44
    const val RAIL_COLLAPSE_BELOW = 820
    const val FEED_W = 288
    const val FEED_STACK_BELOW = 880
    const val LB_FULL_MIN_W = 680
    const val TOAST_W = 220
    const val TOAST_MARGIN = 8
    const val TOAST_MS = 6000L
    const val HUD_X = 8
    const val HUD_Y = 64
    const val HUD_W = 236
    const val TREND_MIN = 30
    const val FEED_WINDOW_MIN = 90
    const val MAX_PARTICIPANTS = 10

    // `.sec-h` (12 16 8 padding, 22 min height), `.ct-scroll` (2 16 16)
    const val SEC_PAD_TOP = 12
    const val SEC_PAD_BOTTOM = 8
    const val SEC_H = 22
    const val BODY_PAD_X = 16
    const val BODY_PAD_TOP = 2
    const val BODY_PAD_BOTTOM = 16
    const val GAP = 8

    // `.ct-rail`, `.ri`
    const val RAIL_PAD_Y = 8
    const val RAIL_PAD_X = 6
    const val RAIL_ITEM_H = 26
    const val RAIL_ITEM_GAP = 1

    // `.abar` (lobby action bar): 8 px padding around a 24 px button
    const val ABAR_H = 40

    // `.banner` outside the scroll: 6 px padding, 13 px lines, 8 px under it
    const val BANNER_GAP = 8

    // `.create` grid: summary column
    const val CREATE_SUM_W = 248
    const val CREATE_GAP = 12

    // `.pop.menu`, `.pop.rules`, `.mdl`
    const val MENU_W = 210
    const val RULES_W = 340

    class Card(val x0: Int, val y0: Int, val x1: Int, val y1: Int, val railW: Int) {
        val w get() = x1 - x0
        val h get() = y1 - y0
        val mini get() = railW == RAIL_MINI_W
    }

    fun cardW(uiW: Float): Int = min(min(floor(uiW * CARD_FW).toInt(), CARD_MAX_W), floor(uiW).toInt() - CARD_MARGIN)

    fun cardH(uiH: Float): Int = min(min(floor(uiH * CARD_FH).toInt(), CARD_MAX_H), floor(uiH).toInt() - CARD_MARGIN)

    fun card(uiW: Float, uiH: Float): Card {
        val w = cardW(uiW)
        val h = cardH(uiH)
        val x0 = floor((uiW - w) / 2).toInt()
        val y0 = floor((uiH - h) / 2).toInt()
        return Card(x0, y0, x0 + w, y0 + h, if (w < RAIL_COLLAPSE_BELOW) RAIL_MINI_W else RAIL_W)
    }

    /** `mainWidth()`: the main column's content width (card minus rail minus the 2 x 16 scroll padding). */
    fun mainWidth(uiW: Float): Int {
        val w = cardW(uiW)
        return w - (if (w < RAIL_COLLAPSE_BELOW) RAIL_MINI_W else RAIL_W) - 2 * BODY_PAD_X
    }

    /** The feed moves under the leaderboard when the main column is narrower than this. */
    fun feedStacked(mainW: Int): Boolean = mainW < FEED_STACK_BELOW

    /** The leaderboard drops "Last run" below this main width (`.narrow`). */
    fun narrow(mainW: Int): Boolean = mainW < LB_FULL_MIN_W

    /** The running page's leaderboard column width (main minus the feed column and its gap). */
    fun leaderboardW(mainW: Int): Int = if (feedStacked(mainW)) mainW else mainW - FEED_W - GAP

    // ------------------------------------------------------------------ CSS grids, resolved

    /**
     * Resolves a CSS grid track list: fixed px tracks, `minmax(min, fr)` tracks share what is left by
     * their fr weight but never go below min. Returns the column x offsets and widths inside [w].
     */
    class Track(val fixed: Int = -1, val min: Int = 0, val fr: Float = 0f)

    fun px(v: Int) = Track(fixed = v)

    fun fr(min: Int, fr: Float = 1f) = Track(min = min, fr = fr)

    fun resolve(tracks: List<Track>, w: Int, gap: Int): IntArray {
        val out = IntArray(tracks.size)
        var free = w - gap * max(0, tracks.size - 1)
        var frTotal = 0f
        for ((i, t) in tracks.withIndex()) {
            if (t.fixed >= 0) {
                out[i] = t.fixed
                free -= t.fixed
            } else {
                frTotal += t.fr
            }
        }
        // Tracks whose share falls below their minimum are frozen at it, the rest re-share.
        val flex = tracks.indices.filter { tracks[it].fixed < 0 }.toMutableList()
        var changed = true
        while (changed && flex.isNotEmpty()) {
            changed = false
            val per = if (frTotal > 0) free / frTotal else 0f
            for (i in flex.toList()) {
                if (per * tracks[i].fr < tracks[i].min) {
                    out[i] = tracks[i].min
                    free -= tracks[i].min
                    frTotal -= tracks[i].fr
                    flex.remove(i)
                    changed = true
                }
            }
        }
        val per = if (frTotal > 0) max(0f, free / frTotal) else 0f
        for (i in flex) out[i] = (per * tracks[i].fr).toInt()
        return out
    }

    /** `.lb-r` columns: rank, trend, player, runs, total, avg, best, last, checks. */
    fun leaderboardTracks(narrow: Boolean): List<Track> =
        if (narrow) listOf(px(26), px(22), fr(110), px(80), px(62), px(54), px(56), px(0), px(78))
        else listOf(px(26), px(26), fr(130), px(84), px(62), px(54), px(56), px(70), px(78))

    const val LB_GAP = 6
    const val LB_ROW_H = 30
    const val LB_HEAD_H = 18
    const val LB_PAD_X = 6

    /** `.rr` columns: #, done, time, profit, key loot, check, tool. */
    val RUN_TRACKS = listOf(px(24), px(40), px(46), px(62), fr(120), px(92), px(52))
    const val RUN_GAP = 8
    const val RUN_ROW_H = 22

    /** `.hr` (history) columns. */
    val HISTORY_TRACKS = listOf(fr(110, 1.3f), fr(110, 1.1f), px(90), fr(150, 1.5f), px(92), px(60))
    const val HISTORY_GAP = 10
    const val HISTORY_ROW_H = 24

    /** `.fgrid` (friends) and `.lobby-grid`: left fr and right column. */
    fun twoCols(w: Int, leftFr: Float, rightMin: Int): IntArray = resolve(listOf(fr(0, leftFr), fr(rightMin, 1f)), w, GAP)

    /** `.grid2`: auto-fit columns of at least 300. */
    fun grid2Cols(w: Int): Int = max(1, (w + GAP) / (300 + GAP))

    // ------------------------------------------------------------------ in game

    /** Toast position (top-right): x of its left edge for a [uiW] wide screen. */
    fun toastX(uiW: Int): Int = uiW - TOAST_W - TOAST_MARGIN
}
