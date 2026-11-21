package com.soulreturns.features.contests.hud

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.config.gui.render.MsdfFonts
import com.soulreturns.config.gui.render.RoundRectRenderState
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.config.gui.render.SoulText
import com.soulreturns.core.events.Events
import com.soulreturns.features.contests.ContestEndedSignal
import com.soulreturns.features.contests.ContestsStore
import com.soulreturns.features.contests.gui.ContestView
import com.soulreturns.features.contests.gui.ContestsFormat
import com.soulreturns.features.contests.gui.ContestsScreen
import com.soulreturns.features.contests.hud.EndRevealTimeline as T
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.StandingRow
import com.soulreturns.features.profileviewer.gui.ProfileFigure
import com.soulreturns.features.profileviewer.gui.kit.Format
import com.soulreturns.ui.SoulUi
import com.soulreturns.ui.hud.HudElement
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.PlayerFaceExtractor
import net.minecraft.client.gui.render.TextureSetup
import net.minecraft.client.input.KeyEvent
import net.minecraft.world.item.component.ResolvableProfile
import org.joml.Matrix3x2f
import org.lwjgl.glfw.GLFW
import kotlin.math.roundToInt

/**
 * The contest end reveal (`mocks/contests/endfx.js`, frames from [EndRevealTimeline]): a HUD overlay
 * over the game that never takes the mouse. Full plays [cfg] `contests.fullVariant` (podium or
 * roll-up), Small the banner, Off nothing (the chat line and toast still come). Waits while a screen
 * is open, plays the banner in dungeons and Kuudra, and plays once per contest. Esc skips to the
 * settled frame, a second Esc closes; Esc is consumed only while the reveal shows.
 */
object ContestEndReveal : HudElement("contest_end_reveal") {
    private class Play(val snap: ContestSnapshot, val variant: String, val startedAt: Long) {
        val v = ContestView(snap, self())
        val sch = T.schedule(variant, v.ranked.size)
    }

    private var playing: Play? = null
    private var pending: ContestSnapshot? = null
    private val played = HashSet<Long>()

    /** Whose place is highlighted (the dev preview passes the fixture's player). */
    var self: () -> String = { ContestsStore.selfUuid() ?: "" }

    /** Shifts the clock after Esc skips to the settled frame. */
    private var skew = 0L

    var clock: () -> Long = System::currentTimeMillis

    override val foreground: Boolean get() = true

    fun register() {
        Events.subscribe<ContestEndedSignal> { onFinished(it.snapshot) }
        ClientTickEvents.END_CLIENT_TICK.register { tick() }
    }

    /** A finished contest arrived live: queue its reveal (once per contest; canceled ones have none). */
    fun onFinished(snap: ContestSnapshot) {
        if (snap.contest.state != ContestView.FINISHED) return
        if (!played.add(snap.contest.id)) return
        if (mode() == "off") return
        pending = snap
    }

    private fun mode(): String = cfg.contests.endAnimation.takeIf { it == "small" || it == "off" } ?: "full"

    private fun tick() {
        val p = pending ?: return
        if (Minecraft.getInstance().gui.screen() != null) return
        pending = null
        val inFight = LocationApi.inDungeon || LocationApi.inSkyblock && LocationApi.mode == "kuudra"
        val variant = if (mode() == "small" || inFight) T.BANNER else if (cfg.contests.fullVariant == T.ROLLUP) T.ROLLUP else T.PODIUM
        start(p, variant)
    }

    /** Starts a reveal now (also the dev preview). */
    fun start(snap: ContestSnapshot, variant: String) {
        skew = 0L
        playing = Play(snap, variant, clock())
    }

    private fun t(p: Play): Long = clock() - p.startedAt + skew

    /** Esc while the reveal shows and no screen is open: skip, then close. True = consumed. */
    fun handleKey(event: KeyEvent): Boolean {
        val p = playing ?: return false
        if (event.key() != GLFW.GLFW_KEY_ESCAPE || Minecraft.getInstance().gui.screen() != null) return false
        val t = t(p)
        if (t < p.sch.settled) skew += p.sch.settled - t
        else if (t < p.sch.close) skew += p.sch.close - t
        else playing = null
        return true
    }

    override fun isEnabled(): Boolean {
        val p = playing ?: return false
        if (t(p) >= p.sch.end) {
            playing = null
            return false
        }
        return Minecraft.getInstance().gui.screen() == null
    }

    // ------------------------------------------------------------------ drawing

    private const val GOLD = 0xFFF0A640.toInt()
    private const val ME = 0xFF55FFFF.toInt()
    private const val COIN = 0xFFFFAA00.toInt()

    private fun alpha(color: Int, a: Double): Int = (((color ushr 24) * a.coerceIn(0.0, 1.0)).roundToInt() shl 24) or (color and 0xFFFFFF)

    private fun rect(g: GuiGraphicsExtractor, x0: Double, y0: Double, x1: Double, y1: Double, color: Int, round: Boolean = true) {
        if (color ushr 24 == 0 || x1 <= x0 || y1 <= y0) return
        val ix0 = x0.roundToInt()
        val iy0 = y0.roundToInt()
        val ix1 = x1.roundToInt()
        val iy1 = y1.roundToInt()
        if (!round) {
            g.fill(ix0, iy0, ix1, iy1, color)
            return
        }
        g.guiRenderState.addGuiElement(RoundRectRenderState(SoulRenderPipelines.ROUND_SMALL, TextureSetup.noTexture(), Matrix3x2f(g.pose()), ix0, iy0, ix1, iy1, color, null))
    }

    private fun text(g: GuiGraphicsExtractor, s: String, x: Double, y: Double, size: Float, color: Int, bold: Boolean = false) {
        if (color ushr 24 < 4) return
        SoulText.draw(g, s, x.roundToInt(), y.roundToInt(), size, color, font = if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular, shadow = false)
    }

    private fun width(s: String, size: Float, bold: Boolean = false): Int = SoulText.width(s, size, if (bold) MsdfFonts.menuSemibold else MsdfFonts.menuRegular)

    private fun center(g: GuiGraphicsExtractor, s: String, cx: Double, y: Double, size: Float, color: Int, bold: Boolean = false) =
        text(g, s, cx - width(s, size, bold) / 2.0, y, size, color, bold)

    private fun right(g: GuiGraphicsExtractor, s: String, rx: Double, y: Double, size: Float, color: Int, bold: Boolean = false) =
        text(g, s, rx - width(s, size, bold), y, size, color, bold)

    private val faces = HashMap<String, ResolvableProfile>()

    private fun face(g: GuiGraphicsExtractor, uuid: String, x: Double, y: Double, size: Int) {
        PlayerFaceExtractor.extractRenderState(g, faces.getOrPut(uuid) { ProfileFigure.profile(uuid) }, x.roundToInt(), y.roundToInt(), size)
    }

    private fun icon(g: GuiGraphicsExtractor, name: String, x: Double, y: Double, size: Int, color: Int) {
        val id = net.minecraft.resources.Identifier.fromNamespaceAndPath("soul", "textures/gui/icons/$name.png")
        g.blit(net.minecraft.client.renderer.RenderPipelines.GUI_TEXTURED, id, x.roundToInt(), y.roundToInt(), 0f, 0f, size, size, 64, 64, 64, 64, color)
    }

    private fun you(g: GuiGraphicsExtractor, x: Double, cy: Double, a: Double): Int {
        val w = width("YOU", 7.5f, true) + 8
        rect(g, x, cy - 5, x + w, cy + 5, alpha(0xFF2563EB.toInt(), a))
        text(g, "YOU", x + 4, cy - 4.5, 7.5f, alpha(-1, a), true)
        return w
    }

    override fun extract(g: GuiGraphicsExtractor) {
        val p = playing ?: return
        val s = SoulUi.factor()
        val mc = Minecraft.getInstance()
        val w = (mc.window.guiScaledWidth / s).toInt()
        val h = (mc.window.guiScaledHeight / s).toInt()
        val t = t(p)
        if (p.variant == T.BANNER) banner(g, p, t, w) else full(g, p, t, w, h)
    }

    private fun full(g: GuiGraphicsExtractor, p: Play, t: Long, w: Int, h: Int) {
        val sch = p.sch
        val v = p.v
        val out = T.out(t, sch)
        rect(g, 0.0, 0.0, w.toDouble(), h.toDouble(), alpha(0x9E08080A.toInt(), T.back(t, sch)), round = false)
        val ta = T.titleAlpha(t, sch)
        val ty = h * 0.1 + T.titleOffset(t)
        center(g, "CONTEST OVER", w / 2.0, ty, 9f, alpha(GOLD, ta), true)
        center(g, v.c.name, w / 2.0, ty + 12, 22f, alpha(-1, ta), true)
        center(g, "Final results · ${ContestsFormat.units(v.type, v.goal)} each · ${v.ranked.size} players", w / 2.0, ty + 40, 10.5f, alpha(0xFFAAAAAA.toInt(), ta))
        val bottom = if (p.variant == T.ROLLUP) rollup(g, p, t, w, h, out) else podium(g, p, t, w, h, out)
        val fa = T.footAlpha(t, sch)
        var fy = bottom + 18
        if (t >= sch.settled) {
            // Buttons are shown for parity with the mock; the overlay takes no clicks (/soul contests opens results).
            val open = "Open results"
            val close = "Close"
            val ow = width(open, 11.5f, true) + 28
            val cw = width(close, 11.5f, true) + 28
            var bx = w / 2.0 - (ow + cw + 8) / 2.0
            rect(g, bx, fy, bx + ow, fy + 24, alpha(0xFF2563EB.toInt(), fa))
            center(g, open, bx + ow / 2.0, fy + 6.5, 11.5f, alpha(-1, fa), true)
            bx += ow + 8
            rect(g, bx, fy, bx + cw, fy + 24, alpha(0xFF2A2A2A.toInt(), fa))
            center(g, close, bx + cw / 2.0, fy + 6.5, 11.5f, alpha(0xFFDDDDDD.toInt(), fa), true)
            fy += 30
            val me = v.me
            val unconf = v.unconfirmed
            val note = (me?.rank?.let { "You placed ${ContestsFormat.ord(it)}${if (me.shared) " (shared)" else ""} · " } ?: "") + "Results are final" +
                (if (unconf > 0) " · $unconf ${if (unconf == 1) v.type.unit.singular else v.type.unit.plural} not confirmed by Hypixel count as they are" else "")
            center(g, note, w / 2.0, fy, 10.5f, alpha(0xFFBBBBBB.toInt(), fa))
            fy += 18
            rect(g, w / 2.0 - 80, fy, w / 2.0 + 80, fy + 3, alpha(0x1FFFFFFF, fa))
            rect(g, w / 2.0 - 80, fy, w / 2.0 - 80 + 160 * T.timerFraction(t, sch), fy + 3, alpha(0xFF7FB0FF.toInt(), fa))
            fy += 9
            center(g, "Closes by itself in ${T.secondsLeft(t, sch)} s · Esc closes · /soul contests opens the results", w / 2.0, fy, 9.5f, alpha(0xFF888888.toInt(), fa))
        } else {
            center(g, "Esc skips · you can keep playing", w / 2.0, fy, 9.5f, alpha(0xFF888888.toInt(), fa))
        }
    }

    private fun place(r: StandingRow) = ContestsFormat.ord(r.rank ?: 0)

    private fun podium(g: GuiGraphicsExtractor, p: Play, t: Long, w: Int, h: Int, out: Double): Double {
        val sch = p.sch
        val v = p.v
        val ranked = v.ranked
        val base = T.podiumBase(h)
        for (i in 0 until minOf(3, ranked.size)) {
            val m = ranked[i]
            val x = T.podiumX(i, w)
            if (t < sch.at[i]) {
                rect(g, x, base - 6, x + T.POD_BW, base, alpha(0x14FFFFFF, out))
                continue
            }
            val bh = T.blockHeight(i, t, sch)
            val top = base - bh
            val me = v.isMe(m.uuid)
            if (me) rect(g, x - 2, top - 2, x + T.POD_BW + 2, base, alpha(ME, out))
            rect(g, x, top, x + T.POD_BW, base, alpha(T.POD_COLOR[i], out))
            if (bh > 20) center(g, if (m.shared) "${place(m)} (shared)" else place(m), x + T.POD_BW / 2.0, top + 7, 13f, alpha(0xFF111111.toInt(), out), true)
            val pop = T.pop(i, t, sch)
            val a = pop.coerceIn(0.0, 1.0) * out
            val cnt = T.podiumCount(i, t, sch)
            val faceS = if (i == 0) 44 else 38
            var y = top - 92 - (if (i == 0) 22 else 0)
            val cx = x + T.POD_BW / 2.0
            if (a > 0.02) {
                if (i == 0) {
                    icon(g, "ct_crown", cx - 11, y, 22, alpha(GOLD, a))
                    y += 20
                }
                rect(g, cx - faceS / 2.0 - 3, y - 3, cx + faceS / 2.0 + 3, y + faceS + 3, alpha(if (i == 0) GOLD else 0xFF161616.toInt(), a))
                if (a > 0.5) face(g, m.uuid, cx - faceS / 2.0, y, faceS)
                y += faceS + 4
                val ns = if (i == 0) 13.5f else 12f
                val nw = width(m.name, ns, true) + if (me) width("YOU", 7.5f, true) + 12 else 0
                text(g, m.name, cx - nw / 2.0, y, ns, alpha(if (me) ME else 0xFFEEEEEE.toInt(), a), true)
                if (me) you(g, cx - nw / 2.0 + width(m.name, ns, true) + 4, y + ns / 2.0, a)
                y += ns + 4
                center(g, Format.short((m.total * cnt).toLong()), cx, y, if (i == 0) 19f else 15f, alpha(COIN, a), true)
            }
        }
        for (k in 0 until maxOf(0, ranked.size - 3)) {
            val i = k + 3
            if (t < sch.at[i]) continue
            val m = ranked[i]
            val a = T.lowIn(i, t, sch) * out
            val x = T.lowX(i, t, sch, w)
            val y = T.lowY(k, h)
            val me = v.isMe(m.uuid)
            if (me) rect(g, x - 1.5, y - 1.5, x + T.LOW_W + 1.5, y + T.LOW_H + 1.5, alpha(ME, a))
            rect(g, x, y, x + T.LOW_W, y + T.LOW_H, alpha(0xEB1E1E1E.toInt(), a))
            val cy = y + T.LOW_H / 2.0
            text(g, place(m), x + 10, cy - 5.5, 11f, alpha(0xFF999999.toInt(), a), true)
            face(g, m.uuid, x + 41, cy - 8, 16)
            text(g, m.name, x + 64, cy - 5.5, 11f, alpha(0xFFDDDDDD.toInt(), a), true)
            if (me) you(g, x + 68 + width(m.name, 11f, true), cy, a)
            right(g, Format.short((m.total * T.lowCount(i, t, sch)).toLong()), x + T.LOW_W - 10, cy - 5.5, 11f, alpha(COIN, a), true)
            right(g, "${m.units}/${v.goal}", x + T.LOW_W - 73, cy - 5, 10f, alpha(0xFF888888.toInt(), a))
        }
        confetti(g, t, sch.burst, w / 2.0, base - T.POD_HEIGHT[0] - 60, 1.0)
        return T.podiumBottom(ranked.size, h)
    }

    private fun rollup(g: GuiGraphicsExtractor, p: Play, t: Long, w: Int, h: Int, out: Double): Double {
        val sch = p.sch
        val v = p.v
        val ranked = v.ranked
        val top0 = T.rollTop(h)
        val x0 = w / 2.0 - T.PANEL_W / 2.0
        val ph = T.rollPanelH(ranked.size)
        val pa = T.rollPanelIn(t) * out
        rect(g, x0 - 1, top0 - 1, x0 + T.PANEL_W + 1, top0 + ph + 1, alpha(0xFF2C2C2C.toInt(), pa))
        rect(g, x0, top0, x0 + T.PANEL_W, top0 + ph, alpha(0xF2161616.toInt(), pa))
        for ((i, m) in ranked.withIndex()) {
            val y = T.rollRowY(i, h)
            val rh = if (i == 0) T.WIN_H else T.ROW_H
            val rw = T.PANEL_W - 20.0
            if (t < sch.at[i]) {
                rect(g, x0 + 10, y, x0 + 10 + rw, y + rh, alpha(0x0AFFFFFF, out))
                continue
            }
            val a = T.rollRowIn(i, t, sch) * out
            val x = x0 + 10 - 40 * (1 - T.rollRowIn(i, t, sch))
            val win = i == 0
            val me = v.isMe(m.uuid)
            if (me) rect(g, x - 1.5, y - 1.5, x + rw + 1.5, y + rh + 1.5, alpha(ME, a))
            rect(g, x, y, x + rw, y + rh, alpha(if (win) 0xFF2B2414.toInt() else 0xFF222222.toInt(), a))
            if (win) rect(g, x, y, x + 3, y + rh, alpha(GOLD, a), round = false)
            val cy = y + rh / 2.0
            var cx = x + 10
            if (win) {
                val pop = T.rollCrown(t, sch).coerceIn(0.0, 1.2)
                val cs = (12 * pop).roundToInt().coerceAtLeast(1)
                icon(g, "ct_crown", cx, cy - cs / 2.0, cs, alpha(GOLD, a))
                cx += 14
            }
            val fs = if (win) 14f else 11.5f
            text(g, if (m.shared) "${m.rank}=" else "${m.rank}", cx, cy - fs / 2, fs, alpha(if (win) GOLD else 0xFF999999.toInt(), a), true)
            cx = x + 10 + 28 + 8
            val faceS = if (win) 26 else 18
            face(g, m.uuid, cx, cy - faceS / 2.0, faceS)
            cx += faceS + 8
            text(g, m.name, cx, cy - fs / 2, fs, alpha(if (me) ME else 0xFFDDDDDD.toInt(), a), true)
            cx += width(m.name, fs, true) + 5
            if (me) cx += you(g, cx, cy, a) + 5
            if (win) {
                val ww = width("WINNER", 8f, true) + 10
                rect(g, cx, cy - 5.5, cx + ww, cy + 5.5, alpha(GOLD, a))
                text(g, "WINNER", cx + 5, cy - 4.5, 8f, alpha(0xFF111111.toInt(), a), true)
            }
            val vs = if (win) 17f else 13f
            right(g, Format.short((m.total * T.rollCount(i, t, sch)).toLong()), x + rw - 10, cy - vs / 2, vs, alpha(COIN, a), true)
            right(g, "${m.units}/${v.goal} ${v.type.unit.plural}", x + rw - 88, cy - 5, 10f, alpha(0xFF888888.toInt(), a))
        }
        confetti(g, t, sch.burst, w / 2.0, top0 + 10, 1.15)
        return top0 + ph
    }

    private fun confetti(g: GuiGraphicsExtractor, t: Long, burst: Long?, x0: Double, y0: Double, spread: Double) {
        for (c in T.confetti(t, burst, x0, y0, spread)) rect(g, c.x, c.y, c.x + c.w, c.y + c.h, alpha(c.color, c.alpha), round = false)
    }

    private fun banner(g: GuiGraphicsExtractor, p: Play, t: Long, w: Int) {
        val sch = p.sch
        val v = p.v
        val bw = T.BANNER_W.toDouble()
        val bh = T.BANNER_H.toDouble()
        val x = w / 2.0 - bw / 2
        val y = T.bannerY(t, sch)
        rect(g, x - 1, y - 1, x + bw + 1, y + bh + 1, 0xFF333333.toInt())
        rect(g, x, y, x + bw, y + bh, 0xF5181818.toInt())
        rect(g, x, y, x + 3, y + bh, GOLD, round = false)
        icon(g, "ct_trophy", x + 14, y + bh / 2 - 10, 20, GOLD)
        val me = v.me
        val win = v.ranked.firstOrNull()
        val line = when {
            me?.rank == 1 -> "You won with ${Format.short(me.total)}"
            me?.rank != null && win != null -> "You placed ${place(me)} · ${Format.short(me.total)} · ${win.name} won (${Format.short(win.total)})"
            win != null -> "${win.name} won with ${Format.short(win.total)}"
            else -> "Nobody finished a ${v.type.unit.singular}"
        }
        val tx = x + 44
        val b3 = minOf(3, v.ranked.size) * 20.0
        val btn = "Results"
        val btnW = width(btn, 10.5f, true) + 20.0
        val room = (x + bw - 12 - btnW - 10 - b3 - 10 - tx).toInt()
        text(g, ellipsize("CONTEST OVER · ${v.c.name.uppercase()}", 8.5f, true, room), tx, y + bh / 2 - 12, 8.5f, GOLD, true)
        text(g, ellipsize(line, 11f, false, room), tx, y + bh / 2 + 1, 11f, 0xFFDDDDDD.toInt())
        var fx = x + bw - 12 - btnW - 10 - b3
        for (m in v.ranked.take(3)) {
            if (v.isMe(m.uuid)) rect(g, fx - 1.5, y + bh / 2 - 9.5, fx + 17.5, y + bh / 2 + 9.5, ME)
            face(g, m.uuid, fx, y + bh / 2 - 8, 16)
            rect(g, fx + 11, y + bh / 2 + 4, fx + 20, y + bh / 2 + 13, 0xFF111111.toInt())
            text(g, "${m.rank}", fx + 13, y + bh / 2 + 4.5, 7.5f, 0xFFDDDDDD.toInt(), true)
            fx += 20
        }
        val bx = x + bw - 12 - btnW
        rect(g, bx, y + bh / 2 - 10, bx + btnW, y + bh / 2 + 10, 0xFF2563EB.toInt())
        center(g, btn, bx + btnW / 2, y + bh / 2 - 5.5, 10.5f, -1, true)
        rect(g, x, y + bh - 2, x + bw, y + bh, 0x1FFFFFFF, round = false)
        rect(g, x, y + bh - 2, x + bw * T.bannerTimer(t, sch), y + bh, 0xFF7FB0FF.toInt(), round = false)
    }

    private fun ellipsize(s: String, size: Float, bold: Boolean, maxW: Int): String {
        if (width(s, size, bold) <= maxW) return s
        var cut = s
        while (cut.isNotEmpty() && width("$cut…", size, bold) > maxW) cut = cut.dropLast(1)
        return "$cut…"
    }

    /** Opens the finished contest's results (the chat line's button does the same). */
    fun openResults() {
        playing?.let { ContestsScreen.openResults(it.snap.contest.id) }
    }
}
