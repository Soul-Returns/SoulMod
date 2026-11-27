package com.soulreturns.features.scoreboard.lines

import com.soulreturns.features.scoreboard.LegacyText
import com.soulreturns.features.scoreboard.LineChoice
import com.soulreturns.features.scoreboard.LineColors
import com.soulreturns.features.scoreboard.LineContext
import com.soulreturns.features.scoreboard.LineSwitch
import com.soulreturns.features.scoreboard.LineType
import com.soulreturns.features.scoreboard.ScoreboardCatalog
import com.soulreturns.util.toLegacyText
import net.minecraft.client.Minecraft
import net.minecraft.core.component.DataComponents
import java.util.Locale

/**
 * `Flint Arrow: 1,234`: the arrow you shoot and how many are left. Read from the lore SkyBlock puts on the
 * items in your inventory (`Active Arrow: Flint Arrow (1,234)`), re-read once a second; nothing shows until
 * an item with that line is in your inventory.
 */
object QuiverLine : LineType(
    "quiver", ScoreboardCatalog.PROGRESS, "Quiver",
    "Arrows left in your quiver.",
    listOf("Flint Arrow: §f1,234"),
) {
    const val SHOW = "arrows"
    const val COLOR = "arrowColor"
    const val CAPACITY = 2880
    private val ACTIVE = Regex("Active Arrow: (.+?) \\(([\\d,]+)\\)")
    private const val POLL_MS = 1_000L

    class Reading(val type: String, val amount: Int)

    override val options =
        listOf(
            LineChoice(SHOW, "Show", listOf(LineChoice.Choice("n", "Number"), LineChoice.Choice("p", "Percent")), "n", segmented = true),
            LineSwitch(COLOR, "Color by amount", false),
        )

    override val colors = LineColors.LabelValue(0xFFFFFF, 0xFFFFFF)

    /** Reads the quiver; tests replace it. */
    internal var source: () -> Reading? = ::scanInventory

    private var cachedAt: Long? = null
    private var cached: Reading? = null

    /** The first `Active Arrow: <type> (<n>)` line in the player's inventory lore. */
    fun parse(lore: List<String>): Reading? {
        for (line in lore) ACTIVE.find(LegacyText.strip(line).trim())?.let { return Reading(it.groupValues[1], it.groupValues[2].replace(",", "").toInt()) }
        return null
    }

    private fun scanInventory(): Reading? {
        val inv = Minecraft.getInstance().player?.inventory ?: return null
        for (i in 0 until inv.containerSize) {
            val lore = inv.getItem(i).takeIf { !it.isEmpty }?.get(DataComponents.LORE)?.lines() ?: continue
            parse(lore.map { it.toLegacyText() })?.let { return it }
        }
        return null
    }

    private fun reading(now: Long): Reading? {
        val at = cachedAt
        if (at == null || now - at >= POLL_MS || now < at) {
            cachedAt = now
            cached = source()
        }
        return cached
    }

    /** Percent-of-capacity color: dark green above 90 %, then green, yellow, gold, red. */
    fun amountCode(fraction: Double): String =
        when {
            fraction > 0.9 -> "§2"
            fraction > 0.75 -> "§a"
            fraction > 0.5 -> "§e"
            fraction > 0.25 -> "§6"
            else -> "§c"
        }

    override fun lines(ctx: LineContext): List<String> {
        val r = reading(ctx.nowMs) ?: return emptyList()
        val fraction = r.amount.toDouble() / CAPACITY
        val code = if (ctx.options.bool(COLOR)) amountCode(fraction) else "§f"
        val shown =
            if (ctx.options.string(SHOW) == "p") String.format(Locale.ROOT, "%.1f%%", fraction * 100).replace(".0%", "%") else ctx.number(r.amount.toDouble())
        return listOf(ctx.labelValue(r.type + ":", code, shown))
    }
}
