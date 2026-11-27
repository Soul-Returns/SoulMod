package com.soulreturns.features.contests.gui

import com.soulreturns.config.gui.Theme
import com.soulreturns.config.gui.render.SoulRenderPipelines
import com.soulreturns.features.contests.ActionResult
import com.soulreturns.features.contests.gui.ContestsNavState as N
import com.soulreturns.features.contests.model.ContestEdit
import com.soulreturns.features.contests.model.GoalInput
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.profileviewer.ProfileViewerService
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.KitScreen
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.StackNode
import com.soulreturns.features.profileviewer.gui.kit.ViewState
import com.soulreturns.features.contests.gui.ContestsLayout as L
import com.soulreturns.ui.SoulUi
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.ChatScreen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Friends & Contests (`mocks/contests`): a rail (Contests, Friends, History, How results are checked),
 * a section head with crumbs, banners pinned above a scrolled body, an optional action bar, popovers
 * and confirm/prompt modals. Pages come from [ContestsPages]; data from the [ContestsSource].
 */
class ContestsScreen(override val src: ContestsSource = StoreContestsSource, start: N = N()) : KitScreen(Component.literal("Friends & Contests")), ContestsHost {
    override val nav: N = start
    override val state: ViewState get() = viewState
    override val nowMs: Long get() = now
    override var mainW: Int = 0
        private set

    private var popover: String? = null
    private val anchors = HashMap<String, IntArray>()
    private val scrolls = HashMap<String, ScrollState>()
    private val railScroll = ScrollState()
    private var lastStale = false

    override fun init() {
        super.init()
        refetch()
    }

    /** Loads what the current page shows (screen open, page change, reconnect). */
    private fun refetch() {
        src.refreshContests()
        if (src.types().isEmpty()) src.loadTypes()
        val p = nav.page
        if (p == N.FRIENDS || p == N.CREATE || p == N.HOME) src.refreshFriends()
        if (p == N.HISTORY) src.loadHistory(true)
        val id = nav.contestId
        if (id != null && (p == N.CONTEST || p == N.PAST || p == N.PLAYER || p == N.CHECKS)) src.openContest(id)
        val pl = nav.player
        if (id != null && pl != null && p == N.PLAYER) src.loadRuns(id, pl)
    }

    // ------------------------------------------------------------------ host

    override fun go(page: String, contestId: Long?, player: String?, fromHistory: Boolean) {
        popover = null
        viewState.blurFields()
        nav.page = page
        nav.contestId = contestId
        nav.player = player
        nav.fromHistory = fromHistory
        if (page != N.FRIENDS) nav.addResult = null
        refetch()
    }

    override fun pop(id: String) {
        popover = if (popover == id) null else id
    }

    override fun anchor(id: String, x0: Int, y0: Int, x1: Int, y1: Int) {
        anchors[id] = intArrayOf(x0, y0, x1, y1)
    }

    override fun popOpen(id: String): Boolean = popover == id

    override fun confirm(title: String, message: String, label: String, action: () -> Unit) {
        popover = null
        showConfirm(title, message, label) {
            closeAlert()
            action()
        }
    }

    override fun prompt(title: String, message: String, label: String, initial: String, action: (String) -> Unit) {
        popover = null
        showPrompt(title, message, label, initial = initial) { text ->
            closeAlert()
            action(text.trim())
        }
    }

    override fun act(call: (Done) -> Unit, then: (ActionResult) -> Unit) {
        popover = null
        call { r ->
            if (r.ok) then(r)
            else if (Minecraft.getInstance().gui.screen() === this) showAlert("That didn't work", r.message ?: "Soul's server said no (${r.code ?: r.status}).")
        }
    }

    override fun openProfile(name: String) = ProfileViewerService.open(name)

    override fun openChat(text: String) {
        Minecraft.getInstance().setScreenAndShow(ChatScreen(text, false))
    }

    override fun kitBlocked(): Boolean = popover != null

    override fun onKitFieldFocus() {
        popover = null
    }

    override fun kitFieldIcon(key: String): String? = if (key == ContestsPages.ADD_KEY) "ct_user_plus" else null

    // ------------------------------------------------------------------ frame

    override fun extractRenderState(g: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
        beginFrame(g, mouseX, mouseY)
        val stale = src.connection().stale
        if (lastStale && !stale) refetch()
        lastStale = stale
        val s = SoulUi.factor()
        val uiW = width / s
        val uiH = height / s
        g.pose().pushMatrix()
        g.pose().scale(s, s)
        val card = L.card(uiW, uiH)
        mainW = L.mainWidth(uiW)
        anchors.clear()

        g.fill(0, 0, uiW.toInt() + 1, uiH.toInt() + 1, Theme.DIM)
        roundRect(g, card.x0, card.y0, card.x1, card.y1, Theme.PANEL, SoulRenderPipelines.ROUND_MEDIUM)
        drawRail(g, card)
        val page = try {
            ContestsPages.build(this)
        } catch (e: Throwable) {
            Page(listOf(Crumb("Contests", null)), body = listOf(ParaNode(com.soulreturns.features.profileviewer.gui.kit.Rich.of("This page couldn't be drawn: ${e.message}"), color = Ct.T_66)), key = "error")
        }
        drawMain(g, card, page)
        drawHeader(g, card)
        if (popover != null) {
            region(0, 0, uiW.toInt(), uiH.toInt()) { popover = null }
            drawPopover(g, card)
        }
        drawTooltip(g, uiW.toInt(), uiH.toInt())
        drawAlertModal(g, uiW.toInt(), uiH.toInt(), mx, my)
        g.pose().popMatrix()
        endFrame(g, mouseX, mouseY)
    }

    private fun drawHeader(g: GuiGraphicsExtractor, card: L.Card) {
        val y0 = card.y0
        val y1 = y0 + L.HEAD_H
        roundRect(g, card.x0, y0, card.x1, y1, Theme.TOP_NAV, SoulRenderPipelines.ROUND_TOP)
        g.fillGradient(card.x0, y1, card.x1, y1 + 6, 0x50000000, 0x00000000)
        drawIcon(g, "ct_trophy", card.x0 + 16, y0 + 17, 14, Theme.ACCENT_LIGHT)
        txt("Friends & Contests", card.x0 + 38, y0, y1, Theme.TEXT, S.TITLE, true)
        // Close, then the connection pill.
        val cs = 26
        val top = y0 + (L.HEAD_H - cs) / 2
        var rx = card.x1 - 12 - cs
        val hc = hit(rx, top, rx + cs, top + cs)
        rect(rx, top, rx + cs, top + cs, if (hc) 0x40E05252 else 0, Corner.SMALL)
        txt("×", rx + 8, top, top + cs, if (hc) 0xFFFF8A8A.toInt() else Theme.TEXT_DIM, S.px(14f), true)
        if (hc) tooltip(rx, top, rx + cs, top + cs, listOf("Close (Esc)"))
        region(rx, top, rx + cs, top + cs) { onClose() }
        val c = src.connection()
        val (label, color) = when (c.state) {
            Connection.RECONNECTING -> "Reconnecting…" to Ct.GOLD
            Connection.OFFLINE -> "Offline" to Ct.RED
            else -> "Live" to Ct.GREEN
        }
        val pw = 8 + 6 + 6 + tw(label, S.CAPTION, true) + 8
        rx -= 8 + pw
        rect(rx, top + 3, rx + pw, top + cs - 3, 0xFF1A1A1A.toInt(), Corner.PILL)
        rect(rx + 8, top + cs / 2 - 3, rx + 14, top + cs / 2 + 3, color, Corner.PILL)
        txt(label, rx + 20, top, top + cs, color, S.CAPTION, true)
        if (hit(rx, top, rx + pw, top + cs)) tooltip(rx, top, rx + pw, top + cs, listOf(when (c.state) {
            Connection.RECONNECTING -> "Live updates paused; Soul is reconnecting."
            Connection.OFFLINE -> "Soul's server can't be reached. Runs are saved and sent later."
            else -> "Connected to Soul's server: standings update live."
        }))
    }

    private class RailItem(val id: String, val icon: String, val label: String)

    private val railItems = listOf(
        RailItem(N.HOME, "ct_trophy", "Contests"),
        RailItem(N.FRIENDS, "ct_users", "Friends"),
        RailItem(N.HISTORY, "ct_history", "History"),
        RailItem(N.CHECKS, "ct_shield", "How results are checked"),
    )

    private fun drawRail(g: GuiGraphicsExtractor, card: L.Card) {
        val x0 = card.x0
        val x1 = x0 + card.railW
        val y0 = card.y0 + L.HEAD_H
        val y1 = card.y1
        roundRect(g, x0, y0, x1, y1, Theme.SIDEBAR, SoulRenderPipelines.ROUND_BL)
        val sel = N.railOf(nav.page, nav.fromHistory)
        // A held snapshot newer than the list wins: the list is only refetched on some events.
        val running = src.contests().value?.active?.any { s ->
            val held = src.snapshot(s.id).value?.takeIf { it.rev > s.rev }
            (held?.contest?.state ?: s.state) == ContestView.RUNNING
        } == true
        val requests = src.friends().value?.incoming?.size ?: 0
        val invites = src.contests().value?.invitations?.size ?: 0
        var ry = y0 + L.RAIL_PAD_Y
        val pad = L.RAIL_PAD_X
        for (it in railItems) {
            val ix0 = x0 + pad
            val ix1 = x1 - pad
            val h = L.RAIL_ITEM_H
            val on = it.id == sel
            val hover = popover == null && hit(ix0, ry, ix1, ry + h)
            if (on) rect(ix0, ry, ix1, ry + h, Theme.ACCENT, Corner.SMALL) else if (hover) rect(ix0, ry, ix1, ry + h, Theme.PANEL_HOVER, Corner.SMALL)
            val color = if (on) 0xFFFFFFFF.toInt() else if (hover) Theme.TEXT else Theme.TEXT_DIM
            val badge = when (it.id) {
                N.FRIENDS -> requests.takeIf { n -> n > 0 }?.toString()
                N.HOME -> invites.takeIf { n -> n > 0 }?.toString()
                else -> null
            }
            if (card.mini) {
                drawIcon(g, it.icon, (ix0 + ix1) / 2 - 7, ry + 6, 14, color)
                if (hover) tooltip(ix0, ry, ix1, ry + h, listOf(it.label))
                if (badge != null || it.id == N.HOME && running) rect(ix1 - 9, ry + 4, ix1 - 4, ry + 9, if (badge != null) Ct.ACCENT_LIGHT else Ct.GREEN, Corner.PILL)
            } else {
                drawIcon(g, it.icon, ix0 + 8, ry + 6, 14, color)
                val rightW = if (badge != null) tw(badge, S.CAPTION, true) + 10 else if (it.id == N.HOME && running) 8 else 0
                txt(ellipsize(it.label, ix1 - 8 - rightW - (ix0 + 31), S.BODY, weight(true)), ix0 + 31, ry, ry + h, color, S.BODY, true)
                if (badge != null) {
                    val bw = tw(badge, S.CAPTION, true) + 8
                    rect(ix1 - 6 - bw, ry + 6, ix1 - 6, ry + h - 6, if (on) 0x40FFFFFF else Ct.ACCENT_DIM, Corner.PILL)
                    txt(badge, ix1 - 6 - bw + 4, ry, ry + h, 0xFFFFFFFF.toInt(), S.CAPTION, true)
                } else if (it.id == N.HOME && running) {
                    rect(ix1 - 14, ry + h / 2 - 3, ix1 - 8, ry + h / 2 + 3, Ct.GREEN, Corner.PILL)
                    if (hit(ix1 - 18, ry, ix1, ry + h)) tooltip(ix1 - 18, ry, ix1, ry + h, listOf("A contest is running"))
                }
            }
            region(ix0, ry, ix1, ry + h) { go(it.id, null) }
            ry += h + L.RAIL_ITEM_GAP
        }
    }

    private fun drawMain(g: GuiGraphicsExtractor, card: L.Card, page: Page) {
        val x0 = card.x0 + card.railW
        val x1 = card.x1
        val y0 = card.y0 + L.HEAD_H
        val y1 = card.y1
        val bx0 = x0 + L.BODY_PAD_X
        val bx1 = x1 - L.BODY_PAD_X
        val bw = bx1 - bx0

        // Section head: crumbs left, extra nodes right.
        val hTop = y0 + L.SEC_PAD_TOP
        val hBottom = hTop + L.SEC_H
        var rx = bx1
        bodyRect[0] = x0; bodyRect[1] = hTop; bodyRect[2] = x1; bodyRect[3] = hBottom
        canvas.viewTop = hTop
        canvas.viewBottom = hBottom
        for (n in page.right.asReversed()) {
            val nw = (n as? com.soulreturns.features.profileviewer.gui.kit.Inline)?.inlineWidth(canvas) ?: 0
            val nh = n.height(canvas, nw)
            rx -= nw
            n.paint(canvas, rx, hTop + (L.SEC_H - nh) / 2, nw, nh)
            rx -= 8
        }
        var hx = bx0
        for ((i, c) in page.crumbs.withIndex()) {
            val last = i == page.crumbs.lastIndex
            val size = S.HEADING
            val room = rx - 8 - hx
            val label = ellipsize(c.label, max(20, room), size, weight(true))
            val w = tw(label, size, true)
            val act = c.onClick
            val hover = act != null && popover == null && hit(hx, hTop, hx + w, hBottom)
            txt(label, hx, hTop, hBottom, if (last) Theme.TEXT else if (hover) Theme.TEXT else Theme.TEXT_DIM, size, true)
            if (act != null) region(hx, hTop, hx + w, hBottom) { act() }
            hx += w
            if (!last) {
                txt("/", hx + 6, hTop, hBottom, Theme.TEXT_FAINT, size, false)
                hx += 6 + tw("/", size, false) + 6
            }
        }

        // Pinned banners.
        var top = hBottom + L.SEC_PAD_BOTTOM
        if (page.fixed.isNotEmpty()) {
            val fixed = StackNode(page.fixed, L.BANNER_GAP)
            val fh = fixed.height(canvas, bw)
            bodyRect[0] = x0; bodyRect[1] = top; bodyRect[2] = x1; bodyRect[3] = top + fh
            canvas.viewTop = top
            canvas.viewBottom = top + fh
            fixed.paint(canvas, bx0, top, bw, fh)
            top += fh + L.BANNER_GAP
        }

        // Action bar.
        var bottom = y1
        val bar = page.bar
        if (bar != null) {
            bottom = y1 - L.ABAR_H
            bodyRect[0] = x0; bodyRect[1] = bottom; bodyRect[2] = x1; bodyRect[3] = y1
            canvas.viewTop = bottom
            canvas.viewBottom = y1
            beginClip(g, x0, bottom, x1, y1)
            bar.paint(canvas, x0, bottom, x1 - x0, L.ABAR_H)
            endClip(g)
        }

        // Scrolled body.
        val root = StackNode(page.body, L.GAP)
        val contentH = root.height(canvas, bw)
        val st = scrolls.getOrPut(page.key) { ScrollState() }
        st.view = bottom - top
        st.content = L.BODY_PAD_TOP + contentH + L.BODY_PAD_BOTTOM
        st.clamp()
        bodyRect[0] = x0; bodyRect[1] = top; bodyRect[2] = x1; bodyRect[3] = bottom
        canvas.viewTop = top
        canvas.viewBottom = bottom
        beginClip(g, x0, top, x1, bottom)
        canvas.culling = true
        try {
            root.paint(canvas, bx0, top + L.BODY_PAD_TOP - st.pos.roundToInt(), bw, contentH)
        } finally {
            canvas.culling = false
        }
        endClip(g)
        drawScrollbar(g, mx, my, st, x0, top, x1 - 4, bottom - 4)
        for (i in 0 until 6) g.fill(x0 + i, y0, x0 + i + 1, y1, (0x40 * (6 - i) / 6) shl 24)
    }

    // ------------------------------------------------------------------ popovers

    private class MenuItem(val label: String, val icon: String? = null, val danger: Boolean = false, val small: String? = null, val disabled: Boolean = false, val tip: String? = null, val onClick: () -> Unit = {})

    private val SEP = MenuItem("-")

    private fun menuFor(id: String): List<MenuItem>? {
        val k = id.substringBefore(':')
        val arg = id.substringAfter(':', "")
        val cid = nav.contestId
        val v = cid?.let { ContestsPages.view(this, it) }
        return when (k) {
            "manage" -> if (v == null) null else manageMenu(v)
            "friend" -> {
                val f = src.friends().value?.friends?.firstOrNull { it.uuid == arg } ?: return null
                listOf(
                    MenuItem("Message (/msg)", "ct_chat") { popover = null; openChat("/msg ${f.name} ") },
                    MenuItem("Invite to a contest", "ct_trophy") { popover = null; ContestsPages.inviteFriend(this, f.uuid) },
                    SEP,
                    MenuItem("Remove friend…", "ct_close", danger = true) {
                        confirm("Remove ${f.name}?", "You stop being Soul friends: no invites, no presence. Contests you share are not affected.", "Remove") { act({ src.removeFriend(f.uuid, it) }) }
                    },
                    MenuItem("Block…", "ct_ban", danger = true) {
                        confirm("Block ${f.name}?", "They can't send you friend requests or contest invites, and don't see you online. They are not told.", "Block") { act({ src.blockPlayer(f.uuid, it) }) }
                    },
                )
            }
            "inv", "lrole" -> {
                val ownerOnly = k == "lrole" && v?.role != ContestView.OWNER
                listOf(
                    Triple(ContestView.PARTICIPANT, "Participant", "Plays"),
                    Triple(ContestView.SPECTATOR, "Spectator", "Watches live"),
                    Triple(ContestView.MANAGER, "Manager", "Plays, can manage"),
                ).map { (role, label, d) ->
                    val dis = ownerOnly && role == ContestView.MANAGER
                    MenuItem(label, small = d, disabled = dis, tip = if (dis) "Only the owner makes managers" else null) {
                        popover = null
                        if (k == "inv") nav.createInvites[arg] = role
                        else if (cid != null) act({ src.setRole(cid, arg, role, it) })
                    }
                }
            }
            "invite" -> {
                if (v == null) return null
                val members = v.members.filter { it.status != ContestView.LEFT && it.status != ContestView.REMOVED && it.status != ContestView.DECLINED }.map { it.uuid }.toSet()
                val free = src.friends().value?.friends.orEmpty().filter { it.uuid !in members }
                if (free.isEmpty()) listOf(MenuItem("All your Soul friends are in", disabled = true))
                else free.map { f ->
                    MenuItem(f.name, "ct_user_plus", small = if (f.presence.online) "online" else null) {
                        popover = null
                        act({ src.invite(v.c.id, listOf(InviteSpec(f.uuid, ContestView.PARTICIPANT)), it) })
                    }
                }
            }
            "remove" -> {
                if (v == null) return null
                v.ranked.filter { !v.isMe(it.uuid) && it.role != ContestView.OWNER }.map { r ->
                    MenuItem(r.name, "ct_ban", danger = true) { ContestsPages.removePrompt(this, v, r.uuid, r.name) }
                }.ifEmpty { listOf(MenuItem("Nobody to remove", disabled = true)) }
            }
            else -> null
        }
    }

    private fun manageMenu(v: ContestView): List<MenuItem> {
        val out = ArrayList<MenuItem>()
        val id = v.c.id
        val t = v.type
        if (v.lobby) {
            if (v.can("edit")) {
                out += MenuItem("Change the goal…", "ct_edit") {
                    prompt("Change the goal", "${ContestsFormat.unitsTitle(t)} each (now ${v.goal}).", "Save", "${v.goal}") { text ->
                        val n = text.toIntOrNull()
                        if (n == null || n <= 0) showAlert("That isn't a number", "Enter how many ${t.unit.plural} each player does.")
                        else act({ src.edit(id, ContestEdit(goal = GoalInput("units", n)), it) })
                    }
                }
                out += MenuItem(if (v.c.endsAt != null) "Change the deadline…" else "Set a deadline…", "ct_clock") {
                    prompt("Deadline", "A time like 23:30 (24 h clock), or \"none\" for no deadline.", "Save", v.c.endsAt?.let { ContestsFormat.clock(it) } ?: "") { text ->
                        if (text.equals("none", true)) act({ src.edit(id, ContestEdit(clearEndsAt = true), it) })
                        else {
                            val at = ContestsPages.parseTime(text, max(now, v.c.startsAt ?: 0L))
                            if (at == null) showAlert("That isn't a time", "Enter it like 23:30.")
                            else act({ src.edit(id, ContestEdit(endsAt = at), it) })
                        }
                    }
                }
                out += MenuItem(if (v.c.allowLateJoin) "Turn off joining late" else "Allow joining late", "ct_user_plus") {
                    popover = null
                    act({ src.edit(id, ContestEdit(allowLateJoin = !v.c.allowLateJoin), it) })
                }
            }
            if (v.can("invite")) out += MenuItem("Invite friends", "ct_user_plus") { popover = "invite" }
        } else {
            val flagged = v.ranked.filter { it.checks.flagged > 0 }
            if (flagged.isNotEmpty()) out += MenuItem("Review flagged ${t.unit.plural} (${flagged.sumOf { it.checks.flagged }})", "ct_flag") {
                go(N.PLAYER, id, flagged.maxBy { it.checks.flagged }.uuid)
            }
            if (v.can("invite")) out += MenuItem("Invite more people", "ct_user_plus") { popover = "invite" }
        }
        val danger = ArrayList<MenuItem>()
        if (v.running && v.can("disqualify")) danger += MenuItem("Remove a player…", "ct_ban", danger = true) { popover = "remove" }
        if (v.running && v.can("end")) danger += MenuItem("End contest now…", "ct_stop", danger = true) { ContestsPages.endConfirm(this, v) }
        if (v.can("cancel")) danger += MenuItem("Cancel contest…", "ct_close", danger = true) { ContestsPages.cancelPrompt(this, v) }
        if (out.isNotEmpty() && danger.isNotEmpty()) out += SEP
        return out + danger
    }

    private fun drawPopover(g: GuiGraphicsExtractor, card: L.Card) {
        val id = popover ?: return
        // Popovers opened from a menu ("invite", "remove") reuse the menu's anchor.
        val a = anchors[id] ?: anchors["manage"] ?: anchors["invite"] ?: return
        if (id == "rules") {
            val v = nav.contestId?.let { ContestsPages.view(this, it) } ?: return
            val w = L.RULES_W
            val body = StackNode(ContestsPages.rules(v), 4)
            val h = body.height(canvas, w - 20) + 16
            val (x, y) = place(a, w, h, card)
            panel(x, y, w, h)
            bodyRect[0] = x; bodyRect[1] = y; bodyRect[2] = x + w; bodyRect[3] = y + h
            canvas.viewTop = y
            canvas.viewBottom = y + h
            body.paint(canvas, x + 10, y + 8, w - 20, h - 16)
            region(x, y, x + w, y + h) {}
            return
        }
        val items = menuFor(id) ?: run { popover = null; return }
        val w = L.MENU_W
        val h = 8 + items.sumOf { if (it === SEP) 7 else 22 }
        val (x, y) = place(a, w, h, card)
        panel(x, y, w, h)
        region(x, y, x + w, y + h) {}
        var iy = y + 4
        for (it in items) {
            if (it === SEP) {
                g.fill(x + 6, iy + 3, x + w - 6, iy + 4, Ct.RULE)
                iy += 7
                continue
            }
            val hover = !it.disabled && hit(x + 4, iy, x + w - 4, iy + 22)
            if (hover) rect(x + 4, iy, x + w - 4, iy + 22, 0xFF1E2A40.toInt(), Corner.SMALL)
            val fg = if (it.disabled) Ct.T_55 else if (it.danger) 0xFFFF8A8A.toInt() else Ct.T_DD
            var tx = x + 11
            if (it.icon != null) {
                drawIcon(g, it.icon, tx, iy + 5, 12, if (it.disabled) Ct.T_55 else if (it.danger) Ct.RED else Ct.T_88)
                tx += 20
            }
            val sw = it.small?.let { s -> tw(s, S.TINY, false) + 8 } ?: 0
            txt(ellipsize(it.label, x + w - 11 - sw - tx, S.BODY, weight(false)), tx, iy, iy + 22, fg, S.BODY)
            it.small?.let { s -> txt(s, x + w - 11 - tw(s, S.TINY, false), iy, iy + 22, Ct.T_77, S.TINY) }
            if (it.tip != null && hit(x + 4, iy, x + w - 4, iy + 22)) tooltip(x + 4, iy, x + w - 4, iy + 22, listOf(it.tip))
            if (!it.disabled) {
                val act = it.onClick
                region(x + 4, iy, x + w - 4, iy + 22) { act() }
            }
            iy += 22
        }
    }

    private fun panel(x: Int, y: Int, w: Int, h: Int) {
        val g = frame ?: return
        g.nextStratum()
        g.fill(x - 1, y + 2, x + w + 1, y + h + 8, 0x40000000)
        panel(g, x - 1, y - 1, x + w + 1, y + h + 1, 0xFF181818.toInt(), 0xFF333333.toInt(), radius(Corner.MEDIUM))
    }

    /** Below the anchor, right-aligned to it when it would leave the card; above it when there's no room below. */
    private fun place(a: IntArray, w: Int, h: Int, card: L.Card): Pair<Int, Int> {
        var x = a[0]
        if (x + w > card.x1 - 8) x = a[2] - w
        x = x.coerceIn(card.x0 + 8, max(card.x0 + 8, card.x1 - 8 - w))
        var y = a[3] + 4
        if (y + h > card.y1 - 8) y = max(card.y0 + 8, a[1] - 4 - h)
        return x to min(y, max(card.y0 + 8, card.y1 - 8 - h))
    }

    // ------------------------------------------------------------------ input

    override fun onEmptyClick() {
        viewState.blurFields()
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (alertModalOpen) return super.charTyped(event)
        viewState.focusedField()?.let {
            if (event.isAllowedChatCharacter()) it.charTyped(event)
            return true
        }
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        if (alertModalOpen) return super.keyPressed(event)
        val key = event.key()
        val field = viewState.focusedField()
        if (key == GLFW.GLFW_KEY_ESCAPE) {
            if (field != null || popover != null) {
                viewState.blurFields()
                popover = null
                return true
            }
            onClose()
            return true
        }
        if (field != null) {
            if ((key == GLFW.GLFW_KEY_ENTER || key == GLFW.GLFW_KEY_KP_ENTER) && field === viewState.field(ContestsPages.ADD_KEY)) {
                ContestsPages.sendFriendRequest(this)
                return true
            }
            field.keyPressed(event, minecraft?.keyboardHandler)
            return true
        }
        if (key == GLFW.GLFW_KEY_F5) {
            refetch()
            return true
        }
        return super.keyPressed(event)
    }

    override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
        if (popover != null) return true
        val s = SoulUi.factor()
        val target = scrollTargetAt((mouseX / s).roundToInt(), (mouseY / s).roundToInt())
        if (target != null) {
            target.state.scrollBy(-scrollY.toFloat() * 24f)
            return true
        }
        return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
    }

    override fun removed() {
        src.closeContest()
        super.removed()
    }

    override fun isPauseScreen(): Boolean = false

    companion object {
        private fun open(page: String, id: Long? = null) {
            val mc = Minecraft.getInstance()
            mc.execute {
                val nav = N().apply {
                    this.page = page
                    contestId = id
                }
                mc.setScreenAndShow(ContestsScreen(start = nav))
            }
        }

        fun openHome() = open(N.HOME)
        fun openFriends() = open(N.FRIENDS)
        fun openHistory() = open(N.HISTORY)
        fun openContest(id: Long) = open(N.CONTEST, id)

        /** A finished contest's results (the same contest page; over contests show the results view). */
        fun openResults(id: Long) = open(N.CONTEST, id)
    }
}
