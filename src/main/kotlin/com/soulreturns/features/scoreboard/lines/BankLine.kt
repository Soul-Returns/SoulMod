package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineOptions
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.features.scoreboard.ScoreboardFormat

/**
 * `Bank: 2.1B / 340M`: the bank from the tab list. Hypixel lists the co-op (shared) bank first, then yours;
 * a solo bank has one number. Optionally the time to the next interest (tab list `Interest:`).
 */
object BankLine : LineType(
    "bank", ScoreboardCatalog.MONEY, "Bank",
    "Your bank and the co-op bank.",
    listOf("Bank: §62.1B §7/ §6340M"),
) {
    const val MODE = "bankMode"
    const val SHORT = "bankShort"
    const val INTEREST = "interest"
    private const val LABEL = "Bank:"

    private val gains = GainTracker()

    override val options =
        listOf(
            LineChoice(
                MODE, "Show",
                listOf(LineChoice.Choice("both", "Yours / Co-op"), LineChoice.Choice("coop", "Co-op / Yours"), LineChoice.Choice("combined", "Combined")),
                "both", segmented = true,
            ),
            LineSwitch(SHORT, "Always short (2.1B)", true),
            LineSwitch(INTEREST, "Next interest", false, "Soul · from the tab list"),
            LineOptions.label(LABEL),
            LineOptions.gain(),
        )

    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFFAA00)

    private fun amount(ctx: LineContext, v: Double, short: Boolean = ctx.options.bool(SHORT)): String = if (short) ScoreboardFormat.number(v, true) else ctx.number(v)

    /** The colored amount(s) per the Show choice, or null without a bank reading; [total] is the sum for the gain. */
    private fun shown(ctx: LineContext, mode: String = ctx.options.string(MODE), short: Boolean = ctx.options.bool(SHORT)): Pair<String, Double?>? {
        val bank = ctx.sources.tab.bank ?: return null
        val shared = bank.coins ?: return "§6" + bank.text to null
        val personal = bank.personalCoins ?: return "§6" + amount(ctx, shared, short) to shared
        val total = shared + personal
        val text =
            when (mode) {
                "coop" -> "§6${amount(ctx, shared, short)} §7/ §6${amount(ctx, personal, short)}"
                "combined" -> "§6" + amount(ctx, total, short)
                else -> "§6${amount(ctx, personal, short)} §7/ §6${amount(ctx, shared, short)}"
            }
        return text to total
    }

    override fun lines(ctx: LineContext): List<String> {
        val (text, total) = shown(ctx) ?: run { gains.update(null, ctx.nowMs); return emptyList() }
        val own = gains.update(total, ctx.nowMs)
        val sb = StringBuilder()
        if (ctx.options.bool("gain") && own != null) sb.append(" §6(+${amount(ctx, own)})")
        if (ctx.options.bool(INTEREST)) ctx.sources.tab.interest?.let { sb.append(" §7(§e${it.timeText}§7)") }
        return listOf(ctx.labelValue(ctx.options.string("label"), "§6", text.removePrefix("§6"), sb.toString()))
    }

    override fun emptyLines(ctx: LineContext): List<String> = listOf(ctx.labelValue(ctx.options.string("label"), "§8", "-"))

    /** The colored amounts alone, always short, yours then co-op (Money on one line). */
    fun value(ctx: LineContext): String? = shown(ctx, "both", true)?.first
}
