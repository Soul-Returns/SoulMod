package com.soulreturns.features.scoreboard

/**
 * A live value for Custom text: `{key}` in the text is replaced by [value] (colored text, null = no
 * value now). [example] is what the setup screen shows in its tooltip and preview without live data.
 */
class ScoreboardValue(
    val key: String,
    /** One of [ScoreboardValues.groups] ("More values…" lists them by group). */
    val group: String,
    val name: String,
    val example: String,
    val value: (LineContext) -> String?,
)

/** The placeholder registry: values come from [ScoreboardCatalog.values]. */
object ScoreboardValues {
    /** "More values…" groups, in order. */
    val groups = listOf("Money", "Place", "Time", "Progress", "Soul")

    /** The insert buttons under the text field (the rest are behind "More values…"). */
    val quick = listOf("purse", "bits", "area", "pet", "time", "server")

    /** Drawn for a known value that has nothing right now. */
    const val MISSING = "§8-"

    private val PLACEHOLDER = Regex("\\{([A-Za-z][A-Za-z0-9_]*)}")

    private val byKey: Map<String, ScoreboardValue> by lazy { ScoreboardCatalog.values.associateBy { it.key } }

    fun all(): List<ScoreboardValue> = ScoreboardCatalog.values

    fun get(key: String): ScoreboardValue? = byKey[key]

    /**
     * [text] with every known `{key}` replaced by its value; the text after it continues in the style it
     * had before (`§7Goal: {purse} left` keeps `left` gray). Unknown keys stay as typed.
     */
    fun expand(text: String, ctx: LineContext, lookup: (String) -> ScoreboardValue? = ::get): String {
        if ('{' !in text) return text
        val sb = StringBuilder()
        var last = 0
        for (m in PLACEHOLDER.findAll(text)) {
            val v = lookup(m.groupValues[1]) ?: continue
            sb.append(text, last, m.range.first)
            val resume = LegacyText.activeCodes(text.substring(0, m.range.first)).ifEmpty { "§r" }
            sb.append(runCatching { v.value(ctx) }.getOrNull() ?: MISSING).append(resume)
            last = m.range.last + 1
        }
        sb.append(text, last, text.length)
        return sb.toString()
    }
}
