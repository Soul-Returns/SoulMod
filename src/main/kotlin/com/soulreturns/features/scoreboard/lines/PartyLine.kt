package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.party.PartyManager
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineNumber
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardActivities
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardSnapshot

/** Your party as [PartyManager] knows it: [leader] and the other [members] by name, [size] counting members not named yet. */
class PartyInfo(val leader: String?, val members: List<String>, val size: Int)

/**
 * `Party (4)` and its members. In a dungeon Hypixel lists the teammates itself (`[M] Name 19,151❤`: class
 * letter, name, health; real capture), in Kuudra with their health; Soul gives them the heading and
 * keeps Hypixel's rows. Everywhere else it is [PartyManager]'s party: the leader (♚) and the members.
 * Outside those places it only shows when "Everywhere" is on (SkyHanni's rule: Dungeon Hub, Kuudra,
 * Crimson Isle and the cold mining islands are where Hypixel lists a party in its own widgets).
 */
object PartyLine : LineType(
    "party", ScoreboardCatalog.BUFFS, "Party",
    "Your party members.",
    listOf("§9Party (4)", " §e[M] §fSoulReturns"),
) {
    const val MAX = "partyMax"
    const val LEADER = "partyLeader"
    const val ALL = "partyAll"

    /** Dungeon teammate row (stripped): `[M] SoulReturns 19,151❤`, `[H] Name [Lv48]`, before the health is known. */
    private val DUNGEON_ROW = Regex("\\[\\w] \\w{2,16}(?: .*)?")

    /** Kuudra teammate row (stripped): `SoulReturns 14,210❤`. Unverified: no Kuudra capture exists. */
    private val KUUDRA_ROW = Regex("\\w{3,16} [\\d,.]+[kM]?❤")

    private val HYPIXEL_PARTY_PLACES = listOf("Dungeon Hub", "Kuudra", "Crimson Isle", "Dwarven Mines", "Glacite", "Mineshaft")

    /** Where the party comes from; tests replace it. */
    @Volatile
    var source: () -> PartyInfo? = ::live

    override val where = "In a party"

    override val options =
        listOf(
            LineNumber(MAX, "Members shown", 4, 1, 10, hint = "Not applied in dungeons, where every teammate shows"),
            LineSwitch(LEADER, "Show the leader", true),
            LineSwitch(ALL, "Everywhere, not only where Hypixel shows it", false),
        )

    override val colors = LineColors.HeadingLabels(0x5555FF, 0xFFFFFF)

    /** Indexes of Hypixel's own teammate rows (dungeon or Kuudra only). */
    private fun rows(s: ScoreboardSnapshot): List<Int> {
        val re =
            when {
                ScoreboardActivities.of(ScoreboardActivities.DUNGEONS)?.matches(s) == true -> DUNGEON_ROW
                ScoreboardActivities.of(ScoreboardActivities.KUUDRA)?.matches(s) == true -> KUUDRA_ROW
                else -> return emptyList()
            }
        return s.sidebar.indices.filter { re.matches(s.sidebar[it].text.trim()) }
    }

    override fun claims(sources: ScoreboardSnapshot): Collection<Int> = rows(sources)

    override fun lines(ctx: LineContext): List<String> {
        val s = ctx.sources
        val rows = rows(s)
        if (rows.isNotEmpty()) {
            return listOf("§9Party (${rows.size})") + rows.map { " " + s.sidebar[it].legacy }
        }
        val inInstance = ScoreboardActivities.of(ScoreboardActivities.DUNGEONS)?.matches(s) == true ||
            ScoreboardActivities.of(ScoreboardActivities.KUUDRA)?.matches(s) == true
        if (inInstance) return emptyList()
        val where = s.area.orEmpty() + " " + s.subLocation.orEmpty()
        if (!ctx.options.bool(ALL) && HYPIXEL_PARTY_PLACES.none { where.contains(it, ignoreCase = true) }) return emptyList()
        val party = source() ?: return emptyList()
        val out = ArrayList<String>()
        out += "§9Party (${party.size})"
        val showLeader = ctx.options.bool(LEADER)
        if (showLeader && party.leader != null) out += " §7- §f${party.leader} §e♚"
        val others = if (showLeader) party.members.filter { !it.equals(party.leader, ignoreCase = true) } else party.members
        for (name in others.take(ctx.options.int(MAX))) out += " §7- §f$name"
        return out
    }

    private fun live(): PartyInfo? {
        val state = PartyManager.getPartyState() ?: return null
        return PartyInfo(state.leader?.name, state.members.values.map { it.name }, state.size)
    }
}
