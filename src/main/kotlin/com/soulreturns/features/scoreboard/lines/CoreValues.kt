package com.soulreturns.features.scoreboard.lines

import com.soulreturns.data.skyblock.ProfileType
import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.ScoreboardValue
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The `{value}`s for Custom text that come from the sidebar's fixed block and Soul's state readers. */
object CoreValues {
    private val CLOCK = DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT)

    val all: List<ScoreboardValue> =
        listOf(
            ScoreboardValue("purse", "Money", "Purse", "§61,193,004,241") { c -> c.fields.purse?.let { "§6" + c.number(it) } },
            ScoreboardValue("bank", "Money", "Bank", "§62.1B") { c -> c.sources.tab.bank?.let { "§6" + it.text } },
            ScoreboardValue("bits", "Money", "Bits", "§b13,845") { c -> c.fields.bits?.let { "§b" + c.number(it) } },
            ScoreboardValue("gems", "Money", "Gems", "§a1.2k") { c -> c.sources.tab.gems?.let { "§a" + c.number(it.toDouble()) } },
            ScoreboardValue("area", "Place", "Area", "§bDwarven Village") { c -> c.sources.subLocation?.let { "§b$it" } },
            ScoreboardValue("island", "Place", "Island", "§aDwarven Mines") { c -> c.sources.area?.let { "§a$it" } },
            ScoreboardValue("server", "Place", "Server id", "§8m14AC") { c -> (c.fields.serverId ?: c.sources.serverId)?.let { "§8$it" } },
            ScoreboardValue("profile", "Place", "Profile", "§aIronman") { c ->
                val type = c.sources.profileType
                if (type != ProfileType.NORMAL && type != ProfileType.UNKNOWN) {
                    "§a" + type.name.lowercase(Locale.ROOT).replaceFirstChar { it.uppercaseChar() }
                } else {
                    c.sources.profile?.let { "§a$it" }
                }
            },
            ScoreboardValue("time", "Time", "SkyBlock time", "§72:20pm") { c -> c.fields.time?.let { "§7$it" } },
            ScoreboardValue("date", "Time", "SkyBlock date", "§fLate Winter 10th") { c -> c.sidebar(c.fields.skyblockDateIndex)?.let { "§f" + it.text.trim() } },
            ScoreboardValue("irl", "Time", "Real time", "§722:15") { _ -> "§7" + LocalTime.now().format(CLOCK) },
            ScoreboardValue("objective", "Progress", "Objective", "§eReach the Crystal Hollows") { c ->
                c.fields.objectiveLines.firstOrNull()?.let { c.sidebar(it)?.legacy }?.let { LegacyText.normalize(it).trim() }
            },
            ScoreboardValue("sblevel", "Progress", "SkyBlock level", "§b[312]") { c -> c.sources.tab.skyblockLevel?.let { "§b[${it.level}]" } },
            ScoreboardValue("pet", "Soul", "Pet", "§6Golden Dragon") { c ->
                c.sources.tab.pet?.let { p -> LegacyText.rgbCode(p.rarity?.color ?: 0xFFFFFF) + p.name }
            },
        )
}
