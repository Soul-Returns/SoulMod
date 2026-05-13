package com.soulreturns.features.mining.mineshaft

import com.google.gson.JsonObject
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.AreaChanged
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MobSpotter
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.time.Instant

/**
 * Logs Glacite Mineshaft visits to the backend so per-user stats can be aggregated server-side.
 *
 * Visit lifecycle:
 *  - **Entry** — `AreaChanged` into `Mineshaft` records `enteredAt`, clears per-visit state, and
 *    classifies the visit's [VisitSource] (DISCOVERED vs WARPED) from short-lived chat-state set
 *    a few seconds earlier by [onChat]. See "Source classification" below.
 *  - **During** — each tick we opportunistically (a) capture the mineshaft identifier from
 *    [MineshaftScoreboard] once the sublocation reads `Glacite Mineshafts`, and (b) flip
 *    `littlefootFound = true` the first time `MobSpotter.findVisible("Littlefoot")` returns a
 *    visible entity. The LOS gate is the same ToS-safe one used by `LittlefootAlert`.
 *  - **Exit** — `AreaChanged` away from `Mineshaft` snapshots `MineshaftCorpses.byType` and POSTs
 *    `/mineshaft/visit`. Reads happen synchronously inside the event handler so the snapshot
 *    reflects the state at the moment of leaving, before `MineshaftCorpses` next tick clears it.
 *
 * **Source classification.** Two chat lines differentiate the two ways into a Glacite Mineshaft:
 *  - `Sending to Mineshaft...` — fires only for self-discovery (you triggered the mineshaft warp
 *    yourself by breaking glacite/ice in Dwarven Mines).
 *  - `Party Leader, <display>, summoned you to their server.` — fires only when a party leader's
 *    `/p warp` pulls you into their instance. `<display>` includes the rank prefix
 *    (`[MVP+] Luckyking1105`); we extract the bare username for `warpedBy`.
 *
 * Both fire **before** `AreaChanged` (during the previous world, before the transfer), so we
 * remember them as pending timestamps with a [PENDING_TTL_MS] window and resolve at start-of-visit.
 * The "discovered" signal wins ties — if both fire within the window (which shouldn't happen),
 * we trust the mineshaft-specific message.
 *
 * **Used by alert features for gating.** [isWarpedVisit] lets `LapisCorpseAlert`,
 * `VanguardCorpseAlert`, and `LittlefootAlert` skip their `!ptme` sends when you're a guest in
 * someone else's mineshaft. Source classification runs even when [logMineshaftVisits] is off so
 * the gate works regardless of the user's telemetry preference.
 *
 * The POST is fire-and-forget on the backend executor; on failure (network, 4xx, 5xx) we log a
 * warning and drop the visit — better to lose one data point than to retry / queue locally.
 */
object MineshaftVisitTracker {
    enum class VisitSource { DISCOVERED, WARPED, UNKNOWN }

    private val logger = SoulLogger("Soul/MineshaftVisitTracker")

    private const val AREA_NAME = "Mineshaft"
    private const val ENDPOINT = "/mineshaft/visit"

    /** Max age of a pre-entry chat signal that can still classify the next mineshaft entry. */
    private const val PENDING_TTL_MS = 10_000L

    private val SENDING_TO_MINESHAFT = Regex("Sending to Mineshaft\\.\\.\\.", RegexOption.IGNORE_CASE)
    private val SUMMONED_BY_LEADER = Regex("""^Party Leader, (.+?), summoned you to their server\.$""")

    // Per-visit state
    @Volatile private var enteredAt: Instant? = null

    @Volatile private var mineshaftType: String? = null

    @Volatile private var serverInstance: String? = null

    @Volatile private var littlefootFound: Boolean = false

    @Volatile var visitSource: VisitSource = VisitSource.UNKNOWN
        private set

    @Volatile var warpedBy: String? = null
        private set

    // Pre-entry chat signals — pending until resolved at startVisit().
    @Volatile private var pendingDiscoveredAtMs: Long = 0L

    @Volatile private var pendingWarpedFromName: String? = null

    @Volatile private var pendingWarpedAtMs: Long = 0L

    /** True when the current (or most recent) visit was classified as a party-warp arrival. */
    fun isWarpedVisit(): Boolean = visitSource == VisitSource.WARPED

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ -> tick() }
        )
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        if (SENDING_TO_MINESHAFT.containsMatchIn(stripped)) {
            pendingDiscoveredAtMs = System.currentTimeMillis()
            return
        }
        SUMMONED_BY_LEADER.matchEntire(stripped)?.let { m ->
            pendingWarpedFromName = extractUsername(m.groupValues[1].trim())
            pendingWarpedAtMs = System.currentTimeMillis()
        }
    }

    @HandleEvent
    fun onAreaChanged(event: AreaChanged) {
        val left = event.previous == AREA_NAME && event.current != AREA_NAME
        val entered = event.previous != AREA_NAME && event.current == AREA_NAME
        if (left) finishVisit()
        if (entered) startVisit()
    }

    private fun startVisit() {
        enteredAt = Instant.now()
        mineshaftType = null
        serverInstance = null
        littlefootFound = false

        val now = System.currentTimeMillis()
        val discoveredFresh = pendingDiscoveredAtMs != 0L && now - pendingDiscoveredAtMs <= PENDING_TTL_MS
        val warpedFresh =
            pendingWarpedFromName != null &&
                pendingWarpedAtMs != 0L &&
                now - pendingWarpedAtMs <= PENDING_TTL_MS

        visitSource =
            when {
                discoveredFresh -> VisitSource.DISCOVERED
                warpedFresh -> VisitSource.WARPED
                else -> VisitSource.UNKNOWN
            }
        warpedBy = if (visitSource == VisitSource.WARPED) pendingWarpedFromName else null

        // Consume pending state so a later visit doesn't inherit a stale classification.
        pendingDiscoveredAtMs = 0L
        pendingWarpedFromName = null
        pendingWarpedAtMs = 0L

        DebugLogger.logFeatureEvent("MineshaftVisitTracker: visit started — source=$visitSource warpedBy=$warpedBy")
    }

    private fun tick() {
        if (enteredAt == null) return
        if (!cfg.dev.data.logMineshaftVisits()) return
        if (mineshaftType == null) {
            val parsed = MineshaftScoreboard.readIdentifier()
            if (parsed != null) {
                mineshaftType = parsed.first
                serverInstance = parsed.second
                DebugLogger.logFeatureEvent("MineshaftVisitTracker: identifier ${parsed.second}")
            }
        }
        if (!littlefootFound && MobSpotter.findVisible("Littlefoot") != null) {
            littlefootFound = true
            DebugLogger.logFeatureEvent("MineshaftVisitTracker: Littlefoot LOS confirmed this visit")
        }
    }

    private fun finishVisit() {
        val entered = enteredAt ?: return
        val left = Instant.now()
        val type = mineshaftType
        val instance = serverInstance
        val foundLf = littlefootFound
        val source = visitSource
        val warpedByCopy = warpedBy
        // Snapshot now — MineshaftCorpses clears its map on the next tick once we leave the area.
        val corpses = MineshaftCorpses.byType.toMap()

        // Always reset, even if we skip the POST below.
        enteredAt = null
        mineshaftType = null
        serverInstance = null
        littlefootFound = false
        visitSource = VisitSource.UNKNOWN
        warpedBy = null

        if (!cfg.dev.data.logMineshaftVisits()) return
        if (type == null || instance == null) {
            DebugLogger.logFeatureEvent("MineshaftVisitTracker: skipping POST — identifier never resolved")
            return
        }

        val payload =
            JsonObject().apply {
                addProperty("mineshaftType", type)
                addProperty("serverInstance", instance)
                addProperty("enteredAt", entered.toString())
                addProperty("leftAt", left.toString())
                add(
                    "corpses",
                    JsonObject().apply {
                        for ((corpseType, counts) in corpses) {
                            add(
                                corpseType,
                                JsonObject().apply {
                                    addProperty("found", counts.total)
                                    addProperty("looted", counts.looted)
                                }
                            )
                        }
                    }
                )
                addProperty("littlefootFound", foundLf)
                addProperty("warped", source == VisitSource.WARPED)
                warpedByCopy?.let { addProperty("warpedBy", it) }
            }

        BackendClient.post(ENDPOINT, payload, intent = "mineshaft-visit")
            .thenAccept { result ->
                when (result) {
                    is BackendClient.Result.Ok ->
                        logger.info(
                            "POST $ENDPOINT ok — type=$type source=$source warpedBy=$warpedByCopy lf=$foundLf corpses=${corpses.size}"
                        )
                    is BackendClient.Result.Error ->
                        logger.warn("POST $ENDPOINT failed ${result.statusCode}: ${result.message}")
                }
            }
    }

    /** Strip a Hypixel rank prefix from a display name → bare Mojang username. */
    private fun extractUsername(display: String): String =
        if (display.contains("] ")) {
            display.substringAfterLast("] ").substringBefore(" ").trim()
        } else {
            display.substringBefore(" ").trim()
        }
}
