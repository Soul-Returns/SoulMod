package com.soulreturns.data.skyblock

import com.soulreturns.util.MessageDetector

/** One tab list read, before [TabWidgets] merges it with what it already knew. */
class TabRead(
    val headers: List<String>,
    val skyblockLevel: TabSkyblockLevel?,
    val bank: TabBank?,
    val interest: TabInterest?,
    val gems: Long?,
    val fairySouls: TabFraction?,
    val skills: List<TabSkill>,
    val skillAverage: Double?,
    /** The `Stats:` lines; empty when the widget is absent or Hypixel has not filled it yet. */
    val stats: List<TabStat>,
    /**
     * The Pet widget had a pet line. Its header alone does not count: Hypixel fills the tab one entry
     * at a time, so a header with nothing under it is mid-update, while ` No pet selected` is a line.
     */
    val petSeen: Boolean,
    val pet: TabPet?,
)

/**
 * Parses SkyBlock's tab widgets out of the tab list lines, **in display order**, as legacy `§`
 * strings (colors are needed for the pet's rarity). Pure string logic, no MC API.
 *
 * Layout, verified against real packet logs (2026-11-07): four columns of 20 fake entries named
 * `!A-a`..`!D-t`. Columns A/B hold `Players (N)` and the players; C/D start with a centered `Info`
 * title, then widgets separated by blank entries. A widget is a header line at column 0
 * (`Profile: Blueberry`, `Pet:`, `Skills:`, `Stats:`, `Area: The End`) followed by body lines
 * indented by **one** space (` Bank: 1B`). Centered titles (`Info`, `Players (20)`) are indented by
 * many spaces and end a widget like a blank line does.
 *
 * Line values (SB Level, Bank, Interest, Gems, Fairy Souls) match on any body line, because Hypixel
 * moves them between widgets (the dungeon tab has an `Account Info:` widget). Skills, Stats and Pet
 * lines only parse inside their widget: `Name: value` alone says nothing about which widget it is.
 */
object TabWidgetParser {
    /** `SB Level: [607] 72/100 XP`; the XP part is optional. */
    private val SB_LEVEL = Regex("SB Level: \\[(\\d+)](?:\\s+([\\d,]+)/([\\d,]+) XP)?")
    private val BANK = Regex("Bank: (.+?)(?: / (.+))?")
    private val INTEREST = Regex("Interest: (.+?)(?: \\(([^)]+)\\))?")
    private val GEMS = Regex("Gems: ([\\d,]+)")
    private val FAIRY_SOULS = Regex("Fairy Souls: (\\d+)/(\\d+)")

    /** A header: everything before the first colon is the widget name. */
    private val HEADER = Regex("([^:]+):\\s*(.*)")

    /** `Combat 60: MAX`, `Farming 45: 32.1%`, `Mining XXV: 12%`. Names are not validated. */
    private val SKILL = Regex("([A-Za-z][A-Za-z ]*?) (\\d+|[IVXLCDM]+): (MAX|[\\d.,]+%)")
    private val AVERAGE = Regex("[\\d.]+")

    /** `Strength: <glyph>1420`: a letter-anchored name, then anything. */
    private val STAT = Regex("([A-Za-z][A-Za-z' ]*): (.+)")
    private val NUMBER = Regex("[+-]?\\d[\\d,]*(?:\\.\\d+)?")

    /** `[Lvl 200] Golden Dragon ✦`, `[Lvl 200] [122✦] Golden Dragon`. */
    private val PET = Regex("\\[Lvl ([\\d,]+)] (?:\\[\\d+(✦)] )?(.+?)( ✦)?")
    private val PET_XP = Regex("(\\+)?([\\d,.]+[kMB]?)(?:/([\\d,.]+[kMB]?))? XP(?: \\(([\\d.]+)%\\))?")

    private val AMOUNT = Regex("([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT]?)")
    private val DURATION_PART = Regex("(\\d+)\\s*(days?|d|hours?|h|minutes?|mins?|m|seconds?|secs?|s)\\b", RegexOption.IGNORE_CASE)

    fun parse(legacyLines: List<String>, nowMs: Long = System.currentTimeMillis()): TabRead {
        val headers = ArrayList<String>()
        var widget: String? = null
        var widgetBody = 0

        var level: TabSkyblockLevel? = null
        var bank: TabBank? = null
        var interest: TabInterest? = null
        var gems: Long? = null
        var souls: TabFraction? = null
        val skills = ArrayList<TabSkill>()
        var average: Double? = null
        val stats = ArrayList<TabStat>()
        var petSeen = false
        var pet: TabPet? = null

        for (legacy in legacyLines) {
            val line = MessageDetector.stripColorCodes(legacy).trimEnd()
            val indent = line.length - line.trimStart().length
            if (line.isBlank() || indent >= 2) {
                widget = null
                continue
            }
            if (indent == 0) {
                val match = HEADER.matchEntire(line)
                widget = (match?.groupValues?.get(1) ?: line).trim()
                widgetBody = 0
                // Player rows (`[607] SoulReturns Σ`) sit at column 0 too but carry no colon.
                if (match != null) headers += widget
                val inline = match?.groupValues?.get(2)?.trim().orEmpty()
                if (widget == "Skills") {
                    // `Skills: 55.3` (average) or, in dungeons, `Skills: Catacombs 50: 35.4%`.
                    val inlineSkill = skill(inline)
                    if (inlineSkill != null) skills += inlineSkill else if (AVERAGE.matches(inline)) average = inline.toDoubleOrNull()
                }
                continue
            }

            val body = line.substring(1)
            val bodyIndex = widgetBody++
            SB_LEVEL.matchEntire(body)?.let { m ->
                level = TabSkyblockLevel(m.groupValues[1].toInt(), m.groupValues[2].toIntOrNull(","), m.groupValues[3].toIntOrNull(","))
                continue
            }
            BANK.matchEntire(body)?.let { m ->
                bank = TabBank(body.removePrefix("Bank: "), amount(m.groupValues[1]), m.groupValues[2].ifEmpty { null }?.let(::amount))
                continue
            }
            INTEREST.matchEntire(body)?.let { m ->
                val time = m.groupValues[1].trim()
                val amountText = m.groupValues[2].ifEmpty { null }
                interest = TabInterest(time, duration(time), amountText, amountText?.let(::amount), nowMs)
                continue
            }
            GEMS.matchEntire(body)?.let { m ->
                gems = m.groupValues[1].replace(",", "").toLongOrNull()
                continue
            }
            FAIRY_SOULS.matchEntire(body)?.let { m ->
                souls = TabFraction(m.groupValues[1].toInt(), m.groupValues[2].toInt())
                continue
            }
            when (widget) {
                "Skills" -> skill(body)?.let { skills += it }
                "Stats" -> STAT.matchEntire(body)?.let { m ->
                    val text = m.groupValues[2].trim()
                    val number = NUMBER.find(text)
                    stats += TabStat(
                        name = m.groupValues[1].trim(),
                        text = text,
                        icon = number?.let { text.substring(0, it.range.first).trim() } ?: "",
                        value = number?.value?.replace(",", "")?.toDoubleOrNull(),
                    )
                }
                "Pet" -> when (bodyIndex) {
                    0 -> {
                        petSeen = true
                        pet = pet(body, legacy)
                    }
                    1 -> pet = pet?.let { withXp(it, body) }
                }
            }
        }

        return TabRead(headers, level, bank, interest, gems, souls, skills, average, stats, petSeen, pet)
    }

    private fun skill(text: String): TabSkill? {
        val m = SKILL.matchEntire(text) ?: return null
        val level = m.groupValues[2].let { it.toIntOrNull() ?: roman(it) } ?: return null
        val progress = m.groupValues[3]
        val maxed = progress == "MAX"
        return TabSkill(m.groupValues[1], level, if (maxed) null else progress.removeSuffix("%").replace(",", "").toDoubleOrNull(), maxed)
    }

    private fun pet(body: String, legacy: String): TabPet? {
        val m = PET.matchEntire(body) ?: return null
        val name = m.groupValues[3]
        val strippedStart = MessageDetector.stripColorCodes(legacy).indexOf(name)
        return TabPet(
            level = m.groupValues[1].replace(",", "").toIntOrNull() ?: return null,
            name = name,
            rarity = if (strippedStart < 0) null else colorAt(legacy, strippedStart)?.let(SkyblockRarity::forColorCode),
            skinned = m.groupValues[2].isNotEmpty() || m.groupValues[4].isNotEmpty(),
            xpText = null,
            xp = null,
            xpForNext = null,
            percent = null,
            maxed = false,
        )
    }

    private fun withXp(pet: TabPet, body: String): TabPet {
        if (body == "MAX LEVEL") return pet.copy(xpText = body, maxed = true)
        val m = PET_XP.matchEntire(body) ?: return pet.copy(xpText = body)
        val overflow = m.groupValues[1].isNotEmpty()
        return pet.copy(
            xpText = body,
            xp = amount(m.groupValues[2]),
            xpForNext = m.groupValues[3].ifEmpty { null }?.let(::amount),
            percent = m.groupValues[4].toDoubleOrNull(),
            maxed = overflow,
        )
    }

    /** The legacy color code active at [strippedIndex] of the color-stripped text. */
    private fun colorAt(legacy: String, strippedIndex: Int): Char? {
        var color: Char? = null
        var visible = 0
        var i = 0
        while (i < legacy.length) {
            val c = legacy[i]
            if (c == '§' && i + 1 < legacy.length) {
                val code = legacy[i + 1].lowercaseChar()
                if (code in "0123456789abcdef") color = code else if (code == 'r') color = null
                i += 2
                continue
            }
            if (visible == strippedIndex) return color
            visible++
            i++
        }
        return null
    }

    /** `1B`, `993.3k`, `1,234,567`, `1.2M` as a number, or null. */
    fun amount(text: String): Double? {
        val m = AMOUNT.matchEntire(text.trim()) ?: return null
        val base = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        return base * when (m.groupValues[2].lowercase()) {
            "k" -> 1e3
            "m" -> 1e6
            "b" -> 1e9
            "t" -> 1e12
            else -> 1.0
        }
    }

    /** `14 Hours`, `1d 3h`, `45m 10s` in seconds; null when nothing reads as a duration. */
    fun duration(text: String): Long? {
        var total = 0L
        var any = false
        for (m in DURATION_PART.findAll(text)) {
            any = true
            val n = m.groupValues[1].toLong()
            val unit = m.groupValues[2].lowercase()
            total += n * when {
                unit.startsWith("d") -> 86_400L
                unit.startsWith("h") -> 3_600L
                unit.startsWith("m") -> 60L
                else -> 1L
            }
        }
        return if (any) total else null
    }

    private fun String.toIntOrNull(strip: String): Int? = replace(strip, "").toIntOrNull()

    private fun roman(text: String): Int? {
        val values = mapOf('I' to 1, 'V' to 5, 'X' to 10, 'L' to 50, 'C' to 100, 'D' to 500, 'M' to 1000)
        var total = 0
        for (i in text.indices) {
            val v = values[text[i]] ?: return null
            val next = text.getOrNull(i + 1)?.let { values[it] } ?: 0
            total += if (v < next) -v else v
        }
        return total
    }
}
