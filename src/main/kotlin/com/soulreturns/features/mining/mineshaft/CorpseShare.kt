package com.soulreturns.features.mining.mineshaft

import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.HypixelNetwork
import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.features.party.PartyManager
import com.soulreturns.platform.concurrent.SoulScope
import com.soulreturns.platform.http.BackendClient
import com.soulreturns.platform.http.BackendResult
import com.soulreturns.platform.realtime.RealtimeClient
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MojangUuid
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import net.minecraft.client.Minecraft
import java.util.UUID

/**
 * Party sharing for the corpse locator (rule://mineshaft): sends this stay's located corpses to the
 * party members on the same server through `POST /mineshaft/sync` and merges theirs
 * ([CorpseLocator.receive]). Payload and accept rules: [CorpseSharePayload]. Client thread only,
 * except the realtime handler, which hops onto it first.
 */
object CorpseShare {
    private const val INTENT = "mineshaft-sync"
    private const val ENDPOINT = "/mineshaft/sync"

    /** A changed list waits this long for more changes before it goes out. */
    private const val DEBOUNCE_MS = 250L

    /** At most one send per second (the endpoint allows about 60 a minute per token). */
    private const val MIN_GAP_MS = 1000L

    /** Party roster from the mod API (includes the player); empty outside a party. */
    @Volatile private var party: Set<UUID> = emptySet()

    private var layout: String? = null
    private var helloSent = false
    private var sentSignature: Any? = null
    private var signature: Any? = null
    private var changedAt = 0L
    private var replyDue = false
    private var lastSentAt = 0L

    private var sent = 0
    private var received = 0
    private var dropped = 0
    private var lastDrop: String? = null
    private var lastError: String? = null

    fun register() {
        RealtimeClient.onRealtimeEvent("mineshaft-sync") { event -> Minecraft.getInstance().execute { onEvent(event.topic, event.data) } }
        Events.subscribe<HypixelModApi.PartyInfoEvent> { party = it.members }
        PartyManager.addListener { if (layout != null) HypixelModApi.requestPartyInfo() }
    }

    private fun enabled(): Boolean = CorpseLocator.enabled() && cfg.mining.mineshaft.corpseLocatorShare

    /** A stay's layout is known: ask for the roster (the hello goes once it is there). */
    fun onStay(id: String) {
        layout = id
        helloSent = false
        sentSignature = null
        signature = null
        replyDue = false
        if (enabled()) HypixelModApi.requestPartyInfo()
    }

    fun reset() {
        layout = null
        helloSent = false
        sentSignature = null
        signature = null
        replyDue = false
    }

    /** Each mineshaft tick; [ownSignature] changes whenever the own located list or a type does. */
    fun tick(ownSignature: Any) {
        val id = layout ?: return
        // Nothing from Alpha reaches the backend (rule://backend); AlphaGate refuses the POST as well.
        if (!enabled() || HypixelNetwork.isAlpha) return
        val now = System.currentTimeMillis()
        if (ownSignature != signature) {
            signature = ownSignature
            changedAt = now
        }
        if (party.size < 2) return
        if (!helloSent) {
            helloSent = send(CorpseSharePayload.hello(id))
            return
        }
        val due = replyDue || signature != sentSignature
        if (!due || now - changedAt < DEBOUNCE_MS || now - lastSentAt < MIN_GAP_MS) return
        val corpses = CorpseLocator.ownCorpses()
        if (corpses.isEmpty() && sentSignature == null) {
            replyDue = false
            sentSignature = signature
            return
        }
        if (send(CorpseSharePayload.state(id, corpses))) {
            sentSignature = signature
            replyDue = false
        }
    }

    private fun send(message: JsonObject): Boolean {
        val server = LocationApi.serverId ?: return false
        val self = Minecraft.getInstance().user.profileId
        val body = CorpseSharePayload.body(server, party, self, message) ?: return false
        lastSentAt = System.currentTimeMillis()
        sent++
        SoulScope.io.launch {
            val result =
                try {
                    BackendClient.postUnit(ENDPOINT, body, JsonObject.serializer(), INTENT)
                } catch (t: Throwable) {
                    lastError = t.message ?: t.javaClass.simpleName
                    return@launch
                }
            if (result !is BackendResult.Ok) lastError = (result as BackendResult.Failure).message
        }
        return true
    }

    private fun onEvent(topic: String?, data: JsonObject) {
        if (!enabled()) return
        val self = Minecraft.getInstance().user.profileId
        val parsed = CorpseSharePayload.parse(topic, data, self, LocationApi.serverId, layout, party)
        if (parsed is CorpseSharePayload.Parsed.Drop) {
            dropped++
            lastDrop = parsed.reason
            DebugLogger.logFeatureEvent("Corpse share: dropped (${parsed.reason})")
            return
        }
        val message = (parsed as CorpseSharePayload.Parsed.Ok).message
        received++
        if (message is CorpseSharePayload.State) {
            CorpseLocator.receive(name(message.sender), message.corpses)
        } else {
            // A late joiner: everyone answers with their full state.
            replyDue = true
        }
    }

    private fun name(uuid: UUID): String =
        Minecraft.getInstance().connection?.getPlayerInfo(uuid)?.profile?.name ?: MojangUuid.undashed(uuid)

    fun state(): Map<String, Any?> =
        linkedMapOf(
            "enabled" to enabled(),
            "party" to party.size,
            "hello_sent" to helloSent,
            "sent" to sent,
            "received" to received,
            "dropped" to dropped,
            "last_drop" to lastDrop,
            "last_error" to lastError,
        )
}
