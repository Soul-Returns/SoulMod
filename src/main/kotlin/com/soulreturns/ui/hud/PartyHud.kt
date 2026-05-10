package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.features.party.PartyManager
import com.soulreturns.features.party.PartyManager.PartyRole
import com.soulreturns.gui.lib.GuiLayoutApi
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.client.Minecraft

/**
 * Party HUD — pure view. Reads state directly from [PartyManager] (which owns party
 * tracking via chat parsing) and pushes a text block into [GuiLayoutApi].
 */
object PartyHud {
    private const val ELEMENT_ID = "party_info"

    fun register() {
        ClientTickEvents.END_CLIENT_TICK.register { client -> tick(client) }
    }

    private fun tick(client: Minecraft) {
        client.player ?: return
        val enabled = try {
            cfg.render.overlays.enablePartyOverlay()
        } catch (_: Exception) {
            return
        }

        if (!enabled) {
            // Hide but keep layout — defaults are not re-applied because layout already exists.
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

        // Everyone except the leader (members + moderators).
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
