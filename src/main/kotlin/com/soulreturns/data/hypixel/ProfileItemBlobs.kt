package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonObject

/*
 * Every item blob of a member (`inventory.*`: base64 gzipped 1.8 NBT), read once for all Profile Viewer
 * sections (Overview, Gear, Inventories, Accessories). Confirmed against a real dump: backpack keys are
 * zero-based (0..17), the Ender Chest blob is 405 slots (nine pages of 45), the vault 27, the bags 9..45,
 * `inv_armor` lists boots first and `inv_contents` the hotbar first. Absence is normal (an API toggle is
 * off) and means null; a blank blob counts as absent.
 */

internal fun SkyblockProfileMember.dataBlob(vararg keys: String): String? {
    var node: JsonObject = raw
    for (key in keys) node = node.obj(key) ?: return null
    return node.string("data")?.takeIf { it.isNotBlank() }
}

/** `inventory.inv_armor.data`: four slots, boots first. */
val SkyblockProfileMember.armorBlob: String? get() = dataBlob("inventory", "inv_armor")

/** `inventory.equipment_contents.data`: necklace, cloak, belt, gloves. */
val SkyblockProfileMember.equipmentBlob: String? get() = dataBlob("inventory", "equipment_contents")

/** `inventory.wardrobe_contents.data`: 36 slots per page, two pages. */
val SkyblockProfileMember.wardrobeBlob: String? get() = dataBlob("inventory", "wardrobe_contents")

/** `inventory.inv_contents.data`: hotbar first (slots 0..8), then the three inventory rows. */
val SkyblockProfileMember.inventoryBlob: String? get() = dataBlob("inventory", "inv_contents")

/** `inventory.ender_chest_contents.data`: every page in one blob, 45 slots per page. */
val SkyblockProfileMember.enderChestBlob: String? get() = dataBlob("inventory", "ender_chest_contents")

/** `inventory.personal_vault_contents.data`; absent when the Personal Vault API toggle is off. */
val SkyblockProfileMember.personalVaultBlob: String? get() = dataBlob("inventory", "personal_vault_contents")

val SkyblockProfileMember.potionBagBlob: String? get() = dataBlob("inventory", "bag_contents", "potion_bag")

val SkyblockProfileMember.fishingBagBlob: String? get() = dataBlob("inventory", "bag_contents", "fishing_bag")

val SkyblockProfileMember.quiverBlob: String? get() = dataBlob("inventory", "bag_contents", "quiver")

/** `inventory.bag_contents.talisman_bag.data`, the accessory bag. */
val SkyblockProfileMember.accessoryBagBlob: String? get() = dataBlob("inventory", "bag_contents", "talisman_bag")

/** True when `inventory.personal_vault_contents` came with a blob (a toggle separate from the Inventory API). */
val SkyblockProfileMember.hasPersonalVaultApi: Boolean get() = personalVaultBlob != null

/**
 * One backpack: [index] is the zero-based API key (`backpack_contents.<index>`), [slot] the in-game slot 1..18,
 * [data] its contents blob and [iconBlob] the backpack item itself (`backpack_icons.<index>`).
 */
class BackpackBlob(val index: Int, val data: String, val iconBlob: String? = null) {
    val slot: Int get() = index + 1
}

/** `inventory.backpack_contents.<n>` joined with `backpack_icons.<n>`, in slot order; a key that is not a number is skipped. */
val SkyblockProfileMember.backpackBlobs: List<BackpackBlob>
    get() {
        val contents = raw.path("inventory", "backpack_contents") ?: return emptyList()
        val icons = raw.path("inventory", "backpack_icons")
        return contents.keys.mapNotNull { key ->
            val index = key.toIntOrNull() ?: return@mapNotNull null
            val data = contents.obj(key)?.string("data")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            BackpackBlob(index, data, icons?.obj(key)?.string("data")?.takeIf { it.isNotBlank() })
        }.sortedBy { it.index }
    }
