package com.soulreturns.features.party

import com.soulreturns.features.party.PartyManager.PartyRole
import java.util.UUID

/**
 * Reads the `party/` fixtures (format in the header of `party/sessions.txt`) and replays them through a
 * [PartyReplay.Subject], checking it against what each session proves at that moment: the mod-API
 * packet, a `/party list` header and every "You are not in a party" reply.
 */
object PartyReplay {
    const val SELF = "Alice"
    private const val GRACE_MS = 5_000L

    fun uuidOf(name: String): UUID = UUID.nameUUIDFromBytes("party-fixture:$name".toByteArray())

    class Event(val t: Long, val kind: String, val text: String, val roles: Map<String, PartyRole>?)

    class Session(val id: String, val events: List<Event>)

    data class View(
        val inParty: Boolean,
        val size: Int,
        val selfLeader: Boolean,
        val leader: String? = null,
        val members: List<String> = emptyList(),
    )

    interface Subject {
        fun chat(text: String, t: Long)

        fun info(inParty: Boolean, roles: Map<UUID, PartyRole>, t: Long) {}

        fun join(t: Long) {}

        fun disconnect() {}

        fun view(): View
    }

    /** The real tracker; packets only go in with [feedInfo] (otherwise chat alone is measured). */
    class TrackerSubject(private val feedInfo: Boolean) : Subject {
        val tracker = PartyTracker().apply {
            selfName = SELF
            selfUuid = uuidOf(SELF)
            onHypixel = true
        }

        override fun chat(text: String, t: Long) = tracker.onChat(text, t)

        override fun info(inParty: Boolean, roles: Map<UUID, PartyRole>, t: Long) {
            if (feedInfo) tracker.onPartyInfo(inParty, roles, mapOf(uuidOf(SELF) to SELF), t)
        }

        override fun join(t: Long) = tracker.onJoin(t)

        override fun disconnect() = tracker.onDisconnect()

        override fun view(): View {
            val s = tracker.state ?: return View(false, 0, false)
            val leader = s.leader?.name
            return View(true, s.size, leader.equals(SELF, ignoreCase = true), leader, s.members.values.map { it.name })
        }
    }

    /** Plays a `sequence` and returns every `expect` line the subject does not meet. */
    fun expectFailures(session: Session, s: Subject): List<String> {
        val out = ArrayList<String>()
        for (e in session.events) {
            when (e.kind) {
                "connect" -> connect(s, e.t)
                "world" -> s.join(e.t)
                "info" -> s.info(e.roles != null, e.roles.orEmpty().mapKeys { uuidOf(it.key) }, e.t)
                "chat" -> s.chat(e.text, e.t)
                "expect" -> {
                    val v = s.view()
                    for (claim in e.text.split(' ').filter { it.isNotBlank() }) {
                        val key = claim.substringBefore('=')
                        val want = claim.substringAfter('=')
                        val ok = when (key) {
                            "in" -> v.inParty == (want == "yes")
                            "size" -> v.size == want.toInt()
                            "leader" -> if (want == "none") v.leader == null else v.leader.equals(want, ignoreCase = true)
                            "members" -> v.members.map { it.lowercase() }.sorted() == want.split(',').map { it.lowercase() }.sorted()
                            else -> error("unknown claim $claim")
                        }
                        if (!ok) out += "${session.id} @${e.t / 1000}s $claim but got $v"
                    }
                }
            }
        }
        return out
    }

    fun load(resource: String): List<Session> {
        val text = PartyReplay::class.java.getResource(resource)!!.readText()
        val sessions = ArrayList<Session>()
        var id: String? = null
        var events = ArrayList<Event>()
        for (line in text.lines()) {
            if (line.isBlank() || line.startsWith("#")) continue
            if (line.startsWith("session ") || line.startsWith("sequence ")) {
                id?.let { sessions += Session(it, events) }
                id = line.substringAfter(' ').trim()
                events = ArrayList()
                continue
            }
            val t = (line.substringBefore(' ').toDouble() * 1000).toLong()
            val rest = line.substringAfter(' ')
            val kind = rest.substringBefore(' ')
            val body = if (rest.contains(' ')) rest.substringAfter(' ') else ""
            events += when (kind) {
                "chat" -> Event(t, kind, body.replace("\\n", "\n"), null)
                "info" -> Event(t, kind, body, if (body == "out") null else parseRoles(body.removePrefix("in").trim()))
                else -> Event(t, kind, body, null)
            }
        }
        id?.let { sessions += Session(it, events) }
        return sessions
    }

    private fun parseRoles(body: String): Map<String, PartyRole> =
        body.split(' ').filter { it.isNotBlank() }.associate {
            val name = it.substringBeforeLast(':')
            val role = it.substringAfterLast(':')
            name to if (role == "LEADER") PartyRole.LEADER else if (role == "MOD") PartyRole.MODERATOR else PartyRole.MEMBER
        }

    fun truthOf(event: Event): View {
        val roles = event.roles ?: return View(false, 0, false)
        return View(true, roles.size, roles[SELF] == PartyRole.LEADER)
    }

    class Result {
        var checks = 0
        var ok = 0
        var settling = 0
        val failures = LinkedHashMap<String, Int>()
        var episodes = 0
        var ghostReplies = 0
        var notInPartyReplies = 0
        var listChecks = 0
        var listMismatches = 0
        val details = ArrayList<String>()

        fun fail(field: String) {
            failures[field] = (failures[field] ?: 0) + 1
        }

        override fun toString(): String =
            "packets $checks (+$settling settling): ok $ok, wrong $failures in $episodes episodes; " +
                "'not in a party' replies $notInPartyReplies, $ghostReplies while tracked in a party; " +
                "/party list headers $listChecks, $listMismatches disagreeing with the tracked state"
    }

    private val NOT_IN_PARTY = Regex("""You are not (?:currently )?in a party(?: right now)?[.!]|You are not in a party and were moved to the ALL channel\.""")
    private val LIST_HEADER = Regex("""Party Members \((\d+)\)""")

    private fun wrong(got: View, truth: View): List<String> {
        if (got.inParty != truth.inParty) return listOf("in_party")
        if (!truth.inParty) return emptyList()
        val out = ArrayList<String>()
        if (got.size != truth.size) out += "size"
        if (got.selfLeader != truth.selfLeader) out += "self_leader"
        return out
    }

    /** Logs mark no disconnects: a new connection means the previous one ended. */
    private fun connect(s: Subject, t: Long) {
        s.disconnect()
        s.join(t)
    }

    /**
     * [grace]: a packet also counts as matched when the state agrees within 5 s after it (chat lines
     * that explain it can follow it); only meaningful when packets are not fed in. Checks in the first
     * 10 s of a connection are settling, not drift: nothing has said anything about the party yet.
     */
    fun run(sessions: List<Session>, grace: Boolean, subject: () -> Subject): Result {
        val r = Result()
        for (session in sessions) {
            val s = subject()
            var connectedAt = Long.MIN_VALUE / 2
            var pendingTruth: View? = null
            var pendingAt = 0L
            var pendingMatched = false
            var pendingGot: View? = null
            var inEpisode = false
            val recent = ArrayDeque<String>()

            fun close() {
                val truth = pendingTruth ?: return
                pendingTruth = null
                if (pendingAt - connectedAt < SETTLE_MS) {
                    r.settling++
                    return
                }
                r.checks++
                val got = if (grace) s.view() else pendingGot!!
                val bad = if (pendingMatched) emptyList() else wrong(got, truth)
                if (bad.isEmpty()) {
                    r.ok++
                    inEpisode = false
                } else {
                    bad.forEach(r::fail)
                    if (!inEpisode) {
                        r.episodes++
                        if (r.details.size < MAX_DETAILS) r.details += "${session.id} @${pendingAt / 1000}s got $got truth $truth after ${recent.toList()}"
                    }
                    inEpisode = true
                }
            }

            for (e in session.events) {
                if (pendingTruth != null && (e.t - pendingAt > GRACE_MS || e.kind == "info" || e.kind == "connect")) close()
                when (e.kind) {
                    "connect" -> {
                        connect(s, e.t)
                        connectedAt = e.t
                        recent.clear()
                    }
                    "world" -> s.join(e.t)
                    "info" -> {
                        val truth = truthOf(e)
                        val got = s.view()
                        pendingTruth = truth
                        pendingAt = e.t
                        pendingGot = got
                        pendingMatched = wrong(got, truth).isEmpty()
                        s.info(e.roles != null, e.roles.orEmpty().mapKeys { uuidOf(it.key) }, e.t)
                        continue
                    }
                    "chat" -> {
                        val settled = e.t - connectedAt >= SETTLE_MS
                        for (line in e.text.split('\n')) {
                            val clean = line.trim()
                            if (NOT_IN_PARTY.matches(clean) && settled) {
                                r.notInPartyReplies++
                                if (s.view().inParty) r.ghostReplies++
                            }
                            if (settled) LIST_HEADER.matchEntire(clean)?.let { m ->
                                r.listChecks++
                                val v = s.view()
                                if (!v.inParty || v.size != m.groupValues[1].toInt()) r.listMismatches++
                            }
                        }
                        s.chat(e.text, e.t)
                        val shown = e.text.replace("\n", "⏎")
                        if (!shown.all { it == '-' }) {
                            if (recent.size == 4) recent.removeFirst()
                            recent.addLast("${e.t / 1000}:${shown.take(90)}")
                        }
                    }
                }
                if (grace && pendingTruth != null && !pendingMatched && wrong(s.view(), pendingTruth!!).isEmpty()) pendingMatched = true
            }
            close()
        }
        return r
    }

    private const val SETTLE_MS = 10_000L
    private const val MAX_DETAILS = 60
}
