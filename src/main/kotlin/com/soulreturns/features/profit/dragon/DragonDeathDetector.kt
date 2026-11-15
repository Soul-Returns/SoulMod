package com.soulreturns.features.profit.dragon

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Event
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.data.skyblock.SkyblockLocation
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.SoulLogger

/**
 * A dragon just died at the nest. Published by [DragonDeathDetector] on the banner line.
 *
 * @property tick the client tick the banner landed on — [DragonLegionAnnouncer] schedules its send
 *   relative to it, so a consumer never has to re-derive when "now" was.
 */
data class DragonDied(val type: DragonType, val tick: Int) : Event

/**
 * Turns Hypixel's `<TYPE> DRAGON DOWN!` banner into a [DragonDied] event.
 *
 * ### One parser, two consumers
 * [DragonLootScanner] opens its scan window on this, and [DragonLegionAnnouncer] broadcasts on it.
 * The legacy mod gave each of them its own copy of the regex with a comment saying the two were
 * "kept in lockstep manually" — two parsers for one chat line, waiting for the next time Hypixel
 * rewords it. Publishing an event keeps them independently toggleable (which was the real reason
 * they were separate objects) without the duplication.
 *
 * ### The sublocation gate is mandatory
 * The **same banner fires in Catacombs Master Mode F7**, which also spawns dragons. Without
 * `isInSubLocation("Dragon's Nest")` a dungeon run silently poisons the tracker with kills whose loot
 * never existed and whose drops belong to a different table entirely.
 *
 * ### The banner is a block, and this is line 2 of it
 * Hypixel wraps the line in a 14-line `▬▬▬` box — separator, DOWN! line, damage breakdown, closing
 * separator. Anything that sends chat in response must wait ~500 ms or the message lands *inside* the
 * box and fractures it. That delay is [DragonLegionAnnouncer]'s to apply, which is why this event
 * carries the tick it fired on.
 */
object DragonDeathDetector {
    private val logger = SoulLogger("Soul/DragonProfit")

    /** `<TYPE> DRAGON DOWN!` after strip + trim. Anchored — the banner is the whole line. */
    private val DEATH_REGEX = Regex("^(PROTECTOR|OLD|WISE|UNSTABLE|YOUNG|STRONG|SUPERIOR) DRAGON DOWN!$")

    @Volatile
    var clientTick: Int = 0
        private set

    fun register() {
        Events.subscribe(this)
    }

    /** Called from [DragonTick], the feature's one tick handler, so this file owns no second one. */
    internal fun advanceTick() {
        clientTick++
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (!cfg.combat.dragons.enabled) return
        if (!SkyblockLocation.isInSubLocation(DragonsNest.SUBLOCATION)) return
        val clean = MessageDetector.stripColorCodes(event.raw).trim()
        val match = DEATH_REGEX.matchEntire(clean) ?: return
        val typeName = match.groupValues[1]
        val type = DragonType.byName(typeName) ?: run {
            // Hypixel adding an eighth dragon is a thing that could happen; saying so beats silence.
            logger.warn("Unrecognised dragon type in death banner: $typeName")
            return
        }
        Events.publish(DragonDied(type, clientTick))
    }
}
