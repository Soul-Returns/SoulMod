package com.soulreturns.features.mining.commissions

import com.soulreturns.util.MessageDetector

/** One commission: its name as Hypixel prints it, the progress text (`0%`, `37.5%`, `DONE`) and whether it is done. */
data class Commission(val name: String, val progress: String?, val done: Boolean)

/**
 * Pure readers for the two commission sources (capture `docs/research/commissions-2026-11-15.md`).
 * Names and areas are taken as printed: never a closed list.
 */
object CommissionParsing {
    const val MENU_TITLE = "Commissions"
    const val DONE = "DONE"

    private val TAB_HEADER = Regex("""^Commissions:?$""")
    private val TAB_ENTRY = Regex("""^ ([A-Za-z0-9][^:]*?):\s*(\S.*)$""")
    // Hypixel appends a tag to a freshly rolled slot ("Commission #4 NEW").
    private val ITEM_NAME = Regex("""^Commission #\d+(?: .*)?$""")
    private val PERCENT = Regex("""(\d+(?:\.\d+)?%)\s*$""")
    private val SECTION_HEADS = setOf("Rewards", "Progress")

    /**
     * The tab list's `Commissions:` widget (legacy lines in display order): one ` <Name>: <N%|DONE>` line
     * per commission until a line of another shape (blank, next header, centered title). Null without
     * the widget; an empty list while Hypixel has not filled it yet.
     */
    fun parseTab(lines: List<String>): List<Commission>? {
        var rows: ArrayList<Commission>? = null
        var inWidget = false
        for (raw in lines) {
            val line = MessageDetector.stripColorCodes(raw)
            if (TAB_HEADER.matches(line.trim()) && !line.startsWith(" ")) {
                inWidget = true
                rows = rows ?: ArrayList()
                continue
            }
            if (!inWidget) continue
            val m = TAB_ENTRY.matchEntire(line.trimEnd())
            if (m == null) {
                inWidget = false
                continue
            }
            val value = m.groupValues[2].trim()
            rows?.add(Commission(m.groupValues[1].trim(), value, value.equals(DONE, ignoreCase = true)))
        }
        return rows
    }

    fun isMenuTitle(title: String): Boolean = MessageDetector.stripColorCodes(title).trim() == MENU_TITLE

    /**
     * A `Commission #N` item (legacy name and lore lines), or null for any other item. The name is the
     * first blue (`§9`) lore line that is not a section head; progress is the percent ending the line
     * after `Progress`, or DONE when a line reads `COMPLETED`.
     */
    fun parseMenuItem(name: String, lore: List<String>): Commission? {
        if (!ITEM_NAME.matches(MessageDetector.stripColorCodes(name).trim())) return null
        var commission: String? = null
        var progress: String? = null
        var done = false
        var afterProgress = false
        for (raw in lore) {
            val text = MessageDetector.stripColorCodes(raw).trim()
            if (afterProgress) {
                afterProgress = false
                PERCENT.find(text)?.let { progress = it.groupValues[1] }
            }
            if (text == "COMPLETED") done = true
            if (text == "Progress") afterProgress = true
            if (commission == null && text.isNotEmpty() && text !in SECTION_HEADS && raw.replace("§r", "").startsWith("§9")) commission = text
        }
        val n = commission ?: return null
        return Commission(n, if (done) DONE else progress, done)
    }
}
