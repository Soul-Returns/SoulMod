package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull

/**
 * Version 11: the config menu rework (`mocks/config-menu/`) merges related toggles into one card
 * with its own master switch. Per card, the master becomes true when any of its parts was on (the
 * parts keep their values), so nothing a player had on turns off. With no part on, the master
 * stays false and the parts take their new defaults, so the card switch alone does what it says.
 *
 * Existing files also mark the first-launch question as answered: a player with a config is not
 * on their first launch.
 *
 * The part lists and new defaults are a snapshot of version 11, never read from live code.
 */
internal object MergedCardsMigration {
    private class Card(val section: List<String>, val master: String, val parts: Map<String, Boolean>)

    private val cards =
        listOf(
            Card(listOf("general"), "cleanUpInventory", mapOf("hideRecipeBook" to true, "hideOffhandSlot" to true, "hideCraftingGrid" to true)),
            Card(listOf("general"), "hideEffects", mapOf("hideEffectsHud" to true, "hideEffectsInventory" to true)),
            Card(
                listOf("general"),
                "quietImplosion",
                mapOf("hideImplosionParticles" to true, "hideImplosionExplosionSound" to true, "hideImplosionTeleportSound" to true),
            ),
            Card(
                listOf("mining", "nucleus"),
                "compactChat",
                mapOf("compactFound" to true, "compactLoot" to true, "hideAlreadyObtained" to false, "hideBlocksInWay" to false),
            ),
        )

    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        var tree = root
        for (card in cards) tree = update(tree, card.section) { obj -> apply(obj, card) }
        val menu = tree["menu"] as? JsonObject ?: JsonObject(emptyMap())
        return tree + ("menu" to JsonObject(menu + ("onboardingDone" to JsonPrimitive(true))))
    }

    private fun apply(obj: Map<String, JsonElement>, card: Card): Map<String, JsonElement> {
        if (obj.containsKey(card.master)) return obj
        val anyOn = card.parts.keys.any { (obj[it] as? JsonPrimitive)?.takeIf { p -> !p.isString }?.booleanOrNull == true }
        if (anyOn) return obj + (card.master to JsonPrimitive(true))
        return obj + (card.master to JsonPrimitive(false)) + card.parts.mapValues { JsonPrimitive(it.value) }
    }

    /** Rewrites the object at [path] (created when absent), leaving a non-object value alone. */
    private fun update(
        root: Map<String, JsonElement>,
        path: List<String>,
        block: (Map<String, JsonElement>) -> Map<String, JsonElement>,
    ): Map<String, JsonElement> {
        val key = path.first()
        val child = root[key] ?: JsonObject(emptyMap())
        if (child !is JsonObject) return root
        val next = if (path.size == 1) block(child) else update(child, path.drop(1), block)
        return root + (key to JsonObject(next))
    }
}
