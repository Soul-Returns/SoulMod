package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockState
import com.soulreturns.features.dev.DevState
import com.soulreturns.features.party.PartyManager
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.withOutgoingPrefix
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft

/**
 * The Vanguard guild call. In a shaft you found yourself that holds a Vanguard corpse, guild chat gets
 * [VanguardGuildChat.ANNOUNCE] once, and from then on every guild member who answers `me` is invited
 * to the party (`/p invite <name>`, one at a time, [VanguardGuildWindow.PACE_MS] apart) until the
 * entrance collapses (`The mineshaft entrance has caved in...`, ~62 s after `Sending to Mineshaft...` in
 * every recording, and nobody can be warped in after it), you leave, change server or disconnect, or the
 * setting goes off. `!warp` is another mod's job.
 *
 * Only a shaft **you found** is announced ([VisitSource.DISCOVERED]): a guest's or an unclassified
 * visit is not yours to advertise (same rule as [MineshaftAlertGate]), which also means re-entering a
 * shaft never announces again (no `Sending to Mineshaft...`, so the visit is `unknown`), and the
 * announcement is once per [MineshaftState.visitId] besides. With `vanguardGuildSkipInParty` (default
 * on) a player who is in a party when entering the shaft gets neither the call nor invites. The
 * announcement is a conversational send, so it follows the outgoing-prefix setting like `/pc` text.
 *
 * Detection reuses the Vanguard alert's: a Vanguard row in the tab's Frozen Corpses list
 * ([MineshaftCorpses]). Driven by [MineshaftTick] (client thread, only inside the shaft); chat arrives
 * on the pump's thread and only touches the thread-safe [VanguardGuildWindow].
 */
object VanguardGuildInvite {
    private const val CORPSE_TYPE = "Vanguard"
    private val logger = SoulLogger("Soul/Mineshaft")
    private val window = VanguardGuildWindow()

    /** The visit [announceDecision] and [partyAtEntry] belong to. */
    @Volatile private var visitId: String? = null

    @Volatile private var partyAtEntry = false

    /** Null while still waiting for the Vanguard row; set once the call went out or was ruled out. */
    @Volatile private var announceDecision: String? = null

    /** The visit whose entrance collapsed, so a re-entry never inherits it. */
    @Volatile private var collapsedVisit: String? = null

    fun register() {
        Events.subscribe(this)
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> window.close("joined another server") }
        ClientPlayConnectionEvents.DISCONNECT.register { _, _ -> window.close("disconnected") }
        DevState.register("vanguard_guild") {
            linkedMapOf(
                "enabled" to cfg.mining.mineshaft.vanguardGuild,
                "skip_in_party" to cfg.mining.mineshaft.vanguardGuildSkipInParty,
                "window_open" to window.open,
                "close_reason" to window.closeReason,
                "visit" to visitId,
                "decision" to (announceDecision ?: "waiting"),
                "in_party_at_entry" to partyAtEntry,
                "collapsed" to isCollapsed(),
                "invited" to window.invited,
                "invites_sent" to window.invitesSent,
                "queued" to window.queued,
                "last_ignored" to window.lastIgnored,
            )
        }
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        if (VanguardGuildChat.isCollapse(stripped)) {
            if (MineshaftState.enteredAt == null) return
            collapsedVisit = MineshaftState.visitId
            if (window.open) DebugLogger.logFeatureEvent("Vanguard guild: entrance collapsed — window closed")
            window.close("the shaft collapsed")
            return
        }
        if (!cfg.mining.mineshaft.vanguardGuild) {
            window.close("setting off")
            return
        }
        if (window.onGuildLine(stripped, Minecraft.getInstance().user.name)) {
            DebugLogger.logFeatureEvent("Vanguard guild: queued ${window.queued.lastOrNull() ?: "?"}")
        }
    }

    @HandleEvent
    fun onAreaChanged(event: AreaChanged) {
        if (event.previous == MineshaftState.AREA && event.current != MineshaftState.AREA) window.close("left the shaft")
    }

    /** Driven by [MineshaftTick], so only inside the mineshaft area. */
    fun tick(nowMs: Long) {
        if (!cfg.mining.mineshaft.vanguardGuild) {
            window.close("setting off")
            return
        }
        syncVisit()
        if (announceDecision == null) tryAnnounce()
        val name = window.nextInvite(nowMs) ?: return
        val player = Minecraft.getInstance().player ?: return
        player.connection.sendCommand("p invite $name")
        logger.info("Vanguard guild: sent /p invite $name")
    }

    private fun isCollapsed(): Boolean = MineshaftState.enteredAt != null && collapsedVisit == MineshaftState.visitId

    /** A new visit starts with a clean slate and its own party snapshot (taken on its first tick, i.e. on entry). */
    private fun syncVisit() {
        val id = MineshaftState.visitId
        if (id == visitId) return
        visitId = id
        announceDecision = null
        partyAtEntry = PartyManager.isInParty()
        window.reset()
    }

    private fun tryAnnounce() {
        if (!SkyblockState.onSkyblock || MineshaftState.enteredAt == null) return
        val m = cfg.mining.mineshaft
        val verdict = VanguardGuildChat.verdict(
            MineshaftState.source, isCollapsed(), m.vanguardGuildSkipInParty, partyAtEntry, MineshaftCorpses.totalOf(CORPSE_TYPE),
        )
        if (!verdict.announce) {
            if (verdict.final) decide(verdict.why)
            return
        }
        val player = Minecraft.getInstance().player ?: return

        player.connection.sendCommand("gc ${withOutgoingPrefix(VanguardGuildChat.ANNOUNCE)}")
        window.begin()
        decide(verdict.why)
        logger.info("Vanguard guild: announced in guild chat, invite window open")
    }

    private fun decide(what: String) {
        announceDecision = what
        DebugLogger.logFeatureEvent("Vanguard guild: $what")
    }
}
