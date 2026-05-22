package com.soulreturns.features.diana

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.MythologicalMobCatalog
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientEntityEvents
import net.fabricmc.fabric.api.event.player.AttackEntityCallback
import net.minecraft.client.Minecraft
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.skyblock.MythologicalMobCatalog.Mob
import net.minecraft.world.InteractionResult
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Detects Mythological lootshare events — a nearby Mythological mob the player damaged
 * dies without the player being the one who dug it out. Bumps the mob tracker's
 * per-mob lootshare counter and exposes a short attribution window so
 * [DianaLootWatcher] can route inventory pickups from that death to the lootshare bucket.
 *
 * **Hypixel mob entity model.** Most named mobs are `Player`-typed entities at the mob's
 * feet (Minos Inquisitor, Sphinx, …); some use vanilla mob entity classes (Cretan Bull
 * is a cow, Gaia Construct an iron golem, etc.). Identity is always carried by the
 * entity's `name` (`customName` when present, else the default mob type name). We match
 * against the catalog by name string and don't gate on entity class — same approach
 * [com.soulreturns.util.MobSpotter] uses.
 *
 * **Detection flow:**
 *   1. [AttackEntityCallback] — when the local player swings at any entity that
 *      [resolveDianaMob] identifies as a Diana mob, record `(uuid, mobName, hitTime)` in
 *      [recentlyHit]. The mob is resolved via three strategies: exact `entity.name`
 *      match (Player-typed mobs) → substring match against the catalog (full-nameplate
 *      customName) → nearby armor-stand scan (vanilla-typed mobs with no customName on
 *      the entity itself). The 1 % damage threshold the game uses for lootshare
 *      eligibility is satisfied in practice by any deliberate hit; we can't read the
 *      server-side damage % from the client.
 *   2. [ClientEntityEvents.ENTITY_UNLOAD] — when an entity unloads, if it's in
 *      [recentlyHit] AND within [LOOTSHARE_RADIUS] AND has no pending own-dig credit,
 *      fire lootshare for that mob.
 *   3. [ChatMessage] subscription — every `MythologicalMobCatalog.matchDigOut` hit
 *      AND every `matchCocoon` hit adds one credit to [ownedKillCredits] for that mob
 *      name. Cocoon equipment respawns a Diana mob ~5 s after the first kill without
 *      emitting a fresh "You dug out" line — banking a credit on the cocoon line is
 *      what stops the second death from being mis-attributed as lootshare. The unload
 *      handler consumes one credit per matching mob death and suppresses lootshare.
 *      Counted (not timed) so a slow death-animation gap between chat-fires-at-dig and
 *      entity-unloads-on-death doesn't leak the own-kill into the lootshare bucket.
 *
 * **Lootshare window** — exposed via [isInLootshareWindow]. After a lootshare event
 * fires the window stays open for [LOOTSHARE_WINDOW_MS]. This signal feeds the
 * **participation counter** (`MythologicalMobTracker.recordLootshare`) — it counts
 * every other player's mob the player damaged, drop or no drop.
 *
 * **Loot attribution** is a **separate** chat-driven signal: Hypixel sends
 * `LOOT SHARE You received loot for assisting <player>!` when an inventory item
 * actually drops via lootshare. [isInLootShareLootWindow] reflects that line so
 * [DianaLootWatcher] can route the next inventory delta to the LOOTSHARE bucket. The
 * two signals are decoupled on purpose — counting participation needs every nearby
 * death, but bucket attribution only happens when something actually drops.
 */
object DianaLootshareDetector {
    private val logger = SoulLogger("Soul/Diana")

    private const val HIT_TTL_MS = 60_000L
    private const val LOOTSHARE_RADIUS = 30.0
    private const val LOOTSHARE_RADIUS_SQ = LOOTSHARE_RADIUS * LOOTSHARE_RADIUS

    /** Vertical range to walk for a nearby armor stand carrying the Diana nameplate. */
    private const val NAMEPLATE_VERT_RANGE = 3.5
    private const val NAMEPLATE_HORIZ_RADIUS_SQ = 4.0

    /**
     * How long after a lootshare event the inventory watcher treats incoming drops as
     * lootshare-attributable. Death drops in SkyBlock land in inventory instantly
     * server-side; 2 s covers client-side render lag + the 1 s inventory tick cadence
     * with a small safety margin.
     */
    const val LOOTSHARE_WINDOW_MS = 2_000L

    /**
     * Width of the loot-attribution window opened by a `LOOT SHARE You received loot...`
     * chat line. The chat arrives within ~1 tick of the inventory packet so this only
     * needs to absorb client-side render/tick jitter; 3 s is generous.
     */
    const val LOOT_SHARE_LOOT_WINDOW_MS = 3_000L

    /**
     * Regex for Hypixel's `LOOT SHARE You received loot for assisting <player>!` line.
     * Fires every time the player receives ≥1 lootshare-attributable item from another
     * player's mob kill — the authoritative signal for routing inventory deltas to the
     * LOOTSHARE bucket. Color codes already stripped by the caller. Non-greedy player
     * name + no trailing `$` anchor so any third-party chat suffix is tolerated.
     */
    private val LOOT_SHARE_CHAT = Regex("""^LOOT SHARE You received loot for assisting (.+?)!""")

    /**
     * Hit entities — `uuid → (mobName, hitTime)`. Mob name is resolved at hit time (when the
     * full entity context is available) so the unload handler can attribute correctly even
     * if the entity's nearby armor stand has already despawned by then.
     */
    private val recentlyHit: ConcurrentHashMap<UUID, HitInfo> = ConcurrentHashMap()

    /**
     * Per-mob "own dig" credits. Each `You dug out a <Mob>!` chat increments; each candidate
     * lootshare unload (matching the mob name) consumes one credit and is suppressed. This
     * replaces the previous time-windowed suppression — death animations can fire seconds
     * after the chat, and a fixed 3 s window leaked own-kills as ghost lootshares.
     */
    private val ownedKillCredits: ConcurrentHashMap<String, Int> = ConcurrentHashMap()

    private data class HitInfo(val mobName: String, val hitTime: Long)

    /**
     * Entity-name aliases used by [resolveDianaMob]'s substring strategy. Some Hypixel
     * mobs use a different noun on the entity's customName than the catalog stores —
     * notably **Siamese Lynxes** spawn as two `minecraft:cat` entities (no customName on
     * the cat itself) with a single armor stand nearby carrying the nameplate
     * `"[Lv200] ✿☮ Empyrean Bagheera 1.2M/1.2M❤"` — Hypixel's actual mob name is
     * "Bagheera", the catalog stores the chat-side name "Siamese Lynxes".
     *
     * Key = the canonical [MythologicalMobCatalog] mob name. Values = additional substrings
     * to check against the entity's name. Add entries here when the "Diana attack-resolve
     * miss" log line surfaces a Hypixel-side name that doesn't match the catalog.
     */
    private val ENTITY_NAME_ALIASES: Map<String, List<String>> =
        mapOf(
            "Siamese Lynxes" to listOf("Bagheera"),
        )

    /**
     * Per-mob credit multiplier — how many credits a single dig-out / cocoon line banks for
     * the [ownedKillCredits] map. Most Diana mobs spawn one entity per dig-out (multiplier
     * defaults to 1 via [creditsPerSpawn]); **Siamese Lynxes** spawn two cat entities for
     * one dig-out, so the chat needs to bank 2 credits — one per entity-death-unload — to
     * cover both deaths without a ghost lootshare on whichever cat unloads last.
     */
    private val ENTITY_COUNT_PER_SPAWN: Map<String, Int> =
        mapOf(
            "Siamese Lynxes" to 2,
        )

    private fun creditsPerSpawn(mobName: String): Int = ENTITY_COUNT_PER_SPAWN[mobName] ?: 1

    @Volatile private var lastLootshareAt: Long = 0L

    /**
     * Timestamp of the most recent `LOOT SHARE You received loot for assisting <player>!`
     * chat line. This is Hypixel's authoritative signal that the player's inventory has
     * just received loot from another player's Diana mob. The entity-unload-based
     * [lastLootshareAt] still drives the **participation counter** (how many other
     * people's mobs the player damaged) — the chat signal here drives **loot bucket
     * attribution**, since it only fires when an item actually dropped.
     */
    @Volatile private var lastLootShareLootAt: Long = 0L

    /** True for the [LOOTSHARE_WINDOW_MS] following any detected lootshare event. */
    fun isInLootshareWindow(): Boolean = System.currentTimeMillis() - lastLootshareAt < LOOTSHARE_WINDOW_MS

    /**
     * True for [LOOT_SHARE_LOOT_WINDOW_MS] after a `LOOT SHARE` chat line. Drops landing
     * in this window are attributed to LOOTSHARE; outside this window they're own-kill
     * MOB_LOOT. Width is generous (3 s) because the chat and the inventory delta are
     * processed on the same client tick but a snapshot tick may straddle them.
     */
    fun isInLootShareLootWindow(): Boolean =
        System.currentTimeMillis() - lastLootShareLootAt < LOOT_SHARE_LOOT_WINDOW_MS

    fun register() {
        AttackEntityCallback.EVENT.register(
            AttackEntityCallback { _, _, _, entity, _ ->
                onAttack(entity)
                InteractionResult.PASS
            },
        )
        ClientEntityEvents.ENTITY_UNLOAD.register(
            ClientEntityEvents.Unload { entity, _ -> onUnload(entity) },
        )
        Events.subscribe(this)
    }

    private fun onAttack(entity: Entity) {
        if (!cfg.dev.trackers.mythologicalTracker()) return
        // Armor stands carry the nameplate but are NOT the mob — `entity.name.string`
        // for one like `"[Lv###] ... Minotaur ##/##❤"` substring-matches a catalog entry
        // and would otherwise get registered alongside the real mob, producing a
        // duplicate lootshare credit when both UUIDs unload at death.
        if (entity is ArmorStand) return
        val mob = resolveDianaMob(entity)
        if (mob == null) {
            // Only log Hub-area misses to avoid spam from non-Diana attacks elsewhere.
            // Surface the entity's name + Java class so the alias map can be extended
            // when Hypixel uses a noun the catalog doesn't carry (Siamese Lynxes singular,
            // Cretan Bull vanilla "Cow", etc.).
            if (LocationApi.isInArea("Hub")) {
                logger.info(
                    "Diana attack-resolve miss: name='${entity.name.string}' type=${entity.javaClass.simpleName}",
                )
            }
            return
        }
        recentlyHit[entity.uuid] = HitInfo(mob.name, System.currentTimeMillis())
    }

    private fun onUnload(entity: Entity) {
        // Belt-and-braces: even if an armor stand uuid slipped into the hit map via some
        // other path (legacy from a prior session, future feature, etc.), don't process
        // it on unload. The "real" mob entity is the only legitimate lootshare source.
        if (entity is ArmorStand) {
            recentlyHit.remove(entity.uuid)
            return
        }
        val hit = recentlyHit.remove(entity.uuid) ?: return
        if (!cfg.dev.trackers.mythologicalTracker()) return
        val now = System.currentTimeMillis()
        if (now - hit.hitTime > HIT_TTL_MS) return
        val player = Minecraft.getInstance().player ?: return
        val dx = entity.x - player.x
        val dy = entity.y - player.y
        val dz = entity.z - player.z
        if (dx * dx + dy * dy + dz * dz > LOOTSHARE_RADIUS_SQ) return
        // Consume one own-dig credit per matching mob death. If credits exist, this was
        // the player's own dig — don't double-count as lootshare. Otherwise it's a real
        // lootshare event (someone else dug the burrow, player just damaged it).
        val credits = ownedKillCredits[hit.mobName] ?: 0
        logger.info(
            "Diana unload: name='${hit.mobName}' credits=$credits type=${entity.javaClass.simpleName} dist=${kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toInt()}b creditMap=$ownedKillCredits",
        )
        if (credits > 0) {
            ownedKillCredits[hit.mobName] = credits - 1
            return
        }
        MythologicalMobTracker.recordLootshare(hit.mobName)
        lastLootshareAt = now
        logger.info("Lootshare: ${hit.mobName} (dist=${kotlin.math.sqrt(dx * dx + dy * dy + dz * dz).toInt()}b)")
    }

    /**
     * Resolve an entity to a Diana mob via three strategies in order:
     *  1. Exact match on `entity.name.string` against the catalog. Hits the Player-typed
     *     mobs (Minos Inquisitor, Sphinx, …) which carry a clean bare-name `displayName`.
     *  2. Substring match — the entity's name `contains` a catalog name. Hits vanilla-
     *     typed mobs (cow / iron golem / …) when Hypixel sets customName to the full
     *     `[Lv###] <Empyrean-tier?> <Name> ##/##❤ <symbols>` nameplate.
     *  3. Nearby-armor-stand scan — for the iron golem case where customName is empty on
     *     the mob itself but a separate armor stand 1-2 blocks above carries the
     *     nameplate. Walks armor stands within the entity's horizontal radius +
     *     vertical range above.
     */
    private fun resolveDianaMob(entity: Entity): Mob? {
        val name = entity.name.string
        MythologicalMobCatalog.byName(name)?.let { return it }
        matchByNameOrAlias(name)?.let { return it }
        val level = Minecraft.getInstance().level ?: return null
        for (other in level.entitiesForRendering()) {
            if (other !is ArmorStand) continue
            val dy = other.y - entity.y
            if (dy < 0.0 || dy > NAMEPLATE_VERT_RANGE) continue
            val dx = other.x - entity.x
            val dz = other.z - entity.z
            if (dx * dx + dz * dz > NAMEPLATE_HORIZ_RADIUS_SQ) continue
            matchByNameOrAlias(other.name.string)?.let { return it }
        }
        return null
    }

    /**
     * Substring-match the catalog against [text]. Tries each catalog mob name first, then
     * each mob's [ENTITY_NAME_ALIASES] entries. Returns the first matching catalog mob,
     * or null when nothing matches.
     */
    private fun matchByNameOrAlias(text: String): Mob? {
        for (mob in MythologicalMobCatalog.all()) {
            if (text.contains(mob.name)) return mob
            val aliases = ENTITY_NAME_ALIASES[mob.name] ?: continue
            for (alias in aliases) {
                if (text.contains(alias)) return mob
            }
        }
        return null
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (event.source != ChatMessage.Source.SERVER) return
        val stripped = MessageDetector.stripColorCodes(event.raw).trim()
        // Bank one credit per dig-out (mob freshly spawned, original kill is the player's)
        // AND one credit per cocoon (cocoon reforge respawns the mob a few seconds after
        // the first kill — without a fresh "You dug out" line — so the second death needs
        // its own credit to avoid being mis-attributed as lootshare).
        MythologicalMobCatalog.matchDigOut(stripped)?.let { mob ->
            // Dig-out applies the multi-entity multiplier: Siamese Lynxes spawn as 2 cats
            // per dig-out, so credit twice to cover both entity-unload events.
            val n = creditsPerSpawn(mob.name)
            val newValue = ownedKillCredits.merge(mob.name, n) { a, b -> a + b }
            logger.info("Diana own-dig credit: ${mob.name} +$n -> $newValue (line='$stripped')")
            return
        }
        MythologicalMobCatalog.matchCocoon(stripped)?.let { mob ->
            // Cocoon always banks ONE credit even for multi-entity mobs — the reforge
            // perk only cocoons a single entity per proc, so a Siamese Lynxes cocoon line
            // produces exactly one extra entity death, not two.
            val newValue = ownedKillCredits.merge(mob.name, 1) { a, b -> a + b }
            logger.info("Diana cocoon credit: ${mob.name} -> $newValue (line='$stripped')")
            return
        }
        // LOOT SHARE chat — Hypixel's authoritative signal that the player just received
        // lootshare loot from another player's Diana mob. Opens the LOOTSHARE attribution
        // window for [LOOT_SHARE_LOOT_WINDOW_MS]; [DianaLootWatcher] reads this to pick
        // the bucket for the next inventory delta.
        LOOT_SHARE_CHAT.find(stripped)?.let { match ->
            lastLootShareLootAt = System.currentTimeMillis()
            logger.info("Diana LOOT SHARE: assisting=${match.groupValues[1]} (line='$stripped')")
        }
    }
}
