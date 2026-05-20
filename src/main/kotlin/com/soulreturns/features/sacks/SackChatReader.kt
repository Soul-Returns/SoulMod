package com.soulreturns.features.sacks

import com.soulreturns.core.events.Events
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent

/**
 * Incremental sack-delta channel between authoritative snapshots.
 *
 * Hypixel batches sack changes into a single chat line every ~5 seconds while the player
 * is actively gaining/losing sack items:
 *
 * ```
 * [Sacks] +30 items. (Last 5s.)
 * [Sacks] -12 items. (Last 5s.)
 * ```
 *
 * The visible line is terse; the per-item breakdown lives in the line's hover tooltip
 * (rendered as a `HoverEvent.ShowText` Component on the root):
 *
 * ```
 * Added items:
 *   +20 Ancient Claw (Events Sack)
 *   +10 Enchanted Gold Ingot (Enchanted Mining Sack)
 *
 * This message can be disabled in the settings.
 * ```
 *
 * This reader subscribes to [ChatMessage], filters to the trigger line, walks the
 * Component's hover tree, parses the per-item lines via the empirical regex
 * `^([+-])([\d,]+)\s+(.+?)\s+\(([^)]+)\)$` (sign, count, displayName, sackName), maps
 * each display name to a skyblock id via [ItemCatalogClient.byDisplayName], and applies
 * the result via [SackState.applyDeltas]. The sack-name suffix is informational only
 * — state is keyed by item id, not sack kind.
 *
 * **Authoritative-vs-incremental layering.** This is the **delta** channel:
 *  - Bulk authoritative state arrives via [com.soulreturns.data.skyblock.SkyblockProfileLoader]
 *    on every [com.soulreturns.data.model.ProfileChanged] (Hypixel `/skyblock/profiles`
 *    snapshot — 60 s server cache + 2 min Hypixel cache).
 *  - Per-sack authoritative state arrives via [SackGuiReader] on every sack-screen open.
 *  - This reader fills the gap between snapshots so the state ticks forward every ~5 s
 *    of active gameplay without waiting for the next sack open or profile switch.
 *
 * **Catalog dependency.** Display-name lookups go through [ItemCatalogClient]. If the
 * catalog is empty (first-launch, no disk cache, network down), this reader logs misses
 * and drops the deltas — local state stays at whatever the GUI reader has captured. Once
 * the catalog refreshes (via Mercure invalidate or a manual `/soul dev refreshCatalog`),
 * future lines parse cleanly.
 *
 * **Drift detection.** The trigger line's count (e.g. `+30 items`) is the server's
 * authoritative total. We sum the per-item magnitudes and log an info line if they
 * disagree — usually means a catalog miss dropped one or more items.
 */
object SackChatReader {
    private val logger = SoulLogger("Soul/SackChat")

    /** `§e§l[Sacks] §a+30 items. §7(Last 5s.)` — after stripColorCodes leaves `[Sacks] +30 items. (Last 5s.)`. */
    private val TRIGGER_PATTERN = Regex("^\\[Sacks] ([+-])(\\d+) items?\\.")

    /**
     * `+20 Ancient Claw (Events Sack)` after stripColorCodes + trim. Groups:
     *  1. sign (+/-)
     *  2. count (digits, commas allowed for thousands-formatted values)
     *  3. display name (non-greedy — Hypixel doesn't nest parens in item names)
     *  4. sack name (informational; we key by item id)
     */
    private val DELTA_LINE_PATTERN = Regex("^([+-])([\\d,]+)\\s+(.+?)\\s+\\(([^)]+)\\)$")

    @Volatile private var registered: Boolean = false

    fun register() {
        if (registered) return
        registered = true
        Events.subscribe<ChatMessage> { event -> onChat(event) }
    }

    private fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val plain = MessageDetector.stripColorCodes(event.raw).trim()
        val headerMatch = TRIGGER_PATTERN.find(plain) ?: return
        val component = event.component
        if (component == null) {
            // Synthesized via simulateMessage — no Component to walk. Drop silently;
            // real Hypixel lines always carry the Component.
            return
        }
        val hover = extractFirstHoverText(component)
        if (hover == null) {
            logger.info("[Sacks] line had no hover tooltip: $plain")
            return
        }
        val hoverPlain = MessageDetector.stripColorCodes(hover.string)
        val parsed = parseDeltas(hoverPlain)
        if (parsed.deltas.isEmpty()) {
            logger.info("[Sacks] tooltip parsed 0 deltas from: $plain")
            return
        }
        SackState.applyDeltas(parsed.deltas)
        val expectedTotal = headerMatch.groupValues[2].toLongOrNull() ?: 0L
        if (parsed.actualMagnitudeTotal != expectedTotal) {
            logger.info(
                "[Sacks] header total=$expectedTotal but per-item sum=${parsed.actualMagnitudeTotal} " +
                    "(catalog misses: ${parsed.unknownDisplayNames}) — applied ${parsed.deltas.size} item delta(s)",
            )
        }
    }

    private data class ParsedDeltas(
        val deltas: Map<String, Long>,
        val actualMagnitudeTotal: Long,
        val unknownDisplayNames: List<String>,
    )

    private fun parseDeltas(hoverPlain: String): ParsedDeltas {
        val out = HashMap<String, Long>()
        var magnitudeTotal = 0L
        val unknown = ArrayList<String>()
        for (rawLine in hoverPlain.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            // Skip the header ("Added items:" / "Removed items:") and the footer
            // ("This message can be disabled..."). The DELTA_LINE_PATTERN already excludes
            // these implicitly (no leading sign, or no trailing parenthesized sack name),
            // but the explicit skip keeps the parse loop intent obvious.
            if (!line.startsWith("+") && !line.startsWith("-")) continue
            val m = DELTA_LINE_PATTERN.find(line) ?: continue
            val sign = m.groupValues[1]
            val countRaw = m.groupValues[2].replace(",", "")
            val displayName = m.groupValues[3].trim()
            val count = countRaw.toLongOrNull() ?: continue
            magnitudeTotal += count
            val delta = if (sign == "-") -count else count
            val entry = ItemCatalogClient.byDisplayName(displayName)
            if (entry == null) {
                unknown.add(displayName)
                continue
            }
            out.merge(entry.id, delta, Long::plus)
        }
        return ParsedDeltas(out, magnitudeTotal, unknown)
    }

    /**
     * Walk a [Component] tree depth-first and return the first `HoverEvent.ShowText`
     * value encountered. Mirrors the helper in
     * [com.soulreturns.features.chat.ChatRightClickCopy] — same `is HoverEvent.ShowText`
     * pattern-match (NOT `when` on the sealed interface, per the $WhenMappings rule).
     * Per Hypixel's chat protocol, hover-text is attached to the root component of the
     * `[Sacks]` line; the recursive walk is defensive against future per-segment hovers.
     */
    private fun extractFirstHoverText(component: Component): Component? {
        val hover = component.style.hoverEvent
        if (hover is HoverEvent.ShowText) return hover.value()
        for (sibling in component.siblings) {
            val found = extractFirstHoverText(sibling)
            if (found != null) return found
        }
        return null
    }
}
