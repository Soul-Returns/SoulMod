package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player

/**
 * Legion HUD — counts other real players within a 30-block radius and exposes a
 * positionable Soul UI panel.
 *
 * No persistent state: the count is recomputed each frame inside the composable. Pure
 * view; nothing else in the codebase reads "legion count," so no separate state object.
 *
 * Migrated from legacy [com.soulreturns.gui.lib.GuiLayoutApi.updateTextBlock] to
 * [SoulHud.register] as part of the P3 framework migration. See
 * `docs/ui-framework-roadmap.md`.
 */
object LegionHud {
    private const val HUD_ID = "legion_counter"
    private const val RADIUS = 30.0
    private const val RADIUS_SQ = RADIUS * RADIUS

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 180,
            height = 60,
            defaultAnchorX = 0.02,
            defaultAnchorY = 0.3,
        ) {
            Content()
        }
    }

    @SoulComposable
    private fun Content() {
        // Visibility: feature toggle off, or no world loaded → compose an empty root.
        // SoulComposer.build requires exactly one root node, so we can't `return` blank —
        // a zero-size [Box] is the canonical "render nothing" placeholder.
        if (!cfg.render.overlays.enableLegionCounter()) {
            Box {}
            return
        }
        val count = countNearbyPlayers()
        if (count == null) {
            Box {}
            return
        }
        Surface {
            Column(gap = 4f) {
                Text(
                    text = "Legion",
                    size = SoulTheme.typography.heading.size,
                    color = SoulTheme.colors.text,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text = "Nearby players: $count",
                    size = SoulTheme.typography.body.size,
                    color = SoulTheme.colors.textDim,
                    font = SoulTheme.typography.body.font,
                )
            }
        }
    }

    private fun countNearbyPlayers(): Int? {
        val mc = Minecraft.getInstance()
        val player = mc.player ?: return null
        val world = mc.level ?: return null
        // Hypixel SkyBlock NPCs typically have non-v4 UUIDs; filtering by UUID version
        // gives a count that closely matches Legion stacks.
        return world.players().count { other ->
            other !== player &&
                other.isRealPlayer() &&
                player.distanceToSqr(other) <= RADIUS_SQ
        }
    }

    private fun Player.isRealPlayer(): Boolean = uuid?.let { it.version() == 4 } ?: false
}
