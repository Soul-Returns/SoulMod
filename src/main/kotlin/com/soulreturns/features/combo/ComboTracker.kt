package com.soulreturns.features.combo

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.ChatDisplayFilter
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.soulChat
import net.minecraft.network.chat.Component

/**
 * Detects the SkyBlock kill combo (Grandma Wolf Pet perk) from chat and drives [ComboHud].
 *
 * Ported from the SBCombo ChatTriggers module: three chat patterns — a milestone with a bonus
 * (`+N Kill Combo +X …`), a bare milestone past the bonus range (`+N Kill Combo`), and the expiry
 * line (`Your Kill Combo has expired! You reached a N Kill Combo!`). Detection runs off the chat
 * event pipeline; **hiding** the vanilla lines is display-only via [ChatDisplayFilter] (registered
 * here) so other mods still receive them.
 */
object ComboTracker {
    private val MILESTONE = Regex("^\\+(\\d+) Kill Combo")
    private val EXPIRE = Regex("Your Kill Combo has expired! You reached a (\\d+) Kill Combo!")

    @Volatile
    private var combo: Int = 0

    @Volatile
    private var lastUpdateMs: Long = 0L

    /** Combo count to display, or 0 when there's nothing to show (expired / decayed / never started). */
    fun displayCombo(): Int {
        val c = combo
        if (c < 5) return 0
        val decay = cfg.combo.decaySeconds
        if (decay > 0f && System.currentTimeMillis() - lastUpdateMs > (decay * 1000f).toLong()) return 0
        return c
    }

    /**
     * Estimated seconds until the current combo expires (clamped to 0). Assumes a **level-100 pet**
     * (the tier durations are the level-100 values). Accuracy depends on the kill signal: with a
     * kill-counter item in the inventory ([ComboKillCounter]) the anchor refreshes on every kill, so
     * this is exact; without one it can only re-anchor on the every-5-kills milestone line, so it
     * over-counts elapsed time between milestones. Returns 0 when no combo is active.
     */
    fun secondsUntilExpire(): Float {
        val c = combo
        if (c < 5) return 0f
        val elapsed = (System.currentTimeMillis() - lastUpdateMs) / 1000f
        return (ComboData.tierFor(c).durationSeconds - elapsed).coerceAtLeast(0f)
    }

    /**
     * A kill was detected by the inventory counter ([ComboKillCounter]) — advance the exact live combo
     * count by [kills] and re-anchor the expiration countdown (every kill refreshes the real timer).
     * The counter is the source of truth; the count is never capped here — the chat milestones only
     * ever raise a floor ([applyFloor]), never add a kill. Guarded to start once the first `+5`
     * milestone has bootstrapped the count (below 5 the overlay is hidden anyway).
     */
    fun onKillDetected(kills: Int) {
        if (kills <= 0 || combo < 5) return
        combo += kills
        lastUpdateMs = System.currentTimeMillis()
    }

    /**
     * Raise the count to a milestone value [n] the chat announced, but only if the counter is behind
     * (e.g. counter at 28 when the chat says `+30`). Never lowers the count, and is a no-op when the
     * counter already reached the milestone — so the kill that triggered the milestone is never
     * double-counted. Applied by [ComboKillCounter] after a short grace so a merely-lagging counter
     * catches up first. Also bootstraps the very first milestone (0 → 5).
     */
    fun applyFloor(n: Int) {
        if (combo < n) combo = n
    }

    fun register() {
        Events.subscribe<ChatMessage> { onChat(it.raw) }
        ChatDisplayFilter.register(::shouldHide)
    }

    private fun onChat(raw: String) {
        if (!cfg.combo.enabled) return
        val clean = MessageDetector.stripColorCodes(raw).trim()
        EXPIRE.find(clean)?.let { m ->
            onExpire(m.groupValues[1].toIntOrNull() ?: 0)
            return
        }
        MILESTONE.find(clean)?.let { m ->
            val n = m.groupValues[1].toIntOrNull() ?: return
            lastUpdateMs = System.currentTimeMillis()
            if (ComboKillCounter.hasCounter()) {
                // A kill counter is driving the exact count — use this milestone only as a delayed
                // floor, so the kill that triggered it isn't counted twice (once by chat, once by NBT).
                ComboKillCounter.setFloor(n)
            } else {
                combo = n // no counter: the milestone chat is the only signal, so it sets the count
            }
        }
    }

    private fun onExpire(reached: Int) {
        combo = 0
        lastUpdateMs = 0L
        ComboKillCounter.cancelFloor() // drop any milestone floor waiting to apply — the combo is over
        if (cfg.combo.trackBest && reached > cfg.combo.bestCombo) {
            val old = cfg.combo.bestCombo
            ConfigManager.update { copy(combo = combo.copy(bestCombo = reached)) }
            soulChat("§aNew best kill combo: §d$reached §a(was §6$old§a)")
        }
    }

    /** Display-only suppression of the vanilla combo/expiry lines, gated on the config toggles. */
    private fun shouldHide(message: Component): Boolean {
        if (!cfg.combo.enabled) return false
        val clean = MessageDetector.stripColorCodes(message.string).trim()
        if (EXPIRE.containsMatchIn(clean)) return cfg.combo.hideExpireMessage
        if (MILESTONE.containsMatchIn(clean)) return cfg.combo.hideComboMessages
        return false
    }
}
