package com.soulreturns.features.chat

/** Where a meow came from, and the command that answers there. */
enum class MeowChannel(val command: String) {
    COOP("cc"),
    PARTY("pc"),
    PRIVATE("r"),
    GUILD("gc"),
    ALL("ac"),
}

/** A chat line whose message is exactly "meow": the channel and the sender's name. */
data class MeowLine(val channel: MeowChannel, val sender: String)

/**
 * Auto meow's decisions, free of Minecraft classes so a test drives exactly what the game runs.
 *
 * **The match**: color codes stripped, the message after the sender's `: ` trimmed and equal to
 * `meow` ignoring case ("Meow" and "MEOW" count; "meow!", "meow meow" and "meoww" do not).
 * Channels come from Hypixel's prefixes: `Co-op > `, `Party > `, `Guild > `, `From ` (a private
 * message to you), and every other `name: text` line is all chat (`[123] ⚒ [MVP+] Name: meow`, lobby
 * `[VIP] Name: meow`). Ranks and emblems are not checked against any list; the sender is the last
 * plain word before the colon (bracketed ranks and trailing emblems skipped). `To ` (your own private message),
 * `Officer > ` and `[NPC] ` lines never answer.
 *
 * **No ping pong**: one answer, then every meow from anyone in any channel is ignored for
 * [COOLDOWN_MS]; the player's own lines (Soul's sent meow echoing back included) never count and
 * never start the cooldown.
 */
class MeowResponder(private val clock: () -> Long) {
    private var answeredAt: Long? = null

    /**
     * The channel to answer [line] in, or null. [line] is color-stripped; [self] is the player's
     * name; [enabled] says which channels the player picked; party answers need [inParty].
     */
    fun answer(line: String, self: String?, enabled: (MeowChannel) -> Boolean, inParty: Boolean): MeowChannel? {
        val meow = parse(line) ?: return null
        if (self != null && meow.sender.equals(self, ignoreCase = true)) return null
        val now = clock()
        val last = answeredAt
        if (last != null && now - last < COOLDOWN_MS) return null
        if (!enabled(meow.channel)) return null
        if (meow.channel == MeowChannel.PARTY && !inParty) return null
        answeredAt = now
        return meow.channel
    }

    companion object {
        const val COOLDOWN_MS = 20_000L

        private val CHANNEL = Regex("""^(Co-op|Party|Guild) > (.+)$""")
        private val NAME = Regex("""^\w{1,16}$""")

        /** [line] (color-stripped) as a meow, or null when it is no meow or no chat. */
        fun parse(line: String): MeowLine? {
            val s = line.trim()
            val colon = s.indexOf(": ")
            if (colon <= 0) return null
            if (!s.substring(colon + 2).trim().equals("meow", ignoreCase = true)) return null
            val channel: MeowChannel
            val head: String
            val m = CHANNEL.find(s.substring(0, colon + 2))
            if (m != null) {
                channel = if (m.groupValues[1] == "Co-op") MeowChannel.COOP else if (m.groupValues[1] == "Party") MeowChannel.PARTY else MeowChannel.GUILD
                head = s.substring(m.groups[2]!!.range.first, colon)
            } else if (s.startsWith("From ")) {
                channel = MeowChannel.PRIVATE
                head = s.substring(5, colon)
            } else {
                head = s.substring(0, colon)
                // Another channel (Officer >, anything new with " > ") or an outgoing/NPC line: not all chat.
                if (head.contains(" > ") || head.startsWith("To ") || head.startsWith("[NPC]")) return null
                channel = MeowChannel.ALL
            }
            // The last plain word: emblems after the name (`Name ♲`) and ranks in brackets are skipped.
            val sender = head.trim().split(' ').lastOrNull { NAME.matches(it) } ?: return null
            return MeowLine(channel, sender)
        }
    }
}
