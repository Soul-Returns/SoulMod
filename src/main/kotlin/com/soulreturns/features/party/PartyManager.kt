package com.soulreturns.features.party

import com.soulreturns.Soul
import com.soulreturns.api.HypixelModApi
import com.soulreturns.api.HypixelNetwork
import com.soulreturns.core.events.Events
import com.soulreturns.features.dev.DevState
import com.soulreturns.util.ChatDisplayFilter
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageHandler
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import java.util.UUID
import java.util.concurrent.CopyOnWriteArraySet

/**
 * The local player's Hypixel party: chat lines, `/party list` and the mod API's party info fed into
 * [PartyTracker], which holds the state and decides when to resync. Call [register] once at init.
 */
object PartyManager {
    enum class PartyRole { LEADER, MODERATOR, MEMBER }

    data class PartyMember(
        val name: String,
        val displayName: String,
        val role: PartyRole,
        /** Known once the mod API (with the tab list or an unambiguous roster) named this uuid. */
        val uuid: UUID? = null,
    )

    data class PartyInvite(
        val from: String,
        val to: String,
        val outgoing: Boolean,
        val createdAt: Long,
        val expiresAt: Long,
    )

    data class PartyState(
        val leader: PartyMember?,
        val members: MutableMap<String, PartyMember>,
        val createdAt: Long,
        var lastUpdatedAt: Long,
        /** Members the mod API reported whose names are not known yet; counted in [size]. */
        val unnamedMembers: Set<UUID> = emptySet(),
    ) {
        val size: Int get() = members.size + unnamedMembers.size
    }

    enum class PartyDisbandReason {
        LEADER_DISBANDED,
        EMPTY_OR_EXPIRED,
        LEFT_PARTY,
        KICKED,
        UNKNOWN,
        LEADER_DISCONNECTED,
    }

    sealed class PartyEvent {
        data class PartyJoined(val state: PartyState, val joinedAsLeader: Boolean) : PartyEvent()

        data class PartyLeft(val previousState: PartyState, val reason: PartyDisbandReason) : PartyEvent()

        data class PartyDisbanded(val previousState: PartyState, val reason: PartyDisbandReason) : PartyEvent()

        data class MemberJoined(val member: PartyMember, val newState: PartyState) : PartyEvent()

        data class MemberLeft(val memberName: String, val newState: PartyState?) : PartyEvent()

        data class MemberKicked(val memberName: String, val by: String?, val newState: PartyState?) : PartyEvent()

        data class InviteReceived(val invite: PartyInvite) : PartyEvent()

        data class InviteSent(val invite: PartyInvite) : PartyEvent()

        data class InviteExpired(val invite: PartyInvite) : PartyEvent()

        data class PartySyncedFromList(val state: PartyState) : PartyEvent()

        data class RoleChanged(val member: PartyMember, val oldRole: PartyRole, val newRole: PartyRole) : PartyEvent()

        /** A mod-API packet or `/party list` changed a roster chat had built (drift corrected). */
        data class PartyCorrected(val previousState: PartyState?, val state: PartyState) : PartyEvent()
    }

    private val listeners = CopyOnWriteArraySet<(PartyEvent) -> Unit>()
    private val tracker = PartyTracker(::fire)
    private var isRegistered = false

    fun register() {
        if (isRegistered) return

        // Some /p list lines look like player messages to MessageDetector, so listen to both streams.
        MessageHandler.onServerMessage { message -> handleServerMessage(message) }
        MessageHandler.onPlayerMessage { message -> handleServerMessage(message) }
        Events.subscribe<HypixelModApi.PartyInfoEvent> { onPartyInfo(it) }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ ->
            identify()
            tracker.onHypixel = HypixelNetwork.current != HypixelNetwork.NONE
            tracker.onJoin(System.currentTimeMillis())
        }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ ->
            val mc = Minecraft.getInstance()
            if (mc.isSameThread) tracker.onDisconnect() else mc.execute { tracker.onDisconnect() }
        }
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
        ChatDisplayFilter.register { tracker.hidesEcho(it.string, System.currentTimeMillis()) }
        DevState.register("party") { tracker.diagnostics(System.currentTimeMillis()) }

        isRegistered = true
        Soul.getLogger().info("PartyManager registered for chat messages")
    }

    /** Send `/party list` now; its answer resyncs the roster and stays out of chat. */
    fun requestRefresh() {
        val player = Minecraft.getInstance().player ?: return
        tracker.onPartyListSent(System.currentTimeMillis())
        player.connection.sendCommand("party list")
        DebugLogger.logFeatureEvent("PartyManager: sent /party list")
    }

    fun addListener(listener: (PartyEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (PartyEvent) -> Unit) {
        listeners -= listener
    }

    fun isInParty(): Boolean = tracker.state != null

    fun isLeader(): Boolean {
        val me = localPlayerName() ?: return false
        return tracker.state?.leader?.name.equals(me, ignoreCase = true)
    }

    fun getPartyState(): PartyState? = tracker.state

    fun getLeader(): PartyMember? = tracker.state?.leader

    /** Named members only; [getPartySize] also counts members the mod API reported without a name. */
    fun getMembers(includeLeader: Boolean = true): List<PartyMember> {
        val state = tracker.state ?: return emptyList()
        return state.members.values.filter { includeLeader || it.role != PartyRole.LEADER }
    }

    fun getMemberNames(includeLeader: Boolean = true): List<String> = getMembers(includeLeader).map { it.name }

    fun getPartySize(): Int = tracker.state?.size ?: 0

    fun getPendingInvites(): List<PartyInvite> = tracker.pendingInvites(System.currentTimeMillis())

    /** Where the current state came from and the tracker's counters, for dev diagnostics. */
    fun diagnostics(): Map<String, Any?> = tracker.diagnostics(System.currentTimeMillis())

    /** Feed every server chat line into this. */
    fun handleServerMessage(rawMessage: String) {
        identify()
        tracker.onChat(rawMessage, System.currentTimeMillis())
    }

    private fun onPartyInfo(event: HypixelModApi.PartyInfoEvent) {
        identify()
        val roles = LinkedHashMap<UUID, PartyRole>()
        val names = HashMap<UUID, String>()
        val connection = Minecraft.getInstance().connection
        for (uuid in event.members) {
            roles[uuid] = if (uuid == event.leader) PartyRole.LEADER else if (uuid in event.moderators) PartyRole.MODERATOR else PartyRole.MEMBER
            connection?.getPlayerInfo(uuid)?.profile?.name?.let { names[uuid] = it }
        }
        tracker.onPartyInfo(event.inParty, roles, names, System.currentTimeMillis())
    }

    private fun tick(mc: Minecraft) {
        val player = mc.player ?: return
        tracker.onHypixel = HypixelNetwork.current != HypixelNetwork.NONE
        val request = tracker.poll(System.currentTimeMillis()) ?: return
        if (request == PartyTracker.Request.PARTY_INFO) {
            HypixelModApi.requestPartyInfo()
        } else {
            player.connection.sendCommand("party list")
            DebugLogger.logFeatureEvent("PartyManager: sent /party list to name the party")
        }
    }

    private fun identify() {
        val user = Minecraft.getInstance().user
        tracker.selfName = user.name
        tracker.selfUuid = user.profileId
    }

    private fun fire(event: PartyEvent) {
        listeners.forEach { listener ->
            try {
                listener(event)
            } catch (t: Throwable) {
                Soul.getLogger().error("Error in PartyManager listener", t)
            }
        }
    }

    private fun localPlayerName(): String? = Minecraft.getInstance().user.name
}
