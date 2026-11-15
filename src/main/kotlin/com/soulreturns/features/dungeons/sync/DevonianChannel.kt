package com.soulreturns.features.dungeons.sync

import com.soulreturns.api.HypixelModApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.features.dungeons.map.DungeonDataset
import com.soulreturns.features.dungeons.map.DungeonGrid
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.SyncLog
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import net.minecraft.client.Minecraft
import java.math.BigInteger
import java.net.URI
import java.net.http.WebSocket
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CompletionStage
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicReference

/**
 * Fallback channel: devonian's public relay at `ws://wss.docilelm.top/`, for teammates running
 * devonian rather than Soul. Its protocol is open (Mojang `joinServer`/`hasJoined` auth, no
 * User-Agent gate) and party-scoped by a `Party[<hash>]` subscription, where the hash is the sum
 * of party-member UUID hashCodes — identical between JVMs, so we land on the same channel as real
 * devonian clients.
 *
 * Partial by nature: devonian only syncs **per-room secret counts** (`RoomSecrets[cur/total, id]`),
 * keyed by its integer room id — which equals our own `roomId` (both from devonian's dataset), so
 * no translation is needed. Room matches
 * and mimic/prince kills are NOT carried (devonian scans rooms locally on every client).
 *
 * NOTE (2026-07): devonian's current public build ships secret sync **disabled** (their own config
 * says it doesn't track/sync secrets right now), so this channel will connect + subscribe but
 * receive nothing until they re-enable it. The client here is protocol-correct and ready; the gap
 * is on the peer side. Verify Soul↔Soul sync via [SoulBackendChannel] in the meantime.
 */
object DevonianChannel : SecretSyncChannel {
    override val id = "devonian"

    private val logger = SoulLogger("Soul/DevonianSync")
    private const val WS_URL = "ws://wss.docilelm.top/"
    private const val KEY_NAME = "devonianwebsocket"
    private val roomSecretsRegex = "^RoomSecrets\\[(\\d+)/(\\d+), (-?\\d+)]$".toRegex()

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "soul-devonian-sync").apply { isDaemon = true }
    }

    private val socket = AtomicReference<WebSocket?>(null)

    @Volatile private var active = false
    @Volatile private var partyMembers: Set<UUID> = emptySet()
    @Volatile private var lastPartyHash = Int.MIN_VALUE
    private var subscribed = false

    // The server acks a successful Authenticate with "3002". devonian only ever subscribes to the
    // party (Party[hash]) AFTER that ack — subscribing earlier is ignored server-side, which left
    // us connected-but-not-subscribed (received 3002 but no RoomSecrets). Gate party sends on this.
    @Volatile private var authAcked = false

    // devonian's RoomSecrets carries only (roomId, count) — no position — so we can only attach it
    // to a room we've already identified. Counts for not-yet-matched rooms are buffered here and
    // flushed when the room gets identified (by our own RoomMatcher or a Soul teammate's room_match).
    private val pendingCounts = java.util.concurrent.ConcurrentHashMap<Int, Int>()

    init {
        // Track the party roster so we can compute (and refresh) the devonian party hash.
        Events.subscribe<HypixelModApi.PartyInfoEvent> { event ->
            partyMembers = event.members
            SyncLog.ws("devonian", "party info: ${event.members.size} members ${event.members}")
            if (active && authAcked) sendPartyHash()
        }
        // When any room becomes identified, retry buffered devonian counts against it.
        Events.subscribe<DungeonGrid.RoomMatchedEvent> { flushPending() }
    }

    // Always-on fallback: whenever secret sync is enabled we also speak devonian, so teammates
    // running devonian (not Soul) are reached automatically with no separate toggle.
    override fun isEnabled(): Boolean = cfg.dungeons.secretsSyncEnabled

    override fun onDungeonStart(serverId: String) {
        if (active) return // idempotent — the lifecycle reconcile may call every tick
        active = true
        DungeonDataset.ensureLoaded()
        HypixelModApi.requestPartyInfo()
        SyncLog.ws("devonian", "onDungeonStart($serverId) — connecting")
        logger.info("Devonian fallback: connecting")
        executor.submit { connect() }
    }

    override fun onDungeonEnd() {
        if (!active && socket.get() == null) return // idempotent
        SyncLog.ws("devonian", "onDungeonEnd — disconnecting")
        active = false
        subscribed = false
        authAcked = false
        lastPartyHash = Int.MIN_VALUE
        pendingCounts.clear()
        socket.getAndSet(null)?.let {
            try {
                it.sendText("Disconnect", true)
                it.sendClose(WebSocket.NORMAL_CLOSURE, "left dungeon")
            } catch (_: Throwable) {
            }
        }
    }

    override fun publish(message: JsonObject) {
        // devonian only understands per-room secret counts.
        val type = message["type"]?.jsonPrimitive?.content
        if (type != "room_secret_count") {
            SyncLog.ws("devonian", "publish skipped (devonian carries only room_secret_count, not $type)")
            return
        }
        val roomId = message["roomId"]?.jsonPrimitive?.int ?: return
        val count = message["secretCount"]?.jsonPrimitive?.int ?: return
        // Our roomId IS devonian's roomID, so no translation needed.
        val total = DungeonGrid.roomById(roomId)?.secretsTotal
            ?: DungeonDataset.roomForId(roomId)?.secrets ?: 0
        SyncLog.ws("devonian", "publish RoomSecrets[$count/$total, $roomId]")
        send("RoomSecrets[$count/$total, $roomId]")
    }

    // ---------------------------------------------------------------- connection

    private fun connect() {
        val mc = Minecraft.getInstance()
        val session = mc.user ?: return
        val name = session.name ?: return
        val accessToken = session.accessToken ?: return
        val uuid = session.profileId ?: return
        val sessionService = mc.services()?.sessionService() ?: return

        val r = UUID.randomUUID().toString()
        try {
            // devonian's server verifies hasJoined(name, sha1(KEY_NAME + r)); we authenticate the
            // same random token via Mojang so only that random string ever hits their server.
            SyncLog.ws("devonian", "Mojang joinServer as $name (serverId=${hash(r)})")
            sessionService.joinServer(uuid, accessToken, hash(r))
        } catch (t: Throwable) {
            SyncLog.ws("devonian", "joinServer FAILED: ${t.message}")
            logger.warn("devonian joinServer failed: ${t.message}")
            active = false
            return
        }

        try {
            SyncLog.ws("devonian", "opening websocket $WS_URL")
            java.net.http.HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(URI.create(WS_URL), makeListener(name, r))
                .whenComplete { ws, error ->
                    if (error != null) {
                        SyncLog.ws("devonian", "ws connect FAILED: ${error.message}")
                        logger.warn("devonian ws connect failed: ${error.message}")
                        active = false
                    } else {
                        socket.set(ws)
                        SyncLog.ws("devonian", "websocket connected — waiting for 3002 auth ack before Party subscribe")
                        logger.info("Devonian fallback: connected to $WS_URL")
                        // Do NOT subscribe yet: devonian's server only honors Party[hash] AFTER it
                        // acks Authenticate with "3002" (see handleText). Subscribing here (pre-ack)
                        // is silently ignored.
                    }
                }
        } catch (t: Throwable) {
            logger.warn("devonian ws build failed", t)
            active = false
        }
    }

    /** Anonymous listener so it can close over the channel's private state (socket, subscribed). */
    private fun makeListener(name: String, r: String): WebSocket.Listener = object : WebSocket.Listener {
        private val parts = StringBuilder()

        override fun onOpen(webSocket: WebSocket) {
            SyncLog.ws("devonian", "→ Authenticate[$name, <token>]")
            webSocket.sendText("Authenticate[$name, $r]", true)
            webSocket.request(1)
        }

        override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
            parts.append(data)
            webSocket.request(1)
            if (last) {
                val msg = parts.toString()
                parts.setLength(0)
                try {
                    handleText(msg)
                } catch (t: Throwable) {
                    logger.debug("devonian handleText threw: ${t.message}")
                }
            }
            return null
        }

        override fun onError(webSocket: WebSocket, error: Throwable) {
            SyncLog.ws("devonian", "ws error: ${error.message}")
            logger.debug("devonian ws error: ${error.message}")
            socket.compareAndSet(webSocket, null)
        }

        override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
            SyncLog.ws("devonian", "ws closed ($statusCode: $reason)")
            socket.compareAndSet(webSocket, null)
            subscribed = false
            authAcked = false
            return null
        }
    }

    private fun send(msg: String) {
        val ws = socket.get()
        if (ws == null) {
            SyncLog.ws("devonian", "send dropped (not connected): $msg")
            return
        }
        try {
            SyncLog.ws("devonian", "→ $msg")
            ws.sendText(msg, true)
        } catch (t: Throwable) {
            SyncLog.ws("devonian", "send failed: ${t.message}")
        }
    }

    private fun sendPartyHash() {
        if (socket.get() == null) {
            SyncLog.ws("devonian", "party subscribe deferred — not connected")
            return
        }
        if (!authAcked) {
            SyncLog.ws("devonian", "party subscribe deferred — awaiting 3002 auth ack")
            return
        }
        val members = partyMembers
        if (members.isEmpty()) {
            SyncLog.ws("devonian", "party subscribe deferred — no party info yet (requested)")
            return
        }
        // devonian's server groups clients by sum of member UUID.hashCode() — identical across JVMs.
        val hash = members.sumOf { it.hashCode() }
        if (hash == lastPartyHash && subscribed) {
            SyncLog.ws("devonian", "party hash unchanged ($hash) — already subscribed")
            return
        }
        lastPartyHash = hash
        subscribed = true
        logger.info("Devonian fallback: subscribing party (hash=$hash, ${members.size} members)")
        send("Party[$hash]")
    }

    private fun handleText(msg: String) {
        if (!active) return
        SyncLog.ws("devonian", "← $msg")
        // Auth ack (devonian uses "3002" as the connected/ready marker) → NOW subscribe to the party.
        if (msg == "3002") {
            authAcked = true
            SyncLog.ws("devonian", "got 3002 (auth ack) — subscribing party")
            sendPartyHash()
            return
        }
        val match = roomSecretsRegex.matchEntire(msg) ?: return
        val current = match.groupValues[1].toIntOrNull() ?: return
        val roomId = match.groupValues[3].toIntOrNull() ?: return
        if (roomId < 0) return
        // Keep the highest count seen for this room id, then try to place it.
        pendingCounts.merge(roomId, current) { a, b -> maxOf(a, b) }
        Minecraft.getInstance().execute { applyOrBuffer(roomId) }
    }

    /** Apply a buffered devonian count if its room is identified locally; else keep it buffered. */
    private fun applyOrBuffer(roomId: Int) {
        val count = pendingCounts[roomId] ?: return
        if (DungeonGrid.roomById(roomId) == null) {
            SyncLog.ws("devonian", "RoomSecrets id $roomId ($count) — room not identified yet, buffering")
            return
        }
        SyncLog.ws("devonian", "RoomSecrets id $roomId → applying $count")
        SecretSyncHandler.applySecretCount(roomId, count)
        pendingCounts.remove(roomId)
    }

    /** Retry every buffered count — called when a room becomes identified. */
    private fun flushPending() {
        if (pendingCounts.isEmpty()) return
        for (id in pendingCounts.keys.toList()) applyOrBuffer(id)
    }

    private fun hash(r: String): String {
        val digest = MessageDigest.getInstance("sha1").digest((KEY_NAME + r).toByteArray())
        // Mirror devonian's exact (unpadded) BigInteger hex — the server hashes the same way.
        return BigInteger(1, digest).toString(16)
    }
}
