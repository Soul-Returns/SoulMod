package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineChips
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineNumber
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog

/**
 * `1.19B | 13.8k | 2.1B`: several currencies side by side, [PER_LINE] per line. Only the chosen stats that
 * have a value show; the values are the line types' own (colors, Look number format).
 */
object MoneyChunkLine : LineType(
    "chunk", ScoreboardCatalog.MONEY, "Money on one line",
    "Several currencies side by side, up to 5 per line.",
    listOf("§61.19B §7| §b13.8k §7| §62.1B"),
) {
    const val STATS = "stats"
    const val PER_LINE = "perLine"
    private const val SEPARATOR = " §7| "

    /** The chips, in the mock's order; the values are line type ids. */
    val STAT_IDS =
        listOf("purse", "bank", "bits", "gems", "motes", "copper", "sowdust", "heat", "cold", "northStars")

    private val NAMES = mapOf("purse" to "Purse", "bank" to "Bank", "bits" to "Bits", "gems" to "Gems", "motes" to "Motes", "copper" to "Copper", "sowdust" to "Sowdust", "heat" to "Heat", "cold" to "Cold", "northStars" to "North Stars")

    override val options =
        listOf(
            LineChips(STATS, "Stats", STAT_IDS.map { LineChoice.Choice(it, NAMES.getValue(it)) }, STAT_IDS),
            LineNumber(PER_LINE, "Per line", 3, 1, 5),
        )

    override val colors = LineColors.Text(0xFFAA00)

    /** The colored value of stat [id], or null while it has none. */
    fun value(ctx: LineContext, id: String): String? =
        when (id) {
            "purse" -> ctx.fields.purse?.let { "§6" + ctx.number(it) }
            "bank" -> BankLine.value(ctx)
            "bits" -> ctx.fields.bits?.let { "§b" + ctx.number(it) }
            "gems" -> ctx.sources.tab.gems?.let { "§a" + ctx.number(it.toDouble()) }
            "motes" -> MotesLine.value(ctx)
            "copper" -> CopperLine.value(ctx)
            "sowdust" -> SowdustLine.value(ctx)
            "heat" -> HeatLine.value(ctx)
            "cold" -> ColdLine.value(ctx)
            "northStars" -> NorthStarsLine.value(ctx)
            else -> null
        }

    override fun lines(ctx: LineContext): List<String> {
        val values = ctx.options.list(STATS).mapNotNull { value(ctx, it) }
        return values.chunked(ctx.options.int(PER_LINE)).map { it.joinToString(SEPARATOR) }
    }
}
