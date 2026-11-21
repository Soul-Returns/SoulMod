package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.QueuedRun
import com.soulreturns.features.contests.gui.ContestsFormat.clock
import com.soulreturns.features.contests.gui.ContestsFormat.coins
import com.soulreturns.features.contests.gui.ContestsFormat.mmss
import com.soulreturns.features.contests.gui.ContestsFormat.ord
import com.soulreturns.features.contests.model.ContestSummary
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.FeedEntry
import com.soulreturns.features.contests.model.Member
import com.soulreturns.features.contests.model.Run
import com.soulreturns.features.contests.model.StandingRow
import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Inline
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.contests.gui.ContestsLayout as L
import kotlin.math.max
import kotlin.math.min

/** Tie chip of a standings row (owner decision Q9: higher best run wins, equal best runs share). None before a first run (everyone ties at 0). */
fun tieChip(v: ContestView, row: StandingRow): Chip? {
    if (row.units == 0) return null
    if (row.shared) return Ct.warn("SHARED", "Same total and the same best run: they share the place.")
    val other = v.tieBreakWith(row) ?: return null
    return Ct.warn("TIEBREAK", "Same total as ${other.name}. The higher best run decides: ${ContestsFormat.metric(v.type, row.best)} vs ${ContestsFormat.metric(v.type, other.best)}.")
}

private fun cols(tracks: List<L.Track>, w: Int, gap: Int): IntArray {
    // A hidden (0 px) track is `display: none` in the mock: it takes no gap either.
    val ws = L.resolve(tracks, w + gap * tracks.count { it.fixed == 0 }, gap)
    val xs = IntArray(ws.size)
    var x = 0
    for (i in ws.indices) {
        xs[i] = x
        x += ws[i] + if (ws[i] > 0 || i == 0) gap else 0
    }
    return xs + ws
}

private fun KitCanvas.headCell(label: String, x: Int, y: Int, w: Int, right: Boolean, color: Int = Ct.T_55) {
    val up = label.uppercase()
    val tw = trackedWidth(up, S.LABEL, S.TH_TRACKING)
    val cx = if (right) x + w - tw / 2 else x + tw / 2
    tracked(up, cx, y, color, S.LABEL, S.TH_TRACKING)
}

private fun KitCanvas.rightText(s: Rich, x: Int, top: Int, bottom: Int, w: Int, size: Float, color: Int, bold: Boolean = false) {
    val tw = s.width(this, size, bold)
    s.draw(this, x + w - tw, top, bottom, size, color, bold)
}

// ------------------------------------------------------------------ leaderboard

/**
 * `.lb`: the leaderboard card body (header row, ranked rows, out rows, never-joined rows, spectators),
 * columns from [ContestsLayout.leaderboardTracks].
 */
class LeaderboardNode(
    val v: ContestView,
    val sort: String,
    val stale: Boolean,
    val now: Long,
    val narrow: Boolean,
    val inRun: (String) -> Int?,
    val onPlayer: (String) -> Unit,
    val requestNote: (Member) -> Rich,
) : Node() {
    private val t get() = v.type
    private val running get() = v.running
    private val rows: List<StandingRow> get() = if (running && sort == "avg") v.byAverage() else v.ranked
    private val queued get() = v.queued.size

    override fun measure(m: KitMeasure, w: Int): Int {
        var h = L.LB_HEAD_H + 2 + rows.size * L.LB_ROW_H + (v.out.size + v.never.size) * L.LB_ROW_H
        if (v.spectators.isNotEmpty()) h += 4 + 8 + 14
        return h
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val tracks = L.leaderboardTracks(narrow)
        val geo = cols(tracks, w - L.LB_PAD_X * 2, L.LB_GAP)
        val n = tracks.size
        fun cx(i: Int) = x + L.LB_PAD_X + geo[i]
        fun cw(i: Int) = geo[n + i]
        // header
        val hy = y + L.LB_HEAD_H / 2
        c.rect(x, y + L.LB_HEAD_H - 1, x + w, y + L.LB_HEAD_H, Ct.RULE, Corner.SQUARE)
        c.headCell("#", cx(0), hy, cw(0), true)
        c.tooltip(cx(1), y, cx(1) + cw(1), y + L.LB_HEAD_H, listOf("Change in the last ${L.TREND_MIN} min"), "lb:trend")
        c.headCell("Player", cx(2), hy, cw(2), false)
        c.headCell(ContestsFormat.unitsTitle(t), cx(3), hy, cw(3), false)
        val avgSorted = running && sort == "avg"
        c.headCell("Total", cx(4) - (if (!avgSorted) 10 else 0), hy, cw(4), true, if (!avgSorted) Ct.T_55 else Ct.T_55)
        if (!avgSorted) c.icon("ct_down", cx(4) + cw(4) - 8, hy - 4, 8, Ct.ACCENT_LIGHT)
        c.tooltip(cx(4), y, cx(4) + cw(4), y + L.LB_HEAD_H, listOf("${t.metric.label} so far (counted ${t.unit.plural})"), "lb:total")
        c.headCell("Avg/${t.unit.singular}", cx(5) - (if (avgSorted) 10 else 0), hy, cw(5), true)
        if (avgSorted) c.icon("ct_down", cx(5) + cw(5) - 8, hy - 4, 8, Ct.ACCENT_LIGHT)
        c.headCell("Best ${t.unit.singular}", cx(6), hy, cw(6), true)
        if (!narrow) c.headCell("Last ${t.unit.singular}", cx(7), hy, cw(7), false)
        c.headCell("Checks", cx(8), hy, cw(8), true)

        var ry = y + L.LB_HEAD_H + 2
        for (r in rows) {
            paintRow(c, r, x, ry, w, ::cx, ::cw)
            ry += L.LB_ROW_H
        }
        for (r in v.out) {
            paintOut(c, r, x, ry, w, ::cx, ::cw)
            ry += L.LB_ROW_H
        }
        for (m in v.never) {
            paintNever(c, m, x, ry, w, ::cx)
            ry += L.LB_ROW_H
        }
        if (v.spectators.isNotEmpty()) {
            ry += 4
            c.rect(x, ry, x + w, ry + 1, Ct.LINE, Corner.SQUARE)
            ry += 8
            var sx = x + L.LB_PAD_X
            c.icon("ct_eye", sx, ry + 2, 10, Ct.T_77)
            sx += 15
            sx = Rich.colored("Watching: ", Ct.T_77).draw(c, sx, ry, ry + 14, S.CAPTION, Ct.T_77)
            for ((i, m) in v.spectators.withIndex()) {
                c.face(m.uuid, sx, ry + 1, 12)
                sx += 15
                val label = m.name + (if (v.isMe(m.uuid)) " (you)" else "") + (if (i < v.spectators.lastIndex) "," else "")
                sx = Rich.colored(label, Ct.T_77).draw(c, sx, ry, ry + 14, S.CAPTION, Ct.T_77) + 5
            }
        }
    }

    private fun alpha(color: Int): Int = if (stale) ((color ushr 24) * 62 / 100 shl 24) or (color and 0xFFFFFF) else color

    private fun paintRow(c: KitCanvas, r: StandingRow, x: Int, y: Int, w: Int, cx: (Int) -> Int, cw: (Int) -> Int) {
        val me = v.isMe(r.uuid)
        val done = r.units >= v.goal && v.goal > 0
        val zero = r.units == 0
        val hov = c.hovered(x, y, x + w, y + L.LB_ROW_H)
        if (me) {
            c.rect(x, y, x + w, y + L.LB_ROW_H, if (hov) 0x2E3B82F6 else 0x1F3B82F6, Corner.SMALL)
            c.rect(x, y + 2, x + 2, y + L.LB_ROW_H - 2, Ct.ACCENT, Corner.SQUARE)
        } else if (hov) {
            c.rect(x, y, x + w, y + L.LB_ROW_H, Ct.HOVER, Corner.SMALL)
        }
        val top = y
        val bot = y + L.LB_ROW_H
        val base = alpha(if (zero) Ct.T_66 else Ct.T_EE)
        // rank
        val rankText = (r.rank ?: 0).toString() + if (r.shared && !zero) "=" else ""
        val rkColor = alpha(if (done) Ct.GOLD else base)
        val rkW = c.width(rankText, S.HEADING, true)
        c.text(rankText, cx(0) + cw(0) - rkW, top, bot, rkColor, S.HEADING, true)
        if (r.rank == 1 && !zero) c.icon("ct_crown", cx(0) + cw(0) - rkW - 12, top + 10, 10, alpha(Ct.GOLD))
        // trend
        val before = r.rankBefore
        if (running && sort != "avg") {
            val rank = r.rank ?: 0
            val d = if (before == null) 0 else before - rank
            if (d > 0) {
                c.icon("ct_up", cx(1), top + 10, 10, alpha(Ct.GREEN))
                c.text("$d", cx(1) + 10, top, bot, alpha(Ct.GREEN), S.TINY, true)
                c.tooltip(cx(1), top, cx(1) + cw(1), bot, listOf("Up $d in the last ${L.TREND_MIN} min"), "tr:${r.uuid}")
            } else if (d < 0) {
                c.icon("ct_down", cx(1), top + 10, 10, alpha(Ct.RED))
                c.text("${-d}", cx(1) + 10, top, bot, alpha(Ct.RED), S.TINY, true)
                c.tooltip(cx(1), top, cx(1) + cw(1), bot, listOf("Down ${-d} in the last ${L.TREND_MIN} min"), "tr:${r.uuid}")
            } else {
                c.icon("ct_same", cx(1), top + 10, 10, alpha(Ct.T_44))
            }
        }
        // player
        val m = v.member(r.uuid)
        var px = cx(2)
        c.face(r.uuid, px, top + 7, 16)
        px += 16
        val presence = m?.presence
        if (presence != null && running) {
            c.dot(if (presence.online) (if (inRun(r.uuid) != null) Dot.RUN else Dot.ON) else Dot.OFF, px - 3, top + 21)
        }
        px += 6
        val nameRight = cx(2) + cw(2)
        val sub = r.unpriced > 0
        val lineTop = if (sub) top + 4 else top
        val lineBot = if (sub) top + 18 else bot
        val nameColor = alpha(if (zero) Ct.T_88 else Ct.T_EE)
        val name = Rich.colored(r.name, nameColor, true).ellipsize(c, S.SMALL, max(20, nameRight - px - 60), true)
        var nx = name.draw(c, px, lineTop, lineBot, S.SMALL, nameColor, true) + 4
        if (r.role == ContestView.OWNER) {
            c.icon("ct_crown", nx, (lineTop + lineBot) / 2 - 5, 10, alpha(Ct.GOLD))
            c.tooltip(nx, lineTop, nx + 10, lineBot, listOf("Owner"), "own:${r.uuid}")
            nx += 14
        } else if (r.role == ContestView.MANAGER) {
            c.icon("ct_shield", nx, (lineTop + lineBot) / 2 - 5, 10, alpha(Ct.ACCENT_LIGHT))
            c.tooltip(nx, lineTop, nx + 10, lineBot, listOf("Manager: can void runs, remove players, end early"), "mgr:${r.uuid}")
            nx += 14
        }
        if (me) nx += Ct.you().paint(c, nx, (lineTop + lineBot) / 2) + 6
        tieChip(v, r)?.takeIf { nx + it.width(c) <= nameRight }?.let { nx += it.paint(c, nx, (lineTop + lineBot) / 2, "tie:${r.uuid}") + 6 }
        if (sub) {
            val np = ContestsFormat.noPrice(r.unpriced)
            c.text(np, px, top + 17, top + 27, alpha(0xFF8A8A8A.toInt()), S.CHIP)
            c.tooltip(px, top + 17, px + c.width(np, S.CHIP), top + 27, listOf(ContestsFormat.NO_PRICE_TIP), "np:${r.uuid}")
        }
        // runs
        val rx = cx(3)
        var tx = Rich.colored("${r.units}", base, true).draw(c, rx, top + 4, top + 18, S.SMALL, base, true)
        tx = Rich.colored("/${v.goal}", alpha(Ct.T_55)).draw(c, tx, top + 4, top + 18, S.SMALL, Ct.T_55)
        if (me && queued > 0 && running) {
            tx = Rich.colored(" +$queued", alpha(Ct.GOLD)).draw(c, tx, top + 4, top + 18, S.TINY, Ct.GOLD)
            c.icon("ct_upload", tx + 1, top + 7, 9, alpha(Ct.GOLD))
            c.tooltip(rx, top, tx + 10, top + 18, listOf(Checks.QUEUED.text), "q:${r.uuid}")
            tx += 11
        }
        if (r.voided > 0) {
            val vt = " (${r.voided} void)"
            c.text(vt, tx, top + 4, top + 18, alpha(Ct.T_55), S.SMALL)
            c.tooltip(tx, top + 4, tx + c.width(vt, S.SMALL), top + 18, listOf("${r.voided} voided ${t.unit.plural} don't count"), "void:${r.uuid}")
        }
        c.fbar(rx, top + 21, cw(3), if (v.goal > 0) r.units.toFloat() / v.goal else 0f, alpha(if (done) Ct.GOLD else Ct.ACCENT))
        // total, avg, best
        c.rightText(Rich.of(ContestsFormat.metric(t, r.total)), cx(4), top, bot, cw(4), S.px(11.5f), alpha(if (done) Ct.GOLD else base), true)
        c.rightText(Rich.of(if (r.units > 0) ContestsFormat.metric(t, r.avg) else "–"), cx(5), top, bot, cw(5), S.SMALL, alpha(if (zero) Ct.T_66 else Ct.T_BB))
        c.rightText(Rich.of(if (r.units > 0) ContestsFormat.metric(t, r.best) else "–"), cx(6), top, bot, cw(6), S.SMALL, alpha(if (zero) Ct.T_66 else Ct.T_BB))
        // last
        if (!narrow) {
            val runN = if (running) inRun(r.uuid) else null
            val last = r.lastRunAt
            val (status, color) = if (done) "done ${clock(r.goalReachedAt ?: last)}" to Ct.T_55
            else if (r.units == 0) "not started" to Ct.T_55
            else if (!running) "last ${clock(last)}" to Ct.T_55
            else if (runN != null) "in ${t.unit.singular} $runN" to Ct.ACCENT_LIGHT
            else (if (last != null) ContestsFormat.ago(last, now) else "–") to Ct.T_55
            c.text(status, cx(7), top, bot, alpha(color), S.CAPTION)
        }
        // checks
        var kx = cx(8) + cw(8)
        val counts = ArrayList<Pair<String, Int>>()
        if (r.checks.verified > 0) counts += "verified" to r.checks.verified
        if (r.checks.pending > 0) counts += (if (v.finished) "unconfirmed" else "pending") to r.checks.pending
        if (r.checks.flagged > 0) counts += "flagged" to r.checks.flagged
        if (me && queued > 0 && running) counts += "queued" to queued
        for ((st, n) in counts.asReversed()) {
            val label = "$n"
            kx -= c.checkWidth(label)
            val s = Checks.of(st)
            c.check(st, kx, (top + bot) / 2, label, listOf("$n ${s.label.lowercase()}", s.text))
            kx -= 5
        }
        c.onClick(x, y, x + w, y + L.LB_ROW_H) { onPlayer(r.uuid) }
    }

    private fun paintOut(c: KitCanvas, r: StandingRow, x: Int, y: Int, w: Int, cx: (Int) -> Int, cw: (Int) -> Int) {
        val top = y
        val bot = y + L.LB_ROW_H
        if (c.hovered(x, y, x + w, bot)) c.rect(x, y, x + w, bot, Ct.HOVER, Corner.SMALL)
        c.text("–", cx(0) + cw(0) - c.width("–", S.HEADING, true), top, bot, Ct.T_66, S.HEADING, true)
        c.face(r.uuid, cx(2), top + 7, 16)
        Rich.colored(r.name, Ct.T_88, true).ellipsize(c, S.SMALL, cw(2) - 22, true).draw(c, cx(2) + 22, top, bot, S.SMALL, Ct.T_88, true)
        var tx = Rich.colored("${r.units}", Ct.T_66, true).draw(c, cx(3), top, bot, S.SMALL, Ct.T_66, true)
        Rich.colored("/${v.goal}", Ct.T_55).draw(c, tx, top, bot, S.SMALL, Ct.T_55)
        val total = ContestsFormat.metric(v.type, r.total)
        val tw = c.width(total, S.px(11.5f), true)
        c.text(total, cx(4) + cw(4) - tw, top, bot, Ct.T_66, S.px(11.5f), true)
        c.rect(cx(4) + cw(4) - tw, (top + bot) / 2, cx(4) + cw(4), (top + bot) / 2 + 1, Ct.T_66, Corner.SQUARE)
        val dq = r.status == ContestView.DISQUALIFIED
        val chip = if (dq) Ct.cxl("DISQUALIFIED") else Ct.plain("LEFT")
        var nx = cx(5)
        nx += chip.paint(c, nx, (top + bot) / 2, "out:${r.uuid}") + 4
        val note = if (dq) r.dqReason.orEmpty() else "Left the contest. Their ${v.type.unit.plural} stay listed but don't count."
        Rich.colored(note, Ct.T_77).ellipsize(c, S.CAPTION, x + w - L.LB_PAD_X - nx).draw(c, nx, top, bot, S.CAPTION, Ct.T_77)
        if (note.isNotEmpty()) c.tooltip(nx, top, x + w, bot, listOf(note), "outn:${r.uuid}")
        c.onClick(x, y, x + w, bot) { onPlayer(r.uuid) }
    }

    private fun paintNever(c: KitCanvas, m: Member, x: Int, y: Int, w: Int, cx: (Int) -> Int) {
        val top = y
        val bot = y + L.LB_ROW_H
        c.text("–", cx(1) - 6 - c.width("–", S.HEADING, true), top, bot, Ct.T_66, S.HEADING, true)
        c.face(m.uuid, cx(2), top + 7, 16)
        c.dot(if (m.presence.online) Dot.ON else Dot.OFF, cx(2) + 13, top + 21)
        val nameW = c.width(m.name, S.SMALL, true)
        c.text(m.name, cx(2) + 22, top, bot, Ct.T_88, S.SMALL, true)
        val nx0 = max(cx(3), cx(2) + 22 + nameW + 10)
        val note = requestNote(m)
        val chip = if (m.status == ContestView.REQUESTED) Ct.warn("ASKED TO JOIN", "Joining after the start needs the owner's or a manager's OK.") else Ct.plain("NEVER JOINED", "Invited, never accepted. Not ranked.")
        val nx = nx0 + chip.paint(c, nx0, (top + bot) / 2, "never:${m.uuid}") + 4
        note.ellipsize(c, S.CAPTION, x + w - L.LB_PAD_X - nx).draw(c, nx, top, bot, S.CAPTION, Ct.T_77)
    }
}

// ------------------------------------------------------------------ projection

class ProjectionNode(val v: ContestView) : Node() {
    private val rows = v.projection()
    override fun measure(m: KitMeasure, w: Int): Int = rows.size * 18 + 8 + 24

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val max = rows.firstOrNull()?.value?.coerceAtLeast(1) ?: 1
        val ws = L.resolve(listOf(L.px(12), L.px(12), L.Track(min = 70, fr = 0f), L.fr(0), L.px(50), L.px(40)), w, 6)
        // `minmax(70px, 120px)`: the name column takes up to 120.
        ws[2] = min(120, max(70, ws[2]))
        ws[3] = w - (12 + 12 + ws[2] + 50 + 40 + 5 * 6)
        var ry = y
        for ((i, p) in rows.withIndex()) {
            val me = v.isMe(p.row.uuid)
            var cx = x
            val top = ry
            val bot = ry + 18
            c.rightText(Rich.of("${i + 1}"), cx, top, bot, 12, S.SMALL, Ct.T_66)
            cx += 12 + 6
            c.face(p.row.uuid, cx, top + 3, 12)
            cx += 12 + 6
            Rich.colored(p.row.name, if (me) Ct.ME else Ct.T_CC).ellipsize(c, S.SMALL, ws[2]).draw(c, cx, top, bot, S.SMALL, Ct.T_CC)
            cx += ws[2] + 6
            val f = p.value.toFloat() / max
            if (p.final) c.fbar(cx, top + 7, ws[3], f, Ct.GOLD) else c.projBar(cx, top + 7, ws[3], f)
            cx += ws[3] + 6
            c.rightText(Rich.of(ContestsFormat.metric(v.type, p.value)), cx, top, bot, 50, S.SMALL, if (me) Ct.ME else Ct.T_EE, true)
            cx += 50 + 6
            c.rightText(Rich.of(if (p.final) "final" else "${p.row.units}/${v.goal}"), cx, top, bot, 40, S.TINY, Ct.T_55)
            ry += 18
        }
        val foot = "If everyone keeps their average so far. Finished players show their final total. Not a result."
        ParaNode(Rich.of(foot), S.TINY, Ct.T_66, 12).let { it.paint(c, x, ry + 8, w, it.height(c, w)) }
    }
}

// ------------------------------------------------------------------ feed

/** `.fes`: the live feed lines (time, icon, text), newest first. */
class FeedNode(
    val v: ContestView,
    val entries: List<FeedEntry>,
    val queued: List<QueuedRun>,
    val stale: Boolean,
    val onPlayer: (String) -> Unit,
) : Node() {
    private class Line(val at: Long, val cls: String, val icon: String, val text: Rich, val player: String?)

    private val lines: List<Line> by lazy {
        val out = ArrayList<Line>()
        for (q in queued) out += Line(q.finishedAt, "queued", "ct_upload", rich {
            t("Your ")
            c("${v.type.unit.singular} from ${clock(q.finishedAt)}", Ct.ACCENT_LIGHT)
            t(" is ")
            b("waiting to send")
        }, v.selfUuid)
        for (e in entries) line(e)?.let { out += it }
        out.sortedByDescending { it.at }.take(40)
    }

    private fun who(uuid: String?, name: String?): Rich =
        if (uuid != null && v.isMe(uuid)) Rich.colored("You", Ct.ME, true) else Rich.colored(name ?: "Someone", Ct.T_DD, true)

    private fun unitN(n: Int?) = "${v.type.unit.singular} ${n ?: "?"}"

    private fun line(e: FeedEntry): Line? {
        val p = e.player
        val w = who(p?.uuid, p?.name)
        val t = v.type
        val r = when (e.kind) {
            "run" -> Triple("run", "ct_check", rich {
                r(w); t(" finished "); c(unitN(e.int("n")), Ct.ACCENT_LIGHT); t(" ")
                val value = e.long("value") ?: 0
                c(ContestsFormat.signedMetric(t, value), if (value < 0) Ct.RED else Ct.COIN, true)
                if (t.runFields.duration) c(" · ${e.long("durationMs")?.let(::mmss) ?: "untimed"}", Ct.T_55)
                val np = e.int("unpriced") ?: 0
                if (np > 0) c(" · ${ContestsFormat.noPrice(np)}", Ct.T_55)
            })
            "drop" -> Triple("drop", "ct_gem", rich {
                r(w); t(" got "); c(e.str("name") ?: "?", Ct.COIN, true)
                val count = e.long("count") ?: 1
                if (count > 1) c(" x$count", Ct.T_55)
                t(" "); c(ContestsFormat.metric(t, e.long("value")), Ct.COIN, true)
            })
            "pass" -> {
                val other = e.ref("passed")
                Triple("pass", "ct_up", rich {
                    r(w); t(" passed "); r(who(other?.uuid, other?.name).let { if (other != null && v.isMe(other.uuid)) Rich.colored("you", Ct.ME, true) else it })
                    t(" for ${ord(e.int("rank") ?: 0)}")
                })
            }
            "goal" -> Triple("finish", "ct_trophy", rich { r(w); t(" finished all ${e.int("units") ?: v.goal} ${t.unit.plural} with "); c(ContestsFormat.metric(t, e.long("total")), Ct.COIN, true) })
            "flag" -> Triple("flag", "ct_flag", rich {
                c(unitN(runN(e)), Ct.ACCENT_LIGHT); t(" of "); r(w); t(" was "); c("flagged", Ct.GOLD, true)
                Checks.reason(e.str("reason"))?.let { t(": $it") }
            })
            "void" -> Triple("void", "ct_ban", rich { c(unitN(runN(e)), Ct.ACCENT_LIGHT); t(" of "); r(w); t(" was "); b("voided"); t(". ${e.str("reason").orEmpty()}") })
            "restore" -> Triple("void", "ct_refresh", rich { c(unitN(runN(e)), Ct.ACCENT_LIGHT); t(" of "); r(w); t(" was "); b("restored") })
            "dq" -> Triple("dq", "ct_ban", rich { r(w); t(" was "); b("disqualified"); e.str("reason")?.takeIf { it.isNotEmpty() }?.let { t(": $it") } })
            "request" -> Triple("run", "ct_user_plus", rich { r(w); t(" asked to join") })
            "approve" -> Triple("pass", "ct_user_plus", rich { r(w); t(" joined late, accepted by "); r(who(e.actor?.uuid, e.actor?.name)) })
            "join" -> Triple("run", "ct_user_plus", rich { r(w); t(" joined") })
            "leave" -> Triple("dq", "ct_door", rich { r(w); t(" left") })
            "start" -> Triple("finish", "ct_play", rich { t("The contest "); b("started") })
            "end" -> Triple("finish", "ct_stop", rich { t("The contest "); b("ended") })
            "cancel" -> Triple("dq", "ct_close", rich { t("The contest was "); b("canceled") })
            else -> null
        } ?: return null
        return Line(e.at, r.first, r.second, r.third, p?.uuid)
    }

    private fun runN(e: FeedEntry): Int? = e.int("n") ?: v.snap.feed.firstOrNull { it.kind == "run" && it.str("runId") == e.str("runId") }?.int("n")

    private fun iconColor(cls: String) = when (cls) {
        "drop" -> Ct.COIN
        "flag" -> Ct.GOLD
        "void", "dq" -> Ct.RED
        "finish" -> Ct.GOLD
        "pass" -> Ct.GREEN
        "queued" -> Ct.ACCENT_LIGHT
        else -> 0xFF3A6A4A.toInt()
    }

    private fun lineH(m: KitMeasure, l: Line, w: Int) = 6 + l.text.wrap(m, S.SMALL, w - 30 - 12 - 10).size * 13

    override fun measure(m: KitMeasure, w: Int): Int = if (lines.isEmpty()) 24 else lines.sumOf { lineH(m, it, w) }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (lines.isEmpty()) {
            c.text("Nothing yet.", x + 2, y, y + 24, Ct.T_66, S.SMALL)
            return
        }
        var ly = y
        for (l in lines) {
            val lh = lineH(c, l, w)
            if (l.cls == "drop") c.rect(x, ly, x + w, ly + lh, 0x0DFFAA00, Corner.SQUARE)
            c.rect(x, ly + lh - 1, x + w, ly + lh, 0xFF232323.toInt(), Corner.SQUARE)
            val a = if (stale) 0x9E else 0xFF
            fun al(col: Int) = (a shl 24) or (col and 0xFFFFFF)
            c.text(clock(l.at), x, ly + 3, ly + 16, al(Ct.T_55), S.TINY)
            c.icon(l.icon, x + 35, ly + 4, 10, al(iconColor(l.cls)))
            var ty = ly + 3
            for (t in l.text.wrap(c, S.SMALL, w - 30 - 12 - 10)) {
                t.draw(c, x + 47, ty, ty + 13, S.SMALL, al(Ct.T_AA))
                ty += 13
            }
            val pl = l.player
            if (pl != null) c.onClick(x, ly, x + w, ly + lh) { onPlayer(pl) }
            ly += lh
        }
    }
}

// ------------------------------------------------------------------ runs (player detail)

/** `.rr`: the run list, newest first, with the why-line under a flagged/voided/unconfirmed/queued run. */
class RunListNode(
    val t: ContestTypeDescriptor,
    val runs: List<Run>,
    val queued: List<QueuedRun>,
    val finished: Boolean,
    val manage: Boolean,
    val onVoid: (Run) -> Unit,
    val onRestore: (Run) -> Unit,
) : Node() {
    private class Row(val n: String, val at: Long, val dur: Long?, val partial: Boolean, val value: Long?, val state: String, val run: Run?, val q: QueuedRun?, val why: String?)

    private val rows: List<Row> by lazy {
        val out = ArrayList<Row>()
        for (q in queued) out += Row("–", q.finishedAt, q.durationMs, q.durationMs == null, null, "queued", null, q,
            "Saved on this computer. Soul sends it as soon as it reconnects; it counts if its bundle came before the end.")
        for (r in runs.asReversed()) {
            val st = r.check.state
            val state = if (r.voided != null || st == "voided") "voided" else if (st == "pending" && finished) "unconfirmed" else st
            val why = when (state) {
                "voided" -> "Voided by ${r.voided?.by?.name ?: "an owner or manager"}: ${r.voided?.reason.orEmpty()}"
                "flagged" -> Checks.reason(r.check.reason)?.let { "Flagged: $it" }
                "unconfirmed" -> if (r.late) "Bundle at ${clock(r.finishedAt)}, before the end. Sent after the end (saved while offline): it counts, and the standings were updated." else "Hypixel's API hadn't shown this run when the contest ended. Results are final, so it counts as it is."
                "pending" -> Checks.reason(r.check.reason)?.takeIf { r.check.reason != "waiting" }
                else -> if (r.late) "Sent after the end; its bundle came before the end, so it counts." else null
            }
            val n = r.n?.toString() ?: if (r.counted) "?" else "–"
            out += Row(n, r.finishedAt, r.durationMs, r.partial, r.value, state, r, null, why)
        }
        out
    }

    private fun whyH(m: KitMeasure, r: Row, w: Int, x2: Int) = if (r.why == null || r.state == "verified") 0 else 4 + Rich.of(r.why).wrap(m, S.CAPTION, w - x2).size * 12

    override fun measure(m: KitMeasure, w: Int): Int {
        if (rows.isEmpty()) return 18 + 24
        val geo = cols(L.RUN_TRACKS, w - 12, L.RUN_GAP)
        return 18 + rows.sumOf { L.RUN_ROW_H + whyH(m, it, w - 12, geo[2]) }
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val n = L.RUN_TRACKS.size
        val geo = cols(L.RUN_TRACKS, w - 12, L.RUN_GAP)
        fun cx(i: Int) = x + 6 + geo[i]
        fun cw(i: Int) = geo[n + i]
        val hy = y + 9
        c.rect(x, y + 17, x + w, y + 18, Ct.RULE, Corner.SQUARE)
        c.headCell("#", cx(0), hy, cw(0), true)
        c.headCell("Done", cx(1), hy, cw(1), false)
        if (t.runFields.duration) c.headCell("Time", cx(2), hy, cw(2), false)
        c.headCell(t.metric.label.ifEmpty { "Value" }, cx(3), hy, cw(3), true)
        if (t.runFields.items) c.headCell("Key loot", cx(4), hy, cw(4), false)
        c.headCell("Check", cx(5), hy, cw(5), false)
        if (rows.isEmpty()) {
            c.text("No ${t.unit.plural} yet.", x + 2, y + 18, y + 42, Ct.T_66, S.SMALL)
            return
        }
        var ry = y + 18
        for (r in rows) {
            val wh = whyH(c, r, w - 12, geo[2])
            val rh = L.RUN_ROW_H + wh
            val hov = c.hovered(x, ry, x + w, ry + rh)
            when (r.state) {
                "flagged" -> {
                    c.rect(x, ry, x + w, ry + rh, 0x12F0A640, Corner.SMALL)
                    c.rect(x, ry + 2, x + 2, ry + rh - 2, Ct.GOLD, Corner.SQUARE)
                }
                "queued" -> {
                    c.rect(x, ry, x + w, ry + rh, 0x123B82F6, Corner.SMALL)
                    c.rect(x, ry + 2, x + 2, ry + rh - 2, Ct.ACCENT_LIGHT, Corner.SQUARE)
                }
                else -> if (hov) c.rect(x, ry, x + w, ry + rh, Ct.HOVER, Corner.SMALL)
            }
            val voided = r.state == "voided"
            fun dim(col: Int) = if (voided) (0x73 shl 24) or (col and 0xFFFFFF) else col
            val top = ry
            val bot = ry + L.RUN_ROW_H
            c.rightText(Rich.of(r.n), cx(0), top, bot, cw(0), S.SMALL, Ct.T_88)
            c.text(clock(r.at), cx(1), top, bot, dim(Ct.T_88), S.SMALL)
            if (t.runFields.duration) {
                if (r.dur == null) {
                    c.text("untimed", cx(2), top, bot, dim(Ct.T_66), S.SMALL)
                    c.tooltip(cx(2), top, cx(2) + cw(2), bot, listOf(if (r.partial) "The first run after entering, a server change or a restart has no start time." else "No start time.", "Counted, but left out of run times."), "unt:${r.n}:${r.at}")
                } else c.text(mmss(r.dur), cx(2), top, bot, dim(Ct.T_CC), S.SMALL)
            }
            val value = r.value
            val vt = if (value == null) "–" else ContestsFormat.signedMetric(t, value)
            val vw = c.width(vt, S.SMALL, true)
            c.text(vt, cx(3) + cw(3) - vw, top, bot, dim(if (value != null && value < 0) Ct.RED else Ct.COIN), S.SMALL, true)
            if (voided) c.rect(cx(3) + cw(3) - vw, (top + bot) / 2, cx(3) + cw(3), (top + bot) / 2 + 1, dim(Ct.COIN), Corner.SQUARE)
            if (t.runFields.items) paintLoot(c, r, cx(4), top, bot, cw(4), ::dim)
            val st = Checks.of(r.state)
            c.check(r.state, cx(5), (top + bot) / 2, st.label.let { if (it.length > 14 && cw(5) < 120) "Not confirmed" else it }, Checks.tip(st))
            val run = r.run
            if (manage && run != null) {
                val b = if (voided) Btn("Restore", style = Btn.GHOST, small = true) { onRestore(run) }
                else Btn("Void", style = if (r.state == "flagged") Btn.DANGER else Btn.GHOST, small = true) { onVoid(run) }
                c.button(b, cx(6) + cw(6) - b.width(c), top + 1)
            }
            if (wh > 0 && r.why != null) {
                val col = when (r.state) {
                    "flagged" -> Ct.GOLD
                    "voided" -> 0xFFCC7777.toInt()
                    "queued" -> Ct.ACCENT_LIGHT
                    else -> Ct.T_99
                }
                var wy = bot
                for (l in Rich.of(r.why).wrap(c, S.CAPTION, w - 12 - geo[2])) {
                    l.draw(c, cx(2), wy, wy + 12, S.CAPTION, col)
                    wy += 12
                }
            }
            ry += rh
        }
    }

    private fun paintLoot(c: KitCanvas, r: Row, x: Int, top: Int, bot: Int, w: Int, dim: (Int) -> Int) {
        val items = r.run?.items ?: r.q?.items?.map { com.soulreturns.features.contests.model.RunItem(it.name, it.id, it.count, null, 0) } ?: emptyList()
        val notable = t.notableUnitValue ?: Long.MAX_VALUE
        val priced = items.filter { it.unitPrice != null }.sortedByDescending { it.value }.take(3)
        val unpriced = if (r.run != null) items.filter { it.unitPrice == null } else emptyList()
        val powder = r.run?.powder ?: r.q?.powder ?: emptyMap()
        val parts = ArrayList<Rich>()
        val shown = if (r.q != null) items.take(3) else priced
        for ((i, it) in shown.withIndex()) {
            if (i > 0) parts += Rich.colored(", ", Ct.T_55)
            val col = if ((it.unitPrice ?: 0) >= notable) Ct.COIN else Ct.T_DD
            parts += Rich.colored(it.name, dim(col))
            if (it.count > 1) parts += Rich.colored(" x${it.count}", Ct.T_55)
        }
        val extraW = (if (unpriced.isNotEmpty()) c.width(ContestsFormat.noPrice(unpriced.size), S.CHIP, true) + 10 else 0) + (if (powder.values.any { it > 0 }) 14 else 0)
        var ex = Rich.join(*parts.toTypedArray()).ellipsize(c, S.SMALL, w - extraW).draw(c, x, top, bot, S.SMALL, Ct.T_DD)
        if (unpriced.isNotEmpty()) {
            val np = ContestsFormat.noPrice(unpriced.size)
            val nw = c.width(np, S.CHIP, true) + 6
            ex += 4
            c.rect(ex, (top + bot) / 2 - 5, ex + nw, (top + bot) / 2 + 6, Ct.RULE, Corner.SMALL)
            c.text(np, ex + 3, top, bot, Ct.T_99, S.CHIP, true)
            c.tooltip(ex, top, ex + nw, bot, listOf("Count 0 coins, not hidden:") + unpriced.map { it.name + if (it.count > 1) " x${it.count}" else "" }, "np:${r.n}:${r.at}")
            ex += nw
        }
        val pw = powder.filterValues { it > 0 }
        if (pw.isNotEmpty()) {
            ex += 3
            c.icon("ct_gem", ex, (top + bot) / 2 - 5, 10, Ct.T_55)
            c.tooltip(ex, top, ex + 10, bot, pw.map { (k, n) -> "${ContestsFormat.capitalize(k)} Powder x${com.soulreturns.features.profileviewer.gui.kit.Format.num(n)}" } + "Powder has no coin value.", "pw:${r.n}:${r.at}")
        }
    }
}

// ------------------------------------------------------------------ history table

/** `.htbl`: past contests (or the last few on the home page). */
class HistoryTableNode(val rows: List<ContestSummary>, val selfUuid: String, val onOpen: (ContestSummary) -> Unit) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 8 + 18 + rows.size * L.HISTORY_ROW_H + if (rows.isEmpty()) 24 else 0

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.layer()
        c.rect(x, y, x + w, y + h, Ct.CARD, Corner.MEDIUM)
        val n = L.HISTORY_TRACKS.size
        val geo = cols(L.HISTORY_TRACKS, w - 24, L.HISTORY_GAP)
        fun cx(i: Int) = x + 12 + geo[i]
        fun cw(i: Int) = geo[n + i]
        val hy = y + 4 + 9
        c.rect(x, y + 4 + 17, x + w, y + 4 + 18, Ct.RULE, Corner.SQUARE)
        listOf("Contest", "Type", "Date", "Winner", "Players", "You").forEachIndexed { i, s -> c.headCell(s, cx(i), hy, cw(i), false) }
        var ry = y + 4 + 18
        if (rows.isEmpty()) {
            c.text("No past contests yet.", x + 12, ry, ry + 24, Ct.T_66, S.SMALL)
            return
        }
        for (s in rows) {
            val top = ry
            val bot = ry + L.HISTORY_ROW_H
            if (c.hovered(x, top, x + w, bot)) c.rect(x, top, x + w, bot, Ct.HOVER, Corner.SQUARE)
            val cxl = s.state == ContestView.CANCELED
            val nameCol = if (cxl) Ct.T_88 else Ct.T_EE
            val nm = Rich.colored(s.name, nameCol, true).ellipsize(c, S.SMALL, cw(0), true)
            val nw = nm.draw(c, cx(0), top, bot, S.SMALL, nameCol, true) - cx(0)
            if (cxl) c.rect(cx(0), (top + bot) / 2, cx(0) + nw, (top + bot) / 2 + 1, Ct.T_55, Corner.SQUARE)
            Rich.colored("${s.typeTitle} · ${s.goal.label}", Ct.T_CC).ellipsize(c, S.SMALL, cw(1)).draw(c, cx(1), top, bot, S.SMALL, Ct.T_CC)
            c.text((s.endedAt ?: s.startedAt ?: s.startsAt)?.let { ContestsFormat.date(it) } ?: "–", cx(2), top, bot, Ct.T_CC, S.SMALL)
            if (cxl) {
                Ct.cxl("CANCELED").paint(c, cx(3), (top + bot) / 2, "h:${s.id}")
                c.text("${s.players} joined", cx(4), top, bot, Ct.T_55, S.SMALL)
                c.text("–", cx(5), top, bot, Ct.T_55, S.SMALL)
            } else {
                val win = s.top.filter { it.rank == 1 }
                var wx = cx(3)
                val wEnd = cx(3) + cw(3)
                c.icon("ct_crown", wx, (top + bot) / 2 - 5, 10, Ct.GOLD)
                wx += 14
                for (wr in win) {
                    if (wx + 12 > wEnd) break
                    c.face(wr.uuid, wx, (top + bot) / 2 - 6, 12)
                    wx += 15
                    wx = Rich.colored(wr.name, Ct.T_CC).ellipsize(c, S.SMALL, wEnd - wx - 40).draw(c, wx, top, bot, S.SMALL, Ct.T_CC) + 4
                }
                if (win.isNotEmpty()) Rich.colored(coins(win[0].total), Ct.T_EE, true).ellipsize(c, S.SMALL, max(0, wEnd - wx), true).draw(c, wx, top, bot, S.SMALL, Ct.T_EE, true)
                var fx = cx(4)
                for (tr in s.top.take(4)) {
                    c.face(tr.uuid, fx, (top + bot) / 2 - 6, 12)
                    fx += 14
                }
                if (s.players > s.top.size.coerceAtMost(4)) c.text("+${s.players - s.top.size.coerceAtMost(4)}", fx + 1, top, bot, Ct.T_55, S.CAPTION)
                val me = s.me
                val you = if (me.status == ContestView.DISQUALIFIED) "DQ" else me.rank?.let { ord(it) + if (me.shared) " (shared)" else "" } ?: "–"
                Rich.colored(you, Ct.T_CC).ellipsize(c, S.SMALL, cw(5)).draw(c, cx(5), top, bot, S.SMALL, Ct.T_CC)
            }
            c.onClick(x, top, x + w, bot) { onOpen(s) }
            ry = bot
        }
    }
}

// ------------------------------------------------------------------ podium + per player

/** `.podium`: places 1-3 (2nd, 1st, 3rd), shared places side by side. */
class PodiumNode(val v: ContestView) : Node() {
    private val groups: List<List<StandingRow>> = v.ranked.mapNotNull { it.rank }.distinct().filter { it <= 3 }.sorted().map { r -> v.ranked.filter { it.rank == r } }
    private val order = listOfNotNull(groups.getOrNull(1), groups.getOrNull(0), groups.getOrNull(2))
    private fun blockH(rank: Int) = if (rank == 1) 64 else if (rank == 2) 44 else 30
    private fun blockColor(rank: Int) = if (rank == 1) Ct.GOLD else if (rank == 2) 0xFFC8C8C8.toInt() else 0xFFC27A4A.toInt()

    override fun measure(m: KitMeasure, w: Int): Int = 14 + (34 + 6) + 3 + 2 + 13 + 2 + 16 + 2 + 4 + 64

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.layer()
        c.rect(x, y, x + w, y + h, Ct.CARD, Corner.MEDIUM)
        val pw = 120
        val total = order.size * pw + 10 * max(0, order.size - 1)
        var px = x + (w - total) / 2
        val bottom = y + h
        for (g in order) {
            val rank = g[0].rank ?: 0
            val bh = blockH(rank)
            val by = bottom - bh
            c.rect(px, by, px + pw, bottom, blockColor(rank), Corner.MEDIUM)
            c.rect(px, bottom - 6, px + pw, bottom, blockColor(rank), Corner.SQUARE)
            val label = if (g.size > 1) "${ord(rank)} (shared)" else ord(rank)
            val lw = c.width(label, S.px(12f), true)
            c.text(label, px + (pw - lw) / 2, by + 3, by + 19, 0xFF111111.toInt(), S.px(12f), true)
            val vy = by - 4 - 2 - 16
            val value = ContestsFormat.metric(v.type, g[0].total)
            val vw = c.width(value, S.HEADING, true)
            c.text(value, px + (pw - vw) / 2, vy, vy + 16, Ct.COIN, S.HEADING, true)
            val ny = vy - 2 - 13
            val me = g.any { v.isMe(it.uuid) }
            val names = Rich.colored(g.joinToString(" & ") { it.name }, if (me) Ct.ME else Ct.T_EE, true).ellipsize(c, S.BODY, pw, true)
            val nw = names.width(c, S.BODY, true)
            names.draw(c, px + (pw - nw) / 2, ny, ny + 13, S.BODY, Ct.T_EE, true)
            val fs = if (g.size > 1) 26 else 34
            val boxW = g.size * fs + (g.size - 1) * 3 + 6
            val fy = ny - 2 - 3 - (fs + 6)
            val fx = px + (pw - boxW) / 2
            c.rect(fx, fy, fx + boxW, fy + fs + 6, 0xFF161616.toInt(), Corner.MEDIUM)
            for ((i, r) in g.withIndex()) c.face(r.uuid, fx + 3 + i * (fs + 3), fy + 3, fs)
            px += pw + 10
        }
    }
}

/** `.ps`: one row per player with tiles; click opens the player. */
class PerPlayerNode(val v: ContestView, val onPlayer: (String) -> Unit) : Node() {
    private val rows = v.ranked + v.out
    override fun measure(m: KitMeasure, w: Int): Int = rows.size * 42

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var ry = y
        val t = v.type
        for (r in rows) {
            val top = ry
            val bot = ry + 42
            if (c.hovered(x - 4, top, x + w + 4, bot)) c.rect(x - 4, top, x + w + 4, bot, Ct.HOVER, Corner.SMALL)
            if (v.isMe(r.uuid)) c.rect(x - 4, top + 2, x - 2, bot - 2, Ct.ACCENT, Corner.SQUARE)
            c.rect(x - 4, bot - 1, x + w + 4, bot, Ct.LINE, Corner.SQUARE)
            c.face(r.uuid, x, top + 11, 20)
            val tx = x + 30
            val head = (if (r.rank == null) "–" else ord(r.rank)) + " · " + r.name
            Rich.colored(head, Ct.T_EE, true).ellipsize(c, S.BODY, 220, true).draw(c, tx, top + 7, top + 21, S.BODY, Ct.T_EE, true)
            val sub = if (r.status == ContestView.DISQUALIFIED) r.dqReason.orEmpty()
            else if (r.unpriced > 0) ContestsFormat.noPrice(r.unpriced) else "Best ${t.unit.singular} ${ContestsFormat.metric(t, r.best)}"
            Rich.colored(sub, Ct.T_88).ellipsize(c, S.CAPTION, 220).draw(c, tx, top + 21, top + 33, S.CAPTION, Ct.T_88)
            val tiles = listOf(
                ContestsFormat.unitsTitle(t) to Rich.of("${r.units}/${v.goal}"),
                "Total" to Rich.of(ContestsFormat.metric(t, r.total)),
                "Avg/${t.unit.singular}" to Rich.of(if (r.units > 0) ContestsFormat.metric(t, r.avg) else "–"),
                "Best ${t.unit.singular}" to Rich.of(if (r.units > 0) ContestsFormat.metric(t, r.best) else "–"),
            ) + if (t.runFields.duration) listOf("Median time" to Rich.of(mmss(r.medianDurationMs))) else emptyList()
            val tn = TilesRowNode(tiles)
            val tl = tx + 230
            tn.paint(c, tl, top + 6, x + w - tl, tn.height(c, x + w - tl))
            c.onClick(x - 4, top, x + w + 4, bot) { onPlayer(r.uuid) }
            ry = bot
        }
    }
}

// ------------------------------------------------------------------ headers

/** `.chead` / `.phead`: icon (or face) box, name with chips, a dim line, buttons at the right. */
class HeadNode(
    val title: String,
    val chips: List<Chip>,
    val sub: Rich,
    val icon: String? = null,
    val faceUuid: String? = null,
    val buttons: List<Btn> = emptyList(),
    val subDot: Int? = null,
    /** Called with each button's rect as it is painted (popover anchors). */
    val onButtonRect: (Btn, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> },
) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = if (faceUuid != null) 50 else 58

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.layer()
        c.rect(x, y, x + w, y + h, Ct.CARD, Corner.MEDIUM)
        var tx = x + 12
        if (icon != null) {
            c.rect(tx, y + 10, tx + 38, y + 48, 0xFF252525.toInt(), Corner.MEDIUM)
            c.icon(icon, tx + 8, y + 18, 22, Ct.ACCENT_LIGHT)
            tx += 48
        } else if (faceUuid != null) {
            c.face(faceUuid, tx, y + 8, 34)
            tx += 44
        }
        var bx = x + w - 12
        for (b in buttons.asReversed()) {
            bx -= b.width(c)
            val by = y + (h - b.height()) / 2
            c.button(b, bx, by)
            onButtonRect(b, bx, by, bx + b.width(c), by + b.height())
            bx -= 4
        }
        val cy = y + h / 2
        val nameEnd = Rich.colored(title, Ct.T_EE, true).ellipsize(c, S.TITLE, max(40, bx - tx - chips.sumOf { it.width(c) + 6 }), true).draw(c, tx, cy - 19, cy, S.TITLE, Ct.T_EE, true)
        var chx = nameEnd + 8
        for (ch in chips) chx += ch.paint(c, chx, cy - 9, "head:${ch.text}") + 5
        var sx = tx
        if (subDot != null) {
            c.dot(subDot, sx, cy + 9)
            sx += 10
        }
        sub.ellipsize(c, S.CAPTION, bx - sx).draw(c, sx, cy + 2, cy + 15, S.CAPTION, Ct.T_88)
    }
}

/** `.hero`: the running contest card on the home page. */
class HeroNode(val s: ContestSummary, val self: String, val line: Rich, val sub: String, val onOpen: () -> Unit) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 12 + max(58, s.top.size.coerceAtMost(4) * 16) + 12

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.layer()
        val hov = c.hovered(x, y, x + w, y + h)
        c.rect(x, y, x + w, y + h, if (hov) 0xFF222222.toInt() else Ct.CARD, Corner.MEDIUM)
        c.rect(x, y + 3, x + 2, y + h - 3, if (s.state == ContestView.RUNNING) Ct.GREEN else Ct.ACCENT_LIGHT, Corner.SQUARE)
        c.rect(x + 14, y + (h - 40) / 2, x + 54, y + (h + 40) / 2, 0xFF252525.toInt(), Corner.MEDIUM)
        c.icon("ct_gem", x + 23, y + (h - 22) / 2, 22, Ct.ACCENT_LIGHT)
        val open = Btn("Open", style = Btn.ACCENT, onClick = onOpen)
        val bx = x + w - 14 - open.width(c)
        c.button(open, bx, y + (h - 24) / 2)
        val showTop = s.state == ContestView.RUNNING && s.top.isNotEmpty()
        val standW = if (showTop) 200 else 0
        val tx = x + 66
        val textR = bx - 12 - (if (showTop) standW + 12 else 0)
        val cy = y + h / 2
        var nx = Rich.colored(s.name, Ct.T_EE, true).ellipsize(c, S.HEADING, textR - tx - 100, true).draw(c, tx, cy - 22, cy - 6, S.HEADING, Ct.T_EE, true) + 5
        Ct.state(s.state)?.let { nx += it.paint(c, nx, cy - 14, "hero:st") + 5 }
        Ct.role(s.me.role)?.let { it.paint(c, nx, cy - 14, "hero:role") }
        Rich.colored(sub, Ct.T_88).ellipsize(c, S.CAPTION, textR - tx).draw(c, tx, cy - 5, cy + 7, S.CAPTION, Ct.T_88)
        line.ellipsize(c, S.SMALL, textR - tx).draw(c, tx, cy + 7, cy + 21, S.SMALL, Ct.T_CC)
        if (showTop) {
            val sx = bx - 12 - standW
            var sy = y + (h - s.top.size.coerceAtMost(4) * 16) / 2
            for (tr in s.top.take(4)) {
                val me = tr.uuid == self
                c.rightText(Rich.of("${tr.rank ?: "–"}"), sx, sy, sy + 14, 10, S.CAPTION, Ct.T_66)
                c.face(tr.uuid, sx + 15, sy + 1, 12)
                val v = coins(tr.total)
                val vw = c.width(v, S.CAPTION, true)
                c.text(v, sx + standW - vw, sy, sy + 14, if (me) Ct.ME else Ct.T_EE, S.CAPTION, true)
                Rich.colored(tr.name, if (me) Ct.ME else Ct.T_AA).ellipsize(c, S.CAPTION, standW - 32 - vw - 5).draw(c, sx + 32, sy, sy + 14, S.CAPTION, Ct.T_AA)
                sy += 16
            }
        }
        c.onClick(x, y, bx - 4, y + h, onOpen)
    }
}

/** The "How results are checked" steps (`.hw`). */
class StepNode(val n: Int, val icon: String, val title: String, val text: Rich) : Node() {
    private fun textW(w: Int) = minOf(w, 720) - 28 - 18 - 12 - 22 - 12
    override fun measure(m: KitMeasure, w: Int): Int = 10 + 16 + 3 + text.wrap(m, S.SMALL, textW(w)).size * 14 + 10

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val bw = minOf(w, 720)
        c.rect(x, y, x + bw, y + h, Ct.CARD, Corner.MEDIUM)
        c.rect(x + 14, y + 12, x + 32, y + 30, Ct.ACCENT_DIM, Corner.PILL)
        val nw = c.width("$n", S.CAPTION, true)
        c.text("$n", x + 23 - nw / 2, y + 12, y + 30, Ct.WHITE, S.CAPTION, true)
        c.icon(icon, x + 44, y + 10, 22, Ct.ACCENT_LIGHT)
        val tx = x + 78
        c.text(title, tx, y + 10, y + 26, Ct.T_EE, S.px(12f), true)
        var ly = y + 29
        for (l in text.wrap(c, S.SMALL, textW(w))) {
            l.draw(c, tx, ly, ly + 14, S.SMALL, Ct.T_AA)
            ly += 14
        }
    }
}

/** `.lg`: badge column (100 px) and the meaning. */
class LegendRowNode(val state: String) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 4 + Rich.of(Checks.of(state).text).wrap(m, S.SMALL, w - 108).size * 13
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val s = Checks.of(state)
        val label = if (s.label.length > 14) "Not confirmed" else s.label
        c.check(state, x, y + 8, label, Checks.tip(s))
        var ly = y + 2
        for (l in Rich.of(s.text).wrap(c, S.SMALL, w - 108)) {
            l.draw(c, x + 108, ly, ly + 13, S.SMALL, Ct.T_99)
            ly += 13
        }
    }
}

/** Spacer-free vertical stack with a gap (re-exported for page code). */
fun stackOf(nodes: List<Node>, gap: Int = 8): Node = com.soulreturns.features.profileviewer.gui.kit.StackNode(nodes, gap)

/** Height-only filler used in tests and empty states. */
class FixedNode(val h: Int, val draw: (KitCanvas, Int, Int, Int, Int) -> Unit = { _, _, _, _, _ -> }) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = 0
    override fun measure(m: KitMeasure, w: Int): Int = h
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = draw(c, x, y, w, h)
}
