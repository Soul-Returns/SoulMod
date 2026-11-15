package com.soulreturns.features.mining.metaldetector

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SkyblockProfileState
import com.soulreturns.stats.PersistentStats
import com.soulreturns.stats.StatsHandle
import com.soulreturns.stats.TrackerSessions
import com.soulreturns.ui.hud.alert.Alerts
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SkyblockItemUtils
import kotlinx.serialization.Serializable
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/** One scope's loot counters. Keys are item names as Hypixel prints them, colors and glyphs stripped. */
@Serializable
data class DetectorLootCounts(
    val treasures: Long = 0L,
    val tools: Long = 0L,
    val sets: Long = 0L,
    /** Summed tool-set durations (detector time), for the average. */
    val setTimeMs: Long = 0L,
    val lastSetMs: Long = 0L,
    /** Times each item was found. */
    val found: Map<String, Long> = emptyMap(),
    /** Items received (the `x2` amount, or the powder amount). */
    val amount: Map<String, Long> = emptyMap(),
    /** Sets counted without a duration (partial sets), part of [sets]. */
    val untimedSets: Long = 0L,
) {
    fun withFind(item: String, qty: Long): DetectorLootCounts =
        copy(
            treasures = treasures + 1,
            tools = tools + if (MetalDetectorLoot.isTool(item)) 1 else 0,
            found = found + (item to (found[item] ?: 0L) + 1),
            amount = amount + (item to (amount[item] ?: 0L) + qty),
        )

    fun withSet(durationMs: Long): DetectorLootCounts =
        copy(sets = sets + 1, setTimeMs = setTimeMs + durationMs, lastSetMs = durationMs)

    /** A set that was already in progress when tracking began: counted, but with no duration. */
    fun withUntimedSet(): DetectorLootCounts = copy(sets = sets + 1, untimedSets = untimedSets + 1)

    /** Sets that have a duration, the divisor of the average. */
    val timedSets: Long get() = sets - untimedSets
}

/**
 * The `metaldetector` stats namespace, per SkyBlock profile. The session is persisted too, under the
 * shared tracker-session lifetime ([TrackerSessions]): kept across a quick restart, reset on a launch
 * more than 30 minutes after the client last ran.
 *
 * @property sessionMs detector time this session: counted while the HUD is up, so time spent
 *   elsewhere does not stretch the timer or the set times. 0 with [sessionStarted] false = not started.
 * @property setStartMs [sessionMs] at which the current tool set started (session start or last set).
 * @property setTools tool names collected toward the current set.
 */
@Serializable
data class DetectorLootStats(
    val session: DetectorLootCounts = DetectorLootCounts(),
    val total: DetectorLootCounts = DetectorLootCounts(),
    val sessionStarted: Boolean = false,
    val sessionMs: Long = 0L,
    val setStartMs: Long = 0L,
    val setTools: Set<String> = emptySet(),
    val setPartial: Boolean = true,
) {
    /**
     * Pure. A tool was found. [held] = names of the Divan tools in the inventory (may lag the find).
     * A partial set (tools were already held when it started) completes into the total only.
     */
    fun afterFind(item: String, qty: Long, held: Set<String>): DetectorLootStats {
        var s = copy(sessionStarted = true, session = session.withFind(item, qty), total = total.withFind(item, qty))
        if (!MetalDetectorLoot.isTool(item)) return s
        val tools = s.setTools + item
        val all = MetalDetectorLoot.TOOLS.keys
        if (setPartial) {
            return if ((tools + held).containsAll(all)) s.copy(total = s.total.withUntimedSet(), setTools = emptySet()) else s.copy(setTools = tools)
        }
        if (!tools.containsAll(all)) return s.copy(setTools = tools)
        val took = s.sessionMs - s.setStartMs
        // The tools stay in the inventory until handed in, so the next set is partial until they leave.
        s = s.copy(session = s.session.withSet(took), total = s.total.withSet(took), setTools = emptySet(), setStartMs = s.sessionMs, setPartial = true)
        return s
    }

    /** Pure. Inventory check: a partial set ends (a real one starts) once no Divan tool is held. */
    fun afterInventory(held: Set<String>): DetectorLootStats =
        if (setPartial && held.isEmpty()) copy(setPartial = false, setTools = emptySet(), setStartMs = sessionMs) else this
}

/**
 * Loot tracking for the Metal Detector HUD: parses `You found <item> [xN] with your Metal Detector!`,
 * keeps the session clock and tool-set timing, and the all-four-tools warning state.
 */
object MetalDetectorLoot {
    const val NAMESPACE = "metaldetector"
    const val GROUP_TOOLS = "Tools"
    const val GROUP_POWDER = "Powder"
    const val GROUP_OTHER = "Other"

    /** The four nucleus tools, by chat name -> SkyBlock id. */
    val TOOLS: Map<String, String> =
        linkedMapOf(
            "Scavenged Lapis Sword" to "DWARVEN_LAPIS_SWORD",
            "Scavenged Golden Hammer" to "DWARVEN_GOLD_HAMMER",
            "Scavenged Diamond Axe" to "DWARVEN_DIAMOND_AXE",
            "Scavenged Emerald Hammer" to "DWARVEN_EMERALD_HAMMER",
        )

    private const val LINGER_MS = 60_000L
    private val FOUND = Regex("""^You found (.+?) with your Metal Detector!$""")
    private val TRAILING_AMOUNT = Regex("""^(.+?)\s+x([\d,]+)$""")
    private val LEADING_AMOUNT = Regex("""^\+?([\d,]+)\s+(.+)$""")

    val handle: StatsHandle<DetectorLootStats> by lazy {
        PersistentStats.namespace(
            id = NAMESPACE,
            serializer = DetectorLootStats.serializer(),
            default = { DetectorLootStats() },
            key = { SkyblockProfileState.current ?: PersistentStats.LEGACY_KEY },
        )
    }

    /** Last tick the detector was held in the Mines of Divan; 0 = not since leaving. */
    private var lastHeldMs = 0L
    private var lastTickMs = 0L
    private var tick = 0

    /** All four tools are in the inventory right now (checked twice a second). */
    private var heldNames: Set<String> = emptySet()

    var allToolsHeld = false
        private set

    /** How many of the four tools are in the inventory (same 10-tick read; 0 outside the Mines of Divan). */
    var toolCount = 0
        private set

    private fun toolIds(player: net.minecraft.world.entity.player.Player): Set<String> =
        player.inventory.getNonEquipmentItems().mapNotNullTo(HashSet()) { SkyblockItemUtils.getSkyblockId(it) }

    private fun countTools(player: net.minecraft.world.entity.player.Player): Int {
        val ids = toolIds(player)
        return TOOLS.values.count { it in ids }
    }

    fun register() {
        TrackerSessions.resetOnFreshStart(handle) { withoutSession() }
        Events.subscribe<ChatMessage> { onChat(it.raw) }
        ClientTickEvents.END_CLIENT_TICK.register { onTick(it) }
        // A run always needs the full set, so further tool finds are wasted until one goes to storage.
        Alerts.showWhile(
            "metal_detector_tools",
            "All tools in inventory!",
            "Store tools",
            priority = 10,
            sound = { cfg.mining.metalDetector.toolsAlertSound },
            enabled = { cfg.mining.metalDetector.hud && cfg.mining.metalDetector.toolsAlert },
        ) { warningActive() }
    }

    fun isTool(item: String): Boolean = item in TOOLS

    fun group(item: String): String =
        if (isTool(item)) GROUP_TOOLS else if (item.contains("Powder")) GROUP_POWDER else GROUP_OTHER

    /** HUD visibility: held in the Mines of Divan within the last minute; leaving hides at once. */
    fun hudVisible(): Boolean =
        cfg.mining.metalDetector.hud && lastHeldMs != 0L && System.currentTimeMillis() - lastHeldMs <= LINGER_MS

    fun warningActive(): Boolean = cfg.mining.metalDetector.toolsAlert && hudVisible() && allToolsHeld

    /** Pure: `Flawed Ruby Gemstone x2` -> (name, 2); `+1,250 Gemstone Powder` -> (name, 1250). */
    fun parse(stripped: String): Pair<String, Long>? {
        val body = FOUND.matchEntire(stripped.trim())?.groupValues?.get(1)?.trim() ?: return null
        TRAILING_AMOUNT.matchEntire(body)?.let { return it.groupValues[1].trim() to it.groupValues[2].replace(",", "").toLong() }
        LEADING_AMOUNT.matchEntire(body)?.let { return it.groupValues[2].trim() to it.groupValues[1].replace(",", "").toLong() }
        return body to 1L
    }

    /** Colors and Hypixel's private-use icon glyphs off, whitespace collapsed. */
    fun clean(raw: String): String =
        MessageDetector.stripColorCodes(raw).filterNot { it in '\uE000'..'\uF8FF' }.replace(Regex("\\s+"), " ").trim()

    fun resetSession() {
        handle.update { withoutSession() }
    }

    private fun DetectorLootStats.withoutSession(): DetectorLootStats =
        copy(session = DetectorLootCounts(), sessionStarted = false, sessionMs = 0L, setStartMs = 0L, setTools = emptySet(), setPartial = true)

    private fun onChat(raw: String) {
        if (!cfg.mining.metalDetector.hud) return
        val (item, qty) = parse(clean(raw)) ?: return
        handle.update { afterFind(item, qty, heldNames) }
    }

    private fun onTick(mc: Minecraft) {
        val now = System.currentTimeMillis()
        val elapsed = if (lastTickMs == 0L) 0L else (now - lastTickMs).coerceIn(0L, 1_000L)
        lastTickMs = now
        val md = cfg.mining.metalDetector
        if (!md.hud && !md.toolsCounter) return
        if (!SkyblockLocation.isInSubLocation(MetalDetector.SUBLOCATION)) {
            lastHeldMs = 0L
            allToolsHeld = false
            toolCount = 0
            return
        }
        val player = mc.player ?: return
        if (!md.hud) {
            if (++tick % 10 == 0) toolCount = countTools(player)
            return
        }
        if (SkyblockItemUtils.getSkyblockId(player.mainHandItem) == MetalDetector.DETECTOR_ID) {
            lastHeldMs = now
            if (!handle.read().sessionStarted) handle.update { copy(sessionStarted = true) }
        }
        // The clock only runs for a set that started with no tools in the inventory.
        val st = handle.read()
        if (hudVisible() && st.sessionStarted && !st.setPartial && elapsed > 0) handle.update { copy(sessionMs = sessionMs + elapsed) }
        if (++tick % 10 != 0) return
        val ids = toolIds(player)
        allToolsHeld = ids.containsAll(TOOLS.values)
        heldNames = TOOLS.filterValues { it in ids }.keys
        toolCount = heldNames.size
        handle.update { afterInventory(heldNames) }
    }
}
