package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.features.party.PartyManager
import com.soulreturns.features.party.PartyManager.PartyRole
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme

/**
 * Party HUD — reads state directly from [PartyManager] (which owns party tracking via chat
 * parsing) and renders the leader / size / members / pending invites as a Soul UI panel.
 *
 * Migrated from legacy `GuiLayoutApi.updateTextBlock` to [SoulHud.register] per the P3
 * framework migration (`docs/ui-framework-roadmap.md`).
 */
object PartyHud {
    private const val HUD_ID = "party_info"

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 260,
            height = 200,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.40,
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        val enabled =
            try {
                cfg.render.overlays.enablePartyOverlay()
            } catch (_: Exception) {
                false
            }
        if (!enabled) {
            Box {}
            return
        }

        val state = PartyManager.getPartyState()
        Surface {
            Column(gap = 4f) {
                Text(
                    text = "Party",
                    size = SoulTheme.typography.heading.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.heading.font,
                )

                if (state == null) {
                    Text(
                        text = "Not in a party",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.body.font,
                    )
                    return@Column
                }

                val leaderDisplay = state.leader?.displayName ?: state.leader?.name ?: "Unknown"
                Text(
                    text = "Leader: $leaderDisplay",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.body.font,
                )
                Text(
                    text = "Size: ${state.size}",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )

                val memberNames =
                    state.members.values
                        .filter { it.role != PartyRole.LEADER }
                        .joinToString(", ") { it.displayName }
                if (memberNames.isNotBlank()) {
                    Text(
                        text = "Members:",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.body.font,
                    )
                    Text(
                        text = memberNames,
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.text,
                        font = SoulTheme.typography.body.font,
                    )
                }

                val invites = PartyManager.getPendingInvites()
                if (invites.isNotEmpty()) {
                    val now = System.currentTimeMillis()
                    Text(
                        text = "Invites:",
                        size = SoulTheme.typography.body.size,
                        color = SoulTheme.colors.textDim,
                        font = SoulTheme.typography.body.font,
                    )
                    invites.take(3).forEach { invite ->
                        val secondsLeft = ((invite.expiresAt - now) / 1000L).coerceAtLeast(0)
                        val dir = if (invite.outgoing) "->" else "<-"
                        val other = if (invite.outgoing) invite.to else invite.from
                        Text(
                            text = "$dir $other (${secondsLeft}s)",
                            size = SoulTheme.typography.body.size,
                            color = SoulTheme.colors.text,
                            font = SoulTheme.typography.body.font,
                        )
                    }
                }
            }
        }
    }
}
