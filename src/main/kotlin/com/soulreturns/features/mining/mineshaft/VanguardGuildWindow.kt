package com.soulreturns.features.mining.mineshaft

/**
 * The pure half of the Vanguard guild call: how Hypixel's guild chat and the collapse line look, what
 * counts as "me", and who may still be invited. No Minecraft classes, so [VanguardGuildWindowTest]
 * drives it directly; [VanguardGuildInvite] feeds it from the game.
 */
internal object VanguardGuildChat {
    /** What goes to guild chat when a shaft turns out to hold a Vanguard corpse. */
    const val ANNOUNCE = "Vanguard found! If you want to gamble say \"me\" for auto party and then use \"!warp\" when ready"

    /** A guild-chat message from a player: the sender's bare name and what they wrote. */
    data class GuildLine(val name: String, val text: String)

    /**
     * `Guild > [RANK] Name [GuildRank]: text`, the rank and the guild rank both optional (recorded:
     * `Guild > [MVP+] Name [Member]: …`, `Guild > [MVP+] Name: …`; an unranked player has no first
     * bracket). Anchored, so `Party >`, `Officer >`, private messages and public chat never match, and
     * a player quoting a guild line inside their own text cannot pass as another sender. Joins/leaves
     * (`Guild > Name left.`) have no colon and fall out. The name is read from the line's shape
     * (Minecraft names are ASCII letters, digits and `_`), never from a list of players.
     */
    private val GUILD = Regex("""^Guild > (?:\[[^\]]+\] )?(\w{1,16})(?: \[[^\]]+\])?: (.*)$""")

    /** `me` as a whole word, any case: `me`, `Me!`, `"me"`, `me pls`; not `home`, `meme`, `some`. */
    private val ME = Regex("""(?<![\p{L}\p{N}_])me(?![\p{L}\p{N}_])""", RegexOption.IGNORE_CASE)

    /** Hypixel's line when the shaft's entrance collapses: nobody can be warped in any more. */
    private val COLLAPSE = Regex("""mineshaft entrance has caved in""", RegexOption.IGNORE_CASE)

    /** [stripped] is a chat line without color codes. */
    fun parseGuild(stripped: String): GuildLine? =
        GUILD.matchEntire(stripped.trim())?.let { GuildLine(it.groupValues[1], it.groupValues[2]) }

    fun wantsIn(text: String): Boolean = ME.containsMatchIn(text)

    fun isCollapse(stripped: String): Boolean = COLLAPSE.containsMatchIn(stripped)

    /**
     * What to do about the announcement: [announce], or a [final] refusal with the [why], or neither (keep
     * waiting for the tab's Vanguard row). [source] is a [VisitSource] value.
     */
    data class Verdict(val announce: Boolean, val final: Boolean, val why: String)

    fun verdict(source: String, collapsed: Boolean, skipInParty: Boolean, partyAtEntry: Boolean, vanguardCorpses: Int): Verdict {
        if (source != VisitSource.DISCOVERED) return Verdict(false, true, "not announced: the shaft was not found by you ($source)")
        if (collapsed) return Verdict(false, true, "not announced: the entrance had already collapsed")
        if (skipInParty && partyAtEntry) return Verdict(false, true, "not announced: you were in a party when you entered")
        if (vanguardCorpses <= 0) return Verdict(false, false, "waiting for a Vanguard corpse")
        return Verdict(true, true, "announced")
    }

    /**
     * Our own announcement says `say "me"`, and so does another Soul user's: a line carrying it is the
     * call itself, never a reply to it.
     */
    fun isAnnouncement(text: String): Boolean = text.contains("Vanguard found!", ignoreCase = true)
}

/**
 * Who gets a `/p invite` during one shaft visit: from [begin] (the announcement went out) until [close]
 * (collapse, left the shaft, disconnect, setting off). Each player at most once, queued and handed out by
 * [nextInvite] no faster than [paceMs] so Hypixel's command throttle is never hit, never more than
 * [maxInvites] per shaft. Thread-safe: chat arrives on whatever thread the pump runs on, the queue is
 * drained on the client tick.
 */
internal class VanguardGuildWindow(
    private val paceMs: Long = PACE_MS,
    private val maxInvites: Int = MAX_INVITES,
) {
    companion object {
        /** Gap between two invite commands. */
        const val PACE_MS = 1500L

        /** A guild has up to 125 members; ten invites is plenty for one shaft and bounds a spam of "me". */
        const val MAX_INVITES = 10
    }

    var open = false
        private set
    var closeReason: String? = null
        private set

    /** Why the last guild line was not taken (debug); null until one was ignored. */
    var lastIgnored: String? = null
        private set

    private val accepted = LinkedHashMap<String, String>() // lowercase -> name as written
    private val queue = ArrayDeque<String>()
    private val sent = ArrayList<String>()
    private var lastSentAt = Long.MIN_VALUE

    /** Everyone taken this shaft, in order, and who of them already got the command. */
    @get:Synchronized
    val invited: List<String> get() = accepted.values.toList()

    @get:Synchronized
    val invitesSent: List<String> get() = sent.toList()

    @get:Synchronized
    val queued: List<String> get() = queue.toList()

    /** A fresh window for a new announcement: forgets the previous shaft's players. */
    @Synchronized
    fun begin() {
        reset()
        open = true
    }

    /** Back to nothing at all (a new visit starts): closed, no players, no reasons. */
    @Synchronized
    fun reset() {
        accepted.clear()
        queue.clear()
        sent.clear()
        lastSentAt = Long.MIN_VALUE
        lastIgnored = null
        closeReason = null
        open = false
    }

    /** Nothing is invited after this, and whatever was still queued is dropped. */
    @Synchronized
    fun close(reason: String) {
        if (!open) return
        open = false
        closeReason = reason
        queue.clear()
    }

    /**
     * One stripped chat line. Returns true when it queued an invite. [self] is the local player's name,
     * who never invites themselves (their own announcement comes back as a guild line too).
     */
    @Synchronized
    fun onGuildLine(stripped: String, self: String?): Boolean {
        val line = VanguardGuildChat.parseGuild(stripped) ?: return false
        if (!open) return ignore("window closed (${closeReason ?: "not opened"})")
        if (self != null && line.name.equals(self, ignoreCase = true)) return ignore("own message")
        if (VanguardGuildChat.isAnnouncement(line.text)) return ignore("another call, not a reply (${line.name})")
        if (!VanguardGuildChat.wantsIn(line.text)) return ignore("no \"me\" (${line.name})")
        val key = line.name.lowercase()
        if (key in accepted) return ignore("already invited ${line.name}")
        if (accepted.size >= maxInvites) return ignore("invite cap $maxInvites reached (${line.name})")
        accepted[key] = line.name
        queue.addLast(line.name)
        return true
    }

    /** The next name to `/p invite` now, or null when the queue is empty, closed or the pace is not up. */
    @Synchronized
    fun nextInvite(nowMs: Long): String? {
        if (!open || queue.isEmpty()) return null
        if (lastSentAt != Long.MIN_VALUE && nowMs - lastSentAt < paceMs) return null
        val name = queue.removeFirst()
        lastSentAt = nowMs
        sent.add(name)
        return name
    }

    private fun ignore(reason: String): Boolean {
        lastIgnored = reason
        return false
    }
}
