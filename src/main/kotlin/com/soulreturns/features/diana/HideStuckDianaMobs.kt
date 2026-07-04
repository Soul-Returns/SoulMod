package com.soulreturns.features.diana

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.Mob

/**
 * Hides "stuck" skinned Diana mobs — the frozen statues that pile up in the Hub when someone
 * nearby uses a re-modelling griffin pet skin (Four Seasons). Backported from the new mod.
 *
 * **How the skin works (and breaks).** The skin replaces a Mythological Ritual mob's look by
 * spawning a fake *player* entity carrying the mob's name, position-synced to the real (now
 * invisible) mob. Player entities are tracked much farther than monsters, so when the backing mob
 * dies/despawns outside your tracking range, Hypixel never removes the fake player from your
 * client — it stays behind as an immortal frozen statue until a lobby swap.
 *
 * **Detection (recomputed each client tick).** A fake player is hidden when ALL hold:
 * - we're in the SkyBlock Hub (Diana mobs spawn nowhere else);
 * - it's a [RemotePlayer] whose profile/display name is a Mythological Ritual mob name
 *   (the fakes carry the real mob name, e.g. `"Minotaur "` with a trailing space);
 * - its synced health is in SkyBlock-mob territory (millions — real players are ≤ ~40), so a
 *   real player who happens to be *named* e.g. "Harpy" can never match;
 * - it's **orphaned**: no living [Mob] within [BACKING_MOB_RADIUS] blocks. A live skinned mob
 *   always has its invisible backing mob (zombie/etc.) colocated; a stuck one stands alone.
 * A short spawn grace ([GRACE_TICKS]) avoids flicker while a freshly-tracked fake waits for its
 * backing mob's spawn packet.
 *
 * Hidden entities are culled from rendering (`EntityRenderDispatcherMixin`) and from the
 * crosshair/projectile pick (`LivingEntityMixin.isPickable`) so clicks pass through the statue
 * to the live mob behind it. Server-side these entities are already dead — hiding a corpse can't
 * be abused (nothing to act on), mirroring hide-foliage's anticheat-safe stance.
 */
object HideStuckDianaMobs {
    /** Ticks a fake player must exist before it may be hidden (waits out spawn-packet ordering). */
    private const val GRACE_TICKS = 40

    /** A live skinned mob's invisible backing mob rides its fake player within ~1 block; 3.5 is generous. */
    private const val BACKING_MOB_RADIUS = 3.5

    /** Real players sync ≤ ~40 health; SkyBlock mobs sync their true HP (tens of thousands+). */
    private const val MIN_MOB_HEALTH = 1000f

    /**
     * Every Mythological Ritual burrow mob. Hardcoded (mirroring [MythologicalMobCatalog]'s
     * backend list) rather than read from the catalog client so the feature works on a cold
     * offline launch. "Siamese Lynx" singular is speculative — the pair may spawn as two
     * singular-named fakes.
     */
    private val DIANA_MOB_NAMES =
        setOf(
            "Minos Hunter",
            "Siamese Lynxes",
            "Siamese Lynx",
            "Stranded Nymph",
            "Cretan Bull",
            "Harpy",
            "Gaia Construct",
            "Minotaur",
            "Minos Champion",
            "Sphinx",
            "Minos Inquisitor",
            "Manticore",
            "King Minos",
        )

    private var hiddenIds: Set<Int> = emptySet()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { client -> tick(client) }
        )
    }

    /** Per-frame check used by the mixins — just a set lookup. */
    @JvmStatic
    fun isHidden(entity: Entity): Boolean = hiddenIds.isNotEmpty() && entity.id in hiddenIds

    private fun tick(mc: Minecraft) {
        val level = mc.level
        if (level == null || !cfg.combat.diana.hideStuckDianaMobs() ||
            // currentArea is null off SkyBlock, so this also implicitly gates on being in-game.
            !LocationApi.isInArea("Hub")
        ) {
            if (hiddenIds.isNotEmpty()) hiddenIds = emptySet()
            return
        }
        val hidden = HashSet<Int>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is RemotePlayer) continue
            if (entity.tickCount < GRACE_TICKS) continue
            if (entity.health < MIN_MOB_HEALTH) continue
            if (!hasDianaMobName(entity)) continue
            val backing = level.getEntitiesOfClass(Mob::class.java, entity.boundingBox.inflate(BACKING_MOB_RADIUS)) { it.isAlive }
            if (backing.isEmpty()) hidden.add(entity.id)
        }
        hiddenIds = hidden
    }

    private fun hasDianaMobName(player: RemotePlayer): Boolean {
        if (MessageDetector.stripColorCodes(player.gameProfile.name).trim() in DIANA_MOB_NAMES) return true
        return MessageDetector.stripColorCodes(player.displayName.string).trim() in DIANA_MOB_NAMES
    }
}
