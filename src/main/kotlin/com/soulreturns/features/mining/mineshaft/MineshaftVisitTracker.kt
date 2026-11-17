package com.soulreturns.features.mining.mineshaft

import com.soulreturns.api.HypixelNetwork
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.world.entity.Entity
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * The Glacite Mineshaft visit lifecycle: classify how we got in, watch what is in there, and log the
 * visit to the backend on the way out (`POST /mineshaft/visit`).
 *
 * ### Lifecycle
 *  - **Entry** — `AreaChanged` into `Mineshaft` calls [MineshaftState.beginVisit], which stamps
 *    `enteredAt`, mints the `visitId` and resolves the [VisitSource] from the pre-entry chat signals.
 *  - **During** — [tick] (driven by [MineshaftTick]) opportunistically captures the identifier from
 *    [MineshaftScoreboard] once the sidebar reads `Glacite Mineshafts`, and flips `littlefootFound`
 *    the first time [MobSpotter] returns Littlefoot **in line of sight**.
 *  - **Exit** — `AreaChanged` away snapshots [MineshaftCorpses] and hands the payload to
 *    [MineshaftOutbox]. Quitting the game counts as leaving, and takes the same path.
 *
 * ### Source classification, and why it is buffered
 * Two chat lines tell the two ways into a mineshaft apart:
 *  - `Sending to Mineshaft...` — **only** on self-discovery (you broke the glacite yourself).
 *  - `Party Leader, <display>, summoned you to their server.` — **only** on a party `/p warp`.
 *
 * Both arrive **before** `AreaChanged`, in the *previous* world, because Hypixel sends them ahead of
 * the transfer. Reading them at start-of-visit would find nothing and classify every visit as
 * `unknown`; they are therefore buffered with a 10 s TTL in [MineshaftState] and resolved on entry.
 * `<display>` carries a rank prefix (`[MVP+] Luckyking1105`) and is reduced to the bare username.
 *
 * ### The snapshot must be synchronous
 * [MineshaftCorpses] is cleared when the next visit starts and stops being updated the moment we
 * leave the area, so [finishVisit] reads it inline, on the event thread, before anything else can
 * run. An async read would post an empty `corpses` map — which is indistinguishable from a mineshaft
 * that genuinely had none.
 *
 * ### Classification runs even with logging off
 * `mining.mineshaft.logVisits` gates only the POST. The three party alerts gate on
 * [MineshaftState.isWarpedVisit], so a player who turned telemetry off must still not `!ptme` a
 * find that is not theirs.
 */
object MineshaftVisitTracker {
    private val logger = SoulLogger("Soul/Mineshaft")

    private val SENDING_TO_MINESHAFT = Regex("Sending to Mineshaft\\.\\.\\.", RegexOption.IGNORE_CASE)
    private val SUMMONED_BY_LEADER = Regex("""^Party Leader, (.+?), summoned you to their server\.$""")

    /** Stamped at entry: a visit can end after the disconnect that clears [HypixelNetwork]. */
    private var visitOnAlpha = false

    fun register() {
        Events.subscribe(this)
        MineshaftOutbox.load()
        // Drain on join rather than during init: a bearer needs a live session, and this is the first
        // moment one is guaranteed. A client that never joins a server keeps its queue, which is fine.
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> MineshaftOutbox.flush() }
        // Quitting while standing in a mineshaft is a real way to end a visit, and it does not
        // reliably produce an AreaChanged: DISCONNECT fires on the netty thread during shutdown and
        // the reader defers its reset onto a client task queue that may never drain. The visit is
        // recorded to the outbox synchronously here, so the POST it can no longer make (SoulScope is
        // already cancelled by this point) simply happens at the next launch instead.
        ClientLifecycleEvents.CLIENT_STOPPING.register(ClientLifecycleEvents.ClientStopping { finishVisit() })
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        if (SENDING_TO_MINESHAFT.containsMatchIn(stripped)) {
            MineshaftState.onSendingToMineshaft(System.currentTimeMillis())
            return
        }
        SUMMONED_BY_LEADER.matchEntire(stripped)?.let { match ->
            MineshaftState.onSummonedByLeader(System.currentTimeMillis(), bareUsername(match.groupValues[1].trim()))
        }
    }

    @HandleEvent
    fun onAreaChanged(event: AreaChanged) {
        val area = MineshaftState.AREA
        if (event.previous == area && event.current != area) finishVisit()
        if (event.previous != area && event.current == area) startVisit()
    }

    private fun startVisit() {
        visitOnAlpha = HypixelNetwork.isAlpha
        MineshaftState.beginVisit(System.currentTimeMillis())
        // Clear here, not on leaving: finishVisit() has to see the counts from the moment we left.
        MineshaftCorpses.clear()
    }

    /**
     * Per-tick opportunistic capture. Runs only inside the mineshaft area — see [MineshaftTick].
     *
     * @param littlefoot this tick's **line-of-sight-confirmed** Littlefoot, or null. Handed in rather
     *   than looked up here so the entity walk happens once per tick for the whole feature; null also
     *   means "nobody scanned this tick", which is indistinguishable from "not visible" and equally
     *   correct to treat as not-found.
     */
    fun tick(littlefoot: Entity?) {
        if (MineshaftState.enteredAt == null) return
        if (HypixelNetwork.isAlpha) visitOnAlpha = true
        MineshaftState.captureProfile()

        if (MineshaftState.mineshaftType == null) {
            MineshaftScoreboard.readIdentifier()?.let { (type, instance) ->
                MineshaftState.mineshaftType = type
                MineshaftState.serverInstance = instance
                DebugLogger.logFeatureEvent("Mineshaft: identifier $instance")
            }
        }

        if (!MineshaftState.littlefootFound && littlefoot != null) {
            MineshaftState.littlefootFound = true
            DebugLogger.logFeatureEvent("Mineshaft: Littlefoot LOS-confirmed this visit")
        }
    }

    private fun finishVisit() {
        val entered = MineshaftState.enteredAt ?: return
        val left = Instant.now()

        // Everything the payload needs, read before endVisit() wipes it. The corpse snapshot in
        // particular MUST happen here, synchronously — see the class KDoc.
        val visitId = MineshaftState.visitId
        val type = MineshaftState.mineshaftType
        val instance = MineshaftState.serverInstance
        val littlefootFound = MineshaftState.littlefootFound
        val source = MineshaftState.source
        val warpedBy = MineshaftState.warpedBy
        val profileId = MineshaftState.profileId
        val profileName = MineshaftState.profileName
        val corpses = MineshaftCorpses.byType.toMap()

        MineshaftState.endVisit()

        if (!cfg.mining.mineshaft.logVisits) return
        // The outbox replays later, possibly from production, so an Alpha visit must never enter it.
        if (visitOnAlpha || HypixelNetwork.isAlpha) {
            DebugLogger.logFeatureEvent("Mineshaft: not logging the visit — Hypixel Alpha network")
            return
        }
        if (type == null || instance == null) {
            // No identifier means the sidebar never rendered one — a mineshaft we cannot name is not
            // a data point, and the endpoint requires both fields anyway.
            DebugLogger.logFeatureEvent("Mineshaft: not logging the visit — the identifier never resolved")
            return
        }

        // Whole seconds: the contract's example carries none, and the endpoint only ever compares and
        // buckets these. A visit that does not span a second is an area flicker, not a visit.
        val enteredSec = entered.truncatedTo(ChronoUnit.SECONDS)
        val leftSec = left.truncatedTo(ChronoUnit.SECONDS)
        if (!leftSec.isAfter(enteredSec)) {
            DebugLogger.logFeatureEvent("Mineshaft: not logging the visit — it did not last a second")
            return
        }

        val payload = MineshaftVisitPayload(
            visitId = visitId,
            mineshaftType = type,
            serverInstance = instance,
            enteredAt = enteredSec.toString(),
            leftAt = leftSec.toString(),
            corpses = corpses.mapValues { (_, counts) -> CorpseCount(found = counts.total, looted = counts.looted) },
            littlefootFound = littlefootFound,
            source = source,
            warped = source == VisitSource.WARPED,
            warpedBy = warpedBy.takeIf { source == VisitSource.WARPED },
            profileId = profileId,
            // The backend caps the display name at 32 characters and rejects longer; the id is what counts.
            profileName = profileName?.takeIf { profileId != null && it.length <= 32 },
        )

        // Spell the corpses out rather than counting the map: "corpses=2" reads as two corpses when it
        // meant two types, and this line is the only human-readable record of what was actually sent.
        val corpseSummary = payload.corpses.entries
            .sortedByDescending { it.value.found }
            .joinToString(", ") { (type, c) -> "$type ${c.looted}/${c.found}" }
            .ifEmpty { "none" }
        logger.info(
            "Mineshaft visit $instance — source=$source warpedBy=$warpedBy profile=${profileName ?: "-"}/${profileId ?: "-"} " +
                "littlefoot=$littlefootFound " +
                "corpses=[$corpseSummary] (${enteredSec.until(leftSec, ChronoUnit.SECONDS)}s)",
        )
        MineshaftOutbox.submit(payload)
    }

    /** Strip a Hypixel rank prefix from a display name → the bare Mojang username. */
    internal fun bareUsername(display: String): String =
        if (display.contains("] ")) {
            display.substringAfterLast("] ").substringBefore(" ").trim()
        } else {
            display.substringBefore(" ").trim()
        }
}
