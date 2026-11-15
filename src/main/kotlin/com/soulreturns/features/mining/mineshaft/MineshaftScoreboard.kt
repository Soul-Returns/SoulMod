package com.soulreturns.features.mining.mineshaft

import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.util.MessageDetector
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/**
 * Reads the Glacite Mineshaft identifier off the **top** sidebar line, e.g.
 * `§705/12/26 §8m6D§v§8M ONYX_1` → `("ONYX", "ONYX_1")`. After colour codes are stripped the format
 * is `<date> <server> <TYPE>_<instance>`: the last whitespace-separated token is the identifier and
 * everything before its first `_` is the type.
 *
 * ### The sublocation guard is mandatory
 * A "last token of the top sidebar line" heuristic matches *every* sidebar. Without
 * `subLocation == "Glacite Mineshafts"` it happily scrapes garbage out of the hub, the private
 * island or the end and reports it as a mineshaft type — which then reaches the backend, where the
 * type set is deliberately open and so nothing downstream can reject it either.
 *
 * ### The type set is open-ended, forever
 * Hypixel adds mineshaft types without warning (Aquamarine, Peridot, Tungsten, Umber … were all
 * additions). Nothing here or on the backend validates against a known list — see the wire contract:
 * reject on shape, never on identity.
 *
 * The line is rebuilt as team prefix + owner name + team suffix, the way vanilla renders it. Hypixel
 * keeps the visible text in the prefix/suffix and uses the score holder's name as an invisible
 * per-line key, so reading `ownerName()` alone loses most of the line.
 */
object MineshaftScoreboard {
    /** Returns `(type, fullIdentifier)`, e.g. `("ONYX", "ONYX_1")`, or null if not parsable now. */
    fun readIdentifier(): Pair<String, String>? {
        if (!SkyblockLocation.isInSubLocation(MineshaftState.SUBLOCATION)) return null

        val scoreboard = Minecraft.getInstance().player?.connection?.scoreboard() ?: return null
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR) ?: return null
        val top = scoreboard.listPlayerScores(objective).maxByOrNull { it.value() } ?: return null

        val team = scoreboard.getPlayersTeam(top.owner())
        val rendered =
            if (team == null) {
                top.ownerName().string
            } else {
                PlayerTeam.formatNameForTeam(team, top.ownerName()).string
            }

        val lastToken = MessageDetector.stripColorCodes(rendered).trim()
            .split(WHITESPACE).lastOrNull() ?: return null
        val underscore = lastToken.indexOf('_')
        // Reject `_FOO` and `FOO_`: both halves must exist or this is not an identifier at all.
        if (underscore <= 0 || underscore == lastToken.length - 1) return null

        return lastToken.substring(0, underscore).uppercase() to lastToken
    }

    private val WHITESPACE = Regex("\\s+")
}
