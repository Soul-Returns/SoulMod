package com.soulreturns.features.dungeons.boss

import com.soulreturns.api.LocationApi
import com.soulreturns.config.cfg
import com.soulreturns.features.dungeons.devices.GoldorPhase
import com.soulreturns.ui.hud.alert.Alerts
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents
import net.minecraft.client.Minecraft

/**
 * F7/M7 Goldor phase: a persistent warning (the shared [Alerts], live text) while the player stands in a section that
 * is not open yet, until it really begins ([GoldorSections]). Section 1 opens at Goldor's phase start T ([GoldorPhase]),
 * up to 13 ticks before `Who dares` on M7. Its sound plays once per open section, not on every step back across the
 * line. Display only.
 */
object SectionWarning {
    private val sections get() = GoldorPhase.sections
    private var soundFor = 0

    fun register() {
        ClientPlayConnectionEvents.JOIN.register { _, _, _ -> reset() }
        Alerts.showWhileLive(
            "goldor_section_ahead",
            title = { "Section ${ahead() ?: 0} is not open" },
            subtitle = { status() },
            priority = 5,
            sound = {
                if (soundFor == sections.open) {
                    null
                } else {
                    soundFor = sections.open
                    cfg.dungeons.boss.sectionWarningSound
                }
            },
            enabled = { cfg.dungeons.boss.sectionWarning },
            avoidBossTimers = true,
        ) { LocationApi.inDungeon && ahead() != null }
    }

    private fun reset() {
        soundFor = 0
    }

    private fun ahead(): Int? {
        val p = Minecraft.getInstance().player ?: return null
        return sections.ahead(p.x, p.y, p.z)
    }

    /** `Section 2 · 5/8 · gate up`: what the open section still needs. */
    private fun status(): String = statusOf(sections)

    fun statusOf(s: GoldorSections): String {
        val counter = s.done?.let { d -> s.of?.let { "$d/$it" } } ?: "0 done"
        val gate = if (s.open >= 4) "" else if (s.gateDown) " · gate down" else " · gate up"
        return "Section ${s.open} · $counter$gate"
    }
}
