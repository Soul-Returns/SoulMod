package com.soulreturns.features.mining.nucleus

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.data.skyblock.SubLocationChanged
import com.soulreturns.ui.hud.alert.Alerts
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents

/**
 * Bal (the Khazad-dûm boss guarding the Topaz) from chat, per lobby: down after the retreat line
 * (Topaz available), spawning after the ground-shake line, up on any other boss line or on
 * `You haven't earned this Crystal!` in Khazad-dûm. Bal's name tag is not a signal: in the capture the
 * same `Bal ???❤` stand (same entity id) stayed through several retreats and respawns.
 *
 * Respawn timing is open: retreat to the next ground-shake line took 60-362 s over only 8 samples.
 */
object BalState {
    enum class Phase { UNKNOWN, UP, SPAWNING, DOWN }

    /** Boss lines keep arriving for ~1 s after the retreat, `haven't earned` up to 2 s (capture). */
    private const val GRACE_MS = 3_000L

    /** How long the enter warning stays up. */
    private const val WARNING_MS = 4_000L

    private const val ALERT_ID = "nucleus_bal_up"

    /** The shortest retreat -> next spawn seen; after this a "down" Bal may be back. */
    const val MIN_RESPAWN_MS = 60_000L

    /** Sidebar sublocation prefix: the reader stops at the `-` of `Khazad-dûm`. */
    private const val KHAZAD = "Khazad"

    @Volatile var phase: Phase = Phase.UNKNOWN
        private set

    /** Wall clock when [phase] began. */
    @Volatile var since: Long = 0L
        private set

    /** The down time is only known from a Topaz found, not from the retreat line. */
    @Volatile var approximate: Boolean = false
        private set

    fun register() {
        Events.subscribe<SubLocationChanged> { onSubLocation(it.current) }
        Events.subscribe<AreaChanged> { if (it.current != NucleusRuns.AREA) reset() }
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        Alerts.declare { enabled() && cfg.mining.nucleus.balWarning }
    }

    fun enabled(): Boolean = cfg.mining.nucleus.bal

    fun inKhazad(): Boolean = SkyblockLocation.subLocation?.startsWith(KHAZAD) == true

    /** Fed by [NucleusRuns] with every nucleus event in the Crystal Hollows. */
    fun on(event: NucleusEvent) {
        if (!enabled()) return
        val now = System.currentTimeMillis()
        val next = next(phase, since, event, now, inKhazad()) ?: return
        set(next.first, now)
        approximate = next.second
    }

    /**
     * Pure transition: the new phase and whether its start is only inferred, or null for no change.
     * Bal often dies to other players (no retreat line for us), so a Topaz found while not down means
     * he is down.
     */
    fun next(phase: Phase, since: Long, event: NucleusEvent, now: Long, inKhazad: Boolean): Pair<Phase, Boolean>? {
        val justDown = phase == Phase.DOWN && now - since < GRACE_MS
        return when {
            event == NucleusEvent.BalDown -> Phase.DOWN to false
            event == NucleusEvent.BalSpawning -> Phase.SPAWNING to false
            event == NucleusEvent.BalActive -> if (!justDown && phase != Phase.UP) Phase.UP to false else null
            event == NucleusEvent.CrystalLocked ->
                if (inKhazad && !justDown && phase != Phase.UP) Phase.UP to false else null
            event is NucleusEvent.CrystalFound && event.crystal == "Topaz" ->
                if (phase != Phase.DOWN) Phase.DOWN to true else null
            else -> null
        }
    }

    private fun set(p: Phase, now: Long) {
        phase = p
        since = now
        approximate = false
    }

    private fun onSubLocation(current: String?) {
        if (!enabled() || current?.startsWith(KHAZAD) != true) return
        if (!cfg.mining.nucleus.balWarning || (phase != Phase.UP && phase != Phase.SPAWNING)) return
        Alerts.show(ALERT_ID, "Bal is up!", "Topaz locked until he retreats", durationMs = WARNING_MS, priority = 20)
    }

    private fun reset() {
        phase = Phase.UNKNOWN
        since = 0L
        approximate = false
        Alerts.dismiss(ALERT_ID)
    }
}
