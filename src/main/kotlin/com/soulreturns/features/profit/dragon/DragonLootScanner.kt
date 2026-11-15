package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.drops.DropResolver
import com.soulreturns.data.items.ItemCatalogClient
import com.soulreturns.util.SoulLogger
import com.soulreturns.util.toLegacyText
import net.minecraft.client.Minecraft
import net.minecraft.network.chat.Component
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.Locale

/**
 * Reads the loot armour-stands Hypixel renders at a dragon corpse and credits what they say to
 * [DragonProfitTracker].
 *
 * ### The one fact this whole strategy is built on
 * **Hypixel only sends the loot armour-stand packets when the client player is within ~20 blocks of
 * them.** A tag-killer standing 40 blocks off sees *no* stands at all until they walk over. Every
 * unusual thing below follows from that:
 *
 * - **Each scan re-centres on the player's current position**, never on where the banner fired. As
 *   the player walks in, freshly-loaded stands fall inside the radius and get picked up. Centring on
 *   the death position instead is the single mistake that makes this read as "the tracker misses
 *   most of my loot".
 * - **The window is 30 s** ([WINDOW_TICKS]) at two scans a second, which is enough time to cross the
 *   platform and let the far side of the pile load.
 * - **Per *stand*, the maximum count seen across scans is what gets credited, and those maxima are
 *   summed per item.** Picking loot up mid-window shrinks what is rendered, so the max is what stops
 *   that truncating the number. Keying it per stand rather than per item matters because one dragon
 *   can drop the same item as two stands — an `Ender Pearl` beside an `Ender Pearl x16` — and a max
 *   keyed by item id would quietly credit 16 of the 17.
 * - **Radius is xz-only.** Loot lands at whatever height the floor happens to be, so a 3D check drops
 *   the pile the moment the player stands above it.
 *
 * ### Granting is incremental, and safe because the counts only climb
 * Each scan grants the delta between a drop's running total and what has already been granted, so the
 * panel moves ~500 ms after the stands render rather than 30 s after the kill. That is only safe
 * because the total is monotonic — each stand's max never decreases on a transient miss, and a stand
 * is never dropped from the map once seen — and because the already-granted ledger says exactly which
 * part of it is new.
 *
 * ### Kill credit fires on the first non-empty scan
 * Not at window close, for the same responsiveness. A window that finds nothing at all still credits
 * the kill when it closes, so drops-per-kill arithmetic stays honest instead of quietly improving
 * every time someone walks away early.
 *
 * ### Kill source and own-eye count are frozen at scan-begin
 * Back-to-back kills otherwise bleed: finishing kill A and immediately summoning kill B flips
 * [DragonProfitTracker.currentKillSource] while A's window is still open, and A's drops land in B's
 * partition.
 */
object DragonLootScanner {
    private val logger = SoulLogger("Soul/DragonProfit")

    /**
     * xz radius in blocks, from the player's position each scan. Anything the client can see at all
     * is already inside ~20 blocks, so 30 is that plus margin for spawn timing rather than a real
     * reach.
     */
    private const val SCAN_RADIUS = 30.0
    private const val SCAN_RADIUS_SQ = SCAN_RADIUS * SCAN_RADIUS

    /** 600 ticks = 30 s. */
    private const val WINDOW_TICKS = 600

    /** 10 ticks = twice a second. */
    private const val SCAN_INTERVAL_TICKS = 10

    /**
     * 100 ticks = 5 s after the first stand is parsed, then the announcement fires.
     *
     * Long enough for someone still walking into the circle to load the rest of their pile, short
     * enough that the number still feels like it belongs to the kill. Stragglers after this point are
     * folded into the panel but miss the announced figure — by design, since the alternative is
     * announcing 30 s late.
     */
    private const val SUMMARY_DELAY_TICKS = 100

    /**
     * `"<Item Display Name> xN"` → name, count.
     *
     * **The ` xN` suffix is absent for a single item**, hence the optional group. A regex that
     * requires it silently drops every one-of stand — which on a dragon is most of the interesting
     * loot.
     */
    private val STAND_NAME_REGEX = Regex("""^(.+?)(?:\s+x(\d+))?$""")

    /**
     * The pet stand, matched against the **§-coded** name because the colour is the only thing that
     * says which tier it is: `§5` → EPIC → `ENDER_DRAGON;3`, `§6` → LEGENDARY → `ENDER_DRAGON;4`.
     *
     * The leading `.*` is load-bearing — it swallows the run of empty-but-coloured Component segments
     * Hypixel prefixes (`§f§f§7[Lvl 1] …`), which no fixed prefix would survive.
     */
    private val PET_REGEX = Regex(""".*\[Lvl \d+] §([0-9a-f])Ender Dragon$""")

    /** Vanilla's default name on the pedestal stands between the loot. Skipped before the regex. */
    private const val UNNAMED_STAND = "Armor Stand"

    /** One loot stand's reading: which item, and the most it has ever claimed. */
    private class StandObservation(val itemId: String, var count: Long)

    private class ActiveScan(
        val dragonType: DragonType,
        /** Frozen at begin — see the class doc. */
        val killSource: KillSource,
        /** Eyes the local player placed toward *this* kill, frozen for the same reason. */
        val ownEyes: Long,
    ) {
        /**
         * Entity id → what that **one stand** says, at the highest count it has ever shown.
         *
         * Keyed by stand, not by item id, and that distinction is load-bearing. The max is what makes
         * re-reading the same stand sixty times over the window idempotent — but one dragon can drop
         * the same item as two separate stands (`Ender Pearl` beside `Ender Pearl x16`), and a max
         * keyed by item id silently keeps only the larger. Per stand max, summed across stands, is
         * right for both.
         *
         * Entries are never removed. A stand that despawns because the loot was picked up keeps its
         * last observed count, which is exactly what stops a mid-window pickup truncating the credit.
         */
        val standCounts = HashMap<Int, StandObservation>()

        /** Item id → count already pushed to the tracker. */
        val grantedSoFar = HashMap<String, Long>()

        var killGranted = false
        var ticksSinceStart = 0
        var firstLootAtTick = -1
        var summaryFired = false

        /**
         * Stand names already reported as unresolved this window. The same stand is re-read twice a
         * second for 30 s, so without this one unknown drop is sixty log lines and a flushed scan
         * ring.
         */
        val reportedUnresolved = HashSet<String>()

        // Diagnostics for the "nothing was detected" case, which has several distinct causes that
        // look identical from the outside.
        val seenNames = LinkedHashSet<String>()
        var totalStandsSeen = 0
        var standsInRadius = 0
        var closestNamedDistance = Double.MAX_VALUE
        var closestNamedName: String? = null
    }

    @Volatile private var active: ActiveScan? = null

    /**
     * True while a window is open and no loot has rendered yet — the player is too far from the pile.
     * The panel swaps its list for an instruction banner on this, because an empty list after a kill
     * otherwise reads as "the tracker did not notice".
     */
    fun isScanActiveWithoutLoot(): Boolean {
        val ctx = active ?: return false
        return !ctx.killGranted
    }

    /** The dragon currently being scanned, or null. Diagnostics. */
    fun activeDragon(): DragonType? = active?.dragonType

    fun register() {
        Events.subscribe<DragonDied> { event ->
            if (!cfg.combat.dragons.enabled) return@subscribe
            beginScan(event.type)
        }
    }

    /**
     * Open a window. A previous one still open — two kills inside 30 s on the same instance — is
     * finalised first so its drops are not lost.
     */
    fun beginScan(dragonType: DragonType) {
        finalize("a new kill superseded it")
        val ctx = ActiveScan(
            dragonType = dragonType,
            killSource = DragonProfitTracker.currentKillSource,
            ownEyes = EyePlacementTracker.lastSpawnEyes,
        )
        active = ctx
        logger.info(
            "Dragon kill: ${dragonType.displayName} (${ctx.killSource}, ${ctx.ownEyes} own eye(s)) — " +
                "scanning for 30 s, re-centred on the player each pass",
        )
    }

    /** Called from [DragonTick]; does nothing at all while no window is open. */
    internal fun tick() {
        val ctx = active ?: return
        ctx.ticksSinceStart++
        if (ctx.ticksSinceStart % SCAN_INTERVAL_TICKS == 0) scanOnce(ctx)

        if (!ctx.summaryFired && ctx.firstLootAtTick >= 0 &&
            ctx.ticksSinceStart - ctx.firstLootAtTick >= SUMMARY_DELAY_TICKS
        ) {
            ctx.summaryFired = true
            // A copy: the window keeps mutating the original for another 25 s.
            DragonProfitAnnouncer.onDragonLootSummary(
                dragonType = ctx.dragonType,
                killSource = ctx.killSource,
                drops = HashMap(ctx.grantedSoFar),
                ownEyes = ctx.ownEyes,
            )
        }

        if (ctx.ticksSinceStart >= WINDOW_TICKS) finalize("the window expired")
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

            // Track the nearest named stand regardless of radius. It is what distinguishes "the loot
            // is there but out of range the whole window" from "no loot rendered at all".
            if (component != null) {
                val plainName = component.string
                if (plainName != UNNAMED_STAND) {
                    val dist = kotlin.math.sqrt(distSq)
                    if (dist < ctx.closestNamedDistance) {
                        ctx.closestNamedDistance = dist
                        ctx.closestNamedName = plainName
                    }
                }
            }

            if (distSq > SCAN_RADIUS_SQ) continue
            ctx.standsInRadius++
            if (component == null) continue
            val plain = component.string
            if (plain == UNNAMED_STAND) continue
            ctx.seenNames.add(plain)
            val resolved = resolveStand(ctx, component, plain)
            if (resolved == null) continue
            val (itemId, count) = resolved
            val seen = ctx.standCounts[entity.id]
            if (seen == null) {
                ctx.standCounts[entity.id] = StandObservation(itemId, count)
                DragonScanLog.record(ctx.dragonType, plain, itemId, count, count)
            } else if (count > seen.count) {
                // The same stand raising its own count — Hypixel filling the pile in, not a new drop.
                DragonScanLog.record(ctx.dragonType, plain, itemId, count, count - seen.count)
                seen.count = count
            }
        }
        grantPending(ctx)
    }

    /**
     * Push every drop whose observed total now exceeds what has been granted, and credit the kill on
     * the first pass that finds anything.
     *
     * The total per item is the **sum of the per-stand maxima**. Each stand's own max only ever rises
     * and stands are never dropped from the map, so the sum is monotonic too — which is what makes
     * granting the delta against [ActiveScan.grantedSoFar] safe from double-counting.
     */
    private fun grantPending(ctx: ActiveScan) {
        val totals = HashMap<String, Long>()
        for (observation in ctx.standCounts.values) {
            totals[observation.itemId] = (totals[observation.itemId] ?: 0L) + observation.count
        }
        var grantedThisPass = false
        for ((itemId, total) in totals) {
            val already = ctx.grantedSoFar[itemId] ?: 0L
            val delta = total - already
            if (delta <= 0L) continue
            DragonProfitTracker.grantDrop(ctx.dragonType, itemId, delta, ctx.killSource)
            ctx.grantedSoFar[itemId] = total
            grantedThisPass = true
        }
        if (grantedThisPass && !ctx.killGranted) {
            DragonProfitTracker.grantKill(ctx.dragonType, 1, ctx.killSource)
            ctx.killGranted = true
            ctx.firstLootAtTick = ctx.ticksSinceStart
            logger.info("${ctx.dragonType.displayName} (${ctx.killSource}) — first loot detected, kill credited")
        }
    }

    /**
     * A stand → `(itemId, count)`, or null.
     *
     * The pet pattern goes first because it needs the §-coded text to tell EPIC from LEGENDARY, and
     * the plain-text parser would happily read `"[Lvl 100] Ender Dragon"` as an item named that.
     * Everything else reverse-looks-up through [DropResolver] **scoped to this dragon's source**, so
     * a per-drop `displayNameOverride` (`"Aspect of the Dragons"`) resolves without a hardcoded map
     * here.
     */
    private fun resolveStand(
        ctx: ActiveScan,
        component: Component,
        plain: String,
    ): Pair<String, Long>? {
        if (plain.startsWith("[Lvl ")) {
            parsePet(component.toLegacyText())?.let { return it to 1L }
        }
        val (displayName, count) = parseStandName(plain) ?: return null
        val itemId = DropResolver.findItemIdByDisplayName(ctx.dragonType.sourceId, displayName)
        if (itemId == null) {
            // Once per distinct name per window — see ActiveScan.reportedUnresolved.
            if (ctx.reportedUnresolved.add(plain)) {
                // The scan is a radius sweep of every armour stand nearby, so End-island scenery —
                // travel-scroll signposts, floating labels — arrives alongside the loot. A name the
                // item catalog knows is a real gap in the drop graph and worth saying out loud; a name
                // it does not know is almost certainly not loot at all, and telling someone to add a
                // catalog row for a signpost is worse than saying nothing.
                if (ItemCatalogClient.byDisplayName(displayName) != null) {
                    logger.info("Unrecognised loot stand: '$displayName' (x$count) — add it to the backend drop catalog")
                } else {
                    logger.debug("Ignoring a stand that is not loot: '$plain'")
                }
                DragonScanLog.record(ctx.dragonType, plain, null, count, 0L)
            }
            return null
        }
        return itemId to count
    }

    private fun parseStandName(name: String): Pair<String, Long>? {
        val match = STAND_NAME_REGEX.matchEntire(name) ?: return null
        val display = match.groupValues[1].trim()
        if (display.isEmpty()) return null
        val countText = match.groupValues[2]
        val count = if (countText.isEmpty()) 1L else countText.toLongOrNull() ?: 1L
        return display to count
    }

    /**
     * `§5` → `ENDER_DRAGON;3` (EPIC), `§6` → `ENDER_DRAGON;4` (LEGENDARY). Those are the only two
     * tiers that drop in the wild, and both NEU and the backend's drop seed key the pet by that exact
     * `ENDER_DRAGON;<tier>` form.
     *
     * Anything else logs and yields nothing — guessing a tier would put a several-hundred-million
     * coin item in the wrong bucket, and a `null` here just means the stand goes into the scan log as
     * unresolved where somebody can look at it.
     */
    private fun parsePet(coloredName: String): String? {
        val match = PET_REGEX.matchEntire(coloredName) ?: return null
        val code = match.groupValues[1]
        if (code == "5") return "ENDER_DRAGON;3"
        if (code == "6") return "ENDER_DRAGON;4"
        logger.info("Unknown Ender Dragon pet tier §$code — extend the backend pet entries if this is real")
        return null
    }

    private fun finalize(reason: String) {
        val ctx = active ?: return
        active = null
        if (!ctx.killGranted) {
            // Nothing was ever read. The four causes look identical from in game, so name them.
            logger.info("${ctx.dragonType.displayName}: no loot detected ($reason)")
            logger.info(
                "  totalStandsSeen=${ctx.totalStandsSeen}, standsInRadius=${ctx.standsInRadius}, " +
                    "namedStandsInRadius=${ctx.seenNames.size}",
            )
            if (ctx.seenNames.isNotEmpty()) {
                logger.info("  in-radius named stands: ${ctx.seenNames.joinToString(", ") { "'$it'" }}")
            }
            ctx.closestNamedName?.let {
                logger.info(
                    "  closest named stand anywhere: '$it' at " +
                        String.format(Locale.ROOT, "%.1f", ctx.closestNamedDistance) + " blocks xz",
                )
            }
            // Credit the kill anyway — drops-per-kill has to count the ones that gave nothing.
            DragonProfitTracker.grantKill(ctx.dragonType, 1, ctx.killSource)
            return
        }
        val summary = ctx.grantedSoFar.entries.joinToString(", ") { (itemId, count) ->
            "${DropResolver.displayName(ctx.dragonType.sourceId, itemId)} x$count"
        }
        logger.info("${ctx.dragonType.displayName} (${ctx.killSource}) window closed ($reason) — $summary")
    }
}
