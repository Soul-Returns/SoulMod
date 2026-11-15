package com.soulreturns.features.mining.mineshaft

import com.soulreturns.config.cfg
import com.soulreturns.features.party.PartyManager
import com.soulreturns.sound.SoulSounds
import com.soulreturns.util.DebugLogger
import com.soulreturns.util.SoulLogger
import net.minecraft.client.Minecraft

/**
 * The one place a mineshaft alert reaches party chat — the two rules that every `/pc` send in this
 * feature has to obey, implemented once so no alert can be written without them.
 *
 * 1. **Never claim someone else's find.** A visit classified [VisitSource.WARPED] means we are a
 *    guest in a mineshaft the party leader discovered. Announcing it as ours is wrong socially and
 *    breaks the `!ptme` protocol, which exists to make the *finder* party leader.
 * 2. **`/pc` requires a party.** Hypixel **silently drops** `/pc` when you are not in one — no error,
 *    no feedback — so [PartyManager.isInParty] is a hard gate rather than a nicety. A send that
 *    vanishes is the worst kind of bug: the feature looks broken with nothing to look at.
 *
 * ### The message stays raw
 * These are **protocol** messages, not conversation. `!ptme …` and `x: N, y: N, z: N` are matched
 * verbatim by other mods (Skytils / SkyHanni / Patcher build a waypoint straight off that exact
 * format), so nothing here routes through `withOutgoingPrefix` and nothing may "improve" the wording.
 */
internal object MineshaftAlertGate {
    private val logger = SoulLogger("Soul/Mineshaft")

    /**
     * Send `/pc [protocolMessage]` if both gates pass. With [chime], the alert sound
     * (`mining.mineshaft.alertSound`) plays for you once the find is known to be yours, even when
     * the party send is then skipped because you are not in a party.
     *
     * @return true if it went out. Callers should mark themselves fired **either way**: a blocked
     *   send is a decision, not a retry — re-testing it every tick would only log the same refusal 20
     *   times a second.
     */
    fun send(
        protocolMessage: String,
        what: String,
        chime: Boolean = true,
    ): Boolean {
        if (MineshaftState.isWarpedVisit()) {
            DebugLogger.logFeatureEvent("Mineshaft: $what — warped in, so this find is not ours; not sending")
            return false
        }
        if (chime) cfg.mining.mineshaft.alertSound?.let { SoulSounds.play(it) }
        if (!PartyManager.isInParty()) {
            DebugLogger.logFeatureEvent("Mineshaft: $what — not in a party, and Hypixel drops /pc silently; not sending")
            return false
        }
        val player = Minecraft.getInstance().player ?: return false
        player.connection.sendCommand("pc $protocolMessage")
        logger.info("Sent /pc $protocolMessage ($what)")
        return true
    }
}
