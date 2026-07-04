package com.soulreturns.features.diana

import com.soulreturns.config.cfg
import com.soulreturns.data.location.LocationApi
import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB
import kotlin.math.abs

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
 * - it is NOT vouched for by **its own** nameplate [ArmorStand] — the invisible stand carrying
 *   `[Lv1250] ✿✰❃ Empyrean Minos Inquisitor 25.9M/80M❤ ✯` that every live named mob keeps
 *   floating over its head (see the named-mob entity model in CLAUDE.md). Armor stands despawn
 *   at mob tracking range, so a stuck statue's nameplate is gone along with its backing mob.
 *
 * **Nameplate pairing.** The stand rides its mob's x/z exactly (observed client-interpolation
 * lag ≤ 0.02 blocks), so "own nameplate" means: horizontal offset ≤ [NAMEPLATE_XZ_TOLERANCE],
 * floating 0..[NAMEPLATE_MAX_Y_OFFSET] above the fake's feet, custom name contains the mob name.
 * When two same-named fakes are both under one stand (a fresh mob dug up right where a statue
 * is), the stand vouches ONLY for the horizontally nearest — the new mob's nameplate can't
 * resurrect the statue at the dig spot. The pairing competition includes fakes still in spawn
 * grace / without synced health yet, so a freshly dug mob claims its nameplate from tick one.
 *
 * There is deliberately no "living Mob nearby" fallback signal (an earlier revision had one for
 * griffin-skin re-models, whose invisible backing mob rides the fake): any unrelated mob — a
 * player's pet wolf — walking within a few blocks would resurrect a statue, and re-modeled mobs
 * carry nameplates like every other named mob, so the pairing check covers them already.
 *
 * A short spawn grace ([GRACE_TICKS]) avoids flicker while a freshly-tracked fake waits for its
 * nameplate's spawn packet.
 *
 * Hidden entities are culled from rendering (`EntityRenderDispatcherMixin`), from the
 * crosshair/projectile pick (`LivingEntityMixin.isPickable`) so clicks pass through the statue
 * to the live mob behind it, and from sprint particles (`EntityMixin.canSpawnSprintParticle`) —
 * the frozen fake keeps its sprinting flag set, so it would otherwise emit block crumbs at its
 * feet every tick. Server-side these entities are already dead — hiding a corpse can't be
 * abused (nothing to act on), mirroring hide-foliage's anticheat-safe stance.
 */
object HideStuckDianaMobs {
    /** Ticks a fake player must exist before it may be hidden (waits out spawn-packet ordering). */
    private const val GRACE_TICKS = 40

    /** Nameplate stands ride their mob's x/z exactly (observed lag ≤ 0.02); 1.0 is generous. */
    private const val NAMEPLATE_XZ_TOLERANCE = 1.0

    /** Observed nameplate offsets above the fake's feet: +1.0 (wolf) to +2.15 (Inquisitor). */
    private const val NAMEPLATE_MAX_Y_OFFSET = 3.5

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

    private data class Fake(val entity: RemotePlayer, val name: String)

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
        // Every name-matched fake joins the pairing competition — no grace / health filter here,
        // so a just-spawned mob outcompetes an adjacent statue for its nameplate immediately.
        val fakes = ArrayList<Fake>()
        for (entity in level.entitiesForRendering()) {
            if (entity !is RemotePlayer) continue
            val name = dianaMobName(entity) ?: continue
            fakes.add(Fake(entity, name))
        }
        val hidden = HashSet<Int>()
        for (fake in fakes) {
            if (fake.entity.tickCount < GRACE_TICKS) continue
            if (fake.entity.health < MIN_MOB_HEALTH) continue
            if (!isVouchedByOwnNameplate(level, fake, fakes)) hidden.add(fake.entity.id)
        }
        hiddenIds = hidden
    }

    /** The matched catalog name, or null when the fake carries no Diana mob name. */
    private fun dianaMobName(player: RemotePlayer): String? {
        val profile = MessageDetector.stripColorCodes(player.gameProfile.name).trim()
        if (profile in DIANA_MOB_NAMES) return profile
        val display = MessageDetector.stripColorCodes(player.displayName.string).trim()
        return display.takeIf { it in DIANA_MOB_NAMES }
    }

    private fun isVouchedByOwnNameplate(
        level: ClientLevel,
        fake: Fake,
        all: List<Fake>
    ): Boolean {
        val e = fake.entity
        val box =
            AABB(
                e.x - NAMEPLATE_XZ_TOLERANCE,
                e.y,
                e.z - NAMEPLATE_XZ_TOLERANCE,
                e.x + NAMEPLATE_XZ_TOLERANCE,
                e.y + NAMEPLATE_MAX_Y_OFFSET,
                e.z + NAMEPLATE_XZ_TOLERANCE,
            )
        val stands =
            level.getEntitiesOfClass(ArmorStand::class.java, box) { stand ->
                ridesAbove(stand, e) && standNameContains(stand, fake.name)
            }
        return stands.any { stand ->
            val nearest =
                all.asSequence()
                    .filter { it.name == fake.name && ridesAbove(stand, it.entity) }
                    .minByOrNull { horizontalDistSq(stand, it.entity) }
            nearest?.entity === e
        }
    }

    private fun standNameContains(
        stand: ArmorStand,
        mobName: String
    ): Boolean = stand.customName?.string?.let { MessageDetector.stripColorCodes(it).contains(mobName) } == true

    private fun ridesAbove(
        stand: ArmorStand,
        fake: RemotePlayer
    ): Boolean =
        abs(stand.x - fake.x) <= NAMEPLATE_XZ_TOLERANCE &&
            abs(stand.z - fake.z) <= NAMEPLATE_XZ_TOLERANCE &&
            stand.y - fake.y in 0.0..NAMEPLATE_MAX_Y_OFFSET

    private fun horizontalDistSq(
        stand: ArmorStand,
        fake: RemotePlayer
    ): Double {
        val dx = stand.x - fake.x
        val dz = stand.z - fake.z
        return dx * dx + dz * dz
    }
}
