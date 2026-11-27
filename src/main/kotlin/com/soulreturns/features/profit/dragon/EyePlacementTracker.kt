package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * Counts the Summoning Eyes **this player** placed between dragon spawns, and attributes them to the
 * dragon that actually spawns.
 *
 * That count is the whole basis of the eye-cost subtraction — the difference between "I made 14M off
 * that Superior" and "I made 14M and paid 9M for the privilege".
 *
 * ### The four chat lines, and the traps in each
 * - **Own placement** — `☬ You placed a Summoning Eye!`, sometimes carrying `Brace yourselves!` on
 *   the eighth. Matched as a prefix so both variants land.
 * - **Another player's placement** — `☬ <name> placed a Summoning Eye! (N/8)`. **Ignored.** The cost
 *   we subtract is what this player paid, not what the platform cost.
 * - **Recovery** — `You recovered a Summoning Eye!`, fired by right-clicking an eye back out before
 *   the spawn. **It carries no leading `☬`** — verified in game, unlike the placement lines — so it
 *   anchors on the stripped body, and it matches the **full line** rather than
 *   `containsMatchIn`: someone quoting it in party chat must not decrement the count.
 * - **Spawn** — `☬ The <Type> Dragon has spawned!`, in **title case**, not the uppercase the death
 *   banner uses.
 *
 * ### Pending eyes expire
 * Eyes accumulate until a spawn flushes them. A player who places eyes, leaves, and joins someone
 * else's session would otherwise carry stale state onto an unrelated kill, so the queue is dropped
 * after [PENDING_EYE_TTL_MS] without a spawn. Hypixel's own 8/8 cap bounds it in the meantime.
 */
object EyePlacementTracker {
    private val logger = SoulLogger("Soul/DragonProfit")

    private const val PENDING_EYE_TTL_MS = 5 * 60 * 1000L

    /** Prefix, so the plain and `Brace yourselves!` variants both match. */
    private val OWN_PLACEMENT_REGEX = Regex("""^☬ You placed a Summoning Eye!""")

    /** Full line, no `☬` — see the class doc. */
    private val OWN_RECOVERY_REGEX = Regex("""^You recovered a Summoning Eye!$""")

    private val SPAWN_REGEX =
        Regex("""^☬ The (Protector|Old|Wise|Unstable|Young|Strong|Superior) Dragon has spawned!$""")

    @Volatile private var pendingOwnEyes: Long = 0L

    @Volatile private var pendingSince: Long = 0L

    /**
     * Own eyes attributed to the most recent spawn.
     *
     * Survives the gap between the spawn and the death banner, so [DragonLootScanner] can snapshot it
     * as this kill's eye contribution. Reset on the next spawn message — which is strictly after the
     * previous dragon died, since Hypixel refuses eyes on a platform with a live dragon.
     */
    @Volatile
    var lastSpawnEyes: Long = 0L
        private set

    /** Own eyes placed since the last spawn (read-only, for the Scoreboard's Dragon eyes line); 0 once the queue went stale. */
    fun ownPending(): Long = if (pendingOwnEyes > 0L && System.currentTimeMillis() - pendingSince > PENDING_EYE_TTL_MS) 0L else pendingOwnEyes

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        // With the feature off this records nothing at all — an eye counted into the stats file by a
        // switched-off tracker would surface later as profit the player never asked us to track.
        if (!cfg.combat.dragons.enabled) return
        // Eyes can only be placed at the nest, but gate anyway: an echoed line or a custom server
        // must not be able to poison the count from somewhere else.
        if (!SkyblockLocation.isInSubLocation(DragonsNest.SUBLOCATION)) return
        val clean = MessageDetector.stripColorCodes(event.raw).trim()

        // 1. Spawn — flush the queue onto that dragon and classify the kill that follows.
        SPAWN_REGEX.matchEntire(clean)?.let { match ->
            val type = DragonType.byName(match.groupValues[1].uppercase())
            if (type == null) {
                logger.warn("Unrecognised dragon in spawn line: ${match.groupValues[1]}")
                return
            }
            val toGrant = pendingOwnEyes
            pendingOwnEyes = 0L
            pendingSince = 0L
            lastSpawnEyes = toGrant
            DragonProfitTracker.currentKillSource = if (toGrant > 0L) KillSource.SUMMONED else KillSource.LOOTSHARE
            if (toGrant > 0L) {
                DragonProfitTracker.grantEye(type, toGrant)
                logger.info("Spawn: ${type.displayName} (SUMMONED) — attributing $toGrant own eye(s)")
            } else {
                logger.info("Spawn: ${type.displayName} (LOOTSHARE) — no own eyes for this summoning")
            }
            return
        }

        // 2. Own placement. Drop a stale queue first, so a player returning after a long absence does
        //    not carry the last session's eyes into this one.
        if (OWN_PLACEMENT_REGEX.containsMatchIn(clean)) {
            val now = System.currentTimeMillis()
            if (pendingOwnEyes > 0L && now - pendingSince > PENDING_EYE_TTL_MS) {
                logger.info("Dropping $pendingOwnEyes stale pending eye(s) past TTL")
                pendingOwnEyes = 0L
            }
            pendingOwnEyes++
            pendingSince = now
            return
        }

        // 3. Recovery. Floored at 0: if the line ever fires without a matching placement — a missed
        //    chat line, a regex regression — under-counting is the harmless direction, and wrapping
        //    negative is not.
        if (OWN_RECOVERY_REGEX.matches(clean)) {
            if (pendingOwnEyes > 0L) {
                pendingOwnEyes--
                if (pendingOwnEyes == 0L) pendingSince = 0L
            }
        }
    }

    /** Eyes queued waiting for a spawn. Diagnostics only. */
    fun pendingCount(): Long = pendingOwnEyes
}

/** The one place the nest's sublocation name is spelled, since four files gate on it. */
object DragonsNest {
    const val SUBLOCATION: String = "Dragon's Nest"
}
