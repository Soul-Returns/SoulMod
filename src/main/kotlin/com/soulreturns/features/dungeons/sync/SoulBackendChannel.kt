package com.soulreturns.features.dungeons.sync

import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.DungeonRunState
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.SyncLog
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Primary sync channel — Soul's own backend at `sky.soulreturns.com`.
 *
 * Inbound uses only our private user topic on the shared [RealtimeClient] stream, then checks the
 * current instance and sender roster. This channel owns no socket or backoff.
 *
 * Outbound: `POST /dungeon/sync {serverId, recipients, message}`. Recipients come only from the
 * current dungeon roster; the backend stamps sender identity but cannot attest party membership.
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
            if (!DungeonSyncPayload.accepts(event.topic, event.data, serverId, Minecraft.getInstance().user.profileId)) {
                SyncLog.ws("backend", "← dropped dungeon-secret — wrong recipient or instance")
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
        val self = Minecraft.getInstance().player?.uuid ?: return
        val body = DungeonSyncPayload.build(sid, DungeonRunState.players.asSequence().map { it?.uuid }, self, message)
            ?: return
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
