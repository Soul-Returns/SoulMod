package com.soulreturns.features.party

import com.soulreturns.Soul
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.MessageHandler
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft
import java.util.concurrent.CopyOnWriteArraySet

/**
 * Tracks Hypixel party state for the local player by parsing server chat messages.
 *
 * Call [register] once during client init to start listening to server messages.
 *
 * NOTE vs legacy: the `/party list` *display suppression* (a `ChatComponent` mixin that hid the
 * auto-refresh response from chat) is NOT ported in this baseline — that requires a mixin and
 * the baseline is mixin-free. The auto-refresh still runs and parsing still works; the response
 * just isn't hidden from the user's chat. Re-add the suppression mixin when desired.
 */
object PartyManager {
    enum class PartyRole { LEADER, MODERATOR, MEMBER }

    data class PartyMember(
        val name: String,
        val displayName: String,
        val role: PartyRole,
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
    ) {
        val size: Int get() = members.size
    }

    enum class PartyDisbandReason {
        LEADER_DISBANDED,
        EMPTY_OR_EXPIRED,
        LEFT_PARTY,
        KICKED,
        UNKNOWN,
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
    }

    private var currentState: PartyState? = null

    private val pendingInvites = mutableListOf<PartyInvite>()
    private const val INVITE_LIFETIME_MS = 60_000L

    private val listeners = CopyOnWriteArraySet<(PartyEvent) -> Unit>()

    private var parsingList = false
    private var listExpectedSize: Int? = null
    private var listParsedLeader: PartyMember? = null
    private val listParsedMembers = mutableListOf<PartyMember>()

    private var isRegistered = false

    /** Ticks to wait after JOIN before sending /party list. Lets initial chat settle. */
    private const val INITIAL_REFRESH_DELAY_TICKS = 40 // ~2 s at 20 ticks/sec

    @Volatile private var hasAutoRefreshedThisSession: Boolean = false

    @Volatile private var refreshDelayTicks: Int = 0

    fun register() {
        if (isRegistered) return

        // Some /p list lines look like player messages to MessageDetector, so listen to both streams.
        MessageHandler.onServerMessage { message -> handleServerMessage(message) }
        MessageHandler.onPlayerMessage { message -> handleServerMessage(message) }

        ClientPlayConnectionEvents.JOIN.register(
            ClientPlayConnectionEvents.Join { _, _, _ ->
                if (!hasAutoRefreshedThisSession) {
                    hasAutoRefreshedThisSession = true
                    refreshDelayTicks = INITIAL_REFRESH_DELAY_TICKS
                }
            },
        )
        ClientPlayConnectionEvents.DISCONNECT.register(
            ClientPlayConnectionEvents.Disconnect { _, _ ->
                hasAutoRefreshedThisSession = false
                refreshDelayTicks = 0
            },
        )
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                if (refreshDelayTicks > 0) {
                    refreshDelayTicks--
                    if (refreshDelayTicks == 0) requestRefresh()
                }
            },
        )

        isRegistered = true
        Soul.getLogger().info("PartyManager registered for chat messages")
    }

    /** Send `/party list` to bootstrap party state after a join. Dropped if no connection yet. */
    fun requestRefresh() {
        val player = Minecraft.getInstance().player ?: return
        player.connection.sendCommand("party list")
        DebugLogger.logFeatureEvent("PartyManager: sent /party list to bootstrap party state")
    }

    private fun splitListEntries(section: String): List<String> =
        section.split(LIST_ENTRY_SPLIT).flatMap { it.split(", ") }

    fun addListener(listener: (PartyEvent) -> Unit) {
        listeners += listener
    }

    fun removeListener(listener: (PartyEvent) -> Unit) {
        listeners -= listener
    }

    fun isInParty(): Boolean = currentState != null

    fun isLeader(): Boolean {
        val me = localPlayerName() ?: return false
        return currentState?.leader?.name.equals(me, ignoreCase = true)
    }

    fun getPartyState(): PartyState? = currentState

    fun getLeader(): PartyMember? = currentState?.leader

    fun getMembers(includeLeader: Boolean = true): List<PartyMember> {
        val state = currentState ?: return emptyList()
        return state.members.values.filter { includeLeader || it.role != PartyRole.LEADER }
    }

    fun getMemberNames(includeLeader: Boolean = true): List<String> = getMembers(includeLeader).map { it.name }

    fun getPartySize(): Int = currentState?.size ?: 0

    fun getPendingInvites(): List<PartyInvite> {
        pruneExpiredInvites()
        return pendingInvites.toList()
    }

    /** Feed every server chat line into this. */
    fun handleServerMessage(rawMessage: String) {
        val clean = MessageDetector.stripColorCodes(rawMessage).trim()
        if (clean.isEmpty()) return

        pruneExpiredInvites()

        if (clean.startsWith("Party Members (") ||
            clean.startsWith("Party Leader:") ||
            clean.startsWith("Party Members:") ||
            parsingList
        ) {
            handlePartyListLine(clean)
            if (!clean.contains("party", ignoreCase = true)) return
        }

        if (!isPotentialPartyMessage(clean)) return

        when {
            clean.endsWith("has invited you to join their party!") ->
                handleIncomingInvite(clean)

            clean.contains(" has invited you to join ") &&
                clean.endsWith("'s party!") ->
                handleIncomingInviteToNamedParty(clean)

            clean.contains(" invited ") &&
                clean.endsWith(" to the party! They have 60 seconds to accept.") ->
                handleOutgoingInvite(clean)

            clean.startsWith("You have joined ") &&
                clean.endsWith("'s party!") ->
                handleYouJoinedParty(clean)

            clean.startsWith("You'll be partying with:") ->
                handleYoullBePartyingWith(clean)

            clean == "You left the party." ->
                handleYouLeftParty()

            clean.endsWith(" joined the party.") ->
                handleMemberJoined(clean)

            clean.endsWith(" has left the party.") ->
                handleMemberLeft(clean)

            clean.endsWith(" has been removed from the party.") ->
                handleMemberRemoved(clean)

            clean.endsWith(" was removed from your party because they disconnected.") ->
                handleMemberDisconnected(clean)

            clean.startsWith("You have been kicked from the party by ") ->
                handleYouKicked(clean)

            clean.contains(" has promoted ") && clean.endsWith(" to Party Moderator") ->
                handlePromotedToModerator(clean)

            clean.contains(" has promoted ") && clean.endsWith(" to Party Leader") ->
                handlePromotedToLeader(clean)

            clean.endsWith(" is now a Party Moderator") ->
                handleNowModerator(clean)

            clean.startsWith("The party was transferred to ") ->
                handlePartyTransferred(clean)

            clean.endsWith(" has disbanded the party!") ->
                handlePartyDisbandedByLeader(clean)

            clean == "The party was disbanded because all invites expired and the party was empty." ->
                handlePartyDisbandedEmpty()

            clean.startsWith("You are not currently in a party") ||
                clean.startsWith("You are not in a party") ->
                handleNotInParty()
        }
    }

    private fun handleNotInParty() {
        val prev = currentState ?: return
        currentState = null
        DebugLogger.logFeatureEvent("Server reports we are not in a party — clearing stale state")
        fire(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.UNKNOWN))
    }

    private fun handleIncomingInvite(line: String) {
        val inviterDisplay = line.substringBefore(" has invited you to join their party!").trim()
        val inviterName = extractUsername(inviterDisplay) ?: inviterDisplay
        val me = localPlayerName() ?: return

        val now = System.currentTimeMillis()
        val invite =
            PartyInvite(
                from = inviterName,
                to = me,
                outgoing = false,
                createdAt = now,
                expiresAt = now + INVITE_LIFETIME_MS,
            )
        pendingInvites += invite
        DebugLogger.logFeatureEvent("Party invite received from $inviterName")
        fire(PartyEvent.InviteReceived(invite))
    }

    private fun handleIncomingInviteToNamedParty(line: String) {
        val inviterDisplay = line.substringBefore(" has invited you to join ").trim()
        val inviterName = extractUsername(inviterDisplay) ?: inviterDisplay
        val me = localPlayerName() ?: return

        val now = System.currentTimeMillis()
        val invite =
            PartyInvite(
                from = inviterName,
                to = me,
                outgoing = false,
                createdAt = now,
                expiresAt = now + INVITE_LIFETIME_MS,
            )
        pendingInvites += invite
        DebugLogger.logFeatureEvent("Party invite (named party) received from $inviterName")
        fire(PartyEvent.InviteReceived(invite))
    }

    private fun handleOutgoingInvite(line: String) {
        val beforeInvited = line.substringBefore(" invited ")
        val afterInvited = line.substringAfter(" invited ")

        val targetPart = afterInvited.substringBefore(" to the party!").trim()

        val inviterDisplay = beforeInvited.trim()
        val targetDisplay = targetPart.trim()

        val inviterName = extractUsername(inviterDisplay) ?: inviterDisplay
        val targetName = extractUsername(targetDisplay) ?: targetDisplay
        val me = localPlayerName()

        val now = System.currentTimeMillis()
        val invite =
            PartyInvite(
                from = inviterName,
                to = targetName,
                outgoing = inviterName.equals(me, ignoreCase = true),
                createdAt = now,
                expiresAt = now + INVITE_LIFETIME_MS,
            )
        pendingInvites += invite
        DebugLogger.logFeatureEvent("Party invite sent from $inviterName to $targetName")
        fire(PartyEvent.InviteSent(invite))

        if (inviterName.equals(me, ignoreCase = true)) {
            ensurePartyExistsWithSelfAsLeader(inviterDisplay, inviterName)
        }
    }

    private fun handleYouJoinedParty(line: String) {
        val leaderDisplay =
            line
                .removePrefix("You have joined ")
                .removeSuffix("'s party!")
                .trim()

        val leaderName = extractUsername(leaderDisplay) ?: leaderDisplay
        val me = localPlayerName() ?: return
        val now = System.currentTimeMillis()

        clearInvitesInvolving(me)

        val leaderMember =
            PartyMember(
                name = leaderName,
                displayName = leaderDisplay,
                role = PartyRole.LEADER,
            )
        val myMember =
            PartyMember(
                name = me,
                displayName = me,
                role = if (leaderName.equals(me, ignoreCase = true)) PartyRole.LEADER else PartyRole.MEMBER,
            )

        val members = mutableMapOf<String, PartyMember>()
        members[leaderName.lowercase()] = leaderMember
        members[me.lowercase()] = myMember

        val newState =
            PartyState(
                leader = leaderMember,
                members = members,
                createdAt = now,
                lastUpdatedAt = now,
            )
        val joinedAsLeader = leaderName.equals(me, ignoreCase = true)

        val oldState = currentState
        currentState = newState

        DebugLogger.logFeatureEvent("Joined party led by $leaderName (size=${newState.size})")
        fire(PartyEvent.PartyJoined(newState, joinedAsLeader))

        if (oldState != null && oldState != newState) {
            fire(PartyEvent.PartyLeft(oldState, PartyDisbandReason.LEFT_PARTY))
        }
    }

    private fun handleYoullBePartyingWith(line: String) {
        val listSection = line.removePrefix("You'll be partying with:").trim()
        if (listSection.isEmpty()) return

        val state = currentState ?: return
        val now = System.currentTimeMillis()

        val parts = listSection.split(", ")
        for (part in parts) {
            val display = part.trim()
            if (display.isEmpty()) continue
            val name = extractUsername(display) ?: display
            val key = name.lowercase()
            if (state.members.containsKey(key)) continue

            val role =
                if (state.leader?.name.equals(name, ignoreCase = true)) {
                    PartyRole.LEADER
                } else {
                    PartyRole.MEMBER
                }

            val member =
                PartyMember(
                    name = name,
                    displayName = display,
                    role = role,
                )
            state.members[key] = member
        }

        state.lastUpdatedAt = now
    }

    private fun handleYouLeftParty() {
        val prev = currentState ?: return
        currentState = null
        DebugLogger.logFeatureEvent("Left party (prev size=${prev.size})")
        fire(PartyEvent.PartyLeft(prev, PartyDisbandReason.LEFT_PARTY))
        fire(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.LEFT_PARTY))
    }

    private fun handleMemberJoined(line: String) {
        val display = line.removeSuffix(" joined the party.").trim()
        val name = extractUsername(display) ?: display

        val state = currentState
        val now = System.currentTimeMillis()

        clearInvitesInvolving(name)

        if (state == null) {
            val me = localPlayerName() ?: return

            val leader =
                PartyMember(
                    name = name,
                    displayName = display,
                    role = PartyRole.LEADER,
                )
            val meMember =
                PartyMember(
                    name = me,
                    displayName = me,
                    role = PartyRole.MEMBER,
                )
            val members =
                mutableMapOf(
                    leader.name.lowercase() to leader,
                    me.lowercase() to meMember,
                )
            val newState =
                PartyState(
                    leader = leader,
                    members = members,
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            currentState = newState
            DebugLogger.logFeatureEvent("Inferred new party with leader $name due to join message")
            fire(PartyEvent.PartyJoined(newState, joinedAsLeader = false))
            fire(PartyEvent.MemberJoined(leader, newState))
            return
        }

        val existing = state.members[name.lowercase()]
        if (existing != null) return

        val role =
            if (state.leader?.name.equals(name, ignoreCase = true)) {
                PartyRole.LEADER
            } else {
                PartyRole.MEMBER
            }

        val member =
            PartyMember(
                name = name,
                displayName = display,
                role = role,
            )
        state.members[name.lowercase()] = member
        state.lastUpdatedAt = now
        DebugLogger.logFeatureEvent("Party member joined: $display (size=${state.size})")
        fire(PartyEvent.MemberJoined(member, state))
    }

    private fun handleMemberLeft(line: String) {
        val display = line.removeSuffix(" has left the party.").trim()
        val name = extractUsername(display) ?: display

        val state = currentState ?: return
        val removed = state.members.remove(name.lowercase())
        state.lastUpdatedAt = System.currentTimeMillis()

        if (removed != null) {
            DebugLogger.logFeatureEvent("Party member left: $display (remaining=${state.size})")
            fire(PartyEvent.MemberLeft(name, if (state.members.isEmpty()) null else state))
        }

        if (removed?.role == PartyRole.LEADER || state.members.isEmpty()) {
            currentState = null
            fire(PartyEvent.PartyDisbanded(state, PartyDisbandReason.LEFT_PARTY))
        }
    }

    private fun handleMemberDisconnected(line: String) {
        val display = line.removeSuffix(" was removed from your party because they disconnected.").trim()
        val name = extractUsername(display) ?: display

        val state = currentState ?: return
        val removed = state.members.remove(name.lowercase())
        state.lastUpdatedAt = System.currentTimeMillis()

        if (removed != null) {
            DebugLogger.logFeatureEvent("Party member disconnected: $display (remaining=${state.size})")
            fire(PartyEvent.MemberLeft(name, if (state.members.isEmpty()) null else state))
        }

        if (removed?.role == PartyRole.LEADER || state.members.isEmpty()) {
            currentState = null
            fire(PartyEvent.PartyDisbanded(state, PartyDisbandReason.LEFT_PARTY))
        }
    }

    private fun handleMemberRemoved(line: String) {
        val display = line.removeSuffix(" has been removed from the party.").trim()
        val name = extractUsername(display) ?: display

        val state = currentState ?: return
        val removed = state.members.remove(name.lowercase())
        state.lastUpdatedAt = System.currentTimeMillis()

        if (removed != null) {
            DebugLogger.logFeatureEvent("Party member removed: $display (remaining=${state.size})")
            fire(PartyEvent.MemberKicked(name, by = null, newState = if (state.members.isEmpty()) null else state))
        }

        if (state.members.isEmpty()) {
            currentState = null
            fire(PartyEvent.PartyDisbanded(state, PartyDisbandReason.KICKED))
        }
    }

    private fun handleYouKicked(line: String) {
        val byDisplay = line.removePrefix("You have been kicked from the party by ").trim()
        val byName = extractUsername(byDisplay) ?: byDisplay

        val prev = currentState ?: return
        currentState = null

        DebugLogger.logFeatureEvent("Kicked from party by $byDisplay")
        fire(PartyEvent.MemberKicked(memberName = localPlayerName() ?: "You", by = byName, newState = null))
        fire(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.KICKED))
    }

    private fun handlePartyDisbandedByLeader(line: String) {
        val display = line.removeSuffix(" has disbanded the party!").trim()
        val name = extractUsername(display) ?: display

        val prev = currentState ?: return
        currentState = null
        DebugLogger.logFeatureEvent("Party disbanded by leader $display")
        fire(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.LEADER_DISBANDED))
    }

    private fun handlePartyDisbandedEmpty() {
        val prev = currentState ?: return
        currentState = null
        DebugLogger.logFeatureEvent("Party disbanded because it became empty or all invites expired")
        fire(PartyEvent.PartyDisbanded(prev, PartyDisbandReason.EMPTY_OR_EXPIRED))
    }

    private fun handlePromotedToModerator(line: String) {
        val afterPromoted = line.substringAfter(" has promoted ")
        val targetDisplay = afterPromoted.substringBefore(" to Party Moderator").trim()
        val targetName = extractUsername(targetDisplay) ?: targetDisplay
        applyRoleChangeToMember(targetName, targetDisplay, PartyRole.MODERATOR)
    }

    private fun handlePromotedToLeader(line: String) {
        val afterHas = line.substringAfter(" has promoted ")
        val targetDisplay = afterHas.substringBefore(" to Party Leader").trim()
        val targetName = extractUsername(targetDisplay) ?: targetDisplay
        setLeaderWithDemotion(targetName, targetDisplay)
    }

    private fun handleNowModerator(line: String) {
        val display = line.removeSuffix(" is now a Party Moderator").trim()
        val name = extractUsername(display) ?: display
        applyRoleChangeToMember(name, display, PartyRole.MODERATOR)
    }

    private fun handlePartyTransferred(line: String) {
        val afterTo = line.substringAfter("The party was transferred to ")
        val newLeaderDisplay = afterTo.substringBefore(" by ").trim()
        val newLeaderName = extractUsername(newLeaderDisplay) ?: newLeaderDisplay
        setLeaderWithDemotion(newLeaderName, newLeaderDisplay)
    }

    private fun handlePartyListLine(line: String) {
        if (line.startsWith("Party Members (")) {
            parsingList = true
            listParsedLeader = null
            listParsedMembers.clear()

            val countStr =
                line.substringAfter("Party Members (")
                    .substringBefore(")")
            listExpectedSize = countStr.toIntOrNull()
            return
        }

        if (!parsingList) return

        if (line.startsWith("Party Leader:")) {
            val display =
                line.removePrefix("Party Leader:")
                    .trim()
                    .removeSuffix(" ●")
                    .trim()
            val name = extractUsername(display) ?: display
            val leader =
                PartyMember(
                    name = name,
                    displayName = display,
                    role = PartyRole.LEADER,
                )
            listParsedLeader = leader
            if (listParsedMembers.none { it.name.equals(name, ignoreCase = true) }) {
                listParsedMembers.add(leader)
            }
            return
        }

        if (line.startsWith("Party Members:")) {
            val membersSection = line.removePrefix("Party Members:").trim()
            if (membersSection.isEmpty()) return

            for (part in splitListEntries(membersSection)) {
                val display = part.trim()
                if (display.isEmpty()) continue
                val name = extractUsername(display) ?: display
                if (listParsedMembers.none { it.name.equals(name, ignoreCase = true) }) {
                    listParsedMembers.add(
                        PartyMember(
                            name = name,
                            displayName = display,
                            role = PartyRole.MEMBER,
                        ),
                    )
                }
            }
            return
        }

        if (line.startsWith("Party Moderators:")) {
            val moderatorsSection = line.removePrefix("Party Moderators:").trim()
            if (moderatorsSection.isEmpty()) return

            for (part in splitListEntries(moderatorsSection)) {
                val display = part.trim()
                if (display.isEmpty()) continue
                val name = extractUsername(display) ?: display
                if (listParsedMembers.none { it.name.equals(name, ignoreCase = true) }) {
                    listParsedMembers.add(
                        PartyMember(
                            name = name,
                            displayName = display,
                            role = PartyRole.MODERATOR,
                        ),
                    )
                }
            }
            return
        }

        if (line.all { it == '-' }) {
            finalizePartyList()
        }
    }

    private fun finalizePartyList() {
        val leader = listParsedLeader
        if (leader == null) {
            parsingList = false
            listExpectedSize = null
            listParsedMembers.clear()
            return
        }

        val now = System.currentTimeMillis()
        val memberMap = mutableMapOf<String, PartyMember>()
        listParsedMembers.forEach { m ->
            val finalRole =
                when {
                    m.name.equals(leader.name, ignoreCase = true) -> PartyRole.LEADER
                    else -> m.role
                }
            memberMap[m.name.lowercase()] = m.copy(role = finalRole)
        }

        val newState =
            PartyState(
                leader = leader,
                members = memberMap,
                createdAt = currentState?.createdAt ?: now,
                lastUpdatedAt = now,
            )
        currentState = newState
        DebugLogger.logFeatureEvent("Party state synced from /p list (size=${newState.size}, expected=$listExpectedSize)")
        fire(PartyEvent.PartySyncedFromList(newState))

        parsingList = false
        listExpectedSize = null
        listParsedLeader = null
        listParsedMembers.clear()
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

    private fun ensurePartyExistsWithSelfAsLeader(
        display: String,
        name: String,
    ) {
        val me = localPlayerName() ?: return
        val now = System.currentTimeMillis()
        val leaderMember =
            PartyMember(
                name = me,
                displayName = display,
                role = PartyRole.LEADER,
            )
        val members = mutableMapOf(me.lowercase() to leaderMember)

        val existing = currentState
        if (existing == null || !existing.leader?.name.equals(me, ignoreCase = true)) {
            currentState =
                PartyState(
                    leader = leaderMember,
                    members = members,
                    createdAt = now,
                    lastUpdatedAt = now,
                )
            DebugLogger.logFeatureEvent("Created new party state with self as leader ($name)")
            fire(PartyEvent.PartyJoined(currentState!!, joinedAsLeader = true))
        }
    }

    private fun extractUsername(display: String): String? {
        val stripped = display.trim()
        if (stripped.isEmpty()) return null

        return if (stripped.contains("] ")) {
            stripped.substringAfterLast("] ").substringBefore(" ")
        } else {
            stripped.substringBefore(" ")
        }
    }

    private fun isPotentialPartyMessage(line: String): Boolean =
        line.contains("party", ignoreCase = true) ||
            line.startsWith("Party Members (") ||
            line.startsWith("Party Leader:") ||
            line.startsWith("Party Members:")

    private fun pruneExpiredInvites() {
        val now = System.currentTimeMillis()
        val iter = pendingInvites.iterator()
        while (iter.hasNext()) {
            val invite = iter.next()
            if (invite.expiresAt <= now) {
                iter.remove()
                DebugLogger.logFeatureEvent("Party invite from ${invite.from} to ${invite.to} expired")
                fire(PartyEvent.InviteExpired(invite))
            }
        }
    }

    private fun applyRoleChangeToMember(
        memberName: String,
        displayName: String?,
        newRole: PartyRole,
    ) {
        val oldState = currentState ?: return
        val key = memberName.lowercase()
        val oldMembers = oldState.members
        val existing =
            oldMembers[key]
                ?: PartyMember(memberName, displayName ?: memberName, PartyRole.MEMBER)

        if (existing.role == newRole && displayName == null) return

        val updated =
            existing.copy(
                role = newRole,
                displayName = displayName ?: existing.displayName,
            )

        val newMembers = oldMembers.toMutableMap()
        newMembers[key] = updated

        val newState =
            oldState.copy(
                members = newMembers,
                lastUpdatedAt = System.currentTimeMillis(),
            )
        currentState = newState
        fire(PartyEvent.RoleChanged(updated, existing.role, newRole))
    }

    private fun setLeaderWithDemotion(
        newLeaderName: String,
        newLeaderDisplay: String,
    ) {
        val oldState = currentState ?: return
        val now = System.currentTimeMillis()

        val newMembers = oldState.members.toMutableMap()

        val key = newLeaderName.lowercase()
        val existing =
            newMembers[key]
                ?: PartyMember(newLeaderName, newLeaderDisplay, PartyRole.MEMBER)
        val oldRole = existing.role

        val newLeader = existing.copy(role = PartyRole.LEADER, displayName = newLeaderDisplay)
        newMembers[key] = newLeader

        val prevLeader = oldState.leader
        if (prevLeader != null && !prevLeader.name.equals(newLeaderName, ignoreCase = true)) {
            val prevKey = prevLeader.name.lowercase()
            val prevExisting = newMembers[prevKey] ?: prevLeader
            val prevOldRole = prevExisting.role
            val demoted = prevExisting.copy(role = PartyRole.MODERATOR)
            newMembers[prevKey] = demoted
            fire(PartyEvent.RoleChanged(demoted, prevOldRole, demoted.role))
        }

        val newState =
            oldState.copy(
                leader = newLeader,
                members = newMembers,
                lastUpdatedAt = now,
            )
        currentState = newState
        fire(PartyEvent.RoleChanged(newLeader, oldRole, PartyRole.LEADER))
    }

    private fun clearInvitesInvolving(playerName: String) {
        val iter = pendingInvites.iterator()
        while (iter.hasNext()) {
            val invite = iter.next()
            if (invite.from.equals(playerName, ignoreCase = true) ||
                invite.to.equals(playerName, ignoreCase = true)
            ) {
                iter.remove()
            }
        }
    }

    private fun localPlayerName(): String? = Minecraft.getInstance().user.name

    // Hypixel separates /p list entries with the online/offline bullet itself (no comma);
    // split on bullets first, then on ", " as a fallback.
    private val LIST_ENTRY_SPLIT = Regex("\\s*[●○]\\s*")
}
