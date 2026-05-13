package com.soulreturns.features.mining.mineshaft

import com.soulreturns.data.location.LocationApi
import com.soulreturns.util.MessageDetector
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot

/**
 * Reads the Glacite Mineshaft identifier from the top scoreboard sidebar line, e.g.
 * `§705/12/26 §8m6D§v§8M ONYX_1` → `("ONYX", "ONYX_1")`. The format after stripping color
 * codes is `<date> <server> <TYPE>_<instance>` — the last whitespace-separated token is the
 * mineshaft identifier, the prefix before `_` is the type.
 *
 * **Sublocation guard:** only returns non-null when `LocationApi.currentSublocation` is
 * `Glacite Mineshafts`. Without that check, the last-token parse would happily extract
 * garbage from sidebars in unrelated areas (hub, private island, end, etc.).
 *
 * Treat the type set as open-ended — Hypixel may add new ones (Aquamarine, Peridot, …).
 */
object MineshaftScoreboard {
    private const val REQUIRED_SUBLOCATION = "Glacite Mineshafts"

    /** Returns `(type, fullIdentifier)` e.g. `("ONYX", "ONYX_1")`, or null if not parsable now. */
    fun readIdentifier(): Pair<String, String>? {
        if (LocationApi.currentSublocation != REQUIRED_SUBLOCATION) return null
        val mc = Minecraft.getInstance()
        val level = mc.level ?: return null
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null

        val top = scoreboard.listPlayerScores(objective).maxByOrNull { it.value() } ?: return null
        val team = scoreboard.getPlayersTeam(top.owner())
        val rendered =
            if (team == null) {
                top.ownerName().string
            } else {
                team.getPlayerPrefix().copy().append(top.ownerName()).append(team.getPlayerSuffix()).string
            }
        val stripped = MessageDetector.stripColorCodes(rendered).trim()
        val lastToken = stripped.split(Regex("\\s+")).lastOrNull() ?: return null
        val underscore = lastToken.indexOf('_')
        if (underscore <= 0 || underscore == lastToken.length - 1) return null
        val type = lastToken.substring(0, underscore).uppercase()
        return type to lastToken
    }
}
