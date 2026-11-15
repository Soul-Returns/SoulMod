package com.soulreturns.features.dungeons

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import java.util.Base64
import java.util.WeakHashMap
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Entity
import net.minecraft.world.entity.EquipmentSlot
import net.minecraft.world.entity.decoration.ArmorStand
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.Items

/**
 * Stops drawing the Haunted Skulls of the Soulweaver Gloves (everyone's, not only yours). Purely
 * visual: the stands stay in the world, nothing is sent.
 *
 * **Signature** (recordings 2026-11-11/12 from the user's F7 runs, orbit numbers; texture from
 * Skyblocker, SkyHanni's `DUNGEONS_SOUL_WEAVER` and NoFrills, which all carry the same value):
 * an invisible, marker [ArmorStand] whose head is a player head with the skin
 * [TEXTURE_HASH]. In the recordings every orbiting skull was an invisible small marker stand
 * (client flags 17) circling its owner at radius 1.5 and 0.2 below the owner's feet, about 0.22
 * revolutions per second; the room decoration stands (heads, slabs, blocks) are not markers. The
 * texture is the key, the flags only a guard; a name is never used (`Player Head` is generic).
 *
 * The verdict is cached per stand and recomputed only when its head stack instance changes
 * (`ClientboundSetEquipmentPacket` replaces it), so a frame costs a few field reads.
 */
object HideSoulweaverSkulls {
    /** Hash in the skin URL (`textures.minecraft.net/texture/<hash>`) of the Haunted Skull head. */
    const val TEXTURE_HASH = "2f24ed6875304fa4a1f0c785b2cb6a6a72563e9f3e24ea55e18178452119aa66"

    private const val SKIN_PATH = "/texture/$TEXTURE_HASH"

    private class Verdict(val stack: ItemStack, val skull: Boolean)

    private val verdicts = WeakHashMap<ArmorStand, Verdict>()

    /** True for a `textures` property value (base64 of Mojang's textures JSON) naming the skull skin. */
    @JvmStatic
    fun matchesTexture(value: String?): Boolean {
        if (value.isNullOrEmpty()) return false
        val json = try {
            String(Base64.getDecoder().decode(value), Charsets.UTF_8)
        } catch (_: IllegalArgumentException) {
            return false
        }
        return json.contains(SKIN_PATH)
    }

    /** Render hook: should this entity be skipped this frame? Only reached for an armor stand while the option is on. */
    @JvmStatic
    fun isHidden(entity: Entity): Boolean {
        if (entity !is ArmorStand) return false
        if (!cfg.dungeons.hideSoulweaverSkulls) return false
        if (!entity.isInvisible || !entity.isMarker) return false
        val head = entity.getItemBySlot(EquipmentSlot.HEAD)
        if (!head.`is`(Items.PLAYER_HEAD)) return false
        if (!LocationApi.inDungeon) return false
        val cached = verdicts[entity]
        if (cached != null && cached.stack === head) return cached.skull
        val skull = hasSkullTexture(head)
        verdicts[entity] = Verdict(head, skull)
        return skull
    }

    private fun hasSkullTexture(head: ItemStack): Boolean {
        val profile = head.get(DataComponents.PROFILE) ?: return false
        return profile.partialProfile().properties().get("textures").any { matchesTexture(it.value()) }
    }
}
