package com.soulreturns.features.fishing

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.skyblock.AreaChanged
import com.soulreturns.util.SkyblockItemUtils
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.world.entity.projectile.FishingHook

/**
 * The gate that decides whether the fishing HUD is on screen at all.
 *
 * A tracker that shows up whenever you happen to walk past a pond is clutter, so visibility needs
 * **all four** of:
 *  1. a SkyBlock fishing rod somewhere in the inventory or hotbar,
 *  2. an area where fishing is a thing,
 *  3. water or lava within 15 blocks — with a 60 s grace after the last sighting, so being dragged
 *     away from the pond mid-fight does not blink the HUD out,
 *  4. **a first cast this area** — the player's own bobber observed in water or lava.
 *
 * Gate 4 is the one that matters. Without it the HUD appears from standing near any pond on the
 * right island, which is most of the Hub. It resets on every area change, so walking back to a pond
 * on a new island asks for a cast again rather than silently reappearing.
 *
 * ### The liquid scan is spread across the second, not spent in one tick
 * The legacy version walked the whole 15-block sphere — around 14,000 block lookups — inside a
 * single tick, once a second. The worst case is the common one: standing on a fishing island with
 * no water in range means no early exit and the full sphere every time. Rather than port that
 * unexamined, the offsets here are precomputed **sorted by distance** and walked a slice at a time
 * across the 20 ticks of the same one-second window. Observable behaviour is identical — same
 * sphere, same radius, same refresh rate — but the peak per-tick cost drops by a factor of 20, and
 * nearest-first ordering means a pond you are standing next to resolves in the first slice instead
 * of somewhere in the middle of an arbitrary iteration order.
 *
 * (Measured only by construction, not by a live profile: this lane cannot launch a client. The
 * bound is what makes that acceptable — the scan cannot spike a frame even in the worst case.)
 */
object FishingVisibility {
    private const val LIQUID_RADIUS = 15
    private const val LIQUID_RADIUS_SQ = LIQUID_RADIUS * LIQUID_RADIUS

    /** How long the HUD survives after the last liquid sighting. */
    private const val LIQUID_GRACE_MS = 60_000L

    /** Ticks the sphere walk is spread over — one full pass per second, like the legacy scan. */
    private const val SCAN_TICKS = 20

    /** SkyBlock ids for every rod that should turn the tracker on. */
    private val FISHING_ROD_IDS: Set<String> = setOf(
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

    /**
     * Sphere offsets packed as `(dx+15) shl 10 or (dy+15) shl 5 or (dz+15)`, sorted nearest-first.
     * ~14k ints, built once — cheap next to rebuilding the iteration order every scan.
     */
    private val SPHERE: IntArray by lazy {
        val offsets = ArrayList<Pair<Int, Int>>(14_000)
        for (dx in -LIQUID_RADIUS..LIQUID_RADIUS) {
            for (dy in -LIQUID_RADIUS..LIQUID_RADIUS) {
                for (dz in -LIQUID_RADIUS..LIQUID_RADIUS) {
                    val distSq = dx * dx + dy * dy + dz * dz
                    if (distSq > LIQUID_RADIUS_SQ) continue
                    offsets.add(distSq to (((dx + LIQUID_RADIUS) shl 10) or ((dy + LIQUID_RADIUS) shl 5) or (dz + LIQUID_RADIUS)))
                }
            }
        }
        offsets.sortBy { it.first }
        IntArray(offsets.size) { offsets[it].second }
    }

    /** Positions per tick, so one full sphere walk takes exactly [SCAN_TICKS]. */
    private val SLICE: Int by lazy { (SPHERE.size + SCAN_TICKS - 1) / SCAN_TICKS }

    @Volatile private var hasRod = false

    @Volatile private var nearLiquid = false

    @Volatile private var lastLiquidAt = 0L

    /** Sticky within an area: once the player has cast here, they do not have to prove it again. */
    @Volatile private var firstCastDetected = false

    /** Where in the second we are, where the sphere walk resumes, and what it has found so far. */
    private var passTick = 0
    private var scanCursor = 0
    private var foundThisPass = false

    /** True when the cheap gates already said no, so this pass skips the scan entirely. */
    private var passSkipped = false

    private val scratch = BlockPos.MutableBlockPos()

    private var registered = false

    fun register() {
        if (registered) return
        registered = true
        ClientTickEvents.END_CLIENT_TICK.register(ClientTickEvents.EndTick { client -> tick(client) })
        Events.subscribe(this)
    }

    /**
     * Cheap read off cached state — the render thread never pays for the scan.
     *
     * This answers "is the player in a fishing context", not "does the user want the HUD": the
     * config toggle is the HUD's own business.
     */
    val isVisible: Boolean
        get() {
            if (!firstCastDetected || !hasRod) return false
            return nearLiquid || System.currentTimeMillis() - lastLiquidAt < LIQUID_GRACE_MS
        }

    /** For `/soul dev fishing` — which of the three gates is the one currently saying no. */
    fun gateReason(): String {
        if (!hasRod) return "no SkyBlock fishing rod in inventory"
        if (!firstCastDetected) return "no cast detected in this area yet"
        if (!nearLiquid && System.currentTimeMillis() - lastLiquidAt >= LIQUID_GRACE_MS) return "no water or lava within $LIQUID_RADIUS blocks"
        return "visible"
    }

    /**
     * Every gate resets on an area change — including the sticky cast flag. Leaving a fishing island
     * and coming back should ask for a cast again; keeping the flag would make the HUD reappear from
     * merely walking past the pond, which is the behaviour gate 4 exists to prevent.
     */
    @HandleEvent
    @Suppress("UNUSED_PARAMETER")
    fun onAreaChanged(event: AreaChanged) {
        firstCastDetected = false
        hasRod = false
        nearLiquid = false
        lastLiquidAt = 0L
        passTick = 0
        scanCursor = 0
        foundThisPass = false
        passSkipped = false
    }

    private fun tick(client: Minecraft) {
        val player = client.player
        val level = client.level
        if (player == null || level == null) {
            hasRod = false
            nearLiquid = false
            return
        }

        // Every tick, not throttled: a bobber's life can be well under a second when a fish bites
        // fast, and missing the cast means the HUD never appears.
        if (!firstCastDetected) {
            val cast = level.entitiesForRendering()
                .filterIsInstance<FishingHook>()
                .any { it.playerOwner === player && (it.isInWater || it.isInLava) }
            if (cast) firstCastDetected = true
        }

        // One pass per second, a slice of the sphere per tick. The rod and area checks ride the
        // start of the pass so they refresh at the same cadence the legacy scan used — and so a
        // player with no rod does not pay for an inventory walk every tick.
        if (passTick == 0) {
            scanCursor = 0
            foundThisPass = false
            hasRod = hasFishingRod(client)
            // No rod means the scan cannot change visibility. Skip the whole pass rather than paying
            // for an answer nobody will read.
            passSkipped = !hasRod
            if (passSkipped) nearLiquid = false
        }

        if (!passSkipped) {
            if (!foundThisPass) foundThisPass = scanSlice(client)
            scanCursor += SLICE
        }

        if (++passTick >= SCAN_TICKS) {
            passTick = 0
            if (!passSkipped) {
                nearLiquid = foundThisPass
                if (foundThisPass) lastLiquidAt = System.currentTimeMillis()
            }
        }
    }

    private fun hasFishingRod(client: Minecraft): Boolean {
        val inventory = client.player?.inventory ?: return false
        // containerSize covers the hotbar too, so this is one pass over both.
        for (slot in 0 until inventory.containerSize) {
            val stack = inventory.getItem(slot)
            if (stack.isEmpty) continue
            if (SkyblockItemUtils.getSkyblockId(stack) in FISHING_ROD_IDS) return true
        }
        return false
    }

    /** @return true if this slice found water or lava. Early-exits on the first hit. */
    private fun scanSlice(client: Minecraft): Boolean {
        val player = client.player ?: return false
        val level = client.level ?: return false
        val cx = player.blockX
        val cy = player.blockY
        val cz = player.blockZ
        val end = minOf(scanCursor + SLICE, SPHERE.size)
        for (i in scanCursor until end) {
            val packed = SPHERE[i]
            scratch.set(
                cx + ((packed shr 10) and 0x1F) - LIQUID_RADIUS,
                cy + ((packed shr 5) and 0x1F) - LIQUID_RADIUS,
                cz + (packed and 0x1F) - LIQUID_RADIUS,
            )
            if (!level.getBlockState(scratch).fluidState.isEmpty) return true
        }
        return false
    }
}
