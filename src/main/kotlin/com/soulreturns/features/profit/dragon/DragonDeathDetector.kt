package com.soulreturns.features.profit.dragon

import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * Detects dragon deaths from Hypixel's "DRAGON DOWN!" chat banner and triggers the
 * [DragonLootScanner] window. The banner line is rendered inside the `▬▬▬` box with
 * leading whitespace and color codes, e.g.:
 *
 * ```
 *                           STRONG DRAGON DOWN!
 * ```
 *
 * After [MessageDetector.stripColorCodes] + trim the line collapses to
 * `"<TYPE> DRAGON DOWN!"` where `<TYPE>` is one of PROTECTOR / OLD / WISE / UNSTABLE /
 * YOUNG / STRONG / SUPERIOR.
 *
 * Triggering hands off to [DragonLootScanner], which re-centers each per-tick scan on
 * the player's **current** position rather than the banner-time position. That's
 * load-bearing: Hypixel only renders the loot armor-stands when the client player is
 * within ~20 blocks of them, so a tag-killer 40 blocks from the corpse needs to walk
 * over before the stands exist client-side. The scanner's 30-second window gives them
 * time to do so.
 */
object DragonDeathDetector {
    private val logger = SoulLogger("Soul/DragonProfit")

    /** `<TYPE> DRAGON DOWN!`, post-strip + trim. Anchored — the banner is the entire line. */
    private val DEATH_REGEX = Regex("^(PROTECTOR|OLD|WISE|UNSTABLE|YOUNG|STRONG|SUPERIOR) DRAGON DOWN!$")

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        val clean = MessageDetector.stripColorCodes(event.raw).trim()
        val match = DEATH_REGEX.matchEntire(clean) ?: return
        val typeName = match.groupValues[1]
        val type =
            DragonType.byName(typeName) ?: run {
                logger.warn("Unrecognised dragon type in death banner: $typeName")
                return
            }
        DragonLootScanner.beginScan(dragonType = type)
    }
}
