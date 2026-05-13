package com.soulreturns.features.mining.mineshaft

import com.google.gson.JsonObject
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.AreaChanged
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MobSpotter
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import java.time.Instant

/**
 * Logs Glacite Mineshaft visits to the backend so per-user stats can be aggregated server-side.
 *
 * Visit lifecycle:
 *  - **Entry** — `AreaChanged` into `Mineshaft` records `enteredAt` and clears the visit state.
 *  - **During** — each tick we opportunistically (a) capture the mineshaft identifier from
 *    [MineshaftScoreboard] once the sublocation reads `Glacite Mineshafts`, and (b) flip
 *    `littlefootFound = true` the first time `MobSpotter.findVisible("Littlefoot")` returns a
 *    visible entity. The LOS gate is the same ToS-safe one used by `LittlefootAlert`.
 *  - **Exit** — `AreaChanged` away from `Mineshaft` snapshots `MineshaftCorpses.byType` and
 *    POSTs `/mineshaft/visit`. Reads happen synchronously inside the event handler so the
 *    snapshot reflects the state at the moment of leaving, before `MineshaftCorpses` next
 *    tick clears it.
 *
 * The POST is fire-and-forget on the backend executor; on failure (network, 4xx, 5xx) we log
 * a warning and drop the visit — better to lose one data point than to retry / queue locally.
 */
object MineshaftVisitTracker {
    private val logger = SoulLogger("Soul/MineshaftVisitTracker")

    private const val AREA_NAME = "Mineshaft"
    private const val ENDPOINT = "/mineshaft/visit"

    @Volatile private var enteredAt: Instant? = null

    @Volatile private var mineshaftType: String? = null

    @Volatile private var serverInstance: String? = null

    @Volatile private var littlefootFound: Boolean = false

    fun register() {
        Events.subscribe(this)
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ -> tick() }
        )
    }

    @HandleEvent
    fun onAreaChanged(event: AreaChanged) {
        val left = event.previous == AREA_NAME && event.current != AREA_NAME
        val entered = event.previous != AREA_NAME && event.current == AREA_NAME

        if (left) finishVisit()
        if (entered) startVisit()
    }

    private fun startVisit() {
        if (!cfg.dev.data.logMineshaftVisits()) return
        enteredAt = Instant.now()
        mineshaftType = null
        serverInstance = null
        littlefootFound = false
        DebugLogger.logFeatureEvent("MineshaftVisitTracker: visit started")
    }

    private fun tick() {
        if (enteredAt == null) return
        if (!cfg.dev.data.logMineshaftVisits()) {
            // Toggle turned off mid-visit — abandon, don't POST.
            enteredAt = null
            return
        }
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
        // Snapshot now — MineshaftCorpses clears its map on the next tick once we leave the area.
        val corpses = MineshaftCorpses.byType.toMap()

        // Always reset, even if we skip the POST below.
        enteredAt = null
        mineshaftType = null
        serverInstance = null
        littlefootFound = false

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
            }

        BackendClient.post(ENDPOINT, payload, intent = "mineshaft-visit")
            .thenAccept { result ->
                when (result) {
                    is BackendClient.Result.Ok ->
                        logger.info("POST $ENDPOINT ok — type=$type lf=$foundLf corpses=${corpses.size}")
                    is BackendClient.Result.Error ->
                        logger.warn("POST $ENDPOINT failed ${result.statusCode}: ${result.message}")
                }
            }
    }
}
