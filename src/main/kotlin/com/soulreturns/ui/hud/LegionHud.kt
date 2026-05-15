package com.soulreturns.ui.hud

import com.soulreturns.config.cfg
import com.soulreturns.gui.lib.HudHorizontalAnchor
import com.soulreturns.ui.composer.SoulComposable
import com.soulreturns.ui.foundation.Box
import com.soulreturns.ui.foundation.Column
import com.soulreturns.ui.foundation.Surface
import com.soulreturns.ui.foundation.Text
import com.soulreturns.ui.runtime.SoulHud
import com.soulreturns.ui.theme.SoulTheme
import com.soulreturns.util.SkyblockItemUtils
import net.minecraft.client.Minecraft
import net.minecraft.world.entity.player.Player
import java.util.Locale

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

    /** Skyblock enchant id that enables this HUD when present on any armor piece. */
    private const val ENCHANT_ID = "ultimate_legion"

    /** Vanilla `§d` light_purple — matches the in-game tooltip color of the Legion enchant. */
    private const val COLOR_LIGHT_PURPLE = 0xFFFF55FF.toInt()

    /**
     * Bonus multiplier per enchant level — see SkyHanni `LegionBobbinOverlay.kt`. Final boost
     * = level × [BOOST_PER_LEVEL] × min(nearbyPlayers, [PLAYER_CAP]).
     */
    private const val BOOST_PER_LEVEL = 0.07
    private const val PLAYER_CAP = 20

    fun register() {
        SoulHud.register(
            id = HUD_ID,
            width = 180,
            height = 60,
            // Top-center. Shares its default slot with Bobbin Time — only one is enabled at
            // a time (their enchant gates are mutually exclusive in practice), so reusing
            // the position keeps the default HUD layout uncluttered. `horizontalAnchor =
            // Center` makes `dispatchAll` pivot on the actual rendered width so the panel
            // sits dead-center regardless of screen resolution or GUI scale. `anchorY =
            // 0.08` (≈ 8 % of screen height) leaves room above for the vanilla boss bar.
            defaultAnchorX = 0.5,
            defaultAnchorY = 0.08,
            defaultHorizontalAnchor = HudHorizontalAnchor.Center,
            settingsCategory = "render",
            settingsSubcategory = "overlays",
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
        val player = Minecraft.getInstance().player
        if (player == null) {
            Box {}
            return
        }
        val enchantLevel = SkyblockItemUtils.highestArmorEnchantLevel(player, ENCHANT_ID)
        if (enchantLevel <= 0) {
            // HUD only renders when the player is actually wearing the Legion enchant.
            Box {}
            return
        }
        val count = countNearbyPlayers()
        if (count == null) {
            Box {}
            return
        }
        val cappedCount = count.coerceAtMost(PLAYER_CAP)
        val boostPercent = enchantLevel * BOOST_PER_LEVEL * cappedCount
        Surface {
            Column(gap = 4f) {
                Text(
                    text = "Legion",
                    size = SoulTheme.typography.heading.size,
                    color = COLOR_LIGHT_PURPLE,
                    font = SoulTheme.typography.heading.font,
                )
                Text(
                    text =
                        String.format(
                            Locale.ROOT,
                            "Nearby players: %d (%.2f%%)",
                            count,
                            boostPercent,
                        ),
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
