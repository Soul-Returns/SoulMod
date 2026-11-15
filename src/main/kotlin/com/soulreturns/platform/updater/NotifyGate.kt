package com.soulreturns.platform.updater

/**
 * When the updater may announce something unasked (chat line + toast). Pure, so the rule is testable.
 *
 * Only once the player has joined a world (the caller also requires a player and runs on a client
 * tick, so chat and HUD exist), and never in a dungeon (boss room included) or a Kuudra run. Other
 * boss fights (slayers, dragons) have no reliable signal yet. The config menu shows the state regardless.
 */
object NotifyGate {
    fun ready(joined: Boolean, inDungeon: Boolean, inKuudra: Boolean): Boolean =
        joined && !inDungeon && !inKuudra
}
