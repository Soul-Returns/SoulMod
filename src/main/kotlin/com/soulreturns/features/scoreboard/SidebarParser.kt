package com.soulreturns.features.scoreboard

/**
 * The sidebar's fixed block, found by shape (every capture from 2026-11-15/16 has it, top first):
 *
 * ```
 * 11/15/26 m144BH          date + server id   (+ anything after, e.g. a mineshaft layout)
 *                          blank
 *  Early Summer 11th       SkyBlock date
 *  1:20am ☽                SkyBlock time + day/night/weather icon (none in some places)
 *  <U+E067> Upper Mines    area (location marker; older ⏣), else the line under the clock
 *                          blank
 * Purse: 4,464,676 (+5)    Purse (Piggy: with a piggy bank) + Hypixel's own gain
 * Bits: 1,034              Bits
 * Objective                Objective heading, its text on the next line
 *                          blank
 * www.hypixel.net          footer (alpha.hypixel.net on the Alpha network)
 * ```
 *
 * Index -1 = not on the sidebar now. Values are color-stripped; the lines' colors are in the snapshot.
 */
class SidebarFields(
    val dateIndex: Int = -1,
    val date: String? = null,
    val serverId: String? = null,
    val skyblockDateIndex: Int = -1,
    val timeIndex: Int = -1,
    /** `1:20am` as Hypixel shows it. */
    val time: String? = null,
    val areaIndex: Int = -1,
    val purseIndex: Int = -1,
    /** `Purse` or `Piggy`. */
    val purseLabel: String? = null,
    val purse: Double? = null,
    /** Hypixel's own gain, `+5`, while it shows one. */
    val purseGain: String? = null,
    val bitsIndex: Int = -1,
    val bits: Double? = null,
    val bitsGain: String? = null,
    val objectiveIndex: Int = -1,
    /** The objective's text lines (under the heading, up to the next blank). */
    val objectiveLines: List<Int> = emptyList(),
    val footerIndex: Int = -1,
)

object SidebarParser {
    private val DATE = Regex("(\\d{1,2}/\\d{1,2}/\\d{2,4})\\s+(\\S+).*")
    private val SKYBLOCK_DATE = Regex("(?:Early |Late )?(?:Spring|Summer|Autumn|Winter) \\d{1,2}(?:st|nd|rd|th)")
    private val CLOCK = Regex("(\\d{1,2}:\\d{2}\\s*[ap]m).*", RegexOption.IGNORE_CASE)
    private val PURSE = Regex("(Purse|Piggy): ([\\d,.]+)(?:\\s*\\(\\+([\\d,.]+)\\))?.*")
    private val BITS = Regex("Bits: ([\\d,.]+)(?:\\s*\\(\\+([\\d,.]+)\\))?.*")
    private val FOOTER = Regex("(?:www|alpha)\\.hypixel\\.net", RegexOption.IGNORE_CASE)

    /** Location markers, newest first (see `SkyblockStateReader.LOCATION_MARKERS`). */
    private val LOCATION_MARKERS = charArrayOf('\uE067', '\u23E3')

    fun parse(lines: List<SourceLine>): SidebarFields {
        val text = lines.map { it.text.trim() }
        var dateIndex = -1
        var date: String? = null
        var server: String? = null
        var sbDate = -1
        var time = -1
        var clock: String? = null
        var area = -1
        var purse = -1
        var purseLabel: String? = null
        var purseValue: Double? = null
        var purseGain: String? = null
        var bits = -1
        var bitsValue: Double? = null
        var bitsGain: String? = null
        var objective = -1
        val objectiveLines = ArrayList<Int>()
        var footer = -1
        for ((i, t) in text.withIndex()) {
            if (t.isEmpty()) continue
            if (dateIndex < 0 && i <= 1) {
                DATE.matchEntire(t)?.let { m ->
                    dateIndex = i
                    date = m.groupValues[1]
                    server = m.groupValues[2]
                }
                if (dateIndex == i) continue
            }
            if (sbDate < 0 && SKYBLOCK_DATE.matchEntire(t) != null) {
                sbDate = i
                continue
            }
            if (time < 0) {
                CLOCK.matchEntire(t)?.let { m ->
                    time = i
                    clock = m.groupValues[1].replace(" ", "")
                }
                if (time == i) continue
            }
            if (area < 0 && t.any { it in LOCATION_MARKERS }) {
                area = i
                continue
            }
            if (purse < 0) {
                PURSE.matchEntire(t)?.let { m ->
                    purse = i
                    purseLabel = m.groupValues[1]
                    purseValue = number(m.groupValues[2])
                    purseGain = m.groupValues[3].ifEmpty { null }?.let { "+$it" }
                }
                if (purse == i) continue
            }
            if (bits < 0) {
                BITS.matchEntire(t)?.let { m ->
                    bits = i
                    bitsValue = number(m.groupValues[1])
                    bitsGain = m.groupValues[2].ifEmpty { null }?.let { "+$it" }
                }
                if (bits == i) continue
            }
            if (objective < 0 && t == "Objective") {
                objective = i
                var j = i + 1
                while (j < text.size && text[j].isNotEmpty() && !FOOTER.containsMatchIn(text[j])) objectiveLines += j++
                continue
            }
            if (i == text.lastIndex && FOOTER.containsMatchIn(t)) footer = i
        }
        // No marker on the area line (Hypixel changed it once already): the line right under the clock.
        if (area < 0 && time >= 0 && time + 1 < text.size && text[time + 1].isNotEmpty() && time + 1 != purse) area = time + 1
        return SidebarFields(
            dateIndex, date, server, sbDate, time, clock, area,
            purse, purseLabel, purseValue, purseGain, bits, bitsValue, bitsGain,
            objective, objectiveLines, footer,
        )
    }

    /** `4,464,676` -> 4464676.0. */
    fun number(text: String): Double? = text.replace(",", "").toDoubleOrNull()
}
