package com.soulreturns.features.contests.gui

import com.soulreturns.features.profileviewer.gui.kit.Chip
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Inline
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import kotlin.math.max

/** The contests mock's palette (`mock.css`), on top of [S]. */
object Ct {
    val GREEN = 0xFF4ADE80.toInt()
    val GOLD = 0xFFF0A640.toInt()
    val RED = 0xFFE05252.toInt()
    val ACCENT = 0xFF3B82F6.toInt()
    val ACCENT_DIM = 0xFF2563EB.toInt()
    val ACCENT_LIGHT = 0xFF7FB0FF.toInt()
    val ME = 0xFF55FFFF.toInt()
    val COIN = 0xFFFFAA00.toInt()
    val PURPLE = 0xFFB9A6FF.toInt()
    val CARD = 0xFF1E1E1E.toInt()
    val HOVER = 0xFF242424.toInt()
    val LINE = 0xFF252525.toInt()
    val RULE = 0xFF2A2A2A.toInt()
    val WHITE = 0xFFFFFFFF.toInt()
    val T_EE = 0xFFEEEEEE.toInt()
    val T_DD = 0xFFDDDDDD.toInt()
    val T_CC = 0xFFCCCCCC.toInt()
    val T_BB = 0xFFBBBBBB.toInt()
    val T_AA = 0xFFAAAAAA.toInt()
    val T_99 = 0xFF999999.toInt()
    val T_88 = 0xFF888888.toInt()
    val T_77 = 0xFF777777.toInt()
    val T_66 = 0xFF666666.toInt()
    val T_55 = 0xFF555555.toInt()
    val T_44 = 0xFF444444.toInt()
    val UNCONF = 0xFF9AA7C0.toInt()

    // chips (`.chip.*`)
    fun you() = Chip("YOU", WHITE, ACCENT_DIM)
    fun own(tip: String? = "Created the contest. Everything a manager can do, plus making managers.") = Chip("OWNER", GOLD, 0xFF3A2A12.toInt(), tip = tip)
    fun mgr(tip: String? = "Can invite, remove players, edit before the start, void runs and end early.") = Chip("MANAGER", 0xFFCFE0FF.toInt(), 0x333B82F6, tip = tip)
    fun spec(text: String = "SPECTATOR", tip: String? = "Watches live, does not compete.") = Chip(text, 0xFFC8C8C8.toInt(), 0xFF262626.toInt(), 0xFF3A3A3A.toInt(), tip)
    fun later(text: String, tip: String? = null) = Chip(text, T_88, 0, 0xFF3A3A3A.toInt(), tip)
    fun warn(text: String, tip: String? = null) = Chip(text, GOLD, 0xFF3A2A12.toInt(), tip = tip)
    fun proj(text: String, tip: String? = null) = Chip(text, PURPLE, 0x298B5CF6, tip = tip)
    fun type(text: String) = Chip(text, ACCENT_LIGHT, 0x1F3B82F6)
    fun ok(text: String, tip: String? = null) = Chip(text, GREEN, 0x244ADE80, tip = tip)
    fun cxl(text: String, tip: String? = null) = Chip(text, 0xFFEE8888.toInt(), 0xFF3A1A1A.toInt(), tip = tip)
    fun plain(text: String, tip: String? = null) = Chip(text, T_AA, RULE, tip = tip)

    fun state(state: String): Chip? = when (state) {
        ContestView.LOBBY, ContestView.DRAFT -> Chip(if (state == ContestView.DRAFT) "DRAFT" else "LOBBY", ACCENT_LIGHT, 0x333B82F6)
        ContestView.RUNNING -> ok("RUNNING")
        ContestView.FINISHED -> warn("FINISHED")
        ContestView.CANCELED -> cxl("CANCELED")
        else -> null
    }

    fun role(role: String): Chip? = when (role) {
        ContestView.OWNER -> own()
        ContestView.MANAGER -> mgr()
        ContestView.SPECTATOR -> spec()
        else -> null
    }
}

/** Builds a [Rich] from parts: plain runs in the node's color, bold white, colored runs. */
class RichBuilder {
    private val parts = ArrayList<Rich>()
    fun t(s: String) = apply { if (s.isNotEmpty()) parts += Rich.of(s.replace("§", "")) }
    fun b(s: String, color: Int = Ct.T_EE) = apply { parts += Rich.colored(s, color, bold = true) }
    fun c(s: String, color: Int, bold: Boolean = false) = apply { parts += Rich.colored(s, color, bold) }
    fun r(r: Rich) = apply { parts += r }
    fun build(): Rich = Rich.join(*parts.toTypedArray())
}

fun rich(block: RichBuilder.() -> Unit): Rich = RichBuilder().apply(block).build()

/** A presence dot (`.pdot`): green online, accent in a run, grey offline, dark unknown. */
object Dot {
    val ON = Ct.GREEN
    val RUN = Ct.ACCENT
    val OFF = Ct.T_55
    val UNKNOWN = 0xFF3A3A3A.toInt()
}

// ------------------------------------------------------------------ buttons

/** `.btn` variants. */
class Btn(
    val label: String,
    val icon: String? = null,
    val style: Int = DEFAULT,
    val small: Boolean = false,
    val disabled: Boolean = false,
    val tip: String? = null,
    val wide: Boolean = false,
    val onClick: () -> Unit,
) {
    fun height() = if (style == ICON) 22 else if (small) 20 else 24

    fun width(m: KitMeasure): Int {
        if (style == ICON) return 22
        val size = if (small) S.SMALL else S.BODY
        val tw = if (label.isEmpty()) 0 else m.width(label, size, true)
        val iw = if (icon != null) 12 + (if (tw > 0) 5 else 0) else 0
        return (if (small) 9 else 11) * 2 + tw + iw
    }

    companion object {
        const val DEFAULT = 0
        const val ACCENT = 1
        const val GHOST = 2
        const val DANGER = 3
        const val DANGER_SOLID = 4
        const val OK = 5
        const val ICON = 6
    }
}

private fun fade(color: Int, on: Boolean): Int = if (on) color else ((color ushr 24) * 35 / 100 shl 24) or (color and 0xFFFFFF)

/** Paints [b] at (x, y) with width [w] (its own width when -1); returns the width. */
fun KitCanvas.button(b: Btn, x: Int, y: Int, w: Int = -1): Int {
    val bw = if (w >= 0) w else b.width(this)
    val bh = b.height()
    val hover = !b.disabled && hovered(x, y, x + bw, y + bh)
    val on = !b.disabled
    var fg = Ct.T_EE
    when (b.style) {
        Btn.ACCENT -> rect(x, y, x + bw, y + bh, fade(if (hover) Ct.ACCENT else Ct.ACCENT_DIM, on), Corner.SMALL)
        Btn.OK -> {
            rect(x, y, x + bw, y + bh, fade(if (hover) 0xFF267543.toInt() else 0xFF1F5F36.toInt(), on), Corner.SMALL)
            fg = 0xFFD6FFE2.toInt()
        }
        Btn.GHOST, Btn.DANGER -> {
            val edge = if (hover) Ct.T_55 else 0xFF3A3A3A.toInt()
            rect(x, y, x + bw, y + bh, fade(edge, on), Corner.SMALL)
            rect(x + 1, y + 1, x + bw - 1, y + bh - 1, 0xFF1A1A1A.toInt(), Corner.SMALL)
            fg = if (b.style == Btn.DANGER) 0xFFFF8A8A.toInt() else if (hover) Ct.T_EE else Ct.T_AA
        }
        Btn.DANGER_SOLID -> {
            rect(x, y, x + bw, y + bh, fade(if (hover) 0xFFC53A3A.toInt() else 0xFFB03030.toInt(), on), Corner.SMALL)
            fg = Ct.WHITE
        }
        Btn.ICON -> {
            if (hover) rect(x, y, x + bw, y + bh, Ct.RULE, Corner.SMALL)
            fg = if (hover) Ct.T_EE else Ct.T_88
        }
        else -> rect(x, y, x + bw, y + bh, fade(if (hover) 0xFF353535.toInt() else 0xE0242424.toInt(), on), Corner.SMALL)
    }
    fg = fade(fg, on)
    val size = if (b.small) S.SMALL else S.BODY
    val tw = if (b.label.isEmpty()) 0 else width(b.label, size, true)
    val iconSize = if (b.style == Btn.ICON) 14 else 12
    val content = (if (b.icon != null) iconSize + (if (tw > 0) 5 else 0) else 0) + tw
    var cx = x + (bw - content) / 2
    if (b.icon != null) {
        icon(b.icon, cx, y + (bh - iconSize) / 2, iconSize, fg)
        cx += iconSize + (if (tw > 0) 5 else 0)
    }
    if (tw > 0) text(b.label, cx, y, y + bh, fg, size, true)
    if (b.tip != null) tooltip(x, y, x + bw, y + bh, b.tip.split('\n'), "btn:${b.label}:${b.tip}")
    if (on) onClick(x, y, x + bw, y + bh, b.onClick)
    return bw
}

class ButtonNode(val b: Btn) : Node(), Inline {
    override val span: Int get() = 1
    override fun inlineWidth(m: KitMeasure): Int = b.width(m)
    override fun measure(m: KitMeasure, w: Int): Int = b.height() + if (b.wide) 6 else 0
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (b.wide) c.button(b, x, y + 6, w) else c.button(b, x, y)
    }
}

/** A row of inline nodes (buttons, chips) with no layout beyond left to right. */
fun buttons(vararg b: Btn?): List<Node> = b.filterNotNull().map { ButtonNode(it) }

// ------------------------------------------------------------------ small drawing helpers

fun KitCanvas.dot(color: Int, x: Int, cy: Int) {
    rect(x - 1, cy - 4, x + 7, cy + 4, Ct.CARD, Corner.PILL)
    rect(x, cy - 3, x + 6, cy + 3, color, Corner.PILL)
}

/** A verification badge (`.ck`): icon + optional label; returns the width. */
fun KitCanvas.check(state: String, x: Int, cy: Int, label: String?, tip: List<String>?): Int {
    val st = Checks.of(state)
    icon(st.icon, x, cy - 5, 10, st.color)
    var w = 10
    if (label != null) {
        text(label, x + 13, cy - 7, cy + 7, st.color, S.CAPTION)
        w += 3 + width(label, S.CAPTION)
    }
    if (tip != null) tooltip(x, cy - 7, x + w, cy + 7, tip, "ck:$state:$label:${tip.firstOrNull()}")
    return w
}

fun KitMeasure.checkWidth(label: String?): Int = 10 + if (label != null) 3 + width(label, S.CAPTION) else 0

/** The verification states and their words (mock `CHECK`). */
object Checks {
    class State(val id: String, val icon: String, val label: String, val text: String, val color: Int)

    val VERIFIED = State("verified", "ct_check", "Confirmed", "Hypixel's crystal counters went up by one run around this time. That confirms the run happened; what dropped can't be checked.", Ct.GREEN)
    val PENDING = State("pending", "ct_hourglass", "Pending", "Waiting for Hypixel's API to show this run (often a few minutes, longer when Hypixel is slow). It counts meanwhile.", Ct.T_AA)
    val FLAGGED = State("flagged", "ct_flag", "Flagged", "Hypixel's numbers do not match this run. It still counts unless an owner or manager voids it.", Ct.GOLD)
    val VOIDED = State("voided", "ct_ban", "Voided", "Removed by an owner or manager. It does not count and does not use up one of the runs.", Ct.RED)
    val QUEUED = State("queued", "ct_upload", "Not sent yet", "Saved on this computer while Soul is offline. Sent when it reconnects.", Ct.ACCENT_LIGHT)
    val UNCONFIRMED = State("unconfirmed", "ct_question", "Not confirmed by Hypixel", "The contest ended before Hypixel's API showed this run. Results are final at once, so it counts as it is.", Ct.UNCONF)
    private val ALL = listOf(VERIFIED, PENDING, FLAGGED, VOIDED, QUEUED, UNCONFIRMED)

    /** An unknown state from a newer server reads as Pending. */
    fun of(id: String): State = ALL.firstOrNull { it.id == id } ?: PENDING

    fun tip(s: State) = listOf(s.label, s.text)

    /** Why a run is pending or flagged, in the player's words (contract reasons; unknown reasons say nothing). */
    fun reason(reason: String?): String? = when (reason) {
        "waiting" -> "Hypixel's counters have not caught up yet."
        "api_off" -> "This player's profile hides mining data in Hypixel's API, so the run can't be checked."
        "api_error" -> "Hypixel couldn't be reached to check it."
        "no_baseline" -> "There is no counter reading from before this run, so it can never be checked."
        "profile_not_found" -> "The locked SkyBlock profile isn't among the player's profiles."
        "not_in_api" -> "Hypixel's counters still show fewer runs 30 min after it."
        "too_fast" -> "Finished in under a minute, or less than a minute after the previous run."
        "duplicate_loot" -> "Exactly the same loot as the previous run, within 10 minutes."
        "implausible_loot" -> "An item count or powder amount far above what one run gives."
        "implausible_value" -> "Worth more than 100M coins in one run."
        else -> null
    }
}

// ------------------------------------------------------------------ banners

/** `.banner`: a colored left edge, icon or face, wrapped text, buttons at the right. */
class BannerNode(
    val kind: String,
    val text: Rich,
    val icon: String? = null,
    val faceUuid: String? = null,
    val buttons: List<Btn> = emptyList(),
    val maxW: Int = Int.MAX_VALUE,
) : Node() {
    private fun colors(): IntArray = when (kind) {
        "warn" -> intArrayOf(0xFF2A2214.toInt(), Ct.GOLD, Ct.GOLD)
        "err" -> intArrayOf(0xFF2A1616.toInt(), Ct.RED, Ct.RED)
        "ok" -> intArrayOf(0xFF16261B.toInt(), Ct.GREEN, Ct.GREEN)
        "cxl" -> intArrayOf(0xFF231A1A.toInt(), 0xFFA05050.toInt(), 0xFFEE8888.toInt())
        "req" -> intArrayOf(0xFF18202E.toInt(), Ct.ACCENT_LIGHT, Ct.ACCENT_LIGHT)
        else -> intArrayOf(Ct.CARD, Ct.T_88, Ct.T_88)
    }

    private fun lead() = if (icon != null) 14 + 8 else if (faceUuid != null) 18 + 8 else 0
    private fun btnW(m: KitMeasure) = buttons.sumOf { it.width(m) + 8 }
    private fun textW(m: KitMeasure, w: Int) = minOf(w, maxW) - 20 - lead() - btnW(m)

    override fun measure(m: KitMeasure, w: Int): Int {
        val lines = text.wrap(m, S.SMALL, textW(m, w)).size
        return max(12 + lines * 13, if (buttons.isNotEmpty()) 12 + 20 else 0)
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val bw = minOf(w, maxW)
        val (bg, edge, ic) = colors().let { Triple(it[0], it[1], it[2]) }
        c.rect(x, y, x + bw, y + h, bg, Corner.MEDIUM)
        c.rect(x, y + 3, x + 2, y + h - 3, edge, Corner.SQUARE)
        var tx = x + 10
        if (icon != null) {
            c.icon(icon, tx, y + (h - 14) / 2, 14, ic)
            tx += 22
        } else if (faceUuid != null) {
            c.face(faceUuid, tx, y + (h - 18) / 2, 18)
            tx += 26
        }
        val lines = text.wrap(c, S.SMALL, textW(c, w))
        var ly = y + (h - lines.size * 13) / 2
        for (l in lines) {
            l.draw(c, tx, ly, ly + 13, S.SMALL, Ct.T_CC)
            ly += 13
        }
        var bx = x + bw - 10
        for (b in buttons.asReversed()) {
            bx -= b.width(c)
            c.button(b, bx, y + (h - b.height()) / 2)
            bx -= 8
        }
    }
}

/** `.note` under the add-friend row: a thin strip with a colored edge and icon. */
class NoteStripNode(val kind: String, val icon: String, val text: String, val buttons: List<Btn> = emptyList()) : Node() {
    private fun color() = when (kind) {
        "ok" -> Ct.GREEN
        "warn" -> Ct.GOLD
        "err" -> Ct.RED
        "info" -> Ct.ACCENT_LIGHT
        else -> Ct.T_88
    }

    override fun measure(m: KitMeasure, w: Int): Int = 24

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val nw = minOf(w, 560)
        c.rect(x, y, x + nw, y + h, Ct.CARD, Corner.SMALL)
        c.rect(x, y + 2, x + 2, y + h - 2, if (kind == "info") Ct.ACCENT else color(), Corner.SQUARE)
        c.icon(icon, x + 9, y + 5, 14, color())
        var right = x + nw - 9
        for (b in buttons.asReversed()) {
            right -= b.width(c)
            c.button(b, right, y + 2)
            right -= 6
        }
        Rich.of(text).ellipsize(c, S.SMALL, right - (x + 30)).draw(c, x + 30, y, y + h, S.SMALL, Ct.T_CC)
    }
}

// ------------------------------------------------------------------ list rows

/**
 * The mock's list rows (`.fr`, `.li`, `.lp`, `.inv`): face (+ presence dot), a bold name with chips,
 * a dim line under it, right-hand nodes; hover background and a bottom rule where the mock has one.
 */
class ListRowNode(
    val title: Rich,
    val sub: Rich? = null,
    val faceUuid: String? = null,
    val faceSize: Int = 20,
    val icon: String? = null,
    val dot: Int? = null,
    val chips: List<Chip> = emptyList(),
    val right: List<Node> = emptyList(),
    val minH: Int = 30,
    val rule: Boolean = false,
    val hover: Boolean = true,
    val dim: Boolean = false,
    val titleColor: Int = Ct.T_EE,
    val tipKey: String = title.plain,
    val onClick: (() -> Unit)? = null,
) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = max(minH, if (sub != null) 4 + 13 + 12 + 4 else 0)

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val hov = c.hovered(x - 4, y, x + w + 4, y + h)
        if (hover && hov) c.rect(x - 4, y, x + w + 4, y + h, Ct.HOVER, Corner.SMALL)
        if (rule) c.rect(x, y + h - 1, x + w, y + h, Ct.LINE, Corner.SQUARE)
        var lx = x
        val cy = y + h / 2
        if (faceUuid != null) {
            c.face(faceUuid, lx, cy - faceSize / 2, faceSize)
            lx += faceSize
            if (dot != null) {
                c.dot(dot, lx - 4, cy + faceSize / 2 - 4)
                lx += 2
            }
            lx += 8
        } else if (icon != null) {
            c.icon(icon, lx, cy - 7, 14, Ct.T_88)
            lx += 22
        }
        // right side first: its width bounds the text
        var rx = x + w
        for (n in right.asReversed()) {
            val nw = (n as? Inline)?.inlineWidth(c) ?: 0
            rx -= nw
            val nh = n.height(c, nw)
            n.paint(c, rx, cy - nh / 2, nw, nh)
            rx -= 8
        }
        val room = rx - lx
        val tCol = if (dim) Ct.T_88 else titleColor
        if (sub != null) {
            val t = title.ellipsize(c, S.BODY, room - chipsW(c), true)
            val ex = t.draw(c, lx, cy - 13, cy, S.BODY, tCol, true)
            paintChips(c, ex, cy - 6)
            sub.ellipsize(c, S.CAPTION, room).draw(c, lx, cy, cy + 12, S.CAPTION, Ct.T_88)
        } else {
            val t = title.ellipsize(c, S.BODY, room - chipsW(c), true)
            val ex = t.draw(c, lx, cy - 7, cy + 7, S.BODY, tCol, true)
            paintChips(c, ex, cy)
        }
        if (onClick != null) c.onClick(x, y, rx, y + h, onClick)
    }

    private fun chipsW(m: KitMeasure) = chips.sumOf { it.width(m) + S.CHIP_GAP }

    private fun paintChips(c: KitCanvas, x0: Int, cy: Int) {
        var cx = x0
        for (ch in chips) cx += S.CHIP_GAP + ch.paint(c, cx + S.CHIP_GAP, cy, "$tipKey:${ch.text}")
    }
}

/** A small uppercase group label (`.gl`, `.gh`) with an optional link at the right. */
class GroupLabelNode(val text: String, val link: String? = null, val onLink: (() -> Unit)? = null, val padTop: Int = 6) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = padTop + 12
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val up = text.uppercase()
        val tw = c.trackedWidth(up, S.LABEL, S.CHIP_TRACKING)
        c.tracked(up, x + 2 + tw / 2, y + padTop + 6, Ct.T_55, S.LABEL, S.CHIP_TRACKING)
        if (link != null && onLink != null) {
            val lw = c.width(link, S.CAPTION)
            val lx = x + w - lw
            val hov = c.hovered(lx, y + padTop, lx + lw, y + h)
            c.text(link, lx, y + padTop, y + h, if (hov) Ct.WHITE else Ct.ACCENT_LIGHT, S.CAPTION)
            c.onClick(lx, y + padTop, lx + lw, y + h, onLink)
        }
    }
}

/** A plain wrapped paragraph in a given size/color (`.lead`, `.pl2`, `.none`, `.foot`). */
class ParaNode(val text: Rich, val size: Float = S.SMALL, val color: Int = Ct.T_AA, val lineH: Int = 14, val maxW: Int = Int.MAX_VALUE, val padY: Int = 0) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = padY * 2 + text.wrap(m, size, minOf(w, maxW)).size * lineH
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var ly = y + padY
        for (l in text.wrap(c, size, minOf(w, maxW))) {
            l.draw(c, x, ly, ly + lineH, size, color)
            ly += lineH
        }
    }
}

/** Inline nodes left to right at their natural widths (a `flex` row of buttons/chips), wrapping not needed. */
class FlowNode(val items: List<Node>, val gap: Int = 6, val alignRight: Boolean = false) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = items.sumOf { (it as? Inline)?.inlineWidth(m) ?: 0 } + gap * max(0, items.size - 1)
    override fun measure(m: KitMeasure, w: Int): Int = items.maxOfOrNull { it.height(m, (it as? Inline)?.inlineWidth(m) ?: w) } ?: 0
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var cx = if (alignRight) x + w - inlineWidth(c) else x
        for (n in items) {
            val nw = (n as? Inline)?.inlineWidth(c) ?: 0
            val nh = n.height(c, nw)
            n.paint(c, cx, y + (h - nh) / 2, nw, nh)
            cx += nw + gap
        }
    }
}

/** Inline text (a `.faint.small` aside, a status word). */
class InlineTextNode(val text: Rich, val size: Float = S.CAPTION, val color: Int = Ct.T_55, val bold: Boolean = false, val tip: List<String>? = null) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = text.width(m, size, bold)
    override fun measure(m: KitMeasure, w: Int): Int = 14
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        text.draw(c, x, y, y + h, size, color, bold)
        if (tip != null) c.tooltip(x, y, x + inlineWidth(c), y + h, tip, "it:${text.plain}")
    }
}

/** A chip as an inline node. */
class ChipNode(val chip: Chip) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = chip.width(m)
    override fun measure(m: KitMeasure, w: Int): Int = S.CHIP_H
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        chip.paint(c, x, y + h / 2)
    }
}

/** `.sw`: the 30 x 16 switch row (`.optrow`). */
class SwitchRowNode(val title: String, val sub: String, val on: Boolean, val onClick: () -> Unit) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 2 + 26 + 8 + 4
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val y1 = y + 2 + 26
        c.rect(x, y1 + 8 - 1, x + w, y1 + 8, Ct.RULE, Corner.SQUARE)
        c.text(title, x, y + 2, y + 15, Ct.T_EE, S.BODY, true)
        c.text(sub, x, y + 15, y + 27, Ct.T_88, S.CAPTION)
        val sx = x + w - 30
        val sy = y + 2 + 5
        c.rect(sx, sy, sx + 30, sy + 16, if (on) Ct.ACCENT else 0xFF333333.toInt(), Corner.PILL)
        val kx = if (on) sx + 16 else sx + 2
        c.rect(kx, sy + 2, kx + 12, sy + 14, if (on) Ct.WHITE else Ct.T_AA, Corner.PILL)
        c.onClick(x, y, x + w, y1 + 8, onClick)
    }
}

/** `.rule`: an icon and wrapped text (rules lists). */
class IconLineNode(val icon: String, val text: Rich, val iconColor: Int = Ct.ACCENT_LIGHT, val color: Int = Ct.T_BB) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 6 + text.wrap(m, S.SMALL, w - 20).size * 13
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.icon(icon, x, y + 4, 12, iconColor)
        var ly = y + 3
        for (l in text.wrap(c, S.SMALL, w - 20)) {
            l.draw(c, x + 20, ly, ly + 13, S.SMALL, color)
            ly += 13
        }
    }
}

/** `.mts` tiles: equal boxes with a small label and a value ([big] = `.mts.big`). */
class TilesRowNode(val tiles: List<Pair<String, Rich>>, val big: Boolean = false, val tips: Map<Int, List<String>> = emptyMap()) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = 0
    override fun measure(m: KitMeasure, w: Int): Int = if (big) 7 + 11 + 19 + 8 else 3 + 11 + 14 + 4

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val n = tiles.size
        if (n == 0) return
        val gap = 6
        val tw = (w - gap * (n - 1)) / n
        for ((i, t) in tiles.withIndex()) {
            val tx = x + i * (tw + gap)
            c.rect(tx, y, tx + tw, y + h, if (big) Ct.CARD else Ct.LINE, if (big) Corner.MEDIUM else Corner.SMALL)
            val px = if (big) 10 else 7
            val top = y + if (big) 7 else 3
            c.text(t.first, tx + px, top, top + 11, Ct.T_77, S.LABEL)
            val vs = if (big) S.px(15f) else S.BODY
            t.second.ellipsize(c, vs, tw - px * 2, true).draw(c, tx + px, top + 11, top + 11 + (if (big) 19 else 14), vs, Ct.T_EE, true)
            tips[i]?.let { c.tooltip(tx, y, tx + tw, y + h, it, "tile:${t.first}") }
        }
    }
}

/** A fraction bar (`.bar`) of a given width class. */
fun KitCanvas.fbar(x: Int, y: Int, w: Int, f: Float, color: Int) {
    rect(x, y, x + w, y + 4, Ct.RULE, Corner.PILL)
    val fw = (w * f.coerceIn(0f, 1f)).toInt()
    if (fw > 0) rect(x, y, x + max(fw, 4), y + 4, color, Corner.PILL)
}

/** The projection bar: dashed violet (`repeating-linear-gradient` 6 px on, 2 px darker). */
fun KitCanvas.projBar(x: Int, y: Int, w: Int, f: Float) {
    rect(x, y, x + w, y + 4, Ct.RULE, Corner.PILL)
    val fw = (w * f.coerceIn(0f, 1f)).toInt()
    var dx = 0
    while (dx < fw) {
        rect(x + dx, y, x + minOf(fw, dx + 6), y + 4, 0xFF8B5CF6.toInt(), Corner.SQUARE)
        if (dx + 6 < fw) rect(x + dx + 6, y, x + minOf(fw, dx + 8), y + 4, 0xFF6D48C9.toInt(), Corner.SQUARE)
        dx += 8
    }
}

/** A card shell (`.c`) around arbitrary children, title optional (uses the kit's CardNode look). */
fun cardOf(
    title: String?,
    body: List<Node>,
    sub: Rich? = null,
    meta: Node? = null,
    tools: List<Node> = emptyList(),
    padX: Int = S.CARD_PAD_X,
    padTop: Int = S.CARD_PAD_TOP,
    padBottom: Int = S.CARD_PAD_BOTTOM,
    gap: Int = 0,
    chips: List<Chip> = emptyList(),
): Node = com.soulreturns.features.profileviewer.gui.kit.CardNode(
    title = title, body = body, sub = sub, meta = meta, tools = tools, bodyGap = gap,
    padX = padX, padTop = padTop, padBottom = padBottom, chips = chips,
)

/** Two columns side by side at the given widths; each column stacks with [gap]. */
class ColsNode(val cols: List<List<Node>>, val widths: (Int) -> IntArray, val gap: Int = 8, val stackBelow: Int = 0) : Node() {
    private fun stacked(w: Int) = stackBelow > 0 && w < stackBelow
    private fun colH(m: KitMeasure, col: List<Node>, w: Int) = col.sumOf { it.height(m, w) } + gap * max(0, col.size - 1)

    override fun measure(m: KitMeasure, w: Int): Int {
        if (stacked(w)) return cols.filter { it.isNotEmpty() }.sumOf { colH(m, it, w) } + gap * max(0, cols.count { it.isNotEmpty() } - 1)
        val ws = widths(w)
        return cols.indices.maxOf { colH(m, cols[it], ws[it]) }
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (stacked(w)) {
            var cy = y
            for (col in cols) {
                if (col.isEmpty()) continue
                for (n in col) {
                    val nh = n.height(c, w)
                    if (cy + nh >= c.viewTop && cy <= c.viewBottom) n.paint(c, x, cy, w, nh)
                    cy += nh + gap
                }
            }
            return
        }
        val ws = widths(w)
        var cx = x
        for ((i, col) in cols.withIndex()) {
            var cy = y
            for (n in col) {
                val nh = n.height(c, ws[i])
                if (cy + nh >= c.viewTop && cy <= c.viewBottom) n.paint(c, cx, cy, ws[i], nh)
                cy += nh + gap
            }
            cx += ws[i] + gap
        }
    }
}
