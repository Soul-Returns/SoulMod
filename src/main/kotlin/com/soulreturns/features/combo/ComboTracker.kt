package com.soulreturns.features.combo

import com.soulreturns.config.ConfigManager
import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.stats.PlayerDataset
import com.soulreturns.util.ChatDisplayFilter
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.soulChat
import net.minecraft.network.chat.Component

/**
 * Detects the SkyBlock kill combo (Grandma Wolf Pet perk) from chat and drives [ComboHud].
 *
 * Ported from the SBCombo ChatTriggers module: chat patterns for a milestone with its bonus
 * (`+N Kill Combo +4☯ Combat Wisdom`), a bare milestone and the expiry line
 * (`Your Kill Combo has expired! You reached a N Kill Combo!`), parsed by [ComboChat]. Bonuses are read
 * from each milestone line and stacked in [bonuses]; nothing about them is hard-coded. Detection runs off the chat
 * event pipeline; **hiding** the vanilla lines is display-only via [ChatDisplayFilter] (registered
 * here) so other mods still receive them.
 */
object ComboTracker {
    /** Bonuses stacked over the running combo; reset when it expires. */
    val bonuses = ComboRun()

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
     * Estimated seconds until the current combo expires (clamped to 0). The duration per milestone is
     * the pet's own, learned from its lore ([ComboDurations]), else the level-100 table. Accuracy depends on the kill signal: with a
     * kill-counter item in the inventory ([ComboKillCounter]) the anchor refreshes on every kill, so
     * this is exact; without one it can only re-anchor on the every-5-kills milestone line, so it
     * over-counts elapsed time between milestones. Returns 0 when no combo is active.
     */
    fun secondsUntilExpire(): Float {
        val c = combo
        if (c < 5) return 0f
        val elapsed = (System.currentTimeMillis() - lastUpdateMs) / 1000f
        return (ComboDurations.seconds(c) - elapsed).coerceAtLeast(0f)
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
        when (val line = ComboChat.parse(clean)) {
            is ComboChat.Expired -> onExpire(line.reached)
            is ComboChat.Milestone -> onMilestone(line)
            null -> Unit
        }
    }

    private fun onMilestone(line: ComboChat.Milestone) {
        val n = line.count
        lastUpdateMs = System.currentTimeMillis()
        bonuses.onMilestone(n, line.bonuses)
        if (ComboKillCounter.hasCounter()) {
            // A kill counter is driving the exact count — use this milestone only as a delayed
            // floor, so the kill that triggered it isn't counted twice (once by chat, once by NBT).
            ComboKillCounter.setFloor(n)
        } else {
            combo = n // no counter: the milestone chat is the only signal, so it sets the count
        }
    }

    private fun onExpire(reached: Int) {
        combo = 0
        lastUpdateMs = 0L
        bonuses.reset()
        ComboKillCounter.cancelFloor() // drop any milestone floor waiting to apply — the combo is over
        // config.json is shared by both datasets: an Alpha (or not yet confirmed) combo is no record.
        if (cfg.combo.trackBest && reached > cfg.combo.bestCombo && PlayerDataset.confirmedProduction()) {
            val old = cfg.combo.bestCombo
            ConfigManager.update { copy(combo = combo.copy(bestCombo = reached)) }
            soulChat("§aNew best kill combo: §d$reached §a(was §6$old§a)")
        }
    }

    /** Display-only suppression of the vanilla combo/expiry lines, gated on the config toggles. */
    private fun shouldHide(message: Component): Boolean {
        if (!cfg.combo.enabled) return false
        val clean = MessageDetector.stripColorCodes(message.string).trim()
        if (ComboChat.isExpiry(clean)) return cfg.combo.hideExpireMessage
        if (ComboChat.isMilestone(clean)) return cfg.combo.hideComboMessages
        return false
    }
}
