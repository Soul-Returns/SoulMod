package com.soulreturns.features.profit.dragon

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * Counts the local player's Summoning Eye placements between dragon spawns, then attributes
 * the accumulated count to the dragon that actually spawned. Drives the `Eyes placed: N`
 * line on the Dragon Profit HUD and the eye-cost subtraction inside [DragonProfitTracker.ancillaryCost].
 *
 * **Chat lines:**
 * - Own placement: `"☬ You placed a Summoning Eye! (N/8)"` — sometimes with the
 *   `"Brace yourselves!"` insert on the final eye (`"☬ You placed a Summoning Eye! Brace yourselves! (8/8)"`).
 * - Other player's placement: `"☬ <name> placed a Summoning Eye! (N/8)"` — we ignore these
 *   so the cost only reflects what *this* player paid.
 * - Own recovery: `"You recovered a Summoning Eye!"` — fires when the player right-clicks
 *   one of their already-placed eyes to take it back. Decrements `pendingOwnEyes` by 1
 *   (floored at 0). Only fires before the spawn message; once eyes turn into
 *   "Remnants of the Eye" they're no longer recoverable.
 * - Spawn: `"☬ The <Type> Dragon has spawned!"` — title-case dragon type, not uppercase.
 *
 * **Attribution + TTL:** pending eyes accumulate from each "You placed" line. On the next
 * spawn message they're flushed to that dragon's bucket via [DragonProfitTracker.grantEye].
 * To prevent stale pending state from drifting onto an unrelated kill (e.g. if the player
 * places eyes, leaves, and joins someone else's session), pending eyes expire after
 * [PENDING_EYE_TTL_MS] without a spawn. The 8/8 cap means at most 8 eyes can be queued.
 */
object EyePlacementTracker {
    private val logger = SoulLogger("Soul/DragonProfit")

    private const val PENDING_EYE_TTL_MS = 5 * 60 * 1000L

    /** `^☬ You placed a Summoning Eye!` — matches both the plain and `Brace yourselves!` variants. */
    private val OWN_PLACEMENT_REGEX = Regex("""^☬ You placed a Summoning Eye!""")

    /**
     * `You recovered a Summoning Eye!` — fires when the player right-clicks one of their
     * placed eyes to take it back. The leading `☬` glyph is **not** present in this line
     * (verified in-game vs. the placement lines which do carry it), so we anchor on the
     * stripped+trimmed body instead. Matches the full line, not `containsMatchIn`, to
     * avoid accidentally firing on quoted/echoed party chat.
     */
    private val OWN_RECOVERY_REGEX = Regex("""^You recovered a Summoning Eye!$""")

    /**
     * `^☬ The <Type> Dragon has spawned!` after color-strip. The exclamation is present in
     * every spawn line and the dragon name is title-cased ("Strong" not "STRONG").
     */
    private val SPAWN_REGEX =
        Regex("""^☬ The (Protector|Old|Wise|Unstable|Young|Strong|Superior) Dragon has spawned!$""")

    @Volatile private var pendingOwnEyes: Long = 0L

    @Volatile private var pendingSince: Long = 0L

    /**
     * Own-eye count attributed to the most recent dragon spawn. Set inside [onChat]'s spawn
     * branch (same value as `toGrant`). Survives across the gap between spawn and the kill's
     * death banner, so [DragonLootScanner.beginScan] can snapshot it as the local player's
     * eye contribution for that specific dragon. Reset whenever a new spawn message lands,
     * which is strictly after the previous dragon has died (Hypixel won't allow eyes on a
     * platform with an active dragon).
     */
    @Volatile var lastSpawnEyes: Long = 0L
        private set

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        val clean = MessageDetector.stripColorCodes(event.raw).trim()

        // 1. Spawn — flush pending eyes to that dragon, classify the kill source, and
        //    reset the queue for the next summoning.
        SPAWN_REGEX.matchEntire(clean)?.let { match ->
            val typeName = match.groupValues[1].uppercase()
            val type = DragonType.byName(typeName)
            if (type == null) {
                logger.warn("Unrecognised dragon in spawn line: $typeName")
                return
            }
            val toGrant = pendingOwnEyes
            pendingOwnEyes = 0L
            pendingSince = 0L
            lastSpawnEyes = toGrant
            // Classify *this* spawn: ≥1 own eye placed → SUMMONED, else LOOTSHARE. The
            // source stays valid through to the death banner so loot is attributed to the
            // right partition by DragonLootScanner.
            val source =
                if (toGrant > 0L) KillSource.SUMMONED else KillSource.LOOTSHARE
            DragonProfitTracker.currentKillSource = source
            if (toGrant > 0L) {
                DragonProfitTracker.grantEye(type, toGrant)
                logger.info("Spawn: ${type.displayName} (SUMMONED) — attributing $toGrant own eye(s)")
            } else {
                logger.info("Spawn: ${type.displayName} (LOOTSHARE) — no own eyes for this summoning")
            }
            return
        }

        // 2. Own eye placement — bump the queue. Other-player placements are dropped.
        //    Drop stale pending state if the user's been idle past the TTL (left the
        //    instance without a spawn, etc.).
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

        // 3. Own eye recovery — right-click on a placed eye returned it to inventory.
        //    Floor at 0 in case the line ever fires without a matching prior placement
        //    (regex regression, missed chat line, etc.) — better to under-count than to
        //    wrap to negative. Clear `pendingSince` when the queue drains so the TTL
        //    timer doesn't fire on stale state.
        if (OWN_RECOVERY_REGEX.matches(clean)) {
            if (pendingOwnEyes > 0L) {
                pendingOwnEyes--
                if (pendingOwnEyes == 0L) pendingSince = 0L
            }
        }
    }

    /** Number of eyes queued waiting for a dragon spawn. Diagnostic only. */
    fun pendingCount(): Long = pendingOwnEyes
}
