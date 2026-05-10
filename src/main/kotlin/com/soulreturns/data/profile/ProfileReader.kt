package com.soulreturns.data.profile

import com.soulreturns.util.MessageDetector
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Polls the tab list every client tick, parses the Hypixel SkyBlock `Profile:` entry, and
 * feeds [ProfileApi]. Publishes [com.soulreturns.data.model.ProfileChanged] only on transitions.
 *
 * Handles both the plain `Profile: <name>` form and the parenthesised co-op variant
 * (`Profile (Co-op): <name>`, `Profile (Stranded): <name>`, etc.).
 */
object ProfileReader {
    /**
     * Profile pattern: optional parenthesised qualifier after `Profile`, then `:`, then the name.
     * Names are alphanumeric (Hypixel uses fruit-themed defaults — `Banana`, `Apple`, etc., plus
     * user-renames which can include digits but never spaces).
     */
    private val PROFILE_PATTERN = Regex("Profile(?:\\s*\\([^)]+\\))?:\\s*([A-Za-z][A-Za-z0-9_]*)")

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register(
            ClientTickEvents.EndTick { _ ->
                ProfileApi.updateProfile(readProfile())
            }
        )
    }

    private fun readProfile(): String? {
        val conn = Minecraft.getInstance().player?.connection ?: return null
        for (info in conn.listedOnlinePlayers) {
            val displayName = info.tabListDisplayName?.string ?: continue
            // Strip color codes — Hypixel tab list prefixes use §a, §y, etc.
            val clean = MessageDetector.stripColorCodes(displayName).trim()
            val match = PROFILE_PATTERN.find(clean) ?: continue
            return match.groupValues[1]
        }
        return null
    }
}
