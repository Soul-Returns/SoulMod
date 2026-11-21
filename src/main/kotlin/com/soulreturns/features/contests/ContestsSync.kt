package com.soulreturns.features.contests

import com.soulreturns.core.events.Events
import com.soulreturns.features.contests.model.ContestStates
import com.soulreturns.features.contests.model.ContestUpdate
import com.soulreturns.features.contests.model.FriendUpdate
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import kotlinx.serialization.json.JsonObject

/**
 * Wires the store to the outside: realtime `contest-update` / `friend-update` on the own `user/<uuid>`
 * topic (hopped to the client thread), the first load once a production connection is confirmed, the
 * refetch after the stream reconnects, the outbox flush, and the lazy poll while a held contest runs and
 * the stream is down.
 */
object ContestsSync {
    private val logger = SoulLogger("Soul/Contests")
    private const val TICK_EVERY = 20

    private var tick = 0
    private var loadedThisConnection = false
    private var lastConnectedSince: java.time.Instant? = null
    private var lastPoll = 0L

    fun register() {
        RealtimeClient.onRealtimeEvent("contest-update") { e -> hop { onContest(e.topic, e.data) } }
        RealtimeClient.onRealtimeEvent("friend-update") { e -> hop { onFriend(e.topic, e.data) } }
        Events.subscribe<PlayerDataset.Resolved> { if (it.dataset == PlayerDataset.PRODUCTION) firstLoad() }
        ClientTickEvents.END_CLIENT_TICK.register { if (++tick % TICK_EVERY == 0) onSecond() }
        net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            hop { loadedThisConnection = false }
        }
        ContestOutbox.load()
    }

    private fun hop(block: () -> Unit) {
        val mc = Minecraft.getInstance()
        if (mc.isSameThread) block() else mc.execute(block)
    }

    /** True for the signed-in player's own topic only (frames carry no topic; the envelope does). */
    internal fun ownTopic(topic: String?): Boolean {
        val self = ContestsStore.selfUuid() ?: return false
        return topic == "user/$self"
    }

    internal fun onContest(topic: String?, data: JsonObject) {
        if (!ownTopic(topic)) return
        val update = try {
            ContestsApi.json.decodeFromJsonElement(ContestUpdate.serializer(), data)
        } catch (t: Throwable) {
            logger.warn("Dropped malformed contest-update: ${t.message}")
            return
        }
        if (update.contestId <= 0) return
        ContestsStore.onContestUpdate(update)
    }

    internal fun onFriend(topic: String?, data: JsonObject) {
        if (!ownTopic(topic)) return
        val update = try {
            ContestsApi.json.decodeFromJsonElement(FriendUpdate.serializer(), data)
        } catch (t: Throwable) {
            logger.warn("Dropped malformed friend-update: ${t.message}")
            return
        }
        ContestsStore.onFriendUpdate(update)
    }

    private fun firstLoad() {
        if (loadedThisConnection) return
        loadedThisConnection = true
        ContestsStore.refreshContests()
        ContestsStore.refreshFriends()
        ContestOutbox.flush(force = true)
    }

    private fun onSecond() {
        val status = RealtimeClient.status()
        val since = status.connectedSince
        val connected = status.connected && since != null
        ContestsStore.setStream(connected)
        // A new stream: events sent while it was down are lost (no replay), so re-read everything shown.
        if (connected && since != lastConnectedSince) {
            lastConnectedSince = since
            if (loadedThisConnection) refetchAll()
        }
        if (!loadedThisConnection) return
        val now = System.currentTimeMillis()
        if (!connected && ContestsStore.runningSnapshots().isNotEmpty() && now - lastPoll >= ContestsStore.POLL_MS) {
            lastPoll = now
            ContestsStore.runningSnapshots().forEach { ContestsStore.refreshContest(it.contest.id) }
            ContestsStore.openId?.let { id -> if (ContestsStore.runningSnapshots().none { it.contest.id == id }) ContestsStore.refreshContest(id) }
        }
        if (tick % (TICK_EVERY * 30) == 0) ContestOutbox.flush()
    }

    private fun refetchAll() {
        logger.info("Realtime reconnected: refetching contests")
        ContestsStore.refreshContests()
        ContestsStore.refreshFriends()
        ContestsStore.heldSnapshots()
            .filter { !ContestStates.isOver(it.contest.state) || it.contest.id == ContestsStore.openId }
            .forEach { ContestsStore.refreshContest(it.contest.id) }
        ContestOutbox.flush(force = true)
    }
}
