package com.soulreturns.features.notifications

import com.soulreturns.config.cfg
import com.soulreturns.core.events.Events
import com.soulreturns.core.events.HandleEvent
import com.soulreturns.data.model.ChatMessage
import com.soulreturns.util.MessageDetector
import com.soulreturns.util.RenderUtils

/**
 * Subscribes to [ChatMessage] events and renders [RenderUtils.showAlert] when an incoming
 * line matches one of the configured [Rule]s.
 *
 * Currently the rule list is hardcoded. A future revision will surface a `/soul chatNotifications`
 * GUI for users to add/edit rules at runtime; rules will move into a dedicated persistence file
 * (likely `config/soul/chat-rules.json`) once that lands.
 */
object ChatNotifications {
    /**
     * A single chat→alert mapping.
     *
     * - [pattern] is matched against the **color-stripped** message text. Use capture groups for
     *   anything you want to interpolate.
     * - [format] receives the [MatchResult] and returns the user-facing alert string.
     */
    private data class Rule(
        val id: String,
        val pattern: Regex,
        val format: (MatchResult) -> String,
        val color: Int,
        val scale: Float = 2.0f,
        val durationMs: Long = 4_000L,
    )

    private val rules: List<Rule> =
        listOf(
            Rule(
                id = "garden.pestSpawn",
                // Raw chat:    "§6§lEWW! §22 §2ൠ Pest §7have spawned in §aPlot §7- §b13§7!"
                // Stripped:    "EWW! 2 ൠ Pest have spawned in Plot - 13!"
                // Hypixel uses singular "Pest" even for counts > 1, but the regex tolerates either.
                pattern = Regex("EWW! (\\d+)\\s*ൠ\\s*Pest(?:s)?\\s*have spawned in Plot\\s*-\\s*\\d+"),
                format = { match ->
                    val count = match.groupValues[1].toIntOrNull() ?: 0
                    val word = if (count == 1) "Pest" else "Pests"
                    "$count $word spawned"
                },
                color = 0xFFFF8800.toInt(),
            ),
        )

    fun register() {
        Events.subscribe(this)
    }

    @HandleEvent
    fun onChat(event: ChatMessage) {
        if (!cfg.notifications.chatAlerts()) return
        val stripped = MessageDetector.stripColorCodes(event.raw)
        for (rule in rules) {
            val match = rule.pattern.find(stripped) ?: continue
            RenderUtils.showAlert(rule.format(match), rule.color, rule.scale, rule.durationMs)
            return
        }
    }
}
