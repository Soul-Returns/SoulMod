package com.soulreturns.features.prices

import com.soulreturns.config.cfg
import com.soulreturns.data.prices.MarketValues
import net.fabricmc.fabric.api.client.item.v1.ItemTooltipCallback
import net.minecraft.ChatFormatting
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.MutableComponent
import net.minecraft.world.item.ItemStack
import java.util.Locale
import kotlin.math.abs

/**
 * Market lines under SkyBlock item tooltips, from [MarketValues] (`config.prices`, default off).
 *
 * Fabric's tooltip callback covers every screen and the hotbar-less inventory alike; lines go after
 * Hypixel's lore behind one blank line. A missing value drops its line, and an item with no market
 * entry gets nothing at all. With Shift-for-details on, value and stack total show at once and the
 * rest only while Shift is held (a dim hint says so when there is more).
 */
object PriceTooltip {
    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ItemTooltipCallback.EVENT.register(ItemTooltipCallback { stack, _, _, lines -> append(stack, lines) })
    }

    private fun append(stack: ItemStack, lines: MutableList<Component>) {
        val c = cfg.prices
        if (!c.tooltips) return
        val v = MarketValues.get(stack) ?: return

        val always = ArrayList<Component>(2)
        val details = ArrayList<Component>(4)
        val fair = v.fair
        if (c.fairValue && fair != null) always += line("Value", coins(fair))
        if (c.stackTotal && fair != null && stack.count > 1) {
            always += line("Stack (${stack.count})", coins(fair * stack.count))
        }
        if (c.bazaar && (v.buy != null || v.sell != null)) {
            val out = Component.literal("Bazaar: ").withStyle(ChatFormatting.GRAY)
            v.buy?.let { out.append(gold(coins(it))).append(dim(" buy")) }
            if (v.buy != null && v.sell != null) out.append(dim(" / "))
            v.sell?.let { out.append(gold(coins(it))).append(dim(" sell")) }
            details += out
        }
        if (c.lowestBin) v.lbin?.let { details += line("Lowest BIN", coins(it)) }
        if (c.trend) v.trend24h?.let { details += trendLine(it) }
        if (c.sellSpeed) {
            val tts = v.tts50
            val liquidity = v.liquidity
            if (tts != null) {
                val out = line("Time to sell", "~" + duration(tts))
                if (liquidity != null) out.append(dim(" (${amount(liquidity)}/day)"))
                details += out
            } else if (liquidity != null) {
                details += line("Volume", amount(liquidity) + "/day")
            }
        }

        val showDetails = !c.shiftForDetails || Minecraft.getInstance().hasShiftDown()
        if (always.isEmpty() && details.isEmpty()) return
        lines += Component.empty()
        lines += always
        if (showDetails) {
            lines += details
        } else if (details.isNotEmpty()) {
            lines += dim("Hold Shift for market details")
        }
    }

    private fun line(label: String, value: String): MutableComponent =
        Component.literal("$label: ").withStyle(ChatFormatting.GRAY).append(gold(value))

    private fun trendLine(trend: Double): Component {
        val pct = trend * 100.0
        val text = String.format(Locale.ROOT, "%+.1f%%", pct)
        val color = if (text == "+0.0%" || text == "-0.0%") ChatFormatting.GRAY else if (pct > 0) ChatFormatting.GREEN else ChatFormatting.RED
        return Component.literal("24h trend: ").withStyle(ChatFormatting.GRAY)
            .append(Component.literal(text).withStyle(color))
    }

    private fun gold(text: String): Component = Component.literal(text).withStyle(ChatFormatting.GOLD)

    private fun dim(text: String): Component = Component.literal(text).withStyle(ChatFormatting.DARK_GRAY)

    /**
     * `1.31k`, `45.3k`, `488.9M`, `950`, `2.5`: two decimals below 10 of a unit, one above (a bazaar
     * spread of 1307 / 1251 must not read 1.3k / 1.3k), trailing zeros dropped, unit bumped on
     * rounding.
     */
    internal fun coins(value: Double): String {
        val a = abs(value)
        val sign = if (value < 0) "-" else ""
        val body = if (a >= 999_950_000.0) {
            scaled(a / 1_000_000_000.0, "B")
        } else if (a >= 999_950.0) {
            scaled(a / 1_000_000.0, "M")
        } else if (a >= 999.95) {
            scaled(a / 1_000.0, "k")
        } else if (a >= 100.0) {
            String.format(Locale.ROOT, "%.0f", a)
        } else {
            scaled(a, "")
        }
        return sign + body
    }

    /** Item counts (volume): same compaction, no decimals below 1 000. */
    private fun amount(value: Double): String =
        if (value >= 999.95) coins(value) else String.format(Locale.ROOT, "%.0f", value)

    private fun scaled(value: Double, unit: String): String {
        var text = String.format(Locale.ROOT, if (value < 9.995) "%.2f" else "%.1f", value)
        text = text.trimEnd('0').trimEnd('.')
        return text + unit
    }

    /** `45s`, `12m`, `1h 29m`, `2d 3h`. */
    internal fun duration(seconds: Long): String {
        if (seconds < 60) return "${seconds.coerceAtLeast(1)}s"
        val minutes = seconds / 60
        if (minutes < 60) return "${minutes}m"
        val hours = minutes / 60
        if (hours < 24) return if (minutes % 60 == 0L) "${hours}h" else "${hours}h ${minutes % 60}m"
        val days = hours / 24
        return if (hours % 24 == 0L) "${days}d" else "${days}d ${hours % 24}h"
    }
}
