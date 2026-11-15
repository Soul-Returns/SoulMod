package com.soulreturns.features.dungeons.sync

import com.soulreturns.config.cfg
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.SyncLog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Primary sync channel — Soul's own backend at `sky.soulreturns.com`.
 *
 * **Inbound** rides the shared [RealtimeClient] connection: the backend authorises the URI template
 * `dungeon/{serverId}` and the Mercure hub honours that template as a *subscribe selector*, so the
 * one always-on stream already carries every instance the player enters. (Verified empirically
 * against Mercure 0.24.2: the template selector delivers a publish to a concrete `dungeon/<id>`;
 * a wildcard glob under `dungeon` does not, and must not be used.) This channel owns no thread, no
 * socket and no backoff of its own — it registers one handler and is done.
 *
 * **Outbound** is still plain REST: `POST /dungeon/sync {serverId, message}`. The backend stamps the
 * sender uuid from the bearer token and re-broadcasts on the instance topic, so it is a dumb relay
 * and the wire schema of `message` stays ours.
 *
 * No User-Agent whitelist (unlike Skyblocker's hub) — this is our infrastructure.
 */
object SoulBackendChannel : SecretSyncChannel {
    override val id = "soul-backend"

    private val logger = SoulLogger("Soul/BackendSync")

    /** `X-Intent` for every call this channel makes; the backend's usage dashboard groups by it. */
    private const val INTENT = "dungeon-sync"

    @Volatile private var serverId: String? = null

    // ------------------------------------------------------- live status (dev diagnostics)

    @Volatile private var lastError: String? = null
    @Volatile private var lastInboundAt: Instant? = null
    @Volatile private var lastPublishAt: Instant? = null
    private val eventsReceived = AtomicLong()
    private val eventsPublished = AtomicLong()

    /** Snapshot of the channel's live state, dumped by `/soul dev backend`. */
    data class Status(
        val enabled: Boolean,
        val serverId: String?,
        /** The shared realtime stream this channel's inbound half depends on. */
        val realtimeConnected: Boolean,
        val eventsReceived: Long,
        val lastInboundAt: Instant?,
        val eventsPublished: Long,
        val lastPublishAt: Instant?,
        val lastError: String?,
    )

    fun status(): Status = Status(
        enabled = try {
            isEnabled()
        } catch (_: Throwable) {
            false
        },
        serverId = serverId,
        realtimeConnected = RealtimeClient.status().connected,
        eventsReceived = eventsReceived.get(),
        lastInboundAt = lastInboundAt,
        eventsPublished = eventsPublished.get(),
        lastPublishAt = lastPublishAt,
        lastError = lastError,
    )

    /** Subscribe to the inbound half. Call once at startup, before [SecretSyncHandler.register]. */
    fun register() {
        RealtimeClient.onRealtimeEvent("dungeon-secret") { event ->
            // The shared stream carries `dungeon/{serverId}` for every instance the template covers.
            // In practice nobody publishes to an instance they are not in, but relevance is now this
            // channel's job to decide rather than something the subscription guaranteed.
            if (serverId == null) {
                SyncLog.ws("backend", "← dropped dungeon-secret — not in a run")
                return@onRealtimeEvent
            }
            eventsReceived.incrementAndGet()
            lastInboundAt = Instant.now()
            SyncLog.ws("backend", "← ${event.data}")
            SecretSyncHandler.handleInbound(event.data, validateSender = true)
        }
    }

    override fun isEnabled(): Boolean = cfg.dungeons.secretsSyncEnabled

    override fun onDungeonStart(serverId: String) {
        if (this.serverId == serverId) return
        this.serverId = serverId
        SyncLog.ws("backend", "onDungeonStart($serverId) — inbound rides the shared realtime stream")
    }

    override fun onDungeonEnd() {
        if (serverId == null) return
        SyncLog.ws("backend", "onDungeonEnd")
        serverId = null
    }

    override fun publish(message: JsonObject) {
        val sid = serverId ?: return
        val body = buildJsonObject {
            put("serverId", sid)
            put("message", message)
        }
        SoulScope.io.launch {
            SyncLog.ws("backend", "POST /dungeon/sync → $body")
            val result = try {
                BackendClient.postUnit("/dungeon/sync", body, JsonObject.serializer(), INTENT)
            } catch (t: Throwable) {
                logger.warn("Backend publish failed", t)
                lastError = "publish failed: ${t.message ?: t.javaClass.simpleName}"
                return@launch
            }
            if (result is BackendResult.Ok) {
                eventsPublished.incrementAndGet()
                lastPublishAt = Instant.now()
            } else {
                val failure = result as BackendResult.Failure
                SyncLog.ws("backend", "POST /dungeon/sync failed: ${failure.message}")
                lastError = "POST /dungeon/sync ${failure.message}"
            }
        }
    }
}
