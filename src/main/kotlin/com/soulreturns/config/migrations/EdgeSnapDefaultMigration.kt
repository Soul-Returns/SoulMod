package com.soulreturns.config.migrations

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/**
 * Version 10: the Move GUI's edge snap default goes from 1 px to 5 px. Soul has always written
 * defaults to the file, so a player who never touched the setting stores exactly 1; that value
 * becomes 5. Any other stored distance is the player's choice and stays.
 */
internal object EdgeSnapDefaultMigration {
    fun migrate(root: Map<String, JsonElement>): Map<String, JsonElement> {
        val general = root["general"] as? JsonObject ?: return root
        val moveGui = general["moveGui"] as? JsonObject ?: return root
        val snap = (moveGui["edgeSnap"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull ?: return root
        if (snap != 1.0) return root
        val fresh = JsonObject(moveGui + ("edgeSnap" to JsonPrimitive(5f)))
        return root + ("general" to JsonObject(general + ("moveGui" to fresh)))
    }
}
