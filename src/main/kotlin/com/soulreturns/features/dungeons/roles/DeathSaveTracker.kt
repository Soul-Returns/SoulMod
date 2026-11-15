package com.soulreturns.features.dungeons.roles

import com.soulreturns.util.MessageDetector
import kotlin.math.ceil

/** Cooldowns come only from this actual item's lore after its own observed proc, on the shared server clock. */
class DeathSaveTracker {
    class Save(val label: String) {
        var active: Boolean? = null
            internal set
        var identity: String? = null
            internal set
        var cooldownTicks: Long? = null
            internal set
        var consumedAt: Long? = null
            internal set
        internal var anchorValid = false
        /** Null means unobserved readiness, not "spent" or "ready by ownership". */
        var available: Boolean? = null
            internal set
    }

    val spirit = Save("Spirit Mask")
    val bonzo = Save("Bonzo's Mask")
    val phoenix = Save("Phoenix")
    private val saves = arrayOf(spirit, bonzo, phoenix)
    private var now: Long? = null
    private var fresh = false

    fun reset() {
        now = null
        fresh = false
        for (save in saves) {
            save.active = null
            save.identity = null
            save.cooldownTicks = null
            save.consumedAt = null
            save.anchorValid = false
            save.available = null
        }
    }

    fun observe(source: Int, active: Boolean?, identity: String?, cooldownTicks: Long?) {
        val save = saves[source]
        if (save.identity != identity || save.cooldownTicks != cooldownTicks) save.anchorValid = false
        save.active = active
        save.identity = identity
        save.cooldownTicks = cooldownTicks?.takeIf { it > 0 }
        update(save)
    }

    fun advance(tick: Long?, clockFresh: Boolean) {
        val previous = now
        if (tick == null || (previous != null && tick < previous)) {
            for (save in saves) save.anchorValid = false
        }
        now = tick
        fresh = clockFresh
        for (save in saves) update(save)
    }

    fun chat(line: String, tick: Long): Boolean {
        if (MessageDetector.isPlayerMessage(line)) return false
        val save = when {
            line == "Second Wind Activated! Your Spirit Mask saved your life!" -> spirit
            BONZO_PROC.matches(line) -> bonzo
            line == "Your Phoenix Pet saved you from certain death!" -> phoenix
            else -> return false
        }
        save.consumedAt = tick
        save.anchorValid = save !== phoenix && save.active == true && save.identity != null && save.cooldownTicks != null && now != null && fresh
        update(save)
        return true
    }

    private fun update(save: Save) {
        val t = now
        val proc = save.consumedAt
        val cooldown = save.cooldownTicks
        save.available = if (!fresh || !save.anchorValid || t == null || proc == null || cooldown == null || t < proc) null else t - proc >= cooldown
    }

    fun status(): Int {
        var swap = false
        var spent = false
        var unknown = false
        for (save in saves) {
            if (save.available == true) {
                if (save.active == true) return USABLE
                swap = true
            } else if (save.available == false) spent = true
            else if (save.identity != null || save.active != false) unknown = true
        }
        return if (swap) SWAP else if (unknown) UNKNOWN else if (spent) SPENT else UNKNOWN
    }

    companion object {
        const val SPIRIT = 0
        const val BONZO = 1
        const val PHOENIX = 2
        const val UNKNOWN = 0
        const val SPENT = 1
        const val SWAP = 2
        const val USABLE = 3
        private val BONZO_PROC = Regex("^Your (?:[\\uE000-\\uF8FF] )?Bonzo's Mask saved your life!$")
        private val COOLDOWN = Regex("^Cooldown: ([0-9]+(?:\\.[0-9]+)?)s$")

        fun advertisedCooldown(line: String): Long? {
            val seconds = COOLDOWN.matchEntire(MessageDetector.stripColorCodes(line).trim())?.groupValues?.get(1)?.toDoubleOrNull() ?: return null
            return ceil(seconds * 20.0).toLong().takeIf { it > 0 }
        }
    }
}
