package com.soulreturns.features.contests.hud

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * The contest-end reveal as a pure function of time, ported 1:1 from `mocks/contests/endfx.js`:
 * [schedule] says when each place appears, the frame helpers say where everything is and how opaque
 * at `t` ms after the reveal started. [ContestEndReveal] only draws what these return.
 */
object EndRevealTimeline {
    const val PODIUM = "podium"
    const val ROLLUP = "rollup"
    const val BANNER = "banner"

    const val AUTO_CLOSE_FULL_MS = 10_000L
    const val AUTO_CLOSE_BANNER_MS = 8_000L
    const val FADE_OUT_MS = 350L
    const val LOW_STEP_MS = 280L
    const val ROW_STEP_MS = 420L
    const val COUNT_MS = 700L
    const val CONFETTI = 64
    const val CONFETTI_LIFE = 2.6
    const val GRAVITY = 560.0
    val CONFETTI_COLORS = intArrayOf(
        0xFFF0A640.toInt(), 0xFFFFDD66.toInt(), 0xFF55FFFF.toInt(), 0xFF4ADE80.toInt(),
        0xFFFF7AB8.toInt(), 0xFFFFFFFF.toInt(), 0xFF7FB0FF.toInt(),
    )

    // podium
    const val POD_BW = 150
    const val POD_GAP = 12
    val POD_HEIGHT = intArrayOf(132, 98, 72)
    val POD_COLOR = intArrayOf(0xFFF0A640.toInt(), 0xFFC8C8C8.toInt(), 0xFFC27A4A.toInt())
    const val LOW_H = 26
    const val LOW_GAP = 4
    const val LOW_W = 360

    // roll-up
    const val PANEL_W = 460
    const val ROW_H = 32
    const val WIN_H = 50

    // banner
    const val BANNER_W = 440
    const val BANNER_H = 60

    /** @property at ms at which place i (0 = winner) appears; [burst] confetti start (null: none). */
    class Schedule(val at: LongArray, val burst: Long?, val settled: Long, val close: Long) {
        /** The reveal is gone (faded out) from here on. */
        val end: Long get() = close + FADE_OUT_MS
    }

    fun clamp01(x: Double): Double = max(0.0, min(1.0, x))

    fun prog(t: Long, at: Long, dur: Long): Double = clamp01((t - at).toDouble() / dur)

    fun easeOut(x: Double): Double = 1 - (1 - clamp01(x)).pow(3)

    fun backOut(x0: Double): Double {
        val x = clamp01(x0)
        val c = 1.70158
        return 1 + (c + 1) * (x - 1).pow(3) + c * (x - 1).pow(2)
    }

    fun schedule(variant: String, n: Int): Schedule {
        val at = LongArray(n)
        if (variant == BANNER) return Schedule(at, null, 400, AUTO_CLOSE_BANNER_MS)
        if (variant == ROLLUP) {
            var t = 700L
            for (i in n - 1 downTo 1) {
                at[i] = t
                t += ROW_STEP_MS
            }
            if (n > 0) at[0] = t + 500
            val a0 = if (n > 0) at[0] else t + 500
            return Schedule(at, a0 + 350, a0 + 1500, a0 + 1500 + AUTO_CLOSE_FULL_MS)
        }
        var t = 700L
        for (i in n - 1 downTo 3) {
            at[i] = t
            t += LOW_STEP_MS
        }
        val pod = min(n, 3)
        t += 250
        for (i in pod - 1 downTo 1) {
            at[i] = t
            t += 950
        }
        val a0 = t + 350
        if (n > 0) at[0] = a0
        return Schedule(at, a0 + 450, a0 + 1600, a0 + 1600 + AUTO_CLOSE_FULL_MS)
    }

    /** Overall fade-out factor (1 until close, then to 0 over [FADE_OUT_MS]). */
    fun out(t: Long, s: Schedule): Double = 1 - prog(t, s.close, FADE_OUT_MS)

    /** Dark backdrop opacity (0..1; the CSS color's own alpha .62 applies on top). */
    fun back(t: Long, s: Schedule): Double = clamp01(t / 350.0) * out(t, s)

    /** Title block: y offset (px, negative = above its rest) and opacity. */
    fun titleOffset(t: Long): Double = -16 * (1 - easeOut(prog(t, 120, 450)))

    fun titleAlpha(t: Long, s: Schedule): Double = easeOut(prog(t, 120, 450)) * out(t, s)

    /** Footer opacity: 0.75 before settled, then fades in over 400 ms. */
    fun footAlpha(t: Long, s: Schedule): Double = (if (t >= s.settled) easeOut(prog(t, s.settled, 400)) else 0.75) * out(t, s)

    /** Seconds left on the auto-close timer, as the hint shows them. */
    fun secondsLeft(t: Long, s: Schedule): Int = max(0, ceil((s.close - max(t, s.settled)) / 1000.0).toInt())

    /** Width fraction of the full variants' timer bar. */
    fun timerFraction(t: Long, s: Schedule): Double = clamp01((s.close - t).toDouble() / AUTO_CLOSE_FULL_MS)

    // ------------------------------------------------------------------ podium

    /** Left x of podium place i (0..2) for a W px wide screen. */
    fun podiumX(i: Int, w: Int): Double = when (i) {
        1 -> w / 2.0 - POD_BW / 2.0 - POD_BW - POD_GAP
        0 -> w / 2.0 - POD_BW / 2.0
        else -> w / 2.0 + POD_BW / 2.0 + POD_GAP
    }

    fun podiumBase(h: Int): Double = h * 0.56

    /** Block height of podium place i at t (0 before it appears). */
    fun blockHeight(i: Int, t: Long, s: Schedule): Double = if (t < s.at[i]) 0.0 else POD_HEIGHT[i] * easeOut(prog(t, s.at[i], 450))

    /** The face/name/value pop above a block: 0..~1.1 (back-out overshoot). */
    fun pop(i: Int, t: Long, s: Schedule): Double = backOut(prog(t, s.at[i] + 280, 380))

    /** Count-up fraction of place i's total. */
    fun podiumCount(i: Int, t: Long, s: Schedule): Double = easeOut(prog(t, s.at[i] + 300, if (i == 0) 1100 else COUNT_MS))

    /** Places 4+: slide-in fraction and count-up. */
    fun lowIn(i: Int, t: Long, s: Schedule): Double = easeOut(prog(t, s.at[i], 350))

    fun lowCount(i: Int, t: Long, s: Schedule): Double = easeOut(prog(t, s.at[i] + 100, COUNT_MS))

    fun lowY(k: Int, h: Int): Double = podiumBase(h) + 12 + k * (LOW_H + LOW_GAP)

    fun lowX(i: Int, t: Long, s: Schedule, w: Int): Double = w / 2.0 - 220 + 30 * (1 - lowIn(i, t, s))

    /** Bottom of the podium variant's body (the footer sits 18 px below it). */
    fun podiumBottom(n: Int, h: Int): Double = podiumBase(h) + 12 + max(0, n - 3) * (LOW_H + LOW_GAP)

    // ------------------------------------------------------------------ roll-up

    fun rollTop(h: Int): Double = h * 0.22

    fun rollPanelH(n: Int): Int = WIN_H + max(0, n - 1) * (ROW_H + 4) + 20

    fun rollRowY(i: Int, h: Int): Double = rollTop(h) + 10 + if (i == 0) 0 else WIN_H + 4 + (i - 1) * (ROW_H + 4)

    fun rollPanelIn(t: Long): Double = easeOut(prog(t, 250, 400))

    fun rollRowIn(i: Int, t: Long, s: Schedule): Double = easeOut(prog(t, s.at[i], 380))

    fun rollCount(i: Int, t: Long, s: Schedule): Double = easeOut(prog(t, s.at[i] + 120, if (i == 0) 1100 else COUNT_MS))

    fun rollCrown(t: Long, s: Schedule): Double = backOut(prog(t, s.at[0], 500))

    // ------------------------------------------------------------------ banner

    /** Banner top y at t: slides in from above, back up when closing. */
    fun bannerY(t: Long, s: Schedule): Double {
        val inY = easeOut(prog(t, 0, 380))
        val gone = clamp01(1 - out(t, s))
        return -BANNER_H - 10 + (BANNER_H + 18) * inY - (BANNER_H + 18) * easeOut(gone)
    }

    fun bannerTimer(t: Long, s: Schedule): Double = clamp01((s.close - t).toDouble() / (s.close - s.settled))

    // ------------------------------------------------------------------ confetti

    class Piece(val x: Double, val y: Double, val w: Double, val h: Double, val color: Int, val alpha: Double)

    /**
     * Confetti at t, bursting from (x0, y0): axis-aligned rects that fall and "flip" by width, from the
     * mock's seeded generator (same seed, same pieces).
     */
    fun confetti(t: Long, burstAt: Long?, x0: Double, y0: Double, spread: Double): List<Piece> {
        if (burstAt == null) return emptyList()
        val tau = (t - burstAt) / 1000.0
        if (tau <= 0 || tau > CONFETTI_LIFE) return emptyList()
        var seed = 7L
        fun rnd(): Double {
            seed = (seed * 16807) % 2147483647
            return seed / 2147483647.0
        }
        val out = ArrayList<Piece>(CONFETTI)
        for (i in 0 until CONFETTI) {
            val vx = (rnd() * 2 - 1) * 340 * spread
            val vy = -100 - rnd() * 260
            val w = 3 + rnd() * 3
            val h = 6 + rnd() * 4
            val spin = 4 + rnd() * 9
            val ph = rnd() * 6.28
            val c = CONFETTI_COLORS[i % CONFETTI_COLORS.size]
            val delay = rnd() * 0.12
            val s = tau - delay
            if (s <= 0) continue
            val x = x0 + vx * s * (1 - 0.25 * s)
            val y = y0 + vy * s + 0.5 * GRAVITY * s * s
            val ww = max(0.6, w * abs(cos(ph + spin * s)))
            val o = 1 - clamp01((s - (CONFETTI_LIFE - 0.7)) / 0.7)
            out += Piece(x - ww / 2, y, ww, h, c, o)
        }
        return out
    }
}
