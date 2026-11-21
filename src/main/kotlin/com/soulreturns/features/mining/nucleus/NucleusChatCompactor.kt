package com.soulreturns.features.mining.nucleus

import com.soulreturns.config.cfg
import com.soulreturns.features.profit.dragon.DragonProfitAnnouncer
import com.soulreturns.util.ChatDisplayFilter
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import java.util.Locale

/**
 * Chat compaction in the Crystal Hollows, display only ([ChatDisplayFilter]: other mods and our own
 * parsers still see every line). The 8-line found block and the ~22-line loot bundle become one line
 * each with the original lines on hover; the re-punch and etherwarp spam can be hidden.
 *
 * Blocks open on a bare `▬` bar, which is hidden at once; the next line decides. Not a nucleus block
 * (another Hypixel message with a bar), or still open after [STALE_MS]: every hidden line is put back
 * in order. Lines of one block arrive in the same tick.
 */
object NucleusChatCompactor {
    private const val STALE_MS = 1_000L
    private const val MAX_BLOCK_LINES = 40

    private const val KIND_UNKNOWN = 0
    private const val KIND_FOUND = 1
    private const val KIND_LOOT = 2

    private var block: ArrayList<Component>? = null
    private var kind = KIND_UNKNOWN
    private var openedAt = 0L
    private var hideFooter = false

    /** Set while we add lines ourselves, so they pass the filter. */
    private var emitting = false

    fun register() {
        ChatDisplayFilter.register(::shouldHide)
        ClientTickEvents.END_CLIENT_TICK.register {
            if (block != null && System.currentTimeMillis() - openedAt > STALE_MS) flush()
        }
    }

    private fun shouldHide(message: Component): Boolean {
        if (emitting) return false
        val open = block
        if (!NucleusRuns.inHollows()) {
            if (open != null) flush()
            hideFooter = false
            return false
        }
        val n = cfg.mining.nucleus
        val line = NucleusLines.clean(message.string)
        if (open != null) return inBlock(message, line, open)
        if (hideFooter) {
            hideFooter = false
            if (line == NucleusLines.LOOT_FOOTER) return true
        }
        if ((n.compactFoundOn || n.compactLootOn) && NucleusLines.isBar(line)) {
            block = arrayListOf(message)
            kind = KIND_UNKNOWN
            openedAt = System.currentTimeMillis()
            return true
        }
        if (n.hideAlreadyObtainedOn && line == NucleusLines.ALREADY_OBTAINED) return true
        return n.hideBlocksInWayOn && line == NucleusLines.BLOCKS_IN_WAY
    }

    private fun inBlock(message: Component, line: String, open: ArrayList<Component>): Boolean {
        val n = cfg.mining.nucleus
        if (kind == KIND_UNKNOWN) {
            kind =
                if (n.compactFoundOn && NucleusLines.FOUND_HEADER.matches(line)) KIND_FOUND
                else if (n.compactLootOn && line == NucleusLines.LOOT_HEADER) KIND_LOOT
                else KIND_UNKNOWN
            if (kind == KIND_UNKNOWN) {
                // Someone else's bar: put it back, then let this line through after it.
                flush()
                return false
            }
            open += message
            return true
        }
        open += message
        if (NucleusLines.isBar(line)) {
            val lines = open
            val wasLoot = kind == KIND_LOOT
            block = null
            kind = KIND_UNKNOWN
            emit(if (wasLoot) lootLine(lines) else foundLine(lines), lines)
            hideFooter = wasLoot
            return true
        }
        if (open.size > MAX_BLOCK_LINES) flush()
        return true
    }

    /** `✦ Amethyst Crystal found (1/5) in 0:41 -2.1s`; split and delta when the run state has them. */
    private fun foundLine(lines: List<Component>): String {
        val clean = lines.map { NucleusLines.clean(it.string) }
        val header = clean.firstNotNullOfOrNull { NucleusLines.FOUND_HEADER.matchEntire(it) }
        val crystal = clean.firstNotNullOfOrNull { NucleusLines.CRYSTAL_NAME.matchEntire(it)?.groupValues?.get(1) } ?: "?"
        val count = header?.let { " §8(${it.groupValues[1]}/${it.groupValues[2]})" } ?: ""
        val progress = NucleusRuns.handle.read().run.crystals[crystal]
        val split = progress?.splitMs?.let { " §7in §f" + clock(it) } ?: ""
        val delta = progress?.deltaMs?.let { (if (it <= 0) " §a-" else " §c+") + seconds(kotlin.math.abs(it)) } ?: ""
        return "§5§l✦ §r${NucleusCrystals.legacy(crystal)}$crystal Crystal §7found$count$split$delta"
    }

    /** `✦ Nucleus loot 3.8M in 2:01 · Divan Fragment x2, Jaderald` (rare items by the panel threshold). */
    private fun lootLine(lines: List<Component>): String {
        val items = lines.mapNotNull { c -> NucleusLines.lootLineRaw(c.string) }
        val mode = NucleusHudSettings.priceMode
        val threshold = NucleusRuns.rareThreshold()
        val valued = items.map { it to NucleusItems.unitValue(it.name, mode) * it.amount }
        val total = valued.sumOf { it.second }
        val rare = valued.filter { it.second > 0 && it.second >= threshold }.sortedByDescending { it.second }
        val sb = StringBuilder("§3§l✦ §r§5§lNucleus loot §r§7(${items.size} items)")
        if (total > 0) sb.append(" §6").append(DragonProfitAnnouncer.formatCoins(total))
        NucleusRuns.lastBundleRunMs?.let { sb.append(" §7in §f").append(clock(it)) }
        if (rare.isNotEmpty()) {
            sb.append(" §8· ")
            sb.append(rare.joinToString("§7, ") { (item, _) -> "§5" + item.name + if (item.amount > 1) " x${item.amount}" else "" })
        }
        return sb.toString()
    }

    private fun emit(text: String, original: List<Component>) {
        val hover = Component.empty()
        original.forEachIndexed { i, c ->
            if (i > 0) hover.append("\n")
            hover.append(c)
        }
        val line = Component.literal(text).withStyle { it.withHoverEvent(HoverEvent.ShowText(hover)) }
        withEmitting { it.addClientSystemMessage(line) }
    }

    /** Puts every hidden line back, in order. */
    private fun flush() {
        val open = block ?: return
        block = null
        kind = KIND_UNKNOWN
        withEmitting { chat -> open.forEach(chat::addServerSystemMessage) }
    }

    private inline fun withEmitting(block: (net.minecraft.client.gui.components.ChatComponent) -> Unit) {
        emitting = true
        try {
            block(Minecraft.getInstance().gui.hud.chat)
        } finally {
            emitting = false
        }
    }

    private fun clock(ms: Long): String {
        val s = ms / 1000
        return String.format(Locale.ROOT, "%d:%02d", s / 60, s % 60)
    }

    private fun seconds(ms: Long): String = String.format(Locale.ROOT, "%.1fs", ms / 1000.0)
}
