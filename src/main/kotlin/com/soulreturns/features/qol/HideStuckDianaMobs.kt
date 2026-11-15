package com.soulreturns.features.qol

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.util.MessageDetector
import kotlin.math.abs
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.RemotePlayer
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.phys.AABB

/**
 * Hides "stuck" skinned Diana mobs — the frozen statues that pile up in the Hub when someone
 * nearby uses a re-modelling griffin pet skin (Four Seasons).
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
 *   real player who happens to be *named* e.g. "Harpy" can never match. Once a fake has been seen
 *   at mob-tier health it stays classified as a mob even after its health drops, so a stuck statue
 *   playing its **death animation** (health flopping toward 0) keeps hiding instead of un-hiding
 *   the corpse;
 * - no **nameplate vouches for it**: every live named mob — native (another player's boss you
 *   warped to) or griffin-skin re-model — keeps an invisible nameplate [ArmorStand]
 *   (`[Lv1250] ✿✰❃ Empyrean Minos Inquisitor 25.9M/80M❤ ✯`) riding exactly above its head, and
 *   armor stands despawn at mob tracking range, so a stuck statue has lost its nameplate. A
 *   stand vouches when its stripped name *contains* the mob name (strength prefixes like
 *   `Empyrean` make exact-match wrong), it sits within [NAMEPLATE_XZ_TOLERANCE] horizontally
 *   and 0–[NAMEPLATE_MAX_Y_OFFSET] above the fake's feet, AND the fake is the horizontally
 *   **nearest** same-named candidate under that stand — so a fresh mob dug up on top of a
 *   statue claims its own nameplate and the statue stays hidden. (An earlier heuristic — "no
 *   living backing `Mob` nearby" — false-positived on native bosses, which have no backing mob,
 *   and un-hid statues whenever any stray mob walked past.)
 * A short spawn grace ([GRACE_TICKS]) avoids flicker while a freshly-tracked fake waits for its
 * nameplate's spawn packet; graced fakes still *compete* for nameplates so a just-dug mob
 * outbids an adjacent statue immediately.
 *
 * Hidden entities are culled from rendering (`EntityRenderDispatcherMixin`), from the
 * crosshair/projectile pick (`PlayerMixin.isPickable`) so clicks pass through the statue to the
 * live mob behind it, and from sprint particles (`EntityMixin.canSpawnSprintParticle`) — the
 * statue's sprinting flag stays set, so `Entity.baseTick` would keep spawning block crumbs at
 * its feet every tick even while render-culled. Server-side these entities are already dead — hiding a corpse can't be
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
     * Every Mythological Ritual burrow mob (matches the backend catalog the legacy mod ships).
     * "Siamese Lynx" singular is speculative — the pair may spawn as two singular-named fakes.
     */
    private val DIANA_MOB_NAMES = setOf(
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

    /**
     * Ids of name-matched fakes seen at mob-tier health at least once (pruned each tick to those
     * still present as fakes). A fake keeps its "known" status even after its synced health drops —
     * a stuck statue playing its **death animation** flops toward 0 HP, which would otherwise fail
     * the [MIN_MOB_HEALTH] filter and un-hide the corpse. Real players never reach mob-tier health,
     * so they never enter this set: the real-player guard is preserved.
     */
    private var knownFakeIds: HashSet<Int> = HashSet()

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { mc -> tick(mc) }
    }

    /** Per-frame check used by the mixins — just a set lookup. */
    @JvmStatic
    fun isHidden(entity: Entity): Boolean = hiddenIds.isNotEmpty() && entity.id in hiddenIds

    private fun tick(mc: Minecraft) {
        val level = mc.level
        if (level == null || !cfg.general.hideStuckDianaMobs ||
            !LocationApi.inSkyblock || LocationApi.mode != "hub"
        ) {
            if (hiddenIds.isNotEmpty()) hiddenIds = emptySet()
            if (knownFakeIds.isNotEmpty()) knownFakeIds = HashSet()
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
        val known = HashSet<Int>()
        for (fake in fakes) {
            // A fake qualifies as a mob if it's at mob-tier health now, or was on a previous tick
            // (a dying statue flopping toward 0 HP stays known). Real players never qualify.
            val isMob = fake.entity.health >= MIN_MOB_HEALTH || fake.entity.id in knownFakeIds
            if (isMob) known.add(fake.entity.id)
            if (fake.entity.tickCount < GRACE_TICKS) continue
            if (!isMob) continue
            if (!isVouchedByOwnNameplate(level, fake, fakes)) hidden.add(fake.entity.id)
        }
        hiddenIds = hidden
        knownFakeIds = known
    }

    /** The matched catalog name, or null when the fake carries no Diana mob name. */
    private fun dianaMobName(player: RemotePlayer): String? {
        val profile = MessageDetector.stripColorCodes(player.gameProfile.name).trim()
        if (profile in DIANA_MOB_NAMES) return profile
        val display = MessageDetector.stripColorCodes(player.displayName.string).trim()
        return display.takeIf { it in DIANA_MOB_NAMES }
    }

    private fun isVouchedByOwnNameplate(level: ClientLevel, fake: Fake, all: List<Fake>): Boolean {
        val e = fake.entity
        val box = AABB(
            e.x - NAMEPLATE_XZ_TOLERANCE, e.y, e.z - NAMEPLATE_XZ_TOLERANCE,
            e.x + NAMEPLATE_XZ_TOLERANCE, e.y + NAMEPLATE_MAX_Y_OFFSET, e.z + NAMEPLATE_XZ_TOLERANCE,
        )
        val stands = level.getEntitiesOfClass(ArmorStand::class.java, box) { stand ->
            ridesAbove(stand, e) && standNameContains(stand, fake.name)
        }
        return stands.any { stand ->
            val nearest = all.asSequence()
                .filter { it.name == fake.name && ridesAbove(stand, it.entity) }
                .minByOrNull { horizontalDistSq(stand, it.entity) }
            nearest?.entity === e
        }
    }

    private fun standNameContains(stand: ArmorStand, mobName: String): Boolean =
        stand.customName?.string?.let { MessageDetector.stripColorCodes(it).contains(mobName) } == true

    private fun ridesAbove(stand: ArmorStand, fake: RemotePlayer): Boolean =
        abs(stand.x - fake.x) <= NAMEPLATE_XZ_TOLERANCE &&
            abs(stand.z - fake.z) <= NAMEPLATE_XZ_TOLERANCE &&
            stand.y - fake.y in 0.0..NAMEPLATE_MAX_Y_OFFSET

    private fun horizontalDistSq(stand: ArmorStand, fake: RemotePlayer): Double {
        val dx = stand.x - fake.x
        val dz = stand.z - fake.z
        return dx * dx + dz * dz
    }
}
