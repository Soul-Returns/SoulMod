package com.soulreturns.features.party

import com.soulreturns.config.cfg
import com.soulreturns.features.party.PartyManager.PartyRole
import com.soulreturns.gui.lib.GuiLayoutApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.MinecraftClient

/**
 * Simple on-screen overlay that shows current party information using the
 * generic GUI layout system (TextBlockElement).
 */
object PartyHudOverlay {
    private const val ELEMENT_ID = "party_info"

    fun register() {
        // Keep party state updated every client tick and refresh the HUD text.
        ClientTickEvents.END_CLIENT_TICK.register { client ->
            tick(client)
        }
    }

    private fun tick(client: MinecraftClient) {
        client.player ?: return
        val enabled = try {
            cfg.render.overlays.enablePartyOverlay()
        } catch (_: Exception) {
            return
        }

        // If overlay is disabled, hide the element but keep any saved layout.
        if (!enabled) {
            GuiLayoutApi.updateTextBlock(
                id = ELEMENT_ID,
                enabled = false,
                title = "Party",
                lines = emptyList(),
                defaultAnchorX = 0.02,
                defaultAnchorY = 0.40,
                defaultScale = 1.0f,
            )
            return
        }

        val state = PartyManager.getPartyState()

        if (state == null) {
            // Optionally show a small hint when not in a party.
            GuiLayoutApi.updateTextBlock(
                id = ELEMENT_ID,
                enabled = true,
                title = "Party",
                lines = listOf("Not in a party"),
                color = 0xFFFFFFFF.toInt(),
                defaultAnchorX = 0.02,
                defaultAnchorY = 0.40,
                defaultScale = 1.0f,
            )
            return
        }

        val leaderDisplay = state.leader?.displayName ?: state.leader?.name ?: "Unknown"
        val members = state.members.values

        // Show everyone except the leader (members + moderators)
        val memberNames = members
            .filter { it.role != PartyRole.LEADER }
            .joinToString(", ") { it.displayName }

        val lines = mutableListOf<String>()
        lines += "Leader: $leaderDisplay"
        lines += "Size: ${state.size}"
        if (memberNames.isNotBlank()) {
            lines += "Members:"
            lines += memberNames
        }

        val invites = PartyManager.getPendingInvites()
        val now = System.currentTimeMillis()
        if (invites.isNotEmpty()) {
            lines += "Invites:"
            invites.take(3).forEach { invite ->
                val secondsLeft = ((invite.expiresAt - now) / 1000L).coerceAtLeast(0)
                val dir = if (invite.outgoing) "->" else "<-"
                val other = if (invite.outgoing) invite.to else invite.from
                lines += "$dir $other (${secondsLeft}s)"
            }
        }

        GuiLayoutApi.updateTextBlock(
            id = ELEMENT_ID,
            enabled = true,
            title = "Party",
            lines = lines,
            color = 0xFFFFFFFF.toInt(),
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.40,
            defaultScale = 1.0f,
        )
    }
}
