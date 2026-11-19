package com.soulreturns.features.party

import com.soulreturns.features.party.PartyManager.PartyDisbandReason
import com.soulreturns.features.party.PartyManager.PartyEvent
import com.soulreturns.features.party.PartyManager.PartyInvite
import com.soulreturns.features.party.PartyManager.PartyMember
import com.soulreturns.features.party.PartyManager.PartyRole
import com.soulreturns.features.party.PartyManager.PartyState
import com.soulreturns.util.MessageDetector
import java.util.UUID

/** Where the tracked party state last came from. */
enum class PartySource { NONE, CHAT, PARTY_LIST, MOD_API }

/**
 * The party state machine behind [PartyManager]: Hypixel chat lines, `/party list` output and the
 * mod API's party info in; state, [PartyEvent]s and requests out. No Minecraft classes, so the
 * replay tests drive exactly what the game runs. Client thread only.
 *
 * A mod-API packet replaces the roster (uuids and roles, no names), a `/party list` replaces it with
 * names, chat lines change it in between. Every change line asks for a mod-API check shortly after,
 * and a line that contradicts or cannot explain the state asks sooner ([poll]). `/party list` goes
 * out (its echo hidden, [hidesEcho]) only when a packet leaves members or the leader without a name
 * or no packet answers.
 */
class PartyTracker(private val emit: (PartyEvent) -> Unit = {}) {
    enum class Request { PARTY_INFO, PARTY_LIST }

    var selfName: String? = null
    var selfUuid: UUID? = null

    /** `/party list` is only ever sent on a Hypixel connection. */
    var onHypixel = false

    @Volatile
    var state: PartyState? = null
        private set
    var source = PartySource.NONE
        private set
    var updatedAt = 0L
        private set

    private var inParty = false
    private var createdAt = 0L
    private val members = LinkedHashMap<String, PartyMember>()
    private var leaderKey: String? = null
    private val unnamed = LinkedHashSet<UUID>()

    /** Names learned for uuids (mod API with the tab list, inference); kept one-to-one. */
    private val uuidByName = HashMap<String, UUID>()
    private val nameByUuid = HashMap<UUID, String>()

    private val invites = mutableListOf<PartyInvite>()

    private var listOpen = false
    private var listLeader: PartyMember? = null
    private val listMembers = LinkedHashMap<String, PartyMember>()
    private var warpBlock = false

    private var lastInfoRoles: Map<UUID, PartyRole>? = null
    private var lastInfoAt = Long.MIN_VALUE / 2

    private var infoDueAt = -1L
    private var infoReasonAt = 0L
    private var lastInfoSentAt = Long.MIN_VALUE / 2
    private var awaitingInfoSince = -1L
    private var listDueAt = -1L
    private var lastListSentAt = Long.MIN_VALUE / 2
    private var uncertain = false
    private var connected = false

    private var echoUntil = 0L
    private var echoSawContent = false

    private val lineCounts = LinkedHashMap<String, Int>()
    private val eventCounts = LinkedHashMap<String, Int>()
    private val requestReasons = LinkedHashMap<String, Int>()
    private val corrections = LinkedHashMap<String, Int>()
    private val unknownSamples = ArrayDeque<String>()
    private var unknownLines = 0
    private var infoSent = 0
    private var listSent = 0
    private var listsParsed = 0
    private var lastKind: String? = null

    private class Shape(val kind: String, val regex: Regex, val handle: (MatchResult, Long) -> Unit)

    private val shapes: List<Shape> = buildShapes()

    // ------------------------------------------------------------------ inputs

    /** One chat message as received, colors and all; a multi-line component is read line by line. */
    fun onChat(raw: String, now: Long) {
        pruneInvites(now)
        val lines = MessageDetector.stripColorCodes(raw).split('\n')
        for (line in lines) onLine(line.trim(), now)
        if (lines.size > 1 && listOpen) finishList(now)
    }

    /**
     * A mod-API party packet. [roles] holds every member, self included; [names] what the caller
     * could resolve (tab list). Other uuids keep names learned earlier or stay unnamed.
     */
    fun onPartyInfo(inParty: Boolean, roles: Map<UUID, PartyRole>, names: Map<UUID, String>, now: Long) {
        countEvent("mod_api")
        val initial = source == PartySource.NONE
        lastInfoAt = now
        awaitingInfoSince = -1L
        uncertain = false
        if (infoDueAt >= 0 && now - infoReasonAt >= SATISFIES_AFTER_MS) infoDueAt = -1L
        if (!inParty) {
            lastInfoRoles = null
            listDueAt = -1L
            if (this.inParty) {
                if (!initial) correction(PartySource.MOD_API, "in_party")
                clear(now, PartySource.MOD_API) { prev -> listOf(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.UNKNOWN)) }
            } else {
                touch(now, PartySource.MOD_API)
            }
            return
        }
        lastInfoRoles = roles
        val self = selfName
        val me = selfUuid
        if (self != null && me != null) learn(self, me)
        for ((u, n) in names) learn(n, u)

        val before = snapshotKey()
        val wasIn = this.inParty
        val previous = state
        val named = LinkedHashMap<String, PartyMember>()
        val open = LinkedHashSet<UUID>()
        var newLeader: String? = null
        for ((u, role) in roles) {
            val n = nameByUuid[u]
            if (n == null) {
                open += u
                continue
            }
            val key = n.lowercase()
            named[key] = PartyMember(n, members[key]?.displayName ?: n, role, u)
            if (role == PartyRole.LEADER) newLeader = key
        }
        // Chat-known members without a uuid: pair them with the unnamed uuids where that is certain,
        // keep them unpaired while they fit, and drop them all when some must be gone.
        val leftover = members.values.filter { it.name.lowercase() !in named && it.name.lowercase() !in uuidByName }
        if (open.isNotEmpty() && leftover.isNotEmpty()) {
            pairByRole(open, leftover, roles)
            val rest = ArrayList<PartyMember>()
            for (m in leftover) {
                val key = m.name.lowercase()
                val u = uuidByName[key]
                if (u == null || u !in open) {
                    rest += m
                    continue
                }
                open -= u
                val role = roles.getValue(u)
                named[key] = m.copy(role = role, uuid = u)
                if (role == PartyRole.LEADER) newLeader = key
            }
            if (rest.size <= open.size) {
                val leaderUuid = roles.entries.firstOrNull { it.value == PartyRole.LEADER }?.key
                for (m in rest) {
                    val key = m.name.lowercase()
                    val asLeader = m.role == PartyRole.LEADER && newLeader == null && leaderUuid != null && leaderUuid in open
                    val taken = if (asLeader) leaderUuid!! else open.firstOrNull { roles[it] != PartyRole.LEADER } ?: open.first()
                    open -= taken
                    val role = if (asLeader) PartyRole.LEADER else if (m.role == PartyRole.LEADER) PartyRole.MEMBER else m.role
                    named[key] = m.copy(role = role, uuid = null)
                    if (asLeader) newLeader = key
                }
            }
        }
        if (!wasIn) createdAt = now
        this.inParty = true
        members.clear()
        members.putAll(named)
        unnamed.clear()
        unnamed.addAll(open)
        leaderKey = newLeader
        val after = snapshotKey()
        val changed = before != after
        if (!initial) {
            if (!wasIn) correction(PartySource.MOD_API, "in_party") else if (changed) diffCorrections(PartySource.MOD_API, before, after)
        }
        publish(now, PartySource.MOD_API)
        val current = state!!
        if (!wasIn) {
            emit(PartyEvent.PartyJoined(current, joinedAsLeader = isSelf(leaderKey)))
        } else if (changed) {
            emit(PartyEvent.PartyCorrected(previous, current))
        }
        if (needsNames()) requestList(now) else listDueAt = -1L
    }

    /** Every `ClientPlayConnectionEvents.JOIN`: a new connection or a server switch. */
    fun onJoin(now: Long) {
        val reason = if (connected) "server_switch" else "connect"
        if (!connected) {
            connected = true
            uncertain = true
        }
        countEvent(reason)
        requestInfo(now, JOIN_DELAY_MS, reason)
    }

    /** The connection closed: nothing tracked survives it (an offline kick happens unseen). */
    fun onDisconnect() {
        connected = false
        inParty = false
        members.clear()
        unnamed.clear()
        leaderKey = null
        state = null
        source = PartySource.NONE
        invites.clear()
        listOpen = false
        warpBlock = false
        lastInfoRoles = null
        infoDueAt = -1L
        listDueAt = -1L
        awaitingInfoSince = -1L
        uncertain = false
        echoUntil = 0L
        countEvent("disconnect")
    }

    /** At most one request per call; the caller sends it. */
    fun poll(now: Long): Request? {
        pruneInvites(now)
        if (infoDueAt in 0..now) {
            infoDueAt = -1L
            lastInfoSentAt = now
            if (awaitingInfoSince < 0) awaitingInfoSince = now
            infoSent++
            return Request.PARTY_INFO
        }
        if (awaitingInfoSince >= 0 && now - awaitingInfoSince >= INFO_TIMEOUT_MS) {
            awaitingInfoSince = -1L
            countEvent("mod_api_timeout")
            if (uncertain || needsNames()) requestList(now)
        }
        if (listDueAt in 0..now && onHypixel) {
            listDueAt = -1L
            onPartyListSent(now)
            return Request.PARTY_LIST
        }
        return null
    }

    /** `/party list` sent for the tracker: its answer is hidden from chat for a few seconds. */
    fun onPartyListSent(now: Long) {
        lastListSentAt = now
        echoUntil = now + ECHO_WINDOW_MS
        echoSawContent = false
        listSent++
    }

    /** Display filter: true for the lines of a `/party list` answer the tracker asked for. */
    fun hidesEcho(text: String, now: Long): Boolean {
        if (now > echoUntil) return false
        val lines = MessageDetector.stripColorCodes(text).split('\n').map { it.trim() }
        if (lines.any { !isEchoLine(it) }) return false
        if (lines.any { it.isNotEmpty() && !isDivider(it) }) {
            echoSawContent = true
        } else if (echoSawContent && lines.any(::isDivider)) {
            echoUntil = 0L
        }
        return true
    }

    fun pendingInvites(now: Long): List<PartyInvite> {
        pruneInvites(now)
        return invites.toList()
    }

    /** For `DevState` and `/soul dev party`. */
    fun diagnostics(now: Long): Map<String, Any?> {
        val s = state
        return linkedMapOf(
            "in_party" to (s != null),
            "leader" to s?.leader?.name,
            "self_role" to selfName?.let { s?.members?.get(it.lowercase())?.role?.name },
            "size" to (s?.size ?: 0),
            "members" to s?.members?.values?.map { linkedMapOf("name" to it.name, "role" to it.role.name, "uuid" to it.uuid?.toString()) },
            "unnamed" to (s?.unnamedMembers?.size ?: 0),
            "source" to source.name,
            "updated_ms_ago" to if (updatedAt > 0) now - updatedAt else null,
            "last_line" to lastKind,
            "uncertain" to uncertain,
            "lines" to LinkedHashMap(lineCounts),
            "events" to LinkedHashMap(eventCounts),
            "unknown_lines" to unknownLines,
            "unknown_samples" to unknownSamples.toList(),
            "resync" to linkedMapOf(
                "reasons" to LinkedHashMap(requestReasons),
                "mod_api_sent" to infoSent,
                "last_mod_api_ms_ago" to if (lastInfoAt > 0) now - lastInfoAt else null,
                "mod_api_due_in_ms" to if (infoDueAt >= 0) infoDueAt - now else null,
                "party_list_sent" to listSent,
                "party_list_parsed" to listsParsed,
                "party_list_due_in_ms" to if (listDueAt >= 0) listDueAt - now else null,
            ),
            "corrections" to LinkedHashMap(corrections),
            "invites" to pendingInvites(now).map { "${it.from}->${it.to}" },
        )
    }

    fun lineCounts(): Map<String, Int> = LinkedHashMap(lineCounts)

    fun corrections(): Map<String, Int> = LinkedHashMap(corrections)

    fun sent(): Map<String, Int> = linkedMapOf("mod_api" to infoSent, "party_list" to listSent)

    fun unknownLines(): Int = unknownLines

    // ------------------------------------------------------------------ lines

    private fun onLine(line: String, now: Long) {
        if (line.isEmpty()) return
        if (isDivider(line)) {
            if (listOpen) finishList(now)
            warpBlock = false
            return
        }
        if (listOpen && !isListLine(line)) finishList(now)
        if (!mayConcernParty(line)) {
            warpBlock = false
            return
        }
        for (shape in shapes) {
            val m = shape.regex.matchEntire(line) ?: continue
            lineCounts[shape.kind] = (lineCounts[shape.kind] ?: 0) + 1
            lastKind = shape.kind
            shape.handle(m, now)
            return
        }
        warpBlock = false
        if (line.contains("party", ignoreCase = true) && !MessageDetector.isPlayerMessage(line)) {
            unknownLines++
            if (unknownSamples.size == UNKNOWN_SAMPLES) unknownSamples.removeFirst()
            unknownSamples.addLast(line.take(160))
            ambiguous(now, "unknown_line")
        }
    }

    private fun buildShapes(): List<Shape> {
        val a = WHO
        val b = WHO
        val list = ArrayList<Shape>()

        fun shape(kind: String, pattern: String, handle: (MatchResult, Long) -> Unit) {
            list += Shape(kind, Regex(pattern), handle)
        }

        fun ignored(kind: String, pattern: String) = shape(kind, pattern) { _, _ -> }

        // `/party list`: group 1/2 = display/name of the first player, 3/4 of the second
        shape("list_header", """Party Members \(\d+\)""") { _, now -> startList(now) }
        shape("list_leader", """Party Leader: (.+)""") { m, _ -> if (listOpen) listEntries(m.groupValues[1], PartyRole.LEADER) }
        shape("list_moderators", """Party Moderators: ?(.*)""") { m, _ -> if (listOpen) listEntries(m.groupValues[1], PartyRole.MODERATOR) }
        shape("list_members", """Party Members: ?(.*)""") { m, _ -> if (listOpen) listEntries(m.groupValues[1], PartyRole.MEMBER) }

        // joining
        shape("you_joined", """You have joined $a's? party!""") { m, now -> youJoined(m.groupValues[1], m.groupValues[2], now) }
        shape("partying_with", """You'll be partying with: (.+)""") { m, now -> partyingWith(m.groupValues[1], now) }
        shape("finder_joined", """Party Finder > $a joined the (?:dungeon )?group! \(.+\)""") { m, now -> finderJoined(m.groupValues[1], m.groupValues[2], now) }
        shape("member_joined", """$a joined the party\.""") { m, now -> memberJoined(m.groupValues[1], m.groupValues[2], now) }
        shape("public_party", """Created a public party! Players can join with /party join [A-Za-z0-9_]{1,16}""") { _, now -> selfLeads(now) }

        // leaving
        shape("you_left", """You left the party\.""") { _, now -> selfGone(now, PartyDisbandReason.LEFT_PARTY) }
        shape("member_left", """$a has left the party\.""") { m, now -> memberGone(m.groupValues[2], kicked = false, now = now) }
        shape("member_removed", """$a has been removed from the party\.""") { m, now -> memberGone(m.groupValues[2], kicked = true, now = now) }
        shape("member_removed_disconnected", """$a was removed from your party because they disconnected\.""") { m, now -> memberGone(m.groupValues[2], kicked = false, now = now) }
        shape("kicked_offline", """Kicked (.+) because they were offline\.""") { m, now ->
            for (entry in m.groupValues[1].split(", ", " and ")) entryName(entry)?.let { memberGone(it, kicked = true, now = now) }
        }
        shape("you_kicked", """You have been kicked from the party by $a""") { m, now ->
            val me = selfName ?: "You"
            clear(now, PartySource.CHAT) { prev -> listOf(PartyEvent.MemberKicked(me, m.groupValues[2], null), PartyEvent.PartyDisbanded(prev, PartyDisbandReason.KICKED)) }
        }
        shape("disbanded_by", """$a has disbanded the party!""") { _, now -> disbanded(now, PartyDisbandReason.LEADER_DISBANDED) }
        shape("disbanded_empty", """The party was disbanded because all invites expired and the party was empty\.""") { _, now -> disbanded(now, PartyDisbandReason.EMPTY_OR_EXPIRED) }
        shape("disbanded_leader_disconnected", """The party was disbanded because the party leader disconnected\.""") { _, now -> disbanded(now, PartyDisbandReason.LEADER_DISCONNECTED) }
        shape("disbanded_other", """The party was disbanded because .+""") { _, now -> disbanded(now, PartyDisbandReason.UNKNOWN) }

        // offline for up to five minutes: still members
        shape("member_disconnected", """$a has disconnected, they have \d+ minutes? to rejoin before they are removed from the party\.""") { m, now -> evidenceMember(m.groupValues[1], m.groupValues[2], now) }
        shape("member_rejoined", """$a has rejoined\.""") { m, now -> evidenceMember(m.groupValues[1], m.groupValues[2], now) }
        shape("leader_disconnected", """The party leader, $a has disconnected, they have \d+ minutes? to rejoin before the party is disbanded\.""") { m, now -> evidenceLeader(m.groupValues[1], m.groupValues[2], now) }
        shape("leader_rejoined", """The party leader $a has rejoined\.""") { m, now -> evidenceLeader(m.groupValues[1], m.groupValues[2], now) }

        // leader and moderators
        shape("transferred_left", """The party was transferred to $a because $b left""") { m, now ->
            val gone = m.groupValues[4]
            if (isSelf(gone.lowercase())) {
                selfGone(now, PartyDisbandReason.LEFT_PARTY)
            } else {
                memberGone(gone, kicked = false, now = now, check = false)
                newLeader(m.groupValues[1], m.groupValues[2], now, explained = true)
            }
        }
        shape("transferred_by", """The party was transferred to $a by $b""") { m, now ->
            newLeader(m.groupValues[1], m.groupValues[2], now, explained = true, formerDisplay = m.groupValues[3], former = m.groupValues[4])
        }
        shape("promoted_leader", """$a has promoted $b to Party Leader""") { m, now ->
            newLeader(m.groupValues[3], m.groupValues[4], now, explained = true, formerDisplay = m.groupValues[1], former = m.groupValues[2])
        }
        shape("promoted_moderator", """$a has promoted $b to Party Moderator""") { m, now -> newRole(m.groupValues[3], m.groupValues[4], PartyRole.MODERATOR, now) }
        shape("demoted_member", """$a has demoted $b to Party Member""") { m, now -> newRole(m.groupValues[3], m.groupValues[4], PartyRole.MEMBER, now) }
        shape("now_moderator", """$a is now a Party Moderator""") { m, now -> newRole(m.groupValues[1], m.groupValues[2], PartyRole.MODERATOR, now) }

        // invites
        shape("invite_received", """$a has invited you to join their party!""") { m, now -> inviteReceived(m.groupValues[2], now) }
        shape("invite_received_other", """$a has invited you to join $b's? party!""") { m, now -> inviteReceived(m.groupValues[2], now) }
        shape("invite_sent", """$a invited $b to the party! They have \d+ seconds to accept\.""") { m, now -> inviteSent(m.groupValues[1], m.groupValues[2], m.groupValues[4], now) }
        shape("invite_expired_from", """The party invite from $a has expired\.""") { m, now -> inviteExpired(m.groupValues[2], now) }
        shape("invite_expired_to", """The party invite to $a has expired\.""") { m, now -> inviteExpired(m.groupValues[2], now) }
        shape("already_member", """$a is already in the party\.""") { m, now -> evidenceMember(m.groupValues[1], m.groupValues[2], now) }

        // warps
        shape("summoned", """Party Leader, $a, summoned you to their server\.""") { m, now -> evidenceLeader(m.groupValues[1], m.groupValues[2], now) }
        shape("you_summoned", """You summoned $a to your server\.""") { m, now -> evidenceMember(m.groupValues[1], m.groupValues[2], now) }
        shape("warp_title", """SkyBlock Party Warp \(\d+ players?\)""") { _, now ->
            evidenceInParty(now)
            warpBlock = true
        }
        shape("warp_result", """[✔⚔✮✖] $a (?:warped to your (?:server|instance)|is on your server|is offline|is not allowed on your server!|didn't warp correctly!)""") { m, now ->
            if (warpBlock) evidenceMember(m.groupValues[1], m.groupValues[2], now)
        }

        // in a party, or not
        shape("not_leader", """You are not this party's leader[!.]""") { _, now -> notLeader(now) }
        shape("not_allowed_invite", """You are not allowed to invite players\.""") { _, now -> notLeader(now) }
        shape("already_in_party", """(?:Party Finder > )?You are already in a party!.*""") { _, now -> evidenceInParty(now) }
        shape("party_channel", """You are now in the PARTY channel""") { _, now -> evidenceInParty(now) }
        shape("not_in_party", """You are not (?:currently )?in a party(?: right now)?[.!]""") { _, now -> notInParty(now) }
        shape("not_in_party_moved", """You are not in a party and were moved to the ALL channel\.""") { _, now -> notInParty(now) }
        shape("not_in_party_channel", """You must be in a party to join the party channel!""") { _, now -> notInParty(now) }
        shape("party_chat", """Party > $a: .*""") { m, now -> evidenceMember(m.groupValues[1], m.groupValues[2], now) }

        // nothing about the roster in them
        ignored("finder_info", """Party Finder > .*""")
        ignored("attempting_add", """Attempting to add you to the party\.\.\.""")
        ignored("queueing", """Queueing your party\.\.\.""")
        ignored("queue_position", """The party is in position #\d+ of the queue!""")
        ignored("warping", """Warping party\.\.\.""")
        ignored("warp_error", """An error occurred while warping the party!""")
        ignored("invite_offline", """You cannot invite that player since they're not online\.""")
        ignored("invite_pending", """$a has already been invited to the party\.""")
        ignored("invite_missing", """You don't have an invite to that player's party\.""")
        ignored("not_in_your_party", """That player is not in your party!""")
        ignored("transfer_self", """You can't transfer the party to yourself!""")
        ignored("party_capped", """Party is capped at \d+ players\.""")
        ignored("leader_only", """Only the party leader can .+""")
        ignored("all_invite", """$a (?:enabled|disabled) All Invite""")
        ignored("party_split", """Your party was split for balancing purposes\.""")
        return list
    }

    // ------------------------------------------------------------------ handlers

    private fun youJoined(display: String, leader: String, now: Long) {
        state?.let { emit(PartyEvent.PartyLeft(it, PartyDisbandReason.LEFT_PARTY)) }
        resetRoster(now)
        inParty = true
        put(leader, display, PartyRole.LEADER)
        leaderKey = leader.lowercase()
        selfName?.let { if (!isSelf(leaderKey)) put(it, it, PartyRole.MEMBER) }
        invites.clear()
        publish(now, PartySource.CHAT)
        emit(PartyEvent.PartyJoined(state!!, joinedAsLeader = isSelf(leaderKey)))
        verify(now, "you_joined")
    }

    private fun partyingWith(entries: String, now: Long) {
        evidenceInParty(now)
        for (entry in entries.split(", ")) {
            val display = entry.trim()
            val name = entryName(display) ?: continue
            if (members.containsKey(name.lowercase())) continue
            put(name, display, PartyRole.MEMBER)
            consumeUnnamed(name)
        }
        publish(now, PartySource.CHAT)
        verify(now, "partying_with")
    }

    private fun finderJoined(display: String, name: String, now: Long) {
        if (!isSelf(name.lowercase())) {
            memberJoined(display, name, now)
            return
        }
        // Joined someone's group through the finder: no "You have joined" line, no leader, no names.
        state?.let { emit(PartyEvent.PartyLeft(it, PartyDisbandReason.LEFT_PARTY)) }
        startUnknownParty(now)
        publish(now, PartySource.CHAT)
        emit(PartyEvent.PartyJoined(state!!, joinedAsLeader = false))
        ambiguous(now, "finder_joined")
    }

    private fun memberJoined(display: String, name: String, now: Long) {
        clearInvitesInvolving(name)
        val wasIn = inParty
        evidenceInParty(now)
        val key = name.lowercase()
        if (members.containsKey(key)) return
        val member = put(name, display, PartyRole.MEMBER)
        // A packet just before the line may already count the newcomer among its unnamed uuids.
        if (now - lastInfoAt <= PACKET_RACE_MS) consumeUnnamed(name)
        publish(now, PartySource.CHAT)
        emit(PartyEvent.MemberJoined(member, state!!))
        if (wasIn) verify(now, "member_joined")
    }

    private fun memberGone(name: String, kicked: Boolean, now: Long, check: Boolean = true) {
        if (!inParty) {
            if (check) ambiguous(now, "member_gone_unknown_party")
            return
        }
        val key = name.lowercase()
        if (isSelf(key)) {
            selfGone(now, if (kicked) PartyDisbandReason.KICKED else PartyDisbandReason.LEFT_PARTY)
            return
        }
        val removed = members.remove(key)
        if (removed == null) {
            // Not named, so one of the unnamed uuids, unless a packet just before already dropped it.
            val u = uuidByName[key]
            if (u != null) unnamed.remove(u) else if (unnamed.isNotEmpty() && now - lastInfoAt > PACKET_RACE_MS) unnamed.remove(unnamed.first())
        }
        if (leaderKey == key) leaderKey = null
        publish(now, PartySource.CHAT)
        if (removed != null) {
            emit(if (kicked) PartyEvent.MemberKicked(removed.name, null, state) else PartyEvent.MemberLeft(removed.name, state))
        }
        if (check) verify(now, if (kicked) "member_removed" else "member_left")
    }

    private fun selfGone(now: Long, reason: PartyDisbandReason) {
        clear(now, PartySource.CHAT) { prev -> listOf(PartyEvent.PartyLeft(prev, reason), PartyEvent.PartyDisbanded(prev, reason)) }
    }

    private fun disbanded(now: Long, reason: PartyDisbandReason) {
        clear(now, PartySource.CHAT) { prev -> listOf(PartyEvent.PartyDisbanded(prev, reason)) }
    }

    /** [explained]: a transfer or promotion line, so only confirm; otherwise the leader was a surprise. */
    private fun newLeader(display: String, name: String, now: Long, explained: Boolean, formerDisplay: String? = null, former: String? = null) {
        val created = !inParty
        evidenceInParty(now)
        val key = name.lowercase()
        val previousKey = leaderKey
        if (previousKey != key || members[key]?.role != PartyRole.LEADER) {
            if (previousKey != null && previousKey != key) demote(previousKey)
            val existing = members[key]
            val promoted = (existing ?: PartyMember(name, display, PartyRole.MEMBER, uuidByName[key])).copy(role = PartyRole.LEADER, displayName = display)
            if (existing == null) consumeUnnamed(name)
            members[key] = promoted
            leaderKey = key
            emit(PartyEvent.RoleChanged(promoted, existing?.role ?: PartyRole.MEMBER, PartyRole.LEADER))
        }
        if (former != null && formerDisplay != null) {
            val formerKey = former.lowercase()
            if (formerKey != key && formerKey != previousKey) {
                if (members.containsKey(formerKey)) demote(formerKey) else {
                    put(former, formerDisplay, PartyRole.MODERATOR)
                    consumeUnnamed(former)
                }
            }
        }
        publish(now, PartySource.CHAT)
        if (explained && !created) verify(now, "leader_changed") else ambiguous(now, "unknown_leader")
    }

    private fun demote(key: String) {
        val old = members[key] ?: return
        if (old.role == PartyRole.MODERATOR) return
        val demoted = old.copy(role = PartyRole.MODERATOR)
        members[key] = demoted
        emit(PartyEvent.RoleChanged(demoted, old.role, PartyRole.MODERATOR))
    }

    private fun newRole(display: String, name: String, role: PartyRole, now: Long) {
        evidenceInParty(now)
        val key = name.lowercase()
        val existing = members[key]
        if (existing?.role == role) return
        val updated = (existing ?: PartyMember(name, display, PartyRole.MEMBER, uuidByName[key])).copy(role = role)
        if (existing == null) consumeUnnamed(name)
        members[key] = updated
        if (leaderKey == key) leaderKey = null
        publish(now, PartySource.CHAT)
        emit(PartyEvent.RoleChanged(updated, existing?.role ?: PartyRole.MEMBER, role))
        verify(now, "role_changed")
    }

    private fun selfLeads(now: Long) {
        val me = selfName ?: return
        if (inParty) return
        startUnknownParty(now)
        members[me.lowercase()] = members.getValue(me.lowercase()).copy(role = PartyRole.LEADER)
        leaderKey = me.lowercase()
        publish(now, PartySource.CHAT)
        emit(PartyEvent.PartyJoined(state!!, joinedAsLeader = true))
        verify(now, "self_leads")
    }

    private fun inviteReceived(from: String, now: Long) {
        val invite = PartyInvite(from, selfName ?: return, outgoing = false, createdAt = now, expiresAt = now + INVITE_LIFETIME_MS)
        invites += invite
        emit(PartyEvent.InviteReceived(invite))
    }

    private fun inviteSent(fromDisplay: String, from: String, to: String, now: Long) {
        val mine = isSelf(from.lowercase())
        val invite = PartyInvite(from, to, outgoing = mine, createdAt = now, expiresAt = now + INVITE_LIFETIME_MS)
        invites += invite
        if (mine && !inParty) {
            // Inviting from outside a party creates one led by the inviter; inside one, roles stay.
            selfLeads(now)
        } else {
            evidenceMember(fromDisplay, from, now)
        }
        emit(PartyEvent.InviteSent(invite))
    }

    private fun inviteExpired(name: String, now: Long) {
        val it = invites.iterator()
        while (it.hasNext()) {
            val invite = it.next()
            if (invite.from.equals(name, ignoreCase = true) || invite.to.equals(name, ignoreCase = true)) {
                it.remove()
                emit(PartyEvent.InviteExpired(invite))
            }
        }
        if (inParty) verify(now, "invite_expired")
    }

    private fun notLeader(now: Long) {
        evidenceInParty(now)
        val key = leaderKey
        if (!isSelf(key)) return
        val me = members.getValue(key!!)
        members[key] = me.copy(role = PartyRole.MEMBER)
        leaderKey = null
        publish(now, PartySource.CHAT)
        emit(PartyEvent.RoleChanged(members.getValue(key), PartyRole.LEADER, PartyRole.MEMBER))
        ambiguous(now, "not_leader")
    }

    private fun notInParty(now: Long) {
        if (!inParty) {
            uncertain = false
            return
        }
        clear(now, PartySource.CHAT) { prev -> listOf(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.UNKNOWN)) }
    }

    /** A line only a party member gets; outside a tracked party it starts one with unknown roster. */
    private fun evidenceInParty(now: Long) {
        if (inParty) return
        startUnknownParty(now)
        publish(now, PartySource.CHAT)
        emit(PartyEvent.PartyJoined(state!!, joinedAsLeader = false))
        ambiguous(now, "in_party_evidence")
    }

    private fun evidenceMember(display: String, name: String, now: Long) {
        evidenceInParty(now)
        val key = name.lowercase()
        if (members.containsKey(key)) return
        val member = put(name, display, PartyRole.MEMBER)
        consumeUnnamed(name)
        publish(now, PartySource.CHAT)
        emit(PartyEvent.MemberJoined(member, state!!))
        ambiguous(now, "unknown_member")
    }

    private fun evidenceLeader(display: String, name: String, now: Long) {
        if (inParty && leaderKey == name.lowercase()) return
        newLeader(display, name, now, explained = false)
    }

    // ------------------------------------------------------------------ /party list

    private fun startList(now: Long) {
        if (listOpen) finishList(now)
        listOpen = true
        listLeader = null
        listMembers.clear()
    }

    private fun listEntries(section: String, role: PartyRole) {
        for (entry in section.split(LIST_SPLIT)) {
            for (part in entry.split(", ")) {
                val display = part.trim()
                if (display.isEmpty()) continue
                val name = entryName(display) ?: continue
                val key = name.lowercase()
                if (listMembers.containsKey(key)) continue
                val member = PartyMember(name, display, role, uuidByName[key])
                listMembers[key] = member
                if (role == PartyRole.LEADER) listLeader = member
            }
        }
    }

    private fun finishList(now: Long) {
        listOpen = false
        val leader = listLeader ?: return
        listsParsed++
        countEvent("party_list")
        val before = snapshotKey()
        val wasIn = inParty
        val previous = state
        val initial = source == PartySource.NONE
        if (!wasIn) createdAt = now
        inParty = true
        members.clear()
        members.putAll(listMembers)
        unnamed.clear()
        leaderKey = leader.name.lowercase()
        listMembers.clear()
        listLeader = null
        lastInfoRoles?.takeIf { now - lastInfoAt <= INFER_WINDOW_MS && it.size == members.size }?.let { roles ->
            val open = roles.keys.filterTo(LinkedHashSet()) { it !in nameByUuid }
            pairByRole(open, members.values.filter { it.name.lowercase() !in uuidByName }, roles)
            for (key in members.keys.toList()) uuidByName[key]?.let { u -> members[key] = members.getValue(key).copy(uuid = u) }
        }
        val after = snapshotKey()
        val changed = before != after
        if (!initial) {
            if (!wasIn) correction(PartySource.PARTY_LIST, "in_party") else if (changed) diffCorrections(PartySource.PARTY_LIST, before, after)
        }
        uncertain = false
        listDueAt = -1L
        publish(now, PartySource.PARTY_LIST)
        val current = state!!
        if (!wasIn) emit(PartyEvent.PartyJoined(current, joinedAsLeader = isSelf(leaderKey)))
        emit(PartyEvent.PartySyncedFromList(current))
        if (wasIn && changed) emit(PartyEvent.PartyCorrected(previous, current))
    }

    // ------------------------------------------------------------------ helpers

    private fun startUnknownParty(now: Long) {
        resetRoster(now)
        inParty = true
        uncertain = true
        selfName?.let { put(it, it, PartyRole.MEMBER) }
    }

    private fun resetRoster(now: Long) {
        members.clear()
        unnamed.clear()
        leaderKey = null
        createdAt = now
        listDueAt = -1L
    }

    private fun put(name: String, display: String, role: PartyRole): PartyMember {
        val key = name.lowercase()
        val member = PartyMember(name, display, role, uuidByName[key])
        members[key] = member
        return member
    }

    /** A name chat adds is most likely one of the uuids the last packet could not name. */
    private fun consumeUnnamed(name: String) {
        if (unnamed.isEmpty()) return
        val known = uuidByName[name.lowercase()]
        if (known != null) {
            unnamed.remove(known)
            return
        }
        val first = unnamed.first()
        if (unnamed.size == 1) learn(name, first)
        unnamed.remove(first)
    }

    /** Learns a uuid for a name where one role (or the whole remainder) leaves exactly one of each. */
    private fun pairByRole(open: Set<UUID>, leftover: List<PartyMember>, roles: Map<UUID, PartyRole>) {
        for (role in PartyRole.entries) {
            val uuids = open.filter { roles[it] == role && it !in nameByUuid }
            val names = leftover.filter { it.role == role && it.name.lowercase() !in uuidByName }
            if (uuids.size == 1 && names.size == 1) learn(names[0].name, uuids[0])
        }
        val uuids = open.filter { it !in nameByUuid }
        val names = leftover.filter { it.name.lowercase() !in uuidByName }
        if (uuids.size == 1 && names.size == 1) learn(names[0].name, uuids[0])
    }

    private fun learn(name: String, uuid: UUID) {
        val key = name.lowercase()
        nameByUuid.remove(uuidByName[key])
        nameByUuid[uuid]?.let { uuidByName.remove(it.lowercase()) }
        uuidByName[key] = uuid
        nameByUuid[uuid] = name
    }

    private fun clear(now: Long, by: PartySource, events: (PartyState) -> List<PartyEvent>) {
        val previous = state
        inParty = false
        members.clear()
        unnamed.clear()
        leaderKey = null
        listDueAt = -1L
        uncertain = false
        state = null
        touch(now, by)
        if (by == PartySource.CHAT && previous != null) verify(now, "left_party")
        if (previous != null) events(previous).forEach(emit)
    }

    private fun publish(now: Long, by: PartySource) {
        state = if (!inParty) {
            null
        } else {
            PartyState(
                leader = leaderKey?.let { members[it] },
                members = LinkedHashMap(members),
                createdAt = createdAt,
                lastUpdatedAt = now,
                unnamedMembers = LinkedHashSet(unnamed),
            )
        }
        touch(now, by)
    }

    private fun touch(now: Long, by: PartySource) {
        source = by
        updatedAt = now
    }

    /** A change a line explains: confirm it with the mod API shortly after. */
    private fun verify(now: Long, reason: String) = requestInfo(now, VERIFY_DELAY_MS, reason)

    /** A line contradicts the state or cannot fully explain it: ask the mod API soon. */
    private fun ambiguous(now: Long, reason: String) {
        uncertain = true
        requestInfo(now, AMBIGUOUS_DELAY_MS, reason)
    }

    private fun requestInfo(now: Long, delay: Long, reason: String) {
        requestReasons[reason] = (requestReasons[reason] ?: 0) + 1
        infoReasonAt = now
        val due = maxOf(now + delay, lastInfoSentAt + INFO_FLOOR_MS)
        if (infoDueAt < 0 || due < infoDueAt) infoDueAt = due
    }

    private fun requestList(now: Long) {
        if (listDueAt >= 0) return
        listDueAt = maxOf(now, lastListSentAt + LIST_FLOOR_MS)
    }

    private fun needsNames(): Boolean = inParty && (unnamed.isNotEmpty() || leaderKey == null)

    private fun isSelf(key: String?): Boolean = key != null && selfName?.lowercase() == key

    private fun countEvent(kind: String) {
        eventCounts[kind] = (eventCounts[kind] ?: 0) + 1
    }

    private fun correction(by: PartySource, field: String) {
        val key = "${by.name.lowercase()}.$field"
        corrections[key] = (corrections[key] ?: 0) + 1
    }

    private fun snapshotKey(): List<Any?> =
        listOf(inParty, members.size + unnamed.size, leaderKey, members.keys.toSortedSet(), members.values.associate { it.name.lowercase() to it.role })

    private fun diffCorrections(by: PartySource, before: List<Any?>, after: List<Any?>) {
        if (before[1] != after[1]) correction(by, "size")
        if (before[2] != after[2]) correction(by, "leader")
        if (before[3] != after[3]) correction(by, "names") else if (before[4] != after[4]) correction(by, "roles")
    }

    private fun clearInvitesInvolving(name: String) {
        invites.removeAll { it.from.equals(name, ignoreCase = true) || it.to.equals(name, ignoreCase = true) }
    }

    private fun pruneInvites(now: Long) {
        if (invites.isEmpty()) return
        val it = invites.iterator()
        while (it.hasNext()) {
            val invite = it.next()
            if (invite.expiresAt <= now) {
                it.remove()
                emit(PartyEvent.InviteExpired(invite))
            }
        }
    }

    companion object {
        const val INFO_FLOOR_MS = 5_000L
        const val VERIFY_DELAY_MS = 1_500L
        const val AMBIGUOUS_DELAY_MS = 500L
        const val JOIN_DELAY_MS = 2_000L
        const val INFO_TIMEOUT_MS = 5_000L
        const val LIST_FLOOR_MS = 60_000L
        const val ECHO_WINDOW_MS = 5_000L
        const val INVITE_LIFETIME_MS = 60_000L

        /** A packet this long after a request's reason already reflects it. */
        private const val SATISFIES_AFTER_MS = 250L

        /** A packet this recent may already include the join or leave a chat line reports. */
        private const val PACKET_RACE_MS = 3_000L

        /** A `/party list` and a packet this close apart describe the same roster. */
        private const val INFER_WINDOW_MS = 10_000L
        private const val UNKNOWN_SAMPLES = 5

        /** `[RANK] Name` or `Name`: display, then name. */
        private const val WHO = """((?:\[[^\]]+] )?([A-Za-z0-9_]{1,16}))"""
        private val LIST_SPLIT = Regex("""\s*[●○]\s*""")
        private val ENTRY = Regex("""(?:\[[^\]]+]\s*)*([A-Za-z0-9_]{1,16})""")
        private val ECHO = Regex("""Party Members \(\d+\)|Party (?:Leader|Moderators|Members): .*|You are not (?:currently )?in a party(?: right now)?[.!]""")

        internal fun isDivider(line: String): Boolean = line.length >= 5 && line.all { it == '-' || it == '▬' }

        private fun isListLine(line: String): Boolean =
            line.startsWith("Party Leader:") || line.startsWith("Party Moderators:") || line.startsWith("Party Members:")

        private fun isEchoLine(line: String): Boolean = line.isEmpty() || isDivider(line) || ECHO.matches(line)

        /** Cheap gate before the regexes: every handled shape contains one of these. */
        private fun mayConcernParty(line: String): Boolean =
            line.contains("party", ignoreCase = true) || line.contains("rejoined") || line.contains("Kicked ") ||
                line.contains("summoned") || line.contains("invite", ignoreCase = true) || line.startsWith("✔ ") ||
                line.startsWith("⚔ ") || line.startsWith("✮ ") || line.startsWith("✖ ")

        internal fun entryName(display: String): String? = ENTRY.matchEntire(display.trim())?.groupValues?.get(1)
    }
}
