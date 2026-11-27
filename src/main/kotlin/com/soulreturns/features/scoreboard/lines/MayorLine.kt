package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/**
 * `Diana (4d 12h)`, its perks, the minister and Jerry's extra mayor. Hypixel's sidebar never shows the
 * mayor, so the data is [MayorData] (the public election API; Jerry's pick from the calendar menu).
 * Rows: the mayor in its color with the time to the election, perks (`All` one row each, `Count` one
 * row), the minister (`Compact` name only, `Full` with its perk by the same Perks setting), Foxy's extra
 * event and the Perkpocalypse mayor.
 */
object MayorLine : LineType(
    "mayor", ScoreboardCatalog.BUFFS, "Mayor & minister",
    "Mayor, perks, minister and time to the election.",
    listOf("§2Diana §7(§e4d 12h§7)", " §7- §eMythological Ritual", "§bMinister: §eFoxy"),
) {
    const val PERKS = "perks"
    const val TIME = "mayorTime"
    const val MINISTER = "minister"
    const val JERRY = "jerry"
    const val FOXY = "foxy"

    private val OFF_COUNT_ALL = listOf(LineChoice.Choice("off", "Off"), LineChoice.Choice("count", "Count"), LineChoice.Choice("all", "All"))
    private val FOXY_EVENT = Regex("Schedules an extra (.+) event during the year\\.")

    /** The mayors' colors as NotEnoughUpdates draws them (SkyHanni's table); an unknown mayor is red. */
    private val COLORS =
        mapOf(
            "aatrox" to "§3", "cole" to "§e", "diana" to "§2", "diaz" to "§6", "finnegan" to "§c", "foxy" to "§d",
            "marina" to "§b", "paul" to "§c", "scorpius" to "§d", "jerry" to "§d", "derpy" to "§d", "aura" to "§b",
        )

    override val options =
        listOf(
            LineChoice(PERKS, "Perks", OFF_COUNT_ALL, "all", segmented = true),
            LineSwitch(TIME, "Time to the election", true),
            LineChoice(MINISTER, "Minister", listOf(LineChoice.Choice("off", "Off"), LineChoice.Choice("compact", "Compact"), LineChoice.Choice("full", "Full")), "full", segmented = true),
            LineSwitch(JERRY, "Extra Jerry mayor as minister", true),
            LineSwitch(FOXY, "Foxy's event", true),
        )

    override val colors = LineColors.HeadingLabels(0x00AA00, 0xFFFF55)

    private fun color(key: String): String = COLORS[key.lowercase()] ?: "§c"

    override fun lines(ctx: LineContext): List<String> {
        MayorData.touch(ctx.nowMs)
        val election = MayorData.election ?: return emptyList()
        val mayor = election.mayor ?: return emptyList()
        val perks = ctx.options.string(PERKS)
        val out = ArrayList<String>()

        var head = color(mayor.key) + mayor.name
        if (ctx.options.bool(TIME)) head += " §7(§e${MayorData.duration(MayorData.nextMayorAt(ctx.nowMs) - ctx.nowMs)}§7)"
        out += head
        // The minister's own perk is listed under the minister, not under the mayor.
        val active = mayor.perks.filter { !it.minister }
        out += perkLines(active, perks, ctx.options.bool(FOXY))
        if (mayor.key.equals("foxy", ignoreCase = true) && perks != "all") foxyLine(active, ctx.options.bool(FOXY))?.let(out::add)

        val minister = election.minister
        val ministerMode = ctx.options.string(MINISTER)
        if (minister != null && ministerMode != "off") {
            out += "§bMinister: ${color(minister.key)}${minister.name}"
            if (ministerMode == "full") {
                out += perkLines(minister.perks, perks, ctx.options.bool(FOXY))
                if (minister.key.equals("foxy", ignoreCase = true) && perks != "all") foxyLine(minister.perks, ctx.options.bool(FOXY))?.let(out::add)
            }
        }

        val jerry = MayorData.jerryMayor
        if (ctx.options.bool(JERRY) && mayor.key.equals("jerry", ignoreCase = true) && jerry != null && MayorData.jerryUntil > ctx.nowMs) {
            var line = "§bExtra mayor: ${color(jerry.key)}${jerry.name}"
            if (ctx.options.bool(TIME)) line += " §7(§6${MayorData.duration(MayorData.jerryUntil - ctx.nowMs)}§7)"
            out += line
        }
        return out
    }

    private fun perkLines(perks: List<MayorPerk>, mode: String, foxyEvent: Boolean): List<String> =
        when (mode) {
            "off" -> emptyList()
            "count" -> if (perks.isEmpty()) emptyList() else listOf(" §7- §e${perks.size} perk${if (perks.size == 1) "" else "s"}")
            else ->
                perks.map { p ->
                    val event = if (foxyEvent) foxyEventOf(p) else null
                    " §7- §e${p.name}" + if (event != null) "§7: §b$event" else ""
                }
        }

    /** Foxy's extra event named after the perk's description (`Schedules an extra Fishing Festival event during the year.`). */
    private fun foxyEventOf(perk: MayorPerk): String? = FOXY_EVENT.find(LegacyText.strip(perk.description))?.groupValues?.get(1)

    private fun foxyLine(perks: List<MayorPerk>, enabled: Boolean): String? {
        if (!enabled) return null
        val event = perks.firstNotNullOfOrNull(::foxyEventOf) ?: return null
        return " §7- §eExtra event§7: §b$event"
    }
}
