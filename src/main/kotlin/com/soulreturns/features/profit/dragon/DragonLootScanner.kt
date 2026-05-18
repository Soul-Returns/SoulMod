package com.soulreturns.features.profit.dragon

import com.soulreturns.util.SoulLogger
import com.soulreturns.util.toLegacyText
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.decoration.ArmorStand

/**
 * Scans loot armor-stands after a dragon death and grants the parsed drops to
 * [DragonProfitTracker]. Hypixel renders each player's loot privately as a cluster of
 * invisible armor stands at the kill site, each carrying a `customName` like
 * `"Protector Dragon Fragment x8"` (or `"Draconic Shard"` for quantity 1).
 *
 * **Render-distance gating** — this is the load-bearing fact for the scan strategy.
 * Hypixel only sends the loot armor-stand entity packets when the **client player** is
 * within ~20 blocks of the loot. A tag-killer 40+ blocks from the corpse won't see any
 * stands at all until they walk closer. So:
 *  - Each scan **re-centers on the player's current position** (not the banner-time
 *    position). As the player walks toward the corpse, freshly-loaded stands fall inside
 *    the per-scan radius and get picked up.
 *  - The window is **30 seconds** to give the player time to walk to the loot. (Common
 *    case: ~5-10 s for short walks; the longer window survives end-of-fight detours.)
 *  - We keep the per-drop **max count** seen across scans, so pickup mid-window doesn't
 *    truncate the recorded amount — the highest count we ever observed is what we credit.
 *
 * **Radius:** xz-only (loot lands at variable floor heights, so a 3D distance check
 * would miss drops when the player is up high). 30 blocks is plenty when re-centering on
 * the player — they're already within ~20 blocks of the loot by the time stands render.
 *
 * **Granting is incremental.** Each scan, for any drop whose `maxCount` is higher than
 * what's been granted so far, the delta gets granted right away to [DragonProfitTracker].
 * This means the HUD updates ~500 ms after stands render, not 30 s after the death banner.
 * Safe against double-counting because `maxCounts` is monotonic non-decreasing (we always
 * keep the highest count ever seen per drop, never decrement on transient miss) and we
 * track `grantedSoFar` to know exactly which delta is new this scan. The 30 s window now
 * serves as an upper bound for stragglers (delayed-spawn drops or slow players) rather
 * than the only time data lands in the tracker.
 *
 * **Kill credit fires on the first non-empty scan**, not at window close — same reasoning.
 * Kills with no detected loot still get credited at finalize so drops-per-kill math holds.
 */
object DragonLootScanner {
    private val logger = SoulLogger("Soul/DragonProfit")

    /**
     * xz radius in blocks, **measured from the player's current position each scan**.
     * Loot stands only render within ~20 blocks of the client player, so any stand we can
     * "see" via `entitiesForRendering` is already within this radius by definition; 30
     * just adds a small margin for spawn timing. Scoping by player position (rather than
     * the banner-time death position) is what lets tag-killers walk over to their pile
     * and have the scanner pick it up as it loads in.
     */
    private const val SCAN_RADIUS = 30.0
    private const val SCAN_RADIUS_SQ = SCAN_RADIUS * SCAN_RADIUS

    /**
     * ticks. 600 = 30 seconds. Long enough for the player to walk from anywhere on the
     * dragon platform to their loot pile, then linger long enough for the stands to fully
     * load. Mid-window pickup is fine — see the per-drop max-count strategy in the kdoc.
     */
    private const val WINDOW_TICKS = 600

    /** ticks between scans. 10 = twice per second. */
    private const val SCAN_INTERVAL_TICKS = 10

    /**
     * Delay (ticks) after the first parsed loot stand before firing the profit-summary
     * announcement to [DragonProfitAnnouncer]. 100 = 5 seconds. Long enough for a player
     * still walking into the loot circle to load the rest of the stands (Hypixel only
     * renders each within ~20 blocks of the local client), short enough that the
     * announcement still feels real-time. Window is 30 s total, so even at this delay the
     * scan has 25 s of buffer for stragglers to be folded into the live HUD — they just
     * miss the announced number, by design.
     */
    private const val SUMMARY_DELAY_TICKS = 100

    /**
     * `"<Item Display Name> xN"` → groups (`<Item Display Name>`, `N`). The `xN` suffix is
     * optional — a stand carrying a single item omits it entirely.
     */
    private val STAND_NAME_REGEX = Regex("""^(.+?)(?:\s+x(\d+))?$""")

    /**
     * Pet armor-stand pattern, against the §-coded customName.
     * Captures the §-code char immediately before `Ender Dragon` — that's the rarity tint:
     *  - `§5` (dark_purple) → EPIC → `ENDER_DRAGON;3`
     *  - `§6` (gold)        → LEGENDARY → `ENDER_DRAGON;4`
     * Only Epic / Legendary tiers drop in the wild; other §-codes log + skip. The leading
     * `.*` swallows the inevitable preamble of empty-but-colored Component segments
     * (`§f§f§7[Lvl 1] …`) so we don't have to anchor on a fixed prefix.
     */
    private val PET_REGEX = Regex(""".*\[Lvl \d+\] §([0-9a-f])Ender Dragon$""")

    /**
     * Currently-active scan window, or null when no kill is being tracked. Re-centering on
     * the player each tick means we don't carry the banner-time position here — only the
     * dragon type, kill source (frozen at scan-begin time), the deadline, and accumulated
     * state.
     */
    private data class ActiveScan(
        val dragonType: DragonType,
        /**
         * Frozen at [beginScan] time so back-to-back kills don't bleed kill-source
         * attribution. If a player finishes kill A and immediately summons kill B while
         * A's scan window is still open, B's spawn message would otherwise flip
         * [DragonProfitTracker.currentKillSource] before A finalizes, and A's drops would
         * land in B's partition.
         */
        val killSource: KillSource,
        /**
         * Number of Summoning Eyes the **local player** placed for this specific kill.
         * Snapshotted from [EyePlacementTracker.lastSpawnEyes] at [beginScan] time so
         * back-to-back spawns can't overwrite it before the announcer fires. Used by
         * [DragonProfitAnnouncer] to subtract per-kill eye cost from the gross drop value
         * in the announced profit number.
         */
        val ownEyes: Long,
        val maxCounts: MutableMap<DragonDrop, Long> = mutableMapOf(),
        /**
         * Per-drop count already pushed to [DragonProfitTracker]. Each scan grants
         * `maxCounts[drop] - grantedSoFar[drop]` if positive, then bumps `grantedSoFar`
         * to match. Guarantees no double-counting even as `maxCounts` keeps climbing.
         */
        val grantedSoFar: MutableMap<DragonDrop, Long> = mutableMapOf(),
        /** Flips true on the first scan that found any loot — prevents double-grantKill. */
        var killGranted: Boolean = false,
        var ticksSinceStart: Int = 0,
        /**
         * Tick index at which the FIRST loot stand was parsed (i.e. the same tick
         * `killGranted` flipped). -1 until that happens. Used to schedule a delayed
         * profit-summary callback to [DragonProfitAnnouncer] — see [SUMMARY_DELAY_TICKS].
         */
        var firstLootAtTick: Int = -1,
        /** One-shot guard so the announcer's `onDragonLootSummary` fires at most once per scan. */
        var summaryFired: Boolean = false,
        /**
         * Distinct custom-name strings observed inside the radius during this scan
         * window — used for diagnostics when no loot is detected, so the log can tell us
         * whether stands were missing entirely vs in the wrong place vs unparseable.
         */
        val seenNames: MutableSet<String> = mutableSetOf(),
        /** Total armor stands observed across all scans (any radius). */
        var totalStandsSeen: Int = 0,
        /** Stands inside the radius across all scans. */
        var standsInRadius: Int = 0,
        /** Closest customName-bearing stand observed (xz distance, regardless of radius). */
        var closestNamedStandDistance: Double = Double.MAX_VALUE,
        var closestNamedStandName: String? = null,
    )

    @Volatile private var active: ActiveScan? = null

    /**
     * True while a scan window is open but no loot has been detected yet — i.e. the player
     * hasn't walked close enough to the loot pile for Hypixel to render the armor stands.
     * The HUD uses this to swap its scrollable list for a "Go near the loot to track it"
     * instruction. Flips back to false as soon as the first loot stand is parsed (the same
     * moment `killGranted` flips to true inside `grantPending`).
     */
    fun isScanActiveWithoutLoot(): Boolean {
        val ctx = active ?: return false
        return !ctx.killGranted
    }

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                tick()
            },
        )
    }

    /**
     * Open the scan window for a dragon death. Called from [DragonDeathDetector]. If a
     * previous window is still open (rare — two kills in 30 s on the same instance), the
     * older one is finalised first so its drops aren't lost.
     */
    fun beginScan(dragonType: DragonType) {
        finalize(reason = "new kill overrides previous scan")
        val source = DragonProfitTracker.currentKillSource
        val ownEyes = EyePlacementTracker.lastSpawnEyes
        active = ActiveScan(dragonType = dragonType, killSource = source, ownEyes = ownEyes)
        logger.info(
            "Dragon kill: ${dragonType.displayName} ($source, $ownEyes own eye(s)) — scanning for loot " +
                "(30 s window, re-centered on player)",
        )
    }

    private fun tick() {
        val ctx = active ?: return
        ctx.ticksSinceStart++
        if (ctx.ticksSinceStart % SCAN_INTERVAL_TICKS == 0) {
            scanOnce(ctx)
        }
        // Delayed profit-summary trigger — fires once, 5 s after the first stand parsed.
        // `firstLootAtTick` is set inside [grantPending] on the kill-credit transition.
        if (!ctx.summaryFired && ctx.firstLootAtTick >= 0 &&
            ctx.ticksSinceStart - ctx.firstLootAtTick >= SUMMARY_DELAY_TICKS
        ) {
            ctx.summaryFired = true
            // Snapshot to avoid mutation races if the announcer ever iterates async.
            val snapshot = HashMap(ctx.grantedSoFar)
            DragonProfitAnnouncer.onDragonLootSummary(
                dragonType = ctx.dragonType,
                killSource = ctx.killSource,
                drops = snapshot,
                ownEyes = ctx.ownEyes,
            )
        }
        if (ctx.ticksSinceStart >= WINDOW_TICKS) {
            finalize(reason = "window expired")
        }
    }

    private fun scanOnce(ctx: ActiveScan) {
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return
        val player = mc.player ?: return
        val centerX = player.x
        val centerZ = player.z
        for (entity in level.entitiesForRendering()) {
            if (entity !is ArmorStand) continue
            ctx.totalStandsSeen++
            val dx = entity.x - centerX
            val dz = entity.z - centerZ
            val distSq = dx * dx + dz * dz
            val component = entity.customName
            // Track the closest customName-bearing stand globally — diagnostic for the
            // "no loot detected" failure mode, tells us if loot exists but lies outside
            // the radius.
            if (component != null) {
                val plainName = component.string
                if (plainName != "Armor Stand") {
                    val dist = kotlin.math.sqrt(distSq)
                    if (dist < ctx.closestNamedStandDistance) {
                        ctx.closestNamedStandDistance = dist
                        ctx.closestNamedStandName = plainName
                    }
                }
            }
            if (distSq > SCAN_RADIUS_SQ) continue
            ctx.standsInRadius++
            if (component == null) continue
            val plain = component.string
            // Generic stands (the pedestals between loot items) all carry the literal text
            // "Armor Stand" via vanilla naming — skip those without touching the regex.
            if (plain == "Armor Stand") continue
            ctx.seenNames.add(plain)
            val (drop, count) = resolveStand(component, plain) ?: continue
            val prev = ctx.maxCounts[drop] ?: 0L
            if (count > prev) ctx.maxCounts[drop] = count
        }
        // Incremental grant — push any new deltas to the tracker so the HUD updates
        // ~500 ms after stands render, instead of waiting for window-close.
        grantPending(ctx)
    }

    /**
     * For each drop whose observed max exceeds what we've already granted, push the delta
     * to [DragonProfitTracker] and bump the per-drop ledger. First non-empty pass also
     * credits the kill itself.
     */
    private fun grantPending(ctx: ActiveScan) {
        var grantedThisPass = false
        for ((drop, max) in ctx.maxCounts) {
            val already = ctx.grantedSoFar[drop] ?: 0L
            val delta = max - already
            if (delta <= 0L) continue
            DragonProfitTracker.grantDrop(ctx.dragonType, drop, delta, ctx.killSource)
            ctx.grantedSoFar[drop] = max
            grantedThisPass = true
        }
        if (grantedThisPass && !ctx.killGranted) {
            DragonProfitTracker.grantKill(ctx.dragonType, 1, ctx.killSource)
            ctx.killGranted = true
            ctx.firstLootAtTick = ctx.ticksSinceStart
            logger.info(
                "Dragon ${ctx.dragonType.displayName} (${ctx.killSource}) — first loot detected, kill credited",
            )
        }
    }

    /**
     * Resolve a stand to (drop, count). Tries the pet pattern first (needs the §-colored
     * text to discriminate rarity), then falls back to the standard `"<Name> [xN]"` plain-
     * text parser keyed by [DragonDrop.byDisplayName].
     */
    private fun resolveStand(
        component: net.minecraft.network.chat.Component,
        plain: String,
    ): Pair<DragonDrop, Long>? {
        // Pet detection — only profitable to compute the colored text if the plain text
        // has the `[Lvl N]` prefix shape, which is unique to pets.
        if (plain.startsWith("[Lvl ")) {
            val colored = component.toLegacyText()
            val petDrop = parsePet(colored)
            if (petDrop != null) return petDrop to 1L
        }
        val parsed = parseStandName(plain) ?: return null
        val (displayName, count) = parsed
        val drop = DragonDrop.byDisplayName(displayName)
        if (drop == null) {
            logger.info("Unrecognised loot stand: '$displayName' (x$count) — add to DragonDrop catalog")
            return null
        }
        return drop to count
    }

    /**
     * Parse a stand `customName` into (display, count). Returns null when the name doesn't
     * fit the loot-stand shape — that should only happen for genuinely unrelated stands
     * since we already filter `"Armor Stand"` literals upstream.
     */
    private fun parseStandName(name: String): Pair<String, Long>? {
        val match = STAND_NAME_REGEX.matchEntire(name) ?: return null
        val display = match.groupValues[1].trim()
        if (display.isEmpty()) return null
        val countStr = match.groupValues[2]
        val count = if (countStr.isEmpty()) 1L else countStr.toLongOrNull() ?: 1L
        return display to count
    }

    /**
     * `§5` → EPIC pet, `§6` → LEGENDARY pet. Unknown rarity colors log and skip — better
     * to leave a drop unrecorded than mis-attribute it.
     */
    private fun parsePet(coloredName: String): DragonDrop? {
        val match = PET_REGEX.matchEntire(coloredName) ?: return null
        return when (match.groupValues[1]) {
            "5" -> DragonDrop.ENDER_DRAGON_PET_EPIC
            "6" -> DragonDrop.ENDER_DRAGON_PET_LEGENDARY
            else -> {
                logger.info("Unknown pet tier §${match.groupValues[1]} on Ender Dragon — add a DragonDrop entry if real")
                null
            }
        }
    }

    private fun finalize(reason: String) {
        val ctx = active ?: return
        active = null
        if (!ctx.killGranted) {
            // No loot was ever observed in this window — diagnostic dump + credit-only kill.
            logger.info("Dragon ${ctx.dragonType.displayName}: no loot detected ($reason)")
            // Diagnostic dump — tells us why nothing matched:
            //  - totalStandsSeen=0 → no armor stands in the loaded world at all
            //  - standsInRadius=0 but closestNamedStandDistance < ∞ → loot exists but
            //    sits outside `SCAN_RADIUS` from the player (during the whole window)
            //  - seenNames empty but standsInRadius > 0 → stands are there but unnamed
            //  - seenNames non-empty → check the unrecognised-loot-stand log line above
            logger.info(
                "  diagnostic: totalStandsSeen=${ctx.totalStandsSeen}, " +
                    "standsInRadius=${ctx.standsInRadius}, " +
                    "namedStandsInRadius=${ctx.seenNames.size}",
            )
            if (ctx.seenNames.isNotEmpty()) {
                logger.info("  in-radius named stands: ${ctx.seenNames.joinToString(", ") { "'$it'" }}")
            }
            if (ctx.closestNamedStandName != null) {
                logger.info(
                    "  closest named stand anywhere: '${ctx.closestNamedStandName}' " +
                        "at ${"%.1f".format(ctx.closestNamedStandDistance)} blocks xz",
                )
            }
            // Still credit the kill — drops-per-kill displays use this. Source is the
            // frozen scan-time source.
            DragonProfitTracker.grantKill(ctx.dragonType, 1, ctx.killSource)
            return
        }
        // Loot was already granted incrementally inside [grantPending]; nothing more to
        // do here besides logging the final totals.
        val summary = ctx.grantedSoFar.entries.joinToString(", ") { (d, c) -> "${d.displayName} x$c" }
        logger.info("Dragon ${ctx.dragonType.displayName} (${ctx.killSource}) window closed ($reason) — $summary")
    }
}
