package com.soulreturns.platform.http

import com.google.gson.JsonObject
import com.soulreturns.core.events.Events
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.model.AreaChanged
import com.soulreturns.data.model.OnSkyblockChanged
import com.soulreturns.data.model.SublocationChanged
import com.soulreturns.data.skyblock.SkyblockApi
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Pushes the player's coarse state to the backend over REST: which Hypixel server they're
 * on, whether they're in SkyBlock, and (if so) the current area / sublocation. The backend
 * stores the latest state on `player_presence`.
 *
 * **Online signal is the Mercure SSE connection lifetime** ([RealtimeClient][com.soulreturns.platform.realtime.RealtimeClient]) — the
 * backend treats "live SSE connection" as "player is online" and updates `last_seen` on
 * connect/disconnect. This service does **not** heartbeat; it only publishes state on
 * transitions. There's no `/ping` anymore.
 *
 * Triggers a debounced `POST /presence/state` on:
 *   - First start (initial state push so the backend has the current snapshot).
 *   - [AreaChanged] / [SublocationChanged] from `LocationReader`.
 *   - [OnSkyblockChanged] from `SkyblockReader`.
 *   - Hypixel-server-address change (polled at tick rate — there's no dedicated event for
 *     this since it's just `Minecraft.currentServer.ip` flipping).
 *
 * Debounce coalesces a burst of state transitions (e.g. joining a server fires server
 * change → on-skyblock change → area change → sublocation change within a few ticks) into
 * a single POST. The `lastSent` snapshot dedupes against the previous push so a refresh
 * with the same content is a no-op.
 */
object PresenceService {
    private val logger = SoulLogger("Soul/Presence")

    /** Coalesce window for state changes. Long enough for a server-join burst to settle. */
    private const val DEBOUNCE_MS = 2_000L

    private val scheduler =
        Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "soul-presence").also { it.isDaemon = true }
        }

    private data class State(
        val server: String?,
        val onSkyblock: Boolean,
        val area: String?,
        val subLocation: String?,
    )

    private val lastSent = AtomicReference<State?>(null)
    private val pending = AtomicReference<ScheduledFuture<*>?>(null)

    @Volatile private var lastObservedServer: String? = null

    fun start() {
        Events.subscribe<AreaChanged> { schedulePush() }
        Events.subscribe<SublocationChanged> { schedulePush() }
        Events.subscribe<OnSkyblockChanged> { schedulePush() }

        // Server-address change has no event source; cheap to poll on tick and dispatch.
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                val current = Minecraft.getInstance().currentServer?.ip
                if (current != lastObservedServer) {
                    lastObservedServer = current
                    schedulePush()
                }
            }
        )

        // Initial push so the backend has the player's current snapshot without waiting for
        // a transition. Same debounce window — gives auth + readers a moment to populate.
        schedulePush()
        logger.debug("PresenceService started (event-driven)")
    }

    private fun schedulePush() {
        pending.getAndSet(
            scheduler.schedule(::doPush, DEBOUNCE_MS, TimeUnit.MILLISECONDS),
        )?.cancel(false)
    }

    private fun doPush() {
        pending.set(null)
        val current = buildState()
        if (current == lastSent.get()) return
        val body =
            JsonObject().apply {
                if (current.server != null) addProperty("server", current.server) else add("server", null)
                addProperty("onSkyblock", current.onSkyblock)
                if (current.area != null) addProperty("area", current.area) else add("area", null)
                if (current.subLocation != null) {
                    addProperty("subLocation", current.subLocation)
                } else {
                    add("subLocation", null)
                }
            }
        try {
            val result = BackendClient.post("/presence/state", body, intent = "presence-state").join()
            when (result) {
                is BackendClient.Result.Ok -> lastSent.set(current)
                is BackendClient.Result.Error -> {
                    logger.debug("Presence state push failed: HTTP ${result.statusCode} ${result.message}")
                    // Don't update lastSent — retry on next change.
                }
            }
        } catch (_: Throwable) {
            // Network blip; next change will retry.
        }
    }

    private fun buildState(): State {
        val onSkyblock = SkyblockApi.isOnSkyblock
        return State(
            server = Minecraft.getInstance().currentServer?.ip,
            onSkyblock = onSkyblock,
            area = if (onSkyblock) LocationApi.currentArea else null,
            subLocation = if (onSkyblock) LocationApi.currentSublocation else null,
        )
    }
}
