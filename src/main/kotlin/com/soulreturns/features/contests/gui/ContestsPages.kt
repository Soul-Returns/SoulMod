package com.soulreturns.features.contests.gui

import com.soulreturns.features.contests.gui.ContestsFormat.clock
import com.soulreturns.features.contests.gui.ContestsFormat.coins
import com.soulreturns.features.contests.gui.ContestsFormat.ord
import com.soulreturns.features.contests.gui.ContestsNavState as N
import com.soulreturns.features.contests.model.ContestSnapshot
import com.soulreturns.features.contests.model.ContestSummary
import com.soulreturns.features.contests.model.ContestTypeDescriptor
import com.soulreturns.features.contests.model.CreateContestRequest
import com.soulreturns.features.contests.model.Friend
import com.soulreturns.features.contests.model.GoalInput
import com.soulreturns.features.contests.model.InviteSpec
import com.soulreturns.features.contests.model.Member
import com.soulreturns.features.contests.model.Presence
import com.soulreturns.features.profileviewer.gui.kit.Corner
import com.soulreturns.features.profileviewer.gui.kit.Inline
import com.soulreturns.features.profileviewer.gui.kit.KitCanvas
import com.soulreturns.features.profileviewer.gui.kit.KitMeasure
import com.soulreturns.features.profileviewer.gui.kit.KitStyle as S
import com.soulreturns.features.profileviewer.gui.kit.Kv
import com.soulreturns.features.profileviewer.gui.kit.KvsNode
import com.soulreturns.features.profileviewer.gui.kit.Node
import com.soulreturns.features.profileviewer.gui.kit.Rich
import com.soulreturns.features.profileviewer.gui.kit.SegNode
import com.soulreturns.features.contests.gui.ContestsLayout as L
import java.time.LocalTime
import java.time.ZonedDateTime
import kotlin.math.max

/**
 * Builds every page of the Contests screen (`mocks/contests/mock.js` page functions) from the
 * [ContestsSource] and the contest-type descriptor. Pure apart from the click lambdas.
 */
object ContestsPages {
    fun build(h: ContestsHost): Page = when (h.nav.page) {
        N.FRIENDS -> friends(h)
        N.HISTORY -> history(h)
        N.CHECKS -> checks(h)
        N.CREATE -> create(h)
        N.CONTEST, N.PAST -> contest(h)
        N.PLAYER -> player(h)
        else -> home(h)
    }

    // ------------------------------------------------------------------ shared

    private fun stale(h: ContestsHost) = h.src.connection().stale

    fun connBanner(h: ContestsHost, v: ContestView?): Node? {
        val c = h.src.connection()
        val queued = v?.queued?.size ?: 0
        val unit = v?.type?.unit?.singular ?: "run"
        return when (c.state) {
            Connection.RECONNECTING -> BannerNode("warn", rich {
                b("Live updates paused."); t(" Reconnecting to Soul's server… ")
                t(c.since?.let { "The data is from ${ContestsFormat.span(h.nowMs - it)} ago." } ?: "Showing the last data.")
            }, icon = "ct_live")
            Connection.BACK -> BannerNode("ok", rich {
                b("Back online."); t(" Reloaded at ${clock(c.since)} (live updates sent while you were away are not replayed).")
                if (queued == 0 && v != null && v.running) t("")
            }, icon = "ct_check_circle")
            Connection.OFFLINE -> BannerNode("err", rich {
                b("You're offline."); t(c.dataAt?.let { " Showing what Soul knew at ${clock(it)}." } ?: " Soul's server is unreachable.")
                if (queued > 0) t(" Your ${if (queued == 1) unit else "$queued ${v?.type?.unit?.plural ?: "runs"}"} ${if (queued == 1) "is" else "are"} saved on this computer and will be sent when Soul reconnects.")
            }, icon = "ct_offline", buttons = listOf(Btn("Try again", small = true) { h.src.retryNow() }))
            else -> null
        }
    }

    private fun presenceDot(p: Presence, inRun: Boolean = false) = if (!p.online) Dot.OFF else if (inRun) Dot.RUN else Dot.ON

    private fun where(p: Presence, now: Long): Rich = when {
        !p.online -> Rich.colored(p.lastOnlineAt?.let { "Last online ${ContestsFormat.ago(it, now)}" } ?: "Offline", Ct.T_55)
        p.areaHidden -> Rich.colored("Online · location hidden", Ct.T_55)
        p.area != null -> rich { t(p.area); p.subLocation?.let { c(" · ", Ct.T_55); t(it) } }
        else -> Rich.colored(if (p.inGame) "Online" else "Online · not in game", Ct.T_88)
    }

    private fun Btn.node() = ButtonNode(this)

    private fun labelCase(s: String) = ContestsFormat.capitalize(s)

    // ------------------------------------------------------------------ home

    private fun home(h: ContestsHost): Page {
        val body = ArrayList<Node>()
        val cs = h.src.contests()
        val all = cs.value
        val self = h.src.selfUuid
        body += GroupLabelNode("Running", padTop = 0)
        val active = all?.active.orEmpty()
        val upcoming = active.filter { it.state != ContestView.RUNNING && it.startsAt != null }
        val heroes = active - upcoming.toSet()
        if (all == null) {
            body += if (cs.loading) ParaNode(Rich.of("Loading…"), color = Ct.T_66, padY = 6) else ParaNode(Rich.of("Contests couldn't be loaded. ${cs.error?.message.orEmpty()}"), color = Ct.T_66, padY = 6)
        } else if (heroes.isEmpty()) {
            val last = all.history.firstOrNull()
            body += cardOf(null, listOf(ListRowNode(rich {
                t("No contest running.")
                if (last != null) t(" ${last.name} ${if (last.state == ContestView.CANCELED) "was canceled" else "finished ${last.endedAt?.let { ContestsFormat.date(it) } ?: ""}"}.")
            }, icon = "ct_trophy", minH = 20, hover = false, titleColor = Ct.T_AA, onClick = last?.let { l -> { h.go(N.PAST, l.id, fromHistory = true) } })), padTop = 10, padBottom = 10)
        } else {
            for (s in heroes) body += hero(h, s)
        }
        val invites = all?.invitations.orEmpty()
        val inv = if (invites.isEmpty()) listOf(ParaNode(Rich.of("No invitations."), color = Ct.T_66, padY = 6)) else invites.mapIndexed { i, s ->
            ListRowNode(
                Rich.colored(s.name, Ct.T_EE, true),
                Rich.colored("${s.invitedBy?.name ?: s.owner.name} invited you${s.invitedAt?.let { " " + ContestsFormat.ago(it, h.nowMs) } ?: ""} · ${s.typeTitle} · ${s.goal.label} · ${startsText(s)} · ${s.players} people", Ct.T_88),
                faceUuid = s.invitedBy?.uuid ?: s.owner.uuid, faceSize = 18, rule = i < invites.lastIndex, hover = false, minH = 30,
                right = listOf(
                    Btn("Join", style = Btn.ACCENT, small = true, tip = "Join as a participant") { h.act({ h.src.join(s.id, ContestView.PARTICIPANT, it) }) { h.go(N.CONTEST, s.id) } }.node(),
                    Btn("Watch", small = true, tip = "Join as a spectator: you see everything live but do not compete.") { h.act({ h.src.join(s.id, ContestView.SPECTATOR, it) }) { h.go(N.CONTEST, s.id) } }.node(),
                    Btn("Decline", style = Btn.GHOST, small = true) { h.act({ h.src.declineInvite(s.id, it) }) }.node(),
                ),
            )
        }
        val up = if (upcoming.isEmpty()) listOf(ParaNode(Rich.of("Nothing scheduled."), color = Ct.T_66, padY = 6)) else upcoming.mapIndexed { i, s ->
            ListRowNode(
                Rich.colored(s.name, Ct.T_EE, true),
                Rich.colored("${ContestsFormat.possessive(s.owner.name)} · ${s.typeTitle} · ${s.goal.label} · ${s.players} people", Ct.T_88),
                icon = "ct_clock", rule = i < upcoming.lastIndex, minH = 30, chips = listOf(Ct.ok("JOINED")),
                right = listOf(InlineTextNode(Rich.of(s.startsAt?.let { ContestsFormat.dayClock(it) } ?: ""), S.SMALL, Ct.T_CC)),
                onClick = { h.go(N.CONTEST, s.id) },
            )
        }
        val invCard = cardOf("Invitations", inv, meta = InlineTextNode(Rich.of("${invites.size}"), S.CAPTION, Ct.T_88))
        val upCard = cardOf("Upcoming", up)
        body += ColsNode(listOf(listOf(invCard), listOf(upCard)), { w -> val each = (w - L.GAP) / 2; intArrayOf(each, w - L.GAP - each) }, stackBelow = 2 * 300 + L.GAP)
        body += GroupLabelNode("Past contests", "All history", { h.go(N.HISTORY) })
        body += HistoryTableNode(all?.history.orEmpty().take(3), self) { s -> h.go(N.PAST, s.id, fromHistory = true) }
        return Page(
            crumbs = listOf(Crumb("Contests", null)),
            right = listOf(Btn("New contest", "ct_plus", Btn.ACCENT, tip = "Start a contest with your Soul friends") { h.nav.resetCreate(); h.go(N.CREATE) }.node()),
            fixed = listOfNotNull(connBanner(h, null)),
            body = body,
            key = "home",
        )
    }

    private fun startsText(s: ContestSummary) = when {
        s.state == ContestView.RUNNING -> "running since ${clock(s.startedAt)}"
        s.startsAt != null -> "starts ${ContestsFormat.dayClock(s.startsAt)}"
        else -> "starts when ${s.owner.name} presses Start"
    }

    private fun hero(h: ContestsHost, s: ContestSummary): Node {
        val m = s.me
        val line = when {
            s.state != ContestView.RUNNING -> rich { t("Lobby · ${s.players} playing · ${startsText(s)}") }
            m.role != ContestView.SPECTATOR -> rich { t("You're "); b(m.rank?.let(::ord) ?: "–", Ct.WHITE); t(" · ${m.units}/${s.goal.units ?: 0} · ${coins(m.total)}") }
            else -> s.top.firstOrNull().let { lead -> rich { t("You're watching"); if (lead != null) t(" · ${lead.name} leads with ${coins(lead.total)}") } }
        }
        val sub = "${s.typeTitle} · ${s.goal.label}" + (s.startedAt?.let { " · started ${clock(it)}" } ?: "")
        return HeroNode(s, h.src.selfUuid, line, sub) { h.go(N.CONTEST, s.id) }
    }

    // ------------------------------------------------------------------ history

    private fun history(h: ContestsHost): Page {
        val hs = h.src.history()
        val body = ArrayList<Node>()
        body += ParaNode(Rich.of("Every contest you were in, with final standings and everyone's runs. Kept on Soul's server."), S.SMALL, Ct.T_AA, 14, 640)
        val list = hs.contests.ifEmpty { h.src.contests().value?.history.orEmpty() }
        body += HistoryTableNode(list, h.src.selfUuid) { s -> h.go(N.PAST, s.id, fromHistory = true) }
        if (hs.loading) body += ParaNode(Rich.of("Loading…"), color = Ct.T_66)
        else if (!hs.exhausted && list.isNotEmpty()) body += FlowNode(listOf(Btn("Load older", small = true) { h.src.loadHistory(false) }.node()))
        return Page(listOf(Crumb("History", null)), body = body, key = "history")
    }

    // ------------------------------------------------------------------ friends

    private fun addNote(h: ContestsHost): Node? {
        val r = h.nav.addResult ?: return null
        val n = h.nav.addName
        return when (r) {
            "sent" -> NoteStripNode("ok", "ct_check", "Request sent to $n. They see it in chat and under Friends the next time they play.")
            "accepted" -> NoteStripNode("ok", "ct_check", "$n had already asked you: you're friends now.")
            "not_a_user" -> NoteStripNode("warn", "ct_info", "$n doesn't use Soul. Soul friends both need the mod.")
            "already_friends" -> NoteStripNode("info", "ct_users", "$n is already your friend.")
            "already_requested" -> NoteStripNode("info", "ct_clock", "You already sent $n a request.")
            "you_blocked" -> NoteStripNode("warn", "ct_ban", "You blocked $n. Unblock them first (Blocked, below).")
            "not_found" -> NoteStripNode("err", "ct_search", "No Minecraft account is called \"$n\".")
            "invalid_name" -> NoteStripNode("err", "ct_search", "\"$n\" isn't a Minecraft name.")
            "self" -> NoteStripNode("info", "ct_user", "That's you.")
            "friend_limit" -> NoteStripNode("warn", "ct_info", "You have reached the friend limit (200 friends and requests).")
            "lookup_unavailable" -> NoteStripNode("warn", "ct_clock", "Couldn't check whether \"$n\" exists right now. Try again in a minute.")
            else -> NoteStripNode("err", "ct_warn", r.removePrefix("error:"))
        }
    }

    fun sendFriendRequest(h: ContestsHost) {
        val name = h.state.text(ADD_KEY).trim()
        if (name.isEmpty() || h.nav.busy) return
        h.nav.addName = name
        h.nav.busy = true
        h.src.addFriend(name) { r ->
            h.nav.busy = false
            h.nav.addResult = if (r.ok) (r.friendResult ?: "sent") else (r.code?.takeIf { it in ADD_CODES } ?: "error:${r.message ?: "That didn't work."}")
            if (r.ok) h.state.field(ADD_KEY).set("")
        }
    }

    const val ADD_KEY = "friends.add"
    private val ADD_CODES = setOf("not_a_user", "already_friends", "already_requested", "you_blocked", "not_found", "invalid_name", "self", "friend_limit", "lookup_unavailable")

    private fun friends(h: ContestsHost): Page {
        val fr = h.src.friends()
        val body = h.src.friends().value
        val down = body == null && fr.error != null || fr.stale
        val add = FlowNode(listOf(FieldNode(ADD_KEY, "Add a friend by Minecraft name", 280, disabled = down), Btn("Send request", style = Btn.ACCENT, disabled = down || h.nav.busy) { sendFriendRequest(h) }.node()))
        val nodes = ArrayList<Node>()
        nodes += add
        addNote(h)?.let { nodes += it }
        val fixed = ArrayList<Node>()
        if (down) fixed += BannerNode("err", rich {
            b("Can't reach Soul's server."); t(if (body != null) " This is your friend list from ${clock(fr.fetchedAt)}." else ""); t(" Requests and invites need the server.")
        }, icon = "ct_offline", buttons = listOf(Btn("Try again", small = true) { h.src.refreshFriends() }))
        if (body == null) {
            if (fr.loading) nodes += ParaNode(Rich.of("Loading…"), color = Ct.T_66, padY = 6)
            return Page(listOf(Crumb("Friends", null)), right = listOf(InlineTextNode(Rich.of("Soul friends, not Hypixel friends: both need Soul"))), fixed = fixed, body = nodes, key = "friends")
        }
        if (body.friends.isEmpty() && body.incoming.isEmpty() && body.outgoing.isEmpty() && !down) {
            nodes += CenterNode("ct_users", "No Soul friends yet", "Soul friends are separate from your Hypixel friends: both of you need Soul. Add someone by name; they accept in chat or here. Friends can invite each other to contests and see each other's area.")
            nodes += cardOf("What friends see", privacy(h, body.me.shareArea))
            return Page(listOf(Crumb("Friends", null)), right = listOf(InlineTextNode(Rich.of("Soul friends, not Hypixel friends: both need Soul"))), fixed = fixed, body = nodes, key = "friends")
        }
        val online = body.friends.filter { it.presence.online }
        val offline = body.friends.filter { !it.presence.online }
        val list = ArrayList<Node>()
        if (down) {
            body.friends.forEach { list += friendRow(h, it, true) }
        } else {
            list += GroupLabelNode("Online · ${online.size}", padTop = 0)
            online.forEach { list += friendRow(h, it, false) }
            list += GroupLabelNode("Offline · ${offline.size}")
            offline.forEach { list += friendRow(h, it, false) }
        }
        val right = ArrayList<Node>()
        if (!down) {
            val req = ArrayList<Node>()
            body.incoming.forEach { r ->
                req += ListRowNode(Rich.colored(r.name, Ct.T_EE, true), Rich.colored("Wants to be friends${r.at?.let { " · " + ContestsFormat.ago(it, h.nowMs) } ?: ""}", Ct.T_88), faceUuid = r.uuid,
                    right = listOf(
                        Btn("Accept", style = Btn.ACCENT, small = true) { h.act({ h.src.acceptFriend(r.uuid, it) }) }.node(),
                        Btn("Decline", small = true) { h.act({ h.src.declineFriend(r.uuid, it) }) }.node(),
                        Btn("", "ct_ban", Btn.ICON, tip = "Block: they can't send you requests or invites") { blockConfirm(h, r.uuid, r.name) }.node(),
                    ))
            }
            body.outgoing.forEach { r ->
                req += ListRowNode(Rich.colored(r.name, Ct.T_EE, true), Rich.colored("Request sent${r.at?.let { " " + ContestsFormat.ago(it, h.nowMs) } ?: ""} · waiting for them", Ct.T_88), faceUuid = r.uuid,
                    right = listOf(Btn("Cancel", style = Btn.GHOST, small = true) { h.act({ h.src.cancelFriendRequest(r.uuid, it) }) }.node()))
            }
            if (req.isEmpty()) req += ParaNode(Rich.of("No open requests."), color = Ct.T_66, padY = 2)
            right += cardOf("Requests", req, meta = InlineTextNode(Rich.of("${body.incoming.size + body.outgoing.size}"), S.CAPTION, Ct.T_88))
            if (body.blocked.isNotEmpty()) right += cardOf("Blocked", body.blocked.map { r ->
                ListRowNode(Rich.colored(r.name, Ct.T_EE, true), Rich.colored("Can't send you requests or contest invites", Ct.T_88), faceUuid = r.uuid,
                    right = listOf(Btn("Unblock", style = Btn.GHOST, small = true) { h.act({ h.src.unblockPlayer(r.uuid, it) }) }.node()))
            }, meta = InlineTextNode(Rich.of("${body.blocked.size}"), S.CAPTION, Ct.T_88))
            right += cardOf("What friends see", privacy(h, body.me.shareArea))
        }
        nodes += ColsNode(listOf(listOf(cardOf("Friends", list, meta = InlineTextNode(Rich.of("${body.friends.size}"), S.CAPTION, Ct.T_88))), right),
            { w -> L.twoCols(w, 1.4f, 260) }, stackBelow = 560)
        return Page(listOf(Crumb("Friends", null)), right = listOf(InlineTextNode(Rich.of("Soul friends, not Hypixel friends: both need Soul"))), fixed = fixed, body = nodes, key = "friends")
    }

    private fun privacy(h: ContestsHost, share: Boolean): List<Node> = listOf(
        SwitchRowNode("Friends can see where I am", if (share) "Friends see your area and spot, e.g. Crystal Hollows · Jungle." else "Friends only see whether you are online.", share) {
            h.act({ h.src.setShareArea(!share, it) })
        },
        KvsNode(listOf(
            Kv(Rich.of("Online or offline"), Rich.of("Friends")),
            Kv(Rich.of("Contest runs and loot"), Rich.of("Contest members")),
            Kv(Rich.of("Your Soul stats (/sp)"), Rich.of("Share setting")),
        )),
        ParaNode(Rich.of("Where you are comes from Soul's presence, which Soul already sends. Never shown from Hypixel's Alpha network."), S.TINY, Ct.T_66, 12, padY = 4),
    )

    private fun blockConfirm(h: ContestsHost, uuid: String, name: String) =
        h.confirm("Block $name?", "They can't send you friend requests or contest invites, and don't see you online. They are not told.", "Block") { h.act({ h.src.blockPlayer(uuid, it) }) }

    private fun friendRow(h: ContestsHost, f: Friend, down: Boolean): Node {
        val running = f.contests.firstOrNull { it.state == ContestView.RUNNING }
        val chip = if (down || running == null) null else if (running.role == ContestView.SPECTATOR) Ct.spec("WATCHING", "Spectator in your running contest") else Ct.ok("IN ${running.name.uppercase()}", "Playing in your running contest")
        return ListRowNode(
            Rich.colored(f.name, Ct.T_EE, true),
            if (down) Rich.colored("Online state unknown", Ct.T_55) else where(f.presence, h.nowMs),
            faceUuid = f.uuid, dot = if (down) Dot.UNKNOWN else presenceDot(f.presence), chips = listOfNotNull(chip), dim = !f.presence.online || down,
            right = listOf(
                Btn("", "ct_trophy", Btn.ICON, disabled = down, tip = "Invite to a contest") { inviteFriend(h, f.uuid) }.node(),
                Btn("", "ct_user", Btn.ICON, tip = "Profile (/sp ${f.name})") { h.openProfile(f.name) }.node(),
                AnchoredButtonNode(Btn("", "ct_dots", Btn.ICON, disabled = down, tip = "More") { h.pop("friend:${f.uuid}") }, "friend:${f.uuid}", h),
            ),
        )
    }

    /** Opens Create with this friend ticked as a participant. */
    fun inviteFriend(h: ContestsHost, uuid: String) {
        h.nav.resetCreate()
        h.nav.createInvites[uuid] = ContestView.PARTICIPANT
        h.go(N.CREATE)
    }

    // ------------------------------------------------------------------ create

    const val NAME_KEY = "create.name"
    const val START_KEY = "create.start"
    const val END_KEY = "create.end"

    fun createType(h: ContestsHost): ContestTypeDescriptor? {
        val types = h.src.types().filter { it.enabled }
        return types.firstOrNull { it.id == h.nav.createType } ?: types.firstOrNull()
    }

    private fun goalKind(t: ContestTypeDescriptor) = t.goalKinds.firstOrNull { it.kind == "units" }

    fun createUnits(h: ContestsHost, t: ContestTypeDescriptor): Int {
        val g = goalKind(t)
        return (h.nav.createUnits ?: g?.defaultUnits ?: 50).coerceIn(g?.min ?: 1, g?.max ?: 200)
    }

    /** `HH:mm` as the next such time from [now]; null when it doesn't parse. */
    fun parseTime(text: String, now: Long): Long? {
        val t = try {
            LocalTime.parse(text.trim().let { if (it.length == 4 && it[1] == ':') "0$it" else it })
        } catch (_: Exception) {
            return null
        }
        var at = ZonedDateTime.ofInstant(java.time.Instant.ofEpochMilli(now), ContestsFormat.zone).with(t).withSecond(0).withNano(0)
        if (at.toInstant().toEpochMilli() <= now) at = at.plusDays(1)
        return at.toInstant().toEpochMilli()
    }

    private fun create(h: ContestsHost): Page {
        val t = createType(h)
        val crumbs = listOf(Crumb("Contests") { h.go(N.HOME) }, Crumb("New contest", null))
        if (t == null) {
            val loading = h.src.types().isEmpty()
            return Page(crumbs, body = listOf(ParaNode(Rich.of(if (loading) "Loading contest types…" else "No contest type is available right now."), color = Ct.T_66, padY = 6)), key = "create")
        }
        val nav = h.nav
        val units = createUnits(h, t)
        val friends = h.src.friends().value?.friends.orEmpty()
        val nPlay = nav.createInvites.values.count { it != ContestView.SPECTATOR } + 1
        val nWatch = nav.createInvites.values.count { it == ContestView.SPECTATOR }
        val name = h.state.text(NAME_KEY).trim()
        val startText = h.state.text(START_KEY)
        val endText = h.state.text(END_KEY)
        val form = ArrayList<Node>()
        form += StepHeadNode(1, "What are you competing in?")
        form += TypeCardsNode(h.src.types(), t.id) { id -> nav.createType = id; nav.createUnits = null }
        form += StepHeadNode(2, "Goal and time")
        val g = goalKind(t)
        form += cardOf(null, listOf(
            FormRowNode("Name", listOf(FieldNode(NAME_KEY, "${t.title} with friends", 220))),
            FormRowNode("Goal", listOf(SegNode("create.goal", "units", t.goalKinds.map { it.kind to it.label }.ifEmpty { listOf("units" to "Same number each") }))),
            FormRowNode(ContestsFormat.unitsTitle(t) + " each", listOf(StepperNode(units, { nav.createUnits = max(g?.min ?: 1, units - 5) }, { nav.createUnits = minOf(g?.max ?: 200, units + 5) })),
                hint = "Everyone does the same number of ${t.unit.plural}; the best ${t.metric.label.lowercase()} wins."),
            FormRowNode("Start", listOfNotNull(
                ToggleSegNode(listOf("now" to "When I press Start", "at" to "At a time"), if (nav.createStartAt) "at" else "now") { nav.createStartAt = it == "at" },
                if (nav.createStartAt) FieldNode(START_KEY, "21:00", 70) else null,
            ), hint = if (nav.createStartAt) "Starts by itself at that time (24 h clock)." else "You start it from the lobby once people joined."),
            FormRowNode("Deadline", listOfNotNull(
                ToggleSegNode(listOf("none" to "None", "at" to "At a time"), if (nav.createDeadline) "at" else "none") { nav.createDeadline = it == "at" },
                if (nav.createDeadline) FieldNode(END_KEY, "01:00", 70) else null,
            ), hint = "${t.unit.plural.replaceFirstChar { it.uppercase() }} after the deadline don't count. Without one it ends when everyone is done."),
            FormRowNode("Join late", listOf(ToggleSegNode(listOf("off" to "Off", "on" to "Allow, with my OK"), if (nav.createLateJoin) "on" else "off") { nav.createLateJoin = it == "on" }),
                hint = if (nav.createLateJoin) "People who missed the start can ask; you or a manager accept. They get the same ${t.unit.plural}; theirs count from then." else "Only people in the lobby at the start play."),
        ), padTop = 10, padBottom = 10, gap = 6)
        form += StepHeadNode(3, "Scoring")
        form += cardOf(null, listOf(
            FormRowNode("Prices", listOf(InlineTextNode(Rich.of("Frozen at the start"), S.BODY, Ct.T_EE, true)), hint = "Everyone's loot is priced the same, whenever they got it."),
            FormRowNode("Counts", listOf(InlineTextNode(Rich.of("${t.metric.label} only"), S.BODY, Ct.T_EE, true)), hint = t.description),
        ), padTop = 10, padBottom = 10, gap = 6)
        form += StepHeadNode(4, "Who's in", "Soul friends only · up to ${L.MAX_PARTICIPANTS} playing")
        val inv = ArrayList<Node>()
        if (friends.isEmpty()) inv += ParaNode(Rich.of("Add Soul friends first (Friends, in the rail). You can also create it alone and invite later."), color = Ct.T_88, padY = 4)
        for (f in friends) {
            val role = nav.createInvites[f.uuid]
            inv += ListRowNode(Rich.colored(f.name, if (role != null) Ct.T_EE else Ct.T_88, true), faceUuid = f.uuid, faceSize = 18, dot = presenceDot(f.presence), minH = 28, rule = true, hover = false,
                right = listOfNotNull(
                    if (role != null) AnchoredButtonNode(DropdownBtn(labelCase(role)) { h.pop("inv:${f.uuid}") }, "inv:${f.uuid}", h) else null,
                    CheckboxNode(role != null) { if (role != null) nav.createInvites.remove(f.uuid) else nav.createInvites[f.uuid] = ContestView.PARTICIPANT },
                ))
        }
        inv += ParaNode(Rich.of("Participant plays. Spectator watches live. Manager plays and can invite, remove players, void ${t.unit.plural} and end early."), S.TINY, Ct.T_66, 12, padY = 4)
        form += cardOf(null, inv, padTop = 6, padBottom = 10)
        val startsAt = if (nav.createStartAt) parseTime(startText, h.nowMs) else null
        val endsAt = if (nav.createDeadline) parseTime(endText, startsAt ?: h.nowMs)?.let { e -> if (startsAt != null && e <= startsAt) e + 86_400_000L else e } else null
        val sum = listOf(
            "Type" to t.title,
            "Goal" to "${ContestsFormat.units(t, units)} each",
            "Counts" to "${t.metric.label}. Nothing is subtracted.",
            "Prices" to "Frozen when it starts",
            "Starts" to if (!nav.createStartAt) "When you press Start" else startsAt?.let { ContestsFormat.dayClock(it) } ?: "Enter a time (HH:MM)",
            "Ends" to "When everyone has ${ContestsFormat.units(t, units)}" + if (nav.createDeadline) (endsAt?.let { " or ${ContestsFormat.dayClock(it)}" } ?: " or the deadline") else "",
            "Joining late" to if (nav.createLateJoin) "Allowed, with your OK" else "Off",
            "People" to "$nPlay playing (you included) · $nWatch watching",
        )
        val tooMany = nPlay > L.MAX_PARTICIPANTS
        val canCreate = !nav.busy && !tooMany && (!nav.createStartAt || startsAt != null) && (!nav.createDeadline || endsAt != null)
        val summary = cardOf(null, listOf(
            ParaNode(Rich.colored(name.ifEmpty { "Untitled" }, Ct.T_EE, true), S.HEADING, Ct.T_EE, 18),
            ColKvsNode(sum),
            ParaNode(Rich.of(if (tooMany) "At most ${L.MAX_PARTICIPANTS} people can play." else "Invites go out now. People join the lobby; nothing counts until it starts. You can change everything until then."), S.TINY, if (tooMany) Ct.RED else Ct.T_66, 12, padY = 4),
            Btn("Create and invite", style = Btn.ACCENT, wide = true, disabled = !canCreate) {
                val req = CreateContestRequest(
                    type = t.id, name = name.ifEmpty { "${t.title} with friends" }.take(32), goal = GoalInput("units", units),
                    startsAt = startsAt, endsAt = endsAt, allowLateJoin = nav.createLateJoin, draft = false, profileId = null,
                    invites = nav.createInvites.map { (u, r) -> InviteSpec(u, r) },
                )
                nav.busy = true
                h.src.createContest(req) { r ->
                    nav.busy = false
                    if (r.ok) {
                        nav.resetCreate()
                        h.state.field(NAME_KEY).set("")
                        h.go(N.CONTEST, r.contestId)
                    } else {
                        h.confirm("Couldn't create it", r.message ?: "Soul's server said no (${r.code}).", "OK") {}
                    }
                }
            }.node(),
            Btn("Cancel", style = Btn.GHOST, wide = true) { h.go(N.HOME) }.node(),
        ), padTop = 11, padBottom = 12)
        val body = listOf(ColsNode(listOf(form, listOf(summary)), { w -> intArrayOf(w - L.CREATE_SUM_W - L.CREATE_GAP, L.CREATE_SUM_W) }, gap = L.CREATE_GAP, stackBelow = 520))
        return Page(crumbs, body = body, key = "create")
    }

    /** Pre-fills Create from a finished contest: same type, goal, rules and people with their roles. */
    fun playAgain(h: ContestsHost, v: ContestView) {
        h.nav.resetCreate()
        h.nav.createType = v.type.id
        h.nav.createUnits = v.goal
        h.nav.createLateJoin = v.c.allowLateJoin
        for (m in v.members) {
            if (v.isMe(m.uuid) || m.status == ContestView.DECLINED || m.status == ContestView.INVITED) continue
            h.nav.createInvites[m.uuid] = if (m.role == ContestView.OWNER) ContestView.MANAGER else m.role
        }
        h.state.field(NAME_KEY).set(v.c.name.take(30).let { if (it.endsWith(" 2")) it else "$it 2" }.take(32))
        h.go(N.CREATE)
    }

    // ------------------------------------------------------------------ contest

    fun view(h: ContestsHost, id: Long): ContestView? {
        val snap = h.src.snapshot(id).value ?: return null
        return ContestView(snap, h.src.selfUuid, h.src.queued(id))
    }

    private fun loadingPage(h: ContestsHost, crumbs: List<Crumb>, key: String): Page {
        val err = h.nav.contestId?.let { h.src.snapshot(it).error }
        val text = if (err != null) "This contest couldn't be loaded. ${err.message ?: err.error}" else "Loading…"
        return Page(crumbs, fixed = listOfNotNull(connBanner(h, null)), body = listOf(ParaNode(Rich.of(text), color = Ct.T_66, padY = 6)), key = key)
    }

    private fun contestHead(h: ContestsHost, v: ContestView): Node {
        val c = v.c
        val t = v.type
        val parts = ArrayList<String>()
        parts += "${ContestsFormat.units(t, v.goal)} each"
        when {
            v.lobby -> parts += c.startsAt?.let { "starts ${ContestsFormat.dayClock(it)}" } ?: "starts when the owner presses Start"
            else -> c.startedAt?.let { parts += "started ${clock(it)}" }
        }
        when {
            v.running -> parts += "ends when everyone has ${ContestsFormat.units(t, v.goal)}" + (c.endsAt?.let { " or ${ContestsFormat.dayClock(it)}" } ?: "")
            v.finished -> parts += "ended ${clock(c.endedAt)}"
            v.canceled -> parts += "canceled ${clock(c.canceledAt)}"
            else -> c.endsAt?.let { parts += "deadline ${ContestsFormat.dayClock(it)}" }
        }
        parts += if (v.lobby) "prices freeze at the start" else c.pricesFrozenAt?.let { "prices frozen at ${clock(it)}" } ?: "prices frozen at the start"
        parts += "${t.metric.label.lowercase()} only"
        val live = !v.over
        val buttons = ArrayList<Btn>()
        val anchors = HashMap<Btn, String>()
        if (live) {
            buttons += Btn("Rules", "ct_info", Btn.GHOST) { h.pop("rules") }.also { anchors[it] = "rules" }
            if (v.canManage && (v.running || v.lobby)) buttons += Btn("Manage", "ct_caret") { h.pop("manage") }.also { anchors[it] = "manage" }
            if (v.running && v.role != ContestView.OWNER && v.can("leave")) buttons += Btn(if (v.isSpectator) "Stop watching" else "Leave", "ct_door", Btn.GHOST) { leaveConfirm(h, v) }
        }
        return HeadNode(c.name, listOfNotNull(Ct.type(t.title.uppercase()), Ct.state(v.state), Ct.role(v.role)), Rich.of(parts.joinToString(" · ")), icon = "ct_gem", buttons = buttons,
            onButtonRect = { b, x0, y0, x1, y1 -> anchors[b]?.let { h.anchor(it, x0, y0, x1, y1) } })
    }

    fun leaveConfirm(h: ContestsHost, v: ContestView) {
        if (v.isSpectator) h.confirm("Stop watching?", "You can watch again if someone invites you.", "Stop watching") { h.act({ h.src.leave(v.c.id, it) }) { h.go(N.HOME) } }
        else h.confirm("Leave ${v.c.name}?", "Your ${v.type.unit.plural} so far stay in the list but no longer count, and you can't rejoin.", "Leave") { h.act({ h.src.leave(v.c.id, it) }) { h.go(N.HOME) } }
    }

    /** The rules list (popover and lobby card), from the descriptor plus the contest's own settings. */
    fun rules(v: ContestView): List<Node> {
        val t = v.type
        val out = ArrayList<Node>()
        out += IconLineNode("ct_trophy", rich { b("${ContestsFormat.units(t, v.goal)} each."); t(" The most ${t.metric.label.lowercase()} after ${ContestsFormat.units(t, v.goal)} wins. Same total: the higher best ${t.unit.singular} wins; same best ${t.unit.singular} too: a shared place.") })
        val icons = listOf("ct_gem", "ct_coins", "ct_clock", "ct_coins", "ct_info")
        t.rules.forEachIndexed { i, r -> out += IconLineNode(icons[i % icons.size], Rich.of(r)) }
        v.c.pricesFrozenAt?.let { out += IconLineNode("ct_clock", rich { t("Prices were "); b("frozen at ${clock(it)}"); t(": Soul's server prices every drop with the same price list.") }) }
        t.verification?.let { out += IconLineNode("ct_shield", Rich.of("$it Flagged ${t.unit.plural} still count until the owner or a manager voids them.")) }
        out += IconLineNode("ct_flag", Rich.of("Results are final the moment it ends. ${ContestsFormat.unitsTitle(t)} Hypixel hasn't confirmed yet count as they are. A ${t.unit.singular} saved while offline still counts if it finished before the end, even when it arrives later."))
        out += IconLineNode("ct_user_plus", Rich.of(if (v.c.allowLateJoin) "Joining after the start: allowed with the owner's or a manager's OK. Late joiners get the same ${t.unit.plural}; theirs count from when they are accepted." else "Joining after the start is off: only people in the lobby at the start play."))
        out += IconLineNode("ct_ban", Rich.of("${ContestsFormat.unitsTitle(t)} on Hypixel's Alpha network never count."))
        return out
    }

    private fun contest(h: ContestsHost): Page {
        val id = h.nav.contestId ?: return home(h)
        val past = h.nav.page == N.PAST
        val v = view(h, id)
        val name = v?.c?.name ?: h.src.contests().value?.let { b -> (b.active + b.history + b.invitations).firstOrNull { it.id == id }?.name } ?: "Contest"
        val crumbs = if (past) listOf(Crumb("History") { h.go(N.HISTORY) }, Crumb(name, null)) else listOf(Crumb("Contests") { h.go(N.HOME) }, Crumb(name, null))
        if (v == null) return loadingPage(h, crumbs, "contest:$id")
        val fixed = ArrayList<Node>()
        if (!v.over) connBanner(h, v)?.let { fixed += it }
        val body = ArrayList<Node>()
        body += contestHead(h, v)
        var bar: Node? = null
        if (v.lobby) {
            body += lobby(h, v)
            bar = lobbyBar(h, v)
        } else if (v.over) {
            body += finished(h, v, past)
        } else {
            if (v.isSpectator) fixed += BannerNode("", rich { b("You're watching."); t(" Spectators see the leaderboard, the feed and every ${v.type.unit.singular} live, but don't compete.") }, icon = "ct_eye",
                buttons = if (v.c.allowLateJoin && v.can("join") && v.member(v.selfUuid)?.request == null) listOf(Btn("Ask to play", small = true, tip = "Joining after the start is allowed in this contest: asks the owner or a manager to make you a participant.") { h.act({ h.src.join(id, ContestView.PARTICIPANT, it) }) }) else emptyList())
            if (v.canManage && v.can("approve")) for (m in v.requests) fixed += BannerNode("req", rich {
                b("${m.name} asks to join as a ${m.request?.role ?: ContestView.PARTICIPANT}."); t(" They get the same ${ContestsFormat.units(v.type, v.goal)} and theirs count from when you accept")
                v.c.endsAt?.let { t("; there's ${ContestsFormat.span(it - h.nowMs)} left") }; t(".")
            }, faceUuid = m.uuid, buttons = listOf(
                Btn("Accept", style = Btn.ACCENT, small = true) { h.act({ h.src.approveJoin(id, m.uuid, it) }) },
                Btn("Decline", style = Btn.GHOST, small = true) { h.act({ h.src.rejectJoin(id, m.uuid, it) }) },
            ))
            body += progress(h, v)
            body += running(h, v)
        }
        return Page(crumbs, fixed = fixed, body = body, bar = bar, key = "contest:$id")
    }

    private fun progress(h: ContestsHost, v: ContestView): Node {
        val t = v.type
        val done = v.ranked.sumOf { it.units }
        val text = buildString {
            v.c.startedAt?.let { append("${ContestsFormat.span(h.nowMs - it)} in · ") }
            append("$done of ${v.ranked.size * v.goal} ${t.unit.plural} done")
            v.c.endsAt?.let { append(" · deadline in ${ContestsFormat.span(it - h.nowMs)}") }
        }
        val right = (v.c.pricesFrozenAt?.let { "Prices frozen ${clock(it)}" } ?: "Prices frozen at the start") + " · ${t.metric.label.lowercase()} only"
        return FixedNode(16) { c, x, y, w, hh ->
            c.icon("ct_clock", x + 4, y + 3, 10, Ct.T_AA)
            val rw = c.width(right, S.CAPTION)
            c.text(right, x + w - 4 - rw, y, y + hh, Ct.T_55, S.CAPTION)
            Rich.of(text).ellipsize(c, S.CAPTION, w - rw - 30).draw(c, x + 20, y, y + hh, S.CAPTION, Ct.T_AA)
        }
    }

    private fun requestNote(h: ContestsHost, v: ContestView, m: Member): Rich = when (m.status) {
        ContestView.REQUESTED -> Rich.of("Asked at ${clock(m.request?.at)} · waiting for ${if (v.canManage) "you" else v.owner.name} or a manager")
        ContestView.REJECTED -> Rich.of("Asked to join late; it was declined.")
        else -> Rich.of("Invited at ${clock(m.invitedAt)}, didn't join before the start." + if (v.c.allowLateJoin) "" else " Joining after the start is off.")
    }

    private fun leaderboardCard(h: ContestsHost, v: ContestView, title: String): Node {
        val st = stale(h)
        val conn = h.src.connection()
        val sub = if (!v.running) null else if (st) Rich.colored("as of ${clock(conn.dataAt ?: conn.since)}", Ct.GOLD) else Rich.colored("updates live", Ct.GREEN)
        val narrow = L.narrow(h.mainW)
        val tools = if (v.running) listOf(ToggleSegNode(listOf("total" to "Total", "avg" to "Average"), h.nav.lbSort) { h.nav.lbSort = it }) else emptyList()
        val node = LeaderboardNode(v, h.nav.lbSort, st, h.nowMs, narrow, { null }, { uuid -> h.go(N.PLAYER, v.c.id, uuid, fromHistory = h.nav.page == N.PAST) }) { m -> requestNote(h, v, m) }
        return cardOf(title, listOf(node), sub = sub, tools = tools, padX = 6, padTop = 9, padBottom = 8)
    }

    private fun running(h: ContestsHost, v: ContestView): Node {
        val left = ArrayList<Node>()
        left += leaderboardCard(h, v, "Leaderboard")
        if (v.projection().isNotEmpty()) left += cardOf("Projected finish", listOf(ProjectionNode(v)), chips = listOf(Ct.proj("PROJECTION", "Not a result. Each player's average so far x ${ContestsFormat.units(v.type, v.goal)}. Changes with every ${v.type.unit.singular}.")))
        val feed = FeedNode(v, v.feed(h.nav.feedFilter, h.nowMs, h.src.feed(v.c.id).ifEmpty { v.snap.feed }),
            if (h.nav.feedFilter == "drops") emptyList() else v.queued, stale(h)) { uuid -> h.go(N.PLAYER, v.c.id, uuid) }
        val feedCard = cardOf("Live feed", listOf(feed, ParaNode(Rich.of("Older ${v.type.unit.plural} are in each player's run list."), S.TINY, Ct.T_66, 12, padY = 4)),
            tools = listOf(ToggleSegNode(listOf("all" to "All", "drops" to "Drops", "checks" to "Checks"), h.nav.feedFilter) { h.nav.feedFilter = it }), padX = 10, padTop = 9, padBottom = 10)
        val stack = L.feedStacked(h.mainW)
        return if (stack) stackOf(left + feedCard) else ColsNode(listOf(left, listOf(feedCard)), { w -> intArrayOf(w - L.FEED_W - L.GAP, L.FEED_W) })
    }

    private fun lobby(h: ContestsHost, v: ContestView): Node {
        val rows = ArrayList<Node>()
        val players = v.members.filter { it.status != ContestView.REMOVED && it.status != ContestView.LEFT }
        for ((i, m) in players.withIndex()) {
            val me = v.isMe(m.uuid)
            val (cls, label) = when {
                m.status == ContestView.JOINED && (m.ready || m.role == ContestView.SPECTATOR) -> Ct.GREEN to "Ready"
                m.status == ContestView.JOINED -> Ct.T_AA to "Joined, not ready"
                m.status == ContestView.INVITED -> Ct.GOLD to "Invited · no answer yet"
                m.status == ContestView.DECLINED -> Ct.RED to "Declined"
                else -> Ct.T_AA to labelCase(m.status)
            }
            val editable = v.canManage && m.role != ContestView.OWNER && !me && !(v.role == ContestView.MANAGER && m.role == ContestView.MANAGER)
            val roleNode: Node = if (editable) AnchoredButtonNode(DropdownBtn(labelCase(m.role)) { h.pop("lrole:${m.uuid}") }, "lrole:${m.uuid}", h)
            else InlineTextNode(rich { if (m.role == ContestView.OWNER) t("Owner") else t(labelCase(m.role)) }, S.SMALL, Ct.T_AA).let { FixedWidthNode(86, it, if (m.role == ContestView.OWNER) "ct_crown" else null) }
            rows += ListRowNode(
                Rich.colored(m.name, Ct.T_EE, true), where(m.presence, h.nowMs), faceUuid = m.uuid, dot = presenceDot(m.presence), chips = listOfNotNull(if (me) Ct.you() else null),
                minH = 32, rule = i < players.lastIndex || v.canManage, hover = false, dim = m.status == ContestView.DECLINED,
                right = listOf(
                    FixedWidthNode(120, InlineTextNode(Rich.of(label), S.CAPTION, cls), alignRight = true),
                    roleNode,
                    if (editable) Btn("", "ct_close", Btn.ICON, tip = if (m.status == ContestView.INVITED) "Take back the invite" else "Remove from the contest") {
                        h.confirm("Remove ${m.name}?", "They leave the lobby. You can invite them again.", "Remove") { h.act({ h.src.removeMember(v.c.id, m.uuid, "", it) }) }
                    }.node() else FixedNode(22).let { FixedWidthNode(22, it) },
                ),
            )
        }
        if (v.canManage && v.can("invite")) rows += FlowNode(listOf(
            AnchoredButtonNode(Btn("Invite friends", "ct_user_plus", small = true) { h.pop("invite") }, "invite", h),
            InlineTextNode(Rich.of(if (v.role == ContestView.OWNER) "Only you can make managers." else "Only the owner can make managers."), S.CAPTION, Ct.T_55),
        ), gap = 10)
        val nReady = v.members.count { it.role != ContestView.SPECTATOR && it.status == ContestView.JOINED && it.ready }
        val people = cardOf("People", rows, meta = InlineTextNode(Rich.of("$nReady ready to play · max ${v.c.maxPlayers}"), S.CAPTION, Ct.T_88))
        val help = cardOf("Before it starts", listOf(ParaNode(Rich.of("Nothing counts yet. " + (if (v.canManage) "You can still change the goal and deadline. " else "") + "Get your kit ready."), S.SMALL, Ct.T_BB, 14)))
        return ColsNode(listOf(listOf(people), listOf(cardOf("Rules", rules(v)), help)), { w -> L.twoCols(w, 1.3f, 260) }, stackBelow = 560)
    }

    private fun lobbyBar(h: ContestsHost, v: ContestView): Node {
        val id = v.c.id
        val me = v.member(v.selfUuid)
        if (me?.status == ContestView.INVITED) return BarNode("ct_bell", "${v.owner.name} invited you as a ${me.role}.", listOf(
            Btn("Decline", style = Btn.GHOST) { h.act({ h.src.declineInvite(id, it) }) { h.go(N.HOME) } },
            Btn("Watch") { h.act({ h.src.join(id, ContestView.SPECTATOR, it) }) },
            Btn("Join", style = Btn.ACCENT) { h.act({ h.src.join(id, if (me.role == ContestView.SPECTATOR) ContestView.SPECTATOR else ContestView.PARTICIPANT, it) }) },
        ))
        if (v.isSpectator) return BarNode("ct_eye", "You're a spectator: you'll see everything live once it starts.", listOf(Btn("Stop watching", style = Btn.GHOST) { leaveConfirm(h, v) }))
        if (!v.canManage) {
            val ready = me?.ready == true
            return BarNode("ct_clock", "Waiting for ${v.owner.name} to start.", listOf(
                Btn("Leave", style = Btn.GHOST) { leaveConfirm(h, v) },
                Btn(if (ready) "Ready" else "I'm ready", if (ready) "ct_check" else null, if (ready) Btn.OK else Btn.ACCENT) { h.act({ h.src.setReady(id, !ready, it) }) },
            ))
        }
        val ready = v.members.filter { it.role != ContestView.SPECTATOR && it.status == ContestView.JOINED && it.ready }
        val notReady = v.members.filter { it.role != ContestView.SPECTATOR && it.status == ContestView.JOINED && !it.ready }
        val noAnswer = v.members.filter { it.status == ContestView.INVITED }
        val note = buildList {
            add("${ready.size} ready")
            notReady.forEach { add("${it.name} not ready") }
            noAnswer.forEach { add("${it.name} hasn't answered" + if (v.c.allowLateJoin) " (can join late)" else "") }
        }.joinToString(" · ")
        val buttons = ArrayList<Btn>()
        if (me?.profileLocked == false && v.can("join")) buttons += Btn("Lock my profile", tip = "Locks your current SkyBlock profile for this contest.") { h.act({ h.src.join(id, ContestView.PARTICIPANT, it) }) }
        if (v.can("start")) buttons += Btn("Start now", "ct_play", Btn.ACCENT) {
            h.confirm("Start ${v.c.name} now?", "${ready.size} ${if (ready.size == 1) "player is" else "players are"} ready." + (if (noAnswer.isNotEmpty()) " ${noAnswer.joinToString(", ") { it.name }} hasn't answered" + (if (v.c.allowLateJoin) " and can still ask to join after the start." else "; joining after the start is off, so they would miss it.") else "") + " Prices are frozen now. ${ContestsFormat.unitsTitle(v.type)} count from this moment.", "Start") {
                h.act({ h.src.start(id, it) })
            }
        }
        return BarNode(null, note, buttons, left = if (v.can("cancel")) Btn("Cancel contest", style = Btn.DANGER) { cancelPrompt(h, v) } else null)
    }

    fun cancelPrompt(h: ContestsHost, v: ContestView) =
        h.prompt("Cancel the contest?", "Nobody wins and nothing counts. It stays in History as canceled. Everyone sees your reason.", "Cancel contest", "Canceled by ${h.src.selfName}.") { reason ->
            h.act({ h.src.cancel(v.c.id, reason.take(120), it) })
        }

    fun endConfirm(h: ContestsHost, v: ContestView) =
        h.confirm("End the contest now?", "Results are final at once: the standings are taken from the ${v.type.unit.plural} counted so far, and players who haven't reached ${ContestsFormat.units(v.type, v.goal)} are ranked by their total anyway. ${ContestsFormat.unitsTitle(v.type)} Hypixel hasn't confirmed yet count as they are. A ${v.type.unit.singular} already saved offline still counts when it arrives.", "End now") {
            h.act({ h.src.endEarly(v.c.id, it) })
        }

    fun removePrompt(h: ContestsHost, v: ContestView, uuid: String, name: String) =
        h.prompt("Remove $name from the contest?", "$name is listed as disqualified with your reason. Their ${v.type.unit.plural} stay visible but don't count. This can't be undone.", "Remove player", "Disqualified by ${h.src.selfName}.") { reason ->
            h.act({ h.src.removeMember(v.c.id, uuid, reason.take(120), it) })
        }

    private fun finished(h: ContestsHost, v: ContestView, past: Boolean): List<Node> {
        val out = ArrayList<Node>()
        val t = v.type
        if (v.canceled) {
            out += BannerNode("cxl", rich {
                b("This contest was canceled.")
                t(" ${v.c.canceledBy?.let { "Canceled by ${it.name}" } ?: "Canceled"} at ${clock(v.c.canceledAt)}")
                v.c.canceledReason?.takeIf { it.isNotEmpty() }?.let { t(": \"$it\"") }
                t(". Nothing from it counts and there's no winner.")
            }, icon = "ct_ban")
            if (v.ranked.any { it.units > 0 }) out += leaderboardCard(h, v, "Standings when it was canceled")
            return out
        }
        tieBanner(v)?.let { out += it }
        standingsUpdated(v)?.let { out += it }
        val win = v.ranked.firstOrNull()
        val unconf = v.unconfirmed
        val finalLine = "Results were final at once" + (if (unconf > 0) "; $unconf ${if (unconf == 1) t.unit.singular else t.unit.plural} not confirmed by Hypixel count as they are" else "") + "."
        val why = when (v.c.endReason) {
            "all_finished" -> "everyone reached ${ContestsFormat.units(t, v.goal)}"
            "ended_early" -> "it was ended early"
            "deadline" -> "the deadline was reached"
            else -> "it ended"
        }
        val text = if (!past) "Ended ${clock(v.c.endedAt)} when $why." + (win?.let { " ${it.name} wins with ${ContestsFormat.metric(t, it.total)}." } ?: "") + " $finalLine"
        else "${v.c.endedAt?.let { ContestsFormat.date(it) } ?: ""}, ${clock(v.c.startedAt)}–${clock(v.c.endedAt)}. ${v.ranked.size} players. $finalLine"
        val side = cardOf(if (past) "From your history" else "It's over", listOfNotNull(
            ParaNode(Rich.of(text), S.SMALL, Ct.T_BB, 14),
            Btn("Play again with the same people", "ct_refresh", Btn.ACCENT, wide = true, tip = "Opens New contest with the same people, roles and rules") { playAgain(h, v) }.node(),
            if (!past) Btn("Open in History", style = Btn.GHOST, wide = true) { h.go(N.HISTORY) }.node() else null,
        ))
        out += ColsNode(listOf(listOf(PodiumNode(v)), listOf(side)), { w -> L.twoCols(w, 1.4f, 240) }, stackBelow = 520)
        out += cardOf("Per player", listOf(PerPlayerNode(v) { uuid -> h.go(N.PLAYER, v.c.id, uuid, fromHistory = past) }), sub = Rich.of("Click a player for every ${t.unit.singular}"))
        out += leaderboardCard(h, v, "Final standings")
        return out
    }

    private fun tieBanner(v: ContestView): Node? {
        val shared = v.ranked.filter { it.shared }
        val t = v.type
        if (shared.isNotEmpty()) {
            val a = shared[0]
            return BannerNode("warn", rich { b("Shared place."); t(" ${shared.joinToString(" and ") { it.name }} finished with exactly ${com.soulreturns.features.profileviewer.gui.kit.Format.num(a.total)} and the same best ${t.unit.singular} (${ContestsFormat.metric(t, a.best)}), so they share ${ord(a.rank ?: 0)} place.") }, icon = "ct_info")
        }
        val broken = v.ranked.filter { v.tieBreakWith(it) != null }.sortedBy { it.rank }
        if (broken.size >= 2) {
            val (p1, p2) = broken
            return BannerNode("warn", rich {
                b("Tie on the total, decided by the best ${t.unit.singular}.")
                t(" ${p1.name} and ${p2.name} both finished with exactly ${com.soulreturns.features.profileviewer.gui.kit.Format.num(p1.total)}. ${ContestsFormat.possessive(p1.name)} best ${t.unit.singular} was ${ContestsFormat.metric(t, p1.best)}, ${ContestsFormat.possessive(p2.name)} ${ContestsFormat.metric(t, p2.best)}, so ${p1.name} takes ${ord(p1.rank ?: 0)} and ${p2.name} ${ord(p2.rank ?: 0)}.")
            }, icon = "ct_info")
        }
        return null
    }

    /** Late runs (sent after the end with their bundle before it) changed the final standings. */
    private fun standingsUpdated(v: ContestView): Node? {
        val end = v.c.endedAt ?: return null
        val late = v.snap.feed.filter { it.kind == "run" && it.bool("late") == true }.maxByOrNull { it.at } ?: return null
        val mine = late.player?.uuid?.let(v::isMe) == true
        val who = if (mine) "Your" else ContestsFormat.possessive(late.player?.name ?: "A player")
        return BannerNode("ok", rich {
            b("Standings updated at ${clock(late.at)}.")
            t(" $who ${v.type.unit.singular} ${late.int("n") ?: ""} (finished before the end at ${clock(end)}) was saved while offline and arrived after the end. It counts")
            v.me?.rank?.let { t(": you're ${ord(it)} now") }
            t(". Everyone in the contest got this update.")
        }, icon = "ct_refresh")
    }

    // ------------------------------------------------------------------ player

    private fun player(h: ContestsHost): Page {
        val id = h.nav.contestId ?: return home(h)
        val uuid = h.nav.player ?: return contest(h)
        val v = view(h, id)
        val past = h.nav.fromHistory
        val name = v?.c?.name ?: "Contest"
        val row = v?.snap?.standings?.firstOrNull { it.uuid == uuid }
        val m = v?.member(uuid)
        val pname = row?.name ?: m?.name ?: "Player"
        val crumbs = if (past) listOf(Crumb("History") { h.go(N.HISTORY) }, Crumb(name) { h.go(N.PAST, id, fromHistory = true) }, Crumb(pname, null))
        else listOf(Crumb("Contests") { h.go(N.HOME) }, Crumb(name) { h.go(N.CONTEST, id) }, Crumb(pname, null))
        if (v == null) return loadingPage(h, crumbs, "player:$id:$uuid")
        val t = v.type
        val runsR = h.src.runs(id, uuid)
        val runs = runsR.value?.runs.orEmpty()
        val me = v.isMe(uuid)
        val queued = if (me && !v.over) v.queued else emptyList()
        val manage = v.canManage && v.running && (v.can("void") || v.can("disqualify"))
        val dq = row?.status == ContestView.DISQUALIFIED
        val body = ArrayList<Node>()
        val chips = listOfNotNull(if (me) Ct.you() else null, Ct.role(row?.role ?: m?.role ?: ""), if (dq) Ct.cxl("DISQUALIFIED") else null)
        val sub = if (!v.over && m != null) where(m.presence, h.nowMs) else Rich.of("${v.c.name}${v.c.endedAt?.let { " · " + ContestsFormat.date(it) } ?: ""}")
        val buttons = if (manage && !me && !dq && v.can("disqualify") && row?.role != ContestView.OWNER) listOf(Btn("Remove from contest", "ct_ban", Btn.DANGER) { removePrompt(h, v, uuid, pname) }) else emptyList()
        body += HeadNode(pname, chips, sub, faceUuid = uuid, buttons = buttons, subDot = if (!v.over && m != null) presenceDot(m.presence) else null)
        if (row != null) {
            val tiles = ArrayList<Pair<String, Rich>>()
            val tips = HashMap<Int, List<String>>()
            tiles += "Rank" to Rich.of(if (dq) "DQ" else row.rank?.let { ord(it) + if (row.shared) " (shared)" else if (v.tieBreakWith(row) != null) " (tiebreak)" else "" } ?: "–")
            tiles += ContestsFormat.unitsTitle(t) to Rich.of("${row.units}/${v.goal}")
            tiles += "Total" to rich { t(ContestsFormat.metric(t, row.total)); if (row.unpriced > 0) r(Rich.sized("  " + ContestsFormat.noPrice(row.unpriced), Ct.T_88, S.TINY)) }
            if (row.unpriced > 0) tips[2] = listOf(ContestsFormat.NO_PRICE_TIP)
            tiles += "Avg/${t.unit.singular}" to Rich.of(if (row.units > 0) ContestsFormat.metric(t, row.avg) else "–")
            tiles += "Best ${t.unit.singular}" to Rich.of(if (row.units > 0) ContestsFormat.metric(t, row.best) else "–")
            if (t.runFields.duration) {
                tiles += "Median time" to rich { t(ContestsFormat.mmss(row.medianDurationMs)); if (row.untimed > 0) r(Rich.sized("  ${row.untimed} untimed", Ct.T_88, S.TINY)) }
                if (row.untimed > 0) tips[tiles.lastIndex] = listOf("${ContestsFormat.unitsTitle(t)} with no time are counted but left out of times.")
            }
            if (v.running && row.units in 1 until v.goal) tiles += "Projected" to rich { t(ContestsFormat.metric(t, (row.avg ?: 0) * v.goal)); r(Rich.sized("  projection", Ct.T_88, S.TINY)) }
            body += TilesRowNode(tiles, big = true, tips = tips)
        }
        val counts = buildList {
            row?.checks?.let { c ->
                if (c.verified > 0) add("${c.verified} confirmed")
                if (c.pending > 0) add("${c.pending} ${if (v.finished) "not confirmed" else "pending"}")
                if (c.flagged > 0) add("${c.flagged} flagged")
            }
            if (row != null && row.voided > 0) add("${row.voided} voided")
            if (queued.isNotEmpty()) add("${queued.size} not sent yet")
        }
        val sent = runs.size
        val runNodes = ArrayList<Node>()
        if (runsR.value == null && runsR.loading) runNodes += ParaNode(Rich.of("Loading ${t.unit.plural}…"), color = Ct.T_66, padY = 6)
        else if (runsR.value == null && runsR.error != null) runNodes += ParaNode(Rich.of("Couldn't load the ${t.unit.plural}. ${runsR.error.message.orEmpty()}"), color = Ct.T_66, padY = 6)
        else runNodes += RunListNode(t, runs, queued, v.finished, manage && v.can("void"),
            onVoid = { r -> voidPrompt(h, v, r, pname) },
            onRestore = { r -> h.confirm("Restore ${t.unit.singular} ${r.n ?: ""}?", "It counts again. Everyone sees that you restored it.", "Restore") { h.act({ h.src.restoreRun(id, r.runId, it) }) { h.src.loadRuns(id, uuid) } } })
        body += cardOf(ContestsFormat.unitsTitle(t), runNodes, sub = Rich.of("$sent sent" + if (counts.isNotEmpty()) " · " + counts.joinToString(" · ") else ""), padX = 6, padTop = 9, padBottom = 8)
        return Page(crumbs, fixed = listOfNotNull(if (!v.over) connBanner(h, v) else null), body = body, key = "player:$id:$uuid")
    }

    private fun voidPrompt(h: ContestsHost, v: ContestView, r: com.soulreturns.features.contests.model.Run, name: String) {
        val t = v.type
        val initial = if (r.check.state == "flagged") "Flagged: ${Checks.reason(r.check.reason) ?: "Hypixel's counters don't show this ${t.unit.singular}"}" else ""
        h.prompt("Void ${t.unit.singular} ${r.n ?: ""} of $name?", "It stops counting (${ContestsFormat.signedMetric(t, r.value)}) and $name needs one more ${t.unit.singular} to reach ${v.goal}. Everyone sees that you voided it and why.", "Void ${t.unit.singular}", initial.take(120)) { reason ->
            h.act({ h.src.voidRun(v.c.id, r.runId, reason.take(120), it) }) { h.src.loadRuns(v.c.id, h.nav.player ?: "") }
        }
    }

    // ------------------------------------------------------------------ how results are checked

    private fun checks(h: ContestsHost): Page {
        val t = h.nav.contestId?.let { h.src.snapshot(it).value?.type } ?: h.src.types().firstOrNull { it.enabled }
        val unit = t?.unit?.singular ?: "run"
        val units = t?.unit?.plural ?: "runs"
        val body = ArrayList<Node>()
        body += ParaNode(Rich.of("Contests are run by Soul's server, not by your game. Here's what happens with every $unit, in plain words."), S.SMALL, Ct.T_AA, 14, 640)
        body += StepNode(1, "ct_gem", "Your game reports the $unit", Rich.of("When a $unit finishes, Soul sends the server what it got and how long it took. Nothing else."))
        body += StepNode(2, "ct_coins", "The server prices it", Rich.of(((t?.rules ?: emptyList()).drop(1).joinToString(" ")).ifEmpty { "The server uses its own price list, frozen at the start." } + " Your game never decides what anything is worth, so editing files doesn't change your score."))
        body += StepNode(3, "ct_shield", "The server checks the $unit count with Hypixel", rich {
            t((t?.verification ?: "The server compares Hypixel's counters with the $units it got.") + " It also looks for $units that are impossibly fast or sent twice. A match marks a $unit ")
            c("Confirmed", Ct.GREEN, true); t(": it really happened.")
        })
        body += StepNode(4, "ct_flag", "Odd ${units} get flagged", Rich.of("If the numbers don't match, the $unit is flagged with the reason. It still counts, and everyone in the contest sees the flag. The owner or a manager decides: void it (it stops counting) or leave it."))
        body += cardOf(null, listOf("verified", "pending", "flagged", "voided", "queued", "unconfirmed").map { LegendRowNode(it) }, gap = 2).let { MaxWidthNode(720, it) }
        body += BannerNode("warn", rich {
            b("Being honest about the limits."); t(" Hypixel updates its API only every few minutes, so a fresh $unit usually says "); b("Pending")
            t(" for a while, sometimes longer when Hypixel is slow. And a modified mod could still send made-up loot: the check proves you did a $unit, not what dropped. That part is trust between friends, and the owner's call.")
        }, icon = "ct_info", maxW = 720)
        return Page(listOf(Crumb("How results are checked", null)), body = body, key = "checks")
    }
}

// ------------------------------------------------------------------ page-only nodes

/** A button that records its rect so a popover can anchor to it. */
class AnchoredButtonNode(val b: Btn, val id: String, val h: ContestsHost) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = b.width(m)
    override fun measure(m: KitMeasure, w: Int): Int = b.height()
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h0: Int) {
        val bw = c.button(b, x, y)
        h.anchor(id, x, y, x + bw, y + b.height())
    }
}

/** `.dd`: a small dropdown trigger (role pickers). */
fun DropdownBtn(label: String, onClick: () -> Unit) = Btn(label, "ct_caret", Btn.DEFAULT, small = true, onClick = onClick)

/** `.ckb`: a 14 px checkbox. */
class CheckboxNode(val on: Boolean, val onClick: () -> Unit) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = 14
    override fun measure(m: KitMeasure, w: Int): Int = 14
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        if (on) c.rect(x, y, x + 14, y + 14, Ct.ACCENT, Corner.SMALL)
        else {
            c.rect(x, y, x + 14, y + 14, 0xFF3A3A3A.toInt(), Corner.SMALL)
            c.rect(x + 1, y + 1, x + 13, y + 13, Ct.RULE, Corner.SMALL)
        }
        if (on) c.icon("ct_check", x + 2, y + 2, 10, Ct.WHITE)
        c.onClick(x - 2, y - 2, x + 16, y + 16, onClick)
    }
}

/** A text field (`.fld`, 26 px) bound to the screen's [com.soulreturns.features.profileviewer.gui.kit.ViewState] field [key]. */
class FieldNode(val key: String, val placeholder: String, val w: Int, val disabled: Boolean = false) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = w
    override fun measure(m: KitMeasure, w: Int): Int = 26
    override fun paint(c: KitCanvas, x: Int, y: Int, w0: Int, h: Int) {
        if (disabled) {
            c.rect(x, y, x + w, y + 26, 0x80242424.toInt(), Corner.SMALL)
            c.text(placeholder, x + 26, y, y + 26, Ct.T_44, S.BODY)
            return
        }
        c.field(key, x, y, x + w, y + 26, placeholder)
    }
}

/** `.seg` bound to a callback instead of a ViewState key. */
class ToggleSegNode(val options: List<Pair<String, String>>, val value: String, val onPick: (String) -> Unit) : Node(), Inline {
    private fun btnW(m: KitMeasure, label: String) = m.width(label, S.SMALL) + 16
    override fun inlineWidth(m: KitMeasure): Int = 4 + options.sumOf { btnW(m, it.second) } + (options.size - 1)
    override fun measure(m: KitMeasure, w: Int): Int = 22
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val sw = inlineWidth(c)
        c.rect(x, y, x + sw, y + 22, 0x57000000, Corner.MEDIUM)
        var bx = x + 2
        for ((v, label) in options) {
            val bw = btnW(c, label)
            val on = v == value
            val hover = c.hovered(bx, y + 2, bx + bw, y + 20)
            if (on) c.rect(bx, y + 2, bx + bw, y + 20, Ct.ACCENT, Corner.SMALL)
            c.text(label, bx + 8, y, y + 22, if (on) Ct.WHITE else if (hover) Ct.T_EE else Ct.T_88, S.SMALL)
            if (!on) c.onClick(bx, y, bx + bw, y + 22) { onPick(v) }
            bx += bw + 1
        }
    }
}

/** `.stepper`: − value +. */
class StepperNode(val value: Int, val minus: () -> Unit, val plus: () -> Unit) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = 22 + 30 + 22
    override fun measure(m: KitMeasure, w: Int): Int = 24
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.rect(x, y, x + 74, y + 24, 0xFF333333.toInt(), Corner.SMALL)
        c.rect(x + 1, y + 1, x + 73, y + 23, 0xFF242424.toInt(), Corner.SMALL)
        val hm = c.hovered(x, y, x + 22, y + 24)
        val hp = c.hovered(x + 52, y, x + 74, y + 24)
        c.text("-", x + 8, y, y + 24, if (hm) Ct.WHITE else Ct.T_AA, S.px(12f), true)
        val vs = "$value"
        c.text(vs, x + 22 + (30 - c.width(vs, S.BODY, true)) / 2, y, y + 24, Ct.T_EE, S.BODY, true)
        c.text("+", x + 59, y, y + 24, if (hp) Ct.WHITE else Ct.T_AA, S.px(12f), true)
        c.onClick(x, y, x + 22, y + 24, minus)
        c.onClick(x + 52, y, x + 74, y + 24, plus)
    }
}

/** `.fr2`: a 72 px label, controls, and a dim hint filling the rest (wraps under when narrow). */
class FormRowNode(val label: String, val controls: List<Node>, val hint: String? = null) : Node() {
    private fun ctrlW(m: KitMeasure) = controls.sumOf { (it as? Inline)?.inlineWidth(m) ?: 0 } + 10 * max(0, controls.size - 1)
    private fun hintX(m: KitMeasure) = 72 + 10 + ctrlW(m) + 10
    private fun hintLines(m: KitMeasure, w: Int): List<Rich> {
        if (hint == null) return emptyList()
        val room = w - hintX(m)
        return if (room >= 200) Rich.of(hint).wrap(m, S.CAPTION, room) else Rich.of(hint).wrap(m, S.CAPTION, w - 82)
    }

    override fun measure(m: KitMeasure, w: Int): Int {
        val lines = hintLines(m, w)
        val beside = w - hintX(m) >= 200
        return if (beside) max(26, lines.size * 12) else 26 + lines.size * 12
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.text(label, x, y, y + 26, Ct.T_88, S.BODY)
        var cx = x + 82
        for (n in controls) {
            val nw = (n as? Inline)?.inlineWidth(c) ?: 0
            val nh = n.height(c, nw)
            n.paint(c, cx, y + (26 - nh) / 2, nw, nh)
            cx += nw + 10
        }
        val lines = hintLines(c, w)
        val beside = w - hintX(c) >= 200
        var ly = if (beside) y + max(0, (26 - lines.size * 12) / 2) else y + 26
        val lx = if (beside) x + hintX(c) else x + 82
        for (l in lines) {
            l.draw(c, lx, ly, ly + 12, S.CAPTION, Ct.T_66)
            ly += 12
        }
    }
}

/** `.step`: the numbered step heading on Create. */
class StepHeadNode(val n: Int, val title: String, val aside: String? = null) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 6 + 16
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val y0 = y + 6
        c.rect(x, y0, x + 16, y0 + 16, Ct.ACCENT_DIM, Corner.PILL)
        val nw = c.width("$n", S.TINY, true)
        c.text("$n", x + 8 - nw / 2, y0, y0 + 16, Ct.WHITE, S.TINY, true)
        c.text(title, x + 24, y0, y0 + 16, Ct.T_EE, S.px(12f), true)
        if (aside != null) c.text(aside, x + 24 + c.width(title, S.px(12f), true) + 8, y0, y0 + 16, Ct.T_55, S.CAPTION)
    }
}

/** `.types`: one card per contest type; disabled types show as examples for later. */
class TypeCardsNode(val types: List<ContestTypeDescriptor>, val selected: String, val onPick: (String) -> Unit) : Node() {
    private fun cols(w: Int) = max(1, (w + 8) / (200 + 8))
    private fun cardH(m: KitMeasure, t: ContestTypeDescriptor, w: Int) = 9 + 14 + 5 + Rich.of(t.description).wrap(m, S.CAPTION, w - 22).size * 13 + 6 + 3 * 12 + 9

    override fun measure(m: KitMeasure, w: Int): Int {
        val n = cols(w)
        val cw = (w - 8 * (n - 1)) / n
        return types.chunked(n).sumOf { row -> row.maxOf { cardH(m, it, cw) } } + 8 * max(0, (types.size + n - 1) / n - 1)
    }

    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        val n = cols(w)
        val cw = (w - 8 * (n - 1)) / n
        var ry = y
        for (row in types.chunked(n)) {
            val rh = row.maxOf { cardH(c, it, cw) }
            for ((i, t) in row.withIndex()) {
                val cx = x + i * (cw + 8)
                val on = t.id == selected && t.enabled
                val hov = t.enabled && c.hovered(cx, ry, cx + cw, ry + rh)
                c.rect(cx, ry, cx + cw, ry + rh, if (on || hov) Ct.ACCENT else Ct.RULE, Corner.MEDIUM)
                c.rect(cx + 1, ry + 1, cx + cw - 1, ry + rh - 1, if (!t.enabled) 0xFF1A1A1A.toInt() else if (on) 0xFF1C2331.toInt() else Ct.CARD, Corner.MEDIUM)
                c.icon("ct_gem", cx + 11, ry + 9, 14, if (t.enabled) Ct.ACCENT_LIGHT else Ct.T_66)
                c.text(t.title, cx + 32, ry + 9, ry + 23, if (t.enabled) Ct.T_EE else Ct.T_88, S.BODY, true)
                if (!t.enabled) Ct.later("EXAMPLE · LATER").paint(c, cx + 32 + c.width(t.title, S.BODY, true) + 6, ry + 16)
                var ly = ry + 28
                for (l in Rich.of(t.description).wrap(c, S.CAPTION, cw - 22)) {
                    l.draw(c, cx + 11, ly, ly + 13, S.CAPTION, Ct.T_99)
                    ly += 13
                }
                ly += 6
                for ((k, v) in listOf("COUNTS" to t.metric.label, "PER" to t.unit.singular, "WHERE" to (t.area ?: "Anywhere"))) {
                    c.text(k, cx + 11, ly, ly + 12, Ct.T_55, S.CHIP, true)
                    c.text(v, cx + 60, ly, ly + 12, Ct.T_BB, S.TINY)
                    ly += 12
                }
                if (!t.enabled) c.tooltip(cx, ry, cx + cw, ry + rh, listOf("Not available yet: it plugs into the same screens with its own metric, unit and goal."), "type:${t.id}")
                if (t.enabled && !on) c.onClick(cx, ry, cx + cw, ry + rh) { onPick(t.id) }
            }
            ry += rh + 8
        }
    }
}

/** `.kv.col`: label over value rows (Create's summary). */
class ColKvsNode(val rows: List<Pair<String, String>>) : Node() {
    private fun rowH(m: KitMeasure, v: String, w: Int) = 4 + 12 + Rich.of(v).wrap(m, S.BODY, w).size * 13 + 4
    override fun measure(m: KitMeasure, w: Int): Int = rows.sumOf { rowH(m, it.second, w) }
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var ry = y
        for ((i, r) in rows.withIndex()) {
            val rh = rowH(c, r.second, w)
            val up = r.first.uppercase()
            val tw = c.trackedWidth(up, S.TINY, 0.06f)
            c.tracked(up, x + tw / 2, ry + 4 + 6, Ct.T_55, S.TINY, 0.06f)
            var ly = ry + 16
            for (l in Rich.of(r.second).wrap(c, S.BODY, w)) {
                l.draw(c, x, ly, ly + 13, S.BODY, Ct.T_EE, true)
                ly += 13
            }
            if (i < rows.lastIndex) c.rect(x, ry + rh - 1, x + w, ry + rh, Ct.LINE, Corner.SQUARE)
            ry += rh
        }
    }
}

/** `.center`: a big icon, a title and a paragraph (empty states). */
class CenterNode(val icon: String, val title: String, val text: String) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = 60 + 30 + 6 + 6 + 16 + 6 + Rich.of(text).wrap(m, S.BODY, minOf(380, w)).size * 14 + 20
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        var cy = y + 60
        c.icon(icon, x + (w - 30) / 2, cy, 30, Ct.T_55)
        cy += 30 + 12
        val tw = c.width(title, S.HEADING, true)
        c.text(title, x + (w - tw) / 2, cy, cy + 16, Ct.T_EE, S.HEADING, true)
        cy += 22
        val mw = minOf(380, w)
        for (l in Rich.of(text).wrap(c, S.BODY, mw)) {
            val lw = l.width(c, S.BODY)
            l.draw(c, x + (w - lw) / 2, cy, cy + 14, S.BODY, Ct.T_88)
            cy += 14
        }
    }
}

/** `.abar`: the lobby's action bar pinned under the scroll. */
class BarNode(val icon: String?, val text: String, val buttons: List<Btn>, val left: Btn? = null) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = L.ABAR_H
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) {
        c.rect(x, y, x + w, y + h, 0xFF111111.toInt(), Corner.SQUARE)
        c.rect(x, y, x + w, y + 1, Ct.RULE, Corner.SQUARE)
        var lx = x + 16
        if (left != null) lx += c.button(left, lx, y + 8) + 8
        if (icon != null) {
            c.icon(icon, lx, y + 13, 14, Ct.ACCENT_LIGHT)
            lx += 22
        }
        var rx = x + w - 16
        for (b in buttons.asReversed()) {
            rx -= b.width(c)
            c.button(b, rx, y + 8)
            rx -= 8
        }
        Rich.of(text).ellipsize(c, if (left != null) S.CAPTION else S.SMALL, rx - lx - 8).let {
            if (left != null) it.draw(c, rx - 8 - it.width(c, S.CAPTION), y, y + h, S.CAPTION, Ct.T_55) else it.draw(c, lx, y, y + h, S.SMALL, Ct.T_AA)
        }
    }
}

/** An inline node held at a fixed width (lobby status / role columns). */
class FixedWidthNode(val w: Int, val inner: Node, val icon: String? = null, val alignRight: Boolean = false) : Node(), Inline {
    override fun inlineWidth(m: KitMeasure): Int = w
    override fun measure(m: KitMeasure, w: Int): Int = inner.height(m, this.w)
    override fun paint(c: KitCanvas, x: Int, y: Int, w0: Int, h: Int) {
        var ix = x
        val iw = (inner as? Inline)?.inlineWidth(c) ?: w
        if (alignRight) ix = x + w - iw
        else if (icon != null) {
            c.icon(icon, x + 8, y + (h - 10) / 2, 10, Ct.GOLD)
            ix = x + 22
        } else ix = x + 8
        inner.paint(c, ix, y, minOf(iw, w), h)
    }
}

/** Caps a child's width (`max-width: 720px`). */
class MaxWidthNode(val maxW: Int, val inner: Node) : Node() {
    override fun measure(m: KitMeasure, w: Int): Int = inner.height(m, minOf(w, maxW))
    override fun paint(c: KitCanvas, x: Int, y: Int, w: Int, h: Int) = inner.paint(c, x, y, minOf(w, maxW), h)
}
