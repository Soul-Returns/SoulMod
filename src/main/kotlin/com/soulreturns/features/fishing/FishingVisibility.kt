package com.soulreturns.features.fishing

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.location.LocationApi
import com.soulreturns.data.model.AreaChanged
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.player.Player
import net.minecraft.world.entity.projectile.FishingHook

/**
 * Visibility gate for the Fishing Tracker HUD.
 *
 * The HUD only makes sense when the player is actually fishing — outside that context it
 * just adds clutter. Visibility requires **all** of:
 *  1. A SkyBlock fishing rod (per [FISHING_ROD_IDS]) somewhere in the player's main
 *     inventory + hotbar.
 *  2. Current area listed in [FISHING_AREAS] — Hypixel islands where fishing is a thing.
 *  3. Recent proximity to a water or lava block, within [LIQUID_RADIUS_BLOCKS]. The "recent"
 *     window starts at [LIQUID_SHOW_GRACE_MS] after the last detected liquid block — so the
 *     HUD stays visible through brief combat-pulls away from the pond, then fades 60 s after
 *     the player wanders off.
 *  4. **First cast detected** — the very first show of the HUD this session requires the
 *     player's *own* `FishingHook` entity to land in water or lava. Once that flips
 *     [firstCastDetected] true, the flag persists for the JVM session so subsequent
 *     hide/show cycles only need the first three conditions. Without this gate, the HUD
 *     would pop up just from standing near any pond on the right island — annoying when
 *     the player is doing some other activity that happens to involve being near water.
 *
 * The check runs once per [SCAN_INTERVAL_TICKS] (default 20 = 1 s real time) — fast enough
 * that "walking up to a pond" shows the HUD without a perceptible lag, slow enough that the
 * cubic block scan doesn't show up in profiles.
 */
object FishingVisibility {
    private const val SCAN_INTERVAL_TICKS = 20
    private const val LIQUID_RADIUS_BLOCKS = 15
    private const val LIQUID_RADIUS_SQ = LIQUID_RADIUS_BLOCKS * LIQUID_RADIUS_BLOCKS
    private const val LIQUID_SHOW_GRACE_MS = 60_000L

    /** Hypixel SkyBlock IDs for every rod that should activate the fishing tracker. */
    private val FISHING_ROD_IDS: Set<String> =
        setOf(
            "ROD_OF_THE_SEA",
            "FISHING_ROD",
            "CHALLENGE_ROD",
            "CHAMP_ROD",
            "LEGEND_ROD",
            "GIANT_FISHING_ROD",
            "STARTER_LAVA_ROD",
            "POLISHED_TOPAZ_ROD",
            "MAGMA_ROD",
            "INFERNO_ROD",
            "HELLFIRE_ROD",
            "DIRT_ROD",
            "BINGO_ROD",
            "BINGO_LAVA_ROD",
        )

    /** Areas where the fishing tracker is allowed to render. */
    private val FISHING_AREAS: Set<String> =
        setOf(
            "Crimson Isle",
            "Hub",
            "Spider's Den",
            "Backwater Bayou",
            "The Park",
            "The Farming Islands",
            "Crystal Hollows",
            "Dwarven Mines",
            "Galatea",
            "Jerry's Workshop",
            "Lotus Atoll",
        )

    @Volatile private var ticksSinceScan = 0

    @Volatile private var hasRod: Boolean = false

    @Volatile private var lastLiquidAt: Long = 0L

    @Volatile private var nearLiquidNow: Boolean = false

    /**
     * Has the local player's own fishing bobber been observed in water or lava at any point
     * since the client started? Sticky for the session: once true, stays true. The first
     * appearance of the Fishing Tracker HUD is gated on this so it doesn't show just from
     * standing near a pond on a fishing island without actually fishing.
     */
    @Volatile private var firstCastDetected: Boolean = false

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
        Events.subscribe(this)
    }

    /**
     * Reset the visibility gate on every area transition. Leaving a fishing area clears the
     * sticky [firstCastDetected] flag so the player has to actively cast again on the next
     * island — without this, the HUD would silently reappear the moment they walk back near
     * any pond, even if they're just passing through.
     *
     * Liquid-grace state and the cheap caches are zeroed too so the very-first tick after
     * the transition doesn't read stale "yes near pond" data from the previous area.
     */
    @HandleEvent
    @Suppress("UNUSED_PARAMETER")
    fun onAreaChanged(event: AreaChanged) {
        firstCastDetected = false
        lastLiquidAt = 0L
        nearLiquidNow = false
        hasRod = false
        ticksSinceScan = 0
    }

    /**
     * True when the fishing tracker should be shown. Cheap read from cached state — recompute
     * cost is paid by the tick handler, not the render thread.
     */
    val isVisible: Boolean
        get() {
            if (!firstCastDetected) return false
            if (!hasRod) return false
            val area = LocationApi.currentArea ?: return false
            if (area !in FISHING_AREAS) return false
            if (nearLiquidNow) return true
            return System.currentTimeMillis() - lastLiquidAt < LIQUID_SHOW_GRACE_MS
        }

    private fun tick(client: Minecraft) {
        // Bobber-in-liquid detection runs every tick (not throttled) — the bobber lifetime
        // can be sub-second when fish bites quickly, and we don't want to miss the cast.
        // The scan itself is O(n_entities) on the visible entity list, which is small.
        if (!firstCastDetected) detectOwnBobberCast(client)

        ticksSinceScan++
        if (ticksSinceScan < SCAN_INTERVAL_TICKS) return
        ticksSinceScan = 0

        val player = client.player
        if (player == null) {
            hasRod = false
            nearLiquidNow = false
            return
        }

        // Cheap inventory scan — early-exit on first matching rod. Includes the hotbar
        // since `containerSize` covers both regions.
        val inventory = player.inventory
        var rod = false
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            val id = SkyblockItemUtils.getSkyblockId(stack) ?: continue
            if (id in FISHING_ROD_IDS) {
                rod = true
                break
            }
        }
        hasRod = rod

        // Skip the (more expensive) liquid scan when the cheap gates are already failing —
        // the result wouldn't change visibility anyway.
        if (!rod) {
            nearLiquidNow = false
            return
        }
        val area = LocationApi.currentArea
        if (area == null || area !in FISHING_AREAS) {
            nearLiquidNow = false
            return
        }

        val near = scanForLiquid(client, player)
        nearLiquidNow = near
        if (near) lastLiquidAt = System.currentTimeMillis()
    }

    /**
     * Walk the visible-entity list for a [FishingHook] owned by the local player that's
     * currently in water or lava. Mirrors the bobber-in-liquid check in [FishingTimer] —
     * keeping the logic local here so the visibility gate doesn't depend on the timer's
     * internal state. Sticky: flips [firstCastDetected] true on the first hit and never
     * re-evaluates after that.
     */
    private fun detectOwnBobberCast(client: Minecraft) {
        val player = client.player ?: return
        val world = client.level ?: return
        val cast =
            world.entitiesForRendering()
                .filterIsInstance<FishingHook>()
                .any { it.playerOwner === player && (it.isInWater || it.isInLava) }
        if (cast) firstCastDetected = true
    }

    /**
     * Sphere scan around the player out to [LIQUID_RADIUS_BLOCKS] for any water or lava
     * block. Early-exits on first match. Walks unique world positions in widening radius so
     * a typical pond-side scan resolves in tens of lookups.
     */
    private fun scanForLiquid(
        client: Minecraft,
        player: Player,
    ): Boolean {
        val world = client.level ?: return false
        val centerX = player.blockX
        val centerY = player.blockY
        val centerZ = player.blockZ
        val pos = BlockPos.MutableBlockPos()
        for (dx in -LIQUID_RADIUS_BLOCKS..LIQUID_RADIUS_BLOCKS) {
            for (dz in -LIQUID_RADIUS_BLOCKS..LIQUID_RADIUS_BLOCKS) {
                val xzSq = dx * dx + dz * dz
                if (xzSq > LIQUID_RADIUS_SQ) continue
                for (dy in -LIQUID_RADIUS_BLOCKS..LIQUID_RADIUS_BLOCKS) {
                    if (xzSq + dy * dy > LIQUID_RADIUS_SQ) continue
                    pos.set(centerX + dx, centerY + dy, centerZ + dz)
                    val state = world.getBlockState(pos)
                    val fluid = state.fluidState
                    if (!fluid.isEmpty) return true
                }
            }
        }
        return false
    }
}
