package com.soulreturns.data.hypixel

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/*
 * Hypixel replaced the wardrobe with Loadouts: `members.<uuid>.loadout` holds the armor sets (`armor`), the
 * equipment sets (`equipment`) and the named loadouts that pick one of each (`loadouts`). Confirmed against live
 * data of three migrated profiles:
 *
 * - `armor` / `equipment` are objects keyed by the set id as a string (sparse, unsorted) plus the non-set key
 *   `equipped_set` (the worn set's id). A set is `{id, HELMET?, CHESTPLATE?, LEGGINGS?, BOOTS?}` (equipment:
 *   `EQUIPMENT_SLOT_1..4?`); each piece is `{type: 0, data: <base64 gzipped NBT with ONE item>}`, a missing key
 *   is an empty slot and a set can be just `{id}`.
 * - **The worn set's own entry is empty** (just `{id}`): its pieces are on the body, in `inv_armor` and
 *   `equipment_contents`. No item sits in two sets, and none of the sets' items is also one of those worn ones
 *   (checked by `ExtraAttributes.uuid`: 74 + 49 + 33 armor pieces and 35 + 17 equipment pieces, no repeat).
 * - `inventory.wardrobe_equipped_slot` is the *selected loadout's* id, not a set id (13 = "Loadout 13", whose
 *   `armor_set_id` is the worn set 9). It can differ from the worn sets (a loadout with no sets selected, the
 *   sets then swapped by hand), so the outline follows `equipped_set`. A member whose sets were migrated without
 *   `equipped_set` or `loadouts` still carries the old wardrobe meaning of `wardrobe_equipped_slot` (the worn set).
 * - Members who have not logged in since the change may still have `inventory.wardrobe_contents` and no `loadout`.
 *
 * Tolerant like every reader here: unknown keys are ignored, ids are read as numbers, non-object entries skipped.
 */

/** One armor or equipment set: [id] counts from 1; [pieces] holds the four slots in row order, null = empty. */
class LoadoutSet(val id: Int, val pieces: List<String?>)

/**
 * `loadout.armor` or `loadout.equipment`: [sets] in ascending id order, [equippedId] the worn set's id.
 * [blobs] lists the present pieces' blobs in (set, row) order, the order the decoder returns them in.
 */
class LoadoutSets(val sets: List<LoadoutSet>, val equippedId: Int?) {
    val highestId: Int get() = sets.maxOfOrNull { it.id } ?: 0

    val blobs: List<String> by lazy { sets.flatMap { set -> set.pieces.filterNotNull() } }
}

/** One named loadout: [armorSetId] and [equipmentSetId] are the sets it uses, when it has any. */
class Loadout(val id: Int, val name: String, val armorSetId: Int?, val equipmentSetId: Int?)

private val ARMOR_KEYS = GearText.ARMOR_PIECES.map { it.uppercase() }
private val EQUIPMENT_KEYS = List(4) { "EQUIPMENT_SLOT_${it + 1}" }

private fun JsonObject.positiveInt(key: String): Int? {
    val v = prim(key)?.contentOrNull?.toDoubleOrNull() ?: return null
    return v.toInt().takeIf { it >= 1 && it.toDouble() == v }
}

private fun SkyblockProfileMember.loadoutSets(key: String, pieceKeys: List<String>): LoadoutSets? {
    val node = raw.path("loadout", key) ?: return null
    val sets = ArrayList<LoadoutSet>()
    for ((setKey, value) in node) {
        val id = setKey.toIntOrNull()?.takeIf { it >= 1 } ?: continue
        val set = value as? JsonObject ?: continue
        sets += LoadoutSet(id, pieceKeys.map { piece -> set.obj(piece)?.string("data")?.takeIf { it.isNotBlank() } })
    }
    sets.sortBy { it.id }
    return LoadoutSets(sets.distinctBy { it.id }, node.positiveInt("equipped_set"))
}

/** `loadout.armor`; null when the member has none (not migrated, or the API shape is gone). */
val SkyblockProfileMember.armorSets: LoadoutSets? get() = loadoutSets("armor", ARMOR_KEYS)

/** `loadout.equipment`; null when the member has none (a profile can have armor sets only). */
val SkyblockProfileMember.equipmentSets: LoadoutSets? get() = loadoutSets("equipment", EQUIPMENT_KEYS)

/** `loadout.loadouts` in id order; entries without a numeric id are skipped, a missing name reads "Loadout <id>". */
val SkyblockProfileMember.loadouts: List<Loadout>
    get() {
        val node = raw.path("loadout", "loadouts") ?: return emptyList()
        return node.entries.mapNotNull { (key, value) ->
            val o = value as? JsonObject ?: return@mapNotNull null
            val id = key.toIntOrNull()?.takeIf { it >= 1 } ?: o.positiveInt("id") ?: return@mapNotNull null
            Loadout(id, o.string("name")?.takeIf { it.isNotBlank() } ?: "Loadout $id", o.positiveInt("armor_set_id"), o.positiveInt("equipment_set_id"))
        }.sortedBy { it.id }
    }

/** True when `loadout.loadouts` exists, which makes `inventory.wardrobe_equipped_slot` a loadout id. */
val SkyblockProfileMember.hasLoadouts: Boolean get() = raw.path("loadout", "loadouts") != null
